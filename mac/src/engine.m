// SPDX-License-Identifier: GPL-3.0-or-later
// Tab Cast for Mac: the cast engine (see engine.h). Objective-C for ScreenCaptureKit and the Core Audio tap API,
// which have no C interface; the link and the send queue are C (link.c, sendq.c).

#import "engine.h"
#import <AppKit/AppKit.h>
#import <CoreAudio/CoreAudio.h>
#import <CoreAudio/AudioHardwareTapping.h>
#import <CoreAudio/CATapDescription.h>
#import <VideoToolbox/VideoToolbox.h>
#include <mach/mach_time.h>
#include <stdatomic.h>
#include "sendq.h"

// ------------------------------------------------------------------------------------------------ virtual display
// CGVirtualDisplay: CoreGraphics, undocumented (also used by BetterDisplay, DeskPad and Chromium's tests). Looked up
// at run time: without it the second-screen mode says so instead of failing to start.

@interface CGVirtualDisplayDescriptor : NSObject
@property(retain, nonatomic) dispatch_queue_t queue;
@property(retain, nonatomic) NSString *name;
@property(nonatomic) unsigned int maxPixelsHigh;
@property(nonatomic) unsigned int maxPixelsWide;
@property(nonatomic) CGSize sizeInMillimeters;
@property(nonatomic) unsigned int serialNum;
@property(nonatomic) unsigned int productID;
@property(nonatomic) unsigned int vendorID;
@property(copy, nonatomic) void (^terminationHandler)(id, id);
@end
@interface CGVirtualDisplayMode : NSObject
- (instancetype)initWithWidth:(unsigned int)width height:(unsigned int)height refreshRate:(double)refreshRate;
@end
@interface CGVirtualDisplaySettings : NSObject
@property(retain, nonatomic) NSArray *modes;
@property(nonatomic) unsigned int hiDPI;
@end
@interface CGVirtualDisplay : NSObject
- (instancetype)initWithDescriptor:(CGVirtualDisplayDescriptor *)descriptor;
- (BOOL)applySettings:(CGVirtualDisplaySettings *)settings;
@property(readonly, nonatomic) unsigned int displayID;
@end

// ------------------------------------------------------------------------------------------------ helpers

static int64_t hostMicros(uint64_t hostTime) {
    static mach_timebase_info_data_t tb;
    if (!tb.denom) mach_timebase_info(&tb);
    return (int64_t)(hostTime * tb.numer / tb.denom / 1000);
}

/** Fetches what macOS lets us capture (waits up to 2 s). */
static SCShareableContent *shareable(NSString **error) {
    __block SCShareableContent *c = nil;
    __block NSError *err = nil;
    dispatch_semaphore_t done = dispatch_semaphore_create(0);
    // All windows, not only the visible ones: a full-screen game sits in its own Space.
    [SCShareableContent getShareableContentExcludingDesktopWindows:YES onScreenWindowsOnly:NO
                                                 completionHandler:^(SCShareableContent *sc, NSError *e) {
        c = sc; err = e; dispatch_semaphore_signal(done);
    }];
    if (dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, 2 * NSEC_PER_SEC))) {
        if (error) *error = @"macOS didn't answer in time";
        return nil;
    }
    if (!c && error) *error = @"Tab Cast may not record the screen yet: allow it in System Settings › Privacy & Security › "
                              @"Screen & System Audio Recording, then try again";
    return c;
}

/** A pixel picture box at most 16:9 of the given height, aspect kept, even sizes. */
static void fitBox(double srcW, double srcH, int height, int *w, int *h) {
    double boxW = height * 16.0 / 9.0, boxH = height;
    double s = fmin(boxW / srcW, boxH / srcH);
    *w = ((int)(srcW * s)) & ~1;
    *h = ((int)(srcH * s)) & ~1;
}

// ------------------------------------------------------------------------------------------------ the engine

@interface TCEngine () <SCStreamOutput, SCStreamDelegate>
@end

@implementation TCEngine {
    TcLink *_link;
    SendQ *_q;
    SCStream *_stream;
    dispatch_queue_t _videoQueue, _audioQueue;
    VTCompressionSessionRef _session;
    BOOL _hevc;
    int _fps, _targetBps, _bps;
    _Atomic int _forceKey;
    _Atomic long _encoded, _bytes;
    CGVirtualDisplay *_virtual;
    // sound
    AudioObjectID _tap, _aggregate;
    AudioDeviceIOProcID _ioProc;
    BOOL _soundFromStream;
    double _soundRate;
    // status
    NSTimer *_tick;
    long _lastEncoded, _lastBytes, _lastBehind;
    int _cleanSeconds;
    CFTimeInterval _lastTick;
}

