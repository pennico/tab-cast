// SPDX-License-Identifier: GPL-3.0-or-later
// Tab Cast for Mac: "Check This Mac" — what this Mac can do for casting, as a short plain-text report (model, macOS,
// hardware video encoders and how fast they are at 1080p, muting the cast sound, the second screen, permissions).
// No names, serial numbers or addresses: it is meant to be sent to someone.

#import <Foundation/Foundation.h>

/** Runs the checks (about 3 s: it encodes a few seconds of test picture) and returns the report. Main thread. */
NSString *TCCheckThisMac(void);

/** YES on an Intel Mac (also when this app runs translated, which it shouldn't: it is universal). */
BOOL TCIsIntelMac(void);
