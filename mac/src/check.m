// SPDX-License-Identifier: GPL-3.0-or-later
// Tab Cast for Mac: "Check This Mac" (see check.h).

#import "check.h"
#import <AppKit/AppKit.h>
#import <ApplicationServices/ApplicationServices.h>
#import <CoreGraphics/CoreGraphics.h>
#import <VideoToolbox/VideoToolbox.h>
#include <sys/sysctl.h>

static NSString *sysctlString(const char *name) {
    size_t n = 0;
    if (sysctlbyname(name, NULL, &n, NULL, 0) != 0 || n == 0) return nil;
    char *b = malloc(n);
    NSString *s = sysctlbyname(name, b, &n, NULL, 0) == 0 ? [NSString stringWithUTF8String:b] : nil;
    free(b);
    return s;
}

static int64_t sysctlInt(const char *name) {
    int64_t v = 0;
    size_t n = sizeof v;
    if (sysctlbyname(name, &v, &n, NULL, 0) != 0) return -1;
    return n == sizeof(int32_t) ? (int64_t)*(int32_t *)&v : v;
}

static BOOL translated(void) { return sysctlInt("sysctl.proc_translated") == 1; }

BOOL TCIsIntelMac(void) { return sysctlInt("hw.optional.arm64") != 1; }

/** The hardware encoders VideoToolbox lists for a codec ("Apple HEVC (HW)", …); empty when there is none. */
static NSArray<NSString *> *hardwareEncoders(CMVideoCodecType codec) {
    CFArrayRef list = NULL;
    NSMutableArray *names = [NSMutableArray array];
    if (VTCopyVideoEncoderList(NULL, &list) != noErr || !list) return names;
    for (NSDictionary *e in (__bridge NSArray *)list) {
        if ([e[(__bridge NSString *)kVTVideoEncoderList_CodecType] unsignedIntValue] != codec) continue;
        if (![e[(__bridge NSString *)kVTVideoEncoderList_IsHardwareAccelerated] boolValue]) continue;
        [names addObject:e[(__bridge NSString *)kVTVideoEncoderList_DisplayName] ?: e[(__bridge NSString *)kVTVideoEncoderList_EncoderName] ?: @"?"];
    }
    CFRelease(list);
    return names;
}

typedef struct { BOOL ok; double avgMs, p95Ms; int frames; } Speed;

/**
 * Encodes 120 frames of a moving 1920x1080 test picture one at a time, the way a cast does (hardware only, real time,
 * low-latency rate control when offered, 12 Mbit/s), and times each frame from hand-over to the encoded result.
 */
