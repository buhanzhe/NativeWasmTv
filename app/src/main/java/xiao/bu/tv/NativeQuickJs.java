package xiao.bu.tv;

import java.io.IOException;

/** Small isolated runtime per resolution; never executes on the Android UI thread. */
final class NativeQuickJs {
    static { System.loadLibrary("ntvquickjs"); }

    interface Host {
        String invoke(int operation, String[] arguments) throws Exception;
        boolean isCancelled();
    }

    static void execute(String script, Host host) throws IOException {
        nativeExecute(script.getBytes("UTF-8"), host);
    }

    private static native void nativeExecute(byte[] script, Host host) throws IOException;
}
