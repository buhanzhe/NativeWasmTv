#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
#include "quickjs.h"

typedef struct {
    JNIEnv *env;
    jobject host;
    jmethodID invoke, cancelled, string_ctor, get_bytes;
    jclass string_class;
    jstring utf8;
    int64_t deadline;
} Host;

static int64_t now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (int64_t)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}

static int interrupted(JSRuntime *rt, void *opaque) {
    Host *h = opaque;
    if (now_ms() >= h->deadline) return 1;
    return (*h->env)->CallBooleanMethod(h->env, h->host, h->cancelled)
        || (*h->env)->ExceptionCheck(h->env);
}

static JSValue call_host(JSContext *ctx, JSValueConst self, int argc, JSValueConst *argv, int op) {
    Host *h = JS_GetContextOpaque(ctx);
    JNIEnv *e = h->env;
    if (argc > 8) return JS_ThrowInternalError(ctx, "Too many CJS host arguments");
    if (interrupted(JS_GetRuntime(ctx), h)) return JS_ThrowInternalError(ctx, "CJS execution cancelled or timed out");
    if ((*e)->PushLocalFrame(e, 32) < 0) return JS_EXCEPTION;
    jobjectArray args = (*e)->NewObjectArray(e, argc, h->string_class, NULL);
    for (int i = 0; args && i < argc; i++) {
        size_t size;
        const char *s = JS_ToCStringLen(ctx, &size, argv[i]);
        if (!s) { (*e)->PopLocalFrame(e, NULL); return JS_EXCEPTION; }
        jbyteArray bytes = (*e)->NewByteArray(e, (jsize)size);
        if (bytes) (*e)->SetByteArrayRegion(e, bytes, 0, size, (const jbyte *)s);
        JS_FreeCString(ctx, s);
        jobject str = bytes ? (*e)->NewObject(e, h->string_class, h->string_ctor, bytes, h->utf8) : NULL;
        if (str) (*e)->SetObjectArrayElement(e, args, i, str);
        if (str) (*e)->DeleteLocalRef(e, str);
        if (bytes) (*e)->DeleteLocalRef(e, bytes);
        if ((*e)->ExceptionCheck(e)) break;
    }
    jobject result = NULL;
    if (!(*e)->ExceptionCheck(e) && args)
        result = (*e)->CallObjectMethod(e, h->host, h->invoke, op, args);
    if ((*e)->ExceptionCheck(e)) {
        (*e)->ExceptionClear(e);
        (*e)->PopLocalFrame(e, NULL);
        return JS_ThrowInternalError(ctx, "CJS host call failed");
    }
    JSValue value = JS_UNDEFINED;
    if (result) {
        jbyteArray bytes = (*e)->CallObjectMethod(e, result, h->get_bytes, h->utf8);
        if (bytes && !(*e)->ExceptionCheck(e)) {
            jsize size = (*e)->GetArrayLength(e, bytes);
            jbyte *data = (*e)->GetByteArrayElements(e, bytes, NULL);
            if (data) {
                value = JS_NewStringLen(ctx, (const char *)data, size);
                (*e)->ReleaseByteArrayElements(e, bytes, data, JNI_ABORT);
            }
        }
    }
    (*e)->PopLocalFrame(e, NULL);
    return value;
}

JNIEXPORT void JNICALL Java_xiao_bu_tv_NativeQuickJs_nativeExecute(JNIEnv *e, jclass klass, jbyteArray script, jobject host) {
    JSRuntime *rt = NULL;
    JSContext *ctx = NULL;
    Host h = {0};
    h.env = e; h.host = host; h.deadline = now_ms() + 20000;
    jclass hc = (*e)->GetObjectClass(e, host);
    h.invoke = (*e)->GetMethodID(e, hc, "invoke", "(I[Ljava/lang/String;)Ljava/lang/String;");
    h.cancelled = (*e)->GetMethodID(e, hc, "isCancelled", "()Z");
    h.string_class = (*e)->FindClass(e, "java/lang/String");
    h.string_ctor = (*e)->GetMethodID(e, h.string_class, "<init>", "([BLjava/lang/String;)V");
    h.get_bytes = (*e)->GetMethodID(e, h.string_class, "getBytes", "(Ljava/lang/String;)[B");
    h.utf8 = (*e)->NewStringUTF(e, "UTF-8");
    if ((*e)->ExceptionCheck(e)) return;
    rt = JS_NewRuntime();
    if (!rt) goto oom;
    JS_SetMemoryLimit(rt, 16 * 1024 * 1024);
    JS_SetMaxStackSize(rt, 256 * 1024);
    JS_SetInterruptHandler(rt, interrupted, &h);
    ctx = JS_NewContext(rt);
    if (!ctx) goto oom;
    JS_SetContextOpaque(ctx, &h);
    JSValue global = JS_GetGlobalObject(ctx), bridge = JS_NewObject(ctx);
    const char *names[] = {"get", "post", "request", "md5", "log", "complete", "fail"};
    for (int i = 0; i < 7; i++)
        JS_SetPropertyStr(ctx, bridge, names[i], JS_NewCFunctionMagic(ctx, call_host, names[i], 0, JS_CFUNC_generic_magic, i));
    JS_SetPropertyStr(ctx, global, "NtvCjsBridge", bridge);
    JS_SetPropertyStr(ctx, global, "window", JS_DupValue(ctx, global));
    JS_FreeValue(ctx, global);
    jsize size = (*e)->GetArrayLength(e, script);
    // JS_Eval requires a trailing NUL; Java byte arrays do not provide one.
    char *code = js_malloc(ctx, (size_t)size + 1);
    if (!code) goto oom;
    (*e)->GetByteArrayRegion(e, script, 0, size, (jbyte *)code);
    code[size] = 0;
    JSValue result = JS_Eval(ctx, code, size, "site-plugin.js", JS_EVAL_TYPE_GLOBAL);
    js_free(ctx, code);
    int failed = JS_IsException(result);
    JS_FreeValue(ctx, result);
    while (!failed && JS_IsJobPending(rt)) {
        JSContext *job_ctx = NULL;
        if (JS_ExecutePendingJob(rt, &job_ctx) < 0) { failed = 1; break; }
    }
    if (failed && !(*e)->ExceptionCheck(e)) {
        JSValue error = JS_GetException(ctx);
        const char *message = JS_ToCString(ctx, error);
        (*e)->ThrowNew(e, (*e)->FindClass(e, "java/io/IOException"), message ? message : "QuickJS execution failed");
        JS_FreeCString(ctx, message);
        JS_FreeValue(ctx, error);
    }
    goto done;
oom:
    if (!(*e)->ExceptionCheck(e)) (*e)->ThrowNew(e, (*e)->FindClass(e, "java/io/IOException"), "QuickJS memory limit reached");
done:
    if (ctx) JS_FreeContext(ctx);
    if (rt) JS_FreeRuntime(rt);
}
