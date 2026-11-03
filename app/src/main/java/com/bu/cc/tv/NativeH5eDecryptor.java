package com.bu.cc.tv;

import xiao.bu.tv.CjsPluginRuntime;

public final class NativeH5eDecryptor {
    static {
        boolean bundled = false;
        if (xiao.bu.tv.BuildConfig.H5E_STREAMING_CHECKS) {
            try {
                System.loadLibrary("h5estream");
                bundled = true;
            } catch (UnsatisfiedLinkError unavailable) {
                android.util.Log.w("NativeH5eDecryptor", "Bundled H5E unavailable; using site plugin", unavailable);
            }
        }
        if (!bundled) CjsPluginRuntime.loadNativeLibrary("tv.cctv.com");
    }

    private NativeH5eDecryptor() {
    }

    public static native byte[] decryptTransportStream(byte[] transportStream);

    public static native boolean supportsStreamingTransportStream();

    /** Complete PES units only; the first chunk starts a new segment/session. */
    public static native byte[] decryptTransportStreamPart(byte[] transportStream, boolean first);

    public static native void setSpsCompatibilityMode(boolean enabled);

    public static native void cancelPendingDecrypts();

    public static native void releaseThreadContext();
}