+ (NSArray<SCWindow *> *)castableWindowsError:(NSString **)error {
    SCShareableContent *c = shareable(error);
    if (!c) return nil;
    NSString *me = NSBundle.mainBundle.bundleIdentifier;
    NSMutableArray *out = [NSMutableArray array];
    for (SCWindow *w in c.windows) {
        if (w.windowLayer != 0 || w.frame.size.width < 320 || w.frame.size.height < 200) continue;
        if (!w.owningApplication.applicationName.length) continue;
        if ([w.owningApplication.bundleIdentifier isEqualToString:me]) continue;
        [out addObject:w];
    }
    [out sortUsingComparator:^NSComparisonResult(SCWindow *a, SCWindow *b) {
        double x = a.frame.size.width * a.frame.size.height, y = b.frame.size.width * b.frame.size.height;
        return x > y ? NSOrderedAscending : x < y ? NSOrderedDescending : NSOrderedSame;
    }];
    return out;
}

// ---------------------------------------------------------------------------- start / stop

- (BOOL)startOn:(TcLink *)link tvCodecs:(NSString *)tvCodecs mode:(TCMode)mode window:(SCWindow *)window
        quality:(TCQuality)quality muteMac:(BOOL)muteMac error:(NSString **)error {
    if (_casting) [self stop];
    NSString *err = nil;
    SCShareableContent *content = shareable(&err);
    if (!content) { *error = err; return NO; }

    int height = quality == TCQualityLight ? 720 : 1080;
    _fps = quality == TCQualityFilm ? 60 : 30;
    _targetBps = quality == TCQualityFilm ? 12000000 : quality == TCQualitySmooth ? 8000000 : 5000000;

    SCContentFilter *filter = nil;
    double srcW = 0, srcH = 0;
    pid_t soundPid = 0;          // 0: everything
    NSString *soundBundle = nil; // the app and its helpers (a browser's sound comes from a helper process)
    if (mode == TCModeWindow) {
        if (!window) { *error = @"Choose a window to cast"; return NO; }
        filter = [[SCContentFilter alloc] initWithDesktopIndependentWindow:window];
        srcW = window.frame.size.width; srcH = window.frame.size.height;
        soundPid = window.owningApplication.processID;
        soundBundle = window.owningApplication.bundleIdentifier;
        _what = window.owningApplication.applicationName;
    } else if (mode == TCModeScreen) {
        SCDisplay *d = content.displays.firstObject;
        for (SCDisplay *x in content.displays) if (x.displayID == CGMainDisplayID()) d = x;
        if (!d) { *error = @"No screen to cast"; return NO; }
        filter = [[SCContentFilter alloc] initWithDisplay:d excludingWindows:@[]];
        srcW = d.width; srcH = d.height;
        _what = @"the screen";
    } else {
        CGDirectDisplayID vid = [self makeVirtualDisplay];
        if (!vid) { *error = @"The TV screen didn't come up on this Mac: cast a window or the screen instead"; return NO; }
        SCDisplay *vd = nil;
        for (int i = 0; i < 40 && !vd; i++) { // it takes a moment to appear
            [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.1]];
            for (SCDisplay *d in shareable(NULL).displays) if (d.displayID == vid) vd = d;
        }
        if (!vd) { _virtual = nil; *error = @"The TV screen didn't come up: try again"; return NO; }
        filter = [[SCContentFilter alloc] initWithDisplay:vd excludingWindows:@[]];
        srcW = vd.width; srcH = vd.height;
        _tvDisplay = vid;
        _what = @"the TV screen";
    }
    int w, h;
    fitBox(srcW, srcH, height, &w, &h);

    // HEVC when the TV decodes it and this Mac encodes it in hardware; else H.264.
    BOOL tvHevc = [tvCodecs containsString:@"hevc"], tvH264 = [tvCodecs containsString:@"h264"];
    if (!(tvHevc || tvH264)) { [self dropVirtual]; *error = @"This Tab Cast TV is too old for the Mac: update it on the TV"; return NO; }
    if (!(tvHevc && [self makeEncoder:YES width:w height:h]) && !(tvH264 && [self makeEncoder:NO width:w height:h])) {
        [self dropVirtual]; *error = @"This Mac has no hardware video encoder Tab Cast can use"; return NO;
    }

    _link = link;
    _q = sq_new(sendOnLink, behind, (__bridge void *)self, 1500000, 50); // ~1 s of picture at 12 Mbit/s; ~1 s of sound
    atomic_store(&_encoded, 0); atomic_store(&_bytes, 0);
    _lastEncoded = _lastBytes = _lastBehind = 0; _cleanSeconds = 0;

    // Sound first (its format goes in AUDIO_CONFIG).
    _soundFromStream = ![self startTapForPid:soundPid bundle:soundBundle mute:muteMac];

    SCStreamConfiguration *cfg = [[SCStreamConfiguration alloc] init];
    cfg.width = w; cfg.height = h;
    cfg.minimumFrameInterval = CMTimeMake(1, _fps);
    cfg.pixelFormat = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange; // what the encoder takes without converting
    cfg.queueDepth = 6;
    cfg.showsCursor = YES;
    cfg.scalesToFit = YES;
    if (_soundFromStream) {
        cfg.capturesAudio = YES; cfg.sampleRate = 48000; cfg.channelCount = 2; cfg.excludesCurrentProcessAudio = YES;
        _soundRate = 48000;
    }
    _stream = [[SCStream alloc] initWithFilter:filter configuration:cfg delegate:self];
    _videoQueue = dispatch_queue_create("tabcast.video", DISPATCH_QUEUE_SERIAL);
    _audioQueue = dispatch_queue_create("tabcast.audio", DISPATCH_QUEUE_SERIAL);
    NSError *e = nil;
    if (![_stream addStreamOutput:self type:SCStreamOutputTypeScreen sampleHandlerQueue:_videoQueue error:&e] ||
        (_soundFromStream && ![_stream addStreamOutput:self type:SCStreamOutputTypeAudio sampleHandlerQueue:_audioQueue error:&e])) {
        [self teardown]; *error = [NSString stringWithFormat:@"Can't capture: %@", e.localizedDescription]; return NO;
    }

    char cfgv[200];
    snprintf(cfgv, sizeof cfgv, "codec=%s\nw=%d\nh=%d\nsource=mac", _hevc ? "hevc" : "h264", w, h);
    sq_control(_q, TC_VIDEO_CONFIG, cfgv, strlen(cfgv));
    snprintf(cfgv, sizeof cfgv, "codec=pcm_s16le\nrate=%d\nchannels=2", (int)_soundRate);
    sq_control(_q, TC_AUDIO_CONFIG, cfgv, strlen(cfgv));

    dispatch_semaphore_t started = dispatch_semaphore_create(0);
    __block NSError *startErr = nil;
    [_stream startCaptureWithCompletionHandler:^(NSError *x) { startErr = x; dispatch_semaphore_signal(started); }];
    dispatch_semaphore_wait(started, dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC));
    if (startErr) { [self teardown]; *error = [NSString stringWithFormat:@"Capture didn't start: %@", startErr.localizedDescription]; return NO; }

    _casting = YES;
    _lastTick = ((double)clock_gettime_nsec_np(CLOCK_UPTIME_RAW) / 1e9);
    _tick = [NSTimer scheduledTimerWithTimeInterval:1.0 target:self selector:@selector(tick) userInfo:nil repeats:YES];
    NSLog(@"Tab Cast: casting %@ %dx%d %@ %d fps %.1f Mbit/s, sound %@", _what, w, h, _hevc ? @"HEVC" : @"H.264", _fps,
          _bps / 1e6, _soundFromStream ? @"from the stream (not muted)" : (muteMac ? @"tapped, muted on the Mac" : @"tapped"));
    return YES;
}

