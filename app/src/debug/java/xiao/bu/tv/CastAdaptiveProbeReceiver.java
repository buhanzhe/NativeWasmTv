package xiao.bu.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Exercises the actual resize path without installing a second test package. */
public final class CastAdaptiveProbeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!"xiao.bu.tv.TEST_CAST_RESIZE".equals(intent.getAction())) return;
        try {
            MainActivity owner = CastKeepAliveService.localInputOwner();
            Field field = MainActivity.class.getDeclaredField("webViewCastManager");
            field.setAccessible(true);
            WebViewCastManager manager = (WebViewCastManager) field.get(owner);
            if (manager == null || !manager.isRunning()) throw new IllegalStateException("No active cast");
            Field codec = WebViewCastManager.class.getDeclaredField("videoEncoder");
            Field controller = WebViewCastManager.class.getDeclaredField("bitrateController");
            codec.setAccessible(true); controller.setAccessible(true);
            Method resize = WebViewCastManager.class.getDeclaredMethod("requestLowerResolution",
                    android.media.MediaCodec.class, CastBitrateController.class);
            resize.setAccessible(true);
            resize.invoke(manager, codec.get(manager), controller.get(manager));
            setResultCode(1);
        } catch (Exception error) {
            android.util.Log.e("CastAdaptiveProbe", "Resize probe failed", error);
            setResultCode(-1);
        }
    }
}
