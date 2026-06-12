package com.bu.cc.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
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
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;

final class YangshipinWebResolver {
    interface Callback {
        void onResolved(int requestId, String url, String cmgTag,
                String cmgInitialUpdateTag, String cmgUpdateTag);

        void onFailed(int requestId, String reason);
    }

    private static final String TAG = "YangshipinResolver";
    private static final long TIMEOUT_MS = 30000L;
    private static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    private static final String TAG_PREFIX = "__NTV_CMG_MEDIA_TAG__";
    private static final String HLS_HOOK =
            ";(function(){try{"
                    + "if(!fG||!fG.moduleDecData||fG.__ntvTagHooked)return;"
                    + "fG.__ntvTagHooked=true;"
                    + "var originalModuleDecData=fG.moduleDecData;"
                    + "fG.moduleDecData=function(module,mediaTagId,data){"
                    + "try{"
                    + "var nalType=data&&data.length?(data[0]&31):-1;"
                    + "var tag=self.vmpTag||'';"
                    + "if(!fG.__ntvFirstVmpTag&&nalType===7&&tag){fG.__ntvFirstVmpTag=tag;}"
                    + "if(!fG.__ntvLoggedMediaTag&&(nalType===1||nalType===5)&&tag){"
                    + "fG.__ntvLoggedMediaTag=true;"
                    + "console.log('" + TAG_PREFIX + "'+mediaTagId+'|'+(fG.__ntvFirstVmpTag||tag)+'|'+tag);"
                    + "}"
                    + "}catch(e){}"
                    + "return originalModuleDecData.apply(this,arguments);"
                    + "};"
                    + "}catch(e){try{console.log('__NTV_CMG_HOOK_ERROR__'+e);}catch(x){}}"
                    + "})();";

    private final Activity activity;
    private final WebView webView;
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

    private Pending pendingRequest;

    @SuppressLint("SetJavaScriptEnabled")
    YangshipinWebResolver(Activity activity, FrameLayout root) {
        this.activity = activity;
        webView = new WebView(activity.getApplicationContext());
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setAlpha(0.01f);
        webView.setVisibility(View.GONE);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1, 1);
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
                    maybeResolveCmgTag(consoleMessage.message());
                }
                return super.onConsoleMessage(consoleMessage);
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onLoadResource(WebView view, String url) {
                maybeResolve(url);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
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

    void resolve(final int requestId, final Channel channel, final Callback callback) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                clearPending(false);
                if (channel.yangshipinPid == null || channel.yangshipinPid.length() == 0) {
                    callback.onFailed(requestId, "频道缺少央视频 pid");
                    return;
                }
                pendingRequest = new Pending(requestId, channel, callback);
                webView.setVisibility(View.VISIBLE);
                final String url = "https://www.yangshipin.cn/tv/home?pid="
                        + Uri.encode(channel.yangshipinPid);
                Log.i(TAG, "Resolving Yangshipin HLS for " + channel.name + ": " + url);
                webView.loadUrl("about:blank");
                webView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        webView.loadUrl(url);
                        webView.postDelayed(pollPage, 1500L);
                    }
                }, 100L);
                webView.postDelayed(timeout, TIMEOUT_MS);
            }
        });
    }

    void destroy() {
        clearPending(false);
        webView.stopLoading();
        webView.loadUrl("about:blank");
        ViewGroup parent = (ViewGroup) webView.getParent();
        if (parent != null) {
            parent.removeView(webView);
        }
        webView.destroy();
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
        if (tag.length() == 0 || initialUpdateTag.length() == 0 || updateTag.length() == 0) {
            return;
        }
        pending.cmgTag = tag;
        pending.cmgInitialUpdateTag = initialUpdateTag;
        pending.cmgUpdateTag = updateTag;
        Log.i(TAG, "Resolved Yangshipin CMG tag for " + pending.channel.name + ": "
                + tag + " initialTag=" + initialUpdateTag + " updateTag=" + updateTag);
        tryComplete(pending);
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
        if (pendingRequest == null) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            return;
        }
        webView.evaluateJavascript(
                "(function(){"
                        + "function scan(o,d){"
                        + "if(!o||d>5)return '';"
                        + "if(typeof o==='string')return o.indexOf('.m3u8')>=0?o:'';"
                        + "if(typeof o!=='object')return '';"
                        + "if(Array.isArray(o)){for(var i=0;i<o.length;i++){var r=scan(o[i],d+1);if(r)return r;}return '';}"
                        + "if(o.videoUrl&&String(o.videoUrl).indexOf('.m3u8')>=0)return String(o.videoUrl);"
                        + "for(var k in o){var v=o[k];"
                        + "if(/url|video|src|current/i.test(k)){var r=scan(v,d+1);if(r)return r;}"
                        + "}"
                        + "return '';"
                        + "}"
                        + "var nodes=document.querySelectorAll('*');"
                        + "for(var i=0;i<nodes.length;i++){"
                        + "if(nodes[i].__vue__){var r=scan(nodes[i].__vue__.$data,0);if(r)return r;}"
                        + "}"
                        + "return '';"
                        + "})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        String url = decodeJsString(value);
                        if (url != null && url.length() > 0) {
                            maybeResolve(url);
                        }
                        if (pendingRequest != null) {
                            webView.postDelayed(pollPage, 1000L);
                        }
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
                        pending.cmgTag, pending.cmgInitialUpdateTag, pending.cmgUpdateTag);
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
        webView.removeCallbacks(timeout);
        webView.removeCallbacks(pollPage);
        pendingRequest = null;
        if (stopPage) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.setVisibility(View.GONE);
        }
    }

    private static final class Pending {
        final int requestId;
        final Channel channel;
        final Callback callback;
        String resolvedUrl;
        String cmgTag;
        String cmgInitialUpdateTag;
        String cmgUpdateTag;

        Pending(int requestId, Channel channel, Callback callback) {
            this.requestId = requestId;
            this.channel = channel;
            this.callback = callback;
        }
    }

    private static WebResourceResponse patchHlsCmgScript(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", DESKTOP_USER_AGENT);
            String body = new String(readFully(connection.getInputStream()), "UTF-8");
            String patched = patchHlsCmgSource(body);
            return new WebResourceResponse("application/javascript", "UTF-8",
                    new ByteArrayInputStream(patched.getBytes("UTF-8")));
        } catch (IOException error) {
            Log.w(TAG, "Unable to patch hls.cmg.js", error);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String patchHlsCmgSource(String body) {
        String marker = ";var fI=function";
        int index = body.indexOf(marker);
        if (index < 0) {
            return body + HLS_HOOK;
        }
        return body.substring(0, index) + HLS_HOOK + body.substring(index);
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
