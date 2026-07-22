package com.bu.cc.tv;

public final class NativeYspSigner {
    static {
        System.loadLibrary("ysp_keygen");
    }

    private NativeYspSigner() {
    }

    public static synchronized native String tokenRnd(String guid, String timestampMs);

    public static synchronized native String signature(String guid, String token, String input);
}
