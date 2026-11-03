package xiao.bu.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Process;

/** Optional system picture/colour page; never substitute a generic settings page. */
final class XiaomiTvColorSettings {
    private static final String[] PACKAGES = {"com.xiaomi.mitv.settings", "com.android.tv.settings"};
    private static final String PICTURE_ACTIVITY = "com.xiaomi.mitv.settings.picture.PictureActivity";

    static Intent find(Context context, boolean television) {
        if (!television || !(xiaomi(Build.MANUFACTURER) || xiaomi(Build.BRAND))) return null;
        PackageManager pm = context.getPackageManager();
        // Prefer a dedicated colour page when the firmware exposes one.
        for (String name : PACKAGES) try {
            PackageInfo info = pm.getPackageInfo(name, PackageManager.GET_ACTIVITIES);
            if (info.activities == null) continue;
            for (ActivityInfo activity : info.activities) {
                if (activity == null || !accessible(context, activity)) continue;
                CharSequence label = activity.loadLabel(pm);
                if (label == null || !colourLabel(label.toString().trim())) continue;
                Intent intent = available(context, name, activity.name);
                if (intent != null) return intent;
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            // Firmware may omit either package or restrict its component metadata.
        }
        // Xiaomi also ships this picture page inside the AOSP TV settings package.
        for (String name : PACKAGES) {
            Intent intent = available(context, name, PICTURE_ACTIVITY);
            if (intent != null) return intent;
        }
        return null;
    }

    private static boolean xiaomi(String value) {
        return "xiaomi".equalsIgnoreCase(value) || "redmi".equalsIgnoreCase(value);
    }

    private static boolean colourLabel(String value) {
        return "色彩".equals(value) || "色彩设置".equals(value) || "色彩設定".equals(value)
                || "颜色设置".equals(value) || "顏色設定".equals(value)
                || "Color settings".equalsIgnoreCase(value) || "Colour settings".equalsIgnoreCase(value);
    }

    private static Intent available(Context context, String name, String activity) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(name, activity));
            ResolveInfo resolved = context.getPackageManager().resolveActivity(intent, 0);
            if (resolved == null || resolved.activityInfo == null) return null;
            ActivityInfo info = resolved.activityInfo;
            return name.equals(info.packageName) && activity.equals(info.name) && accessible(context, info)
                    ? intent : null;
        } catch (RuntimeException unavailable) { return null; }
    }

    private static boolean accessible(Context context, ActivityInfo info) {
        ApplicationInfo app = info.applicationInfo;
        return info.enabled && info.exported && app != null && app.enabled
                && (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                && (info.permission == null || info.permission.length() == 0
                    || context.checkPermission(info.permission, Process.myPid(), Process.myUid())
                        == PackageManager.PERMISSION_GRANTED);
    }

    private XiaomiTvColorSettings() {}
}
