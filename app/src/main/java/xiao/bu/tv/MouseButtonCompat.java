package xiao.bu.tv;

import android.os.Build;
import android.util.Log;
import android.view.MotionEvent;
import java.lang.reflect.Method;

/** Android exposes getActionButton but not its setter in the public SDK.
 * Chromium on Android 6.0+ needs this field for native mouse down/up. Probe once; devices
 * that deny it retain touch clicks/dragging and still get native mouse hover. */
final class MouseButtonCompat {
    private static Method setter = findSetter();

    private static Method findSetter() {
        if (Build.VERSION.SDK_INT < 23) return null;
        try {
            Method candidate = MotionEvent.class.getMethod("setActionButton", int.class);
            MotionEvent probe = MotionEvent.obtain(0, 0, MotionEvent.ACTION_BUTTON_PRESS, 0, 0, 0);
            try { candidate.invoke(probe, MotionEvent.BUTTON_PRIMARY); }
            finally { probe.recycle(); }
            return candidate;
        } catch (Exception unavailable) {
            return null;
        }
    }

    static boolean supported() { return setter != null; }

    static boolean setPrimary(MotionEvent event) {
        return setButton(event, MotionEvent.BUTTON_PRIMARY);
    }

    static boolean setButton(MotionEvent event, int button) {
        if (setter == null) return false;
        try {
            setter.invoke(event, button);
            return true;
        } catch (Exception unavailable) {
            setter = null;
            Log.w("nTvMouse", "Native mouse buttons unavailable; using touch button fallback");
            return false;
        }
    }

    private MouseButtonCompat() {}
}
