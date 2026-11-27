# SPDX-License-Identifier: GPL-3.0-or-later
# Tab Cast for Mac: a menu-bar app for Tab Cast TV. Universal (Apple silicon and Intel), macOS 13+.
#   make            build "build/Tab Cast.app"
#   make test       the C tests (send queue), with sanitizers
#   make install    copy to ~/Applications as the only registered copy (tools/mac-install.sh)

CC      = clang
CFLAGS  = -O2 -Wall -Wextra -fobjc-arc -arch arm64 -arch x86_64 -mmacosx-version-min=13.0 -Isrc
LIBS    = -framework Foundation -framework AppKit -framework ScreenCaptureKit -framework VideoToolbox \
          -framework CoreMedia -framework CoreVideo -framework CoreGraphics -framework CoreAudio \
          -framework ApplicationServices -framework IOKit -framework Security
SRCS    = src/app.m src/engine.m src/macos.m src/check.m src/link.c src/sendq.c
HDRS    = src/check.h src/engine.h src/macos.h src/link.h src/sendq.h
APP     = build/Tab Cast.app

# Signed with the local code-signing identity when the login keychain has one: macOS then ties the screen, sound,
# network and accessibility permissions to the certificate, so they survive rebuilds. Ad hoc otherwise.
SIGN_NAME := Local Mac Apps Signing
ifeq ($(origin SIGN_ID),undefined)
SIGN_ID := $(shell security find-identity -p codesigning 2>/dev/null | grep -q '"$(SIGN_NAME)"' && echo '$(SIGN_NAME)' || echo -)
endif

all: build/app.stamp

build/tabcast: $(SRCS) $(HDRS)
	@mkdir -p build
	$(CC) $(CFLAGS) -o $@ $(SRCS) $(LIBS)

# A stamp file: make can't name a target with a space ("Tab Cast.app").
build/app.stamp: build/tabcast Info.plist
	@mkdir -p "$(APP)/Contents/MacOS"
	cp Info.plist "$(APP)/Contents/Info.plist"
	cp build/tabcast "$(APP)/Contents/MacOS/tabcast"
	codesign --force --sign "$(SIGN_ID)" "$(APP)" 2>/dev/null || codesign --force --sign - "$(APP)"
	@touch $@

test:
	@mkdir -p build
	$(CC) -O1 -g -Wall -Wextra -fsanitize=address,undefined -o build/test_sendq tests/test_sendq.c src/sendq.c
	./build/test_sendq

install: build/app.stamp
	tools/mac-install.sh "$(APP)"

# For another Mac (also an Intel one: the app is universal): the app zipped with its signature, plus the steps to open
# it the first time (it isn't notarized, so macOS asks once). build/dist/
VERSION := $(shell /usr/libexec/PlistBuddy -c 'Print CFBundleShortVersionString' Info.plist)
dist: build/app.stamp
	@rm -rf build/dist && mkdir -p build/dist
	codesign --verify --strict "$(APP)"
	ditto -c -k --sequesterRsrc --keepParent "$(APP)" "build/dist/Tab Cast for Mac $(VERSION).zip"
	cp dist/INSTALL.txt "build/dist/Install Tab Cast for Mac.txt"
	@echo "build/dist: $$(ls build/dist | tr '\n' ' ')"

clean:
	rm -rf build

.PHONY: all test install dist clean
