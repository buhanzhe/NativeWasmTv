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

    /** Owned by one worker in the disposable compiler process. No webpage is executed here. */
    static final class Compiler implements java.io.Closeable {
        private long handle;
        Compiler(byte[] bootstrap) throws IOException { handle = nativeCompilerCreate(bootstrap);if(handle==0)throw new IOException("Compiler allocation failed"); }
        String transform(String source, String filename, boolean module) throws IOException {
            if(handle==0)throw new IOException("Compiler closed");
            return nativeCompilerTransform(handle,source.getBytes("UTF-8"),filename.getBytes("UTF-8"),module);
        }
        @Override public void close(){if(handle!=0){nativeCompilerClose(handle);handle=0;}}
    }
    private static native long nativeCompilerCreate(byte[] bootstrap) throws IOException;
    private static native String nativeCompilerTransform(long handle,byte[] source,byte[] filename,boolean module) throws IOException;
    private static native void nativeCompilerClose(long handle);
}
