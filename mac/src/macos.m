// SPDX-License-Identifier: GPL-3.0-or-later
// Tab Cast for Mac: small macOS helpers (see macos.h).

#import "macos.h"
#import <AppKit/AppKit.h>
#import <ApplicationServices/ApplicationServices.h>
#import <IOKit/pwr_mgt/IOPMLib.h>
#import <Security/Security.h>

// ------------------------------------------------------------------------------------------------ the front tab

/** Browsers that answer AppleScript: bundle id → the script that returns "url<LF>title" of the front tab. */
static NSDictionary<NSString *, NSString *> *browsers(void) {
    NSString *chromium = @"tell application id \"%@\" to if (count of windows) > 0 then return (URL of active tab of front window) & linefeed & (title of active tab of front window)";
    NSString *safari = @"tell application id \"%@\" to if (count of documents) > 0 then return (URL of front document) & linefeed & (name of front document)";
    return @{
        @"com.brave.Browser": chromium, @"com.google.Chrome": chromium, @"com.microsoft.edgemac": chromium,
        @"com.vivaldi.Vivaldi": chromium, @"company.thebrowser.Browser": chromium, @"com.apple.Safari": safari,
    };
}

NSDictionary<NSString *, NSString *> *TCFrontTab(NSString **why) {
    NSDictionary *scripts = browsers();
    NSMutableArray<NSRunningApplication *> *candidates = [NSMutableArray array];
    NSRunningApplication *front = NSWorkspace.sharedWorkspace.frontmostApplication;
    if (front.bundleIdentifier && scripts[front.bundleIdentifier]) [candidates addObject:front];
    for (NSRunningApplication *a in NSWorkspace.sharedWorkspace.runningApplications)
        if (a.bundleIdentifier && scripts[a.bundleIdentifier] && ![candidates containsObject:a]) [candidates addObject:a];
    if (!candidates.count) { if (why) *why = @"No browser is open (Brave, Safari, Chrome, Edge, Arc or Vivaldi)"; return nil; }
    NSString *lastError = nil;
    for (NSRunningApplication *a in candidates) {
        NSString *src = [NSString stringWithFormat:scripts[a.bundleIdentifier], a.bundleIdentifier];
        NSDictionary *err = nil;
        NSAppleEventDescriptor *r = [[[NSAppleScript alloc] initWithSource:src] executeAndReturnError:&err];
        if (err) { lastError = err[NSAppleScriptErrorMessage]; continue; }
        NSArray *parts = [r.stringValue componentsSeparatedByString:@"\n"];
        NSString *url = parts.firstObject;
        if (![url hasPrefix:@"http://"] && ![url hasPrefix:@"https://"]) continue;
        return @{ @"url": url, @"title": parts.count > 1 ? parts[1] : @"", @"browser": a.localizedName ?: @"" };
    }
    if (why) *why = lastError ? [NSString stringWithFormat:@"The browser didn't tell its tab (%@): allow Tab Cast in System "
                                 @"Settings › Privacy & Security › Automation", lastError]
                              : @"No web page is open in a browser";
    return nil;
}

// ------------------------------------------------------------------------------------------------ moving a window

BOOL TCAccessibilityAllowed(BOOL prompt) {
    NSDictionary *o = @{ (__bridge NSString *)kAXTrustedCheckOptionPrompt: @(prompt) };
    return AXIsProcessTrustedWithOptions((__bridge CFDictionaryRef)o);
}

