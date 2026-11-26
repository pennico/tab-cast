# SPDX-License-Identifier: GPL-3.0-or-later
# Tab Cast for Mac, probe 1 (see probe.m). Universal: Apple silicon and Intel Macs. macOS 13+ (ScreenCaptureKit sound).

CC      = clang
CFLAGS  = -O2 -Wall -Wextra -fobjc-arc -arch arm64 -arch x86_64 -mmacosx-version-min=13.0
LIBS    = -framework Foundation -framework AppKit -framework ScreenCaptureKit -framework VideoToolbox -framework CoreMedia \
          -framework CoreVideo -framework CoreGraphics

APP     = build/TabCastProbe.app

all: $(APP)/Contents/MacOS/tabcast-probe

build/tabcast-probe: probe.m link.c link.h
	@mkdir -p build
	$(CC) $(CFLAGS) -o $@ probe.m link.c $(LIBS)

# Signed with the local code-signing identity when the login keychain has one (SIGN_NAME, or SIGN_ID=… on the command
# line): macOS then ties the screen-recording permission to the certificate, so it survives rebuilds. Ad hoc otherwise
# (each rebuild then needs the permission again).
SIGN_NAME := Local Mac Apps Signing
ifeq ($(origin SIGN_ID),undefined)
SIGN_ID := $(shell security find-identity -p codesigning 2>/dev/null | grep -q '"$(SIGN_NAME)"' && echo '$(SIGN_NAME)' || echo -)
endif

# The app wrapper: macOS gives the screen-recording permission to this app only.
$(APP)/Contents/MacOS/tabcast-probe: build/tabcast-probe Info.plist
	@mkdir -p "$(APP)/Contents/MacOS"
	cp Info.plist "$(APP)/Contents/Info.plist"
	cp build/tabcast-probe "$(APP)/Contents/MacOS/tabcast-probe"
	codesign --force --sign "$(SIGN_ID)" "$(APP)" 2>/dev/null || codesign --force --sign - "$(APP)"

clean:
	rm -rf build

.PHONY: all clean
