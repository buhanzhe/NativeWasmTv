package xiao.bu.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/** Execution only: the same program and host are used by both engines. UI-thread owned. */
final class Ku9ScriptEngine {
    private static final String URL = "https://ntv.local/ku9/";
    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private Ku9Host active;
    private Runnable timeout;
    private int generation;

    Ku9ScriptEngine(Activity activity) { this.activity = activity; }

    void execute(final String program, final Ku9Host host, boolean browser) {
        clearActive();
        active = host;
        final int token = ++generation;
        timeout = () -> {
            if (active == host) host.fail("脚本解析超时");
        };
        handler.postDelayed(timeout, 30000L);
        Log.i("Ku9ScriptEngine", "engine=" + (browser ? "WebView" : "QuickJS"));
        if (!browser) {
            destroyWebView();
            new Thread(() -> {
                long started = android.os.SystemClock.elapsedRealtime();
                try {
                    if (!host.isCancelled()) NativeQuickJs.execute(program, host);
                    if (!host.isCancelled()) host.fail("脚本没有返回播放结果");
                } catch (Throwable error) {
                    host.fail("QuickJS: " + error.toString());
                } finally {
                    handler.post(() -> { if (active == host) removeTimeout(); });
                    Log.i("Ku9ScriptEngine", "QuickJS elapsedMs="
                            + (android.os.SystemClock.elapsedRealtime() - started));
                }
            }, "ku9-quickjs").start();
            return;
        }
        // Before API 17 addJavascriptInterface exposes unrelated public Java methods.
        if (Build.VERSION.SDK_INT < 17) {
            host.fail("此脚本需要浏览器能力，Android 4.2 以下无法安全运行；请使用纯 Ku9 脚本");
            removeTimeout();
            return;
        }
        try {
            ensureWebView();
            webView.addJavascriptInterface(host, "NtvCjsBridge");
            WebViewRecovery.attach(webView, new WebViewClient() {
                private boolean executed;
                @Override public void onPageFinished(WebView view, String url) {
                    if (executed || token != generation || active != host || host.isCancelled()
                            || view != webView || !URL.equals(url)) return;
                    executed = true;
                    if (Build.VERSION.SDK_INT >= 19) view.evaluateJavascript(program, null);
                    else view.loadUrl("javascript:" + program);
                }
                @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    return true; // The executor never navigates to an untrusted document.
                }
            }, (failed, crashed) -> {
                if (failed == webView) {
                    webView = null;
                    removeTimeout();
                    host.fail("脚本浏览器进程已退出");
                }
            });
            webView.loadDataWithBaseURL(URL,
                    "<!doctype html><html><head><meta charset='utf-8'></head><body></body></html>",
                    "text/html", "UTF-8", null);
        } catch (RuntimeException error) {
            host.fail("系统 WebView 不可用: " + error.toString());
            removeTimeout();
            destroyWebView();
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void ensureWebView() {
        if (webView != null) return;
        webView = new WebView(activity);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setDatabaseEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        if (Build.VERSION.SDK_INT >= 21) settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.setAlpha(0f);
        webView.setTranslationX(-10000f);
        webView.setTranslationY(-10000f);
        ((ViewGroup) activity.findViewById(android.R.id.content)).addView(webView,
                new FrameLayout.LayoutParams(1, 1));
    }

    private void removeTimeout() {
        if (timeout != null) handler.removeCallbacks(timeout);
        timeout = null;
    }

    private void clearActive() {
        removeTimeout();
        if (active != null) active.abort();
        active = null;
    }

    void cancel() {
        ++generation;
        clearActive();
        destroyWebView();
    }

    private void destroyWebView() {
        if (webView == null) return;
        WebView old = webView;
        webView = null;
        old.stopLoading();
        old.removeJavascriptInterface("NtvCjsBridge");
        if (old.getParent() instanceof ViewGroup) ((ViewGroup) old.getParent()).removeView(old);
        old.destroy();
    }
}
