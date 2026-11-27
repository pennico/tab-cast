// SPDX-License-Identifier: GPL-3.0-or-later
//
// Tab Cast for Mac: a menu-bar app for Tab Cast TV.
//   Send this tab to the TV   the TV's own browser opens the page and plays its video (the Mac does nothing more)
//   Cast a window / the whole screen / the TV as a second screen   the picture and sound go to the TV (engine.m)
// One TV, reached by its address (shown on Tab Cast TV's start screen), paired once with the code the TV shows.

#import <AppKit/AppKit.h>
#import "engine.h"
#import "macos.h"
#import "check.h"
#include "link.h"

static NSString *const kHost = @"tvHost", *const kTvName = @"tvName", *const kQuality = @"quality",
                *const kMute = @"muteMac", *const kScreenOn = @"screenOn";

@interface TCApp : NSObject <NSApplicationDelegate, NSMenuDelegate>
@end

@implementation TCApp {
    NSStatusItem *_item;
    TCEngine *_engine;
    TcLink *_link;
    NSString *_status;          // the last status line while casting, or a note
    NSArray<SCWindow *> *_windows;
}

- (void)applicationDidFinishLaunching:(NSNotification *)n {
    (void)n;
    [NSUserDefaults.standardUserDefaults registerDefaults:@{ kQuality: @0, kMute: @YES, kScreenOn: @YES }];
    _engine = [TCEngine new];
    __weak TCApp *weak = self;
    _engine.onStatus = ^(NSString *line) { TCApp *s = weak; if (s) s->_status = line; };
    _engine.onEnded = ^(NSString *why) { [weak castEnded:why]; };
    _item = [NSStatusBar.systemStatusBar statusItemWithLength:NSVariableStatusItemLength];
    [self setIcon:NO];
    NSMenu *m = [NSMenu new];
    m.delegate = self;
    _item.menu = m;
}

- (void)setIcon:(BOOL)casting {
    NSImage *img = [NSImage imageWithSystemSymbolName:casting ? @"tv.fill" : @"tv" accessibilityDescription:@"Tab Cast"];
    img.template = YES;
    _item.button.image = img;
}

// ---------------------------------------------------------------------------- the menu

- (NSMenuItem *)add:(NSMenu *)m title:(NSString *)t action:(SEL)a {
    NSMenuItem *i = [m addItemWithTitle:t action:a keyEquivalent:@""];
    i.target = self;
    return i;
}

- (void)note:(NSMenu *)m text:(NSString *)t {
    NSMenuItem *i = [m addItemWithTitle:t action:nil keyEquivalent:@""];
    i.enabled = NO;
}

