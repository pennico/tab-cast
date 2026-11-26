// SPDX-License-Identifier: GPL-3.0-or-later
//
// Tab Cast for Mac, probe 1: how light can casting from the Mac be? Captures one window (e.g. a game in full screen)
// or the whole display with ScreenCaptureKit, scaled to fit 1920x1080, encodes it with the Mac's hardware encoder
// (VideoToolbox: HEVC or H.264, real time, no frame reordering, low-latency rate control where the encoder has it),
// captures the system sound, and measures: frames captured and encoded per second, Mbit/s, encode delay, this process's
// CPU and the whole Mac's CPU. Optionally writes the video as an Annex-B elementary stream (play it with ffplay).
//
//   tabcast-probe --list
//   tabcast-probe [--window "Football Manager" | --display] [--codec hevc|h264] [--fps 60] [--mbps 12]
//                 [--height 1080] [--secs 20] [--out cast.hevc] [--json result.json] [--log run.txt]
//
// Built as a small app (Tab Cast Probe.app) so macOS's screen-recording permission is given to it alone, not to the
// terminal it would otherwise run in: open -W build/TabCastProbe.app --args --log /path/run.txt …
//
// Plain C where it can be; Objective-C only for ScreenCaptureKit, which has no C API.

#import <Foundation/Foundation.h>
#import <AppKit/AppKit.h>
#import <ScreenCaptureKit/ScreenCaptureKit.h>
#import <VideoToolbox/VideoToolbox.h>
#import <CoreMedia/CoreMedia.h>
#include <mach/mach.h>
#include <mach/mach_time.h>
#include <sys/resource.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// ------------------------------------------------------------------------------------------------ options

typedef struct {
    int list;
    const char *window;   // part of the app name or window title; NULL = the main display
    int hevc;
    int fps;
    int mbps;
    int height;
    int secs;
    const char *out;
    const char *json;
} Options;

static Options opt = { 0, NULL, 1, 60, 12, 1080, 20, NULL, NULL };

static void usage(void) {
    fprintf(stderr, "usage: tabcast-probe --list | [--window NAME | --display] [--codec hevc|h264] [--fps N] [--mbps N]\n"
                    "                     [--height N] [--secs N] [--out FILE] [--json FILE]\n");
    exit(2);
}

static void parse(int argc, const char **argv) {
    for (int i = 1; i < argc; i++) {
        const char *a = argv[i];
        const char *v = i + 1 < argc ? argv[i + 1] : NULL;
        if (!strcmp(a, "--list")) opt.list = 1;
        else if (!strcmp(a, "--display")) opt.window = NULL;
        else if (!strcmp(a, "--window") && v) { opt.window = v; i++; }
        else if (!strcmp(a, "--codec") && v) { opt.hevc = strcmp(v, "h264") != 0; i++; }
        else if (!strcmp(a, "--fps") && v) { opt.fps = atoi(v); i++; }
        else if (!strcmp(a, "--mbps") && v) { opt.mbps = atoi(v); i++; }
        else if (!strcmp(a, "--height") && v) { opt.height = atoi(v); i++; }
        else if (!strcmp(a, "--secs") && v) { opt.secs = atoi(v); i++; }
        else if (!strcmp(a, "--out") && v) { opt.out = v; i++; }
        else if (!strcmp(a, "--json") && v) { opt.json = v; i++; }
        else if (!strcmp(a, "--log") && v) { // an app opened with `open` has no terminal: write everything here
            if (!freopen(v, "w", stdout) || !freopen(v, "a", stderr)) exit(9);
            setvbuf(stdout, NULL, _IOLBF, 0); i++;
        }
        else if (!strncmp(a, "-psn_", 5)) {} // old Finder launch argument
        else usage();
    }
    if (opt.fps < 1 || opt.fps > 120 || opt.mbps < 1 || opt.height < 240 || opt.secs < 1) usage();
}

// ------------------------------------------------------------------------------------------------ clocks and CPU