- (void)stop {
    if (!_casting && !_q) return;
    if (_q) sq_control(_q, TC_STATE, "mirror=off", 10);
    [self teardown];
}

- (void)teardown {
    _casting = NO;
    [_tick invalidate]; _tick = nil;
    if (_stream) {
        dispatch_semaphore_t s = dispatch_semaphore_create(0);
        [_stream stopCaptureWithCompletionHandler:^(NSError *e) { (void)e; dispatch_semaphore_signal(s); }];
        dispatch_semaphore_wait(s, dispatch_time(DISPATCH_TIME_NOW, 3 * NSEC_PER_SEC));
        _stream = nil;
    }
    [self stopTap];
    if (_session) { VTCompressionSessionCompleteFrames(_session, kCMTimeInvalid); VTCompressionSessionInvalidate(_session); CFRelease(_session); _session = NULL; }
    if (_q) { sq_free(_q); _q = NULL; } // sends what is left (the TV's "mirror=off" first)
    _link = NULL;
    [self dropVirtual];
}

- (void)dropVirtual { _virtual = nil; _tvDisplay = 0; }

- (void)keyFrameWanted { atomic_store(&_forceKey, 1); }

// ---------------------------------------------------------------------------- status and bitrate (once a second)

- (void)tick {
    if (!_casting) return;
    SqStats s = sq_stats(_q);
    if (s.broken || !tc_alive(_link)) { [self teardown]; if (self.onEnded) self.onEnded(@"The TV went away"); return; }
    CFTimeInterval now = ((double)clock_gettime_nsec_np(CLOCK_UPTIME_RAW) / 1e9), dt = now - _lastTick;
    _lastTick = now;
    long enc = atomic_load(&_encoded), by = atomic_load(&_bytes);
    double fps = (enc - _lastEncoded) / dt, mbps = (by - _lastBytes) * 8 / 1e6 / dt;
    _lastEncoded = enc; _lastBytes = by;
    double rtt = tc_rtt_ms(_link);
    // The bitrate follows the network: down a quarter when the picture fell behind or the round trip grew, back up
    // slowly after 5 clean seconds.
    BOOL trouble = s.behind > _lastBehind || rtt > 80;
    _lastBehind = s.behind;
    if (trouble) { _cleanSeconds = 0; [self setBitrate:MAX(3000000, _bps * 3 / 4)]; }
    else if (++_cleanSeconds >= 5 && _bps < _targetBps) { _cleanSeconds = 0; [self setBitrate:MIN(_targetBps, _bps * 11 / 10)]; }
    // Keep the link alive when the picture stands still.
    uint8_t b[8]; uint64_t t = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
    for (int i = 0; i < 8; i++) b[i] = (uint8_t)(t >> (56 - 8 * i));
    sq_control(_q, TC_PING, b, 8);
    NSString *line = [NSString stringWithFormat:@"Casting %@ · %.0f fps · %.1f Mbit/s · %@", _what, fps, mbps,
                      rtt >= 0 ? [NSString stringWithFormat:@"%.0f ms", rtt] : @"…"];
    if (self.onStatus) self.onStatus(line);
}

