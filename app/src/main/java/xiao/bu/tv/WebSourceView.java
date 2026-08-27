package xiao.bu.tv;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;

import org.json.JSONObject;

public final class WebSourceView extends FrameLayout {
    private static final int VIEWPORT_1080P_WIDTH = 1920;
    private static final int VIEWPORT_1080P_HEIGHT = 1080;
    private static final int VIEWPORT_720P_WIDTH = 1280;
    private static final int VIEWPORT_720P_HEIGHT = 720;
    private static final int VIEWPORT_480P_WIDTH = 854;
    private static final int VIEWPORT_480P_HEIGHT = 480;
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
    private volatile String pageUrl;
    private String lastStreamUrl;
    private boolean streamReported;
    private boolean pageActive;
    private boolean destroyed;
    private int resetGeneration;
    private String viewportMode = "1080p";
    private boolean loadImages = true;
    private int viewportWidth = VIEWPORT_1080P_WIDTH;
    private int viewportHeight = VIEWPORT_1080P_HEIGHT;
    private int compatibilityInjectionCount;
    private String compatibilityBundleUrl;
    private byte[] compatibilityBundle;

    public WebSourceView(Context context) {
        this(context, null);
    }

    @SuppressLint("SetJavaScriptEnabled")
    public WebSourceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundColor(0xff000000);
        setClipChildren(true);
        webView = new WebView(context);
        webView.setPivotX(0f);
        webView.setPivotY(0f);
        addView(webView, new LayoutParams(viewportWidth, viewportHeight));
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(false);
        settings.setUserAgentString(DESKTOP_USER_AGENT);
        webView.setInitialScale(cssInitialScalePercent());
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

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        updateDesktopViewport(width, height);
        if (pageActive) {
            scheduleDesktopViewport(0L);
            scheduleDesktopViewport(300L);
        }
    }

    private void updateDesktopViewport(int width, int height) {
        // Configuration is applied during Activity startup, before this container has
        // a measured size. Always update the WebView's virtual resolution first; only
        // the final fit-to-screen transform needs the measured parent dimensions.
        if ("480p".equals(viewportMode)) {
            viewportWidth = VIEWPORT_480P_WIDTH;
            viewportHeight = VIEWPORT_480P_HEIGHT;
        } else if ("720p".equals(viewportMode)) {
            viewportWidth = VIEWPORT_720P_WIDTH;
            viewportHeight = VIEWPORT_720P_HEIGHT;
        } else {
            viewportWidth = VIEWPORT_1080P_WIDTH;
            viewportHeight = VIEWPORT_1080P_HEIGHT;
        }
        LayoutParams params = (LayoutParams) webView.getLayoutParams();
        if (params.width != viewportWidth || params.height != viewportHeight) {
            params.width = viewportWidth;
            params.height = viewportHeight;
            webView.setLayoutParams(params);
        }
        if (width <= 0 || height <= 0) {
            return;
        }
        float scale = Math.min(width / (float) viewportWidth,
                height / (float) viewportHeight);
        float contentWidth = viewportWidth * scale;
        float contentHeight = viewportHeight * scale;
        webView.setScaleX(scale);
        webView.setScaleY(scale);
        webView.setTranslationX((width - contentWidth) / 2f);
        webView.setTranslationY((height - contentHeight) / 2f);
    }

    private void applyDesktopViewport() {
        String initialScale = String.format(Locale.US, "%.4f", cssInitialScale());
        String content = "width=" + viewportWidth
                + ",initial-scale=" + initialScale
                + ",minimum-scale=" + initialScale
                + ",maximum-scale=" + initialScale + ",user-scalable=no,viewport-fit=cover";
        String script = "(function(w){var d=document,t='" + content + "';"
                + "w.__ntvViewportTarget=t;var a=function(){"
                + "var m=d.querySelector('meta[name=viewport]');"
                + "if(!m){m=d.createElement('meta');m.name='viewport';"
                + "(d.head||d.documentElement).appendChild(m);}"
                + "if(m.content!==w.__ntvViewportTarget)m.content=w.__ntvViewportTarget;};"
                + "w.__ntvApplyViewport=a;a();"
                + "if(!w.__ntvViewportObserver&&w.MutationObserver){"
                + "w.__ntvViewportObserver=new MutationObserver(a);"
                + "w.__ntvViewportObserver.observe(d.documentElement,"
                + "{subtree:true,childList:true,attributes:true,attributeFilter:['content','name']});}"
                + "})(window);";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.evaluateJavascript(script, null);
        } else {
            webView.loadUrl("javascript:" + script);
        }
    }

    private void scheduleDesktopViewport(long delayMillis) {
        final int expectedRequestId = requestId;
        postDelayed(new Runnable() {
            @Override
            public void run() {
                if (pageActive && requestId == expectedRequestId && !destroyed) {
                    applyDesktopViewport();
                }
            }
        }, delayMillis);
    }

    private float cssInitialScale() {
        float density = getResources().getDisplayMetrics().density;
        return density > 0f ? 1f / density : 1f;
    }

    private int cssInitialScalePercent() {
        return Math.max(1, Math.round(cssInitialScale() * 100f));
    }

    void applyConfiguration(String requestedViewportMode, boolean requestedLoadImages) {
        String nextMode = "480p".equals(requestedViewportMode)
                || "720p".equals(requestedViewportMode)
                ? requestedViewportMode : "1080p";
        boolean changed = !nextMode.equals(viewportMode) || loadImages != requestedLoadImages;
        viewportMode = nextMode;
        loadImages = requestedLoadImages;
        WebSettings settings = webView.getSettings();
        settings.setLoadsImagesAutomatically(loadImages);
        settings.setBlockNetworkImage(!loadImages);
        webView.setInitialScale(cssInitialScalePercent());
        updateDesktopViewport(getWidth(), getHeight());
        if (changed && pageActive) {
            final int expectedRequestId = requestId;
            webView.post(new Runnable() {
                @Override
                public void run() {
                    if (pageActive && requestId == expectedRequestId && !destroyed) {
                        webView.setInitialScale(cssInitialScalePercent());
                        webView.reload();
                    }
                }
            });
        }
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void open(int newRequestId, String url) {
        // Every web channel is a new, temporary page. Do not let a page opened by a
        // previous channel remain in WebView's back/forward list.
        webView.stopLoading();
        webView.clearHistory();
        requestId = newRequestId;
        pageUrl = url;
        lastStreamUrl = null;
        streamReported = false;
        pageActive = true;
        compatibilityInjectionCount = 0;
        resetGeneration++;
        setVisibility(View.VISIBLE);
        bringToFront();
        webView.onResume();
        updateDesktopViewport(getWidth(), getHeight());
        webView.setInitialScale(cssInitialScalePercent());
        webView.loadUrl(url);
    }

    void closePage() {
        if (destroyed) {
            return;
        }
        if (!pageActive && requestId < 0 && getVisibility() != View.VISIBLE) {
            webView.clearHistory();
            return;
        }
        final int generation = ++resetGeneration;
        pageActive = false;
        requestId = -1;
        pageUrl = null;
        lastStreamUrl = null;
        streamReported = true;
        webView.stopLoading();
        webView.clearHistory();
        webView.loadUrl("about:blank");
        webView.clearHistory();
        webView.onPause();
        setVisibility(View.GONE);
        // Loading about:blank can itself create a history entry on some old WebView
        // implementations, so clear once more after the navigation has settled.
        post(new Runnable() {
            @Override
            public void run() {
                if (!pageActive && generation == resetGeneration) {
                    webView.clearHistory();
                }
            }
        });
    }

    boolean isPageVisible() {
        return getVisibility() == View.VISIBLE;
    }

    void scrollByRemote(int deltaY) {
        if (isPageVisible()) {
            webView.scrollBy(0, deltaY);
        }
    }

    void dispatchRemoteKey(int keyCode, int metaState) {
        if (!isPageVisible()) {
            return;
        }
        webView.requestFocus();
        long now = android.os.SystemClock.uptimeMillis();
        webView.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                keyCode, 0, metaState));
        webView.dispatchKeyEvent(new KeyEvent(now, now + 24L, KeyEvent.ACTION_UP,
                keyCode, 0, metaState));
    }

    void inputTextRemote(String text) {
        if (!isPageVisible() || text == null || text.length() == 0) {
            return;
        }
        String script = "(function(t){var e=document.activeElement;if(!e||e===document.body)"
                + "return false;if(e.isContentEditable){var s=window.getSelection();"
                + "if(s&&s.rangeCount){var r=s.getRangeAt(0);r.deleteContents();"
                + "r.insertNode(document.createTextNode(t));r.collapse(false)}else e.textContent+=t}"
                + "else if(typeof e.value==='string'){var a=typeof e.selectionStart==='number'"
                + "?e.selectionStart:e.value.length,b=typeof e.selectionEnd==='number'"
                + "?e.selectionEnd:a,v=e.value.slice(0,a)+t+e.value.slice(b),"
                + "p=Object.getPrototypeOf(e),d=p&&Object.getOwnPropertyDescriptor(p,'value');"
                + "if(d&&d.set)d.set.call(e,v);else e.value=v;"
                + "if(e.setSelectionRange)e.setSelectionRange(a+t.length,a+t.length)}else return false;"
                + "try{e.dispatchEvent(new Event('input',{bubbles:true}));"
                + "e.dispatchEvent(new Event('change',{bubbles:true}))}catch(x){}return true})("
                + JSONObject.quote(text) + ");";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.evaluateJavascript(script, null);
        } else {
            webView.loadUrl("javascript:" + script);
        }
    }

    void clearBrowserCache() {
        if (destroyed) {
            return;
        }
        webView.clearCache(true);
        webView.clearHistory();
        webView.clearFormData();
        android.webkit.WebStorage.getInstance().deleteAllData();
    }

    long browserCacheSizeBytes() {
        long bytes = measureFiles(getContext().getCacheDir(), true);
        File webViewData = getContext().getDir("webview", Context.MODE_PRIVATE);
        return bytes + measureFiles(webViewData, false);
    }

    private static long measureFiles(File file, boolean insideCache) {
        if (file == null || !file.exists()) {
            return 0L;
        }
        boolean cache = insideCache
                || file.getName().toLowerCase(Locale.US).contains("cache");
        if (file.isFile()) {
            return cache ? Math.max(0L, file.length()) : 0L;
        }
        long total = 0L;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                total += measureFiles(child, cache);
            }
        }
        return total;
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
        destroyed = true;
        pageActive = false;
        requestId = -1;
        resetGeneration++;
        webView.stopLoading();
        webView.loadUrl("about:blank");
        webView.clearHistory();
        webView.removeAllViews();
        webView.destroy();
    }

    private void observeResource(String url) {
        if (!pageActive || requestId < 0) {
            return;
        }
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
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (!pageActive || requestId < 0 || url == null) {
                return false;
            }
            String lower = url.toLowerCase(Locale.US);
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                return false;
            }
            // Keep redirects and links inside the same TV WebView. This also covers a
            // source whose landing page later moves to a site-specific route.
            pageUrl = url;
            view.loadUrl(url);
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (!pageActive || requestId < 0 || isBlankPage(url)) {
                return;
            }
            pageUrl = url;
            compatibilityInjectionCount = 0;
            updateDesktopViewport(getWidth(), getHeight());
            view.setInitialScale(cssInitialScalePercent());
            if (listener != null) {
                listener.onPageStarted(requestId, url);
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (!pageActive || requestId < 0 || isBlankPage(url)) {
                return;
            }
            pageUrl = url;
            applyDesktopViewport();
            scheduleDesktopViewport(250L);
            scheduleDesktopViewport(1000L);
            // A web channel is not a browser session. Clearing after every completed
            // top-level navigation also prevents scripts/redirects from rebuilding a
            // back stack that could expose an older channel.
            view.clearHistory();
            if (listener != null) {
                listener.onPageReady(requestId, url, view.getTitle());
            }
        }

        @Override
        public void onLoadResource(WebView view, String url) {
            if (compatibilityInjectionCount < 3 && isJavascriptResource(url)) {
                // onPageStarted can race with creation of the new document on old
                // Chromium. Repeat immediately before the first scripts are executed.
                injectJavascriptCompatibility(view);
            }
            observeResource(url);
            super.onLoadResource(view, url);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            observeResource(url);
            WebResourceResponse compatible = interceptCompatibilityBundle(url);
            if (compatible != null) {
                return compatible;
            }
            return super.shouldInterceptRequest(view, url);
        }

        @Override
        public void onReceivedError(WebView view, int errorCode, String description,
                String failingUrl) {
            if (!pageActive || requestId < 0 || isBlankPage(failingUrl)) {
                return;
            }
            Log.w(TAG, "Web source failed code=" + errorCode + " url=" + failingUrl
                    + " description=" + description);
            if (listener != null) {
                listener.onPageError(requestId,
                        description == null ? "网页加载失败" : description);
            }
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            if (pageActive && requestId >= 0 && !isBlankPage(url)) {
                // Keep the current address in sync for redirects and single-page sites
                // that move from one route to another without reopening the channel.
                pageUrl = url;
                scheduleDesktopViewport(0L);
                scheduleDesktopViewport(300L);
            }
            super.doUpdateVisitedHistory(view, url, isReload);
        }
    }

    private void injectJavascriptCompatibility(WebView view) {
        compatibilityInjectionCount++;
        String script = javascriptCompatibilityScript();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            view.evaluateJavascript(script, null);
        } else {
            view.loadUrl("javascript:" + script);
        }
    }

    private static String javascriptCompatibilityScript() {
        return "(function(w){"
                + "if(typeof w.globalThis==='undefined')w.globalThis=w;"
                + "if(!String.prototype.replaceAll)Object.defineProperty(String.prototype,'replaceAll',"
                + "{configurable:true,writable:true,value:function(s,r){var v=String(this);"
                + "if(s instanceof RegExp){if(!s.global)throw new TypeError('replaceAll requires a global RegExp');"
                + "return v.replace(s,r)}s=String(s);if(typeof r!=='function')return v.split(s).join(r);"
                + "var o='',p=0,i;while((i=v.indexOf(s,p))!==-1){o+=v.slice(p,i)+r(s,i,v);"
                + "p=i+s.length;if(!s.length)p++}return o+v.slice(p)}});"
                + "if(!Array.prototype.flat)Object.defineProperty(Array.prototype,'flat',"
                + "{configurable:true,writable:true,value:function(d){d=d===undefined?1:Number(d)||0;"
                + "var o=[];(function f(a,n){for(var i=0;i<a.length;i++)if(i in a){var v=a[i];"
                + "if(n>0&&Array.isArray(v))f(v,n-1);else o.push(v)}})(this,d);return o}});"
                + "if(!Array.prototype.flatMap)Object.defineProperty(Array.prototype,'flatMap',"
                + "{configurable:true,writable:true,value:function(f,t){return this.map(f,t).flat(1)}});"
                + "})(window);";
    }

    private WebResourceResponse interceptCompatibilityBundle(String resourceUrl) {
        if (!isMaimemoCompatibilityBundle(resourceUrl)) {
            return null;
        }
        try {
            byte[] patched = compatibilityBundle;
            if (patched == null || !resourceUrl.equals(compatibilityBundleUrl)) {
                patched = downloadAndPatchJavascript(resourceUrl);
                compatibilityBundleUrl = resourceUrl;
                compatibilityBundle = patched;
            }
            return new WebResourceResponse("application/javascript", "UTF-8",
                    new ByteArrayInputStream(patched));
        } catch (Exception error) {
            Log.w(TAG, "Unable to patch old-WebView compatibility bundle " + resourceUrl,
                    error);
            return null;
        }
    }

    private byte[] downloadAndPatchJavascript(String resourceUrl) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(resourceUrl).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", DESKTOP_USER_AGENT);
        connection.setRequestProperty("Accept", "application/javascript,*/*;q=0.8");
        String referer = pageUrl;
        if (referer != null && referer.length() > 0) {
            connection.setRequestProperty("Referer", referer);
        }
        String cookies = CookieManager.getInstance().getCookie(resourceUrl);
        if (cookies != null && cookies.length() > 0) {
            connection.setRequestProperty("Cookie", cookies);
        }
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new java.io.IOException("HTTP " + responseCode);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    Math.max(32768, connection.getContentLength() + 2048));
            output.write(javascriptCompatibilityScript().getBytes("UTF-8"));
            output.write('\n');
            InputStream input = connection.getInputStream();
            try {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            } finally {
                input.close();
            }
            return output.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    private static boolean isMaimemoCompatibilityBundle(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        return lower.startsWith("https://tc-apis.maimemo.com/webstudy/app/js/vendors.")
                && lower.indexOf(".js") >= 0;
    }

    private static boolean isJavascriptResource(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        int query = lower.indexOf('?');
        if (query >= 0) {
            lower = lower.substring(0, query);
        }
        return lower.endsWith(".js");
    }

    private static boolean isBlankPage(String url) {
        return url == null || url.startsWith("about:");
    }
}
