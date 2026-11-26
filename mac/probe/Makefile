# SPDX-License-Identifier: GPL-3.0-or-later
# Tab Cast for Mac, probe 1 (see probe.m). Universal: Apple silicon and Intel Macs. macOS 13+ (ScreenCaptureKit sound).

CC      = clang
CFLAGS  = -O2 -Wall -Wextra -fobjc-arc -arch arm64 -arch x86_64 -mmacosx-version-min=13.0
LIBS    = -framework Foundation -framework ScreenCaptureKit -framework VideoToolbox -framework CoreMedia \
          -framework CoreVideo -framework CoreGraphics

APP     = build/TabCastProbe.app

all: $(APP)/Contents/MacOS/tabcast-probe

build/tabcast-probe: probe.m
	@mkdir -p build
	$(CC) $(CFLAGS) -o $@ $< $(LIBS)

# The app wrapper: macOS gives the screen-recording permission to this app only (ad-hoc signed: a rebuild may ask again).
$(APP)/Contents/MacOS/tabcast-probe: build/tabcast-probe Info.plist
	@mkdir -p "$(APP)/Contents/MacOS"
	cp Info.plist "$(APP)/Contents/Info.plist"
	cp build/tabcast-probe "$(APP)/Contents/MacOS/tabcast-probe"
	codesign --force --sign - "$(APP)"

clean:
	rm -rf build

.PHONY: all clean