BOOL TCMoveWindowToDisplay(pid_t pid, NSString *title, CGDirectDisplayID display, NSString **why) {
    if (!TCAccessibilityAllowed(YES)) {
        if (why) *why = @"Allow Tab Cast in System Settings › Privacy & Security › Accessibility, then try again";
        return NO;
    }
    AXUIElementRef app = AXUIElementCreateApplication(pid);
    CFArrayRef wins = NULL;
    AXUIElementCopyAttributeValue(app, kAXWindowsAttribute, (CFTypeRef *)&wins);
    AXUIElementRef best = NULL;
    double bestArea = -1;
    for (CFIndex i = 0; wins && i < CFArrayGetCount(wins); i++) {
        AXUIElementRef w = CFArrayGetValueAtIndex(wins, i);
        CFStringRef t = NULL;
        AXUIElementCopyAttributeValue(w, kAXTitleAttribute, (CFTypeRef *)&t);
        NSString *wt = t ? (__bridge_transfer NSString *)t : @"";
        AXValueRef sz = NULL; CGSize size = CGSizeZero;
        if (AXUIElementCopyAttributeValue(w, kAXSizeAttribute, (CFTypeRef *)&sz) == kAXErrorSuccess && sz) { AXValueGetValue(sz, kAXValueTypeCGSize, &size); CFRelease(sz); }
        BOOL match = title.length ? [wt isEqualToString:title] : YES;
        if (match && size.width * size.height > bestArea) { best = w; bestArea = size.width * size.height; }
    }
    BOOL ok = NO;
    if (best) {
        // A full-screen window must leave full screen before it can move.
        CFBooleanRef fs = NULL;
        if (AXUIElementCopyAttributeValue(best, CFSTR("AXFullScreen"), (CFTypeRef *)&fs) == kAXErrorSuccess && fs) {
            if (CFBooleanGetValue(fs)) { AXUIElementSetAttributeValue(best, CFSTR("AXFullScreen"), kCFBooleanFalse); [NSThread sleepForTimeInterval:0.8]; }
            CFRelease(fs);
        }
        CGRect b = CGDisplayBounds(display);
        AXValueRef pos = AXValueCreate(kAXValueTypeCGPoint, &b.origin);
        AXValueRef size = AXValueCreate(kAXValueTypeCGSize, &b.size);
        ok = AXUIElementSetAttributeValue(best, kAXPositionAttribute, pos) == kAXErrorSuccess;
        AXUIElementSetAttributeValue(best, kAXSizeAttribute, size);
        AXUIElementSetAttributeValue(best, kAXPositionAttribute, pos); // again: some apps move it back while resizing
        CFRelease(pos); CFRelease(size);
        if (!ok && why) *why = @"The app didn't let its window move";
    } else if (why) *why = @"That window isn't there any more";
    if (wins) CFRelease(wins);
    CFRelease(app);
    return ok;
}

// ------------------------------------------------------------------------------------------------ pairing token

static NSDictionary *tokenQuery(NSString *host) {
    return @{ (__bridge id)kSecClass: (__bridge id)kSecClassGenericPassword,
              (__bridge id)kSecAttrService: @"Tab Cast for Mac",
              (__bridge id)kSecAttrAccount: host };
}

NSString *TCTokenFor(NSString *host) {
    NSMutableDictionary *q = [tokenQuery(host) mutableCopy];
    q[(__bridge id)kSecReturnData] = @YES;
    CFTypeRef data = NULL;
    if (SecItemCopyMatching((__bridge CFDictionaryRef)q, &data) != errSecSuccess || !data) return nil;
    return [[NSString alloc] initWithData:(__bridge_transfer NSData *)data encoding:NSUTF8StringEncoding];
}

void TCSetToken(NSString *host, NSString *token) {
    TCForgetToken(host);
    NSMutableDictionary *q = [tokenQuery(host) mutableCopy];
    q[(__bridge id)kSecValueData] = [token dataUsingEncoding:NSUTF8StringEncoding];
    q[(__bridge id)kSecAttrLabel] = [NSString stringWithFormat:@"Tab Cast TV at %@", host];
    SecItemAdd((__bridge CFDictionaryRef)q, NULL);
}

void TCForgetToken(NSString *host) { SecItemDelete((__bridge CFDictionaryRef)tokenQuery(host)); }

// ------------------------------------------------------------------------------------------------ staying awake

void TCStayAwake(BOOL awake, BOOL screenOn) {
    static IOPMAssertionID held;
    if (held) { IOPMAssertionRelease(held); held = 0; }
    if (!awake) return;
    IOPMAssertionCreateWithName(screenOn ? kIOPMAssertionTypePreventUserIdleDisplaySleep : kIOPMAssertionTypePreventUserIdleSystemSleep,
                                kIOPMAssertionLevelOn, CFSTR("Tab Cast is casting to the TV"), &held);
}
