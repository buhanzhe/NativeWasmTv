package xiao.bu.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
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

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Locale;

/** Executes a signed CJS site entry in a small off-screen WebView. */
final class CjsSiteResolver {
    interface Callback {
        void onResolved(int requestId, Result result);
        void onFailed(int requestId, String reason);
    }

    static final class Result {
        final String url;
        final String referer;
        final String transformer;
        final String[] transformerArgs;
        final String[] mediaHosts;

        Result(String url, String referer, String transformer,
                String[] transformerArgs, String[] mediaHosts) {
            this.url = url;
            this.referer = referer;
            this.transformer = transformer;
            this.transformerArgs = transformerArgs;
            this.mediaHosts = mediaHosts;
        }
    }

    private static final String TAG = "CjsSiteResolver";
    private static final String EXECUTOR_URL = "https://ntv.local/cjs/";
    private static final String EXECUTOR_PAGE =
            "<!doctype html><meta charset=\"utf-8\"><body></body>";
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final long TIMEOUT_MS = 20000L;

    private final Activity activity;
    private final FrameLayout root;
    private WebView webView;
    private Pending pending;
    private int generation;
    private final Bridge bridge = new Bridge();
    private final Runnable timeout = new Runnable() {
        @Override
        public void run() {
            Pending request = pending;
            if (request != null) {
                fail(request, "在线站点插件解析超时");
            }
        }
    };

    CjsSiteResolver(Activity activity, FrameLayout root) {
        this.activity = activity;
        this.root = root;
    }

