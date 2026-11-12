package xiao.bu.tv;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import xiao.bu.tv.AppLog;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Serial, cancellable renderer drain. All WebView calls remain on its UI thread. */
final class WebViewShutdown {
    interface Completion { void onComplete(boolean stopped); }
    private static final String TAG = "WebViewShutdown";
    // Logical close stops playback without unloading the document or its surface.
    private static final String PAUSE_MEDIA = "(function(){function stop(w){try{"
            + "function hush(v){if(!v.muted)v.muted=true;if(!v.paused)v.pause();}"
            + "var p=w.HTMLMediaElement&&w.HTMLMediaElement.prototype;"
            + "if(p)p.play=function(){hush(this);return w.Promise?w.Promise.resolve():undefined};"
            + "w.document.addEventListener('play',function(e){hush(e.target)},true);"
            + "var m=w.document.querySelectorAll('video,audio');for(var i=0;i<m.length;i++)hush(m[i]);"
            + "var f=w.document.querySelectorAll('iframe');for(var i=0;i<f.length;i++)stop(f[i].contentWindow);"
            + "}catch(e){}}stop(window);})();";
    // Stop media in same-origin frames too. Clearing a MediaStream/MediaSource before
    // detaching a playing surface gives the renderer a chance to release its decoder.
    static final String QUIESCE = "(function(){function stop(w){try{var d=w.document;"
            + "var p=w.HTMLMediaElement&&w.HTMLMediaElement.prototype;"
            + "if(p)p.play=function(){return w.Promise?w.Promise.resolve():undefined};"
            + "var m=d.querySelectorAll('video,audio');for(var i=0;i<m.length;i++){try{"
            + "var v=m[i];v.muted=true;v.pause();if(v.srcObject){var t=v.srcObject.getTracks?v.srcObject.getTracks():[];"
            + "for(var j=0;j<t.length;j++)t[j].stop();v.srcObject=null;}"
            + "v.removeAttribute('src');var s=v.querySelectorAll('source');"
            + "for(var j=0;j<s.length;j++)s[j].removeAttribute('src');v.load();}catch(e){}}"
            + "var f=d.querySelectorAll('iframe');for(var i=0;i<f.length;i++)stop(f[i].contentWindow);"
            + "}catch(e){}}stop(window);})();";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final WebView view;
    private final Runnable ready;
    private final Runnable gone;
    private boolean cancelled, navigating, finished;
    private boolean awaitingHandoff;
    private Boolean stopped;
    private final java.util.ArrayList<Completion> completions = new java.util.ArrayList<>();
    private final Runnable navigate = this::navigateBlank;
    private final Runnable deadline = () -> finish(true);
    private final Runnable handoffDeadline = this::releaseAfterHandoff;

    WebViewShutdown(WebView view, Runnable ready, Runnable gone) {
        this.view = view; this.ready = ready; this.gone = gone;
    }

    void deferUntilHandoff() { awaitingHandoff = true; }
    boolean isAwaitingHandoff() { return awaitingHandoff && !cancelled && !finished; }

    void releaseAfterHandoff() {
        if (!isAwaitingHandoff()) return;
        awaitingHandoff = false;
        handler.removeCallbacks(handoffDeadline);
        drain();
    }

    void start() {
        view.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsBeforeUnload(WebView v, String url,
                    String message, JsResult result) { result.confirm(); return true; }
            @Override public boolean onJsAlert(WebView v, String url,
                    String message, JsResult result) { result.cancel(); return true; }
            @Override public boolean onJsPrompt(WebView v,String url,String message,String value,android.webkit.JsPromptResult result){result.cancel();return true;}
            @Override public boolean onJsConfirm(WebView v, String url,
                    String message, JsResult result) { result.cancel(); return true; }
        });
        WebViewRecovery.attach(view, new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                if (navigating && "about:blank".equals(url)) handler.post(() -> finish(false));
            }
        }, (dead, crashed) -> {
            if (finished) return;
            finished = true;
            handler.removeCallbacks(navigate);
            handler.removeCallbacks(deadline);
            handler.removeCallbacks(handoffDeadline);
            gone.run(); // WebViewRecovery owns destruction of a dead renderer.
            complete(true);
        });
        if (awaitingHandoff) {
            // Network/renderer failures must not retain closed tabs indefinitely.
            handler.postDelayed(handoffDeadline, 5000L);
            if (Build.VERSION.SDK_INT >= 19) view.evaluateJavascript(PAUSE_MEDIA, null);
            else view.loadUrl("javascript:" + PAUSE_MEDIA);
        } else drain();
    }

    private void drain() {
        // Never await renderer callbacks on the UI thread. A timeout bounds
        // retention, but is not evidence that the document stopped producing audio.
        handler.postDelayed(navigate, 300L);
        handler.postDelayed(deadline, 2000L);
        // Do not resume a hidden media session just to tear it down: Chromium can
        // reacquire permanent audio focus and mute the native player. onPause does
        // not pause JavaScript; drain/navigation can run without reviving playback.
        if (Build.VERSION.SDK_INT >= 19) {
            view.evaluateJavascript(QUIESCE, value -> navigateBlank());
        } else {
            view.loadUrl("javascript:" + QUIESCE);
            handler.post(navigate);
        }
    }

    void cancel() {
        cancelled = true;
        handler.removeCallbacks(navigate);
        handler.removeCallbacks(deadline);
        handler.removeCallbacks(handoffDeadline);
        complete(false);
    }

    void whenComplete(Completion callback) {
        if (stopped != null) callback.onComplete(stopped);
        else completions.add(callback);
    }

    private void complete(boolean success) {
        if (stopped != null) return;
        stopped = success;
        java.util.ArrayList<Completion> pending = new java.util.ArrayList<>(completions);
        completions.clear();
        for (Completion callback : pending) callback.onComplete(success);
    }

    private void navigateBlank() {
        if (cancelled || finished || navigating) return;
        navigating = true;
        handler.removeCallbacks(navigate);
        view.loadUrl("about:blank");
    }

    private void finish(boolean timedOut) {
        if (cancelled || finished) return;
        finished = true;
        handler.removeCallbacks(navigate);
        handler.removeCallbacks(deadline);
        if (timedOut) AppLog.w(TAG, "Renderer drain timed out; completing bounded cleanup");
        try { ready.run(); }
        finally { complete(!timedOut); }
    }
}
