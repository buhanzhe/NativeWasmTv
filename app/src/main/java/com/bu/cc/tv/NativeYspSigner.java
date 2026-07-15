package com.bu.cc.tv;

final class NativeYspSigner {
    static {
        System.loadLibrary("ysp_keygen");
    }

    private NativeYspSigner() {
    }

    static synchronized native String tokenRnd(String guid, String timestampMs);

    static synchronized native String signature(String guid, String token, String input);
}
