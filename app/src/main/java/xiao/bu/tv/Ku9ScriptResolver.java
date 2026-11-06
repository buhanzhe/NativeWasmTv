package xiao.bu.tv;

import android.app.Activity;
import android.text.TextUtils;
import xiao.bu.tv.AppLog;
import android.webkit.JavascriptInterface;
import android.widget.FrameLayout;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves Ku9 sources using the shared contract and capability-selected engine. */
final class Ku9ScriptResolver {
    interface Callback {
        void onResolved(int requestId, Result result);

        void onFailed(int requestId, String reason);
    }

    static final class Result {
        private Result(Result value) {
            url=value.url;directDataSource=value.directDataSource;noReferer=value.noReferer;http1=value.http1;
            playlistRefresh=false;signedHeaders=value.signedHeaders;mediaHeaders=value.mediaHeaders;
            referer=value.referer;cookies=value.cookies;userAgent=value.userAgent;webViewUrl=value.webViewUrl;pageScript=value.pageScript;
        }
        Result asInitial(){return playlistRefresh?new Result(this):this;}
        final String url;
        final boolean directDataSource;
        final boolean noReferer, http1;
        final boolean playlistRefresh;
        final java.util.Map<String, String> signedHeaders;
        final java.util.Map<String, String> mediaHeaders;
        final String referer, cookies;
        final String userAgent;
        final String webViewUrl, pageScript;

        Result(String url, boolean directDataSource, JSONObject fields) {
            this(url, directDataSource, fields, false);
        }

        Result(String url, boolean directDataSource, JSONObject fields, boolean playlistRefresh) {
            this.playlistRefresh = playlistRefresh;
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            for (String name : new String[] {"UID", "APPID", "APPSIGN", "APPRANDOMSTR"}) {
                String value = Ku9JsContract.resultHeader(fields, name, name);
                if (value != null && value.length() > 0) headers.put(name, value);
            }
            this.signedHeaders = java.util.Collections.unmodifiableMap(headers);
            this.mediaHeaders = Ku9JsContract.mediaHeaders(fields);
            this.webViewUrl = fields.optString("webview", "").trim();
            this.pageScript = fields.optString("jscode", "");
            this.url = url;
            this.directDataSource = directDataSource || fields.optBoolean("direct", false);
            this.noReferer = fields.optBoolean("noReferer", false);
            this.http1 = fields.optBoolean("http1", false);
            this.referer = Ku9JsContract.resultHeader(fields, "referer", "Referer");
            this.userAgent = Ku9JsContract.resultHeader(fields, "userAgent", "User-Agent");
            this.cookies = Ku9JsContract.resultHeader(fields, "cookies", "Cookie");
        }
    }

    private static final String TAG = "Ku9ScriptResolver";
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final long MIN_PLAYLIST_REFRESH_MS = 2000L;
    private static final long MAX_PLAYLIST_REFRESH_MS = 5000L;
    private static final Pattern TARGET_DURATION = Pattern.compile("(?m)^#EXT-X-TARGETDURATION:(\\d+)");
    private final Activity activity;
    private final Ku9SiteCache cache;
    private final Ku9JsContract contract;
    private final Ku9ScriptEngine engine;
    private final Ku9ScriptLoader scriptLoader;
    private volatile boolean speculative;
    private final Ku9WarmCache warmCache;
    private volatile Pending pending;
    private Ku9PlaylistServer playlistServer;
    private volatile int generation;
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

    Ku9ScriptResolver(Activity activity, FrameLayout root) { this(activity,root,false); }
    Ku9ScriptResolver(Activity activity,FrameLayout root,boolean speculative) {
        this.activity = activity;
        this.speculative=speculative;warmCache=speculative?new Ku9WarmCache():null;
        cache = Ku9SiteCache.legacy(activity);
        contract = new Ku9JsContract(activity);
        engine = new Ku9ScriptEngine(activity,speculative);
        scriptLoader = new Ku9ScriptLoader(activity);
    }

    static boolean isKu9Source(String value) {
        return Ku9ScriptLoader.isSource(value);
    }

