package xiao.bu.tv;

import android.content.Context;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;

/** Provider decisions apply to the document, never to a CDN URL substring. */
final class WebTvPlaybackPolicy {
    static boolean webPlayerOnly(String url) {
        if (url == null) return false;
        try {
            String host = new URI(url).getHost();
            return jiangsu4kOriginalPlayer(url) || host != null && (host.equalsIgnoreCase("gdtv.cn")
                    || host.toLowerCase(java.util.Locale.US).endsWith(".gdtv.cn"));
        } catch (Exception ignored) { return false; }
    }

    private static boolean jiangsu4kOriginalPlayer(String url) {
        try {
            URI uri = new URI(url);
            if (!"live.jstv.com".equalsIgnoreCase(uri.getHost())) return false;
            String query = uri.getRawQuery();
            if (query == null) return false;
            for (String field : query.split("&")) {
                int separator = field.indexOf('=');
                if (separator < 0) continue;
                String key = java.net.URLDecoder.decode(field.substring(0, separator), "UTF-8");
                String value = java.net.URLDecoder.decode(field.substring(separator + 1), "UTF-8");
                // Its SDK renews signed manifest URLs while playing. A captured
                // URL plays initially but expires with 403 after about 6 minutes.
                if ("channelId".equals(key) && "676".equals(value)) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    static boolean requiresWebView(String url, boolean autoPlay, boolean autoPlaySniffed) {
        try {
            URI page = new URI(url);
            String host = page.getHost(), path = page.getPath();
            // Huya's room SDK requires Web Workers, which Lite does not implement.
            // Keep the same room in the existing WebView player instead of leaving
            // the rendered Lite page black after its media bootstrap fails.
            if (host != null && path != null && path.matches("/[0-9]+/?")) {
                host = host.toLowerCase(java.util.Locale.US);
                if (host.equals("huya.com") || host.endsWith(".huya.com")) return true;
            }
        } catch (Exception ignored) { }
        // Lite's canvas renders HTML, but cannot display the original media compositor.
        // Manual playback needs WebView. Auto HLS playback can retain Lite JS/auth.
        // Lite retains the SDK and observes renewed manifests in its live HLS
        // session while the native decoder consumes the bounded HTTP relay.
        return !autoPlay || !autoPlaySniffed;
    }

    static boolean autoTakeoverAllowed(String pageUrl) {
        return autoTakeoverAllowed(pageUrl, pageUrl);
    }

    static boolean autoTakeoverAllowed(String pageUrl, String channelUrl) {
        // MGTV requests a default channel before its in-page channel selection
        // completes. Keep discovered resources available for manual playback,
        // while its original SDK selects and plays the requested camera.
        if (pageUrl == null) return true;
        try {
            String host = new URI(pageUrl).getHost();
            if (host == null) return true;
            host = host.toLowerCase(java.util.Locale.US);
            // The desktop Douyu SDK exposes a preview/transport FLV which can
            // end after a few seconds outside the SDK. Its mobile HLS remains
            // eligible for native takeover; automatic profiles select that page.
            if (host.equals("www.douyu.com") || host.equals("douyu.com")) return false;
            // Huya's MSE player rotates signed FLV requests while playing.
            // Automatic handoff interrupts that working session; manual resource
            // playback remains available from the sniffed resource list.
            if (host.equals("huya.com") || host.endsWith(".huya.com")) return false;
            if (host.equals("mgtv.com") || host.endsWith(".mgtv.com")) return false;
            // NMTV exposes its default station before the selector finishes.
            if (host.equals("nmtv.cn") || host.endsWith(".nmtv.cn")) return false;
            // The unscripted Datong entry is already its intended default
            // station. It needs native HLS on WebViews that cannot render it.
            if (host.equals("dtradio.com.cn") || host.endsWith(".dtradio.com.cn"))
                return !hasSelectionOption(channelUrl);
            // Jiangxi's legacy hash entries open the default station first.
            // Preserve native auto-play for its explicit default channel only.
            if (host.equals("jxntv.cn") || host.endsWith(".jxntv.cn")) {
                String fragment = new URI(pageUrl).getFragment();
                return fragment == null || fragment.length() == 0
                        || "/jxtv1".equalsIgnoreCase(fragment) || "jxtv1".equalsIgnoreCase(fragment);
            }
            return true;
        } catch (Exception ignored) { return true; }
    }

    private static boolean hasSelectionOption(String channelUrl) {
        if (channelUrl == null) return false;
        try {
            String url = channelUrl.startsWith("webview://") ? channelUrl.substring(10) : channelUrl;
            String query = new URI(url).getRawQuery();
            if (query == null) return false;
            for (String option : query.split("&")) {
                int equals = option.indexOf('=');
                if (equals > 0 && equals < option.length() - 1
                        && "ujs".equalsIgnoreCase(java.net.URLDecoder.decode(option.substring(0, equals), "UTF-8")))
                    return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private static String source;
    static synchronized String script(Context context, boolean autoPlay) {
        if (source == null) {
            try (InputStream input = context.getAssets().open("web-tv-playback.user.js")) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                source = output.toString("UTF-8");
            } catch (Exception error) { Log.w("WebTvPlayback", "Unable to load TV helper", error); source = ""; }
        }
        return "window.__ntvTvAutoPlay=" + autoPlay + ";\n" + source;
    }

    private WebTvPlaybackPolicy() { }
}
