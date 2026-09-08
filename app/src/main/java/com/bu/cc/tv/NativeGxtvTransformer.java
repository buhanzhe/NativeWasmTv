package com.bu.cc.tv;

import xiao.bu.tv.CjsPluginRuntime;

/** JNI bridge owned by the tv.gxtv.cn site plugin. */
public final class NativeGxtvTransformer {
    static {
        CjsPluginRuntime.loadNativeLibrary("tv.gxtv.cn");
    }

    private NativeGxtvTransformer() {
    }

    public static byte[] transformTransportStream(byte[] transportStream,
            String transformer, String[] arguments) {
        if (transportStream == null || transformer == null || transformer.length() == 0
                || arguments == null) {
            return null;
        }
        return nativeTransformInPlace(transportStream, transformer, arguments)
                ? transportStream : null;
    }

    private static native boolean nativeTransformInPlace(byte[] transportStream,
            String transformer, String[] arguments);
}