- (void)setBitrate:(int)bps {
    if (bps == _bps || !_session) return;
    _bps = bps;
    CFNumberRef n = CFNumberCreate(NULL, kCFNumberIntType, &bps);
    VTSessionSetProperty(_session, kVTCompressionPropertyKey_AverageBitRate, n);
    CFRelease(n);
}

// ---------------------------------------------------------------------------- send queue glue

static int sendOnLink(void *ctx, int type, int flags, int64_t pts, const void *p, uint32_t len) {
    TCEngine *e = (__bridge TCEngine *)ctx;
    return tc_send(e->_link, type, flags, pts, p, len);
}

static void behind(void *ctx) {
    TCEngine *e = (__bridge TCEngine *)ctx;
    atomic_store(&e->_forceKey, 1);
}

// ---------------------------------------------------------------------------- video encoder

static void encoded(void *ref, void *frameRef, OSStatus status, VTEncodeInfoFlags flags, CMSampleBufferRef sb) {
    (void)frameRef;
    TCEngine *e = (__bridge TCEngine *)ref;
    if (status != noErr || !sb || (flags & kVTEncodeInfo_FrameDropped) || !e->_q) return;
    int key = 1;
    CFArrayRef att = CMSampleBufferGetSampleAttachmentsArray(sb, false);
    if (att && CFArrayGetCount(att) > 0) key = !CFDictionaryContainsKey(CFArrayGetValueAtIndex(att, 0), kCMSampleAttachmentKey_NotSync);
    // Annex-B: start codes instead of VideoToolbox's 4-byte lengths, the parameter sets in front of every key frame.
    static uint8_t *buf; static size_t cap;
    size_t n = 0;
    #define ADD(p, l) do { size_t _l = (l); if (n + _l > cap) { cap = (n + _l) * 2; buf = realloc(buf, cap); } memcpy(buf + n, (p), _l); n += _l; } while (0)
    static const uint8_t sc[4] = { 0, 0, 0, 1 };
    if (key) {
        CMFormatDescriptionRef fd = CMSampleBufferGetFormatDescription(sb);
        size_t count = 0;
        if ((e->_hevc ? CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(fd, 0, NULL, NULL, &count, NULL)
                      : CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fd, 0, NULL, NULL, &count, NULL)) == noErr)
            for (size_t i = 0; i < count; i++) {
                const uint8_t *p = NULL; size_t l = 0;
                if ((e->_hevc ? CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(fd, i, &p, &l, NULL, NULL)
                              : CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fd, i, &p, &l, NULL, NULL)) == noErr) { ADD(sc, 4); ADD(p, l); }
            }
    }
    CMBlockBufferRef bb = CMSampleBufferGetDataBuffer(sb);
    size_t total = 0; char *data = NULL;
    if (CMBlockBufferGetDataPointer(bb, 0, NULL, &total, &data) != noErr) return;
    for (size_t off = 0; off + 4 <= total;) {
        uint32_t l = ((uint8_t)data[off] << 24) | ((uint8_t)data[off + 1] << 16) | ((uint8_t)data[off + 2] << 8) | (uint8_t)data[off + 3];
        off += 4;
        if (off + l > total) break;
        ADD(sc, 4); ADD(data + off, l);
        off += l;
    }
    #undef ADD
    atomic_fetch_add(&e->_encoded, 1);
    atomic_fetch_add(&e->_bytes, (long)n);
    int64_t pts = (int64_t)(CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sb)) * 1e6);
    sq_video(e->_q, TC_VIDEO, buf, n, key, pts);
}

