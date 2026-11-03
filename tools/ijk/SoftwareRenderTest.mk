LOCAL_PATH := $(call my-dir)
NTV_REPO := $(LOCAL_PATH)/../..
NTV_IJK := $(NTV_REPO)/.codex-tmp/ijkplayer-0.8.8
ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
NTV_FF_ARCH := arm64
else
NTV_FF_ARCH := armv7a
endif
include $(CLEAR_VARS)
LOCAL_MODULE := ijkffmpeg
LOCAL_SRC_FILES := $(NTV_RENDER_LIBS)/$(TARGET_ARCH_ABI)/libijkffmpeg.so
include $(PREBUILT_SHARED_LIBRARY)
include $(CLEAR_VARS)
LOCAL_MODULE := ijksdl
LOCAL_SRC_FILES := $(NTV_RENDER_LIBS)/$(TARGET_ARCH_ABI)/libijksdl.so
include $(PREBUILT_SHARED_LIBRARY)
include $(CLEAR_VARS)
LOCAL_MODULE := software-render-test
LOCAL_SRC_FILES := test-software-render.c
LOCAL_C_INCLUDES := $(NTV_IJK)/ijkmedia $(NTV_IJK)/ijkmedia/ijksdl \
    $(NTV_IJK)/android/contrib/build/ffmpeg-$(NTV_FF_ARCH)/output/include $(NTV_RENDER_TEST)
LOCAL_CFLAGS := -std=c99 -UNDEBUG -fPIE
LOCAL_LDFLAGS := -fPIE -pie
LOCAL_SHARED_LIBRARIES := ijksdl ijkffmpeg
LOCAL_LDLIBS := -lEGL -lGLESv2 -llog -landroid -lm
include $(BUILD_EXECUTABLE)
