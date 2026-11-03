/* LGPL-2.1-or-later. Java SurfaceTexture bridge owned by the codec instance. */
#ifndef NTV_HDR_JNI_H
#define NTV_HDR_JNI_H
#include "../gles2/ntv_hdr_lut.h"
static jboolean ntv_hdr_upload_java(JNIEnv *env,jclass klass,jobject pixels)
{
    (void)klass;
    if(!pixels)return JNI_FALSE;
    void *data=(*env)->GetDirectBufferAddress(env,pixels);
    jlong size=(*env)->GetDirectBufferCapacity(env,pixels);
    return data&&size>=0&&ntv_hdr_upload_lut3d(data,(size_t)size)?JNI_TRUE:JNI_FALSE;
}
static jboolean ntv_hdr_upload_java128(JNIEnv *env,jclass klass,jobject pixels)
{
    (void)klass;
    if(!pixels)return JNI_FALSE;
    void *data=(*env)->GetDirectBufferAddress(env,pixels);
    jlong size=(*env)->GetDirectBufferCapacity(env,pixels);
    return data&&size>=0&&ntv_hdr_upload_lut3d_size(data,(size_t)size,128)?JNI_TRUE:JNI_FALSE;
}
static jclass ntv_hdr_class;
static jmethodID ntv_hdr_release, ntv_hdr_output;
static jmethodID ntv_hdr_prepare, ntv_hdr_configure;
void ntv_hdr_jni_init(JNIEnv *env)
{
    if (J4A_GetSystemAndroidApiLevel(env) < 17) return;
    jclass local = (*env)->FindClass(env,"xiao/bu/tv/NativeHdrOutput");
    if (J4A_ExceptionCheck__catchAll(env) || !local) return;
    ntv_hdr_class = (*env)->NewGlobalRef(env,local);
    (*env)->DeleteLocalRef(env,local);
    ntv_hdr_configure = (*env)->GetStaticMethodID(env,ntv_hdr_class,"configure",
            "(Landroid/media/MediaCodec;Landroid/media/MediaFormat;Landroid/view/Surface;Landroid/media/MediaCrypto;I)Lxiao/bu/tv/NativeHdrOutput;");
    ntv_hdr_release = (*env)->GetMethodID(env,ntv_hdr_class,"release","()V");
    ntv_hdr_output = (*env)->GetMethodID(env,ntv_hdr_class,"outputFormat","(Landroid/media/MediaFormat;)V");
    ntv_hdr_prepare = (*env)->GetStaticMethodID(env,ntv_hdr_class,"prepareSoftwareOutput","()V");
    if (J4A_ExceptionCheck__catchAll(env)) {
        (*env)->DeleteGlobalRef(env,ntv_hdr_class); ntv_hdr_class = NULL;
    }
    if(ntv_hdr_class){
        JNINativeMethod optional[]={{"uploadLut3d","(Ljava/nio/ByteBuffer;)Z",(void*)ntv_hdr_upload_java}};
        // Older Java packages keep their existing HDR bridge if this optional
        // acceleration method is absent. New Java also handles older libraries.
        (*env)->RegisterNatives(env,ntv_hdr_class,optional,1);
        J4A_ExceptionCheck__catchAll(env);
        JNINativeMethod large[]={{"uploadLut3d128","(Ljava/nio/ByteBuffer;)Z",(void*)ntv_hdr_upload_java128}};
        (*env)->RegisterNatives(env,ntv_hdr_class,large,1);
        J4A_ExceptionCheck__catchAll(env);
    }
}
int ntv_hdr_prepare_software(void)
{
    if (!ntv_hdr_class) return 0;
    JNIEnv *env = NULL;
    if (JNI_OK != SDL_JNI_SetupThreadEnv(&env)) return -1;
    (*env)->CallStaticVoidMethod(env,ntv_hdr_class,ntv_hdr_prepare);
    return J4A_ExceptionCheck__catchAll(env) ? -1 : 0;
}
static void ntv_hdr_close(JNIEnv *env,jobject *bridge)
{
    if (*bridge) {
        (*env)->CallVoidMethod(env,*bridge,ntv_hdr_release);
        J4A_ExceptionCheck__catchAll(env);
        SDL_JNI_DeleteGlobalRefP(env,bridge);
    }
}
#endif
