package xiao.bu.tv;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebBackForwardList;
import android.webkit.WebHistoryItem;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

public final class WebSourceView extends FrameLayout {
    private static final int VIEWPORT_4K_WIDTH = 3840;
    private static final int VIEWPORT_4K_HEIGHT = 2160;
    private static final int VIEWPORT_2K_WIDTH = 2560;
    private static final int VIEWPORT_2K_HEIGHT = 1440;
    private static final int VIEWPORT_1080P_WIDTH = 1920;
    private static final int VIEWPORT_1080P_HEIGHT = 1080;
    private static final int VIEWPORT_720P_WIDTH = 1280;
    private static final int VIEWPORT_720P_HEIGHT = 720;
    private static final long MULTI_WEBVIEW_MIN_AVAILABLE_BYTES = 200L * 1024L * 1024L;
    private static final String WINDOWS_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    interface Listener {
        void onPageStarted(int requestId, String url);
        default void onResourcesReset(int requestId, String url) { }
        void onPageReady(int requestId, String url, String title);
        void onPageError(int requestId, String message);
        default void onPageUnavailable(int requestId, String message) { onPageError(requestId, message); }
        default void onBrowserRelativeChannel(int direction) { }
        void onStreamDiscovered(int requestId, String streamUrl, String pageUrl,
                String userAgent, String cookies);

        void onBrowserHome();
        void onBrowserChannel(int groupIndex, int channelIndex);
        void onBrowserDownloadImage(String url);
        void onBrowserClipboard(String text, String message);
        void onBrowserUserScript(String url);
        default void onBrowserOverlayShown() { }
        void onBrowserPoliciesChanged(boolean images, boolean adBlock, boolean webRtc);
    }

    private static final String TAG = "WebSourceView";
    private static final java.util.regex.Pattern HLS_QUERY = java.util.regex.Pattern.compile(
            ".*[?&](format|type)=m3u8(?:[&#].*)?$");
    private volatile WebView webView;
    private final LegacyWebSession legacy;
    private final LinkedHashSet<WebViewShutdown> pendingShutdowns = new LinkedHashSet<>();
    private Runnable stablePageRelease;
    private JSONObject webMediaState = new JSONObject();
    private long webMediaSample, webMediaSampleAt;
    private boolean webMediaPending;
    private volatile SourceClient sourceClient;
    private DesktopWebProfile desktopProfile;
    private WebPageScriptManager pageScriptManager;
    private WebRenderDiagnostics renderDiagnostics;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private final LinearLayout loadingOverlay;

