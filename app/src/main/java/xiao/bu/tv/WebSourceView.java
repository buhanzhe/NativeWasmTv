package xiao.bu.tv;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebBackForwardList;
import android.webkit.WebHistoryItem;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.net.URLDecoder;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.LinkedHashSet;

import org.json.JSONObject;

public final class WebSourceView extends FrameLayout {
    private static final int VIEWPORT_4K_WIDTH = 3840;
    private static final int VIEWPORT_4K_HEIGHT = 2160;
    private static final int VIEWPORT_2K_WIDTH = 2560;
    private static final int VIEWPORT_2K_HEIGHT = 1440;
    private static final int VIEWPORT_1080P_WIDTH = 1920;
    private static final int VIEWPORT_1080P_HEIGHT = 1080;
    private static final int VIEWPORT_720P_WIDTH = 1280;
    private static final int VIEWPORT_720P_HEIGHT = 720;
    private static final String WINDOWS_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String IPAD_USER_AGENT =
            "Mozilla/5.0 (iPad; CPU OS 17_1 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/17.1 Mobile/15E148 Safari/604.1";
    interface Listener {
        void onPageStarted(int requestId, String url);
        void onPageReady(int requestId, String url, String title);
        void onPageError(int requestId, String message);
        void onStreamDiscovered(int requestId, String streamUrl, String pageUrl,
                String userAgent, String cookies);
        void onCastEdgeStart();
        void onCastEdgePrevious();
        void onCastEdgeNext();
        void onCastEdgeStop();
    }

    private static final String TAG = "WebSourceView";
    private volatile WebView webView;
    private volatile SourceClient sourceClient;
    private DesktopWebProfile desktopProfile;
    private WebRenderDiagnostics renderDiagnostics;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private final LinearLayout loadingOverlay;
    private final CastEdgeController castEdgeController;
    private String browserUserAgent;
    private LoadingSpinnerView loadingProgress;
    private TextView loadingText;
    private Listener listener;
    private volatile int requestId = -1;
    private volatile String pageUrl;
    private final LinkedHashSet<String> discoveredStreamUrls =
            new LinkedHashSet<String>();
    private volatile boolean pageActive;
    private boolean clearInitialHistory;
    private boolean destroyed;
    private int resetGeneration;
    private String viewportMode = "720p";
    private float pageScale = 1f;
    private float currentPageScale = 1f;
    private String userAgentMode = "windows";
    private volatile String activeUserAgent = WINDOWS_USER_AGENT;
    private boolean loadImages = true;
    private int viewportWidth = VIEWPORT_720P_WIDTH;
    private int viewportHeight = VIEWPORT_720P_HEIGHT;
    private int compatibilityInjectionCount;
    private String compatibilityBundleUrl;
    private byte[] compatibilityBundle;
    private boolean castCaptureActive;
    private int castCaptureFrameRate;
    private boolean hostResumed;
    private int rendererRetries;
    private long cachedBrowserCacheBytes;
    private long browserCacheMeasuredAt;
    private boolean castEdgeAvailable;
    private boolean castEdgeCasting;
    private boolean castEdgeBusy;
    private boolean castPointerVisible = true;
    private boolean castEdgeTransientVisible;
    private String castEdgeContentName = "";
    private final Runnable hideTransientCastEdge = new Runnable() {
        @Override public void run() {
            castEdgeTransientVisible = false;
            applyCastEdgeState();
        }
    };

    public WebSourceView(Context context) {
        this(context, null);
    }

