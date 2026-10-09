package xiao.bu.tv;

import android.os.Build;
import android.util.Log;
import android.webkit.WebResourceResponse;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import okhttp3.MediaType;

/** One-shot HTTPS GET fallback after a main-document WebView error on Android 4.x. */
final class LegacyWebHttp {
    interface Redirect { void navigate(String url); }
    interface Cookies { void save(String url, String value); }
    static boolean enabled() { return Build.VERSION.SDK_INT < 21; }

    static WebResourceResponse intercept(String url, String userAgent, String cookies, Cookies sink, Redirect redirect) {
        return intercept(url, userAgent, cookies, sink, redirect, false);
    }

    /** Correct a main document MIME only after the channel resolver identified HTML. */
    static WebResourceResponse intercept(String url, String userAgent, String cookies,
            Cookies sink, Redirect redirect, boolean forceHtml) {
        return intercept(url, userAgent, cookies, sink, redirect, forceHtml, "");
    }

    static WebResourceResponse intercept(String url, String userAgent, String cookies,
            Cookies sink, Redirect redirect, boolean forceHtml, String prelude) {
        return intercept(url,userAgent,cookies,sink,redirect,forceHtml,prelude,null);
    }
    static WebResourceResponse intercept(String url, String userAgent, String cookies,
            Cookies sink, Redirect redirect, boolean forceHtml, String prelude, LegacyWebTranspilation transpilation) {
        boolean bootstrap = !prelude.isEmpty();
        if (url == null || (!forceHtml && !bootstrap && (!enabled() || !url.startsWith("https://")))) return null;
        HttpURLConnection connection = null;
        try {
            long deadline = System.nanoTime() + 15000000000L;
            URL target = new URL(url);
            for (int hops = 0; hops < 8; hops++) {
                if (!"https".equals(target.getProtocol())
                        && ((!forceHtml && !bootstrap) || !"http".equals(target.getProtocol()))) return null;
                int remaining = (int) ((deadline-System.nanoTime())/1000000L);
                if (remaining <= 0) return null;
                connection = forceHtml || bootstrap ? NetworkClient.openBounded(target, remaining)
                        : NetworkClient.open(target); // Existing OkHttp/TLS compatibility.
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
                if (!forceHtml && (type == null || !"text".equals(type.type()) || !"html".equals(type.subtype()))) return null;
                InputStream body = connection.getInputStream();
                if (forceHtml) {
                    // Recheck the second GET; a dynamic URL may have changed since probing.
                    PushbackInputStream checked = new PushbackInputStream(body, 1024);
                    byte[] prefix = new byte[1024]; int length = 0;
                    while (length < prefix.length) {
                        int read = checked.read(prefix, length, prefix.length - length);
                        if (read < 0) break;
                        length += read;
                        if (HttpStreamResolver.isHtmlPrefix(Arrays.copyOf(prefix, length))) break;
                    }
                    if (!HttpStreamResolver.isHtmlPrefix(Arrays.copyOf(prefix, length))) {
                        checked.close(); return null;
                    }
                    checked.unread(prefix, 0, length); body = checked;
                }
                if (!target.toString().equals(url)) {
                    redirect.navigate(target.toString());
                    return new WebResourceResponse("text/html", "UTF-8", new java.io.ByteArrayInputStream(new byte[0]));
                }
                final HttpURLConnection owned = connection;
                Charset charset = type == null ? Charset.forName("UTF-8") : type.charset(Charset.forName("UTF-8"));
                body = transpilation==null ? LegacyDocumentInjector.prepend(body, charset, prelude)
                        : transpilation.document(body,charset,prelude,url);
                InputStream stream = new FilterInputStream(body) {
                    // KitKat's BrowserBlocking JNI aborts on a Java stream exception.
                    @Override public int read() {try{return super.read();}catch(IOException error){owned.disconnect();return -1;}}
                    @Override public int read(byte[] bytes,int offset,int length) {try{return super.read(bytes,offset,length);}catch(IOException error){owned.disconnect();return -1;}}
                    @Override public void close() throws IOException {
                        try { super.close(); } catch(IOException ignored){} finally { owned.disconnect(); }
                    }
                };
                Log.i("LegacyWebHttp", "GET 200 " + target.getHost() + " early=" + bootstrap);
                WebResourceResponse response = new WebResourceResponse("text/html",
                        charset.name(), stream);
                if (forceHtml && Build.VERSION.SDK_INT >= 21) {
                    Map<String, String> headers = new HashMap<>();
                    for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
                        String key = entry.getKey();
                        if (key == null || entry.getValue() == null
                                || "Content-Type".equalsIgnoreCase(key)
                                || "Content-Encoding".equalsIgnoreCase(key)
                                || "Content-Length".equalsIgnoreCase(key)
                                || "Transfer-Encoding".equalsIgnoreCase(key)) continue;
                        StringBuilder joined = new StringBuilder();
                        for (String value : entry.getValue()) {
                            if (joined.length() > 0) joined.append(", ");
                            joined.append(value);
                        }
                        headers.put(key, joined.toString());
                    }
                    response.setStatusCodeAndReasonPhrase(200, "OK");
                    response.setResponseHeaders(headers);
                }
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