static Speed encodeSpeed(CMVideoCodecType codec) {
    Speed r = {0};
    const int w = 1920, h = 1080, frames = 120;
    VTCompressionSessionRef s = NULL;
    for (int ll = 1; ll >= 0 && !s; ll--) {
        NSMutableDictionary *spec = [@{ (__bridge NSString *)kVTVideoEncoderSpecification_RequireHardwareAcceleratedVideoEncoder: @YES } mutableCopy];
        if (ll) spec[(__bridge NSString *)kVTVideoEncoderSpecification_EnableLowLatencyRateControl] = @YES;
        if (VTCompressionSessionCreate(NULL, w, h, codec, (__bridge CFDictionaryRef)spec, NULL, NULL, NULL, NULL, &s) != noErr) s = NULL;
    }
    if (!s) return r;
    VTSessionSetProperty(s, kVTCompressionPropertyKey_RealTime, kCFBooleanTrue);
    VTSessionSetProperty(s, kVTCompressionPropertyKey_AllowFrameReordering, kCFBooleanFalse);
    VTSessionSetProperty(s, kVTCompressionPropertyKey_ExpectedFrameRate, (__bridge CFNumberRef)@60);
    VTSessionSetProperty(s, kVTCompressionPropertyKey_AverageBitRate, (__bridge CFNumberRef)@12000000);
    VTCompressionSessionPrepareToEncodeFrames(s);

    NSDictionary *attrs = @{ (__bridge NSString *)kCVPixelBufferIOSurfacePropertiesKey: @{} };
    CVPixelBufferRef px = NULL;
    if (CVPixelBufferCreate(NULL, w, h, kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange, (__bridge CFDictionaryRef)attrs, &px) != kCVReturnSuccess) {
        VTCompressionSessionInvalidate(s); CFRelease(s); return r;
    }
    double *ms = calloc(frames, sizeof(double));
    __block int done = 0;
    int n = 0;
    for (int f = 0; f < frames; f++) {
        // A moving picture with detail, so the encoder has real work (a still one would cost almost nothing).
        CVPixelBufferLockBaseAddress(px, 0);
        uint8_t *y = CVPixelBufferGetBaseAddressOfPlane(px, 0), *uv = CVPixelBufferGetBaseAddressOfPlane(px, 1);
        size_t ys = CVPixelBufferGetBytesPerRowOfPlane(px, 0), uvs = CVPixelBufferGetBytesPerRowOfPlane(px, 1);
        for (int row = 0; row < h; row++)
            for (int col = 0; col < w; col++) y[row * ys + col] = (uint8_t)(((col + f * 7) ^ (row * 3 + f)) + (col * row >> 9));
        for (int row = 0; row < h / 2; row++)
            for (int col = 0; col < w; col++) uv[row * uvs + col] = (uint8_t)(128 + ((col + row + f * 5) & 63) - 32);
        CVPixelBufferUnlockBaseAddress(px, 0);
        uint64_t t0 = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
        CMTime pts = CMTimeMake(f, 60);
        OSStatus st = VTCompressionSessionEncodeFrameWithOutputHandler(s, px, pts, CMTimeMake(1, 60), NULL, NULL,
            ^(OSStatus status, VTEncodeInfoFlags flags, CMSampleBufferRef sb) { (void)flags; if (status == noErr && sb) done++; });
        if (st != noErr) break;
        VTCompressionSessionCompleteFrames(s, pts);
        ms[n++] = (double)(clock_gettime_nsec_np(CLOCK_UPTIME_RAW) - t0) / 1e6;
    }
    VTCompressionSessionInvalidate(s);
    CFRelease(s);
    CVPixelBufferRelease(px);
    if (n > 10 && done > 10) {
        // The first frames warm the encoder up: leave out 10.
        double sum = 0;
        for (int i = 10; i < n; i++) sum += ms[i];
        qsort_b(ms + 10, (size_t)(n - 10), sizeof(double), ^int(const void *a, const void *b) {
            double x = *(const double *)a, z = *(const double *)b; return x < z ? -1 : x > z;
        });
        r.ok = YES; r.frames = n - 10;
        r.avgMs = sum / (n - 10);
        r.p95Ms = ms[10 + (int)((n - 10) * 0.95)];
    }
    free(ms);
    return r;
}

static NSString *yes(BOOL b) { return b ? @"yes" : @"no"; }

