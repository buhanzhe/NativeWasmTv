package xiao.bu.tv;

import android.content.Context;
import xiao.bu.tv.AppLog;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Shared, bounded script asset. State/control stays in the owning WebView. */
final class WebMediaSession {
    private static String script;

    static synchronized String script(Context context) {
        if (script != null) return script;
        try (InputStream input = context.getAssets().open("web-media-session.js")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int size;
            while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
            script = output.toString("UTF-8");
        } catch (Exception error) {
            AppLog.w("WebMediaSession", "Unable to load media integration", error);
            script = "";
        }
        return script;
    }

    private WebMediaSession() {}
}
