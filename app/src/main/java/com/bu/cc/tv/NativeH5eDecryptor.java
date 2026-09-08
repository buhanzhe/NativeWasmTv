package com.bu.cc.tv;

import xiao.bu.tv.CjsPluginRuntime;

public final class NativeH5eDecryptor {
    static {
        CjsPluginRuntime.loadNativeLibrary("tv.cctv.com");
    }

    private NativeH5eDecryptor() {
    }

    public static native byte[] decryptTransportStream(byte[] transportStream);

    public static native void setSpsCompatibilityMode(boolean enabled);

    public static native void cancelPendingDecrypts();

    public static native void releaseThreadContext();
}
