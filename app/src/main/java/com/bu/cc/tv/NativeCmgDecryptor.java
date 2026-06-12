package com.bu.cc.tv;

final class NativeCmgDecryptor {
    static {
        System.loadLibrary("cmg_decrypt");
    }

    private NativeCmgDecryptor() {
    }

    static synchronized native String probeRuntime();

    static synchronized native boolean configureRuntimeForProbe(String playerTag, int updateTag);

    static synchronized native void touchActiveForProbe();

    static synchronized native int updateSessionForProbe();

    static synchronized native void setUpdateTagForProbe(int updateTag);

    static synchronized native byte[] decodeNalForProbe(byte[] nal, boolean live, boolean runSteps);

    static synchronized native byte[] decodeNalSingleStepForProbe(byte[] nal, boolean live, int step);
}
