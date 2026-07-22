package com.bu.cc.tv;

public final class NativeH5eDecryptor {
    static {
        System.loadLibrary("cctv_h5e");
    }

    private NativeH5eDecryptor() {
    }

    public static native byte[] decryptTransportStream(byte[] transportStream);

    public static native void cancelPendingDecrypts();

    public static native void releaseThreadContext();
}
