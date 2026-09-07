package xiao.bu.tv;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;

import java.lang.reflect.Method;

/** Separate task: selecting our singleTask MainActivity must not cancel consent. */
public final class CastAudioPermissionActivity extends Activity {
    private static final int CAPTURE = 1;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) return;
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(
                MEDIA_PROJECTION_SERVICE);
        try {
            if (manager == null) throw new IllegalStateException("声音捕获不可用");
            startActivityForResult(createAudioCaptureIntent(manager), CAPTURE);
        } catch (RuntimeException error) {
            CastKeepAliveService.deliverAudioConsent(RESULT_CANCELED, null);
            finish();
        }
    }

    /** Android 17 can label the consent as audio capture. No virtual display is created. */
    private Intent createAudioCaptureIntent(MediaProjectionManager manager) {
        if (Build.VERSION.SDK_INT >= 37) {
            try {
                Class<?> configClass = Class.forName(
                        "android.media.projection.MediaProjectionConfig");
                Class<?> builderClass = Class.forName(
                        "android.media.projection.MediaProjectionConfig$Builder");
                Object builder = builderClass.newInstance();
                Method setAudioRequested = builderClass.getMethod(
                        "setAudioRequested", Boolean.TYPE);
                setAudioRequested.invoke(builder, true);
                Object config = builderClass.getMethod("build").invoke(builder);
                Method createIntent = manager.getClass().getMethod(
                        "createScreenCaptureIntent", configClass);
                return (Intent) createIntent.invoke(manager, config);
            } catch (Exception ignored) {
                // Vendor previews may expose API 37 without the final config API.
            }
        }
        return manager.createScreenCaptureIntent();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != CAPTURE) return;
        CastKeepAliveService.deliverAudioConsent(resultCode, data);
        finish();
    }
}
