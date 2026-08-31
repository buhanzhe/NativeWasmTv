package xiao.bu.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Executes Ku9-style source scripts in an isolated, off-screen WebView. */
final class Ku9ScriptResolver {
    interface Callback {
        void onResolved(int requestId, Result result);

        void onFailed(int requestId, String reason);
    }

    static final class Result {
        final String url;
        final boolean directDataSource;

        Result(String url, boolean directDataSource) {
            this.url = url;
            this.directDataSource = directDataSource;
        }
    }

    private static final String TAG = "Ku9ScriptResolver";
    private static final int MIN_ANDROID_API = Build.VERSION_CODES.LOLLIPOP;
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final long TIMEOUT_MS = 30000L;
    private static final long MIN_PLAYLIST_REFRESH_MS = 2000L;
    private static final long MAX_PLAYLIST_REFRESH_MS = 5000L;
    private static final String EXECUTOR_URL = "https://ntv.local/ku9/";
    private static final Pattern TARGET_DURATION =
            Pattern.compile("(?m)^#EXT-X-TARGETDURATION:(\\d+)");
    private static final String EXECUTOR_PAGE =
            "<!doctype html><html><head><meta charset=\"utf-8\"></head>"
                    + "<body></body></html>";
    private static final String ES5_COMPAT =
            "if(!String.prototype.startsWith){String.prototype.startsWith=function(s,p){"
                    + "p=p||0;return this.substr(p,s.length)===s;};}"
                    + "if(!String.prototype.endsWith){String.prototype.endsWith=function(s,p){"
                    + "var t=String(this);if(p===undefined||p>t.length)p=t.length;"
                    + "return t.substring(p-s.length,p)===s;};}"
                    + "if(!String.prototype.includes){String.prototype.includes=function(s,p){"
                    + "return this.indexOf(s,p||0)!==-1;};}"
                    + "if(!Array.prototype.includes){Array.prototype.includes=function(v,p){"
                    + "return this.indexOf(v,p||0)!==-1;};}";

    private final Activity activity;
    private final FrameLayout root;
    private final SharedPreferences cache;
    private final Ku9ScriptLoader scriptLoader;
    private WebView webView;
    private volatile Pending pending;
    private Ku9PlaylistServer playlistServer;
    private volatile int generation;
    private final Runnable timeout = new Runnable() {
        @Override
        public void run() {
            Pending request = pending;
            if (request != null) {
                fail(request, "酷9脚本解析超时");
            }
        }
    };
    private final Runnable refreshPlaylist = new Runnable() {
        @Override
        public void run() {
            Pending request = pending;
            if (request != null && request.initialCompleted
                    && request.generation == generation) {
                execute(request);
            }
        }
    };

    Ku9ScriptResolver(Activity activity, FrameLayout root) {
        this.activity = activity;
        this.root = root;
        cache = activity.getSharedPreferences("ku9_script_cache", Activity.MODE_PRIVATE);
        scriptLoader = new Ku9ScriptLoader(activity);
    }

    static boolean isKu9Source(String value) {
        return Ku9ScriptLoader.isSource(value);
    }

