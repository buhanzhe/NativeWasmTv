package xiao.bu.tv;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.view.View;

/** Keep status bars hidden; use device defaults unless the user chooses immersive startup. */
final class FullscreenPolicy {
    static final String PREFERENCE = "fullscreen_startup";
    private FullscreenPolicy() { }

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(MainActivity.PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(PREFERENCE, !isTelevision(context));
    }

    static boolean isTelevision(Context context) {
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK;
        if (mode == Configuration.UI_MODE_TYPE_TELEVISION
                || context.getPackageManager().hasSystemFeature("android.software.leanback")
                || context.getPackageManager().hasSystemFeature("android.hardware.type.television")) return true;
        String identity = (Build.MODEL + " " + Build.DEVICE + " " + Build.PRODUCT)
                .toLowerCase(java.util.Locale.US);
        // Older set-top boxes often report a normal UI mode and a generic model.
        return identity.contains("tv")
                || !context.getPackageManager().hasSystemFeature("android.hardware.touchscreen");
    }

    static int flags(Context context, boolean layoutBehindBars) {
        boolean immersive = isEnabled(context);
        int flags = View.SYSTEM_UI_FLAG_FULLSCREEN;
        if (immersive) flags |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        if (layoutBehindBars && Build.VERSION.SDK_INT >= 16) {
            flags |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            if (immersive) flags |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        }
        if (immersive && Build.VERSION.SDK_INT >= 19) flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        return flags;
    }
}
