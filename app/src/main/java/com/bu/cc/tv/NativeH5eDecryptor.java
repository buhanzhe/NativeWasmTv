package com.bu.cc.tv;

final class NativeH5eDecryptor {
    static {
        System.loadLibrary("cctv_h5e");
    }

    private NativeH5eDecryptor() {
    }

    static native byte[] decryptTransportStream(byte[] transportStream);

    static native void cancelPendingDecrypts();

    static native void releaseThreadContext();
}