    @SuppressLint("SetJavaScriptEnabled")
    public WebSourceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundColor(0xff000000);
        setClipChildren(true);
        // Creating even a hidden WebView initializes the Chromium/WebKit runtime and
        // noticeably delays native-channel startup on Android 4.x televisions. Keep
        // the browser lazy and create it only when a web channel is actually opened.
        webView = null;
        loadingOverlay = createLoadingOverlay(context);
        addView(loadingOverlay, new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        castEdgeController = new CastEdgeController(context);
        castEdgeController.setCallback(new CastEdgeController.Callback() {
            @Override public void onStartCast() {
                if (listener != null) listener.onCastEdgeStart();
            }
            @Override public void onPreviousChannel() {
                if (listener != null) listener.onCastEdgePrevious();
            }
            @Override public void onNextChannel() {
                if (listener != null) listener.onCastEdgeNext();
            }
            @Override public void onStopCast() {
                if (listener != null) listener.onCastEdgeStop();
            }
        });
        LayoutParams edgeParams = new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        edgeParams.rightMargin = Math.round(8f * getResources().getDisplayMetrics().density);
        addView(castEdgeController, edgeParams);
        setVisibility(View.GONE);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createWebView(Context context) {
        WebView nextWebView = new CastWebView(context);
        WebSettings settings = nextWebView.getSettings();
        if (browserUserAgent == null) {
            browserUserAgent = settings.getUserAgentString();
        }
        activeUserAgent = userAgentForMode(userAgentMode);
        configureWebViewSettings(settings);
        nextWebView.setPivotX(0f);
        nextWebView.setPivotY(0f);
        nextWebView.setInitialScale(cssInitialScalePercent());
        applyCastCaptureLayer(nextWebView);
        applyCastFrameRate(nextWebView);
        nextWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (webView != nextWebView || !pageActive || fullscreenView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                fullscreenView = view;
                fullscreenCallback = callback;
                setLoadingVisible(false);
                addView(view, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
                nextWebView.setVisibility(View.INVISIBLE);
                castEdgeController.bringToFront();
                view.requestFocus();
                // Stay in the same hierarchy so cast capture, cursor hit-testing
                // and the fixed landscape viewport continue to work in fullscreen.
            }

            @Override
            public void onHideCustomView() {
                if (webView == nextWebView) hideFullscreenView();
            }

            @Override
            public Bitmap getDefaultVideoPoster() {
                Bitmap poster = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
                poster.eraseColor(Color.TRANSPARENT);
                return poster;
            }
        });
        sourceClient = new SourceClient();
        WebViewRecovery.attach(nextWebView, sourceClient, this::onRendererGone);
        desktopProfile = new DesktopWebProfile(nextWebView);
        updateDesktopProfile();
        return nextWebView;
    }

    /** This page is visible on the cast surface even when its Activity window is hidden. */
    private final class CastWebView extends WebView {
        private Boolean pageResumed;
        CastWebView(Context context) { super(context); }

        @Override public int getWindowVisibility() {
            return isCastPage(this) ? View.VISIBLE : super.getWindowVisibility();
        }

        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(isCastPage(this) ? View.VISIBLE : visibility);
        }

        @Override public void onVisibilityAggregated(boolean isVisible) {
            if (Build.VERSION.SDK_INT >= 24) {
                super.onVisibilityAggregated(isCastPage(this) || isVisible);
            }
        }