- (BOOL)makeEncoder:(BOOL)hevc width:(int)w height:(int)h {
    CMVideoCodecType codec = hevc ? kCMVideoCodecType_HEVC : kCMVideoCodecType_H264;
    for (int ll = 1; ll >= 0 && !_session; ll--) {
        NSMutableDictionary *spec = [@{ (__bridge NSString *)kVTVideoEncoderSpecification_RequireHardwareAcceleratedVideoEncoder: @YES } mutableCopy];
        if (ll) spec[(__bridge NSString *)kVTVideoEncoderSpecification_EnableLowLatencyRateControl] = @YES;
        if (VTCompressionSessionCreate(NULL, w, h, codec, (__bridge CFDictionaryRef)spec, NULL, NULL, encoded,
                                       (__bridge void *)self, &_session) != noErr) _session = NULL;
    }
    if (!_session) return NO;
    _hevc = hevc;
    _bps = 0;
    int keyEvery = _fps * 2;
    VTSessionSetProperty(_session, kVTCompressionPropertyKey_RealTime, kCFBooleanTrue);
    VTSessionSetProperty(_session, kVTCompressionPropertyKey_AllowFrameReordering, kCFBooleanFalse);
    VTSessionSetProperty(_session, kVTCompressionPropertyKey_MaxKeyFrameInterval, (__bridge CFNumberRef)@(keyEvery));
    VTSessionSetProperty(_session, kVTCompressionPropertyKey_ExpectedFrameRate, (__bridge CFNumberRef)@(_fps));
    VTSessionSetProperty(_session, kVTCompressionPropertyKey_ProfileLevel,
                         hevc ? kVTProfileLevel_HEVC_Main_AutoLevel : kVTProfileLevel_H264_High_AutoLevel);
    int bps = hevc ? _targetBps : _targetBps * 5 / 4; // H.264 needs about a quarter more for the same picture
    _targetBps = bps;
    [self setBitrate:bps];
    VTCompressionSessionPrepareToEncodeFrames(_session);
    return YES;
}

// ---------------------------------------------------------------------------- capture output

