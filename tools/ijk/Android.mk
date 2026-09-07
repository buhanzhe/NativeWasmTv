# Build the modified IJK player and SDL AudioTrack output.
NTV_MAKE_ROOT := $(call my-dir)
NTV_REPO := $(NTV_MAKE_ROOT)/../..
NTV_IJK := $(NTV_REPO)/.codex-tmp/ijkplayer-0.8.8
ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
NTV_FF_ARCH := arm64
else
NTV_FF_ARCH := armv7a
endif
MY_APP_FFMPEG_OUTPUT_PATH := $(NTV_IJK)/android/contrib/build/ffmpeg-$(NTV_FF_ARCH)/output
MY_APP_FFMPEG_INCLUDE_PATH := $(MY_APP_FFMPEG_OUTPUT_PATH)/include

LOCAL_PATH := $(NTV_MAKE_ROOT)
include $(CLEAR_VARS)
LOCAL_MODULE := ijkffmpeg
LOCAL_SRC_FILES := $(realpath $(MY_APP_FFMPEG_OUTPUT_PATH))/libijkffmpeg.so
include $(PREBUILT_SHARED_LIBRARY)

include $(NTV_IJK)/ijkmedia/ijkj4a/Android.mk
include $(NTV_IJK)/ijkmedia/ijkyuv/Android.mk
include $(NTV_IJK)/ijkmedia/ijksdl/Android.mk
include $(NTV_IJK)/ijkprof/android-ndk-profiler-dummy/jni/Android.mk
include $(NTV_IJK)/ijkmedia/ijksoundtouch/Android.mk
include $(NTV_IJK)/ijkmedia/ijkplayer/Android.mk