        void updatePageLifecycle(boolean resumed) {
            if (pageResumed == null || pageResumed != resumed) {
                pageResumed = resumed;
                if (resumed) onResume(); else onPause();
            }
            if (Build.VERSION.SDK_INT >= 24) onVisibilityAggregated(isShown());
        }
    }

    private boolean isCastPage(WebView candidate) {
        return castCaptureActive && !destroyed && pageActive
                && candidate == webView && isPageVisible();
    }

    @Override public void dispatchWindowVisibilityChanged(int visibility) {
        // HTML fullscreen's custom View is a sibling of the WebView and is also
        // drawn to the receiver. Keep its window lifecycle alive as well.
        super.dispatchWindowVisibilityChanged(isCastPage(webView) ? View.VISIBLE : visibility);
    }

    private void updatePageLifecycle() {
        if (webView == null || destroyed) return;
        boolean resumed = pageActive && isPageVisible() && (hostResumed || castCaptureActive);
        ((CastWebView) webView).updatePageLifecycle(resumed);
        // Restore real window visibility when casting ends, even under HOME or
        // the management Activity. Never pause/resume timers for all WebViews.
        dispatchWindowVisibilityChanged(getWindowVisibility());
    }

    private void configureWebViewSettings(WebSettings settings) {
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setGeolocationEnabled(false);
        if (Build.VERSION.SDK_INT >= 17) {
            // Let the website choose whether to start. Do not force play()/unmute
            // through JS; requiring a gesture makes some players fall back to
            // muted autoplay even when their own volume preference is audible.
            settings.setMediaPlaybackRequiresUserGesture(false);
        }
        settings.setLoadsImagesAutomatically(loadImages);
        settings.setBlockNetworkImage(!loadImages);
        settings.setUserAgentString(activeUserAgent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        CookieManager.getInstance().setAcceptCookie(true);
    }

    private void replaceWebViewForNewPage() {
        destroyCurrentWebView();
        webView = createWebView(getContext());
        addView(webView, 0, new LayoutParams(viewportWidth, viewportHeight));
        updateDesktopViewport(getWidth(), getHeight());
    }

    private void destroyCurrentWebView() {
        if (renderDiagnostics != null) renderDiagnostics.stop("destroyed");
        hideFullscreenView();
        WebView current = webView;
        webView = null;
        desktopProfile = null;
        if (current == null) {
            return;
        }
        current.stopLoading();
        current.setWebChromeClient(null);
        current.onPause();
        removeView(current);
        current.removeAllViews();
        current.destroy();
    }

    /*
     * A stopped WebView can still deliver resource callbacks that were queued by the
     * previous document. Reusing that instance lets an old channel's M3U8 be reported
     * with the new request id. A fresh instance gives every web channel an isolated
     * request pipeline while retaining the shared WebView cookie/cache stores.
     */
    private void prepareFreshPage() {
        pageActive = false;
        requestId = -1;
        resetGeneration++;
        replaceWebViewForNewPage();
    }

    private LinearLayout createLoadingOverlay(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);

        loadingProgress = new LoadingSpinnerView(context);
        card.addView(loadingProgress);

        loadingText = new TextView(context);
        loadingText.setText("网页加载中，请稍候…");
        loadingText.setTextColor(0xffffffff);
        card.addView(loadingText);
        applyLoadingOverlayMetrics(card, 1f);
        card.setVisibility(View.GONE);
        return card;
    }

    private void applyLoadingOverlayMetrics(LinearLayout card, float scale) {
        int horizontalPadding = scaledDp(20f, scale);
        int verticalPadding = scaledDp(14f, scale);
        card.setPadding(horizontalPadding, verticalPadding,
                horizontalPadding, verticalPadding);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xe6222228);
        background.setCornerRadius(scaledDp(16f, scale));
        card.setBackgroundDrawable(background);

        int progressSize = scaledDp(28f, scale);
        loadingProgress.setLayoutParams(new LinearLayout.LayoutParams(
                progressSize, progressSize));
        loadingText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * scale);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        textParams.leftMargin = scaledDp(12f, scale);
        loadingText.setLayoutParams(textParams);
        card.requestLayout();
        card.invalidate();
    }

    private int scaledDp(float value, float scale) {
        return Math.max(1, Math.round(value * scale
                * getResources().getDisplayMetrics().density));
    }

    private void setLoadingVisible(boolean visible) {
        loadingOverlay.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            loadingOverlay.bringToFront();
        }
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
        // a measured size. The configured resolution controls the virtual width; the
        // virtual height follows the real WebView area so pages fill every aspect ratio.
        if ("4k".equals(viewportMode)) {
            viewportWidth = VIEWPORT_4K_WIDTH;
        } else if ("2k".equals(viewportMode)) {
            viewportWidth = VIEWPORT_2K_WIDTH;
        } else if ("1080p".equals(viewportMode)) {
            viewportWidth = VIEWPORT_1080P_WIDTH;
        } else {
            viewportWidth = VIEWPORT_720P_WIDTH;
        }
        if (width > 0 && height > 0) {
            viewportHeight = Math.max(1,
                    Math.round(viewportWidth * (height / (float) width)));
        } else if (viewportWidth == VIEWPORT_4K_WIDTH) {
            viewportHeight = VIEWPORT_4K_HEIGHT;
        } else if (viewportWidth == VIEWPORT_2K_WIDTH) {
            viewportHeight = VIEWPORT_2K_HEIGHT;
        } else if (viewportWidth == VIEWPORT_1080P_WIDTH) {
            viewportHeight = VIEWPORT_1080P_HEIGHT;
        } else {
            viewportHeight = VIEWPORT_720P_HEIGHT;
        }
        updateDesktopProfile();
        if (webView == null) {
            return;
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
        // Rounding the virtual height can leave a sub-pixel difference. Independent
        // final scales remove that difference without changing the effective ratio.
        webView.setScaleX(width / (float) viewportWidth);
        webView.setScaleY(height / (float) viewportHeight);
        webView.setTranslationX(0f);
        webView.setTranslationY(0f);
        if (pageActive) {
            if (renderDiagnostics == null) renderDiagnostics = new WebRenderDiagnostics(getContext());
            renderDiagnostics.update(webView, castCaptureActive, castCaptureFrameRate,
                    viewportWidth, viewportHeight);
        }
    }

    private void applyDesktopViewport() {
        if (webView == null) {
            return;
        }
        if (desktopProfile != null) desktopProfile.applyToCurrentDocument();
        String initialScale = String.format(Locale.US, "%.4f", cssInitialScale());
        // Desktop page zoom changes the CSS layout viewport, not just the visual
        // viewport. Keeping width fixed here crops a full-size layout at 200%.
        int layoutWidth = Math.max(1, Math.round(viewportWidth / effectivePageScale()));
        String content = "width=" + layoutWidth
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
        final WebView expectedView = webView;
        postDelayed(new Runnable() {
            @Override
            public void run() {
                if (expectedView == webView && pageActive && requestId == expectedRequestId && !destroyed) {
                    applyDesktopViewport();
                }
            }
        }, delayMillis);
    }

    private float cssInitialScale() {
        float density = getResources().getDisplayMetrics().density;
        float baseScale = density > 0f ? 1f / density : 1f;
        return baseScale * effectivePageScale();
    }

    private float effectivePageScale() {
        return currentPageScale;
    }

    private int cssInitialScalePercent() {
        // Unlike meta viewport initial-scale, WebView.setInitialScale is expressed
        // in physical pixels and must NOT divide by the phone's density again.
        return Math.max(1, Math.round(effectivePageScale() * 100f));
    }

    void applyConfiguration(String requestedViewportMode, boolean requestedLoadImages,
            String requestedUserAgentMode, float requestedPageScale) {
        String nextMode = "4k".equals(requestedViewportMode)
                || "2k".equals(requestedViewportMode)
                || "1080p".equals(requestedViewportMode)
                ? requestedViewportMode : "720p";
        String nextUserAgentMode = sanitizeUserAgentMode(requestedUserAgentMode);
        String nextUserAgent = userAgentForMode(nextUserAgentMode);
        float nextPageScale = Math.max(0.5f, Math.min(3f, requestedPageScale));
        boolean reloadPage = loadImages != requestedLoadImages
                || !nextUserAgentMode.equals(userAgentMode);
        boolean changed = !nextMode.equals(viewportMode) || loadImages != requestedLoadImages
                || !nextUserAgentMode.equals(userAgentMode)
                || Math.abs(pageScale - nextPageScale) > 0.001f;
        viewportMode = nextMode;
        pageScale = nextPageScale;
        currentPageScale = nextPageScale;
        loadImages = requestedLoadImages;
        userAgentMode = nextUserAgentMode;
        activeUserAgent = nextUserAgent;
        if (webView != null) {
            WebSettings settings = webView.getSettings();
            settings.setLoadsImagesAutomatically(loadImages);
            settings.setBlockNetworkImage(!loadImages);
            settings.setUserAgentString(activeUserAgent);
            webView.setInitialScale(cssInitialScalePercent());
        }
        updateDesktopViewport(getWidth(), getHeight());
        if (changed && pageActive && webView != null) {
            if (!reloadPage) {
                // Browser zoom/window resizing preserves the document, forms and
                // player state. Only loading-policy/UA changes require a reload.
                applyDesktopViewport();
                scheduleDesktopViewport(250L);
                return;
            }
            final int expectedRequestId = requestId;
            final WebView expectedView = webView;
            webView.post(new Runnable() {
                @Override
                public void run() {
                    if (expectedView == webView && pageActive && requestId == expectedRequestId && !destroyed) {
                        setLoadingVisible(true);
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

    void setCastEdgeState(boolean available, boolean casting,
            boolean busy, String contentName) {
        boolean becameAvailable = available && !castEdgeAvailable;
        castEdgeAvailable = available;
        castEdgeCasting = casting;
        castEdgeBusy = busy;
        castEdgeContentName = contentName == null ? "" : contentName;
        if (becameAvailable && !castCaptureActive) showCastEdgeTemporarily();
        applyCastEdgeState();
    }

    void setCastVisualScale(float scale) {
        castEdgeController.setCastVisualScale(scale);
    }

    void setCastPointerVisible(boolean visible) {
        if (castPointerVisible == visible) return;
        castPointerVisible = visible;
        applyCastEdgeState();
    }

    boolean isCastEdgeTouch(MotionEvent event) {
        if (event == null || castEdgeController.getVisibility() != View.VISIBLE) return false;
        Rect bounds = new Rect();
        return castEdgeController.getGlobalVisibleRect(bounds)
                && bounds.contains(Math.round(event.getRawX()), Math.round(event.getRawY()));
    }

    /** Give the native edge control sole ownership of a pointer sequence. */
    boolean dispatchCastEdgeTouch(MotionEvent event) {
        if (event == null || castEdgeController.getVisibility() != View.VISIBLE) return false;
        Rect bounds = new Rect();
        if (!castEdgeController.getGlobalVisibleRect(bounds) || bounds.isEmpty()) return false;
        MotionEvent local = MotionEvent.obtain(event);
        float x = (event.getRawX() - bounds.left) * castEdgeController.getWidth()
                / Math.max(1f, bounds.width());
        float y = (event.getRawY() - bounds.top) * castEdgeController.getHeight()
                / Math.max(1f, bounds.height());
        local.setLocation(x, y);
        boolean handled = castEdgeController.dispatchTouchEvent(local);
        local.recycle();
        return handled;
    }

    private void showCastEdgeTemporarily() {
        castEdgeTransientVisible = true;
        removeCallbacks(hideTransientCastEdge);
        postDelayed(hideTransientCastEdge, 5000L);
        applyCastEdgeState();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event != null && event.getActionMasked() == MotionEvent.ACTION_DOWN
                && pageActive && !castCaptureActive) {
            showCastEdgeTemporarily();
        }
        return super.dispatchTouchEvent(event);
    }

    private void applyCastEdgeState() {
        boolean visible = pageActive && castEdgeAvailable
                && (castCaptureActive ? castPointerVisible : castEdgeTransientVisible);
        castEdgeController.setState(visible,
                castEdgeCasting, castEdgeBusy, castEdgeContentName);
        if (visible) castEdgeController.bringToFront();
    }

    Object localPointerIdentity() {
        return castCaptureActive && hasRetainedPage() && isPageVisible() ? webView : null;
    }

    void open(int newRequestId, String url) {
        rendererRetries = 0;
        openPage(newRequestId, url);
    }

    private void openPage(int newRequestId, String url) {
        openPage(newRequestId, url, false);
    }

    void navigateCastPage(int newRequestId, String url) {
        rendererRetries = 0;
        openPage(newRequestId, url, true);
    }

    private void openPage(int newRequestId, String url, boolean reuseCastPage) {
        if (destroyed) return;
        // Trackpad pinch zoom belongs to one opened channel only. Every channel
        // entry starts from the configured browser scale again.
        currentPageScale = pageScale;
        if (reuseCastPage && castCaptureActive && hasRetainedPage()) {
            pageActive = false;
            requestId = -1;
            resetGeneration++;
            hideFullscreenView();
            webView.stopLoading();
            webView.clearHistory();
            // Retire callbacks queued by the previous document without tearing
            // down Chromium or the surface used by the ongoing cast session.
            sourceClient = new SourceClient();
            WebViewRecovery.attach(webView, sourceClient, this::onRendererGone);
        } else prepareFreshPage();
        requestId = newRequestId;
        pageUrl = url;
        discoveredStreamUrls.clear();
        pageActive = true;
        clearInitialHistory = true;
        compatibilityInjectionCount = 0;
        resetGeneration++;
        setVisibility(View.VISIBLE);
        bringToFront();
        if (!castCaptureActive) showCastEdgeTemporarily();
        applyCastEdgeState();
        setLoadingVisible(true);
        updatePageLifecycle();
        updateDesktopViewport(getWidth(), getHeight());
        webView.setInitialScale(cssInitialScalePercent());
        webView.loadUrl(url);
    }

    void adjustCurrentPageScale(float factor) {
        if (webView == null || !pageActive || destroyed
                || Float.isNaN(factor) || Float.isInfinite(factor)) return;
        float next = Math.max(0.5f, Math.min(3f, currentPageScale * factor));
        next = Math.round(next * 100f) / 100f;
        if (Math.abs(next - currentPageScale) < 0.01f) return;
        currentPageScale = next;
        webView.setInitialScale(cssInitialScalePercent());
        updateDesktopViewport(getWidth(), getHeight());
        applyDesktopViewport();
        scheduleDesktopViewport(120L);
    }

    private void onRendererGone(WebView failed, boolean crashed) {
        if (failed != webView) return;
        if (renderDiagnostics != null) renderDiagnostics.stop("rendererGone crashed=" + crashed);
        final int failedRequest = requestId;
        final String failedUrl = pageUrl;
        final boolean visible = pageActive && isPageVisible();
        webView = null;
        desktopProfile = null;
        pageActive = false;
        removeCallbacks(hideTransientCastEdge);
        castEdgeTransientVisible = false;
        applyCastEdgeState();
        requestId = -1;
        clearInitialHistory = false;
        discoveredStreamUrls.clear();
        final int generation = ++resetGeneration;
        // The fullscreen callback is owned by the dead renderer; don't invoke it.
        if (fullscreenView != null) removeView(fullscreenView);
        fullscreenView = null;
        fullscreenCallback = null;
        setLoadingVisible(false);
        if (!visible || destroyed) return;
        boolean retry = rendererRetries++ < 1 && isWebPage(failedUrl);
        if (listener != null) listener.onPageError(failedRequest, retry
                ? "网页渲染进程已退出，正在尝试恢复"
                : "网页连续崩溃，请切换频道或稍后重新打开");
        if (!retry) return;
        // Let all sibling WebViews acknowledge the old renderer before recreating.
        postDelayed(new Runnable() {
            @Override public void run() {
                if (destroyed || generation != resetGeneration || webView != null) return;
                if (!castCaptureActive && getWindowVisibility() != View.VISIBLE) return;
                openPage(failedRequest, failedUrl);
            }
        }, 750L);
    }

    void closePage() {
        if (renderDiagnostics != null) renderDiagnostics.stop("closed");
        hideFullscreenView();
        if (destroyed) {
            return;
        }
        if (!pageActive && requestId < 0 && getVisibility() != View.VISIBLE) {
            if (webView == null) {
                return;
            }
            webView.clearHistory();
            return;
        }
        final int generation = ++resetGeneration;
        pageActive = false;
        clearInitialHistory = false;
        requestId = -1;
        pageUrl = null;
        discoveredStreamUrls.clear();
        if (webView == null) {
            setLoadingVisible(false);
            setVisibility(View.GONE);
            return;
        }
        webView.stopLoading();
        setLoadingVisible(false);
        webView.clearHistory();
        webView.loadUrl("about:blank");
        webView.clearHistory();
        setVisibility(View.GONE);
        updatePageLifecycle();
        // Loading about:blank can itself create a history entry on some old WebView
        // implementations, so clear once more after the navigation has settled.
        post(new Runnable() {
            @Override
            public void run() {
                if (!destroyed && webView != null && !pageActive && generation == resetGeneration) {
                    webView.clearHistory();
                }
            }
        });
    }

    /** Keep WebView pixels in the View hierarchy while it is drawn to a cast Surface. */
    void setCastCaptureActive(boolean active, int frameRate) {
        castCaptureActive = active;
        castCaptureFrameRate = active ? Math.max(1, frameRate) : 0;
        if (!active) castPointerVisible = true;
        applyCastCaptureLayer(webView);
        applyCastFrameRate(webView);
        applyCastEdgeState();
        updateDesktopViewport(getWidth(), getHeight());
        if (webView != null) {
            webView.setInitialScale(cssInitialScalePercent());
            updatePageLifecycle();
        }
        if (pageActive) {
            scheduleDesktopViewport(0L);
        }
        invalidate();
    }

    private void applyCastCaptureLayer(WebView target) {
        if (target == null) {
            return;
        }
        target.setLayerType(castCaptureActive
                ? Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_SOFTWARE
                : View.LAYER_TYPE_NONE, null);
        target.invalidate();
    }

    /** Match Chromium's View production rate to the configured cast clock on
     * recent Android releases. The encoder submission loop remains the hard cap
     * on every supported version. */
    @SuppressLint("NewApi")
    private void applyCastFrameRate(WebView target) {
        if (Build.VERSION.SDK_INT < 35 || target == null) return;
        float requested = castCaptureActive ? castCaptureFrameRate : 0f;
        try {
            if (Build.VERSION.SDK_INT >= 36) {
                android.view.ViewGroup.class.getMethod("propagateRequestedFrameRate",
                        Float.TYPE, Boolean.TYPE).invoke(this, requested, true);
            } else {
                View.class.getMethod("setRequestedFrameRate", Float.TYPE)
                        .invoke(target, requested);
            }
        } catch (Exception error) {
            Log.d(TAG, "Unable to apply cast frame-rate hint", error);
        }
    }

    boolean isPageVisible() {
        return getVisibility() == View.VISIBLE;
    }

    boolean hasRetainedPage() {
        return !destroyed && webView != null && pageActive && requestId >= 0;
    }

    boolean hasRetainedPage(int expectedRequestId) {
        return hasRetainedPage() && requestId == expectedRequestId;
    }

    void hideForStreamPlayback() {
        if (!hasRetainedPage()) {
            return;
        }
        hideFullscreenView();
        setLoadingVisible(false);
        setVisibility(View.GONE);
        updatePageLifecycle();
    }

    boolean restoreAfterStreamPlayback() {
        if (!hasRetainedPage()) {
            return false;
        }
        setVisibility(View.VISIBLE);
        bringToFront();
        updatePageLifecycle();
        webView.requestFocus();
        updateDesktopViewport(getWidth(), getHeight());
        return true;
    }

    private String userAgentForMode(String mode) {
        if (DesktopWebProfile.isDesktop(mode)) {
            return DesktopWebProfile.userAgent(mode, browserUserAgent);
        }
        if ("ipad".equals(mode)) {
            return IPAD_USER_AGENT;
        }
        if ("native".equals(mode)) {
            return browserUserAgent;
        }
        return WINDOWS_USER_AGENT;
    }

    private void updateDesktopProfile() {
        if (desktopProfile != null) {
            desktopProfile.update(userAgentMode, browserUserAgent,
                    viewportWidth, viewportHeight, effectivePageScale());
        }
    }

    private static String sanitizeUserAgentMode(String mode) {
        if ("macos".equals(mode) || "ipad".equals(mode) || "native".equals(mode)) {
            return mode;
        }
        return "windows";
    }

    boolean goBackIfPossible() {
        if (webView == null || !isPageVisible() || destroyed || !pageActive) {
            return false;
        }
        if (fullscreenView != null) {
            hideFullscreenView();
            return true;
        }
        WebBackForwardList history = webView.copyBackForwardList();
        int currentIndex = history == null ? -1 : history.getCurrentIndex();
        int targetIndex = previousWebPageIndex(history, currentIndex);
        if (targetIndex < 0) {
            return false;
        }
        setLoadingVisible(true);
        webView.goBackOrForward(targetIndex - currentIndex);
        return true;
    }

    private static int previousWebPageIndex(WebBackForwardList history, int currentIndex) {
        if (history == null || currentIndex <= 0) {
            return -1;
        }
        for (int index = currentIndex - 1; index >= 0; index--) {
            WebHistoryItem item = history.getItemAtIndex(index);
            if (item != null && isWebPage(item.getUrl())) {
                return index;
            }
        }
        return -1;
    }

    private void hideFullscreenView() {
        if (fullscreenView == null) return;
        View previous = fullscreenView;
        WebChromeClient.CustomViewCallback callback = fullscreenCallback;
        fullscreenView = null;
        fullscreenCallback = null;
        removeView(previous);
        if (webView != null) {
            webView.setVisibility(View.VISIBLE);
            webView.requestFocus();
        }
        if (callback != null) callback.onCustomViewHidden();
    }

    void setInterfaceScale(float scale) {
        float safeScale = Math.max(0.9f, Math.min(2f, scale));
        // Resize each native element instead of scaling a pre-rendered card. Scaling the
        // whole hierarchy makes text and the spinner visibly soft on 4K televisions.
        loadingOverlay.setScaleX(1f);
        loadingOverlay.setScaleY(1f);
        applyLoadingOverlayMetrics(loadingOverlay, safeScale);
    }

    void dispatchRemoteKey(int keyCode, int metaState) {
        if (webView == null || !isPageVisible()) {
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
        if (webView == null || !isPageVisible() || text == null || text.length() == 0) {
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
        if (webView != null) {
            webView.clearCache(true);
            webView.clearHistory();
            webView.clearFormData();
        }
        android.webkit.WebStorage.getInstance().deleteAllData();
        cachedBrowserCacheBytes = 0L;
        browserCacheMeasuredAt = android.os.SystemClock.elapsedRealtime();
    }

    synchronized long browserCacheSizeBytes() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (browserCacheMeasuredAt > 0L && now - browserCacheMeasuredAt < 30000L) {
            return cachedBrowserCacheBytes;
        }
        long bytes = measureFiles(getContext().getCacheDir(), true);
        File webViewData = getContext().getDir("webview", Context.MODE_PRIVATE);
        cachedBrowserCacheBytes = bytes + measureFiles(webViewData, false);
        browserCacheMeasuredAt = now;
        return cachedBrowserCacheBytes;
    }

    boolean cancelRemoteMouseButton(MotionEvent release) {
        if (webView == null || !pageActive) return false;
        // Chromium ignores ACTION_CANCEL for mouse input. Release outside the
        // document so JS drag handlers receive mouseup without clicking the
        // previously pressed control. Direct dispatch keeps the old mouse target
        // even when the cursor has left its bounds (ViewGroup would hit-test it away).
        MotionEvent outside = MotionEvent.obtain(release);
        outside.setLocation(-1f, -1f);
        try { webView.dispatchGenericMotionEvent(outside); }
        finally { outside.recycle(); }
        return true;
    }

    String browserUserAgent() {
        return browserUserAgent == null ? activeUserAgent : browserUserAgent;
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
        hostResumed = true;
        updatePageLifecycle();
    }

    String currentPageUrl() {
        if (!hasRetainedPage()) return "";
        String current = webView == null ? null : webView.getUrl();
        if (current == null || !(current.startsWith("https://") || current.startsWith("http://"))) current = pageUrl;
        return current != null && (current.startsWith("https://") || current.startsWith("http://")) ? current : "";
    }

    void pausePage() {
        // Remember this even without a page: background channel changes must
        // inherit the session lifecycle, not unconditionally resume a new page.
        hostResumed = false;
        updatePageLifecycle();
    }

    void destroyPage() {
        destroyed = true;
        pageActive = false;
        clearInitialHistory = false;
        requestId = -1;
        resetGeneration++;
        setLoadingVisible(false);
        destroyCurrentWebView();
    }

    private void observeResource(final WebView origin, final SourceClient client, String url) {
        if (origin != webView || client != sourceClient || !pageActive || requestId < 0) {
            return;
        }
        final String streamUrl = normalizeMediaPlaylist(url);
        if (streamUrl == null) {
            return;
        }
        final int observedRequestId = requestId;
        final String observedUrl = streamUrl;
        post(new Runnable() {
            @Override
            public void run() {
                if (destroyed || !pageActive || origin != webView || client != sourceClient
                        || observedRequestId != requestId || listener == null
                        || discoveredStreamUrls.contains(observedUrl)) {
                    return;
                }
                discoveredStreamUrls.add(observedUrl);
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
        // Match complete media files, not TS/M4S segments or URLs embedded in page queries.
        int pathEnd = lower.indexOf('?');
        int fragment = lower.indexOf('#');
        if (pathEnd < 0 || (fragment >= 0 && fragment < pathEnd)) pathEnd = fragment;
        String path = pathEnd < 0 ? lower : lower.substring(0, pathEnd);
        if (path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".mkv")
                || path.endsWith(".mov") || path.endsWith(".flv")
                || path.endsWith(".mp3") || path.endsWith(".m4a") || path.endsWith(".flac")
                || path.endsWith(".wav") || path.endsWith(".ogg") || path.endsWith(".oga")
                || path.endsWith(".opus")) return url;
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
            if (this != sourceClient || view != webView || !pageActive || requestId < 0 || url == null) {
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
            if (this != sourceClient || view != webView || !pageActive || requestId < 0 || isBlankPage(url)) {
                return;
            }
            // Reused WebViews can deliver queued events from the document that
            // loadUrl just replaced, including to the newly assigned client.
            if (!url.equals(view.getUrl())) return;
            pageUrl = url;
            compatibilityInjectionCount = 0;
            setLoadingVisible(true);
            updateDesktopViewport(getWidth(), getHeight());
            if (desktopProfile != null && !desktopProfile.hasDocumentStartProtection()) {
                desktopProfile.applyToCurrentDocument();
            }
            view.setInitialScale(cssInitialScalePercent());
            if (listener != null) {
                listener.onPageStarted(requestId, url);
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (this != sourceClient || view != webView || !pageActive || requestId < 0 || isBlankPage(url)) {
                return;
            }
            if (!url.equals(view.getUrl())) return;
            pageUrl = url;
            if (clearInitialHistory) {
                // clearHistory() before loadUrl() cannot remove the current about:blank
                // item on several vendor WebViews. Clear once the first real document
                // becomes current so Back never exposes that blank bootstrap page.
                clearInitialHistory = false;
                view.clearHistory();
            }
            applyDesktopViewport();
            scheduleDesktopViewport(250L);
            scheduleDesktopViewport(1000L);
            setLoadingVisible(false);
            if (listener != null) {
                listener.onPageReady(requestId, url, view.getTitle());
            }
        }

        @Override
        public void onLoadResource(WebView view, String url) {
            if (this != sourceClient || view != webView || !pageActive) return;
            if (compatibilityInjectionCount < 3 && isJavascriptResource(url)) {
                // onPageStarted can race with creation of the new document on old
                // Chromium. Repeat immediately before the first scripts are executed.
                injectJavascriptCompatibility(view);
            }
            observeResource(view, this, url);
            super.onLoadResource(view, url);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            if (this != sourceClient || view != webView || !pageActive) return null;
            observeResource(view, this, url);
            WebResourceResponse compatible = interceptCompatibilityBundle(url);
            if (compatible != null) {
                return compatible;
            }
            return super.shouldInterceptRequest(view, url);
        }

        @Override
        public void onReceivedError(WebView view, int errorCode, String description,
                String failingUrl) {
            if (this != sourceClient || view != webView || !pageActive || requestId < 0 || isBlankPage(failingUrl)) {
                return;
            }
            Log.w(TAG, "Web source failed code=" + errorCode + " url=" + failingUrl
                    + " description=" + description);
            setLoadingVisible(false);
            if (listener != null) {
                listener.onPageError(requestId,
                        description == null ? "网页加载失败" : description);
            }
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            if (this == sourceClient && view == webView && pageActive && requestId >= 0 && !isBlankPage(url)) {
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
        HttpURLConnection connection = NetworkClient.open(new URL(resourceUrl));
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", activeUserAgent);
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

    private static boolean isWebPage(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