- (void)stream:(SCStream *)stream didOutputSampleBuffer:(CMSampleBufferRef)sb ofType:(SCStreamOutputType)type {
    (void)stream;
    if (!_casting || !CMSampleBufferIsValid(sb)) return;
    if (type == SCStreamOutputTypeAudio) { [self streamSound:sb]; return; }
    if (type != SCStreamOutputTypeScreen) return;
    CFArrayRef att = CMSampleBufferGetSampleAttachmentsArray(sb, false);
    if (att && CFArrayGetCount(att) > 0) {
        CFNumberRef st = CFDictionaryGetValue(CFArrayGetValueAtIndex(att, 0), (__bridge CFStringRef)SCStreamFrameInfoStatus);
        int status = SCFrameStatusComplete;
        if (st) CFNumberGetValue(st, kCFNumberIntType, &status);
        if (status != SCFrameStatusComplete) return; // idle: the picture didn't change
    }
    CVImageBufferRef px = CMSampleBufferGetImageBuffer(sb);
    if (!px || !_session) return;
    CFDictionaryRef props = NULL;
    if (atomic_exchange(&_forceKey, 0)) {
        const void *k[] = { kVTEncodeFrameOptionKey_ForceKeyFrame }, *v[] = { kCFBooleanTrue };
        props = CFDictionaryCreate(NULL, k, v, 1, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    }
    VTCompressionSessionEncodeFrame(_session, px, CMSampleBufferGetPresentationTimeStamp(sb), kCMTimeInvalid, props, NULL, NULL);
    if (props) CFRelease(props);
}

- (void)stream:(SCStream *)stream didStopWithError:(NSError *)error {
    (void)stream;
    NSString *why = [NSString stringWithFormat:@"The capture stopped (%@)", error.localizedDescription];
    dispatch_async(dispatch_get_main_queue(), ^{
        if (!self->_casting) return;
        [self stop];
        if (self.onEnded) self.onEnded(why);
    });
}

// ---------------------------------------------------------------------------- sound

/** Float frames (interleaved or one buffer per channel) as 16-bit interleaved stereo PCM to the TV. */
- (void)sendFloat:(const AudioBufferList *)abl frames:(size_t)frames channels:(int)ch planar:(BOOL)planar pts:(int64_t)pts {
    if (!_q || frames == 0 || ch < 1) return;
    int16_t pcm[frames * 2];
    for (size_t f = 0; f < frames; f++) for (int c = 0; c < 2; c++) {
        int src = ch == 1 ? 0 : c;
        float v = planar ? ((const float *)abl->mBuffers[src].mData)[f] : ((const float *)abl->mBuffers[0].mData)[f * (size_t)ch + (size_t)src];
        if (v > 1) v = 1; else if (v < -1) v = -1;
        pcm[f * 2 + c] = (int16_t)(v * 32767);
    }
    sq_audio(_q, TC_AUDIO, pcm, frames * 4, pts);
}

- (void)streamSound:(CMSampleBufferRef)sb {
    const AudioStreamBasicDescription *d = CMAudioFormatDescriptionGetStreamBasicDescription(CMSampleBufferGetFormatDescription(sb));
    if (!d || !(d->mFormatFlags & kAudioFormatFlagIsFloat) || d->mBitsPerChannel != 32) return;
    struct { AudioBufferList l; AudioBuffer more[1]; } abl;
    CMBlockBufferRef keep = NULL;
    if (CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(sb, NULL, &abl.l, sizeof abl, NULL, NULL, 0, &keep) != noErr) return;
    [self sendFloat:&abl.l frames:(size_t)CMSampleBufferGetNumSamples(sb) channels:(int)d->mChannelsPerFrame
             planar:(d->mFormatFlags & kAudioFormatFlagIsNonInterleaved) != 0
                pts:(int64_t)(CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sb)) * 1e6)];
    CFRelease(keep);
}

/** Core Audio process objects of pid's app and its helpers (same bundle-id prefix); all when pid is 0. */
static NSArray<NSNumber *> *processObjects(pid_t pid, NSString *bundle) {
    AudioObjectPropertyAddress a = { kAudioHardwarePropertyProcessObjectList, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain };
    UInt32 sz = 0;
    if (AudioObjectGetPropertyDataSize(kAudioObjectSystemObject, &a, 0, NULL, &sz) != noErr || !sz) return @[];
    AudioObjectID ids[sz / sizeof(AudioObjectID)];
    if (AudioObjectGetPropertyData(kAudioObjectSystemObject, &a, 0, NULL, &sz, ids) != noErr) return @[];
    NSMutableArray *out = [NSMutableArray array];
    for (UInt32 i = 0; i < sz / sizeof(AudioObjectID); i++) {
        AudioObjectPropertyAddress pa = { kAudioProcessPropertyPID, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain };
        pid_t p = 0; UInt32 ps = sizeof p;
        AudioObjectGetPropertyData(ids[i], &pa, 0, NULL, &ps, &p);
        CFStringRef bid = NULL; UInt32 bs = sizeof bid;
        AudioObjectPropertyAddress ba = { kAudioProcessPropertyBundleID, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain };
        AudioObjectGetPropertyData(ids[i], &ba, 0, NULL, &bs, &bid);
        NSString *b = bid ? (__bridge_transfer NSString *)bid : nil;
        if (p == pid || (bundle.length && [b hasPrefix:bundle])) [out addObject:@(ids[i])];
    }
    return out;
}

/**
 * Taps the sound of pid's app (all apps but Tab Cast when pid is 0) with Core Audio (macOS 14.2+): muted on the Mac
 * while it is cast when mute. NO when the tap isn't possible (the stream's sound is used then, not muted).
 */
