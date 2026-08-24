package xiao.bu.tv;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;

final class CrashReporter {
    private static final String TAG = "CrashReporter";
    private static boolean installed;

    private CrashReporter() {
    }

    static synchronized void install(Context context) {
        if (installed) {
            return;
        }
        installed = true;
        final Context appContext = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                writeCrash(appContext, thread, error);
                if (previous != null) {
                    previous.uncaughtException(thread, error);
                } else {
                    Process.killProcess(Process.myPid());
                    System.exit(10);
                }
            }
        });
    }

    private static void writeCrash(Context context, Thread thread, Throwable error) {
        FileOutputStream output = null;
        PrintWriter writer = null;
        try {
            output = new FileOutputStream(new File(context.getFilesDir(), "last-crash.txt"));
            writer = new PrintWriter(output);
            writer.println("time=" + System.currentTimeMillis());
            writer.println("thread=" + (thread == null ? "unknown" : thread.getName()));
            if (error != null) {
                error.printStackTrace(writer);
            }
            writer.flush();
            output.getFD().sync();
        } catch (Throwable writeError) {
            Log.e(TAG, "Unable to persist Java crash", writeError);
        } finally {
            if (writer != null) {
                writer.close();
            } else if (output != null) {
                try {
                    output.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
