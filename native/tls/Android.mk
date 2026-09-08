LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := ntvtls
LOCAL_SRC_FILES := bridge.c $(patsubst $(LOCAL_PATH)/%,%,$(wildcard $(LOCAL_PATH)/vendor/library/*.c))
LOCAL_C_INCLUDES := $(LOCAL_PATH) $(LOCAL_PATH)/vendor/include $(LOCAL_PATH)/vendor/library
LOCAL_CFLAGS := -std=gnu99 -Os -fvisibility=hidden -DMBEDTLS_CONFIG_FILE=\"config.h\" -ffunction-sections -fdata-sections
LOCAL_LDFLAGS := -Wl,--gc-sections
include $(BUILD_SHARED_LIBRARY)
