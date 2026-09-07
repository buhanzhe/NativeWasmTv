package xiao.bu.tv;

import com.bu.cc.tv.NativeYspSigner;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.net.http.SslError;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

final class YangshipinWebResolver {
    interface Callback {
        void onResolved(int requestId, String url, String cmgTag,
                String cmgInitialUpdateTag, String cmgUpdateTag, int cmgUpdateWarmupCount,
                long cmgInitTimeMs, long cmgUpdateBaseTimeMs, String cmgUpdateTrace,
                String cmgNativeTrace);

        void onFailed(int requestId, String reason);
    }

    private static final String TAG = "YangshipinResolver";
    private static final long TIMEOUT_MS = 30000L;
    private static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    private static final String TAG_PREFIX = "__NTV_CMG_MEDIA_TAG__";
    private static final String API_RESULT_PREFIX = "__NTV_YSP_API__";
    private static final String API_AUTH_PREFIX = "__NTV_YSP_AUTH__";
    private static final String API_ERROR_PREFIX = "__NTV_YSP_API_ERROR__";
    private static final long API_CACHE_MS = 10L * 60L * 1000L;
    private static final long CLOCK_CACHE_MS = 24L * 60L * 60L * 1000L;
    private static final String PREFS_NAME = "yangshipin_resolver";
    private final Activity activity;
    private final FrameLayout root;
    private WebView webView;
    private final boolean keepTracePage;
    private final SharedPreferences resolverPrefs;
    private final Runnable timeout = new Runnable() {
        @Override
        public void run() {
            Pending pending = pendingRequest;
            if (pending != null) {
                fail(pending, "央视频解析超时");
            }
        }
    };
    private final Runnable pollPage = new Runnable() {
        @Override
        public void run() {
            pollPageForVideoUrl();
        }
    };
    private final Runnable traceKeepAlive = new Runnable() {
        @Override
        public void run() {
            keepTracePlaybackAlive();
        }
    };

    private Pending pendingRequest;
    private final Map<String, CachedUrl> apiCache = new HashMap<String, CachedUrl>();
    private long serverClockOffsetMs = Long.MIN_VALUE;

    @SuppressLint("SetJavaScriptEnabled")
    YangshipinWebResolver(Activity activity, FrameLayout root, boolean keepTracePage) {
        this.activity = activity;
        this.root = root;
        this.keepTracePage = keepTracePage;
        resolverPrefs = activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE);
        long savedAt = resolverPrefs.getLong("clock_saved_at", 0L);
        long savedOffset = resolverPrefs.getLong("clock_offset_ms", Long.MIN_VALUE);
        long savedAge = System.currentTimeMillis() - savedAt;
        if (savedOffset != Long.MIN_VALUE && savedAge >= 0L && savedAge < CLOCK_CACHE_MS) {
            serverClockOffsetMs = savedOffset;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void ensureWebView() {
        if (webView != null) {
            return;
        }
        webView = new WebView(activity.getApplicationContext());
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setAlpha(0.01f);
        webView.setVisibility(View.GONE);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                keepTracePage ? 320 : 1, keepTracePage ? 180 : 1);
        params.gravity = Gravity.LEFT | Gravity.TOP;
        root.addView(webView, params);

        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            CookieSyncManager.createInstance(activity.getApplicationContext());
        }

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUserAgentString(DESKTOP_USER_AGENT);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(false);
        webView.addJavascriptInterface(new SignerBridge(), "NtvYspSigner");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings.setMediaPlaybackRequiresUserGesture(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                if (consoleMessage != null) {
                    String message = consoleMessage.message();
                    if (maybeResolveApiAuth(message) || maybeResolveApiResult(message)) {
                        return true;
                    }
                    maybeResolveCmgTag(message);
                }
                return super.onConsoleMessage(consoleMessage);
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler,
                    SslError error) {
                Log.w(TAG, "Ignoring WebView SSL error: " + error);
                handler.proceed();
            }

