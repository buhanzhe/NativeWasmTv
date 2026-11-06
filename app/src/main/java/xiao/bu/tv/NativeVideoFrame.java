package xiao.bu.tv;

import android.graphics.Bitmap;

/** Uses IJK's existing FFmpeg; optional so platform extraction remains a fallback. */
final class NativeVideoFrame {
    private static final boolean AVAILABLE;
    static {
        boolean ready = false;
        try {
            System.loadLibrary("ijkffmpeg");
            System.loadLibrary("ntvframe");
            ready = initialize();
        } catch (LinkageError error) {
            xiao.bu.tv.AppLog.w("NativeVideoFrame", "Native frame decoder unavailable", error);
        }
        AVAILABLE = ready;
    }
    private static native boolean initialize();
    private static native Bitmap decode(String file, int width, int height, boolean preview);
    static Bitmap capture(String file, int width, int height, boolean preview) {
        return AVAILABLE ? decode(file, width, height, preview) : null;
    }
}
