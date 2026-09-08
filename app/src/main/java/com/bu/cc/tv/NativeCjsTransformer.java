package com.bu.cc.tv;

import xiao.bu.tv.CjsPluginRuntime;

/** Generic JNI bridge for a transformer declared by the signed online CJS bundle. */
public final class NativeCjsTransformer {
    static {
        CjsPluginRuntime.loadNativeLibrary("libcjs_site.so");
    }

    private NativeCjsTransformer() {
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