    void resolve(final int requestId, final String channelName, final String sourceUrl,
            final Callback callback) {
        cancel();
        final int requestGeneration = generation;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String script = scriptLoader.load(sourceUrl, false);
                    final Pending work = new Pending(requestId, requestGeneration,
                            channelName, sourceUrl, script, callback);
                    work.javascript = buildJavascript(work);
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (requestGeneration != generation || activity.isFinishing()) {
                                return;
                            }
                            pending = work;
                            if(speculative&&work.browser){fail(work,"此脚本的浏览器执行推迟到切台时");return;}
                            execute(work);
                        }
                    });
                } catch (final IOException error) {
                    AppLog.w(TAG, "Unable to load Ku9 script " + sourceUrl, error);
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

    private void execute(Pending request) {
        if (pending != request || request.generation != generation) return;
        engine.execute(request.javascript, new Bridge(request), request.browser);
    }

    private String buildJavascript(Pending request) {
        JSONObject item = new JSONObject();
        try {
            // IPTV source labels are display text, not query parameter values.
            int label = request.sourceUrl.indexOf('$');
            item.put("url", label < 0 ? request.sourceUrl : request.sourceUrl.substring(0, label));
            item.put("name", request.channelName == null ? "" : request.channelName);
        } catch (JSONException ignored) {
        }
        return contract.build(request.script, item);
    }

    private void complete(Pending request, String json) {
        try {
            Ku9JsContract.Output output = Ku9JsContract.parse(json);
            if (output.webViewUrl.length() > 0) {
                if (!output.webViewUrl.startsWith("https://") && !output.webViewUrl.startsWith("http://"))
                    throw new IOException("酷9网页地址必须使用 HTTP 或 HTTPS");
                clearPending();
                request.callback.onResolved(request.requestId, new Result("", false, output.fields));
                return;
            }
            String url = output.url;
            String m3u8 = output.playlist;
            if (!TextUtils.isEmpty(m3u8) && m3u8.trim().startsWith("#EXTM3U")) {
                if (containsIpv6Literal(m3u8) && !hasUsableIpv6Network()) {
                    throw new IOException("当前网络没有 IPv6，无法播放此频道");
                }
                completeLivePlaylist(request, m3u8.trim() + "\n", output.fields);
                return;
            } else if (!TextUtils.isEmpty(url)) {
                if (containsIpv6Literal(url) && !hasUsableIpv6Network()) {
                    throw new IOException("当前网络没有 IPv6，无法播放此频道");
                }
                Result result = new Result(url.trim(), Ku9JsContract.isDirectDataSource(url), output.fields);
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

    private void completeLivePlaylist(Pending request, String content, JSONObject fields) throws IOException {
        if (playlistServer == null) {
            playlistServer = new Ku9PlaylistServer();
            playlistServer.start();
        }
        boolean refresh = request.initialCompleted;
        Result result = new Result(playlistServer.url(), false, fields, refresh);
        // Renew authentication before exposing the next signed segment URLs.
        if (refresh) request.callback.onResolved(request.requestId, result);
        playlistServer.update(content);
        handler.removeCallbacks(refreshPlaylist);
        if (!content.contains("#EXT-X-ENDLIST"))
            handler.postDelayed(refreshPlaylist, playlistRefreshDelay(content));
        request.initialCompleted = true;
        // Signatures can change with the playlist. Update transport headers
        // while keeping the current player and local playlist URL alive.
        if (!refresh) request.callback.onResolved(request.requestId, result);
    }

    static long playlistRefreshDelay(String content) {
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
            AppLog.w(TAG, "Unable to inspect IPv6 network state", error);
        }
        return false;
    }

    private void fail(Pending request, String reason) {
        if (request == null || pending != request) {
            return;
        }
        if (request.initialCompleted) {
            AppLog.w(TAG, reason + "; retaining the last live playlist");
            handler.removeCallbacks(refreshPlaylist);
            handler.postDelayed(refreshPlaylist, MAX_PLAYLIST_REFRESH_MS);
            return;
        }
        clearPending();
        closePlaylistServer();
        request.callback.onFailed(request.requestId, reason);
    }

    private void clearPending() {
        handler.removeCallbacks(refreshPlaylist);
        pending = null;
        engine.cancel();
    }

    void cancel() {
        generation++;
        clearPending();
        closePlaylistServer();
    }

    void destroy() { cancel(); }
    void promoteWarmup(){speculative=false;engine.setBackground(false);if(warmCache!=null)warmCache.promote();}

    private void closePlaylistServer() {
        if (playlistServer != null) {
            playlistServer.close();
            playlistServer = null;
        }
    }

    private final class Bridge extends Ku9Host {
        private final Pending request;
        Bridge(Pending request) { this.request = request; }
        @Override protected boolean isRequestCancelled() {
            return request.generation != generation || pending != request || Thread.currentThread().isInterrupted();
        }
        @Override @JavascriptInterface public String getCache(String key) { return warmCache==null?cache.get(key):warmCache.get(cache,"legacy",key); }
        @Override @JavascriptInterface public void setCache(String key, String value, double ttlMs) {
            if(warmCache==null)cache.put(key,value,ttlMs);else warmCache.put(cache,"legacy",key,value,ttlMs);
        }
        @Override protected void onComplete(final String json) {
            activity.runOnUiThread(() -> {
                if (pending == request && request.generation == generation)
                    Ku9ScriptResolver.this.complete(request, json);
            });
        }
        @Override protected void onFailure(final String reason) {
            activity.runOnUiThread(() -> {
                if (pending == request && request.generation == generation)
                    Ku9ScriptResolver.this.fail(request, "酷9脚本执行失败: " + reason);
            });
        }
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
        final boolean browser;
        String javascript;

        Pending(int requestId, int generation, String channelName, String sourceUrl,
                String script, Callback callback) {
            this.requestId = requestId;
            this.generation = generation;
            this.channelName = channelName;
            this.sourceUrl = sourceUrl;
            this.script = script;
            this.browser = Ku9EnginePolicy.usesWebView(script);
            this.callback = callback;
        }
    }

}
