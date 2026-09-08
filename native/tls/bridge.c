#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <mbedtls/ssl.h>
#include <mbedtls/entropy.h>
#include <mbedtls/ctr_drbg.h>
#include <mbedtls/error.h>

/* Java serializes engine calls. Closing the underlying Socket interrupts a read
 * before waiting for that lock; native memory is never freed during an operation. */
typedef struct {
    mbedtls_ssl_context ssl;
    mbedtls_ssl_config conf;
    mbedtls_entropy_context entropy;
    mbedtls_ctr_drbg_context rng;
    JNIEnv *env;
    jobject owner; /* local reference, valid only during the current JNI call */
    jbyteArray buffer;
    jmethodID read, write;
    int suites[9];
} connection;

static void fail(JNIEnv *env, int code) {
    if ((*env)->ExceptionCheck(env)) return; /* retain SocketTimeoutException etc. */
    char message[256];
    mbedtls_strerror(code, message, sizeof(message));
    jclass cls = (*env)->FindClass(env, "javax/net/ssl/SSLException");
    if (cls) (*env)->ThrowNew(env, cls, message);
}

static int receive(void *ctx, unsigned char *buf, size_t len) {
    connection *c = ctx;
    if (len > 16384) len = 16384;
    jint n = (*c->env)->CallIntMethod(c->env, c->owner, c->read, c->buffer, (jint)len);
    if ((*c->env)->ExceptionCheck(c->env)) {
        /* OkHttp probes idle pooled sockets using a short read timeout. Preserve
         * that exception AND let mbed TLS retain partially received records. */
        jthrowable error = (*c->env)->ExceptionOccurred(c->env);
        (*c->env)->ExceptionClear(c->env);
        jclass timeout = (*c->env)->FindClass(c->env, "java/net/SocketTimeoutException");
        int timed_out = timeout && (*c->env)->IsInstanceOf(c->env, error, timeout);
        (*c->env)->Throw(c->env, error);
        return timed_out ? MBEDTLS_ERR_SSL_TIMEOUT : MBEDTLS_ERR_SSL_INTERNAL_ERROR;
    }
    if (n < 0) return 0;
    if (n > 0) (*c->env)->GetByteArrayRegion(c->env, c->buffer, 0, n, (jbyte *)buf);
    return n;
}

static int send_bytes(void *ctx, const unsigned char *buf, size_t len) {
    connection *c = ctx;
    if (len > 16384) len = 16384;
    (*c->env)->SetByteArrayRegion(c->env, c->buffer, 0, (jsize)len, (const jbyte *)buf);
    if ((*c->env)->ExceptionCheck(c->env)) return MBEDTLS_ERR_SSL_INTERNAL_ERROR;
    (*c->env)->CallVoidMethod(c->env, c->owner, c->write, c->buffer, (jint)len);
    if ((*c->env)->ExceptionCheck(c->env)) return MBEDTLS_ERR_SSL_INTERNAL_ERROR;
    return (int)len;
}

static void dispose(JNIEnv *env, connection *c) {
    if (!c) return;
    mbedtls_ssl_free(&c->ssl);
    mbedtls_ssl_config_free(&c->conf);
    mbedtls_ctr_drbg_free(&c->rng);
    mbedtls_entropy_free(&c->entropy);
    if (c->buffer) (*env)->DeleteGlobalRef(env, c->buffer);
    free(c);
}

JNIEXPORT jlong JNICALL Java_xiao_bu_tv_LegacyTlsSocket_nativeCreate(
        JNIEnv *env, jobject owner, jstring host, jobjectArray suites) {
    connection *c = calloc(1, sizeof(*c));
    if (!c) { fail(env, MBEDTLS_ERR_SSL_ALLOC_FAILED); return 0; }
    mbedtls_ssl_init(&c->ssl);
    mbedtls_ssl_config_init(&c->conf);
    mbedtls_entropy_init(&c->entropy);
    mbedtls_ctr_drbg_init(&c->rng);
    jclass cls = (*env)->GetObjectClass(env, owner);
    c->read = (*env)->GetMethodID(env, cls, "transportRead", "([BI)I");
    c->write = (*env)->GetMethodID(env, cls, "transportWrite", "([BI)V");
    if ((*env)->ExceptionCheck(env)) { dispose(env, c); return 0; }
    jbyteArray buffer = (*env)->NewByteArray(env, 16384);
    if (buffer) c->buffer = (*env)->NewGlobalRef(env, buffer);
    if (!c->buffer) { dispose(env, c); return 0; }
    const unsigned char personal[] = "NativeWasmTv TLS client";
    int ret = mbedtls_ctr_drbg_seed(&c->rng, mbedtls_entropy_func, &c->entropy,
                                  personal, sizeof(personal) - 1);
    if (!ret) ret = mbedtls_ssl_config_defaults(&c->conf, MBEDTLS_SSL_IS_CLIENT,
                                  MBEDTLS_SSL_TRANSPORT_STREAM, MBEDTLS_SSL_PRESET_DEFAULT);
    if (!ret) {
        int n = (*env)->GetArrayLength(env, suites);
        if (n < 1 || n > 8) { fail(env, MBEDTLS_ERR_SSL_BAD_INPUT_DATA); dispose(env, c); return 0; }
        for (int i = 0; i < n; i++) {
            jstring suite = (*env)->GetObjectArrayElement(env, suites, i);
            const char *name = (*env)->GetStringUTFChars(env, suite, NULL);
            if (!name) { dispose(env, c); return 0; }
            char normalized[128];
            size_t size = strlen(name);
            if (size >= sizeof(normalized)) size = sizeof(normalized) - 1;
            for (size_t j = 0; j < size; j++) normalized[j] = name[j] == '_' ? '-' : name[j];
            normalized[size] = 0;
            c->suites[i] = mbedtls_ssl_get_ciphersuite_id(normalized);
            (*env)->ReleaseStringUTFChars(env, suite, name);
            (*env)->DeleteLocalRef(env, suite);
            if (!c->suites[i]) { fail(env, MBEDTLS_ERR_SSL_BAD_INPUT_DATA); dispose(env, c); return 0; }
        }
        mbedtls_ssl_conf_ciphersuites(&c->conf, c->suites);
        mbedtls_ssl_conf_rng(&c->conf, mbedtls_ctr_drbg_random, &c->rng);
        /* The Java X509TrustManager validates the peer after the handshake.
         * This preserves the host application's trust policy, like JSSE. */
        mbedtls_ssl_conf_authmode(&c->conf, MBEDTLS_SSL_VERIFY_NONE);
        ret = mbedtls_ssl_setup(&c->ssl, &c->conf);
    }
    if (!ret) {
        const char *name = (*env)->GetStringUTFChars(env, host, NULL);
        if (!name) { dispose(env, c); return 0; }
        ret = mbedtls_ssl_set_hostname(&c->ssl, name);
        (*env)->ReleaseStringUTFChars(env, host, name);
    }
    if (ret) { fail(env, ret); dispose(env, c); return 0; }
    mbedtls_ssl_set_bio(&c->ssl, c, send_bytes, receive, NULL);
    return (jlong)(intptr_t)c;
}