static double now_s(void) {
    static mach_timebase_info_data_t tb;
    if (!tb.denom) mach_timebase_info(&tb);
    return (double)mach_absolute_time() * tb.numer / tb.denom / 1e9;
}

static double proc_cpu_s(void) {
    struct rusage r;
    getrusage(RUSAGE_SELF, &r);
    return r.ru_utime.tv_sec + r.ru_utime.tv_usec / 1e6 + r.ru_stime.tv_sec + r.ru_stime.tv_usec / 1e6;
}

/** The whole Mac's busy and total CPU ticks (all cores). */
static void host_ticks(unsigned long long *busy, unsigned long long *total) {
    host_cpu_load_info_data_t l;
    mach_msg_type_number_t n = HOST_CPU_LOAD_INFO_COUNT;
    *busy = *total = 0;
    if (host_statistics(mach_host_self(), HOST_CPU_LOAD_INFO, (host_info_t)&l, &n) != KERN_SUCCESS) return;
    unsigned long long u = l.cpu_ticks[CPU_STATE_USER], s = l.cpu_ticks[CPU_STATE_SYSTEM],
                       ni = l.cpu_ticks[CPU_STATE_NICE], id = l.cpu_ticks[CPU_STATE_IDLE];
    *busy = u + s + ni;
    *total = u + s + ni + id;
}

// ------------------------------------------------------------------------------------------------ statistics

#define MAXLAT 200000
static struct {
    _Atomic long captured, encoded, idle, dropped, audioFrames, keyframes;
    _Atomic long long bytes;
    double lat[MAXLAT];        // encode delay of each frame, seconds
    _Atomic long nlat;
    // submit times by frame number (a small ring: the encoder never holds more than a few frames)
    double submitted[1024];
} st;

static FILE *outFile;
static int stopping;

// ------------------------------------------------------------------------------------------------ Annex-B writer

static const uint8_t startCode[4] = { 0, 0, 0, 1 };

/** Parameter sets (VPS/SPS/PPS or SPS/PPS) of a key frame, in Annex-B form. */
static void writeParameterSets(CMFormatDescriptionRef fd) {
    size_t count = 0;
    int nalLen = 0;
    OSStatus s = opt.hevc
        ? CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(fd, 0, NULL, NULL, &count, &nalLen)
        : CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fd, 0, NULL, NULL, &count, &nalLen);
    if (s != noErr) return;
    for (size_t i = 0; i < count; i++) {
        const uint8_t *p = NULL;
        size_t len = 0;
        s = opt.hevc ? CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(fd, i, &p, &len, NULL, NULL)
                     : CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fd, i, &p, &len, NULL, NULL);
        if (s != noErr) continue;
        fwrite(startCode, 1, 4, outFile);
        fwrite(p, 1, len, outFile);
    }
}

/** The frame's NAL units (4-byte big-endian lengths, as VideoToolbox writes them) with start codes instead. */
static void writeFrame(CMSampleBufferRef sb, int key) {
    if (key) writeParameterSets(CMSampleBufferGetFormatDescription(sb));
    CMBlockBufferRef bb = CMSampleBufferGetDataBuffer(sb);
    size_t total = 0;
    char *data = NULL;
    if (CMBlockBufferGetDataPointer(bb, 0, NULL, &total, &data) != noErr) return;
    size_t off = 0;
    while (off + 4 <= total) {
        uint32_t n = ((uint8_t)data[off] << 24) | ((uint8_t)data[off + 1] << 16) | ((uint8_t)data[off + 2] << 8) | (uint8_t)data[off + 3];
        off += 4;
        if (off + n > total) break;
        fwrite(startCode, 1, 4, outFile);
        fwrite(data + off, 1, n, outFile);
        off += n;
    }
}

// ------------------------------------------------------------------------------------------------ encoder

static VTCompressionSessionRef session;
static const char *encoderName = "?";
static int lowLatency;