    private final WebTabBar tabBar;
    private final BrowserIconView fullscreenExitButton;
    private FrameLayout smartContextLayer;
    private int tabBarHeight;
    private boolean browserFullscreen;
    private final Runnable hideFullscreenExit = new Runnable() {
        @Override public void run() {
            if (browserFullscreen) fullscreenExitButton.setVisibility(View.GONE);
        }
    };
    private final Runnable settleRemoteScroll = new Runnable() {
        @Override public void run() {
            clampWebScrollToContent();
        }
    };
    private WebTabBar.Tab loadedTab;
    private final LinkedHashMap<WebTabBar.Tab, WebView> retainedTabWebViews =
            new LinkedHashMap<WebTabBar.Tab, WebView>();
    private String browserUserAgent;
    private LoadingSpinnerView loadingProgress;
    private TextView loadingText;
    private Listener listener;
    private volatile int requestId = -1;
    private volatile String pageUrl;
    private volatile long resourceNavigation;
    private String resourcePageKey = "";
    private String controllerPageTitle = "";
    private boolean streamPageSuspended;
    private final java.util.Set<String> discoveredStreamUrls =
            java.util.Collections.synchronizedSet(new LinkedHashSet<String>());
    // Both interception and onLoadResource report the same requests. Cache misses
    // too (images, scripts, segments); bounded and reset for each document.
    private final LinkedHashMap<String, String> resourceUrlCache =
            new LinkedHashMap<String, String>(128, .75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, String> item) {
                    return size() > 128;
                }
            };
    private volatile boolean pageActive;
    private boolean clearInitialHistory;
    private boolean destroyed;
    private int resetGeneration;
    private String viewportMode = "720p";
    private float pageScale = 1f;
    private float currentPageScale = 1f;
    private String userAgentMode = "windows";
    private String browserVersionMode = "native";
    private volatile String activeUserAgent = WINDOWS_USER_AGENT;
    private boolean loadImages = true;
    private boolean adBlockEnabled = true;
    private boolean webRtcEnabled;
    private boolean userScriptEnabled;
    private volatile boolean legacyTranspilationEnabled;
    private String userScripts = "[]";
    private int viewportWidth = VIEWPORT_720P_WIDTH;
    private int viewportHeight = VIEWPORT_720P_HEIGHT;
    private float interfaceScale = 1f;
    private float loadingInterfaceScale = 1f;
    private int compatibilityInjectionCount;
    private int profileInjectionCount;
    private int pagePolicyInjectionCount;
    private int pageLoadGeneration;
    private int pageLoadTerminalGeneration = -1;

    private boolean remoteMouseHoverDispatch;

    private boolean hostResumed;
    private int rendererRetries;
    private long cachedBrowserCacheBytes;
    private long browserCacheMeasuredAt;

    public WebSourceView(Context context) {
        this(context, null);
    }

    @SuppressLint("SetJavaScriptEnabled")
    public WebSourceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        legacy = Build.VERSION.SDK_INT < 26 && !(context instanceof LegacyWebActivity)
                ? new LegacyWebSession(this) : null;
        WebAdBlocker.initialize(context);
        setBackgroundColor(0xff000000);
        setClipChildren(true);
        tabBar = new WebTabBar(context, new WebTabBar.Callback() {
            @Override public void onReload() { reloadPage(); }
            @Override public void onNavigate(String value) { navigateAddress(value); }
            @Override public void onChannel(int groupIndex, int channelIndex) {
                if (listener != null) listener.onBrowserChannel(groupIndex, channelIndex);
            }
            @Override public void onOpenBookmark(String url, String title, String group, boolean newTab) {
                openBookmarkedPage(url, title, group, newTab);
            }
            @Override public void onSelect(WebTabBar.Tab tab) { loadBrowserTab(tab); }
            @Override public void onNewTab() { openCurrentInNewTab(); }
            @Override public void onTabClosed(WebTabBar.Tab tab) {
                releaseTabWebView(tab, false);
                if (tab == loadedTab) {
                    loadedTab = null;
                    pageActive = false;
                    // Keep the old surface alive until the replacement has drawn.
                    // On low-memory devices retain the single-renderer reuse path.
                    if (hasMemoryForAdditionalWebView()) destroyCurrentWebView(true);
                }
            }
            @Override public void onAllTabsClosed() {
                if (listener != null) listener.onBrowserHome();
            }
            @Override public void onTabAdBlockChanged(WebTabBar.Tab tab) {
                if (tab != tabBar.active()) return;
                releaseRetainedWebViews(true);
                updatePageScripts();
                reloadPage();
            }
            @Override public void onTabMuteChanged(WebTabBar.Tab tab) {
                if (tab == tabBar.active()) applyActiveTabMute();
            }
            @Override public void onTabSleepChanged(WebTabBar.Tab tab) {
                if (tab.sleeping) {
                    tab.state = null;
                    if (tab != loadedTab) releaseTabWebView(tab, false);
                }
            }
            @Override public void onToolbarHeightChanged(int heightDp) {
                updateToolbarHeight(heightDp);
            }
            @Override public void onImagesChanged(boolean enabled) {
                releaseRetainedWebViews(true);
                loadImages = enabled;
                if (webView != null) {
                    webView.getSettings().setLoadsImagesAutomatically(enabled);
                    webView.getSettings().setBlockNetworkImage(!enabled);
                }
                notifyBrowserPoliciesChanged();
                reloadPage();
            }
            @Override public void onGlobalAdBlockChanged(boolean enabled) {
                releaseRetainedWebViews(true);
                adBlockEnabled = enabled;
                if (enabled) WebAdBlocker.refreshAsync(false);
                updatePageScripts();
                notifyBrowserPoliciesChanged();
                reloadPage();
            }
            @Override public void onWebRtcChanged(boolean enabled) {
                releaseRetainedWebViews(true);
                webRtcEnabled = enabled;
                updatePageScripts();
                notifyBrowserPoliciesChanged();
                reloadPage();
            }
            @Override public void onEnterFullscreen() {
                setBrowserFullscreen(true);
            }
        });
        tabBarHeight = tabBar.heightPx();
        tabBar.setVisibility(View.GONE);
        addView(tabBar, new LayoutParams(LayoutParams.MATCH_PARENT, tabBarHeight, Gravity.TOP));
        fullscreenExitButton = new BrowserIconView(context, BrowserIconView.CLOSE);
        fullscreenExitButton.setIconColor(Color.WHITE);
        fullscreenExitButton.setContentDescription("退出网页全屏");
        GradientDrawable exitBackground = new GradientDrawable();
        exitBackground.setColor(0xd93f4247);
        exitBackground.setShape(GradientDrawable.OVAL);
        fullscreenExitButton.setBackgroundDrawable(exitBackground);
        fullscreenExitButton.setPadding(dp(6), dp(6), dp(6), dp(6));
        fullscreenExitButton.setOnClickListener(v -> setBrowserFullscreen(false));
        fullscreenExitButton.setVisibility(View.GONE);
        LayoutParams exitParams = new LayoutParams(dp(42), dp(42),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        exitParams.topMargin = dp(8);
        addView(fullscreenExitButton, exitParams);
        // Creating even a hidden WebView initializes the Chromium/WebKit runtime and
        // noticeably delays native-channel startup on Android 4.x televisions. Keep
        // the browser lazy and create it only when a web channel is actually opened.
        webView = null;
        final File obsoleteProxyCache = new File(context.getCacheDir(), "browser");
        if (obsoleteProxyCache.exists()) {
            new Thread(() -> deleteObsoleteProxyCache(obsoleteProxyCache),
                    "web-cache-migration").start();
        }
        loadingOverlay = createLoadingOverlay(context);
        addView(loadingOverlay, new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setVisibility(View.GONE);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createWebView(Context context) {
        WebView nextWebView = WebViewAvailability.create(() -> new BrowserWebView(context));
        nextWebView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings settings = nextWebView.getSettings();
        if (browserUserAgent == null) {
            browserUserAgent = settings.getUserAgentString();
        }
        activeUserAgent = userAgentForMode(userAgentMode, browserVersionMode);
        configureWebViewSettings(settings);
        nextWebView.setPivotX(0f);
        nextWebView.setPivotY(0f);
        nextWebView.setInitialScale(cssInitialScalePercent());

        ((BrowserWebView) nextWebView).browserChromeClient = new WebChromeClient() {
            @Override
            public void onReceivedTitle(WebView view, String title) {
                if (isActiveDocument(view)) {
                    tabBar.updateActive(view.getUrl(), title);
                    controllerPageTitle = safe(title);
                }
            }

            @Override
            public void onReceivedIcon(WebView view, Bitmap icon) {
                if (isActiveDocument(view) && icon != null) {
                    tabBar.updateActiveIcon(view.getUrl(), icon);
                }
            }

            @Override
            public void onProgressChanged(WebView view, int progress) {
                if (!isActiveDocument(view)) return;
                if (profileInjectionCount < 6 && desktopProfile != null && !desktopProfile.hasDocumentStartProtection()
                        && DesktopWebProfile.isSpoofed(userAgentMode) && progress > 0) {
                    profileInjectionCount++;
                    desktopProfile.applyToCurrentDocument();
                }
                if (progress >= 80) probePageReady(view, pageLoadGeneration, 0L);
                if (progress >= 100) completePageLoadLater(view, pageLoadGeneration, 120L,
                        "progress=100");
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (webView != nextWebView || !pageActive || fullscreenView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                fullscreenView = view;
                fullscreenCallback = callback;
                configureFullscreenMouseHover(view);
                setLoadingVisible(false);
                addView(view, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
                nextWebView.setVisibility(View.INVISIBLE);

                view.requestFocus();
                // Stay in the same hierarchy so fullscreen content and cursor hit-testing
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

            @Override
            public boolean onCreateWindow(final WebView view, boolean isDialog,
                    boolean isUserGesture, Message resultMsg) {
                if (view != webView || !pageActive || destroyed || !isUserGesture) return false;
                if (resultMsg == null
                        || !(resultMsg.obj instanceof WebView.WebViewTransport)) return false;

                // HitTestResult.getExtra() is the image source for an image link,
                // not necessarily the link target. Routing that value here used to
                // force image clicks into a new tab and bypass the page's JavaScript.
                // Let Chromium execute the site's click/window.open behavior first,
                // then route the URL that the popup actually navigates to.
                final WebView popup;
                try {
                    popup = WebViewAvailability.create(() -> new WebView(getContext()));
                } catch (WebViewAvailability.UnavailableException error) {
                    android.widget.Toast.makeText(getContext(), WebViewAvailability.MESSAGE,
                            android.widget.Toast.LENGTH_LONG).show();
                    return false;
                }
                WebSettings popupSettings = popup.getSettings();
                popupSettings.setJavaScriptEnabled(true);
                popupSettings.setDomStorageEnabled(true);
                final boolean[] popupRouted = { false };
                popup.setWebViewClient(new WebViewClient() {
                    private boolean route(String url) {
                        if (popupRouted[0]) return true;
                        popupRouted[0] = queuePopupNavigation(view, url, popup);
                        return popupRouted[0];
                    }

                    @Override public boolean shouldOverrideUrlLoading(WebView ignored, String url) {
                        return route(url);
                    }

                    @TargetApi(21)
                    @Override public boolean shouldOverrideUrlLoading(WebView ignored,
                            WebResourceRequest request) {
                        Uri uri = request == null ? null : request.getUrl();
                        return route(uri == null ? null : uri.toString());
                    }

                    @Override public void onPageStarted(WebView ignored, String url,
                            Bitmap favicon) {
                        route(url);
                    }
                });
                WebView.WebViewTransport transport =
                        (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                postDelayed(new Runnable() {
                    @Override public void run() {
                        if (popupRouted[0]) return;
                        popup.setWebViewClient(null);
                        popup.destroy();
                    }
                }, 30000L);
                return true;
            }
        };
        nextWebView.setWebChromeClient(((BrowserWebView) nextWebView).browserChromeClient);
        nextWebView.setDownloadListener((url, userAgent, contentDisposition, mimeType,
                contentLength) -> {
            if (isUserScriptInstallUrl(url) && listener != null) {
                listener.onBrowserUserScript(url);
            }
        });
        sourceClient = new SourceClient();
        WebViewRecovery.attach(nextWebView, sourceClient, this::onRendererGone);
        desktopProfile = ((BrowserWebView) nextWebView).desktopPolicy;
        pageScriptManager = ((BrowserWebView) nextWebView).pagePolicy;
        updateDesktopProfile();
        updatePageScripts();
        return nextWebView;
    }

    /** WebView with native mouse handling and per-tab page policies. */
    private final class BrowserWebView extends WebView {
        final DesktopWebProfile desktopPolicy = new DesktopWebProfile(this);
        final WebPageScriptManager pagePolicy = new WebPageScriptManager(this);
        private Boolean pageResumed;
        private WebViewShutdown shutdown;
        private boolean retiring;
        private WebChromeClient browserChromeClient;
        private boolean systemSecondaryDown;
        private boolean systemPrimaryDown;
        private long systemContextShownAt;
        BrowserWebView(Context context) { super(context); }

        @Override public boolean onHoverEvent(MotionEvent event) {
            if (retiring) return false;
            int action = event.getActionMasked();
            if ((remoteMouseHoverDispatch || isSystemMouse(event)) && Build.VERSION.SDK_INT >= 19
                    && event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
                    && (action == MotionEvent.ACTION_HOVER_MOVE
                        || action == MotionEvent.ACTION_HOVER_EXIT)) {
                // Chromium can consume HOVER_MOVE in its accessibility hit tester
                // before Blink sees it, even without touch exploration enabled.
                // A button-free native mouse MOVE reaches the document normally.
                // Keep ViewGroup's transformed coordinates and native iframe hit testing;
                // real accessibility hover events never enter this scoped path.
                MotionEvent mouseMove = MotionEvent.obtain(event);
                mouseMove.setAction(MotionEvent.ACTION_MOVE);
                if (action == MotionEvent.ACTION_HOVER_EXIT) mouseMove.setLocation(-1f, -1f);
                try { return super.onHoverEvent(mouseMove); }
                finally { mouseMove.recycle(); }
            }
            return super.onHoverEvent(event);
        }

        @Override public boolean onGenericMotionEvent(MotionEvent event) {
            if (retiring) return false;
            if (Build.VERSION.SDK_INT >= 23 && isSystemMouse(event)
                    && (event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS
                        || event.getActionMasked() == MotionEvent.ACTION_BUTTON_RELEASE)
                    && event.getActionButton() == MotionEvent.BUTTON_SECONDARY) {
                if (event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS
                        && android.os.SystemClock.uptimeMillis() - systemContextShownAt > 250L) {
                    systemContextShownAt = android.os.SystemClock.uptimeMillis();
                    showSystemMouseContext(event.getRawX(), event.getRawY());
                }
                return true;
            }
            return super.onGenericMotionEvent(event);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (retiring) return false;
            if (isSystemMouse(event)) {
                int action = event.getActionMasked();
                boolean secondary = (event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0;
                if (action == MotionEvent.ACTION_DOWN && secondary) {
                    systemSecondaryDown = true;
                    systemContextShownAt = android.os.SystemClock.uptimeMillis();
                    showSystemMouseContext(event.getRawX(), event.getRawY());
                    return true;
                }
                if (systemSecondaryDown) {
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                        systemSecondaryDown = false;
                    }
                    return true;
                }
                if (action == MotionEvent.ACTION_DOWN) {
                    systemPrimaryDown = true;
                    runSystemMouseSelection("start", event);
                } else if (action == MotionEvent.ACTION_MOVE && systemPrimaryDown) {
                    runSystemMouseSelection("move", event);
                } else if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                        && systemPrimaryDown) {
                    runSystemMouseSelection("end", event);
                    systemPrimaryDown = false;
                }
            }
            // Primary mouse input stays entirely native. In particular, WebView
            // owns down/move/up so dragging can select text and page widgets.
            return super.onTouchEvent(event);
        }

        @Override protected boolean overScrollBy(int deltaX, int deltaY,
                int scrollX, int scrollY, int scrollRangeX, int scrollRangeY,
                int maxOverScrollX, int maxOverScrollY, boolean isTouchEvent) {
            // The scaled desktop viewport must stop at Chromium's content edge.
            // Some vendor WebViews otherwise reveal an empty strip after a wheel fling.
            return super.overScrollBy(deltaX, deltaY, scrollX, scrollY,
                    scrollRangeX, scrollRangeY, 0, 0, isTouchEvent);
        }

        void updatePageLifecycle(boolean resumed) {
            if (pageResumed == null || pageResumed != resumed) {
                pageResumed = resumed;
                if (resumed) onResume(); else onPause();
            }
            if (Build.VERSION.SDK_INT >= 24) onVisibilityAggregated(isShown());
        }
    }

    boolean dispatchRemoteMouseHover(View dispatchRoot, MotionEvent event) {
        if (legacy != null) { android.os.Bundle data = new android.os.Bundle(); data.putParcelable("motion", event); legacy.send("mouse", data); return legacy.visible; }
        boolean previous = remoteMouseHoverDispatch;
        remoteMouseHoverDispatch = true;
        try { return dispatchRoot.dispatchGenericMotionEvent(event); }
        finally { remoteMouseHoverDispatch = previous; }
    }

    private void configureFullscreenMouseHover(View view) {
        // Chromium's fullscreen ContentView is not our BrowserWebView. Apply the
        // same native mouse-move workaround after ViewGroup maps coordinates.
        view.setOnHoverListener((target, event) -> {
            int action = event.getActionMasked();
            if ((!remoteMouseHoverDispatch && !isSystemMouse(event)) || Build.VERSION.SDK_INT < 19
                    || event.getToolType(0) != MotionEvent.TOOL_TYPE_MOUSE
                    || (action != MotionEvent.ACTION_HOVER_MOVE
                        && action != MotionEvent.ACTION_HOVER_EXIT)) return false;
            MotionEvent move = MotionEvent.obtain(event);
            move.setAction(MotionEvent.ACTION_MOVE);
            if (action == MotionEvent.ACTION_HOVER_EXIT) move.setLocation(-1f, -1f);
            try { target.onHoverEvent(move); return true; }
            finally { move.recycle(); }
        });
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                configureFullscreenMouseHover(group.getChildAt(i));
        }
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        updateFullscreenExitForMouse(event);
        return super.dispatchGenericMotionEvent(event);
    }

    private void updateFullscreenExitForMouse(MotionEvent event) {
        if (!browserFullscreen || event == null
                || (event.getSource() & android.view.InputDevice.SOURCE_MOUSE)
                        != android.view.InputDevice.SOURCE_MOUSE) return;
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_HOVER_MOVE && action != MotionEvent.ACTION_MOVE
                && action != MotionEvent.ACTION_HOVER_EXIT) return;
        int[] location = new int[2];
        getLocationOnScreen(location);
        float x = event.getRawX() - location[0];
        float y = event.getRawY() - location[1];
        boolean near = action != MotionEvent.ACTION_HOVER_EXIT && y >= 0f && y <= dp(62)
                && Math.abs(x - getWidth() / 2f) <= dp(86);
        removeCallbacks(hideFullscreenExit);
        if (near) {
            fullscreenExitButton.setVisibility(View.VISIBLE);
            fullscreenExitButton.bringToFront();
        } else if (fullscreenExitButton.getVisibility() == View.VISIBLE) {
            postDelayed(hideFullscreenExit, 300L);
        }
    }

    private void updatePageLifecycle() {
        if (webView == null || destroyed) return;
        boolean resumed = pageActive && isPageVisible() && hostResumed;
        ((BrowserWebView) webView).updatePageLifecycle(resumed);
        // Never pause/resume process-wide timers shared by other tabs.
        dispatchWindowVisibilityChanged(getWindowVisibility());
    }

    private void configureWebViewSettings(WebSettings settings) {
        settings.setJavaScriptEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setGeolocationEnabled(false);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            settings.setAppCacheEnabled(true);
        }
        // Let WebView own normal HTTP traffic and honor each site's cache headers.
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= 17) {
            // Let the website choose whether to start. Requiring a gesture makes some players fall back to
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

    void setMultimediaPaused(boolean paused) {
        if (legacy != null) { legacy.command(paused ? "pause" : "play"); return; }
        setMultimediaPaused(webView, paused);
        if (paused) for (WebView retained : retainedTabWebViews.values()) setMultimediaPaused(retained, true);
    }

    private void setMultimediaPaused(WebView target, boolean paused) {
        if(target==null)return;
        String script=paused
            ? "(function(){if(window.__ntvMediaPause)return;var saved=[];function hush(e){if(!e.muted)e.muted=true;if(!e.paused)e.pause();}function scan(d){var m=d.querySelectorAll('audio,video');for(var i=0;i<m.length;i++){var e=m[i];if(!e.__ntvPaused){e.__ntvPaused=true;saved.push([e,e.muted,!e.paused]);}hush(e);}var f=d.querySelectorAll('iframe');for(var j=0;j<f.length;j++){try{if(f[j].contentDocument)scan(f[j].contentDocument);}catch(ignore){}}}var proto=window.HTMLMediaElement&&HTMLMediaElement.prototype,originalPlay=proto&&proto.play;function blockedPlay(){if(!this.__ntvPaused){this.__ntvPaused=true;saved.push([this,this.muted,true]);}else{for(var j=0;j<saved.length;j++){if(saved[j][0]===this){saved[j][2]=true;break;}}}hush(this);return window.Promise?Promise.resolve():undefined;}if(proto)proto.play=blockedPlay;function apply(){scan(document);}apply();var timer=setInterval(apply,250);window.__ntvMediaPause=function(){clearInterval(timer);if(proto&&proto.play===blockedPlay)proto.play=originalPlay;for(var i=0;i<saved.length;i++){var a=saved[i];if(a[0].muted!==a[1])a[0].muted=a[1];delete a[0].__ntvPaused;if(a[2]){var p=a[0].play();if(p&&p.catch)p.catch(function(){});}}delete window.__ntvMediaPause;};})();"
            : "if(window.__ntvMediaPause)window.__ntvMediaPause();";
        if(!paused)target.onResume();
        if(Build.VERSION.SDK_INT>=19)target.evaluateJavascript(script,null);
        else target.loadUrl("javascript:"+script);
        if(paused)target.onPause();
    }

    private void ensureWebView() {
        if (webView != null) return;
        webView = createWebView(getContext());
        addView(webView, 0, new LayoutParams(viewportWidth, viewportHeight));
        updateDesktopViewport(getWidth(), getHeight());
    }

    private void destroyCurrentWebView() {
        destroyCurrentWebView(false);
    }

    private void destroyCurrentWebView(boolean afterHandoff) {
        if (renderDiagnostics != null) renderDiagnostics.stop("destroyed");
        hideFullscreenView();
        WebView current = webView;
        webView = null;
        sourceClient = null;
        desktopProfile = null;
        pageScriptManager = null;
        destroyWebViewInstance(current, afterHandoff);
    }

    private boolean hasMemoryForAdditionalWebView() {
        Object service = getContext().getSystemService(Context.ACTIVITY_SERVICE);
        if (!(service instanceof ActivityManager)) return false;
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        ((ActivityManager) service).getMemoryInfo(memory);
        return !memory.lowMemory && memory.availMem > MULTI_WEBVIEW_MIN_AVAILABLE_BYTES;
    }

    private void retainCurrentWebView(WebTabBar.Tab tab) {
        WebView current = webView;
        if (current == null || tab == null) return;
        if (renderDiagnostics != null) renderDiagnostics.stop("tab-backgrounded");
        hideFullscreenView();
        ((BrowserWebView) current).updatePageLifecycle(false);
        removeView(current);
        retainedTabWebViews.put(tab, current);
        webView = null;
        sourceClient = null;
        desktopProfile = null;
        pageScriptManager = null;
    }

    private boolean activateRetainedWebView(WebTabBar.Tab tab) {
        WebView retained = retainedTabWebViews.remove(tab);
        if (retained == null) return false;
        webView = retained;
        retained.setVisibility(View.VISIBLE);
        configureWebViewSettings(retained.getSettings());
        retained.setInitialScale(cssInitialScalePercent());

        sourceClient = new SourceClient();
        WebViewRecovery.attach(retained, sourceClient, this::onRendererGone);
        desktopProfile = ((BrowserWebView) retained).desktopPolicy;
        pageScriptManager = ((BrowserWebView) retained).pagePolicy;
        updateDesktopProfile();
        updatePageScripts();
        addView(retained, 0, new LayoutParams(viewportWidth, viewportHeight));
        return true;
    }

    private void releaseTabWebView(WebTabBar.Tab tab, boolean preserveState) {
        if (tab == null) return;
        WebView retained = retainedTabWebViews.remove(tab);
        if (retained == null) return;
        if (preserveState && !tab.sleeping) {
            Bundle state = new Bundle();
            try {
                if (retained.saveState(state) != null) tab.state = state;
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to save background tab " + tab.id, error);
                tab.state = null;
            }
        } else {
            tab.state = null;
        }
        destroyWebViewInstance(retained);
    }

    private void releaseRetainedWebViews(boolean preserveState) {
        WebTabBar.Tab[] tabs = retainedTabWebViews.keySet().toArray(
                new WebTabBar.Tab[retainedTabWebViews.size()]);
        for (WebTabBar.Tab tab : tabs) releaseTabWebView(tab, preserveState);
    }

    private void destroyWebViewInstance(WebView target) {
        destroyWebViewInstance(target, false);
    }

    private void destroyWebViewInstance(WebView target, boolean afterHandoff) {
        if (target == null) return;
        BrowserWebView browser = (BrowserWebView) target;
        if (browser.retiring) return;
        browser.retiring = true;
        cancelShutdown(browser);
        browser.desktopPolicy.dispose();
        browser.pagePolicy.release();
        // Do not detach/destroy a live video surface inside the tab-close callback.
        // Hide it without a GONE/detach transition; drain the document first.
        target.setAlpha(0f);
        target.setEnabled(false);
        browser.shutdown = new WebViewShutdown(target, () -> {
            browser.shutdown = null;
            destroyDrainedWebView(target);
        }, () -> browser.shutdown = null);
        if (afterHandoff && !destroyed) browser.shutdown.deferUntilHandoff();
        startShutdown(browser.shutdown);
    }

    private void startShutdown(WebViewShutdown shutdown) {
        pendingShutdowns.add(shutdown);
        shutdown.whenComplete(stopped -> pendingShutdowns.remove(shutdown));
        shutdown.start();
        // Rapid closes cannot accumulate an unbounded set of video renderers.
        int waiting = 0;
        for (WebViewShutdown item : pendingShutdowns) if (item.isAwaitingHandoff()) waiting++;
        for (WebViewShutdown item : pendingShutdowns.toArray(new WebViewShutdown[0])) {
            if (waiting <= 2) break;
            if (item.isAwaitingHandoff()) { item.releaseAfterHandoff(); waiting--; }
        }
    }

    private void releaseRetiredWebViews() {
        if (stablePageRelease != null) removeCallbacks(stablePageRelease);
        stablePageRelease = null;
        for (WebViewShutdown item : pendingShutdowns.toArray(new WebViewShutdown[0]))
            item.releaseAfterHandoff();
    }

    private void releaseRetiredAfterStablePage(final WebView selected) {
        boolean waiting = false;
        for (WebViewShutdown item : pendingShutdowns) waiting |= item.isAwaitingHandoff();
        if (!waiting || !isActiveDocument(selected)) return;
        final SourceClient owner = sourceClient;
        final int navigation = resetGeneration, load = pageLoadGeneration;
        Runnable drawn = () -> {
            if (selected != webView || owner != sourceClient || navigation != resetGeneration
                    || load != pageLoadGeneration || !isActiveDocument(selected)) return;
            if (stablePageRelease != null) removeCallbacks(stablePageRelease);
            stablePageRelease = () -> {
                stablePageRelease = null;
                if (selected == webView && owner == sourceClient && navigation == resetGeneration
                        && load == pageLoadGeneration && isActiveDocument(selected) && isPageVisible())
                    releaseRetiredWebViews();
            };
            postDelayed(stablePageRelease, 350L);
        };
        if (Build.VERSION.SDK_INT >= 23) {
            VisualHandoff.await(selected, navigation, drawn);
        } else {
            // Legacy WebView has no compositor fence. Called after page finish
            // (or retained-page activation), then allow a normal draw to settle.
            selected.invalidate();
            selected.post(drawn);
        }
    }

    @TargetApi(23)
    private static final class VisualHandoff {
        static void await(WebView view, long id, Runnable drawn) {
            view.postVisualStateCallback(id, new WebView.VisualStateCallback() {
                @Override public void onComplete(long requestId) { drawn.run(); }
            });
        }
    }

    private void cancelShutdown(BrowserWebView browser) {
        if (browser.shutdown != null) {
            browser.shutdown.cancel();
            browser.shutdown = null;
        }
        browser.setWebChromeClient(browser.browserChromeClient);
        // A cancelled drain may have navigated the renderer; refresh lifecycle state.
        browser.pageResumed = null;
    }

    private void destroyDrainedWebView(WebView target) {
        target.setWebChromeClient(null);
        target.setWebViewClient(null);
        android.view.ViewParent parent = target.getParent();
        if (parent instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) parent).removeView(target);
        }
        target.destroy();
    }

    private void preparePage(String url) {
        removeCallbacks(unloadClosedPage);
        pageActive = false;
        requestId = -1;
        resetGeneration++;
        hideFullscreenView();
        dismissSmartContextPopup();
        ensureWebView();
        cancelShutdown((BrowserWebView) webView);
        // Client identity rejects queued old-client work. The start barrier also
        // rejects old-document events delivered to the new client before loadUrl
        // commits the requested navigation. No blank page or native teardown.
        sourceClient = new SourceClient();
        sourceClient.awaitDocument(url);
        WebViewRecovery.attach(webView, sourceClient, this::onRendererGone);
    }

    private boolean isActiveDocument(WebView view) {
        return !destroyed && pageActive && requestId >= 0 && view == webView
                && sourceClient != null && !sourceClient.awaitingDocument
                && sameDocument(view.getUrl(), pageUrl);
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

    private void beginPageLoad(final WebView view) {
        if (view == null || view != webView || !pageActive || requestId < 0) return;
        final int generation = ++pageLoadGeneration;
        pageLoadTerminalGeneration = -1;
        setLoadingVisible(true);
        probePageReady(view, generation, 700L);
        probePageReady(view, generation, 1800L);
        probePageReady(view, generation, 4000L);
        postDelayed(new Runnable() {
            @Override public void run() {
                if (!isCurrentPageLoad(view, generation)) return;
                Log.w(TAG, "Web loading timeout released overlay progress=" + view.getProgress()
                        + " url=" + view.getUrl());
                completePageLoad(view, generation, "timeout");
            }
        }, 20000L);
    }

    private boolean isCurrentPageLoad(WebView view, int generation) {
        return view != null && view == webView && pageActive && requestId >= 0
                && isActiveDocument(view)
                && generation == pageLoadGeneration
                && pageLoadTerminalGeneration != generation && !destroyed;
    }

    private void probePageReady(final WebView view, final int generation, long delayMs) {
        postDelayed(new Runnable() {
            @Override public void run() {
                if (!isCurrentPageLoad(view, generation)) return;
                if (desktopProfile != null && !desktopProfile.hasDocumentStartProtection()
                        && DesktopWebProfile.isSpoofed(userAgentMode)) {
                    desktopProfile.applyToCurrentDocument();
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return;
                view.evaluateJavascript("(function(){return !!document.body&&"
                                + "(document.readyState==='interactive'||document.readyState==='complete')})()",
                        value -> {
                            if ("true".equals(value) && isCurrentPageLoad(view, generation)) {
                                completePageLoad(view, generation, "dom-ready");
                            }
                        });
            }
        }, Math.max(0L, delayMs));
    }

    private void completePageLoadLater(final WebView view, final int generation,
            long delayMs, final String reason) {
        postDelayed(new Runnable() {
            @Override public void run() {
                if (isCurrentPageLoad(view, generation) && view.getProgress() >= 100) {
                    completePageLoad(view, generation, reason);
                }
            }
        }, delayMs);
    }

    private void completePageLoad(WebView view, int generation, String reason) {
        if (!isCurrentPageLoad(view, generation)) return;
        pageLoadTerminalGeneration = generation;
        setLoadingVisible(false);
        String current = view.getUrl();
        if (isWebPage(current)) pageUrl = current;
        tabBar.updateActive(pageUrl, view.getTitle());
        controllerPageTitle = safe(view.getTitle());
        Log.i(TAG, "Web page usable reason=" + reason + " progress=" + view.getProgress()
                + " url=" + pageUrl);
        if (listener != null) listener.onPageReady(requestId, pageUrl, view.getTitle());
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
        int previousViewportWidth = viewportWidth;
        int contentHeight = height > 0 ? Math.max(1, height - tabBarHeight) : height;
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
        if (width > 0 && contentHeight > 0) {
            viewportHeight = Math.max(1,
                    Math.round(viewportWidth * (contentHeight / (float) width)));
        } else if (viewportWidth == VIEWPORT_4K_WIDTH) {
            viewportHeight = VIEWPORT_4K_HEIGHT;
        } else if (viewportWidth == VIEWPORT_2K_WIDTH) {
            viewportHeight = VIEWPORT_2K_HEIGHT;
        } else if (viewportWidth == VIEWPORT_1080P_WIDTH) {
            viewportHeight = VIEWPORT_1080P_HEIGHT;
        } else {
            viewportHeight = VIEWPORT_720P_HEIGHT;
        }
        if (previousViewportWidth != viewportWidth) {
            setInterfaceScale(loadingInterfaceScale);
        }
        updateDesktopProfile();
        if (webView == null) {
            return;
        }
        // Keep WebView at its real on-screen size. Scaling a WebView child with
        // setScaleX/Y looks correct in the Activity window, but several Android
        // WebView implementations capture its backing layer at the unscaled
        // size. On wide viewports that leaves a large empty strip at the right
        // or bottom after scrolling. The meta viewport below still supplies the
        // requested logical desktop width.
        int contentWidth = width > 0 ? width : viewportWidth;
        int layoutHeight = contentHeight > 0 ? contentHeight : viewportHeight;
        LayoutParams params = (LayoutParams) webView.getLayoutParams();
        if (params.width != contentWidth || params.height != layoutHeight
                || params.topMargin != tabBarHeight) {
            params.width = contentWidth;
            params.height = layoutHeight;
            params.topMargin = tabBarHeight;
            webView.setLayoutParams(params);
        }
        if (width <= 0 || height <= 0) {
            return;
        }
        webView.setScaleX(1f);
        webView.setScaleY(1f);
        webView.setTranslationX(0f);
        webView.setTranslationY(0f);
        webView.post(this::clampWebScrollToContent);
        if (pageActive) {
            if (renderDiagnostics == null) renderDiagnostics = new WebRenderDiagnostics(getContext());
            renderDiagnostics.update(webView,
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
                + "var p=d.head||d.documentElement;"
                + "if(!p){if(!w.__ntvViewportWaiting&&d.addEventListener){"
                + "w.__ntvViewportWaiting=true;d.addEventListener('DOMContentLoaded',function(){"
                + "w.__ntvViewportWaiting=false;a();},false);}return;}"
                // A parser/SPA can insert another viewport after our early injection.
                // Chromium uses that later declaration; updating only the first one
                // leaves native innerWidth at device-width despite a correct screen.width.
                + "var ms=d.getElementsByTagName('meta'),found=false;"
                + "for(var i=0;i<ms.length;i++){var m=ms[i];"
                + "if(String(m.name||'').toLowerCase()!=='viewport')continue;"
                + "found=true;if(m.content!==w.__ntvViewportTarget)m.content=w.__ntvViewportTarget;}"
                + "if(!found){var m=d.createElement('meta');m.name='viewport';"
                + "m.content=w.__ntvViewportTarget;p.appendChild(m);}};"
                + "w.__ntvApplyViewport=a;a();"
                + "if(!w.__ntvViewportObserver&&w.MutationObserver&&d.documentElement){"
                // Comments/danmaku mutate the body every frame. Only metadata
                // changes can affect our viewport; never rescan for ordinary text.
                + "function metas(n){return n&&n.nodeType===1&&(n.tagName==='META'||n.tagName==='meta'||"
                + "n.firstElementChild&&n.querySelector&&n.querySelector('meta'));}"
                + "w.__ntvViewportObserver=new MutationObserver(function(rs){"
                + "for(var i=0;i<rs.length;i++){var r=rs[i];"
                + "if(r.type==='attributes'){if(r.target.tagName==='META'||r.target.tagName==='meta'){a();return;}continue;}"
                + "var lists=[r.addedNodes,r.removedNodes];for(var j=0;j<2;j++){var ns=lists[j];"
                + "for(var k=0;k<ns.length;k++)if(metas(ns[k])){a();return;}}}});"
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
        int physicalWidth = getWidth();
        float baseScale = density > 0f ? 1f / density : 1f;
        if (physicalWidth > 0 && viewportWidth > 0 && density > 0f) {
            baseScale = physicalWidth / (density * viewportWidth);
        }
        return baseScale * effectivePageScale();
    }

    private float effectivePageScale() {
        return currentPageScale;
    }

    private int cssInitialScalePercent() {
        // Unlike meta viewport initial-scale, WebView.setInitialScale is expressed
        // in physical pixels. Account for the real WebView width now that its
        // backing layer is no longer stretched with a View transform.
        int physicalWidth = getWidth();
        float physicalScale = physicalWidth > 0 && viewportWidth > 0
                ? physicalWidth / (float) viewportWidth : 1f;
        return Math.max(1, Math.round(physicalScale * effectivePageScale() * 100f));
    }

    void applyConfiguration(String requestedViewportMode, boolean requestedLoadImages,
            String requestedUserAgentMode, float requestedPageScale) {
        applyConfiguration(requestedViewportMode, requestedLoadImages,
                requestedUserAgentMode, "native", requestedPageScale, true, false, false, "[]");
    }

    void applyConfiguration(String requestedViewportMode, boolean requestedLoadImages,
            String requestedUserAgentMode, String requestedBrowserVersionMode,
            float requestedPageScale) {
        applyConfiguration(requestedViewportMode, requestedLoadImages,
                requestedUserAgentMode, requestedBrowserVersionMode, requestedPageScale,
                true, false, false, "[]");
    }

    private void updateToolbarHeight(int heightDp) {
        int next = browserFullscreen ? 0 : tabBar.heightPx();
        if (next == tabBarHeight) return;
        tabBarHeight = next;
        LayoutParams toolbar = (LayoutParams) tabBar.getLayoutParams();
        toolbar.height = tabBarHeight;
        tabBar.setLayoutParams(toolbar);
        updateDesktopViewport(getWidth(), getHeight());
        requestLayout();
        if (pageActive) scheduleDesktopViewport(120L);
    }

    private void setBrowserFullscreen(boolean enabled) {
        if (browserFullscreen == enabled) return;
        browserFullscreen = enabled;
        tabBarHeight = enabled ? 0 : tabBar.heightPx();
        tabBar.setVisibility(enabled || !pageActive ? View.GONE : View.VISIBLE);
        removeCallbacks(hideFullscreenExit);
        fullscreenExitButton.setVisibility(View.GONE);
        updateDesktopViewport(getWidth(), getHeight());
        requestLayout();
        if (pageActive) scheduleDesktopViewport(100L);
    }

    private void clampWebScrollToContent() {
        if (webView == null || !pageActive || webView.getContentHeight() <= 0) return;
        int maximum = Math.max(0,
                Math.round(webView.getContentHeight() * webView.getScale()) - webView.getHeight());
        int current = webView.getScrollY();
        if (current < 0 || current > maximum) {
            webView.scrollTo(webView.getScrollX(), Math.max(0, Math.min(current, maximum)));
        }
    }

    void settleRemoteWebScroll() {
        if (legacy != null) return;
        removeCallbacks(settleRemoteScroll);
        post(settleRemoteScroll);
        postDelayed(settleRemoteScroll, 120L);
    }

    boolean exitBrowserFullscreen() {
        if (legacy != null) {
            if (!legacy.visible || !legacy.state.getBoolean("fullscreen")) return false;
            legacy.command("fullscreenExit"); legacy.state.putBoolean("fullscreen", false); return true;
        }
        if (!browserFullscreen) return false;
        setBrowserFullscreen(false);
        return true;
    }

    private void applyActiveTabMute() {
        if (webView == null || !pageActive || destroyed) return;
        boolean muted = tabBar.activeMuted();
        String script = "(function(){var m=" + (muted ? "true" : "false") + ";"
                + "window.__ntvTabMuted=m;var q=document.querySelectorAll('audio,video');"
                + "for(var i=0;i<q.length;i++){var e=q[i];if(m){if(!e.hasAttribute('data-ntv-muted'))"
                + "e.setAttribute('data-ntv-muted',e.muted?'1':'0');e.muted=true;}else{"
                + "if(e.getAttribute('data-ntv-muted')==='0')e.muted=false;e.removeAttribute('data-ntv-muted');}}"
                + "if(window.__ntvMuteObserver){window.__ntvMuteObserver.disconnect();window.__ntvMuteObserver=null;}"
                + "function muteAdded(e){if(!e||e.nodeType!==1)return;"
                + "if((e.tagName==='AUDIO'||e.tagName==='VIDEO'||e.tagName==='audio'||e.tagName==='video')&&!e.hasAttribute('data-ntv-muted')){"
                + "e.setAttribute('data-ntv-muted',e.muted?'1':'0');if(!e.muted)e.muted=true;}"
                + "if(!e.firstElementChild)return;var a=e.querySelectorAll('audio:not([data-ntv-muted]),video:not([data-ntv-muted])');"
                + "for(var j=0;j<a.length;j++){a[j].setAttribute('data-ntv-muted',a[j].muted?'1':'0');if(!a[j].muted)a[j].muted=true;}}"
                + "if(m&&window.MutationObserver&&document.documentElement){window.__ntvMuteObserver=new MutationObserver(function(rs){"
                + "for(var i=0;i<rs.length;i++)for(var j=0;j<rs[i].addedNodes.length;j++)muteAdded(rs[i].addedNodes[j]);});"
                + "window.__ntvMuteObserver.observe(document.documentElement,{childList:true,subtree:true});}})();";
        if (Build.VERSION.SDK_INT >= 19) webView.evaluateJavascript(script, null);
        else webView.loadUrl("javascript:" + script);
    }

    private void notifyBrowserPoliciesChanged() {
        tabBar.setPolicies(loadImages, adBlockEnabled, webRtcEnabled);
        if (listener != null) {
            listener.onBrowserPoliciesChanged(loadImages, adBlockEnabled, webRtcEnabled);
        }
    }

    private static boolean isSystemMouse(MotionEvent event) {
        return event != null && event.getDeviceId() != 0
                && (event.getSource() & android.view.InputDevice.SOURCE_MOUSE)
                        == android.view.InputDevice.SOURCE_MOUSE;
    }

    private void runSystemMouseSelection(String operation, MotionEvent event) {
        if (webView == null || Build.VERSION.SDK_INT < 19) return;
        float nx = Math.max(0f, Math.min(1f, event.getX() / Math.max(1f, webView.getWidth())));
        float ny = Math.max(0f, Math.min(1f, event.getY() / Math.max(1f, webView.getHeight())));
        String script;
        if ("end".equals(operation)) {
            script = "window.__ntvMouseSelection&&window.__ntvMouseSelection.end();";
        } else {
            script = "window.__ntvMouseSelection&&window.__ntvMouseSelection." + operation + "("
                    + String.format(Locale.US, "%.6f,%.6f", nx, ny) + ");";
        }
        webView.evaluateJavascript(script, null);
    }

    void applyConfiguration(String requestedViewportMode, boolean requestedLoadImages,
            String requestedUserAgentMode, String requestedBrowserVersionMode,
            float requestedPageScale, boolean requestedAdBlock, boolean requestedWebRtc,
            boolean requestedUserScriptEnabled, String requestedUserScripts) {
        if (legacy != null) {
            android.os.Bundle data = new android.os.Bundle();
            data.putString("viewport", requestedViewportMode); data.putBoolean("images", requestedLoadImages);
            data.putString("ua", requestedUserAgentMode); data.putString("version", requestedBrowserVersionMode);
            data.putFloat("pageScale", requestedPageScale); data.putBoolean("ads", requestedAdBlock);
            data.putBoolean("rtc", requestedWebRtc); data.putBoolean("scripts", requestedUserScriptEnabled);
            data.putString("userScripts", requestedUserScripts);data.putBoolean("transpile",legacyTranspilationEnabled); legacy.configure(data); return;
        }
        String nextMode = "4k".equals(requestedViewportMode)
                || "2k".equals(requestedViewportMode)
                || "1080p".equals(requestedViewportMode)
                ? requestedViewportMode : "720p";
        String nextUserAgentMode = sanitizeUserAgentMode(requestedUserAgentMode);
        String nextBrowserVersionMode = sanitizeBrowserVersionMode(requestedBrowserVersionMode);
        String nextUserAgent = userAgentForMode(nextUserAgentMode, nextBrowserVersionMode);
        float nextPageScale = Math.max(0.5f, Math.min(3f, requestedPageScale));
        boolean reloadPage = loadImages != requestedLoadImages
                || !nextUserAgentMode.equals(userAgentMode)
                || !nextBrowserVersionMode.equals(browserVersionMode)
                || adBlockEnabled != requestedAdBlock || webRtcEnabled != requestedWebRtc
                || userScriptEnabled != requestedUserScriptEnabled
                || !safe(requestedUserScripts).equals(userScripts);
        boolean changed = !nextMode.equals(viewportMode) || loadImages != requestedLoadImages
                || !nextUserAgentMode.equals(userAgentMode)
                || !nextBrowserVersionMode.equals(browserVersionMode)
                || Math.abs(pageScale - nextPageScale) > 0.001f || reloadPage;
        // A retained document cannot undo executed userscripts or WebRTC hooks.
        // Preserve its history, but recreate it with the new policy on activation.
        if (reloadPage) releaseRetainedWebViews(true);
        viewportMode = nextMode;
        pageScale = nextPageScale;
        currentPageScale = nextPageScale;
        loadImages = requestedLoadImages;
        userAgentMode = nextUserAgentMode;
        browserVersionMode = nextBrowserVersionMode;
        adBlockEnabled = requestedAdBlock;
        if (adBlockEnabled) WebAdBlocker.refreshAsync(false);
        webRtcEnabled = requestedWebRtc;
        tabBar.setPolicies(loadImages, adBlockEnabled, webRtcEnabled);
        userScriptEnabled = requestedUserScriptEnabled;
        userScripts = safe(requestedUserScripts);
        activeUserAgent = nextUserAgent;
        if (webView != null) {
            WebSettings settings = webView.getSettings();
            settings.setLoadsImagesAutomatically(loadImages);
            settings.setBlockNetworkImage(!loadImages);
            settings.setUserAgentString(activeUserAgent);
            webView.setInitialScale(cssInitialScalePercent());
        }
        updateDesktopViewport(getWidth(), getHeight());
        updatePageScripts();
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
                        beginPageLoad(expectedView);
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

    /** Experimental syntax adaptation applies to newly opened documents. */
    void setLegacyTranspilation(boolean enabled){
        legacyTranspilationEnabled=enabled;
        if(legacy!=null){legacy.configuration.putBoolean("transpile",enabled);android.os.Bundle data=new android.os.Bundle();data.putBoolean("enabled",enabled);legacy.send("transpilation",data);}
    }

    void legacyUnavailable(int failedRequest, String reason) {
        setVisibility(View.GONE);
        if (listener != null) listener.onPageUnavailable(failedRequest, reason);
    }

    void probeLegacyRenderer(Runnable result) {
        if (Build.VERSION.SDK_INT < 19 || webView == null || !pageActive) { result.run(); return; }
        webView.evaluateJavascript("1", ignored -> result.run());
    }

    boolean isInBrowserFullscreen() { return browserFullscreen || fullscreenView != null; }

    void deliverLegacyEvent(String event, android.os.Bundle data, int id) {
        if (listener == null) return;
        switch (event) {
            case "started": listener.onPageStarted(id, data.getString("url")); break;
            case "reset": listener.onResourcesReset(id, data.getString("url")); break;
            case "ready": listener.onPageReady(id, currentPageUrl(), currentPageTitle()); break;
            case "stream": listener.onStreamDiscovered(id, data.getString("stream"), data.getString("url"), data.getString("ua"), data.getString("cookies")); break;
            case "home": listener.onBrowserHome(); break;
            case "channel": listener.onBrowserChannel(data.getInt("group"), data.getInt("channel")); break;
            case "image": listener.onBrowserDownloadImage(data.getString("url")); break;
            case "clipboard": listener.onBrowserClipboard(data.getString("text"), data.getString("message")); break;
            case "script": listener.onBrowserUserScript(data.getString("url")); break;
            case "policies": listener.onBrowserPoliciesChanged(data.getBoolean("images"), data.getBoolean("ads"), data.getBoolean("rtc")); break;
            case "relative": listener.onBrowserRelativeChannel(data.getInt("direction")); break;
        }
    }

    boolean isBrowserChromeTouch(MotionEvent event) {
        if (event == null || !pageActive || tabBar.getVisibility() != View.VISIBLE) return false;
        Rect bounds = new Rect();
        return tabBar.getGlobalVisibleRect(bounds) && !bounds.isEmpty()
                && bounds.contains(Math.round(event.getRawX()), Math.round(event.getRawY()));
    }

    boolean isBrowserNativeContextPoint(float screenX, float screenY) {
        return pageActive && tabBar.containsNativeContextPoint(screenX, screenY);
    }

    boolean scrollBrowserChromeAt(float screenX, float screenY, int distanceX) {
        return pageActive && tabBar.scrollBookmarkBarAt(screenX, screenY, distanceX);
    }

    /** Give the native edge control sole ownership of a pointer sequence. */

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (legacy != null && legacy.visible) {
            android.os.Bundle data = new android.os.Bundle(); data.putParcelable("motion", event);
            legacy.send("touch", data); return true;
        }
        updateFullscreenExitForMouse(event);
        if (event != null && event.getActionMasked() == MotionEvent.ACTION_DOWN
                && pageActive && !false) {

        }
        return super.dispatchTouchEvent(event);
    }

    private String channelPageScript = "";
    private volatile String htmlMimeOverrideUrl = "";
    private volatile String htmlMimeOverrideCookies = "";

    void open(int newRequestId, String url) {
        open(newRequestId, url, "");
    }

    void open(int newRequestId, String url, String pageScript) {
        open(newRequestId, url, pageScript, false);
    }

    void open(int newRequestId, String url, String pageScript, boolean htmlMimeOverride) {
        if (legacy != null) { legacy.open(newRequestId, url, pageScript, htmlMimeOverride); return; }
        htmlMimeOverrideUrl = htmlMimeOverride ? url : "";
        htmlMimeOverrideCookies = htmlMimeOverride ? CookieManager.getInstance().getCookie(url) : "";
        channelPageScript = pageScript == null ? "" : pageScript;
        rendererRetries = 0;
        WebTabBar.Tab tab = tabBar.openChannel(url, "");
        if (tab == null) return;
        releaseTabWebView(tab, false);
        openPage(newRequestId, url);
    }

    private void openPage(int newRequestId, String url) {
        loadPage(newRequestId, url);
    }

    private void loadPage(int newRequestId, String url) {
        if (destroyed) return;
        tabBar.dismissTransientPanels();
        dismissSmartContextPopup();
        boolean wasStreamSuspended = streamPageSuspended;
        streamPageSuspended = false;
        WebTabBar.Tab targetTab = tabBar.active();
        boolean switchingTabs = pageActive && loadedTab != null
                && loadedTab != targetTab && webView != null;
        boolean needsMemoryDecision = switchingTabs || !retainedTabWebViews.isEmpty();
        boolean allowMultipleInstances = needsMemoryDecision && hasMemoryForAdditionalWebView();
        boolean keepCurrentInstance = switchingTabs && allowMultipleInstances
                && !wasStreamSuspended && !loadedTab.sleeping;
        if (needsMemoryDecision && !allowMultipleInstances) {
            // Reclaim previously retained renderers before starting another one.
            // Their navigation state remains available through saveState().
            releaseRetainedWebViews(true);
        }
        if (switchingTabs) {
            String currentUrl = webView.getUrl();
            if (isWebPage(currentUrl)) loadedTab.url = currentUrl;
            if (!wasStreamSuspended) loadedTab.title = webView.getTitle();
            if (wasStreamSuspended || loadedTab.sleeping) {
                loadedTab.state = null;
            } else if (keepCurrentInstance) {
                loadedTab.state = null;
                retainCurrentWebView(loadedTab);
            } else {
                loadedTab.state = new Bundle();
                webView.saveState(loadedTab.state);
            }
        }
        boolean reuseRetainedTab = targetTab != null && targetTab != loadedTab
                && retainedTabWebViews.containsKey(targetTab);
        boolean restoreTab = targetTab != null && targetTab != loadedTab
                && targetTab.state != null && !reuseRetainedTab;
        // Trackpad pinch zoom belongs to one opened channel only. Every channel
        // entry starts from the configured browser scale again.
        currentPageScale = pageScale;
        try {
            if (reuseRetainedTab) {
                removeCallbacks(unloadClosedPage);
                pageActive = false;
                requestId = -1;
                resetGeneration++;
                if (webView != null) destroyCurrentWebView(true);
                if (!activateRetainedWebView(targetTab)) preparePage(url);
            } else preparePage(url);
        } catch (WebViewAvailability.UnavailableException error) {
            pageActive = false;
            requestId = -1;
            webView = null;
            sourceClient = null;
            loadedTab = targetTab;
            pageUrl = url;
            resetResourcePage(url);
            releaseRetiredWebViews();
            setLoadingVisible(false);
            setVisibility(View.VISIBLE);
            bringToFront();
            tabBar.bringToFront();
            android.widget.Toast.makeText(getContext(), WebViewAvailability.MESSAGE,
                    android.widget.Toast.LENGTH_LONG).show();
            if (listener != null) listener.onPageUnavailable(newRequestId, WebViewAvailability.MESSAGE);
            return;
        }
        requestId = newRequestId;
        String retainedUrl = reuseRetainedTab && webView != null ? webView.getUrl() : null;
        pageUrl = isWebPage(retainedUrl) ? retainedUrl : url;
        discoveredStreamUrls.clear();
        pageActive = true;
        clearInitialHistory = !restoreTab && !reuseRetainedTab;
        compatibilityInjectionCount = 0;
        profileInjectionCount = 0;
        pagePolicyInjectionCount = 0;
        resetGeneration++;
        setVisibility(View.VISIBLE);
        bringToFront();
        // Retiring video WebViews stay attached until their asynchronous drain
        // finishes. A restored/new WebView is inserted at index 0, so explicitly
        // promote the selected document above them, not just the tab strip.
        // Alpha on the retiring renderer is not a content ownership boundary.
        webView.bringToFront();
        tabBar.setVisibility(browserFullscreen ? View.GONE : View.VISIBLE);
        if (!browserFullscreen) tabBar.bringToFront();
        fullscreenExitButton.setVisibility(View.GONE);
        updatePageScripts();

        updatePageLifecycle();
        updateDesktopViewport(getWidth(), getHeight());
        webView.setInitialScale(cssInitialScalePercent());
        resetResourcePage(pageUrl, reuseRetainedTab);
        sourceClient.resetHttpsFallback();
        loadedTab = targetTab;
        // A new/retained tab may not deliver onPageStarted for a long time (or
        // at all). Dismiss the previous page's native chrome at navigation intent.
        if (listener != null) listener.onPageStarted(requestId, pageUrl);
        if (reuseRetainedTab) {
            setLoadingVisible(false);
            setMultimediaPaused(webView, false);
            tabBar.updateActive(pageUrl, webView.getTitle());
            controllerPageTitle = safe(webView.getTitle());
            applyActiveTabMute();
            applyDesktopViewport();
            releaseRetiredAfterStablePage(webView);
            if (listener != null) {
                listener.onPageReady(requestId, pageUrl, webView.getTitle());
            }
        } else {
            beginPageLoad(webView);
        }
        if (!reuseRetainedTab
                && (!restoreTab || webView.restoreState(targetTab.state) == null)) {
            webView.loadUrl(url);
        }
    }

    private void navigateAddress(String value) {
        if (webView == null || !pageActive || destroyed) return;
        String target = normalizeAddress(value);
        if (!isWebPage(target)) return;
        tabBar.dismissTransientPanels();
        dismissSmartContextPopup();
        if (listener != null) listener.onPageStarted(requestId, target);
        channelPageScript = "";
        rendererRetries = 0;
        hideFullscreenView();
        pageUrl = target;
        tabBar.updateActive(target, null);
        beginPageLoad(webView);
        resetResourcePage(target);
        sourceClient.resetHttpsFallback();
        sourceClient.armBootstrap(target);
        webView.loadUrl(target);
    }

    private static String normalizeAddress(String input) {
        String value = input == null ? "" : input.trim();
        if (value.length() == 0) return "";
        String lower = value.toLowerCase(Locale.US);
        if (lower.startsWith("http://") || lower.startsWith("https://")) return value;
        if (value.startsWith("//")) return "https:" + value;
        boolean looksLikeHost = value.indexOf(' ') < 0 && value.indexOf('\t') < 0
                && (value.indexOf('.') > 0 || "localhost".equalsIgnoreCase(value)
                    || value.matches("[0-9.]+(:[0-9]+)?(/.*)?"));
        return looksLikeHost ? "https://" + value
                : "https://www.baidu.com/s?wd=" + Uri.encode(value);
    }

    private void reloadPage() {
        if (webView == null || !pageActive || destroyed) return;
        tabBar.dismissTransientPanels();
        hideFullscreenView();
        beginPageLoad(webView);
        webView.reload();
    }

    private void openCurrentInNewTab() {
        String url = webView == null ? pageUrl : webView.getUrl();
        if (!isWebPage(url)) return;
        if (tabBar.openNew(url, webView == null ? "" : webView.getTitle()) == null) return;
        openPage(requestId, url);
    }

    void openBookmarkedPage(String url, String title, String group, boolean newTab) {
        if (legacy != null) { android.os.Bundle data = LegacyWebSession.bundle("url", url); data.putString("title", title); data.putString("group", group); data.putBoolean("newTab", newTab); legacy.send("bookmark", data); return; }
        if (!isWebPage(url) || destroyed) return;
        if (isUserScriptInstallUrl(url)) {
            if (listener != null) listener.onBrowserUserScript(url);
            return;
        }
        WebTabBar.Tab tab = newTab ? tabBar.openNew(url, title) : tabBar.openChannel(url, title);
        if (tab == null) return;
        if (!newTab) releaseTabWebView(tab, false);
        tab.bookmarkTitle = safe(title);
        tab.bookmarkGroup = safe(group);
        tab.bookmarkUrl = url;
        rendererRetries = 0;
        openPage(requestId, url);
    }

    void openLinkInNewTab(String url) {
        if (legacy != null) { legacy.send("tab", LegacyWebSession.bundle("url", url)); return; }
        if (isUserScriptInstallUrl(url)) {
            if (listener != null) listener.onBrowserUserScript(url);
            return;
        }
        if (!isWebPage(url) || destroyed) return;
        // If every tab is pinned, report the capacity limit without replacing the current page.
        if (tabBar.openNew(url, "") == null) return;
        rendererRetries = 0;
        openPage(requestId, url);
    }

    void openLinkInBackgroundTab(String url) {
        if (legacy != null) { legacy.send("backgroundTab", LegacyWebSession.bundle("url", url)); return; }
        if (destroyed || !pageActive || !isWebPage(url)) return;
        if (isUserScriptInstallUrl(url)) {
            if (listener != null) listener.onBrowserUserScript(url);
            return;
        }
        // Keep the visible document, resource identity and audio focus untouched.
        // Load on selection through the normal tab path, not in a hidden renderer.
        if (tabBar.openBackground(url, "") != null) {
            Toast.makeText(getContext(), "已在后台添加标签，切换后加载", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean queuePopupNavigation(final WebView opener, String value,
            final WebView popup) {
        final String url = value == null ? "" : value.trim();
        if (!isWebPage(url)) return false;
        post(new Runnable() {
            @Override public void run() {
                if (popup != null) {
                    popup.stopLoading();
                    popup.setWebViewClient(null);
                    popup.destroy();
                }
                if (destroyed || opener != webView || !pageActive) return;
                openLinkInNewTab(url);
            }
        });
        return true;
    }

    private void loadBrowserTab(WebTabBar.Tab tab) {
        if (tab == null || !isWebPage(tab.url) || destroyed) return;
        channelPageScript = "";
        rendererRetries = 0;
        openPage(requestId, tab.url);
    }

    private boolean pinchViewportPosted;
    private final Runnable applyPinchViewport = new Runnable() {
        @Override public void run() {
            pinchViewportPosted = false;
            if (webView == null || !pageActive || destroyed) return;
            webView.setInitialScale(cssInitialScalePercent());
            updateDesktopProfile();
            applyDesktopViewport();
        }
    };

    void adjustCurrentPageScale(float factor) {
        if (legacy != null) { legacy.send("pageScale", LegacyWebSession.bundle("factor", factor)); return; }
        if (webView == null || !pageActive || destroyed
                || Float.isNaN(factor) || Float.isInfinite(factor)) return;
        float next = Math.max(0.5f, Math.min(3f, currentPageScale * factor));
        if (next == currentPageScale) return;
        currentPageScale = next;
        if (!pinchViewportPosted) {
            pinchViewportPosted = true;
            postDelayed(applyPinchViewport, 16L);
        }
    }

    private void onRendererGone(WebView failed, boolean crashed) {
        if (failed != webView) {
            WebTabBar.Tab failedTab = null;
            for (Map.Entry<WebTabBar.Tab, WebView> entry : retainedTabWebViews.entrySet()) {
                if (entry.getValue() == failed) {
                    failedTab = entry.getKey();
                    break;
                }
            }
            if (failedTab != null) {
                retainedTabWebViews.remove(failedTab);
                failedTab.state = null;
            }
            return;
        }
        if (renderDiagnostics != null) renderDiagnostics.stop("rendererGone crashed=" + crashed);
        final int failedRequest = requestId;
        final String failedUrl = pageUrl;
        final boolean visible = pageActive && isPageVisible();
        webView = null;
        sourceClient = null;
        desktopProfile = null;
        pageScriptManager = null;
        pageActive = false;

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
        // Let sibling WebViews acknowledge the same dead renderer before closing the page.
        postDelayed(() -> {
            if (!destroyed && generation == resetGeneration && webView == null && listener != null)
                listener.onPageUnavailable(failedRequest,
                        crashed ? "网页渲染进程崩溃" : "网页渲染进程因内存不足退出");
        }, 750L);
    }

    void closePage() {
        if (legacy != null) { legacy.close(); setVisibility(View.GONE); return; }
        releaseRetiredWebViews();
        streamPageSuspended = false;
        tabBar.dismissTransientPanels();
        dismissSmartContextPopup();
        channelPageScript = "";
        if (renderDiagnostics != null) renderDiagnostics.stop("closed");
        hideFullscreenView();
        if (destroyed) {
            return;
        }
        if (!pageActive && requestId < 0 && getVisibility() != View.VISIBLE) return;
        ++resetGeneration;
        pageActive = false;
        clearInitialHistory = false;
        requestId = -1;
        pageUrl = null;
        loadedTab = null;
        sourceClient = null;
        controllerPageTitle = "";
        resourcePageKey = "";
        discoveredStreamUrls.clear();
        setLoadingVisible(false);
        tabBar.setVisibility(View.GONE);
        fullscreenExitButton.setVisibility(View.GONE);
        browserFullscreen = false;
        tabBarHeight = tabBar.heightPx();
        setVisibility(View.GONE);
        // startChannel closes and opens in the same UI turn. Let the destination
        // load directly; only unload if we actually leave the browser. Keeping
        // one blank instance avoids rebuilding WebView when returning later.
        removeCallbacks(unloadClosedPage);
        post(unloadClosedPage);
    }

    private final Runnable unloadClosedPage = new Runnable() {
        @Override public void run() {
            if (destroyed || pageActive || webView == null) return;
            final BrowserWebView idle = (BrowserWebView) webView;
            if (idle.shutdown != null) return;
            idle.shutdown = new WebViewShutdown(idle, () -> {
                idle.shutdown = null;
                if (idle == webView && !pageActive && !destroyed) {
                    idle.pageResumed = null;
                    updatePageLifecycle();
                }
            }, () -> {
                idle.shutdown = null;
                if (idle == webView) onRendererGone(idle, false);
            });
            startShutdown(idle.shutdown);
        }
    };

    boolean isPageVisible() {
        if (legacy != null) return legacy.visible;
        return getVisibility() == View.VISIBLE;
    }

    boolean hasRetainedPage() {
        if (legacy != null) return legacy.active;
        return !destroyed && webView != null && pageActive && requestId >= 0;
    }

    boolean hasRetainedPage(int expectedRequestId) {
        if (legacy != null) return legacy.active && legacy.request == expectedRequestId;
        return hasRetainedPage() && requestId == expectedRequestId;
    }

    void hideForStreamPlayback() {
        if (legacy != null) { legacy.hide(); return; }
        if (!hasRetainedPage()) {
            return;
        }
        tabBar.dismissTransientPanels();
        dismissSmartContextPopup();
        hideFullscreenView();
        // onPause alone does not stop a late site play() from stealing IJK audio focus.
        setMultimediaPaused(true);
        setLoadingVisible(false);
        setVisibility(View.GONE);
        updatePageLifecycle();
    }

    void suspendForStreamPlayback(WebViewShutdown.Completion completion) {
        if (legacy != null) { legacy.suspend(completion); return; }
        if (!hasRetainedPage() || isPageVisible() || streamPageSuspended) {
            completion.onComplete(false);
            return;
        }
        streamPageSuspended = true;
        ++resetGeneration;
        sourceClient = null; // Reject late resources/navigation from the drained document.
        if (renderDiagnostics != null) renderDiagnostics.stop("native-playback-stable");
        // Other retained renderers must not keep media/decoders running either.
        releaseRetainedWebViews(true);
        if (loadedTab != null) loadedTab.state = null;
        final BrowserWebView idle = (BrowserWebView) webView;
        cancelShutdown(idle);
        idle.shutdown = new WebViewShutdown(idle, () -> {
            idle.shutdown = null;
            if (idle == webView && streamPageSuspended && !destroyed) {
                idle.pageResumed = null;
                updatePageLifecycle();
            }
        }, () -> {
            idle.shutdown = null;
            if (idle == webView) onRendererGone(idle, false);
        });
        startShutdown(idle.shutdown);
        // Include background tabs and previously closed tabs still draining. One
        // about:blank callback is not proof that every WebView has stopped audio.
        WebViewShutdown[] pending = pendingShutdowns.toArray(new WebViewShutdown[0]);
        final int[] remaining = {pending.length};
        final boolean[] stopped = {true};
        final int generation = resetGeneration;
        if (pending.length == 0) {
            completion.onComplete(false);
            return;
        }
        for (WebViewShutdown shutdown : pending) shutdown.whenComplete(success -> {
            stopped[0] &= success;
            if (--remaining[0] == 0) completion.onComplete(stopped[0]
                    && generation == resetGeneration && streamPageSuspended
                    && !destroyed && !isPageVisible());
        });
    }

    boolean restoreAfterStreamPlayback() {
        if (legacy != null) return legacy.restore();
        if (!hasRetainedPage()) {
            return false;
        }
        if (streamPageSuspended) {
            // A drained page is never automatically reloaded on BACK.
            return false;
        }
        setVisibility(View.VISIBLE);
        bringToFront();
        tabBar.setVisibility(browserFullscreen ? View.GONE : View.VISIBLE);
        if (!browserFullscreen) tabBar.bringToFront();
        fullscreenExitButton.setVisibility(View.GONE);
        updatePageLifecycle();
        setMultimediaPaused(false);
        webView.requestFocus();
        updateDesktopViewport(getWidth(), getHeight());
        return true;
    }

    boolean canRestoreAfterStreamPlayback() {
        if (legacy != null) return legacy.active && !legacy.visible && !legacy.suspended;
        return hasRetainedPage() && !isPageVisible() && !streamPageSuspended;
    }

    private String userAgentForMode(String mode, String versionMode) {
        if (DesktopWebProfile.isDesktop(mode)) {
            return DesktopWebProfile.userAgent(mode, browserUserAgent, versionMode);
        }
        if ("ipad".equals(mode)) {
            return DesktopWebProfile.ipadUserAgent(browserUserAgent, versionMode);
        }
        if ("native".equals(mode)) {
            return browserUserAgent;
        }
        return WINDOWS_USER_AGENT;
    }

    private void updateDesktopProfile() {
        if (desktopProfile != null) {
            desktopProfile.update(userAgentMode, browserUserAgent, browserVersionMode,
                    viewportWidth, viewportHeight, effectivePageScale());
        }
    }

    private static String sanitizeUserAgentMode(String mode) {
        if ("macos".equals(mode) || "ipad".equals(mode) || "native".equals(mode)) {
            return mode;
        }
        return "windows";
    }

    private static String sanitizeBrowserVersionMode(String mode) {
        if ("118".equals(mode) || "128".equals(mode) || "138".equals(mode)) {
            return mode;
        }
        return "native";
    }

    boolean goBackIfPossible() {
        if (legacy != null) { if (!legacy.visible) return false; legacy.command("back"); return true; }
        if (webView == null || !isPageVisible() || destroyed || !pageActive) {
            return false;
        }
        if (fullscreenView != null) {
            final View exiting = fullscreenView;
            WebFullscreenCompatibility.beforeNativeExit(webView, () -> {
                if (fullscreenView == exiting) hideFullscreenView();
            });
            return true;
        }
        WebBackForwardList history = webView.copyBackForwardList();
        int currentIndex = history == null ? -1 : history.getCurrentIndex();
        int targetIndex = previousWebPageIndex(history, currentIndex);
        if (targetIndex < 0) {
            return false;
        }
        beginPageLoad(webView);
        webView.goBackOrForward(targetIndex - currentIndex);
        return true;
    }

    boolean goForwardIfPossible() {
        if (legacy != null) { if (!legacy.visible) return false; legacy.command("forward"); return true; }
        if (webView == null || !isPageVisible() || destroyed || !pageActive) return false;
        WebBackForwardList history = webView.copyBackForwardList();
        if (history == null) return false;
        for (int index = history.getCurrentIndex() + 1; index < history.getSize(); index++) {
            WebHistoryItem item = history.getItemAtIndex(index);
            if (item != null && isWebPage(item.getUrl())) {
                hideFullscreenView();
                beginPageLoad(webView);
                webView.goBackOrForward(index - history.getCurrentIndex());
                return true;
            }
        }
        return false;
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
        if (legacy != null) { legacy.scale = scale; legacy.send("scale", LegacyWebSession.bundle("scale", scale)); return; }
        float safeScale = Math.max(0.35f, Math.min(1.60f, scale));
        interfaceScale = safeScale;
        loadingInterfaceScale = safeScale;
        dismissSmartContextPopup();
        tabBar.setInterfaceScale(safeScale);
        fullscreenExitButton.setPadding(dp(6), dp(6), dp(6), dp(6));
        LayoutParams exitParams = (LayoutParams) fullscreenExitButton.getLayoutParams();
        exitParams.width = dp(42);
        exitParams.height = dp(42);
        exitParams.topMargin = dp(8);
        fullscreenExitButton.setLayoutParams(exitParams);
        updateToolbarHeight(tabBar.heightDp());
        // Resize each native element instead of scaling a pre-rendered card. Scaling the
        // whole hierarchy makes text and the spinner visibly soft on 4K televisions.
        loadingOverlay.setScaleX(1f);
        loadingOverlay.setScaleY(1f);
        applyLoadingOverlayMetrics(loadingOverlay, safeScale);
    }

    void dispatchRemoteKey(int keyCode, int metaState) {
        if (legacy != null) { android.os.Bundle data = new android.os.Bundle(); data.putInt("key", keyCode); data.putInt("meta", metaState); legacy.send("key", data); return; }
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
        if (legacy != null) { legacy.send("text", LegacyWebSession.bundle("text", text)); return; }
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
        deleteObsoleteProxyCache(new File(getContext().getCacheDir(), "legacy-js-cache"));
        if (legacy != null) {
            if (legacy.active) legacy.command("cache");
            else getContext().startService(new android.content.Intent(getContext(), LegacyWebCacheService.class));
            cachedBrowserCacheBytes = 0L; browserCacheMeasuredAt = 0L; return;
        }
        if (destroyed) {
            return;
        }
        if (webView != null) {
            webView.clearCache(true);
            webView.clearHistory();
            webView.clearFormData();
        } else {
            // clearCache() is process-wide but requires an instance. Creating one
            // here is acceptable because this path only runs after an explicit
            // user action, while normal native-channel startup remains lazy.
            WebView cleaner = null;
            try {
                cleaner = WebViewAvailability.create(() -> new WebView(getContext()));
                cleaner.clearCache(true);
                cleaner.clearFormData();
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to initialize WebView cache cleaner", error);
            } finally {
                if (cleaner != null) cleaner.destroy();
            }
        }
        android.webkit.WebStorage.getInstance().deleteAllData();
        deleteObsoleteProxyCache(new File(getContext().getCacheDir(), "browser"));
        cachedBrowserCacheBytes = 0L;
        browserCacheMeasuredAt = android.os.SystemClock.elapsedRealtime();
    }

    synchronized long browserCacheSizeBytes() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (browserCacheMeasuredAt > 0L && now - browserCacheMeasuredAt < 30000L) {
            return cachedBrowserCacheBytes;
        }
        File webViewData = getContext().getDir("webview", Context.MODE_PRIVATE);
        File cacheRoot = getContext().getCacheDir();
        cachedBrowserCacheBytes = measureFiles(webViewData, false)
                + measureFiles(new File(cacheRoot, "webview"), true)
                + measureFiles(new File(cacheRoot, "WebView"), true)
                + measureFiles(new File(cacheRoot, "webviewCacheChromium"), true)
                + measureFiles(new File(cacheRoot, "legacy-js-cache"), true);
        browserCacheMeasuredAt = now;
        return cachedBrowserCacheBytes;
    }

    boolean cancelRemoteMouseButton(MotionEvent release) {
        if (legacy != null) { android.os.Bundle data = new android.os.Bundle(); data.putParcelable("motion", release); legacy.send("mouseCancel", data); return legacy.visible; }
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
        if (legacy != null) return legacy.value("ua");
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

    private void updatePageScripts() {
        boolean blockAds = adBlockEnabled && tabBar.activeAdBlockEnabled();
        if (sourceClient != null) {
            sourceClient.requestAdBlockEnabled = blockAds;
            sourceClient.mainDocumentUrl = pageUrl;
        }
        if (pageScriptManager != null) {
            pageScriptManager.update(blockAds, webRtcEnabled,
                    userScriptEnabled, userScripts);
            if (sourceClient != null)
                sourceClient.legacyPrelude = LegacyWebCompatibility.supports(pageUrl)
                        ? pageScriptManager.legacyDocumentPrelude() : "";
        }
    }

    interface SmartContextCallback { void onResult(JSONObject result); }
    /** Fixed, read-only diagnostics; does not expose arbitrary script execution over IPC. */
    void inspectCompatibility(SmartContextCallback callback) {
        if (legacy != null) { legacy.inspectCompatibility(callback); return; }
        if (webView == null || Build.VERSION.SDK_INT < 19) { callback.onResult(new JSONObject()); return; }
        webView.evaluateJavascript("(function(){var p=window.performance,t=p&&p.timing,v=document.querySelector('video');"
                + "return {url:location.href,title:document.title,ready:document.readyState,"
                + "text:document.body?(document.body.innerText||'').slice(0,180):'',"
                + "compat:window.__ntvTvCompatibility||null,errors:window.__ntvCompatErrors||[],genericProof:typeof window.__ntvGenericProof==='string'?window.__ntvGenericProof.slice(0,64):'',"
                + "policy:window.__ntvPagePolicy,modernScripts:'noModule' in document.createElement('script'),"
                + "promise:typeof Promise,fetch:typeof fetch,params:typeof URLSearchParams,"
                + "domMs:t&&t.domContentLoadedEventEnd>0?t.domContentLoadedEventEnd-t.navigationStart:0,"
                + "loadMs:t&&t.loadEventEnd>0?t.loadEventEnd-t.navigationStart:0,"
                + "video:v?{src:v.currentSrc,ready:v.readyState,paused:v.paused,time:v.currentTime,error:v.error&&v.error.code}:null};})()",
                value -> {
                    JSONObject result=LegacyWebSession.json(value);
                    try { synchronized(discoveredStreamUrls){result.put("discoveredStreams",discoveredStreamUrls.size());} if(sourceClient!=null)result.put("transpiler",sourceClient.transpilation.stats()); }
                    catch(org.json.JSONException ignored) { }
                    callback.onResult(result);
                });
    }

    void inspectSmartContext(float screenX, float screenY,
            final SmartContextCallback callback) {
        if (legacy != null) { legacy.inspect(screenX, screenY, callback); return; }
        if (callback == null) return;
        if (webView == null || !pageActive || !isPageVisible() || destroyed) {
            callback.onResult(new JSONObject());
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            callback.onResult(hitTestContext());
            return;
        }
        int[] location = new int[2];
        webView.getLocationOnScreen(location);
        float shownWidth = Math.max(1f, webView.getWidth() * webView.getScaleX());
        float shownHeight = Math.max(1f, webView.getHeight() * webView.getScaleY());
        if (screenX < location[0] || screenY < location[1]
                || screenX >= location[0] + shownWidth
                || screenY >= location[1] + shownHeight) {
            callback.onResult(new JSONObject());
            return;
        }
        float x = Math.max(0f, Math.min(1f, (screenX - location[0]) / shownWidth));
        float y = Math.max(0f, Math.min(1f, (screenY - location[1]) / shownHeight));
        String script = "(function(nx,ny){var x=nx*window.innerWidth,y=ny*window.innerHeight,"
                + "e=document.elementFromPoint(x,y),a=null,img=null,n=e,i=0;"
                + "while(n&&i++<12){var tag=(n.tagName||'').toLowerCase();"
                + "if(!img&&tag==='img')img=n;if(!a&&tag==='a')a=n;"
                + "n=n.parentElement;}var image=img?(img.currentSrc||img.src||''):'',"
                + "link=a?(a.href||''):'',title=(a&&(a.title||a.textContent)||"
                + "img&&(img.alt||img.title)||'').trim(),selection='';"
                + "try{selection=String(window.getSelection?window.getSelection():'').trim()}catch(z){}"
                + "return JSON.stringify({"
                + "kind:image&&link?'image-link':image?'image':link?'link':'none',"
                + "imageUrl:image,linkUrl:link,title:title.slice(0,160),"
                + "selection:selection.slice(0,4000)});})("
                + String.format(Locale.US, "%.6f,%.6f", x, y) + ");";
        final WebView inspectedView = webView;
        final long inspectedNavigation = resourceNavigation;
        if (Build.VERSION.SDK_INT >= 19) inspectedView.evaluateJavascript(script, new ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                if (inspectedView != webView || inspectedNavigation != resourceNavigation
                        || !pageActive || destroyed) {
                    callback.onResult(new JSONObject());
                    return;
                }
                try {
                    String decoded = new JSONArray("[" + value + "]").optString(0, "{}");
                    callback.onResult(new JSONObject(decoded));
                } catch (Exception error) {
                    callback.onResult(hitTestContext());
                }
            }
        });
    }

    private void showSystemMouseContext(final float screenX, final float screenY) {
        inspectSmartContext(screenX, screenY, new SmartContextCallback() {
            @Override public void onResult(JSONObject result) {
                if (destroyed || !pageActive) return;
                showSmartContextPopup(screenX, screenY, result == null ? new JSONObject() : result);
            }
        });
    }

    void showSmartContextAt(float screenX, float screenY) {
        if (legacy != null) { android.os.Bundle data = LegacyWebSession.bundle("x", screenX); data.putFloat("y", screenY); legacy.send("context", data); return; }
        showSystemMouseContext(screenX, screenY);
    }

    private void showSmartContextPopup(float screenX, float screenY, JSONObject context) {
        dismissSmartContextPopup();
        final String imageUrl = context.optString("imageUrl", "").trim();
        final String linkUrl = context.optString("linkUrl", "").trim();
        final String selection = context.optString("selection", "").trim();
        LinearLayout menu = new LinearLayout(getContext());
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(5), dp(5), dp(5), dp(5));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xfff8f9fa);
        background.setCornerRadius(dp(10));
        background.setStroke(dp(1), 0xffd8dce1);
        menu.setBackgroundDrawable(background);

        if (Build.VERSION.SDK_INT >= 21) menu.setElevation(dp(8));
        if (selection.length() > 0) {
            addSmartContextAction(menu, "复制所选文字", new Runnable() {
                @Override public void run() { copyToClipboard("网页文字", selection, "文字已复制"); }
            });
            addSmartContextAction(menu, "使用 Bing 搜索", new Runnable() {
                @Override public void run() {
                    String query = selection.length() > 512 ? selection.substring(0, 512) : selection;
                    openLinkInNewTab("https://www.bing.com/search?q=" + Uri.encode(query));
                }
            });
        }
        if (linkUrl.length() > 0) {
            addSmartContextAction(menu, "在新标签打开", new Runnable() {
                @Override public void run() { openLinkInNewTab(linkUrl); }
            });
            if (isWebPage(linkUrl) && !isUserScriptInstallUrl(linkUrl)) {
                addSmartContextAction(menu, "在后台打开新标签", new Runnable() {
                    @Override public void run() { openLinkInBackgroundTab(linkUrl); }
                });
            }
            addSmartContextAction(menu, "复制链接", new Runnable() {
                @Override public void run() { copyToClipboard("网页链接", linkUrl, "链接已复制"); }
            });
        }
        if (imageUrl.length() > 0) {
            addSmartContextAction(menu, "下载图片", new Runnable() {
                @Override public void run() {
                    if (listener != null) listener.onBrowserDownloadImage(imageUrl);
                }
            });
            addSmartContextAction(menu, "标记为广告", new Runnable() {
                @Override public void run() {
                    boolean added = markImageAsAd(imageUrl);
                    Toast.makeText(getContext(), added ? "已标记为广告" : "该图片已在广告规则中",
                            Toast.LENGTH_SHORT).show();
                }
            });
        }
        if (menu.getChildCount() == 0) return;
        int width = dp(228);
        int estimatedHeight = menu.getChildCount() * dp(44) + dp(10);
        int[] location = new int[2];
        getLocationOnScreen(location);
        int pointerX = Math.round(screenX) - location[0];
        int pointerY = Math.round(screenY) - location[1];
        int gap = dp(10);
        int x = pointerX + gap;
        if (x + width > getWidth() - gap) x = pointerX - width - gap;
        int y = pointerY + gap;
        if (y + estimatedHeight > getHeight() - gap) y = pointerY - estimatedHeight - gap;
        x = Math.max(gap, Math.min(x, Math.max(gap, getWidth() - width - gap)));
        y = Math.max(gap, Math.min(y, Math.max(gap, getHeight() - estimatedHeight - gap)));

        FrameLayout layer = new FrameLayout(getContext());
        layer.setClickable(true);
        layer.setFocusableInTouchMode(true);
        smartContextLayer = layer;
        addView(layer, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width,
                LayoutParams.WRAP_CONTENT);
        params.leftMargin = x;
        params.topMargin = y;
        layer.addView(menu, params);
        menu.setClickable(true);
        layer.setOnClickListener(v -> dismissSmartContextPopup());
        layer.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                dismissSmartContextPopup();
                return true;
            }
            return false;
        });
        layer.requestFocus();
        if (listener != null) listener.onBrowserOverlayShown();
    }

    private void addSmartContextAction(LinearLayout menu, String label, final Runnable action) {
        TextView item = new TextView(getContext());
        item.setText(label);
        item.setTextColor(0xff202124);
        item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * interfaceScale);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(14), 0, dp(14), 0);
        item.setBackgroundResource(android.R.drawable.list_selector_background);
        item.setOnClickListener(v -> {
            dismissSmartContextPopup();
            action.run();
        });
        menu.addView(item, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(44)));
    }

    private void dismissSmartContextPopup() {
        FrameLayout layer = smartContextLayer;
        smartContextLayer = null;
        if (layer != null && layer.getParent() == this) removeView(layer);
    }

    private void copyToClipboard(String label, String value, String message) {
        ClipboardManager clipboard = (ClipboardManager)
                getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        if (listener != null) listener.onBrowserClipboard(value, message);
    }

    private JSONObject hitTestContext() {
        JSONObject result = new JSONObject();
        if (webView == null) return result;
        WebView.HitTestResult hit = webView.getHitTestResult();
        if (hit == null || hit.getExtra() == null) return result;
        try {
            int type = hit.getType();
            if (type == WebView.HitTestResult.IMAGE_TYPE
                    || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                result.put("kind", "image").put("imageUrl", hit.getExtra());
            } else if (type == WebView.HitTestResult.ANCHOR_TYPE
                    || type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
                result.put("kind", "link").put("linkUrl", hit.getExtra());
            }
        } catch (Exception ignored) { }
        return result;
    }

    boolean markImageAsAd(String url) {
        if (legacy != null) { legacy.send("ad", LegacyWebSession.bundle("url", url)); return legacy.active; }
        if (!WebAdBlocker.markAsAd(getContext(), url)) return false;
        if (webView != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            String script = "(function(u){var a=document.images;for(var i=0;i<a.length;i++)"
                    + "if((a[i].currentSrc||a[i].src)===u)a[i].style.setProperty"
                    + "('display','none','important');})(" + JSONObject.quote(url) + ");";
            webView.evaluateJavascript(script, null);
        }
        return true;
    }

    String activeUserAgent() { return legacy != null ? legacy.value("ua") : activeUserAgent; }
    String activePageUrl() { return legacy != null ? legacy.value("url") : pageUrl; }

    private static String safe(String value) { return value == null ? "" : value; }

    private static void deleteObsoleteProxyCache(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteObsoleteProxyCache(child);
            }
        }
        file.delete();
    }

    void resumePage() {
        if (legacy != null) {
            // Re-enter the browser when the launcher/Recents returns to the player's task.
            postDelayed(() -> {
                if (getContext() instanceof MainActivity && ((MainActivity)getContext()).hasWindowFocus()
                        && ((MainActivity)getContext()).canPresentLegacyWeb()) legacy.present();
            }, 300L);
            return; // The isolated Activity owns its actual foreground lifecycle.
        }
        hostResumed = true;
        updatePageLifecycle();
    }

    String currentPageUrl() {
        if (legacy != null) return legacy.active ? legacy.value("url") : "";
        if (!hasRetainedPage()) return "";
        String current = webView == null ? null : webView.getUrl();
        if (current == null || !(current.startsWith("https://") || current.startsWith("http://"))) current = pageUrl;
        return current != null && (current.startsWith("https://") || current.startsWith("http://")) ? current : "";
    }

    void pausePage() {
        if (legacy != null) return;
        // Remember this even without a page: background channel changes must
        // inherit the session lifecycle, not unconditionally resume a new page.
        hostResumed = false;
        updatePageLifecycle();
    }

    void destroyPage() {
        if (legacy != null) { legacy.close(); destroyed = true; return; }
        if (destroyed) return;
        destroyed = true;
        releaseRetiredWebViews();
        pageActive = false;
        clearInitialHistory = false;
        requestId = -1;
        resetGeneration++;
        removeCallbacks(unloadClosedPage);
        removeCallbacks(applyPinchViewport);
        removeCallbacks(settleRemoteScroll);
        removeCallbacks(hideFullscreenExit);
        tabBar.dismissTransientPanels();
        dismissSmartContextPopup();
        setLoadingVisible(false);
        destroyCurrentWebView();
        releaseRetainedWebViews(false);
        loadedTab = null;
    }

    private void resetResourcePage(String url) {
        resetResourcePage(url, false);
    }

    // Called on the UI thread. Retained documents keep their resource identity;
    // reloads and new documents must never inherit another document's resources.
    String currentResourcePageKey() { return legacy != null ? legacy.value("pageKey") : resourcePageKey; }
    String currentPageTitle() { return legacy != null ? legacy.value("title") : controllerPageTitle.length() > 0 ? controllerPageTitle : safe(pageUrl); }

    private WebTabBar.Tab currentBookmarkChannel() {
        WebTabBar.Tab tab = tabBar.active();
        if (tab == null || tab.bookmarkTitle.length() == 0 || tab.bookmarkUrl.length() == 0) return null;
        String host = Uri.parse(tab.bookmarkUrl).getHost();
        // A same-tab link to another website must not inherit the previous station name.
        return host != null && host.equalsIgnoreCase(Uri.parse(safe(pageUrl)).getHost()) ? tab : null;
    }

    void trimMemory() {
        if (legacy != null) { legacy.command("trim"); return; }
        // Keep active/retained documents and history intact. No renderer teardown
        // or SQLite/file work in the system's main-thread memory callback.
        tabBar.trimMemory();
        synchronized (resourceUrlCache) { resourceUrlCache.clear(); }
    }

    String currentChannelTitle() {
        if (legacy != null) return legacy.value("channelTitle");
        WebTabBar.Tab tab = currentBookmarkChannel();
        return tab == null ? currentPageTitle() : tab.bookmarkTitle;
    }

    String currentChannelUrl() {
        if (legacy != null) return legacy.value("channelUrl");
        WebTabBar.Tab tab = currentBookmarkChannel();
        return tab == null ? activePageUrl() : tab.bookmarkUrl;
    }

    String currentChannelGroup() {
        if (legacy != null) return legacy.value("group");
        WebTabBar.Tab tab = currentBookmarkChannel();
        return tab == null ? "网页" : tab.bookmarkGroup.length() > 0 ? tab.bookmarkGroup : "网页收藏";
    }

    private boolean canReadWebMedia() {
        return Build.VERSION.SDK_INT >= 19 && hasRetainedPage() && isPageVisible()
                && !streamPageSuspended && sourceClient != null && !sourceClient.awaitingDocument;
    }

    JSONObject currentWebMediaState() {
        if (legacy != null) return legacy.media();
        if (!canReadWebMedia()) return new JSONObject();
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - webMediaSampleAt < (webMediaPending ? 2500L : 500L)) return webMediaState;
        webMediaSampleAt = now;
        webMediaPending = true;
        final long sample = ++webMediaSample, navigation = resourceNavigation;
        final WebView origin = webView;
        origin.evaluateJavascript("window.__ntvWebMedia?window.__ntvWebMedia.snapshot():null", value -> {
            if (sample != webMediaSample || navigation != resourceNavigation || origin != webView) return;
            webMediaPending = false;
            if (!canReadWebMedia()) return;
            try { webMediaState = value != null && value.length() < 300000 ? new JSONObject(value) : new JSONObject(); }
            catch (JSONException invalid) { webMediaState = new JSONObject(); }
        });
        return webMediaState;
    }

    interface MediaControlResult { void complete(String error); }

    void controlWebMedia(String action, String pageKey, String mediaToken, MediaControlResult result) {
        if (legacy != null) { legacy.mediaControl(action, pageKey, mediaToken, result); return; }
        if (!canReadWebMedia() || !currentResourcePageKey().equals(pageKey)
                || mediaToken.length() == 0 || !mediaToken.equals(webMediaState.optString("token"))) {
            result.complete("网页媒体已变化，请刷新后重试");
            return;
        }
        String target = "toggle".equals(action) ? (webMediaState.optBoolean("playing") ? "pause" : "play") : action;
        if (!"play".equals(target) && !"pause".equals(target)) {
            result.complete("当前网页只支持播放和暂停"); return;
        }
        final WebView origin = webView;
        final long navigation = resourceNavigation;
        String script = "window.__ntvWebMedia?window.__ntvWebMedia.command("
                + JSONObject.quote(webMediaState.optString("documentToken")) + ","
                + JSONObject.quote(mediaToken) + "," + JSONObject.quote(target) + ","
                + (System.currentTimeMillis() + 2000L) + "):null";
        origin.evaluateJavascript(script, value -> {
            if (!canReadWebMedia() || origin != webView || navigation != resourceNavigation) {
                result.complete("网页已切换，操作结果已忽略"); return;
            }
            try {
                JSONObject response = new JSONObject(value == null || value.length() > 8192 ? "{}" : value);
                result.complete(response.optBoolean("ok") ? null : response.optString("error", "网页没有响应媒体控制"));
            } catch (JSONException invalid) { result.complete("网页没有响应媒体控制"); }
            webMediaSample++;
            webMediaPending = false;
            webMediaSampleAt = 0L;
            currentWebMediaState();
        });
    }

    private void resetResourcePage(String url, boolean restore) {
        webMediaState = new JSONObject();
        webMediaSample++;
        webMediaPending = false;
        webMediaSampleAt = 0L;
        synchronized (discoveredStreamUrls) {
            resourceNavigation++;
            discoveredStreamUrls.clear();
        }
        synchronized (resourceUrlCache) { resourceUrlCache.clear(); }
        WebTabBar.Tab tab = tabBar.active();
        if (tab != null) {
            if (!restore || tab.resourceRevision == 0) tab.resourceRevision = resourceNavigation;
            resourcePageKey = tab.id + ":" + tab.resourceRevision;
        } else resourcePageKey = "";
        if (!restore) controllerPageTitle = "";
        if (listener != null) listener.onResourcesReset(requestId, url);
    }

    private void observeResource(final WebView origin, final SourceClient client, String url) {
        final long observedNavigation = resourceNavigation;
        if (origin != webView || client != sourceClient || client.awaitingDocument
                || !pageActive || requestId < 0) {
            return;
        }
        // A channel document can itself have a .m3u8/.mp4 suffix. It has already
        // been classified as a webpage; never auto-play that document as media.
        if (sameDocument(url, client.mainDocumentUrl)) return;
        final String streamUrl = cachedMediaPlaylist(url);
        if (streamUrl == null) {
            return;
        }
        final int observedRequestId = requestId;
        final String observedUrl = streamUrl;
        // Reserve before posting: repeated manifests and resource-heavy pages must
        // not flood the main queue or grow an unbounded deduplication set.
        synchronized (discoveredStreamUrls) {
            if (origin != webView || client != sourceClient || !pageActive
                    || observedNavigation != resourceNavigation
                    || observedRequestId != requestId || discoveredStreamUrls.size() >= 30
                    || !discoveredStreamUrls.add(observedUrl)) return;
        }
        post(new Runnable() {
            @Override
            public void run() {
                if (destroyed || !pageActive || origin != webView || client != sourceClient
                        || observedRequestId != requestId || observedNavigation != resourceNavigation || listener == null) {
                    return;
                }
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

    private String cachedMediaPlaylist(String url) {
        // Do not retain exceptionally long request URLs just for deduplication.
        if (url == null || url.length() > 8192) return normalizeMediaPlaylist(url);
        synchronized (resourceUrlCache) {
            if (resourceUrlCache.containsKey(url)) return resourceUrlCache.get(url);
            String result = normalizeMediaPlaylist(url);
            resourceUrlCache.put(url, result);
            return result;
        }
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
        if (query < 0) return null;
        String decoded = url.indexOf('%') < 0 ? url : decodeUrl(url);
        String decodedLower = decoded == url ? lower : decoded.toLowerCase(Locale.US);
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
        // A media URL inside an analytics query is not itself a playable resource.
        return HLS_QUERY.matcher(lower).matches() ? url : null;
    }

    private static String decodeUrl(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception ignored) {
            return value;
        }
    }

    private static String normalizedDocument(String url) {
        if (url == null) return "";
        int fragment = url.indexOf('#');
        return fragment < 0 ? url : url.substring(0, fragment);
    }

    private static boolean sameDocument(String first, String second) {
        return normalizedDocument(first).equals(normalizedDocument(second));
    }

    private static String navigationDocument(String url) {
        String value = normalizedDocument(url);
        if (!isWebPage(value)) return value;
        Uri uri = Uri.parse(value);
        // WebView canonicalizes bare origins (https://host -> https://host/).
        return uri.getPath() == null || uri.getPath().length() == 0
                ? uri.buildUpon().path("/").build().toString() : value;
    }

    private static WebResourceResponse blockedResponse() {
        return new WebResourceResponse("text/plain", "UTF-8",
                new ByteArrayInputStream(new byte[0]));
    }

    private final class SourceClient extends WebViewClient {
        // Interception runs off the UI thread: never inspect Views or mutable Tab state there.
        private volatile boolean requestAdBlockEnabled;
        private volatile String mainDocumentUrl;
        private volatile boolean awaitingDocument;
        private String expectedDocument;
        private String replacedDocument;
        private String fallbackDocument;
        private String fallbackCookies;
        private String attemptedFallback;
        private boolean fallbackRetryActive;
        private boolean fallbackExhausted;
        private String bootstrapUrl, bootstrapCookies;
        private volatile String legacyPrelude = "";
        private volatile LegacyWebTranspilation transpilation=new LegacyWebTranspilation(getContext());

        void awaitDocument(String url) {
            expectedDocument = navigationDocument(url);
            replacedDocument = navigationDocument(webView == null ? null : webView.getUrl());
            mainDocumentUrl = url;
            awaitingDocument = true;
            armBootstrap(url);
        }

        private synchronized void armBootstrap(String url) {
            // This runs on the UI thread for explicit GET navigation only.
            bootstrapUrl = bootstrapCookies = null;
            if (!LegacyWebCompatibility.enabled()) return;
            LegacyWebCompatibility.script(getContext());
            if (LegacyWebCompatibility.supports(url)) {
                bootstrapUrl = normalizedDocument(url);
                bootstrapCookies = CookieManager.getInstance().getCookie(url);
                LegacyWebTranspilation document=new LegacyWebTranspilation(getContext());document.navigate(url,bootstrapCookies);transpilation=document;
            }
        }
        private synchronized String claimBootstrap(String url) {
            if (!normalizedDocument(url).equals(bootstrapUrl)) return null;
            bootstrapUrl = null;
            return bootstrapCookies == null ? "" : bootstrapCookies;
        }

        synchronized void resetHttpsFallback() {
            fallbackDocument = null;
            fallbackCookies = null;
            attemptedFallback = null;
            fallbackRetryActive = false;
            fallbackExhausted = false;
        }

        private synchronized void armHttpsFallback(String url, String cookies) {
            fallbackDocument = normalizedDocument(url);
            fallbackCookies = cookies == null ? "" : cookies;
        }

        private synchronized String claimHttpsFallback(String url) {
            if (!normalizedDocument(url).equals(fallbackDocument)) return null;
            fallbackDocument = null;
            String cookies = fallbackCookies;
            fallbackCookies = null;
            return cookies == null ? "" : cookies;
        }

        private boolean retryHttpsWithLegacyClient(final WebView view, String url) {
            final String target = normalizedDocument(url);
            if (!LegacyWebHttp.enabled() || !target.startsWith("https://")
                    || !isCurrentDocument(view, target)) return false;
            synchronized (this) {
                if (target.equals(attemptedFallback)) return false;
                attemptedFallback = target;
                fallbackRetryActive = true;
                fallbackExhausted = false;
            }
            // CookieManager stays on WebView's callback thread. The intercept
            // thread only consumes this snapshot, avoiding KitKat deadlocks.
            armHttpsFallback(target, CookieManager.getInstance().getCookie(target));
            Log.i(TAG, "Retry legacy HTTPS document after WebView error: " + target);
            view.stopLoading();
            view.post(() -> {
                if (this == sourceClient && view == webView && pageActive) view.loadUrl(target);
            });
            return true;
        }

        private boolean isCurrentDocument(WebView view, String url) {
            String current = normalizedDocument(view == null ? null : view.getUrl());
            String opened = normalizedDocument(pageUrl);
            return url.equals(current) || url.equals(opened);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleNavigationOverride(view, url);
        }

        @TargetApi(21)
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (request == null || !request.isForMainFrame()) return false;
            Uri uri = request == null ? null : request.getUrl();
            return handleNavigationOverride(view, uri == null ? null : uri.toString());
        }

        private boolean handleNavigationOverride(WebView view, String url) {
            if (this != sourceClient || view != webView || !pageActive || requestId < 0 || url == null) {
                return false;
            }
            if (isUserScriptInstallUrl(url)) {
                if (listener != null) listener.onBrowserUserScript(url);
                return true;
            }
            String lower = url.toLowerCase(Locale.US);
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                return false;
            }
            if (awaitingDocument) expectedDocument = navigationDocument(url);
            armBootstrap(url);
            // Let WebView commit links and redirects itself. Reissuing loadUrl here
            // cancels the original navigation and replaces its history semantics.
            // onPageStarted updates pageUrl when the navigation actually commits.
            return false;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (this != sourceClient || view != webView || !pageActive || requestId < 0 || isBlankPage(url)) {
                return;
            }
            // Reused WebViews can deliver queued events from the document that
            // loadUrl just replaced, including to the newly assigned client.
            if (!url.equals(view.getUrl())) return;
            if (awaitingDocument && navigationDocument(url).equals(replacedDocument)
                    && !navigationDocument(url).equals(expectedDocument)) return;
            awaitingDocument = false;
            tabBar.dismissTransientPanels();
            pageUrl = url;
            tabBar.updateActive(url, null);
            updatePageScripts();
            // Android supplies WebView.getFavicon() here; on a reused instance
            // that can still be the previous site's icon. Never cache it as url.
            resetResourcePage(url);
            compatibilityInjectionCount = 0;
            profileInjectionCount = 0;
            pagePolicyInjectionCount = 0;
            WebFullscreenCompatibility.apply(view);
            WebAudioCompatibility.apply(view);
            applyActiveTabMute();
            if (!isPageVisible()) setMultimediaPaused(true);
            beginPageLoad(view);
            updateDesktopViewport(getWidth(), getHeight());
            if (desktopProfile != null && !desktopProfile.hasDocumentStartProtection()) {
                desktopProfile.applyToCurrentDocument();
            }
            if (pageScriptManager != null && !pageScriptManager.hasDocumentStartScript()) {
                pageScriptManager.applyToCurrentDocument();
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
            if (awaitingDocument) return;
            pageUrl = url;
            if (Build.VERSION.SDK_INT < 23) releaseRetiredAfterStablePage(view);
            // onPageFinished does not guarantee that a new favicon arrived.
            // Only onReceivedIcon may publish a new icon to the current tab.
            if (desktopProfile != null && DesktopWebProfile.isSpoofed(userAgentMode)) {
                desktopProfile.applyToCurrentDocument();
            }
            if (pageScriptManager != null) pageScriptManager.applyToCurrentDocument();
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
            WebFullscreenCompatibility.apply(view);
            WebAudioCompatibility.apply(view);
            // Ku9's jscode runs in the opened document, never in the resolver's
            // hidden WebView. A document guard prevents duplicate finished events
            // from installing repeated timers; close/open clears the channel script.
            if (channelPageScript.length() > 0) {
                String script = "(function(){if(window.__ntvKu9PageScript)return;"
                        + "window.__ntvKu9PageScript=true;try{" + channelPageScript
                        + "\n}catch(e){console.warn('Ku9 page script: '+e);}})();";
                if (Build.VERSION.SDK_INT >= 19) view.evaluateJavascript(script, null);
                else view.loadUrl("javascript:" + script);
            }
            if (!isPageVisible()) setMultimediaPaused(true);
            completePageLoad(view, pageLoadGeneration, "page-finished");
            synchronized (this) {
                if (normalizedDocument(url).equals(attemptedFallback)) {
                    fallbackRetryActive = false;
                    fallbackExhausted = false;
                }
            }
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            if (this != sourceClient || !isActiveDocument(view) || !sameDocument(url, pageUrl)) return;
            releaseRetiredAfterStablePage(view);
            if (desktopProfile != null && !desktopProfile.hasDocumentStartProtection()
                    && DesktopWebProfile.isSpoofed(userAgentMode)) {
                desktopProfile.applyToCurrentDocument();
            }
            probePageReady(view, pageLoadGeneration, 0L);
        }

        @Override
        public void onLoadResource(WebView view, String url) {
            if (this != sourceClient || !isActiveDocument(view)) return;
            if (profileInjectionCount < 6 && desktopProfile != null
                    && !desktopProfile.hasDocumentStartProtection()
                    && DesktopWebProfile.isSpoofed(userAgentMode)) {
                profileInjectionCount++;
                desktopProfile.applyToCurrentDocument();
            }
            if (pagePolicyInjectionCount < 3 && pageScriptManager != null
                    && !pageScriptManager.hasDocumentStartScript()) {
                pagePolicyInjectionCount++;
                pageScriptManager.applyToCurrentDocument();
            }
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
            if (requestAdBlockEnabled && !sameDocument(url, mainDocumentUrl)
                    && WebAdBlocker.shouldBlock(url)) return blockedResponse();
            if (this != sourceClient || view != webView || !pageActive) return null;
            WebResourceResponse adapted = LegacyTvSiteAdapter.resource(mainDocumentUrl, url, activeUserAgent);
            if (adapted != null) return adapted;
            if (sameDocument(url, htmlMimeOverrideUrl)) return interceptConfirmedHtml(view, url);
            observeResource(view, this, url);
            if(legacyTranspilationEnabled&&LegacyJsTranspiler.enabled()&&!sameDocument(url,mainDocumentUrl)){
                WebResourceResponse compiled=transpilation.resource(url,activeUserAgent);
                if(compiled!=null)return compiled;
            }
            String bootstrap = claimBootstrap(url);
            if (bootstrap != null && !legacyPrelude.isEmpty()) {
                final int bootstrapGeneration = pageLoadGeneration;
                WebResourceResponse page = LegacyTvSiteAdapter.page(url, legacyPrelude);
                if (page != null) return page;
                return LegacyWebHttp.intercept(url, activeUserAgent, bootstrap,
                        (cookieUrl, value) -> view.post(() -> CookieManager.getInstance().setCookie(cookieUrl, value)),
                        target -> view.post(() -> {
                            if (this != sourceClient || view != webView || !pageActive
                                    || bootstrapGeneration != pageLoadGeneration) return;
                            armBootstrap(target);view.loadUrl(target);
                        }), false, legacyPrelude,legacyTranspilationEnabled?transpilation:null);
            }
            String cookies = claimHttpsFallback(url);
            if (cookies != null) {
                return LegacyWebHttp.intercept(url, activeUserAgent, cookies,
                        (cookieUrl, value) -> view.post(() -> CookieManager.getInstance()
                                .setCookie(cookieUrl, value)),
                        target -> view.post(() -> {
                            if (this != sourceClient || view != webView || !pageActive
                                    || !url.equals(view.getUrl())) return;
                            armHttpsFallback(target,
                                    CookieManager.getInstance().getCookie(target));
                            view.loadUrl(target);
                        }), false, LegacyWebCompatibility.supports(url) ? legacyPrelude : "",legacyTranspilationEnabled&&LegacyJsTranspiler.enabled()?transpilation:null);
            }
            return null;
        }

        @TargetApi(21)
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view,
                WebResourceRequest request) {
            if (request == null) return null;
            String url = request.getUrl() == null ? null : request.getUrl().toString();
            if (requestAdBlockEnabled && !request.isForMainFrame()
                    && WebAdBlocker.shouldBlock(url)) return blockedResponse();
            if (this != sourceClient || view != webView || !pageActive) {
                return null;
            }
            if (request.isForMainFrame() && "GET".equals(request.getMethod())
                    && sameDocument(url, htmlMimeOverrideUrl)) return interceptConfirmedHtml(view, url);
            if (!request.isForMainFrame()) observeResource(view, this, url);
            return null;
        }

        private WebResourceResponse interceptConfirmedHtml(WebView view, String url) {
            return LegacyWebHttp.intercept(url, activeUserAgent, htmlMimeOverrideCookies,
                    (cookieUrl, value) -> view.post(() -> CookieManager.getInstance().setCookie(cookieUrl, value)),
                    target -> view.post(() -> {
                        if (this != sourceClient || view != webView || !pageActive) return;
                        htmlMimeOverrideUrl = target;
                        htmlMimeOverrideCookies = CookieManager.getInstance().getCookie(target);
                        view.loadUrl(target);
                    }), true, LegacyWebCompatibility.supports(url) ? legacyPrelude : "",legacyTranspilationEnabled&&LegacyJsTranspiler.enabled()?transpilation:null);
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            String failingUrl = error == null ? null : error.getUrl();
            if (this == sourceClient && view == webView && pageActive && requestId >= 0
                    && retryHttpsWithLegacyClient(view, failingUrl)) {
                handler.cancel();
                return;
            }
            synchronized (this) {
                if (normalizedDocument(failingUrl).equals(attemptedFallback)) {
                    fallbackExhausted = true;
                }
            }
            super.onReceivedSslError(view, handler, error);
        }

        @Override
        public void onReceivedError(WebView view, int errorCode, String description,
                String failingUrl) {
            if (this != sourceClient || view != webView || !pageActive || requestId < 0 || isBlankPage(failingUrl)) {
                return;
            }
            if (!sameDocument(failingUrl, pageUrl)) return;
            if (errorCode == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE
                    && retryHttpsWithLegacyClient(view, failingUrl)) return;
            synchronized (this) {
                if (normalizedDocument(failingUrl).equals(attemptedFallback)
                        && fallbackRetryActive && !fallbackExhausted) {
                    // cancel() from the original WebView request may report after
                    // the compatibility retry was scheduled. Do not fail the
                    // channel while that one fallback request is still active.
                    return;
                }
                fallbackRetryActive = false;
            }
            Log.w(TAG, "Web source failed code=" + errorCode + " url=" + failingUrl
                    + " description=" + description);
            pageLoadTerminalGeneration = pageLoadGeneration;
            setLoadingVisible(false);
            if (listener != null) {
                listener.onPageUnavailable(requestId,
                        description == null ? "网页加载失败" : description);
            }
        }

        @Override public void onReceivedHttpError(WebView view, WebResourceRequest request,
                WebResourceResponse response) {
            if (Build.VERSION.SDK_INT < 23 || this != sourceClient || view != webView
                    || !pageActive || !request.isForMainFrame()) return;
            pageLoadTerminalGeneration = pageLoadGeneration;
            setLoadingVisible(false);
            if (listener != null) listener.onPageUnavailable(requestId,
                    "HTTP " + response.getStatusCode());
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            // Fragment-only navigation may commit without onPageStarted. It must
            // release the barrier too; otherwise the reused tab stays "loading".
            if (this == sourceClient && view == webView && pageActive && awaitingDocument
                    && navigationDocument(url).equals(expectedDocument)
                    && safe(url).equals(view.getUrl())) onPageStarted(view, url, null);
            if (this == sourceClient && view == webView && pageActive && requestId >= 0
                    && !awaitingDocument && !isBlankPage(url) && sameDocument(url, view.getUrl())) {
                // Keep the current address in sync for redirects and single-page sites
                // that move from one route to another without reopening the channel.
                if (!url.equals(pageUrl)) resetResourcePage(url);
                pageUrl = url;
                tabBar.updateActive(url, view.getTitle());
                controllerPageTitle = safe(view.getTitle());
                scheduleDesktopViewport(0L);
                scheduleDesktopViewport(300L);
            }
            super.doUpdateVisitedHistory(view, url, isReload);
        }
    }

    private void injectJavascriptCompatibility(WebView view) {
        compatibilityInjectionCount++;
        WebFullscreenCompatibility.apply(view);
        WebAudioCompatibility.apply(view);
        if (!isPageVisible()) setMultimediaPaused(true);
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

    static boolean isUserScriptInstallUrl(String value) {
        if (value == null) return false;
        try {
            Uri uri = Uri.parse(value.trim());
            String scheme = uri.getScheme();
            String path = uri.getPath();
            boolean web = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            if (web && path != null && path.toLowerCase(Locale.US).endsWith(".user.js")) {
                return true;
            }
            // Greasy Fork mirrors wrap the actual install URL in this redirect's
            // fragment. Recognize only that known wrapper, not arbitrary fragments.
            return web && "home.greasyfork.org.cn".equalsIgnoreCase(uri.getHost())
                    && "/l".equals(path) && uri.getFragment() != null
                    && isUserScriptInstallUrl(uri.getFragment());
        } catch (RuntimeException invalidUrl) {
            return false;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density
                * interfaceScale);
    }
}
