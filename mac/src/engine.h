// SPDX-License-Identifier: GPL-3.0-or-later
//
// Tab Cast for Mac: the cast engine. Picture: ScreenCaptureKit (a window, a display, or Tab Cast's own virtual display
// "Tab Cast TV") → VideoToolbox hardware encoder (HEVC, or H.264 where the Mac or the TV has no HEVC) → send queue →
// Tab Cast TV. Sound: a Core Audio process tap of the cast app (or of everything, for a screen), muted on the Mac
// while it is cast (macOS 14.2+); older macOS: ScreenCaptureKit's sound, not muted. The bitrate follows the network.

#import <Foundation/Foundation.h>
#import <ScreenCaptureKit/ScreenCaptureKit.h>
#include "link.h"

typedef NS_ENUM(NSInteger, TCMode) {
    TCModeWindow,       // one window (also a full-screen one)
    TCModeScreen,       // the Mac's main screen
    TCModeSecondScreen, // a virtual 1920x1080 display "Tab Cast TV": what is moved onto it is cast, the Mac stays free
};

typedef NS_ENUM(NSInteger, TCQuality) {
    TCQualityFilm,      // 1080p, up to 60 fps, 12 Mbit/s
    TCQualitySmooth,    // 1080p, up to 30 fps, 8 Mbit/s
    TCQualityLight,     // 720p, up to 30 fps, 5 Mbit/s
};

@interface TCEngine : NSObject

/** One plain line about the cast for the menu ("Casting … · 50 fps · 9.8 Mbit/s · 4 ms"), on the main thread. */
@property(nonatomic, copy) void (^onStatus)(NSString *line);
/** The cast ended by itself (link lost, window closed): why, on the main thread. */
@property(nonatomic, copy) void (^onEnded)(NSString *why);

@property(nonatomic, readonly) BOOL casting;
/** The virtual display while casting in TCModeSecondScreen, else 0. */
@property(nonatomic, readonly) CGDirectDisplayID tvDisplay;
/** What is cast, for the menu ("Football Manager", "the screen", "the TV screen"). */
@property(nonatomic, readonly, copy) NSString *what;

/**
 * Starts casting over link (connected and paired; the engine sends on it until stop). window: for TCModeWindow.
 * tvCodecs: the TV's HELLO_ACK codecs. muteMac: the cast sound is muted on the Mac. NO + a plain reason on failure.
 */
- (BOOL)startOn:(TcLink *)link tvCodecs:(NSString *)tvCodecs mode:(TCMode)mode window:(SCWindow *)window
        quality:(TCQuality)quality muteMac:(BOOL)muteMac error:(NSString **)error;

/** Stops (tells the TV), frees the capture, the tap and the virtual display; the link is the caller's again. */
- (void)stop;

/** The TV asked for a key frame (KEYFRAME message). */
- (void)keyFrameWanted;

/** The windows that can be cast, largest first (main thread; waits up to 2 s for macOS). nil: no permission. */
+ (NSArray<SCWindow *> *)castableWindowsError:(NSString **)error;

@end
