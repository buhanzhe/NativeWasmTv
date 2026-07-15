package com.bu.cc.tv;

final class NativeCmgDecryptor {
    static {
        System.loadLibrary("cmg_decrypt");
    }

    private NativeCmgDecryptor() {
    }

    static synchronized native String probeRuntime();

    static synchronized native boolean configureRuntimeForProbe(String playerTag, int updateTag);

    static synchronized native void configureLocationForProbe(String href);

    static synchronized native void setClockForProbe(long epochMillis);

    static synchronized native void clearClockForProbe();

    static synchronized native void setPlayerTagForProbe(String playerTag);

    static synchronized native boolean initializeRuntimeForProbe();

    static synchronized native void resetRuntimeForProbe();

    static synchronized native int getPlayerInitResultForProbe();

    static synchronized native void touchActiveForProbe();

    static synchronized native int updateSessionForProbe();

    static synchronized native int replayOfficialTraceForProbe(String nativeTrace,
            String updateTrace, long updateBaseTimeMs, int clockOffsetMs);

    static synchronized native void setUpdateTagForProbe(int updateTag);

    static synchronized native byte[] decodeNalForProbe(byte[] nal, boolean live, boolean runSteps);

    static synchronized native int decodeNalRangeInPlace(byte[] data, int offset, int length,
            boolean live, boolean runSteps);

    static synchronized native byte[] decodeNalSingleStepForProbe(byte[] nal, boolean live, int step);
}
