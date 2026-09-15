package xiao.bu.tv;

import android.os.Build;
import android.util.Log;
import android.webkit.WebResourceResponse;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import okhttp3.MediaType;

/** Small HTTPS GET bridge for explicitly requested documents on Android 4.x. */
final class LegacyWebHttp {
    interface Redirect { void navigate(String url); }
    interface Cookies { void save(String url, String value); }
    static boolean enabled() { return Build.VERSION.SDK_INT < 21; }

    static WebResourceResponse intercept(String url, String userAgent, String cookies, Cookies sink, Redirect redirect) {
        if (!enabled() || url == null || !url.startsWith("https://")) return null;
        HttpURLConnection connection = null;
        try {
            URL target = new URL(url);
            for (int hops = 0; hops < 8; hops++) {
                if (!"https".equals(target.getProtocol())) return null;
                connection = NetworkClient.open(target); // Existing OkHttp/TLS compatibility.
                connection.setRequestMethod("GET");
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("User-Agent", userAgent);
                if (hops == 0 && cookies != null && !cookies.isEmpty()) connection.setRequestProperty("Cookie", cookies);
                int code = connection.getResponseCode();
                for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                    if ("Set-Cookie".equalsIgnoreCase(header.getKey()) && header.getValue() != null)
                        for (String value : header.getValue()) sink.save(target.toString(), value);
                }
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) return null;
                    target = new URL(target, location);
                    connection.disconnect(); connection = null;
                    continue;
                }
                // Android 4.x cannot preserve response status/headers. Leave HTTP errors,
                // downloads and unknown request methods to WebView's own network stack.
                if (code != 200) return null;
                MediaType type = MediaType.parse(connection.getContentType() == null ? "" : connection.getContentType());
                if (type == null || !"text".equals(type.type()) || !"html".equals(type.subtype())) return null;
                if (!target.toString().equals(url)) {
                    redirect.navigate(target.toString());
                    return new WebResourceResponse("text/html", "UTF-8", new java.io.ByteArrayInputStream(new byte[0]));
                }
                final HttpURLConnection owned = connection;
                InputStream stream = new FilterInputStream(connection.getInputStream()) {
                    @Override public void close() throws IOException {
                        try { super.close(); } finally { owned.disconnect(); }
                    }
                };
                Log.i("LegacyWebHttp", "HTTPS GET 200 " + target.getHost());
                WebResourceResponse response = new WebResourceResponse("text/html",
                        type.charset(Charset.forName("UTF-8")).name(), stream);
                connection = null;
                return response;
            }
        } catch (IOException | RuntimeException error) {
            Log.w("LegacyWebHttp", "HTTPS GET failed: " + error.getClass().getSimpleName());
        } finally {
            if (connection != null) connection.disconnect();
        }
        return null;
    }
}
