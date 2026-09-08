LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := ntvquickjs
LOCAL_SRC_FILES := bridge.c vendor/quickjs.c vendor/dtoa.c vendor/libregexp.c vendor/libunicode.c vendor/cutils.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/vendor
LOCAL_CFLAGS := -std=gnu11 -Os -fvisibility=hidden -D_GNU_SOURCE -DNTV_SINGLE_THREADED -DCONFIG_VERSION=\"2026-06-04\" -ffunction-sections -fdata-sections
LOCAL_CFLAGS += -include $(LOCAL_PATH)/compat.h
LOCAL_LDFLAGS := -Wl,--gc-sections
LOCAL_LDLIBS := -lm
include $(BUILD_SHARED_LIBRARY)