- (void)menuNeedsUpdate:(NSMenu *)m {
    [m removeAllItems];
    NSUserDefaults *d = NSUserDefaults.standardUserDefaults;
    NSString *host = [d stringForKey:kHost];
    [self note:m text:host ? [NSString stringWithFormat:@"TV: %@ (%@)", [d stringForKey:kTvName] ?: @"Tab Cast TV", host] : @"No TV yet"];
    if (!host) {
        [self add:m title:@"Add the TV…" action:@selector(chooseTv:)];
        [m addItem:NSMenuItem.separatorItem];
        [self add:m title:@"Check This Mac for Casting…" action:@selector(checkMac:)];
        [self add:m title:@"Quit Tab Cast" action:@selector(quit:)];
        return;
    }
    [m addItem:NSMenuItem.separatorItem];

    NSString *why = nil;
    NSDictionary *tab = TCFrontTab(&why);
    NSMenuItem *send = [self add:m title:@"Send This Tab to the TV" action:@selector(sendTab:)];
    send.representedObject = tab;
    send.enabled = tab != nil;
    [self note:m text:tab ? [NSString stringWithFormat:@"   %@ — %@", tab[@"browser"], [self shorten:tab[@"title"] ?: tab[@"url"]]]
                          : [NSString stringWithFormat:@"   %@", why]];
    [m addItem:NSMenuItem.separatorItem];

    if (_engine.casting) {
        [self note:m text:_status ?: [NSString stringWithFormat:@"Casting %@", _engine.what]];
        if (_engine.tvDisplay) {
            NSMenuItem *mv = [self add:m title:@"Move a Window to the TV" action:nil];
            mv.submenu = [self windowMenu:@selector(moveWindow:) excludingDisplay:_engine.tvDisplay];
        }
        [self add:m title:@"Stop Casting" action:@selector(stopCast:)];
    } else {
        if (_status.length) [self note:m text:_status];
        NSMenuItem *win = [self add:m title:@"Cast a Window" action:nil];
        win.submenu = [self windowMenu:@selector(castWindow:) excludingDisplay:0];
        [self add:m title:@"Cast the Whole Screen" action:@selector(castScreen:)];
        [self add:m title:@"Use the TV as a Second Screen" action:@selector(castSecond:)];
    }
    [m addItem:NSMenuItem.separatorItem];

    NSMenuItem *mute = [self add:m title:@"Mute the Cast Sound on This Mac" action:@selector(toggleMute:)];
    mute.state = [d boolForKey:kMute] ? NSControlStateValueOn : NSControlStateValueOff;
    NSMenuItem *screen = [self add:m title:@"Keep This Mac's Screen On" action:@selector(toggleScreen:)];
    screen.state = [d boolForKey:kScreenOn] ? NSControlStateValueOn : NSControlStateValueOff;
    NSMenuItem *q = [self add:m title:@"Quality" action:nil];
    q.submenu = [NSMenu new];
    NSArray *names = @[ @"Film (1080p, 60 fps)", @"Smooth (1080p, 30 fps)", @"Light (720p, 30 fps)" ];
    for (NSInteger i = 0; i < 3; i++) {
        NSMenuItem *x = [self add:q.submenu title:names[(NSUInteger)i] action:@selector(setQuality:)];
        x.tag = i;
        x.state = [d integerForKey:kQuality] == i ? NSControlStateValueOn : NSControlStateValueOff;
    }
    NSMenuItem *tv = [self add:m title:@"TV" action:nil];
    tv.submenu = [NSMenu new];
    [self add:tv.submenu title:@"Change the TV's Address…" action:@selector(chooseTv:)];
    [self add:tv.submenu title:@"Pair Again" action:@selector(forgetPairing:)];
    [self add:tv.submenu title:@"Save the TV's Log to Downloads" action:@selector(tvReport:)];
    [m addItem:NSMenuItem.separatorItem];
    NSMenuItem *check = [self add:m title:@"Check This Mac for Casting…" action:@selector(checkMac:)];
    check.enabled = !_engine.casting; // the test uses the video encoder
    [self add:m title:@"Quit Tab Cast" action:@selector(quit:)];
}

- (NSString *)shorten:(NSString *)s { return s.length > 60 ? [[s substringToIndex:59] stringByAppendingString:@"…"] : s; }

- (NSMenu *)windowMenu:(SEL)action excludingDisplay:(CGDirectDisplayID)display {
    NSMenu *sub = [NSMenu new];
    NSString *why = nil;
    _windows = [TCEngine castableWindowsError:&why] ?: @[];
    if (!_windows.count) { [self note:sub text:why ?: @"No windows"]; return sub; }
    CGRect tv = display ? CGDisplayBounds(display) : CGRectNull;
    for (SCWindow *w in _windows) {
        if (display && CGRectContainsPoint(tv, CGPointMake(CGRectGetMidX(w.frame), CGRectGetMidY(w.frame)))) continue;
        NSString *t = w.title.length ? [NSString stringWithFormat:@"%@ — %@", w.owningApplication.applicationName, [self shorten:w.title]]
                                     : w.owningApplication.applicationName;
        NSMenuItem *i = [self add:sub title:t action:action];
        i.representedObject = w;
        NSRunningApplication *app = [NSRunningApplication runningApplicationWithProcessIdentifier:w.owningApplication.processID];
        NSImage *icon = [app.icon copy];
        icon.size = NSMakeSize(16, 16);
        i.image = icon;
    }
    return sub;
}