- (BOOL)startTapForPid:(pid_t)pid bundle:(NSString *)bundle mute:(BOOL)mute {
    if (@available(macOS 14.2, *)) {
        CATapDescription *desc;
        if (pid) {
            NSArray *procs = processObjects(pid, bundle);
            if (!procs.count) { NSLog(@"Tab Cast: no sound process for %@ yet: tapping everything", bundle); pid = 0; }
            else desc = [[CATapDescription alloc] initStereoMixdownOfProcesses:procs];
        }
        if (!pid) {
            NSArray *self_ = processObjects(getpid(), nil);
            desc = [[CATapDescription alloc] initStereoGlobalTapButExcludeProcesses:self_];
        }
        desc.name = @"Tab Cast";
        desc.privateTap = YES;
        desc.muteBehavior = mute ? CATapMutedWhenTapped : CATapUnmuted;
        if (AudioHardwareCreateProcessTap(desc, &_tap) != noErr) { NSLog(@"Tab Cast: no sound tap"); _tap = 0; return NO; }
        // The tap's format and UID.
        AudioObjectPropertyAddress fa = { kAudioTapPropertyFormat, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain };
        AudioStreamBasicDescription fmt = { 0 }; UInt32 fs = sizeof fmt;
        AudioObjectGetPropertyData(_tap, &fa, 0, NULL, &fs, &fmt);
        _soundRate = fmt.mSampleRate > 0 ? fmt.mSampleRate : 48000;
        // An aggregate device that reads the tap (private, starts the tap by itself), clocked by the output device.
        AudioObjectPropertyAddress oa = { kAudioHardwarePropertyDefaultOutputDevice, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain };
        AudioObjectID out = 0; UInt32 os = sizeof out;
        AudioObjectGetPropertyData(kAudioObjectSystemObject, &oa, 0, NULL, &os, &out);
        CFStringRef outUid = NULL; UInt32 us = sizeof outUid;
        AudioObjectPropertyAddress ua = { kAudioDevicePropertyDeviceUID, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain };
        AudioObjectGetPropertyData(out, &ua, 0, NULL, &us, &outUid);
        NSDictionary *agg = @{
            @kAudioAggregateDeviceNameKey: @"Tab Cast sound",
            @kAudioAggregateDeviceUIDKey: [NSUUID UUID].UUIDString,
            @kAudioAggregateDeviceMainSubDeviceKey: (__bridge NSString *)outUid ?: @"",
            @kAudioAggregateDeviceIsPrivateKey: @YES,
            @kAudioAggregateDeviceIsStackedKey: @NO,
            @kAudioAggregateDeviceTapAutoStartKey: @YES,
            @kAudioAggregateDeviceSubDeviceListKey: outUid ? @[ @{ @kAudioSubDeviceUIDKey: (__bridge NSString *)outUid } ] : @[],
            @kAudioAggregateDeviceTapListKey: @[ @{ @kAudioSubTapDriftCompensationKey: @YES, @kAudioSubTapUIDKey: desc.UUID.UUIDString } ],
        };
        if (outUid) CFRelease(outUid);
        if (AudioHardwareCreateAggregateDevice((__bridge CFDictionaryRef)agg, &_aggregate) != noErr) { [self stopTap]; return NO; }
        int ch = (int)(fmt.mChannelsPerFrame ? fmt.mChannelsPerFrame : 2);
        BOOL planar = (fmt.mFormatFlags & kAudioFormatFlagIsNonInterleaved) != 0;
        __weak TCEngine *weak = self;
        OSStatus s = AudioDeviceCreateIOProcIDWithBlock(&_ioProc, _aggregate, _audioQueue ?: dispatch_queue_create("tabcast.tap", DISPATCH_QUEUE_SERIAL),
            ^(const AudioTimeStamp *now, const AudioBufferList *in, const AudioTimeStamp *inTime, AudioBufferList *outData, const AudioTimeStamp *outTime) {
                (void)now; (void)outData; (void)outTime;
                TCEngine *e = weak;
                if (!e || !e->_casting || !in->mNumberBuffers) return;
                size_t frames = in->mBuffers[0].mDataByteSize / sizeof(float) / (planar ? 1 : (size_t)ch);
                [e sendFloat:in frames:frames channels:ch planar:planar pts:hostMicros(inTime->mHostTime)];
            });
        if (s != noErr || AudioDeviceStart(_aggregate, _ioProc) != noErr) { [self stopTap]; return NO; }
        return YES;
    }
    return NO;
}

- (void)stopTap {
    if (_aggregate) {
        if (_ioProc) { AudioDeviceStop(_aggregate, _ioProc); AudioDeviceDestroyIOProcID(_aggregate, _ioProc); _ioProc = NULL; }
        AudioHardwareDestroyAggregateDevice(_aggregate); _aggregate = 0;
    }
    if (_tap) { if (@available(macOS 14.2, *)) AudioHardwareDestroyProcessTap(_tap); _tap = 0; }
}

// ---------------------------------------------------------------------------- virtual display

/**
 * Makes the TV screen a screen of its own, right of the Mac's (home). macOS may bring a big new screen up mirrored (it
 * takes it for a TV) or keep a remembered one switched off (a display tool can leave that behind): NO when it is off.
 */