static connection *enter(JNIEnv *env, jobject owner, jlong handle) {
    connection *c = (connection *)(intptr_t)handle;
    c->env = env;
    c->owner = owner;
    return c;
}

JNIEXPORT jobjectArray JNICALL Java_xiao_bu_tv_LegacyTlsSocket_nativeHandshake(
        JNIEnv *env, jobject owner, jlong handle) {
    connection *c = enter(env, owner, handle);
    int ret = mbedtls_ssl_handshake(&c->ssl);
    if (ret) { fail(env, ret); return NULL; }
    const mbedtls_x509_crt *cert = mbedtls_ssl_get_peer_cert(&c->ssl), *p;
    int count = 0;
    for (p = cert; p; p = p->next) count++;
    if (!count) { fail(env, MBEDTLS_ERR_SSL_NO_CLIENT_CERTIFICATE); return NULL; }
    jclass bytes = (*env)->FindClass(env, "[B");
    jobjectArray chain = (*env)->NewObjectArray(env, count, bytes, NULL);
    if (!chain) return NULL;
    for (p = cert, count = 0; p; p = p->next, count++) {
        jbyteArray der = (*env)->NewByteArray(env, (jsize)p->raw.len);
        if (!der) return NULL;
        (*env)->SetByteArrayRegion(env, der, 0, (jsize)p->raw.len, (const jbyte *)p->raw.p);
        (*env)->SetObjectArrayElement(env, chain, count, der);
        (*env)->DeleteLocalRef(env, der);
        if ((*env)->ExceptionCheck(env)) return NULL;
    }
    return chain;
}

JNIEXPORT jstring JNICALL Java_xiao_bu_tv_LegacyTlsSocket_nativeCipher(
        JNIEnv *env, jobject owner, jlong handle) {
    connection *c = (connection *)(intptr_t)handle;
    return (*env)->NewStringUTF(env, mbedtls_ssl_get_ciphersuite(&c->ssl));
}

JNIEXPORT jint JNICALL Java_xiao_bu_tv_LegacyTlsSocket_nativeRead(
        JNIEnv *env, jobject owner, jlong handle, jbyteArray target, jint offset, jint len) {
    connection *c = enter(env, owner, handle);
    unsigned char buf[16384];
    if (len > (int)sizeof(buf)) len = sizeof(buf);
    int ret = mbedtls_ssl_read(&c->ssl, buf, (size_t)len);
    if (ret == 0 || ret == MBEDTLS_ERR_SSL_PEER_CLOSE_NOTIFY) return -1;
    if (ret < 0) { fail(env, ret); return -1; }
    (*env)->SetByteArrayRegion(env, target, offset, ret, (const jbyte *)buf);
    return ret;
}

JNIEXPORT void JNICALL Java_xiao_bu_tv_LegacyTlsSocket_nativeWrite(
        JNIEnv *env, jobject owner, jlong handle, jbyteArray source, jint offset, jint len) {
    connection *c = enter(env, owner, handle);
    unsigned char buf[16384];
    while (len > 0) {
        int chunk = len > (int)sizeof(buf) ? sizeof(buf) : len;
        (*env)->GetByteArrayRegion(env, source, offset, chunk, (jbyte *)buf);
        if ((*env)->ExceptionCheck(env)) return;
        int ret = mbedtls_ssl_write(&c->ssl, buf, chunk);
        if (ret <= 0) { fail(env, ret ? ret : MBEDTLS_ERR_SSL_INTERNAL_ERROR); return; }
        offset += ret;
        len -= ret;
    }
}

JNIEXPORT void JNICALL Java_xiao_bu_tv_LegacyTlsSocket_nativeFree(
        JNIEnv *env, jobject owner, jlong handle) {
    dispose(env, (connection *)(intptr_t)handle);
}