    void resolve(final int requestId, final String channelName, final String sourceUrl,
            final Callback callback) {
        cancel();
        if (Build.VERSION.SDK_INT < MIN_ANDROID_API) {
            callback.onFailed(requestId, "酷9 JS 源仅支持 Android 5.0 及以上");
            return;
        }
        final int requestGeneration = generation;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String script = scriptLoader.load(sourceUrl);
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (requestGeneration != generation || activity.isFinishing()) {
                                return;
                            }
                            startExecution(new Pending(requestId, requestGeneration,
                                    channelName, sourceUrl, script, callback));
                        }
                    });
                } catch (final IOException error) {
                    Log.w(TAG, "Unable to load Ku9 script " + sourceUrl, error);
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (requestGeneration == generation) {
                                callback.onFailed(requestId, error.getMessage());
                            }
                        }
                    });
                }
            }
        }, "ku9-script-load").start();
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void ensureWebView(Pending request) throws IOException {
        if (webView != null) {
            return;
        }
        try {
            webView = new WebView(activity);
        } catch (RuntimeException error) {
            throw new IOException("系统 WebView 不可用", error);
        }
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setDatabaseEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setAlpha(0f);
        webView.setTranslationX(-10000f);
        webView.setTranslationY(-10000f);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1, 1);
        params.gravity = Gravity.LEFT | Gravity.TOP;
        root.addView(webView, params);
        webView.addJavascriptInterface(new Bridge(request), "NtvKu9Bridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                Pending request = pending;
                if (request != null && EXECUTOR_URL.equals(url)) {
                    execute(request);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !EXECUTOR_URL.equals(url);
            }
        });
    }

    private void startExecution(Pending request) {
        try {
            ensureWebView(request);
        } catch (IOException error) {
            request.callback.onFailed(request.requestId, error.getMessage());
            return;
        }
        pending = request;
        webView.removeCallbacks(timeout);
        webView.postDelayed(timeout, TIMEOUT_MS);
        webView.loadDataWithBaseURL(EXECUTOR_URL, EXECUTOR_PAGE,
                "text/html", "UTF-8", null);
    }

    private void execute(Pending request) {
        if (pending != request || request.generation != generation) {
            return;
        }
        webView.evaluateJavascript(buildJavascript(request), null);
    }

    private String buildJavascript(Pending request) {
        JSONObject item = new JSONObject();
        try {
            item.put("url", request.sourceUrl);
            item.put("name", request.channelName == null ? "" : request.channelName);
        } catch (JSONException ignored) {
        }
        return "(function(){'use strict';"
                + "function parseJson(v,d){try{return JSON.parse(v);}catch(e){return d;}}"
                + "function headerJson(v){return typeof v==='string'?v:JSON.stringify(v||{});}"
                + "window.ku9={"
                + "get:function(u,h){return NtvKu9Bridge.get(String(u),headerJson(h));},"
                + "post:function(u,b,h){return NtvKu9Bridge.post(String(u),String(b||''),headerJson(h));},"
                + "request:function(u,m,h,b,f){return parseJson(NtvKu9Bridge.request(String(u),String(m||'GET'),headerJson(h),String(b||''),f!==false),{});},"
                + "getQuery:function(u,n){try{var q=String(u).split('?')[1]||'',a=q.split('&');for(var i=0;i<a.length;i++){var p=a[i].split('=');if(decodeURIComponent(p[0]||'')===String(n))return decodeURIComponent((p.slice(1).join('=')||'').replace(/\\+/g,' '));}}catch(e){}return '';},"
                + "getCache:function(k){return NtvKu9Bridge.getCache(String(k));},"
                + "setCache:function(k,v,t){NtvKu9Bridge.setCache(String(k),String(v),Number(t)||0);},"
                + "md5:function(v){return NtvKu9Bridge.md5(String(v));},"
                + "log:function(v){NtvKu9Bridge.log(String(v));}"
                + "};"
                + ES5_COMPAT
                + "function done(v){try{if(v===undefined||v===null)v={};"
                + "NtvKu9Bridge.complete(JSON.stringify(v));}catch(e){fail(e);}}"
                + "function fail(e){NtvKu9Bridge.fail(String(e&&e.stack?e.stack:e));}"
                + "try{" + request.script + "\n"
                + "if(typeof main!=='function')throw new Error('脚本没有 main(item) 入口');"
                + "var r=main(" + item.toString() + ");"
                + "if(r&&typeof r.then==='function'){r.then(done,fail);}else{done(r);}}"
                + "catch(e){fail(e);}})();";
    }

    private void complete(Pending request, String json) {
        try {
            Object value = new JSONTokener(json == null ? "" : json).nextValue();
            String url = null;
            String m3u8 = null;
            if (value instanceof String) {
                String text = ((String) value).trim();
                if (text.startsWith("#EXTM3U")) {
                    m3u8 = text;
                } else {
                    url = text;
                }
            } else if (value instanceof JSONObject) {
                JSONObject object = (JSONObject) value;
                url = firstNonEmpty(object.optString("url", ""),
                        object.optString("playUrl", ""), object.optString("playurl", ""));
                m3u8 = firstNonEmpty(object.optString("m3u8", ""),
                        object.optString("content", ""));
                if (TextUtils.isEmpty(url)) {
                    JSONArray urls = object.optJSONArray("urls");
                    if (urls != null && urls.length() > 0) {
                        url = urls.optString(0, "");
                    }
                }
            }
            if (!TextUtils.isEmpty(m3u8) && m3u8.trim().startsWith("#EXTM3U")) {
                if (containsIpv6Literal(m3u8) && !hasUsableIpv6Network()) {
                    throw new IOException("当前网络没有 IPv6，无法播放此频道");
                }
                completeLivePlaylist(request, m3u8.trim() + "\n");
                return;
            } else if (!TextUtils.isEmpty(url)) {
                if (containsIpv6Literal(url) && !hasUsableIpv6Network()) {
                    throw new IOException("当前网络没有 IPv6，无法播放此频道");
                }
                Result result = new Result(url.trim(), isDirectDataSource(url));
                clearPending();
                request.callback.onResolved(request.requestId, result);
                return;
            } else {
                throw new IOException("酷9脚本没有返回可播放地址");
            }
        } catch (Exception error) {
            fail(request, "酷9脚本结果无效: " + safeMessage(error));
        }
    }

    private void completeLivePlaylist(Pending request, String content) throws IOException {
        if (playlistServer == null) {
            playlistServer = new Ku9PlaylistServer();
            playlistServer.start();
        }
        playlistServer.update(content);
        if (webView != null) {
            webView.removeCallbacks(timeout);
            webView.removeCallbacks(refreshPlaylist);
            webView.postDelayed(refreshPlaylist, playlistRefreshDelay(content));
        }
        if (!request.initialCompleted) {
            request.initialCompleted = true;
            request.callback.onResolved(request.requestId,
                    new Result(playlistServer.url(), false));
        }
    }

    private static long playlistRefreshDelay(String content) {
        Matcher matcher = TARGET_DURATION.matcher(content);
        long targetMs = matcher.find() ? (long) parseInt(matcher.group(1)) * 500L
                : MAX_PLAYLIST_REFRESH_MS;
        return Math.max(MIN_PLAYLIST_REFRESH_MS,
                Math.min(MAX_PLAYLIST_REFRESH_MS, targetMs));
    }

    private static boolean containsIpv6Literal(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.US);
        return lower.contains("http://[") || lower.contains("https://[");
    }

    /** Link-local, loopback and multicast addresses do not provide Internet IPv6. */
    private static boolean hasUsableIpv6Network() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet6Address && !address.isAnyLocalAddress()
                            && !address.isLoopbackAddress() && !address.isLinkLocalAddress()
                            && !address.isMulticastAddress()) {
                        byte first = address.getAddress()[0];
                        // fc00::/7 is private ULA and cannot reach the public 2400::/12 CDN.
                        if ((first & 0xfe) != 0xfc) {
                            return true;
                        }
                    }
                }
            }
        } catch (SocketException error) {
            Log.w(TAG, "Unable to inspect IPv6 network state", error);
        }
        return false;
    }

    private void fail(Pending request, String reason) {
        if (request == null || pending != request) {
            return;
        }
        if (request.initialCompleted) {
            Log.w(TAG, reason + "; retaining the last live playlist");
            if (webView != null) {
                webView.removeCallbacks(refreshPlaylist);
                webView.postDelayed(refreshPlaylist, MAX_PLAYLIST_REFRESH_MS);
            }
            return;
        }
        clearPending();
        closePlaylistServer();
        request.callback.onFailed(request.requestId, reason);
    }

    private void clearPending() {
        if (webView != null) {
            webView.removeCallbacks(timeout);
            webView.removeCallbacks(refreshPlaylist);
        }
        pending = null;
    }

    void cancel() {
        generation++;
        clearPending();
        closePlaylistServer();
        destroyWebView();
    }

    void destroy() {
        cancel();
    }

    private void destroyWebView() {
        if (webView != null) {
            webView.stopLoading();
            webView.removeJavascriptInterface("NtvKu9Bridge");
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) {
                parent.removeView(webView);
            }
            webView.destroy();
            webView = null;
        }
    }

    private void closePlaylistServer() {
        if (playlistServer != null) {
            playlistServer.close();
            playlistServer = null;
        }
    }

    private final class Bridge {
        private final Pending request;

        Bridge(Pending request) {
            this.request = request;
        }

        @JavascriptInterface
        public String get(String url, String headersJson) {
            try {
                return Ku9HttpClient.getText(url, Ku9HttpClient.parseHeaders(headersJson),
                        MAX_RESPONSE_BYTES);
            } catch (IOException error) {
                Log.w(TAG, "Ku9 GET failed: " + url, error);
                return "";
            }
        }

        @JavascriptInterface
        public String post(String url, String body, String headersJson) {
            return Ku9HttpClient.postText(url, body, headersJson, MAX_RESPONSE_BYTES);
        }

        @JavascriptInterface
        public String request(String url, String method, String headersJson, String body,
                boolean followRedirects) {
            return Ku9HttpClient.requestJson(url, method, headersJson, body,
                    followRedirects, MAX_RESPONSE_BYTES);
        }

        @JavascriptInterface
        public String getCache(String key) {
            long expiresAt = cache.getLong(key + "__expires", 0L);
            if (expiresAt > 0L && expiresAt < System.currentTimeMillis()) {
                cache.edit().remove(key).remove(key + "__expires").apply();
                return "";
            }
            return cache.getString(key, "");
        }

        @JavascriptInterface
        public void setCache(String key, String value, double ttlMs) {
            long expiresAt = ttlMs <= 0 ? 0L
                    : System.currentTimeMillis() + Math.max(0L, (long) ttlMs);
            cache.edit().putString(key, value == null ? "" : value)
                    .putLong(key + "__expires", expiresAt).apply();
        }

        @JavascriptInterface
        public String md5(String value) {
            try {
                MessageDigest digest = MessageDigest.getInstance("MD5");
                byte[] bytes = digest.digest(value.getBytes("UTF-8"));
                StringBuilder result = new StringBuilder(bytes.length * 2);
                for (byte item : bytes) {
                    result.append(String.format(Locale.US, "%02x", item & 0xff));
                }
                return result.toString();
            } catch (Exception error) {
                return "";
            }
        }

        @JavascriptInterface
        public void log(String value) {
            Log.d(TAG, value);
        }

        @JavascriptInterface
        public void complete(final String resultJson) {
            if (pending != request) {
                return;
            }
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (pending == request && request.generation == generation) {
                        Ku9ScriptResolver.this.complete(request, resultJson);
                    }
                }
            });
        }

        @JavascriptInterface
        public void fail(final String reason) {
            if (pending != request) {
                return;
            }
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (pending == request && request.generation == generation) {
                        Ku9ScriptResolver.this.fail(request,
                                "酷9脚本执行失败: " + reason);
                    }
                }
            });
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) {
                return value;
            }
        }
        return "";
    }

    private static boolean isDirectDataSource(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.US);
        return lower.startsWith("file://") || lower.startsWith("rtmp://")
                || lower.startsWith("rtsp://") || lower.endsWith(".flv")
                || lower.endsWith(".mp4") || lower.endsWith(".ts");
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return TextUtils.isEmpty(message) ? "未知错误" : message;
    }

    private static final class Pending {
        final int requestId;
        final int generation;
        final String channelName;
        final String sourceUrl;
        final String script;
        final Callback callback;
        boolean initialCompleted;

        Pending(int requestId, int generation, String channelName, String sourceUrl,
                String script, Callback callback) {
            this.requestId = requestId;
            this.generation = generation;
            this.channelName = channelName;
            this.sourceUrl = sourceUrl;
            this.script = script;
            this.callback = callback;
        }
    }

}