NSString *TCCheckThisMac(void) {
    NSMutableString *o = [NSMutableString string];
    NSOperatingSystemVersion v = NSProcessInfo.processInfo.operatingSystemVersion;
    BOOL intel = TCIsIntelMac();
    NSString *app = [NSBundle.mainBundle objectForInfoDictionaryKey:@"CFBundleShortVersionString"] ?: @"?";
    [o appendFormat:@"Tab Cast for Mac %@: Check This Mac, %@\n", app,
                    [NSISO8601DateFormatter stringFromDate:[NSDate date] timeZone:NSTimeZone.localTimeZone
                                             formatOptions:NSISO8601DateFormatWithFullDate]];
    [o appendString:@"(no names, serial numbers or addresses in this file)\n\n"];

    [o appendString:@"THIS MAC\n"];
    [o appendFormat:@"  Model:        %@\n", sysctlString("hw.model") ?: @"?"];
    [o appendFormat:@"  Chip:         %@%@\n", sysctlString("machdep.cpu.brand_string") ?: @"?", intel ? @" (Intel)" : @" (Apple silicon)"];
    [o appendFormat:@"  Cores:        %lld (%lld performance)\n", sysctlInt("hw.ncpu"), sysctlInt("hw.perflevel0.physicalcpu") > 0 ? sysctlInt("hw.perflevel0.physicalcpu") : sysctlInt("hw.physicalcpu")];
    [o appendFormat:@"  Memory:       %lld GB\n", sysctlInt("hw.memsize") / (1LL << 30)];
    [o appendFormat:@"  macOS:        %ld.%ld.%ld\n", (long)v.majorVersion, (long)v.minorVersion, (long)v.patchVersion];
    if (translated()) [o appendString:@"  Note:         Tab Cast runs translated (Rosetta): reinstall it, it should run natively\n"];
    NSScreen *main = NSScreen.screens.firstObject;
    if (main) {
        CGDirectDisplayID d = [main.deviceDescription[@"NSScreenNumber"] unsignedIntValue];
        [o appendFormat:@"  Main screen:  %.0f x %.0f points, %zu x %zu pixels\n", main.frame.size.width, main.frame.size.height,
                        CGDisplayPixelsWide(d) * (size_t)main.backingScaleFactor, CGDisplayPixelsHigh(d) * (size_t)main.backingScaleFactor];
    }
    NSArray *thermal = @[ @"normal", @"fair", @"serious", @"critical" ];
    NSInteger ts = NSProcessInfo.processInfo.thermalState;
    [o appendFormat:@"  Heat now:     %@\n\n", ts >= 0 && ts < 4 ? thermal[(NSUInteger)ts] : @"?"];

    [o appendString:@"PICTURE (hardware video encoder, 1080p test picture, one frame at a time)\n"];
    Speed best = {0};
    NSString *bestName = nil;
    for (int i = 0; i < 2; i++) {
        CMVideoCodecType c = i == 0 ? kCMVideoCodecType_HEVC : kCMVideoCodecType_H264;
        NSString *name = i == 0 ? @"HEVC " : @"H.264";
        NSArray *hw = hardwareEncoders(c);
        if (!hw.count) { [o appendFormat:@"  %@: no hardware encoder\n", name]; continue; }
        Speed sp = encodeSpeed(c);
        if (!sp.ok) { [o appendFormat:@"  %@: %@, but the test didn't run\n", name, [hw componentsJoinedByString:@", "]]; continue; }
        [o appendFormat:@"  %@: %@; %.1f ms per frame on average, %.1f ms for the slowest 5%% (%d frames)\n", name,
                        [hw componentsJoinedByString:@", "], sp.avgMs, sp.p95Ms, sp.frames];
        if (!bestName) { best = sp; bestName = i == 0 ? @"HEVC" : @"H.264"; } // HEVC first: the cast prefers it
    }
    NSString *quality;
    if (!bestName) quality = @"none: this Mac has no hardware video encoder, so Tab Cast can't cast its picture (Send This Tab still works)";
    else if (best.p95Ms <= 12) quality = [NSString stringWithFormat:@"Film (1080p, 60 fps) with %@", bestName];
    else if (best.p95Ms <= 25) quality = [NSString stringWithFormat:@"Smooth (1080p, 30 fps) with %@; Film may stutter", bestName];
    else quality = [NSString stringWithFormat:@"Light (720p, 30 fps) with %@", bestName];
    [o appendFormat:@"  Best quality: %@\n", quality];
    [o appendString:@"  (with a heavy game running the encoder has less room: the cast lowers its bitrate by itself)\n\n"];

    [o appendString:@"SOUND\n"];
    BOOL tap = v.majorVersion > 14 || (v.majorVersion == 14 && v.minorVersion >= 2);
    [o appendFormat:@"  Sound to the TV:               yes\n"];
    [o appendFormat:@"  Mac silent while casting:      %@\n\n", tap ? @"yes" : @"no: needs macOS 14.2 or later (on this macOS the Mac keeps playing too; turn its volume down)"];

    [o appendString:@"SECOND SCREEN (the TV as a screen of its own)\n"];
    [o appendFormat:@"  Available:                     %@\n\n", yes(NSClassFromString(@"CGVirtualDisplay") != nil)];

    [o appendString:@"PERMISSIONS (macOS asks the first time each is needed)\n"];
    [o appendFormat:@"  Screen Recording:              %@\n", yes(CGPreflightScreenCaptureAccess())];
    [o appendFormat:@"  Accessibility (Move a Window): %@\n", yes(AXIsProcessTrusted())];
    return o;
}
