package xiao.bu.tv;

import android.os.Build;
import android.util.Log;
import android.webkit.WebView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Initial media volume recovery and site codec corrections; never changes system volume. */
final class WebAudioCompatibility {
    private static String script;

    static void apply(WebView view) {
        try {
            if (script == null) {
                try (InputStream input = view.getResources().openRawResource(R.raw.web_audio_compat);
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                    script = bytes.toString("UTF-8");
                }
            }
            if (Build.VERSION.SDK_INT >= 19) view.evaluateJavascript(script, null);
            else view.loadUrl("javascript:" + script);
        } catch (Exception error) {
            Log.w("WebAudioCompatibility", "Cannot apply web audio compatibility", error);
        }
    }
}