// ---------------------------------------------------------------------------- the TV link

- (void)alert:(NSString *)title text:(NSString *)text {
    [NSApp activateIgnoringOtherApps:YES];
    NSAlert *a = [NSAlert new];
    a.messageText = title;
    a.informativeText = text ?: @"";
    [a runModal];
}

- (NSString *)ask:(NSString *)title text:(NSString *)text value:(NSString *)value {
    [NSApp activateIgnoringOtherApps:YES];
    NSAlert *a = [NSAlert new];
    a.messageText = title;
    a.informativeText = text;
    [a addButtonWithTitle:@"OK"];
    [a addButtonWithTitle:@"Cancel"];
    NSTextField *f = [[NSTextField alloc] initWithFrame:NSMakeRect(0, 0, 240, 24)];
    f.stringValue = value ?: @"";
    a.accessoryView = f;
    a.window.initialFirstResponder = f;
    if ([a runModal] != NSAlertFirstButtonReturn) return nil;
    return [f.stringValue stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceCharacterSet];
}

static void onLinkMessage(int type, int flags, const uint8_t *p, uint32_t len, void *ctx) {
    (void)flags;
    TCApp *app = (__bridge TCApp *)ctx;
    if (type == TC_KEYFRAME) [app->_engine keyFrameWanted];
    else if (type == TC_REPORT) {
        NSData *d = [NSData dataWithBytes:p length:len];
        dispatch_async(dispatch_get_main_queue(), ^{ [app saveReport:d]; });
    }
}

/** A paired link to the TV, or nil after telling the user why. Pairs with the TV's code when needed. */
- (TcLink *)connect {
    if (_link && tc_alive(_link)) return _link;
    if (_link) { tc_close(_link); _link = NULL; }
    NSUserDefaults *d = NSUserDefaults.standardUserDefaults;
    NSString *host = [d stringForKey:kHost];
    if (!host) { [self chooseTv:nil]; host = [d stringForKey:kHost]; if (!host) return NULL; }
    NSString *name = [NSHost currentHost].localizedName ?: @"Mac";
    NSString *app = [NSString stringWithFormat:@"Tab Cast for Mac %@", NSBundle.mainBundle.infoDictionary[@"CFBundleShortVersionString"]];
    char err[300];
    TcLink *l = tc_connect(host.UTF8String, TC_PORT, name.UTF8String, app.UTF8String, TCTokenFor(host).UTF8String, 4000, err, sizeof err);
    if (!l) { [self alert:@"Can't reach the TV" text:[NSString stringWithFormat:@"%s.", err]]; return NULL; }
    const char *tvName = tc_hello(l, "name");
    if (tvName) [d setObject:[NSString stringWithUTF8String:tvName] forKey:kTvName];
    const char *paired = tc_hello(l, "paired");
    if (!paired || strcmp(paired, "true")) {
        // The TV shows a code; keep the link alive (the TV closes a silent one after 10 s) while it is typed.
        __block BOOL typing = YES;
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
            while (typing) {
                uint8_t b[8]; uint64_t t = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
                for (int i = 0; i < 8; i++) b[i] = (uint8_t)(t >> (56 - 8 * i));
                tc_send(l, TC_PING, 0, 0, b, 8);
                [NSThread sleepForTimeInterval:2];
            }
        });
        NSString *code = [self ask:@"Pair with the TV"
                              text:@"Type the 6-digit code the TV shows. No code on the TV? Press \"Pair a device\" on Tab Cast TV's start screen."
                             value:@""];
        char tok[200];
        BOOL ok = code.length && tc_pair(l, code.UTF8String, name.UTF8String, 10000, tok, sizeof tok, err, sizeof err);
        typing = NO;
        if (!ok) { tc_close(l); if (code.length) [self alert:@"Not paired" text:[NSString stringWithFormat:@"%s.", err]]; return NULL; }
        TCSetToken(host, [NSString stringWithUTF8String:tok]);
    }
    tc_start_reader(l, onLinkMessage, (__bridge void *)self);
    _link = l;
    return l;
}