static BOOL placeBesideMac(CGDirectDisplayID vid, CGDirectDisplayID home) {
    CGDirectDisplayID all[16];
    uint32_t n = 0;
    CGGetOnlineDisplayList(16, all, &n);
    BOOL online = NO, mirrored = CGDisplayMirrorsDisplay(vid) != kCGNullDirectDisplay, homeOnline = NO;
    for (uint32_t i = 0; i < n; i++) {
        if (all[i] == vid) online = YES;
        else if (CGDisplayMirrorsDisplay(all[i]) == vid) mirrored = YES;
        if (all[i] == home) homeOnline = YES;
    }
    if (!online || (!CGDisplayIsActive(vid) && !mirrored)) return NO;
    CGDisplayConfigRef c;
    if (mirrored && CGBeginDisplayConfiguration(&c) == kCGErrorSuccess) {
        for (uint32_t i = 0; i < n; i++)
            if (all[i] != vid && CGDisplayMirrorsDisplay(all[i]) == vid) CGConfigureDisplayMirrorOfDisplay(c, all[i], kCGNullDirectDisplay);
        if (CGDisplayMirrorsDisplay(vid)) CGConfigureDisplayMirrorOfDisplay(c, vid, kCGNullDirectDisplay);
        if (homeOnline) CGConfigureDisplayOrigin(c, home, 0, 0); // the Mac's screen stays the main one
        // Permanently: macOS remembers the TV screen as a screen of its own and doesn't mirror it next time.
        if (CGCompleteDisplayConfiguration(c, kCGConfigurePermanently) != kCGErrorSuccess) return NO;
    }
    if (!homeOnline) return YES;
    CGRect h = CGDisplayBounds(home), v = CGDisplayBounds(vid);
    if (v.origin.x != CGRectGetMaxX(h) || v.origin.y != h.origin.y) {
        if (CGBeginDisplayConfiguration(&c) == kCGErrorSuccess) {
            CGConfigureDisplayOrigin(c, vid, (int32_t)CGRectGetMaxX(h), (int32_t)h.origin.y);
            CGCompleteDisplayConfiguration(c, kCGConfigurePermanently);
        }
    }
    return !CGDisplayMirrorsDisplay(vid) && CGDisplayIsActive(vid);
}

- (CGDirectDisplayID)makeVirtualDisplay {
    Class D = NSClassFromString(@"CGVirtualDisplayDescriptor"), V = NSClassFromString(@"CGVirtualDisplay"),
          S = NSClassFromString(@"CGVirtualDisplaySettings"), M = NSClassFromString(@"CGVirtualDisplayMode");
    if (!D || !V || !S || !M) return 0;
    CGDirectDisplayID home = CGMainDisplayID();
    // macOS remembers a screen by its serial number. When the remembered one stays off, the next serial is a new screen
    // to macOS; the one that works is kept for next time.
    NSInteger first = MAX(1, [NSUserDefaults.standardUserDefaults integerForKey:@"tvScreenSerial"]);
    for (NSInteger k = 0; k < 4; k++) {
        unsigned serial = (unsigned)((first - 1 + k) % 4 + 1);
        CGVirtualDisplayDescriptor *d = [[D alloc] init];
        d.queue = dispatch_get_main_queue();
        d.name = @"Tab Cast TV";
        d.maxPixelsWide = 1920; d.maxPixelsHigh = 1080;
        d.sizeInMillimeters = CGSizeMake(1210, 680); // a 55" TV: macOS then draws at a normal size
        d.vendorID = 0x7463; d.productID = 0x0006; d.serialNum = serial;
        d.terminationHandler = ^(id a, id b) { (void)a; (void)b; };
        CGVirtualDisplay *v = [[V alloc] initWithDescriptor:d];
        if (!v) return 0;
        CGVirtualDisplaySettings *s = [[S alloc] init];
        s.hiDPI = 0;
        s.modes = @[ [[M alloc] initWithWidth:1920 height:1080 refreshRate:60] ];
        if (![v applySettings:s]) return 0;
        BOOL placed = NO;
        for (int i = 0; i < 15 && !placed; i++) { // it takes a moment to come up
            [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.1]];
            placed = placeBesideMac(v.displayID, home);
        }
        if (placed) {
            [NSUserDefaults.standardUserDefaults setInteger:serial forKey:@"tvScreenSerial"];
            _virtual = v;
            return v.displayID;
        }
        NSLog(@"Tab Cast: the TV screen (serial %u) stayed off: trying another", serial);
        v = nil;
        [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.3]];
    }
    return 0;
}

@end
