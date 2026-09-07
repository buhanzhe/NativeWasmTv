package com.bu.cc.tv;

import xiao.bu.tv.CjsPluginRuntime;

public final class NativeYspSigner {
    static {
        CjsPluginRuntime.loadNativeLibrary("libysp_keygen.so");
    }

    private NativeYspSigner() {
    }

    public static synchronized native String tokenRnd(String guid, String timestampMs);

    public static synchronized native String signature(String guid, String token, String input);
}
