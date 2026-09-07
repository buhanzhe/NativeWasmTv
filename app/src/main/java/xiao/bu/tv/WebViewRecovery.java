package xiao.bu.tv;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Build;
import android.util.Log;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** A renderer exit must be handled by every associated WebView, including hidden ones. */
final class WebViewRecovery {
    interface Listener {
        // Invalidate references/tasks only; never navigate or pause the dead view.
        void onRendererGone(WebView view, boolean crashed);
    }

    static void attach(WebView view, WebViewClient client, Listener listener) {
        // Isolate the API 26 callback types from Android 4.x class loading.
        view.setWebViewClient(Build.VERSION.SDK_INT >= 26
                ? new Api26Client(client, listener) : client);
    }

    @TargetApi(26)
    private static final class Api26Client extends WebViewClient {
        private final WebViewClient delegate;
        private final Listener listener;
        private volatile boolean gone;

        Api26Client(WebViewClient delegate, Listener listener) {
            this.delegate = delegate;
            this.listener = listener;
        }

        @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            if (gone) return true;
            gone = true;
            Log.e("WebViewRecovery", "Renderer gone: crashed=" + detail.didCrash()
                    + " priority=" + detail.rendererPriorityAtExit()
                    + " owner=" + delegate.getClass().getName());
            try {
                listener.onRendererGone(view, detail.didCrash());
            } finally {
                ViewParent parent = view.getParent();
                if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(view);
                view.destroy();
            }
            return true;
        }

        // Forward all callbacks currently overridden by the app's clients. Modern
        // overloads retain WebViewClient's dispatch to their legacy counterparts.
        @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
            return gone || delegate.shouldOverrideUrlLoading(v, url);
        }
        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            return gone || delegate.shouldOverrideUrlLoading(v, r);
        }
        @Override public void onPageStarted(WebView v, String url, Bitmap icon) {
            if (!gone) delegate.onPageStarted(v, url, icon);
        }
        @Override public void onPageFinished(WebView v, String url) {
            if (!gone) delegate.onPageFinished(v, url);
        }
        @Override public void onLoadResource(WebView v, String url) {
            if (!gone) delegate.onLoadResource(v, url);
        }
        @Override public WebResourceResponse shouldInterceptRequest(WebView v, String url) {
            return gone ? null : delegate.shouldInterceptRequest(v, url);
        }
        @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            return gone ? null : delegate.shouldInterceptRequest(v, r);
        }
        @Override public void onReceivedError(WebView v, int code, String text, String url) {
            if (!gone) delegate.onReceivedError(v, code, text, url);
        }
        @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
            if (!gone) delegate.onReceivedError(v, r, e);
        }
        @Override public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) {
            if (gone) h.cancel(); else delegate.onReceivedSslError(v, h, e);
        }
        @Override public void doUpdateVisitedHistory(WebView v, String url, boolean reload) {
            if (!gone) delegate.doUpdateVisitedHistory(v, url, reload);
        }
    }
}
