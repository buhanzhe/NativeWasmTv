package xiao.bu.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import xiao.bu.tv.AppLog;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.Map;

/** Deletes only received APKs whose target package has actually been installed. */
public final class ReceivedApkCleanup extends BroadcastReceiver {
    private static final String PREFS = "received_apk_cleanup";

    static void initialize(Context context) {
        Context app = context.getApplicationContext();
        runCleanup(app, null, null, true);
    }

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        String target;
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) target = context.getPackageName();
        else if ((Intent.ACTION_PACKAGE_ADDED.equals(action) || Intent.ACTION_PACKAGE_REPLACED.equals(action))
                && intent.getData() != null) target = intent.getData().getSchemeSpecificPart();
        else return;
        runCleanup(context.getApplicationContext(), target, goAsync());
    }

    private static void runCleanup(final Context context, final String target,
            final PendingResult pending) {
        runCleanup(context, target, pending, false);
    }

    private static void runCleanup(final Context context, final String target,
            final PendingResult pending, final boolean register) {
        new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {
                // registerReceiver is a synchronous Binder call. A busy system
                // server must not block Application.onCreate (Bugly 5099).
                if (register) {
                    IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
                    filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
                    filter.addDataScheme("package");
                    try { context.registerReceiver(new ReceivedApkCleanup(), filter); }
                    catch (RuntimeException error) { AppLog.w("ReceivedApkCleanup", "Unable to register install cleanup", error); }
                }
                cleanup(context, target);
            }
            finally { if (pending != null) pending.finish(); }
        }, "received-apk-cleanup").start();
    }

    static synchronized void track(Context context, File file, PackageInfo archive) throws IOException {
        long previous = 0;
        try { previous = context.getPackageManager().getPackageInfo(archive.packageName, 0).lastUpdateTime; }
        catch (PackageManager.NameNotFoundException ignored) { }
        try {
            JSONObject record = new JSONObject().put("package", archive.packageName)
                    .put("version", version(archive)).put("previousUpdate", previous);
            if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(file.getName(), record.toString()).commit()) {
                throw new IOException("无法保存安装清理记录");
            }
        } catch (org.json.JSONException error) { throw new IOException(error); }
    }

    static synchronized void cleanup(Context context, String target) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Map<String, ?> records = prefs.getAll();
        if (records.isEmpty()) return;
        File directory = ApkFileProvider.updateDirectory(context);
        if (directory == null) return;
        SharedPreferences.Editor edit = prefs.edit();
        boolean changed = false;
        for (Map.Entry<String, ?> entry : records.entrySet()) {
            String name = entry.getKey();
            try {
                if (!name.matches("received-[0-9]+\\.apk")) continue;
                File file = new File(directory, name);
                if (!file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())) continue;
                // Pending uploads from older versions lived in external files
                // (or cache on Android 7+). Keep cleaning those after upgrading.
                if (!file.exists()) {
                    File base = null;
                    try { base = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS); }
                    catch (RuntimeException ignored) { }
                    File legacy = base == null ? null : new File(new File(base, "updates"), name);
                    if (legacy != null && legacy.isFile()) file = legacy;
                    else {
                        File cached = new File(new File(context.getCacheDir(), "updates"), name);
                        if (cached.isFile()) file = cached;
                    }
                }
                JSONObject record = new JSONObject(String.valueOf(entry.getValue()));
                String packageName = record.getString("package");
                if (target != null && !target.equals(packageName)) continue;
                if (file.exists()) {
                    PackageInfo installed = context.getPackageManager().getPackageInfo(packageName, 0);
                    // Merely opening/cancelling the installer, or having an older copy,
                    // must never delete an APK the installer may still need to read.
                    if (version(installed) < record.getLong("version")
                            || installed.lastUpdateTime <= record.getLong("previousUpdate")) continue;
                    if (!file.delete()) continue;
                    new File(new File(context.getCacheDir(), "apk-installer"), name).delete();
                }
                edit.remove(name);
                changed = true;
            } catch (PackageManager.NameNotFoundException ignored) {
                // Not installed yet. Retry on an install event or the next cold start.
            } catch (Exception error) { AppLog.w("ReceivedApkCleanup", "Unable to clean received APK", error); }
        }
        if (changed) edit.commit();
    }

    private static long version(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : (info.versionCode & 0xffffffffL);
    }
}
