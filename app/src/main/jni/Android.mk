LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := cctv_h5e
LOCAL_SRC_FILES := \
    cctv_h5e_decryptor.c \
    generated/cctv_h5e_wasm.c \
    wasm-rt/wasm-rt-impl.c \
    wasm-rt/wasm-rt-mem-impl.c
LOCAL_C_INCLUDES := \
    $(LOCAL_PATH)/generated \
    $(LOCAL_PATH)/wasm-rt
LOCAL_CFLAGS := -O3 -DNDEBUG -std=c99 -DWASM_RT_USE_MMAP=0
LOCAL_LDLIBS := -llog -lm
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := ysp_keygen
LOCAL_SRC_FILES := \
    ysp_keygen.c \
    generated_ysp/ysp_keygen_wasm.c \
    ysp-rt/wasm-rt-impl.c
LOCAL_C_INCLUDES := \
    $(LOCAL_PATH)/generated_ysp \
    $(LOCAL_PATH)/ysp-rt
LOCAL_CFLAGS := -O3 -DNDEBUG -std=c99 -DWASM_RT_USE_MMAP=0 -fvisibility=hidden
LOCAL_LDLIBS := -llog -lm
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := cmg_decrypt
LOCAL_SRC_FILES := \
    cmg_decryptor.c \
    generated_cmg/cmg_wasm.c \
    cmg-rt/wasm-rt-impl.c
LOCAL_C_INCLUDES := \
    $(LOCAL_PATH)/generated_cmg \
    $(LOCAL_PATH)/cmg-rt
LOCAL_CFLAGS := -O3 -DNDEBUG -std=c99 -DWASM_RT_MEMCHECK_SIGNAL_HANDLER=0 \
    -Dwasm_rt_allocate_memory=cmg_wasm_rt_allocate_memory \
    -Dwasm_rt_allocate_table=cmg_wasm_rt_allocate_table \
    -Dwasm_rt_call_stack_depth=cmg_wasm_rt_call_stack_depth \
    -Dwasm_rt_grow_memory=cmg_wasm_rt_grow_memory \
    -Dwasm_rt_register_func_type=cmg_wasm_rt_register_func_type \
    -Dwasm_rt_trap=cmg_wasm_rt_trap \
    -Dg_jmp_buf=cmg_wasm_g_jmp_buf \
    -Dg_saved_call_stack_depth=cmg_wasm_g_saved_call_stack_depth \
    -Dg_func_types=cmg_wasm_g_func_types \
    -Dg_func_type_count=cmg_wasm_g_func_type_count
LOCAL_LDLIBS := -llog -lm
include $(BUILD_SHARED_LIBRARY)
