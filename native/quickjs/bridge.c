#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
#include <stdlib.h>
#include <stdio.h>
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
    const char *names[] = {"get", "post", "request", "md5", "log", "complete", "fail", "getCache", "setCache",
        "digest", "encodeBase64", "decodeBase64", "toTimestamp", "toDate", "formatDateTime", "parseUri"};
    for (int i = 0; i < sizeof(names) / sizeof(names[0]); i++)
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

/* Compiler runtimes have no network, filesystem, DOM or CJS host callbacks. */
typedef struct { JSRuntime *rt; JSContext *ctx; int64_t deadline; } Compiler;
static int compiler_interrupt(JSRuntime *rt, void *opaque) {
    return now_ms() >= ((Compiler *)opaque)->deadline;
}
static void compiler_error(JNIEnv *e, JSContext *ctx) {
    JSValue error=JS_GetException(ctx);
    const char *text=JS_ToCString(ctx,error);
    /* Exception text need only identify the failure; transformed output uses UTF-8 below. */
    char message[256];
    snprintf(message,sizeof(message),"QuickJS compiler: %.210s",text?text:"memory limit reached");
    for(char *p=message;*p;p++)if((unsigned char)*p>=128)*p='?';
    (*e)->ThrowNew(e,(*e)->FindClass(e,"java/io/IOException"),message);
    if(text)JS_FreeCString(ctx,text);JS_FreeValue(ctx,error);
}
static JSValue compiler_string(JNIEnv *e,JSContext *ctx,jbyteArray bytes) {
    jsize n=(*e)->GetArrayLength(e,bytes);jbyte *data=(*e)->GetByteArrayElements(e,bytes,NULL);
    if(!data)return JS_EXCEPTION;
    JSValue value=JS_NewStringLen(ctx,(const char *)data,n);
    (*e)->ReleaseByteArrayElements(e,bytes,data,JNI_ABORT);return value;
}
JNIEXPORT jlong JNICALL Java_xiao_bu_tv_NativeQuickJs_nativeCompilerCreate(JNIEnv *e,jclass klass,jbyteArray bootstrap) {
    Compiler *c=malloc(sizeof(*c));if(!c)return 0;memset(c,0,sizeof(*c));
    c->rt=JS_NewRuntime();if(!c->rt)goto fail;
    JS_SetMemoryLimit(c->rt,64*1024*1024);JS_SetMaxStackSize(c->rt,768*1024);
    c->deadline=now_ms()+15000;JS_SetInterruptHandler(c->rt,compiler_interrupt,c);
    c->ctx=JS_NewContext(c->rt);if(!c->ctx)goto fail;
    jsize n=(*e)->GetArrayLength(e,bootstrap);char *code=js_malloc(c->ctx,(size_t)n+1);if(!code)goto fail;
    (*e)->GetByteArrayRegion(e,bootstrap,0,n,(jbyte *)code);code[n]=0;
    JSValue result=JS_Eval(c->ctx,code,n,"legacy-compiler.js",JS_EVAL_TYPE_GLOBAL);js_free(c->ctx,code);
    int failed=JS_IsException(result);JS_FreeValue(c->ctx,result);
    if(failed){compiler_error(e,c->ctx);goto fail;}
    return (jlong)(intptr_t)c;
fail:
    if(c->ctx)JS_FreeContext(c->ctx);if(c->rt)JS_FreeRuntime(c->rt);free(c);
    if(!(*e)->ExceptionCheck(e))(*e)->ThrowNew(e,(*e)->FindClass(e,"java/io/IOException"),"QuickJS compiler initialization failed");
    return 0;
}
JNIEXPORT jstring JNICALL Java_xiao_bu_tv_NativeQuickJs_nativeCompilerTransform(JNIEnv *e,jclass klass,jlong handle,jbyteArray source,jbyteArray filename,jboolean module) {
    Compiler *c=(Compiler *)(intptr_t)handle;if(!c)return NULL;
    c->deadline=now_ms()+8000;
    JSValue global=JS_GetGlobalObject(c->ctx),function=JS_GetPropertyStr(c->ctx,global,"ntvTransform");JS_FreeValue(c->ctx,global);
    JSValue args[3]={compiler_string(e,c->ctx,source),compiler_string(e,c->ctx,filename),JS_NewBool(c->ctx,module)};
    JSValue result=JS_Call(c->ctx,function,JS_UNDEFINED,3,args);
    JS_FreeValue(c->ctx,function);for(int i=0;i<3;i++)JS_FreeValue(c->ctx,args[i]);
    jstring output=NULL;
    if(JS_IsException(result)){compiler_error(e,c->ctx);}else{
        size_t n;const char *text=JS_ToCStringLen(c->ctx,&n,result);
        if(text){
            jbyteArray bytes=(*e)->NewByteArray(e,(jsize)n);
            if(bytes){(*e)->SetByteArrayRegion(e,bytes,0,n,(const jbyte *)text);
                jclass strings=(*e)->FindClass(e,"java/lang/String");jmethodID constructor=(*e)->GetMethodID(e,strings,"<init>","([BLjava/lang/String;)V");
                output=(*e)->NewObject(e,strings,constructor,bytes,(*e)->NewStringUTF(e,"UTF-8"));}
            JS_FreeCString(c->ctx,text);
        }else compiler_error(e,c->ctx);
    }
    JS_FreeValue(c->ctx,result);JS_RunGC(c->rt);return output;
}
JNIEXPORT void JNICALL Java_xiao_bu_tv_NativeQuickJs_nativeCompilerClose(JNIEnv *e,jclass klass,jlong handle) {
    Compiler *c=(Compiler *)(intptr_t)handle;if(c){JS_FreeContext(c->ctx);JS_FreeRuntime(c->rt);free(c);}
}
