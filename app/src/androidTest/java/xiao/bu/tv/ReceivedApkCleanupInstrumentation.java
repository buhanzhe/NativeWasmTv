package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.*;

/** Two stages around an adb reinstall of the same app; never launches playback. */
public final class ReceivedApkCleanupInstrumentation extends Instrumentation {
    private boolean verify;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args); verify = "verify".equals(args.getString("stage")); start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle(); int status = -1;
        Context context = getTargetContext();
        try {
            if (verify) {
                String name = context.getSharedPreferences("apk_cleanup_test", 0).getString("file", "");
                if (name.length() == 0) throw new AssertionError("Missing prepared test file");
                File file = new File(ApkFileProvider.updateDirectory(context), name);
                long until = SystemClock.elapsedRealtime() + 5000;
                while (file.exists() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100);
                if (file.exists()) throw new AssertionError("Installed APK was not automatically removed");
                if (context.getSharedPreferences("received_apk_cleanup", 0).contains(name))
                    throw new AssertionError("Cleanup record remains");
                context.getSharedPreferences("apk_cleanup_test", 0).edit().clear().commit();
                result.putString("stream", "PASS self-update/process restart automatically removes received APK and record\n");
            } else {
                PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                File directory = ApkFileProvider.updateDirectory(context);
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Test directory");
                File file = new File(directory, "received-" + System.currentTimeMillis() + ".apk");
                try (InputStream in = new FileInputStream(context.getApplicationInfo().sourceDir);
                     OutputStream out = new FileOutputStream(file)) {
                    byte[] buffer = new byte[16384]; int n;
                    while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
                }
                ReceivedApkCleanup.track(context, file, installed);
                ReceivedApkCleanup.cleanup(context, null);
                if (!file.isFile()) throw new AssertionError("Existing same-version install caused premature deletion");
                ReceivedApkCleanup.cleanup(context, "unrelated.package");
                if (!file.isFile()) throw new AssertionError("Unrelated install deleted APK");
                context.getSharedPreferences("apk_cleanup_test", 0).edit().putString("file", file.getName()).commit();
                result.putString("stream", "PASS pending/cancelled and unrelated installs preserve received APK; ready for reinstall\n");
            }
        } catch (Throwable error) { status = 0; result.putString("stream", android.util.Log.getStackTraceString(error)); }
        finish(status, result);
    }
}
