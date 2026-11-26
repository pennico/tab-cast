// SPDX-License-Identifier: GPL-3.0-or-later
// Tab Cast for Mac: small macOS helpers (browser tab, moving a window, the pairing token, staying awake).

#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>

/** The front tab of a running browser (the frontmost app first): @{url, title, browser}, or nil with a reason. */
NSDictionary<NSString *, NSString *> *TCFrontTab(NSString **why);

/** Accessibility (needed to move windows): YES when allowed; with prompt, macOS asks once. */
BOOL TCAccessibilityAllowed(BOOL prompt);

/** Moves pid's window with this title (the largest when title is empty) onto display and fills it. */
BOOL TCMoveWindowToDisplay(pid_t pid, NSString *title, CGDirectDisplayID display, NSString **why);

/** The pairing token for a TV address, kept in the login keychain; nil when not paired yet. */
NSString *TCTokenFor(NSString *host);
void TCSetToken(NSString *host, NSString *token);
void TCForgetToken(NSString *host);

/** Keeps the Mac awake while casting; screenOn: its screen too. Call with NO, NO to let it sleep again. */
void TCStayAwake(BOOL awake, BOOL screenOn);
