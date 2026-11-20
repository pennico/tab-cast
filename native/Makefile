# Host build of the shared C code (the app builds it with the NDK through ndk-build, see probe/src/main/jni).
CFLAGS ?= -O2 -g -Wall -Wextra -Werror -std=c11
SAN = -fsanitize=address,undefined -fno-omit-frame-pointer

test: build/yuv_test
	./build/yuv_test

build/yuv_test: yuv.c yuv.h test/yuv_test.c
	mkdir -p build
	$(CC) $(CFLAGS) $(SAN) -I. -o $@ yuv.c test/yuv_test.c -lm

clean:
	rm -rf build

.PHONY: test clean
