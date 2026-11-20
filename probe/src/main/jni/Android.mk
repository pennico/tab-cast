# libtcprobe.so = the shared C colour conversion (native/yuv.c) + the probe's JNI glue.
LOCAL_PATH := $(call my-dir)
NATIVE := $(LOCAL_PATH)/../../../../native

include $(CLEAR_VARS)
LOCAL_MODULE := tcprobe
LOCAL_SRC_FILES := probe_jni.c $(NATIVE)/yuv.c
LOCAL_C_INCLUDES := $(NATIVE)
LOCAL_CFLAGS := -O3 -Wall -Wextra -Werror -std=c11
LOCAL_ARM_MODE := arm
LOCAL_ARM_NEON := true
LOCAL_LDLIBS := -ljnigraphics -llog
include $(BUILD_SHARED_LIBRARY)
