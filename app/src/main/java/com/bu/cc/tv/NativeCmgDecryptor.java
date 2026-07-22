package com.bu.cc.tv;

public final class NativeCmgDecryptor {
    static {
        System.loadLibrary("cmg_decrypt");
    }

    private NativeCmgDecryptor() {
    }

    public static synchronized native String probeRuntime();

    public static synchronized native boolean configureRuntimeForProbe(String playerTag, int updateTag);

    public static synchronized native void configureLocationForProbe(String href);

    public static synchronized native void setClockForProbe(long epochMillis);

    public static synchronized native void clearClockForProbe();

    public static synchronized native void setPlayerTagForProbe(String playerTag);

    public static synchronized native boolean initializeRuntimeForProbe();

    public static synchronized native void resetRuntimeForProbe();

    public static synchronized native int getPlayerInitResultForProbe();

    public static synchronized native void touchActiveForProbe();

    public static synchronized native int updateSessionForProbe();

    public static synchronized native int replayOfficialTraceForProbe(String nativeTrace,
            String updateTrace, long updateBaseTimeMs, int clockOffsetMs);

    public static synchronized native void setUpdateTagForProbe(int updateTag);

    public static synchronized native byte[] decodeNalForProbe(byte[] nal, boolean live,
            boolean runSteps);

    public static synchronized native int decodeNalRangeInPlace(byte[] data, int offset, int length,
            boolean live, boolean runSteps);

    public static synchronized native byte[] decodeNalSingleStepForProbe(byte[] nal, boolean live,
            int step);
}