- (void)closeLink { if (_link) { tc_close(_link); _link = NULL; } }

// ---------------------------------------------------------------------------- actions

- (void)sendTab:(NSMenuItem *)i {
    NSDictionary *tab = i.representedObject;
    if (!tab) return;
    if (_engine.casting) [self stopCast:nil];
    TcLink *l = [self connect];
    if (!l) return;
    NSString *kv = [NSString stringWithFormat:@"url=%@\ntitle=%@", tab[@"url"],
                    [tab[@"title"] stringByReplacingOccurrencesOfString:@"\n" withString:@" "]];
    tc_send_kv(l, 10 /* OPEN */, kv.UTF8String);
    _status = [NSString stringWithFormat:@"Sent to the TV: %@ (pause it on the Mac)", [self shorten:tab[@"title"]]];
    // Give the TV back to the tablet: the page plays on without this link.
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 2 * NSEC_PER_SEC), dispatch_get_main_queue(), ^{ if (!self->_engine.casting) [self closeLink]; });
}

- (void)startCast:(TCMode)mode window:(SCWindow *)w {
    TcLink *l = [self connect];
    if (!l) return;
    NSUserDefaults *d = NSUserDefaults.standardUserDefaults;
    NSString *err = nil;
    const char *codecs = tc_hello(l, "codecs");
    if (![_engine startOn:l tvCodecs:codecs ? @(codecs) : @"" mode:mode window:w quality:(TCQuality)[d integerForKey:kQuality]
                  muteMac:[d boolForKey:kMute] error:&err]) {
        [self closeLink];
        [self alert:@"Can't cast" text:err];
        return;
    }
    _status = nil;
    TCStayAwake(YES, [d boolForKey:kScreenOn]);
    [self setIcon:YES];
    if (mode == TCModeSecondScreen)
        [self alert:@"The TV is now a second screen"
               text:@"Use \"Move a Window to the TV\" in this menu to put a game or a video on it (or drag a window off the right edge of the screen). Your Mac's screen stays free."];
}

- (void)castWindow:(NSMenuItem *)i { [self startCast:TCModeWindow window:i.representedObject]; }
- (void)castScreen:(id)s { (void)s; [self startCast:TCModeScreen window:nil]; }
- (void)castSecond:(id)s { (void)s; [self startCast:TCModeSecondScreen window:nil]; }

- (void)moveWindow:(NSMenuItem *)i {
    SCWindow *w = i.representedObject;
    NSString *why = nil;
    if (!TCMoveWindowToDisplay(w.owningApplication.processID, w.title, _engine.tvDisplay, &why)) [self alert:@"Can't move the window" text:why];
}

- (void)stopCast:(id)s {
    (void)s;
    [_engine stop];
    [self closeLink];
    TCStayAwake(NO, NO);
    [self setIcon:NO];
    _status = nil;
}

- (void)castEnded:(NSString *)why {
    [self closeLink];
    TCStayAwake(NO, NO);
    [self setIcon:NO];
    _status = why;
}

- (void)toggleMute:(id)s { (void)s; NSUserDefaults *d = NSUserDefaults.standardUserDefaults; [d setBool:![d boolForKey:kMute] forKey:kMute]; }
- (void)toggleScreen:(id)s { (void)s; NSUserDefaults *d = NSUserDefaults.standardUserDefaults; [d setBool:![d boolForKey:kScreenOn] forKey:kScreenOn]; }
- (void)setQuality:(NSMenuItem *)i { [NSUserDefaults.standardUserDefaults setInteger:i.tag forKey:kQuality]; }