static void encoded(void *ref, void *frameRef, OSStatus status, VTEncodeInfoFlags flags, CMSampleBufferRef sb) {
    (void)ref;
    if (status != noErr || !sb || (flags & kVTEncodeInfo_FrameDropped)) { atomic_fetch_add(&st.dropped, 1); return; }
    long n = (long)(intptr_t)frameRef;
    double d = now_s() - st.submitted[n % 1024];
    long i = atomic_fetch_add(&st.nlat, 1);
    if (i < MAXLAT) st.lat[i] = d;
    atomic_fetch_add(&st.encoded, 1);
    atomic_fetch_add(&st.bytes, (long long)CMSampleBufferGetTotalSampleSize(sb));
    int key = 1;
    CFArrayRef att = CMSampleBufferGetSampleAttachmentsArray(sb, false);
    if (att && CFArrayGetCount(att) > 0) {
        CFDictionaryRef a = CFArrayGetValueAtIndex(att, 0);
        key = !CFDictionaryContainsKey(a, kCMSampleAttachmentKey_NotSync);
    }
    if (key) atomic_fetch_add(&st.keyframes, 1);
    if (outFile) writeFrame(sb, key);
}

static int makeEncoder(int w, int h) {
    CMVideoCodecType codec = opt.hevc ? kCMVideoCodecType_HEVC : kCMVideoCodecType_H264;
    for (int ll = 1; ll >= 0; ll--) {
        CFMutableDictionaryRef spec = CFDictionaryCreateMutable(NULL, 0, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
        CFDictionarySetValue(spec, kVTVideoEncoderSpecification_RequireHardwareAcceleratedVideoEncoder, kCFBooleanTrue);
        if (ll) CFDictionarySetValue(spec, kVTVideoEncoderSpecification_EnableLowLatencyRateControl, kCFBooleanTrue);
        OSStatus s = VTCompressionSessionCreate(NULL, w, h, codec, spec, NULL, NULL, encoded, NULL, &session);
        CFRelease(spec);
        if (s == noErr) { lowLatency = ll; break; }
        fprintf(stderr, "encoder %s%s: not available (%d)\n", opt.hevc ? "HEVC" : "H.264", ll ? " low-latency" : "", (int)s);
        session = NULL;
    }
    if (!session) return 0;
    int bps = opt.mbps * 1000000, keyEvery = opt.fps * 2;
    CFNumberRef nBps = CFNumberCreate(NULL, kCFNumberIntType, &bps);
    CFNumberRef nKey = CFNumberCreate(NULL, kCFNumberIntType, &keyEvery);
    CFNumberRef nFps = CFNumberCreate(NULL, kCFNumberIntType, &opt.fps);
    VTSessionSetProperty(session, kVTCompressionPropertyKey_RealTime, kCFBooleanTrue);
    VTSessionSetProperty(session, kVTCompressionPropertyKey_AllowFrameReordering, kCFBooleanFalse);
    VTSessionSetProperty(session, kVTCompressionPropertyKey_AverageBitRate, nBps);
    VTSessionSetProperty(session, kVTCompressionPropertyKey_MaxKeyFrameInterval, nKey);
    VTSessionSetProperty(session, kVTCompressionPropertyKey_ExpectedFrameRate, nFps);
    VTSessionSetProperty(session, kVTCompressionPropertyKey_ProfileLevel,
                         opt.hevc ? kVTProfileLevel_HEVC_Main_AutoLevel : kVTProfileLevel_H264_High_AutoLevel);
    CFRelease(nBps); CFRelease(nKey); CFRelease(nFps);
    CFStringRef id = NULL;
    if (VTSessionCopyProperty(session, kVTCompressionPropertyKey_EncoderID, NULL, &id) == noErr && id) {
        static char buf[200];
        CFStringGetCString(id, buf, sizeof buf, kCFStringEncodingUTF8);
        encoderName = buf;
        CFRelease(id);
    }
    VTCompressionSessionPrepareToEncodeFrames(session);
    return 1;
}

// ------------------------------------------------------------------------------------------------ capture

@interface Sink : NSObject <SCStreamOutput, SCStreamDelegate>
@end

@implementation Sink
- (void)stream:(SCStream *)stream didOutputSampleBuffer:(CMSampleBufferRef)sb ofType:(SCStreamOutputType)type {
    (void)stream;
    if (stopping || !CMSampleBufferIsValid(sb)) return;
    if (type == SCStreamOutputTypeAudio) {
        atomic_fetch_add(&st.audioFrames, (long)CMSampleBufferGetNumSamples(sb));
        return;
    }
    if (type != SCStreamOutputTypeScreen) return;
    // Only complete frames carry a new picture; "idle" means the screen didn't change.
    CFArrayRef att = CMSampleBufferGetSampleAttachmentsArray(sb, false);
    if (att && CFArrayGetCount(att) > 0) {
        CFDictionaryRef a = CFArrayGetValueAtIndex(att, 0);
        CFNumberRef statusRef = CFDictionaryGetValue(a, (__bridge CFStringRef)SCStreamFrameInfoStatus);
        int status = SCFrameStatusComplete;
        if (statusRef) CFNumberGetValue(statusRef, kCFNumberIntType, &status);
        if (status != SCFrameStatusComplete) { if (status == SCFrameStatusIdle) atomic_fetch_add(&st.idle, 1); return; }
    }
    CVImageBufferRef px = CMSampleBufferGetImageBuffer(sb);
    if (!px) return;
    long n = atomic_fetch_add(&st.captured, 1);
    st.submitted[n % 1024] = now_s();
    OSStatus s = VTCompressionSessionEncodeFrame(session, px, CMSampleBufferGetPresentationTimeStamp(sb), kCMTimeInvalid,
                                                 NULL, (void *)(intptr_t)n, NULL);
    if (s != noErr) atomic_fetch_add(&st.dropped, 1);
}

- (void)stream:(SCStream *)stream didStopWithError:(NSError *)error {
    (void)stream;
    fprintf(stderr, "capture stopped: %s\n", error.localizedDescription.UTF8String);
}
@end

// ------------------------------------------------------------------------------------------------ main

static int cmpd(const void *a, const void *b) {
    double x = *(const double *)a, y = *(const double *)b;
    return x < y ? -1 : x > y;
}

static SCShareableContent *content(void) {
    __block SCShareableContent *c = nil;
    __block NSError *err = nil;
    dispatch_semaphore_t done = dispatch_semaphore_create(0);
    // All windows, not only the visible ones: a full-screen game sits in its own Space.
    [SCShareableContent getShareableContentExcludingDesktopWindows:YES onScreenWindowsOnly:NO
                                                 completionHandler:^(SCShareableContent *sc, NSError *e) {
        c = sc; err = e; dispatch_semaphore_signal(done);
    }];
    dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
    if (!c) {
        fprintf(stderr, "no access to the screen: %s\n(System Settings > Privacy & Security > Screen & System Audio "
                        "Recording: allow the app this runs in, then run again)\n", err.localizedDescription.UTF8String);
        exit(3);
    }
    return c;
}

static int matches(SCWindow *w, const char *name) {
    NSString *n = [NSString stringWithUTF8String:name];
    return [w.owningApplication.applicationName localizedCaseInsensitiveContainsString:n] ||
           [w.title localizedCaseInsensitiveContainsString:n];
}

int main(int argc, const char **argv) {
    @autoreleasepool {
        parse(argc, argv);
        // Capture needs the window server connection that an app gets (else CGS_REQUIRE_INIT asserts on start).
        [NSApplication sharedApplication];
        SCShareableContent *sc = content();
        if (opt.list) {
            for (SCWindow *w in sc.windows) {
                if (w.frame.size.width < 200 || w.frame.size.height < 150 || w.windowLayer != 0) continue;
                printf("%-28s %-40s %4.0fx%-4.0f %s\n", w.owningApplication.applicationName.UTF8String ?: "?",
                       (w.title.length ? w.title : @"(no title)").UTF8String, w.frame.size.width, w.frame.size.height,
                       w.onScreen ? "on screen" : "");
            }
            for (SCDisplay *d in sc.displays) printf("display %u: %ldx%ld\n", d.displayID, (long)d.width, (long)d.height);
            return 0;
        }

        SCContentFilter *filter = nil;
        double srcW, srcH;
        char what[300];
        if (opt.window) {
            SCWindow *best = nil;
            for (SCWindow *w in sc.windows)
                if (w.windowLayer == 0 && matches(w, opt.window) &&
                    (!best || w.frame.size.width * w.frame.size.height > best.frame.size.width * best.frame.size.height)) best = w;
            if (!best) { fprintf(stderr, "no window matches \"%s\" (see --list)\n", opt.window); return 4; }
            filter = [[SCContentFilter alloc] initWithDesktopIndependentWindow:best];
            srcW = best.frame.size.width; srcH = best.frame.size.height;
            snprintf(what, sizeof what, "window \"%s\" of %s", best.title.UTF8String ?: "", best.owningApplication.applicationName.UTF8String ?: "?");
        } else {
            SCDisplay *d = sc.displays.firstObject;
            if (!d) { fprintf(stderr, "no display\n"); return 4; }
            filter = [[SCContentFilter alloc] initWithDisplay:d excludingWindows:@[]];
            srcW = d.width; srcH = d.height;
            snprintf(what, sizeof what, "display %u", d.displayID);
        }
        // The picture box: at most 16:9 at the chosen height, aspect kept, even sizes (the encoder wants them).
        double boxW = opt.height * 16.0 / 9.0, boxH = opt.height;
        double scale = fmin(boxW / srcW, boxH / srcH);
        int w = ((int)(srcW * scale)) & ~1, h = ((int)(srcH * scale)) & ~1;
        if (!makeEncoder(w, h)) { fprintf(stderr, "no hardware %s encoder\n", opt.hevc ? "HEVC" : "H.264"); return 5; }

        SCStreamConfiguration *cfg = [[SCStreamConfiguration alloc] init];
        cfg.width = w; cfg.height = h;
        cfg.minimumFrameInterval = CMTimeMake(1, opt.fps);
        cfg.pixelFormat = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange; // what the encoder takes without converting
        cfg.queueDepth = 6;
        cfg.showsCursor = YES;
        cfg.scalesToFit = YES;
        cfg.capturesAudio = YES;
        cfg.sampleRate = 48000;
        cfg.channelCount = 2;
        cfg.excludesCurrentProcessAudio = YES;

        if (opt.out && !(outFile = fopen(opt.out, "wb"))) { perror(opt.out); return 6; }
        Sink *sink = [Sink new];
        SCStream *stream = [[SCStream alloc] initWithFilter:filter configuration:cfg delegate:sink];
        dispatch_queue_t vq = dispatch_queue_create("video", DISPATCH_QUEUE_SERIAL);
        dispatch_queue_t aq = dispatch_queue_create("audio", DISPATCH_QUEUE_SERIAL);
        NSError *err = nil;
        if (![stream addStreamOutput:sink type:SCStreamOutputTypeScreen sampleHandlerQueue:vq error:&err] ||
            ![stream addStreamOutput:sink type:SCStreamOutputTypeAudio sampleHandlerQueue:aq error:&err]) {
            fprintf(stderr, "can't add the capture output: %s\n", err.localizedDescription.UTF8String); return 7;
        }
        dispatch_semaphore_t started = dispatch_semaphore_create(0);
        __block NSError *startErr = nil;
        [stream startCaptureWithCompletionHandler:^(NSError *e) { startErr = e; dispatch_semaphore_signal(started); }];
        dispatch_semaphore_wait(started, DISPATCH_TIME_FOREVER);
        if (startErr) { fprintf(stderr, "capture didn't start: %s\n", startErr.localizedDescription.UTF8String); return 8; }

        printf("capturing %s (%.0fx%.0f) -> %dx%d %s at up to %d fps, %d Mbit/s, encoder %s%s\n", what, srcW, srcH, w, h,
               opt.hevc ? "HEVC" : "H.264", opt.fps, opt.mbps, encoderName, lowLatency ? " (low-latency rate control)" : "");
        fflush(stdout);

        double t0 = now_s(), c0 = proc_cpu_s();
        unsigned long long hb0, ht0;
        host_ticks(&hb0, &ht0);
        long lastEnc = 0; long long lastBytes = 0; double lastT = t0;
        for (int s = 1; s <= opt.secs; s++) {
            [NSThread sleepForTimeInterval:1.0];
            if (s % 5 == 0 || s == opt.secs) {
                double t = now_s();
                long e = atomic_load(&st.encoded); long long b = atomic_load(&st.bytes);
                printf("%3d s: %5.1f fps encoded, %5.1f Mbit/s\n", s, (e - lastEnc) / (t - lastT), (b - lastBytes) * 8 / 1e6 / (t - lastT));
                fflush(stdout);
                lastEnc = e; lastBytes = b; lastT = t;
            }
        }
        double t1 = now_s(), c1 = proc_cpu_s();
        unsigned long long hb1, ht1;
        host_ticks(&hb1, &ht1);
        stopping = 1;
        dispatch_semaphore_t stopped = dispatch_semaphore_create(0);
        [stream stopCaptureWithCompletionHandler:^(NSError *e) { (void)e; dispatch_semaphore_signal(stopped); }];
        dispatch_semaphore_wait(stopped, dispatch_time(DISPATCH_TIME_NOW, 3 * NSEC_PER_SEC));
        VTCompressionSessionCompleteFrames(session, kCMTimeInvalid);
        if (outFile) fclose(outFile);

        double secs = t1 - t0;
        int ncpu = (int)[NSProcessInfo processInfo].activeProcessorCount;
        long nl = atomic_load(&st.nlat); if (nl > MAXLAT) nl = MAXLAT;
        qsort(st.lat, (size_t)nl, sizeof(double), cmpd);
        double avg = 0; for (long i = 0; i < nl; i++) avg += st.lat[i];
        avg = nl ? avg / nl : 0;
        double p95 = nl ? st.lat[(long)(nl * 0.95)] : 0, mx = nl ? st.lat[nl - 1] : 0;
        double procPct = (c1 - c0) / secs * 100.0;              // of one core
        double hostPct = ht1 > ht0 ? (double)(hb1 - hb0) / (double)(ht1 - ht0) * 100.0 : 0; // of the whole Mac

        char json[2048];
        snprintf(json, sizeof json,
            "{\"source\": \"%s\", \"size\": \"%dx%d\", \"codec\": \"%s\", \"encoder\": \"%s\", \"low_latency\": %s,\n"
            " \"fps_target\": %d, \"mbps_target\": %d, \"seconds\": %.1f,\n"
            " \"captured_fps\": %.1f, \"encoded_fps\": %.1f, \"idle_frames\": %ld, \"dropped\": %ld, \"keyframes\": %ld,\n"
            " \"mbit_per_s\": %.2f, \"encode_ms_avg\": %.1f, \"encode_ms_p95\": %.1f, \"encode_ms_max\": %.1f,\n"
            " \"cpu_this_process_pct_of_one_core\": %.1f, \"cpu_whole_mac_pct\": %.1f, \"cores\": %d,\n"
            " \"audio_khz_received\": %.1f}\n",
            what, w, h, opt.hevc ? "hevc" : "h264", encoderName, lowLatency ? "true" : "false", opt.fps, opt.mbps, secs,
            atomic_load(&st.captured) / secs, atomic_load(&st.encoded) / secs, atomic_load(&st.idle), atomic_load(&st.dropped),
            atomic_load(&st.keyframes), atomic_load(&st.bytes) * 8 / 1e6 / secs, avg * 1000, p95 * 1000, mx * 1000,
            procPct, hostPct, ncpu, atomic_load(&st.audioFrames) / secs / 1000.0);
        fputs(json, stdout);
        if (opt.json) { FILE *f = fopen(opt.json, "w"); if (f) { fputs(json, f); fclose(f); } }
        return 0;
    }
}