    void resolve(final int requestId, final String channelName, final String pageUrl,
            final Callback callback) {
        cancel();
        final int requestGeneration = generation;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final CjsPluginRuntime.SitePlugin site =
                            CjsPluginRuntime.siteForUrl(pageUrl);
                    if (site == null) {
                        throw new IOException("没有匹配该网页的在线站点插件");
                    }
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (requestGeneration == generation && !activity.isFinishing()) {
                                start(new Pending(requestId, requestGeneration,
                                        channelName, pageUrl, site, callback));
                            }
                        }
                    });
                } catch (final Exception error) {
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (requestGeneration == generation) {
                                callback.onFailed(requestId, safeMessage(error));
                            }
                        }
                    });
                }
            }
        }, "cjs-site-load").start();
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void ensureWebView() throws IOException {
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
        webView.addJavascriptInterface(bridge, "NtvCjsBridge");
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

    private void start(Pending request) {
        try {
            ensureWebView();
        } catch (IOException error) {
            request.callback.onFailed(request.requestId, error.getMessage());
            return;
        }
        pending = request;
        Log.i(TAG, "Starting site plug-in id=" + request.site.id
                + " page=" + request.pageUrl);
        webView.removeCallbacks(timeout);
        webView.postDelayed(timeout, TIMEOUT_MS);
        webView.loadDataWithBaseURL(EXECUTOR_URL, EXECUTOR_PAGE,
                "text/html", "UTF-8", null);
    }

    private void execute(Pending request) {
        if (pending != request || generation != request.generation) {
            return;
        }
        Log.d(TAG, "Executing site plug-in id=" + request.site.id);
        webView.evaluateJavascript(buildJavascript(request), null);
    }

    private static String buildJavascript(Pending request) {
        JSONObject item = new JSONObject();
        try {
            item.put("url", request.pageUrl);
            item.put("name", request.channelName == null ? "" : request.channelName);
        } catch (JSONException ignored) {
        }
        return "(function(){'use strict';"
                + "function json(v,d){try{return JSON.parse(v);}catch(e){return d;}}"
                + "function headers(v){return typeof v==='string'?v:JSON.stringify(v||{});}"
                + "window.cjs={"
                + "get:function(u,h){return NtvCjsBridge.get(String(u),headers(h));},"
                + "post:function(u,b,h){return NtvCjsBridge.post(String(u),String(b||''),headers(h));},"
                + "request:function(u,m,h,b,f){return json(NtvCjsBridge.request(String(u),String(m||'GET'),headers(h),String(b||''),f!==false),{});},"
                + "md5:function(v){return NtvCjsBridge.md5(String(v));},"
                + "log:function(v){NtvCjsBridge.log(String(v));}};"
                + "function done(v){NtvCjsBridge.complete(JSON.stringify(v==null?{}:v));}"
                + "function fail(e){NtvCjsBridge.fail(String(e&&e.stack?e.stack:e));}"
                + "try{var pluginMain=(function(){" + request.site.script + "\n"
                + "return typeof main==='function'?main:null;})();"
                + "if(typeof pluginMain!=='function')throw new Error('站点插件没有 main(item) 入口');"
                + "var r=pluginMain(" + item.toString() + ");"
                + "if(r&&typeof r.then==='function'){r.then(done,fail);}else{done(r);}}"
                + "catch(e){fail(e);}})();";
    }

    private void complete(final Pending request, String json) {
        try {
            JSONObject value = new JSONObject(json == null ? "{}" : json);
            String url = value.optString("url", "").trim();
            String referer = value.optString("referer", request.pageUrl).trim();
            String transformer = value.optString("transformer", "").trim();
            String[] transformerArgs = stringArray(value.optJSONArray("transformerArgs"));
            String[] mediaHosts = stringArray(value.optJSONArray("mediaHosts"));
            if (!isOnline(url)) {
                throw new IOException("站点插件没有返回在线播放地址");
            }
            if (!isOnline(referer)) {
                referer = request.pageUrl;
            }
            if (transformer.length() > 0 && (!transformer.equals(request.site.transformer)
                    || transformerArgs.length == 0 || mediaHosts.length == 0)) {
                throw new IOException("站点插件解密参数不匹配");
            }
            final Result result = new Result(url, referer, transformer,
                    transformerArgs, mediaHosts);
            clearPending();
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (generation == request.generation) {
                        request.callback.onResolved(request.requestId, result);
                    }
                }
            });
        } catch (final Exception error) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    fail(request, "站点插件结果无效: " + safeMessage(error));
                }
            });
        }
    }

    private void fail(final Pending request, final String reason) {
        if (request == null || pending != request) {
            return;
        }
        clearPending();
        request.callback.onFailed(request.requestId, reason);
    }

    private void clearPending() {
        if (webView != null) {
            webView.removeCallbacks(timeout);
        }
        pending = null;
    }

    void cancel() {
        generation++;
        clearPending();
        destroyWebView();
    }

    void destroy() {
        cancel();
    }

    private void destroyWebView() {
        if (webView == null) {
            return;
        }
        webView.stopLoading();
        webView.removeJavascriptInterface("NtvCjsBridge");
        ViewGroup parent = (ViewGroup) webView.getParent();
        if (parent != null) {
            parent.removeView(webView);
        }
        webView.destroy();
        webView = null;
    }

    private static boolean isOnline(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private static String[] stringArray(JSONArray values) {
        if (values == null) {
            return new String[0];
        }
        String[] result = new String[values.length()];
        for (int index = 0; index < values.length(); index++) {
            result[index] = values.optString(index, "").trim();
        }
        return result;
    }

    private static String safeMessage(Throwable error) {
        String value = error == null ? null : error.getMessage();
        return TextUtils.isEmpty(value) ? "未知错误" : value;
    }

    private final class Bridge {
        @JavascriptInterface
        public String get(String url, String headersJson) {
            if (!isOnline(url)) {
                return "";
            }
            try {
                return Ku9HttpClient.getText(url,
                        Ku9HttpClient.parseHeaders(headersJson), MAX_RESPONSE_BYTES);
            } catch (IOException error) {
                Log.w(TAG, "CJS GET failed " + url, error);
                return "";
            }
        }

        @JavascriptInterface
        public String post(String url, String body, String headersJson) {
            return isOnline(url) ? Ku9HttpClient.postText(
                    url, body, headersJson, MAX_RESPONSE_BYTES) : "";
        }

        @JavascriptInterface
        public String request(String url, String method, String headersJson,
                String body, boolean followRedirects) {
            if (!isOnline(url)) {
                return "{\"code\":0,\"error\":\"online URL required\"}";
            }
            long startedAt = android.os.SystemClock.elapsedRealtime();
            String result = Ku9HttpClient.requestJson(url, method, headersJson, body,
                    followRedirects, MAX_RESPONSE_BYTES);
            Log.i(TAG, "Site request completed elapsedMs="
                    + (android.os.SystemClock.elapsedRealtime() - startedAt)
                    + " url=" + url);
            return result;
        }

        @JavascriptInterface
        public String md5(String value) {
            try {
                byte[] bytes = MessageDigest.getInstance("MD5")
                        .digest(String.valueOf(value).getBytes("UTF-8"));
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
            Log.i(TAG, value);
        }

        @JavascriptInterface
        public void complete(final String json) {
            final Pending request = pending;
            if (request != null) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        CjsSiteResolver.this.complete(request, json);
                    }
                });
            }
        }

        @JavascriptInterface
        public void fail(final String reason) {
            final Pending request = pending;
            if (request != null) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        CjsSiteResolver.this.fail(request,
                                "站点插件执行失败: " + reason);
                    }
                });
            }
        }
    }

    private static final class Pending {
        final int requestId;
        final int generation;
        final String channelName;
        final String pageUrl;
        final CjsPluginRuntime.SitePlugin site;
        final Callback callback;

        Pending(int requestId, int generation, String channelName, String pageUrl,
                CjsPluginRuntime.SitePlugin site, Callback callback) {
            this.requestId = requestId;
            this.generation = generation;
            this.channelName = channelName;
            this.pageUrl = pageUrl;
            this.site = site;
            this.callback = callback;
        }
    }
}
