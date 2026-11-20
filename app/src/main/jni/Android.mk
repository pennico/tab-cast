# libtcmirror.so (0.5.0 "Mirror screen") = the shared C colour conversion (native/yuv.c) + its JNI glue.
LOCAL_PATH := $(call my-dir)
NATIVE := $(LOCAL_PATH)/../../../../native

include $(CLEAR_VARS)
LOCAL_MODULE := tcmirror
LOCAL_SRC_FILES := mirror_jni.c $(NATIVE)/yuv.c
LOCAL_C_INCLUDES := $(NATIVE)
LOCAL_CFLAGS := -O3 -Wall -Wextra -Werror -std=c11
LOCAL_ARM_MODE := arm
LOCAL_ARM_NEON := true
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
