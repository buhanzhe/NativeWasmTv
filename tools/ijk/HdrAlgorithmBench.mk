LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := ntv-hdr-algorithm-bench
LOCAL_SRC_FILES := bench-hdr-algorithm.c
LOCAL_CFLAGS := -std=c99 -O2 -fPIE
LOCAL_LDFLAGS := -fPIE -pie
LOCAL_LDLIBS := -lEGL -lGLESv2
include $(BUILD_EXECUTABLE)
include $(CLEAR_VARS)
LOCAL_MODULE := ntv-hdr-cache-bench
LOCAL_SRC_FILES := bench-hdr-cache.c
LOCAL_CFLAGS := -std=c99 -O2 -fPIE
LOCAL_LDFLAGS := -fPIE -pie
LOCAL_LDLIBS := -lEGL -lGLESv2
include $(BUILD_EXECUTABLE)
include $(CLEAR_VARS)
LOCAL_MODULE := ntv-hdr-lut-bench
LOCAL_SRC_FILES := bench-hdr-lut.c
LOCAL_CFLAGS := -std=c99 -O2 -fPIE
LOCAL_LDFLAGS := -fPIE -pie
LOCAL_LDLIBS := -lEGL -lGLESv2
include $(BUILD_EXECUTABLE)
