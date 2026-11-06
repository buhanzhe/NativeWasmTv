package xiao.bu.tv;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import xiao.bu.tv.AppLog;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A bounded, best-effort fallback for old devices with very little video memory. */
final class LegacyBackgroundMemoryRelief {
    private static final String TAG = "LegacyMemoryRelief";
    private static final long MIN_INTERVAL_MS = 10 * 60 * 1000L;
    private static final long LOW_AVAILABLE_BYTES = 128L * 1024L * 1024L;
    private static long lastAttemptAt;

    private LegacyBackgroundMemoryRelief() { }

    static void afterHighResolutionPlayerError(Context context, int width, int height) {
        int sdk = Build.VERSION.SDK_INT;
        if (sdk < Build.VERSION_CODES.ICE_CREAM_SANDWICH
                || sdk > Build.VERSION_CODES.KITKAT
                || width < 1280 || height < 720) return;

        long now = SystemClock.elapsedRealtime();
        if (lastAttemptAt > 0 && now - lastAttemptAt < MIN_INTERVAL_MS) return;
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return;
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        try {
            manager.getMemoryInfo(memory);
            if (!memory.lowMemory && memory.availMem >= LOW_AVAILABLE_BYTES) return;
            // Run at most once per cooldown even if there are no eligible apps.
            lastAttemptAt = now;
            List<ActivityManager.RunningAppProcessInfo> running = manager.getRunningAppProcesses();
            if (running == null) return;
            PackageManager packages = context.getPackageManager();
            Set<String> attempted = new HashSet<String>();
            int reclaimed = 0;
            for (ActivityManager.RunningAppProcessInfo process : running) {
                if (reclaimed >= 2) break;
                if (process == null || process.pid == Process.myPid()
                        || process.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND
                        || process.pkgList == null || process.pkgList.length != 1) continue;
                String packageName = process.pkgList[0];
                if (packageName == null || packageName.equals(context.getPackageName())
                        || !attempted.add(packageName)) continue;
                ApplicationInfo application;
                try {
                    application = packages.getApplicationInfo(packageName, 0);
                } catch (PackageManager.NameNotFoundException ignored) {
                    continue;
                }
                if ((application.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) continue;
                manager.killBackgroundProcesses(packageName);
                reclaimed++;
            }
            AppLog.i(TAG, "Legacy video memory relief candidates=" + reclaimed
                    + " availMb=" + (memory.availMem / (1024L * 1024L))
                    + " lowMemory=" + memory.lowMemory);
        } catch (RuntimeException error) {
            AppLog.w(TAG, "Unable to reclaim legacy cached processes", error);
        }
    }
}