            @Override
            public void onLoadResource(WebView view, String url) {
                maybeResolve(url);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                Pending pending = pendingRequest;
                if (pending != null && pending.apiHtml != null
                        && "https://www.yangshipin.cn/".equals(url)) {
                    try {
                        return new WebResourceResponse("text/html", "UTF-8",
                                new ByteArrayInputStream(pending.apiHtml.getBytes("UTF-8")));
                    } catch (UnsupportedEncodingException error) {
                        Log.e(TAG, "Unable to serve Yangshipin API page", error);
                    }
                }
                if (pending != null && pending.bootstrapScript != null && url != null
                        && url.startsWith("https://www.yangshipin.cn/tv/home")) {
                    WebResourceResponse response = patchOfficialPage(url, pending.bootstrapScript);
                    if (response != null) {
                        return response;
                    }
                }
                maybeResolve(url);
                if (url != null && url.toLowerCase().contains("hls.cmg.js")) {
                    WebResourceResponse response = patchHlsCmgScript(url);
                    if (response != null) {
                        return response;
                    }
                }
                return super.shouldInterceptRequest(view, url);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode,
                    String description, String failingUrl) {
                Pending pending = pendingRequest;
                if (pending != null && failingUrl != null
                        && failingUrl.contains("yangshipin.cn")) {
                    fail(pending, description == null ? "央视频页面加载失败" : description);
                }
            }
        });
    }

    void resolve(final int requestId, final Channel channel, final String definition,
            final Callback callback) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                clearPending(false);
                if (channel.yangshipinPid == null || channel.yangshipinPid.length() == 0) {
                    callback.onFailed(requestId, "频道缺少央视频 pid");
                    return;
                }
                pendingRequest = new Pending(requestId, channel, definition, callback);
                String cacheKey = pendingRequest.cacheKey();
                CachedUrl cached = apiCache.get(cacheKey);
                if (cached == null) {
                    String key = "url_" + cacheKey;
                    String persistedUrl = resolverPrefs.getString(key, "");
                    long persistedAt = resolverPrefs.getLong(key + "_at", 0L);
                    if (persistedUrl.length() > 0) {
                        cached = new CachedUrl(persistedUrl, persistedAt);
                    }
                }
                long cacheAge = cached == null ? Long.MAX_VALUE
                        : System.currentTimeMillis() - cached.createdAt;
                if (cached != null && cacheAge >= 0L && cacheAge < API_CACHE_MS) {
                    Log.i(TAG, "Using cached Yangshipin " + pendingRequest.definition
                            + " URL for " + channel.name);
                    completeApi(pendingRequest, cached.url);
                    return;
                }
                try {
                    ensureWebView();
                    webView.setVisibility(View.VISIBLE);
                    boolean clockKnown = serverClockOffsetMs != Long.MIN_VALUE;
                    long guidTimeMs = clockKnown
                            ? System.currentTimeMillis() + serverClockOffsetMs
                            : System.currentTimeMillis();
                    YangshipinApiPayload payload = YangshipinApiPayload.create(
                            channel, guidTimeMs, pendingRequest.definition);
                    String html = buildApiPage(channel, payload);
                    pendingRequest.apiHtml = html;
                    pendingRequest.apiPayload = payload;
                    pendingRequest.clockSynced = clockKnown;
                    pendingRequest.bootstrapScript = buildOfficialBootstrapScript(payload);
                    installApiCookies(payload);
                    Log.i(TAG, "Resolving Yangshipin " + pendingRequest.definition
                            + " API for " + channel.name);
                    webView.onResume();
                    webView.loadUrl("https://www.yangshipin.cn/");
                    webView.postDelayed(timeout, 15000L);
                } catch (Exception error) {
                    Log.e(TAG, "Unable to build Yangshipin API request", error);
                    fail(pendingRequest, "央视频请求生成失败");
                }
            }
        });
    }

    private static String pluginScript(String name, Map<String, String> values) {
        try {
            return CjsPluginRuntime.script(name, values);
        } catch (Exception error) {
            throw new IllegalStateException("CJS script is unavailable: " + name, error);
        }
    }

    private String buildApiPage(Channel channel, YangshipinApiPayload payload) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("ERROR_PREFIX", JSONObject.quote(API_ERROR_PREFIX));
        values.put("AUTH_PREFIX", JSONObject.quote(API_AUTH_PREFIX));
        values.put("AUTH_BODY", JSONObject.quote(payload.authBody));
        return pluginScript("api-page.html.tpl", values);
    }

    private boolean maybeResolveApiAuth(String message) {
        final Pending pending = pendingRequest;
        if (pending == null || pending.apiPayload == null || message == null
                || !message.startsWith(API_AUTH_PREFIX)) {
            return false;
        }
        try {
            String value = message.substring(API_AUTH_PREFIX.length());
            int separator = value.lastIndexOf('|');
            if (separator <= 0) {
                throw new IllegalArgumentException("missing auth separator");
            }
            String token = Uri.decode(value.substring(0, separator));
            long serverSeconds = Long.parseLong(value.substring(separator + 1));
            serverClockOffsetMs = serverSeconds * 1000L - System.currentTimeMillis();
            resolverPrefs.edit()
                    .putLong("clock_offset_ms", serverClockOffsetMs)
                    .putLong("clock_saved_at", System.currentTimeMillis())
                    .apply();
            if (!pending.clockSynced) {
                YangshipinApiPayload corrected = YangshipinApiPayload.create(
                        pending.channel, serverSeconds * 1000L, pending.definition);
                pending.apiPayload = corrected;
                pending.clockSynced = true;
                pending.apiHtml = buildApiPage(pending.channel, corrected);
                pending.bootstrapScript = buildOfficialBootstrapScript(corrected);
                installApiCookies(corrected);
                Log.i(TAG, "Yangshipin clock calibrated offsetMs=" + serverClockOffsetMs
                        + "; refreshing auth identity");
                webView.loadUrl("about:blank");
                webView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (pendingRequest == pending) {
                            webView.loadUrl("https://www.yangshipin.cn/");
                        }
                    }
                }, 50L);
                return true;
            }
            String liveBody = YangshipinApiPayload.createLiveBody(pending.channel,
                    pending.apiPayload.guid, pending.apiPayload.liveRandom, serverSeconds,
                    pending.definition);
            String sdkInput = YangshipinApiPayload.createSdkInput(liveBody);
            Log.i(TAG, "Yangshipin live request serverSeconds=" + serverSeconds
                    + " guid=" + pending.apiPayload.guid
                    + " requestId=" + pending.apiPayload.requestId);
            String script = buildLiveRequestScript(pending, token, serverSeconds,
                    liveBody, sdkInput);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                webView.evaluateJavascript(script, null);
            } else {
                webView.loadUrl("javascript:" + script);
            }
        } catch (Exception error) {
            Log.e(TAG, "Unable to continue Yangshipin server-time request", error);
            fail(pending, "央视频服务器时间处理失败");
        }
        return true;
    }

    private String buildLiveRequestScript(Pending pending, String token,
            long serverSeconds, String liveBody, String sdkInput) {
        YangshipinApiPayload payload = pending.apiPayload;
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("ERROR_PREFIX", JSONObject.quote(API_ERROR_PREFIX));
        values.put("RESULT_PREFIX", JSONObject.quote(API_RESULT_PREFIX));
        values.put("PID", JSONObject.quote(pending.channel.yangshipinPid));
        values.put("SERVER_SECONDS", JSONObject.quote(String.valueOf(serverSeconds)));
        values.put("GUID", JSONObject.quote(payload.guid));
        values.put("TICKET_RANDOM", JSONObject.quote(payload.ticketRandom));
        values.put("TOKEN", JSONObject.quote(token));
        values.put("REQUEST_ID", JSONObject.quote(payload.requestId));
        values.put("SDK_INPUT", JSONObject.quote(sdkInput));
        values.put("LIVE_BODY", JSONObject.quote(liveBody));
        values.put("TOKEN_TIME", JSONObject.quote(String.valueOf(serverSeconds * 1000L)));
        values.put("FULL_SDK_INPUT", JSONObject.quote(sdkInput + "-" + payload.guid
                + "-1-" + payload.requestId));
        return pluginScript("live-request.js.tpl", values);
    }

    final class SignerBridge {
        @JavascriptInterface
        public String tokenRnd(String guid, String timestampMs) {
            try {
                return NativeYspSigner.tokenRnd(guid, timestampMs);
            } catch (Throwable error) {
                Log.e(TAG, "Unable to create Yangshipin token rnd", error);
                return "";
            }
        }

        @JavascriptInterface
        public String signature(String guid, String token, String input) {
            try {
                return NativeYspSigner.signature(guid, token, input);
            } catch (Throwable error) {
                Log.e(TAG, "Unable to create Yangshipin SDK signature", error);
                return "";
            }
        }
    }

    private void installApiCookies(YangshipinApiPayload payload) {
        CookieManager cookies = CookieManager.getInstance();
        String domain = "; Domain=.yangshipin.cn; Path=/";
        cookies.setCookie("https://player-api.yangshipin.cn", "guid=" + payload.guid + domain);
        cookies.setCookie("https://player-api.yangshipin.cn", "versionName=99.99.99" + domain);
        cookies.setCookie("https://player-api.yangshipin.cn", "versionCode=999999" + domain);
        cookies.setCookie("https://player-api.yangshipin.cn", "vplatform=109" + domain);
        cookies.setCookie("https://player-api.yangshipin.cn", "platformVersion=Chrome" + domain);
        cookies.setCookie("https://player-api.yangshipin.cn", "deviceModel=148" + domain);
        cookies.setCookie("https://player-api.yangshipin.cn", "newLogin=1" + domain);
        cookies.setCookie("https://player-api.yangshipin.cn", "pc_version=1.1.16" + domain);
        cookies.setCookie("https://player-api.yangshipin.cn", "ysp_uinfo_pc=" + domain);
        cookies.setCookie("https://player-api.yangshipin.cn", "nseqId=1" + domain);
        cookies.setCookie("https://player-api.yangshipin.cn",
                "nrequest-id=" + payload.requestId + domain);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            CookieSyncManager.getInstance().sync();
        }
    }

    private String buildOfficialBootstrapScript(YangshipinApiPayload payload) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("ERROR_PREFIX", JSONObject.quote(API_ERROR_PREFIX));
        values.put("RESULT_PREFIX", JSONObject.quote(API_RESULT_PREFIX));
        values.put("PID", JSONObject.quote(pendingRequest.channel.yangshipinPid));
        values.put("GUID", JSONObject.quote(payload.guid));
        values.put("TICKET_RANDOM", JSONObject.quote(payload.ticketRandom));
        values.put("REQUEST_ID", JSONObject.quote(payload.requestId));
        values.put("LIVE_BODY", JSONObject.quote(payload.liveBody));
        return pluginScript("bootstrap.js.tpl", values);
    }

    private boolean maybeResolveApiResult(String message) {
        Pending pending = pendingRequest;
        if (pending == null || message == null) {
            return false;
        }
        if (message.startsWith(API_ERROR_PREFIX)) {
            if (message.indexOf("auth-http-401") >= 0 && !pending.bootstrapAttempted) {
                startOfficialBootstrap(pending);
                return true;
            }
            fail(pending, "央视频接口失败：" + message.substring(API_ERROR_PREFIX.length()));
            return true;
        }
        if (!message.startsWith(API_RESULT_PREFIX)) {
            return false;
        }
        try {
            String encoded = message.substring(API_RESULT_PREFIX.length());
            String response = Uri.decode(encoded);
            JSONObject root = new JSONObject(response);
            JSONObject data = root.optJSONObject("data");
            String url = data == null ? "" : data.optString("playurl", "");
            String extended = data == null ? "" : data.optString("extended_param", "");
            if (root.optInt("code", -1) != 0 || url.length() == 0
                    || !url.contains(".m3u8") || !url.contains("ysp.cctv.cn")) {
                Log.w(TAG, "Yangshipin live response code=" + root.optInt("code", -1)
                        + " msg=" + root.optString("msg", "")
                        + " urlLength=" + url.length());
                fail(pending, "央视频接口未返回可用线路");
                return true;
            }
            if (extended.length() > 0 && url.indexOf(extended) < 0) {
                url += extended;
            }
            apiCache.put(pending.cacheKey(),
                    new CachedUrl(url, System.currentTimeMillis()));
            String key = "url_" + pending.cacheKey();
            resolverPrefs.edit()
                    .putString(key, url)
                    .putLong(key + "_at", System.currentTimeMillis())
                    .apply();
            completeApi(pending, url);
        } catch (Exception error) {
            Log.e(TAG, "Unable to parse Yangshipin API response", error);
            fail(pending, "央视频接口数据异常");
        }
        return true;
    }

    private void startOfficialBootstrap(Pending pending) {
        pending.bootstrapAttempted = true;
        pending.apiHtml = null;
        Log.i(TAG, "Starting Yangshipin official auth bootstrap for " + pending.channel.name);
        webView.loadUrl("https://www.yangshipin.cn/tv/home?pid="
                + Uri.encode(pending.channel.yangshipinPid));
    }

    private void completeApi(Pending pending, String url) {
        long now = serverClockOffsetMs == Long.MIN_VALUE
                ? System.currentTimeMillis()
                : System.currentTimeMillis() + serverClockOffsetMs;
        pending.resolvedUrl = url;
        pending.cmgTag = String.valueOf(now);
        pending.cmgInitialUpdateTag = "";
        pending.cmgUpdateTag = "";
        pending.cmgUpdateWarmupCount = 0;
        pending.cmgInitTimeMs = now;
        pending.cmgUpdateBaseTimeMs = now;
        pending.cmgUpdateTrace = "";
        Log.i(TAG, "Resolved Yangshipin " + pending.definition + " API for "
                + pending.channel.name
                + " in native-light mode");
        complete(pending);
    }

    void destroy() {
        clearPending(false);
        if (webView == null) {
            return;
        }
        webView.removeCallbacks(traceKeepAlive);
        webView.stopLoading();
        webView.loadUrl("about:blank");
        ViewGroup parent = (ViewGroup) webView.getParent();
        if (parent != null) {
            parent.removeView(webView);
        }
        webView.destroy();
        webView = null;
    }

    private void maybeResolve(String url) {
        Pending pending = pendingRequest;
        if (pending == null || url == null) {
            return;
        }
        String lower = url.toLowerCase();
        if (!lower.contains(".m3u8") || !lower.contains("ysp.cctv.cn")) {
            return;
        }
        if (url.indexOf("pid=" + pending.channel.yangshipinPid) < 0) {
            return;
        }
        pending.resolvedUrl = url;
        tryComplete(pending);
    }

    private void maybeResolveCmgTag(String message) {
        Pending pending = pendingRequest;
        if (pending == null || message == null || !message.startsWith(TAG_PREFIX)) {
            return;
        }
        String tag = message.substring(TAG_PREFIX.length()).trim();
        if (tag.length() == 0) {
            return;
        }
        String updateTag = "";
        String initialUpdateTag = "";
        int warmupCount = 0;
        long initTimeMs = 0L;
        long warmupBaseTimeMs = 0L;
        String warmupTrace = "";
        String[] parts = tag.split("\\|", -1);
        if (parts.length >= 10) {
            tag = parts[0].trim();
            initialUpdateTag = parts[1].trim();
            updateTag = parts[2].trim();
            warmupCount = parsePositiveInt(parts[3].trim());
            initTimeMs = parsePositiveLong(parts[4].trim());
            warmupBaseTimeMs = parsePositiveLong(parts[5].trim());
            pending.cmgInitResult = parts[6].trim();
            pending.cmgActiveUrl = parts[7].trim();
            pending.cmgLocation = parts[8].trim();
            pending.cmgNativeTrace = parts[9].trim();
            if (parts.length >= 11) {
                warmupTrace = parts[10].trim();
            }
            if (parts.length >= 12) {
                pending.cmgImportTrace = parts[11].trim();
            }
        } else if (parts.length >= 9) {
            tag = parts[0].trim();
            initialUpdateTag = parts[1].trim();
            updateTag = parts[2].trim();
            warmupCount = parsePositiveInt(parts[3].trim());
            initTimeMs = parsePositiveLong(parts[4].trim());
            warmupBaseTimeMs = parsePositiveLong(parts[5].trim());
            pending.cmgInitResult = parts[6].trim();
            pending.cmgActiveUrl = parts[7].trim();
            pending.cmgLocation = parts[8].trim();
            if (parts.length >= 10) {
                warmupTrace = parts[9].trim();
            }
        } else if (parts.length >= 8) {
            tag = parts[0].trim();
            initialUpdateTag = parts[1].trim();
            updateTag = parts[2].trim();
            warmupCount = parsePositiveInt(parts[3].trim());
            initTimeMs = parsePositiveLong(parts[4].trim());
            warmupBaseTimeMs = parsePositiveLong(parts[5].trim());
            pending.cmgInitResult = parts[6].trim();
            pending.cmgActiveUrl = parts[7].trim();
            if (parts.length >= 9) {
                warmupTrace = parts[8].trim();
            }
        } else if (parts.length >= 6) {
            tag = parts[0].trim();
            initialUpdateTag = parts[1].trim();
            updateTag = parts[2].trim();
            warmupCount = parsePositiveInt(parts[3].trim());
            initTimeMs = parsePositiveLong(parts[4].trim());
            warmupBaseTimeMs = parsePositiveLong(parts[5].trim());
            if (parts.length >= 7) {
                warmupTrace = parts[6].trim();
            }
        } else if (parts.length >= 4) {
            tag = parts[0].trim();
            initialUpdateTag = parts[1].trim();
            updateTag = parts[2].trim();
            warmupCount = parsePositiveInt(parts[3].trim());
            if (parts.length >= 5) {
                warmupBaseTimeMs = parsePositiveLong(parts[4].trim());
            }
        } else {
            int separator = tag.indexOf('|');
            if (separator >= 0) {
                String rest = tag.substring(separator + 1).trim();
                tag = tag.substring(0, separator).trim();
                int secondSeparator = rest.indexOf('|');
                if (secondSeparator >= 0) {
                    initialUpdateTag = rest.substring(0, secondSeparator).trim();
                    updateTag = rest.substring(secondSeparator + 1).trim();
                } else {
                    initialUpdateTag = rest;
                    updateTag = rest;
                }
            }
        }
        if (tag.length() == 0 || initialUpdateTag.length() == 0 || updateTag.length() == 0) {
            return;
        }
        pending.cmgTag = tag;
        pending.cmgInitialUpdateTag = initialUpdateTag;
        pending.cmgUpdateTag = updateTag;
        pending.cmgUpdateWarmupCount = warmupCount;
        pending.cmgInitTimeMs = initTimeMs;
        pending.cmgUpdateBaseTimeMs = warmupBaseTimeMs;
        pending.cmgUpdateTrace = warmupTrace;
        Log.i(TAG, "Resolved Yangshipin CMG tag for " + pending.channel.name + ": "
                + tag + " initialTag=" + initialUpdateTag + " updateTag=" + updateTag
                + " warmupCount=" + warmupCount + " initTime=" + initTimeMs
                + " initResult=" + pending.cmgInitResult
                + " activeUrl=" + pending.cmgActiveUrl
                + " location=" + pending.cmgLocation
                + " nativeTrace=" + pending.cmgNativeTrace
                + " importTrace=" + pending.cmgImportTrace
                + " warmupBase=" + warmupBaseTimeMs + " traceLen=" + warmupTrace.length());
        tryComplete(pending);
    }

    private static int parsePositiveInt(String text) {
        try {
            int value = Integer.parseInt(text);
            return Math.max(0, value);
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private static long parsePositiveLong(String text) {
        try {
            long value = Long.parseLong(text);
            return Math.max(0L, value);
        } catch (NumberFormatException error) {
            return 0L;
        }
    }

    private void tryComplete(Pending pending) {
        if (pending.resolvedUrl == null || pending.cmgTag == null
                || pending.cmgInitialUpdateTag == null
                || pending.cmgUpdateTag == null) {
            return;
        }
        complete(pending);
    }

    private void pollPageForVideoUrl() {
        if (pendingRequest == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            return;
        }
        webView.evaluateJavascript(pluginScript("poll-page.js", null),
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        String url = decodeJsString(value);
                        if (url != null && url.length() > 0) maybeResolve(url);
                        if (pendingRequest != null) webView.postDelayed(pollPage, 1000L);
                    }
                });
    }

    private static String decodeJsString(String value) {
        if (value == null || "null".equals(value)) {
            return "";
        }
        try {
            return new JSONArray("[" + value + "]").optString(0, "");
        } catch (JSONException error) {
            return "";
        }
    }

    private void complete(final Pending pending) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (pendingRequest != pending) {
                    return;
                }
                Log.i(TAG, "Resolved Yangshipin HLS for " + pending.channel.name + ": "
                        + pending.resolvedUrl + " cmgTag=" + pending.cmgTag
                        + " initialTag=" + pending.cmgInitialUpdateTag
                        + " updateTag=" + pending.cmgUpdateTag);
                clearPending(true);
                pending.callback.onResolved(pending.requestId, pending.resolvedUrl,
                        pending.cmgTag, pending.cmgInitialUpdateTag, pending.cmgUpdateTag,
                        pending.cmgUpdateWarmupCount, pending.cmgInitTimeMs,
                        pending.cmgUpdateBaseTimeMs, pending.cmgUpdateTrace,
                        pending.cmgNativeTrace);
            }
        });
    }

    private void fail(final Pending pending, final String reason) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (pendingRequest != pending) {
                    return;
                }
                Log.w(TAG, "Yangshipin resolve failed for " + pending.channel.name + ": " + reason);
                clearPending(true);
                pending.callback.onFailed(pending.requestId, reason);
            }
        });
    }

    private void clearPending(boolean stopPage) {
        WebView currentWebView = webView;
        if (currentWebView != null) {
            currentWebView.removeCallbacks(timeout);
            currentWebView.removeCallbacks(pollPage);
        }
        pendingRequest = null;
        if (currentWebView == null) {
            return;
        }
        if (stopPage) {
            if (keepTracePage) {
                currentWebView.setVisibility(View.VISIBLE);
                currentWebView.onResume();
                startTraceKeepAlive();
                return;
            }
            currentWebView.removeCallbacks(traceKeepAlive);
            pauseWebMedia();
            currentWebView.stopLoading();
            currentWebView.loadDataWithBaseURL("about:blank", "", "text/html", "UTF-8", null);
            currentWebView.setVisibility(View.GONE);
            currentWebView.onPause();
        } else {
            currentWebView.removeCallbacks(traceKeepAlive);
        }
    }

    private void startTraceKeepAlive() {
        if (!keepTracePage || webView == null) {
            return;
        }
        webView.removeCallbacks(traceKeepAlive);
        webView.postDelayed(traceKeepAlive, 1000L);
    }

    private void keepTracePlaybackAlive() {
        if (!keepTracePage || webView == null
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return;
        try {
            webView.onResume();
            webView.evaluateJavascript(pluginScript("keepalive.js", null), null);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to keep Yangshipin trace playback alive", error);
        }
        webView.postDelayed(traceKeepAlive, 2500L);
    }

    private void pauseWebMedia() {
        if (webView == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return;
        try {
            webView.evaluateJavascript(pluginScript("pause.js", null), null);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to pause Yangshipin WebView media", error);
        }
    }

    private static final class Pending {
        final int requestId;
        final Channel channel;
        final String definition;
        final Callback callback;
        String resolvedUrl;
        String cmgTag;
        String cmgInitialUpdateTag;
        String cmgUpdateTag;
        int cmgUpdateWarmupCount;
        long cmgInitTimeMs;
        long cmgUpdateBaseTimeMs;
        String cmgInitResult = "";
        String cmgActiveUrl = "";
        String cmgLocation = "";
        String cmgNativeTrace = "";
        String cmgImportTrace = "";
        String cmgUpdateTrace = "";
        String apiHtml;
        YangshipinApiPayload apiPayload;
        boolean clockSynced;
        String bootstrapScript;
        boolean bootstrapAttempted;

        Pending(int requestId, Channel channel, String definition, Callback callback) {
            this.requestId = requestId;
            this.channel = channel;
            this.definition = "shd".equals(definition) || "hd".equals(definition)
                    || "sd".equals(definition)
                    ? definition : "fhd";
            this.callback = callback;
        }

        String cacheKey() {
            return channel.yangshipinPid + "_" + definition;
        }
    }

    private static final class CachedUrl {
        final String url;
        final long createdAt;

        CachedUrl(String url, long createdAt) {
            this.url = url;
            this.createdAt = createdAt;
        }
    }

    private static WebResourceResponse patchHlsCmgScript(String url) {
        HttpURLConnection connection = null;
        boolean responseConsumed = false;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", DESKTOP_USER_AGENT);
            String body = new String(readFully(connection.getInputStream()), "UTF-8");
            responseConsumed = true;
            String patched = patchHlsCmgSource(body);
            return new WebResourceResponse("application/javascript", "UTF-8",
                    new ByteArrayInputStream(patched.getBytes("UTF-8")));
        } catch (IOException error) {
            Log.w(TAG, "Unable to patch hls.cmg.js", error);
            return null;
        } finally {
            if (connection != null && !responseConsumed) {
                connection.disconnect();
            }
        }
    }

    private static WebResourceResponse patchOfficialPage(String url, String bootstrapScript) {
        HttpURLConnection connection = null;
        boolean responseConsumed = false;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", DESKTOP_USER_AGENT);
            String body = new String(readFully(connection.getInputStream()), "UTF-8");
            responseConsumed = true;
            int head = body.toLowerCase().indexOf("<head>");
            String injection = "<script>" + bootstrapScript + "</script>";
            String patched = head >= 0
                    ? body.substring(0, head + 6) + injection + body.substring(head + 6)
                    : injection + body;
            return new WebResourceResponse("text/html", "UTF-8",
                    new ByteArrayInputStream(patched.getBytes("UTF-8")));
        } catch (IOException error) {
            Log.w(TAG, "Unable to patch Yangshipin bootstrap page", error);
            return null;
        } finally {
            if (connection != null && !responseConsumed) {
                connection.disconnect();
            }
        }
    }

    private static String patchHlsCmgSource(String body) throws IOException {
        String importHook = pluginScript("import-hook.js", null);
        String hlsHook = pluginScript("hls-hook.js", null);
        String marker = ";var fI=function";
        int index = body.indexOf(marker);
        if (index < 0) return importHook + body + hlsHook;
        return importHook + body.substring(0, index) + hlsHook + body.substring(index);
    }

    private static byte[] readFully(java.io.InputStream input) throws IOException {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(4096);
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }
}
