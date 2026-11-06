/* LGPL-2.1-or-later. Mirror IJK/FFmpeg logs to the app-owned asynchronous sink. */
#include <jni.h>
#include <android/log.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <pthread.h>
#include <time.h>

static JavaVM *log_vm;
static jclass log_class;
static jmethodID log_append;
static pthread_mutex_t log_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_key_t log_thread_key;
static int log_thread_key_ready;
static time_t log_window;
static unsigned log_info_count, log_error_count;
static jlong log_dropped;

static void detach_log_thread(void *value)
{
    if (value && log_vm) (*log_vm)->DetachCurrentThread(log_vm);
}

JNIEXPORT jboolean JNICALL Java_xiao_bu_tv_AppLog_nativeStart(JNIEnv *env, jclass type)
{
    pthread_mutex_lock(&log_lock);
    if (!log_class) {
        jmethodID method = (*env)->GetStaticMethodID(env, type, "appendNative", "(I[B)V");
        if (method) {
            jclass global = (*env)->NewGlobalRef(env, type);
            if (global) {
                (*env)->GetJavaVM(env, &log_vm);
                log_thread_key_ready = pthread_key_create(&log_thread_key, detach_log_thread) == 0;
                log_append = method;
                log_class = global;
            }
        }
    }
    jboolean ready = log_class != NULL;
    pthread_mutex_unlock(&log_lock);
    return ready;
}

JNIEXPORT jlong JNICALL Java_xiao_bu_tv_AppLog_nativeDroppedCount(JNIEnv *env, jclass type)
{
    pthread_mutex_lock(&log_lock);
    jlong dropped = log_dropped;
    pthread_mutex_unlock(&log_lock);
    return dropped;
}

void ntv_app_log_vprint(int priority, const char *tag, const char *format, va_list args)
{
    va_list copy;
    va_copy(copy, args);
    __android_log_vprint(priority, tag, format, copy);
    va_end(copy);
    // Skip per-frame debug traces before formatting, allocating or entering the VM.
    if (priority < ANDROID_LOG_INFO) return;
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    pthread_mutex_lock(&log_lock);
    JavaVM *vm = log_vm;
    jclass type = log_class;
    jmethodID method = log_append;
    if (now.tv_sec != log_window) {
        log_window = now.tv_sec;
        log_info_count = log_error_count = 0;
    }
    // Reserve a separate budget for warnings/errors so an INFO burst cannot hide them.
    unsigned *count = priority >= ANDROID_LOG_WARN ? &log_error_count : &log_info_count;
    int allowed = *count < 100;
    if (allowed) (*count)++;
    else log_dropped++;
    pthread_mutex_unlock(&log_lock);
    if (!type || !allowed) return;
    char text[8192];
    vsnprintf(text, sizeof(text), format, args);
    JNIEnv *env = NULL;
    int attached = 0;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_4) != JNI_OK) {
        if ((*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK) return;
        // Native decoder threads attach once and detach at thread exit, rather than per log.
        if (!log_thread_key_ready || pthread_setspecific(log_thread_key, (void *)1) != 0)
            attached = 1;
    }
    // Do not alter an exception already pending in the caller.
    if (!(*env)->ExceptionCheck(env)) {
        size_t length = strlen(text);
        jbyteArray bytes = (*env)->NewByteArray(env, (jsize)length);
        if (bytes) {
            (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)length, (const jbyte *)text);
            if (!(*env)->ExceptionCheck(env))
                (*env)->CallStaticVoidMethod(env, type, method, priority, bytes);
            (*env)->DeleteLocalRef(env, bytes);
        }
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    }
    if (attached) (*vm)->DetachCurrentThread(vm);
}

void ntv_app_log_print(int priority, const char *tag, const char *format, ...)
{
    va_list args;
    va_start(args, format);
    ntv_app_log_vprint(priority, tag, format, args);
    va_end(args);
}