- (void)chooseTv:(id)s {
    (void)s;
    NSUserDefaults *d = NSUserDefaults.standardUserDefaults;
    NSString *host = [self ask:@"The TV's address" text:@"Open Tab Cast TV: its start screen shows the address to type here. "
                                                         @"This Mac must be on the same Wi-Fi as the TV." value:[d stringForKey:kHost]];
    if (!host.length) return;
    for (NSString *scheme in @[ @"http://", @"https://" ]) // typed as the TV shows the web remote
        if ([host.lowercaseString hasPrefix:scheme]) host = [host substringFromIndex:scheme.length];
    host = [[host componentsSeparatedByString:@"/"].firstObject componentsSeparatedByString:@":"].firstObject; // the web remote's port isn't the link's
    if (_engine.casting) [self stopCast:nil];
    [self closeLink];
    [d setObject:host forKey:kHost];
    [d removeObjectForKey:kTvName];
}

- (void)forgetPairing:(id)s {
    (void)s;
    NSString *host = [NSUserDefaults.standardUserDefaults stringForKey:kHost];
    if (host) TCForgetToken(host);
    [self stopCast:nil];
}

- (void)tvReport:(id)s {
    (void)s;
    TcLink *l = [self connect];
    if (!l) return;
    tc_send_kv(l, TC_CMD, "c=report\nv=Tab Cast for Mac");
}

- (void)saveReport:(NSData *)d {
    NSDateFormatter *f = [NSDateFormatter new];
    f.dateFormat = @"yyyy-MM-dd HH.mm.ss";
    NSString *path = [NSString stringWithFormat:@"%@/Downloads/Tab Cast TV log %@.txt", NSHomeDirectory(), [f stringFromDate:[NSDate date]]];
    [d writeToFile:path atomically:YES];
    [[NSWorkspace sharedWorkspace] activateFileViewerSelectingURLs:@[ [NSURL fileURLWithPath:path] ]];
    if (!_engine.casting) [self closeLink];
}

/** "Check This Mac": the report goes to Downloads (to send it on); the alert says the main points. */
- (void)checkMac:(id)s {
    (void)s;
    NSString *r = TCCheckThisMac();
    NSDateFormatter *f = [NSDateFormatter new];
    f.dateFormat = @"yyyy-MM-dd HH.mm.ss";
    NSString *path = [NSString stringWithFormat:@"%@/Downloads/Tab Cast Mac check %@.txt", NSHomeDirectory(), [f stringFromDate:[NSDate date]]];
    [r writeToFile:path atomically:YES encoding:NSUTF8StringEncoding error:NULL];
    NSMutableArray *main = [NSMutableArray array];
    for (NSString *line in [r componentsSeparatedByString:@"\n"]) {
        NSString *t = [line stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceCharacterSet];
        if ([t hasPrefix:@"Model:"] || [t hasPrefix:@"Best quality:"] || [t hasPrefix:@"Mac silent while casting:"] || [t hasPrefix:@"Available:"])
            [main addObject:[t hasPrefix:@"Available:"] ? [@"Second screen " stringByAppendingString:[t lowercaseString]] : t];
    }
    [self alert:@"This Mac's check is in Downloads"
           text:[[main componentsJoinedByString:@"\n"] stringByAppendingString:@"\n\nSend the file in Downloads (\"Tab Cast Mac check …\") if someone asked for it."]];
    [[NSWorkspace sharedWorkspace] activateFileViewerSelectingURLs:@[ [NSURL fileURLWithPath:path] ]];
}

- (void)quit:(id)s { (void)s; [self stopCast:nil]; [NSApp terminate:nil]; }

- (void)applicationWillTerminate:(NSNotification *)n { (void)n; [_engine stop]; [self closeLink]; TCStayAwake(NO, NO); }

@end

int main(int argc, const char **argv) {
    (void)argc; (void)argv;
    @autoreleasepool {
        NSApplication *app = [NSApplication sharedApplication];
        app.activationPolicy = NSApplicationActivationPolicyAccessory; // menu bar only, no Dock icon
        TCApp *d = [TCApp new];
        app.delegate = d;
        [app run];
    }
    return 0;
}
