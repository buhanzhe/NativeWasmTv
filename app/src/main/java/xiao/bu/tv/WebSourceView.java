package xiao.bu.tv;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.Locale;
import java.net.URLDecoder;

public final class WebSourceView extends FrameLayout {
    private static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 nTvWebView/1.5";
    interface Listener {
        void onPageStarted(int requestId, String url);
        void onPageReady(int requestId, String url, String title);
        void onPageError(int requestId, String message);
        void onStreamDiscovered(int requestId, String streamUrl, String pageUrl,
                String userAgent, String cookies);
    }

    private static final String TAG = "WebSourceView";
    private final WebView webView;
    private Listener listener;
    private int requestId = -1;
    private String pageUrl;
    private String lastStreamUrl;
    private boolean streamReported;

    public WebSourceView(Context context) {
        this(context, null);
    }

    @SuppressLint("SetJavaScriptEnabled")
    public WebSourceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundColor(0xff000000);
        webView = new WebView(context);
        addView(webView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setUserAgentString(DESKTOP_USER_AGENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings.setMediaPlaybackRequiresUserGesture(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        CookieManager.getInstance().setAcceptCookie(true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new SourceClient());
        setVisibility(View.GONE);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void open(int newRequestId, String url) {
        requestId = newRequestId;
        pageUrl = url;
        lastStreamUrl = null;
        streamReported = false;
        setVisibility(View.VISIBLE);
        bringToFront();
        webView.onResume();
        webView.loadUrl(url);
    }

    void closePage() {
        requestId = -1;
        streamReported = true;
        webView.stopLoading();
        webView.loadUrl("about:blank");
        webView.onPause();
        setVisibility(View.GONE);
    }

    boolean isPageVisible() {
        return getVisibility() == View.VISIBLE;
    }

    boolean canGoBack() {
        return isPageVisible() && webView.canGoBack();
    }

    void goBack() {
        if (webView.canGoBack()) {
            webView.goBack();
        }
    }

    void scrollByRemote(int deltaY) {
        if (isPageVisible()) {
            webView.scrollBy(0, deltaY);
        }
    }

    void resumePage() {
        if (isPageVisible()) {
            webView.onResume();
        }
    }

    void pausePage() {
        if (isPageVisible()) {
            webView.onPause();
        }
    }

    void destroyPage() {
        webView.stopLoading();
        webView.loadUrl("about:blank");
        webView.clearHistory();
        webView.removeAllViews();
        webView.destroy();
    }

    private void observeResource(String url) {
        final String streamUrl = normalizeMediaPlaylist(url);
        if (streamReported || streamUrl == null) {
            return;
        }
        if (streamUrl.equals(lastStreamUrl)) {
            return;
        }
        lastStreamUrl = streamUrl;
        final int observedRequestId = requestId;
        final String observedUrl = streamUrl;
        post(new Runnable() {
            @Override
            public void run() {
                if (streamReported || observedRequestId != requestId || listener == null) {
                    return;
                }
                streamReported = true;
                String currentPage = webView.getUrl();
                if (currentPage == null || currentPage.startsWith("about:")) {
                    currentPage = pageUrl;
                }
                String cookies = CookieManager.getInstance().getCookie(observedUrl);
                listener.onStreamDiscovered(observedRequestId, observedUrl, currentPage,
                        webView.getSettings().getUserAgentString(), cookies);
            }
        });
    }

    private static String normalizeMediaPlaylist(String url) {
        if (url == null) {
            return null;
        }
        String lower = url.toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return null;
        }
        int query = lower.indexOf('?');
        int playlist = lower.indexOf(".m3u8");
        if (playlist >= 0 && (query < 0 || playlist < query)) {
            return url;
        }
        String decoded = decodeUrl(url);
        String decodedLower = decoded.toLowerCase(Locale.US);
        int marker = decodedLower.indexOf("streamurl=");
        if (marker >= 0) {
            int start = marker + "streamurl=".length();
            int end = decoded.indexOf('&', start);
            String nested = decoded.substring(start, end < 0 ? decoded.length() : end);
            nested = decodeUrl(nested);
            String nestedLower = nested.toLowerCase(Locale.US);
            if ((nestedLower.startsWith("http://") || nestedLower.startsWith("https://"))
                    && nestedLower.indexOf(".m3u8") >= 0) {
                return nested;
            }
        }
        return lower.indexOf(".m3u8") >= 0
                || lower.indexOf("format=m3u8") >= 0
                || lower.indexOf("type=m3u8") >= 0 ? url : null;
    }

    private static String decodeUrl(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception ignored) {
            return value;
        }
    }

    private final class SourceClient extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            pageUrl = url;
            if (listener != null) {
                listener.onPageStarted(requestId, url);
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            pageUrl = url;
            if (listener != null) {
                listener.onPageReady(requestId, url, view.getTitle());
            }
        }

        @Override
        public void onLoadResource(WebView view, String url) {
            observeResource(url);
            super.onLoadResource(view, url);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            observeResource(url);
            return super.shouldInterceptRequest(view, url);
        }

        @Override
        public void onReceivedError(WebView view, int errorCode, String description,
                String failingUrl) {
            Log.w(TAG, "Web source failed code=" + errorCode + " url=" + failingUrl
                    + " description=" + description);
            if (listener != null) {
                listener.onPageError(requestId,
                        description == null ? "网页加载失败" : description);
            }
        }
    }
}
