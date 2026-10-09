package xiao.bu.tv;

import android.content.Context;
import android.os.Build;
import java.io.*;
import java.net.URI;

/** Local polyfills. Native legacy tabs allow HTTP(S); standalone userscript retains site scope. */
final class LegacyWebCompatibility {
    private static String source;
    static boolean enabled() { return Build.VERSION.SDK_INT <= 19; }
    static synchronized String script(Context context) {
        if (source != null) return source;
        try (InputStream input = context.getAssets().open("control/userscripts/kitkat-tv.user.js")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();byte[] block = new byte[4096];int n;
            while ((n = input.read(block)) != -1) bytes.write(block, 0, n);
            source = bytes.toString("UTF-8");
        } catch (IOException error) { source = ""; }
        return source;
    }
    static boolean supports(String url) {
        if (!enabled() || url == null) return false;
        try {
            URI uri = URI.create(url);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException invalid) { return false; }
    }
    private LegacyWebCompatibility() { }
}
