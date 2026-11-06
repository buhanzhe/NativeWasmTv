package xiao.bu.tv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Offline YouTube DOM fixture in the device's system WebView; no account or media requests. */
public final class YouTubeFullscreenInstrumentation extends Instrumentation {
    private final CountDownLatch completed = new CountDownLatch(1);
    private volatile String result;
    public final class Capture {
        @JavascriptInterface public void result(String value) { result = value; completed.countDown(); }
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle report = new Bundle(); Activity activity = null; WebView[] page = {null};
        int status = Activity.RESULT_CANCELED;
        try {
            activity = startActivitySync(new Intent(getTargetContext(), ManagementActivity.class)
                    .putExtra("management_url", "about:blank").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            String fixture;
            try (InputStream in = getContext().getAssets().open("youtube-fullscreen-check.js")) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
                while ((n = in.read(b)) != -1) out.write(b, 0, n);
                fixture = out.toString("UTF-8");
            }
            final Activity host = activity;
            final String html = "<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'>"
                    + "<body><script>" + WebTvPlaybackPolicy.script(getTargetContext(), false)
                    + "</script><script>" + fixture + "</script></body>";
            runOnMainSync(() -> {
                page[0] = new WebView(host); page[0].getSettings().setJavaScriptEnabled(true);
                page[0].addJavascriptInterface(new Capture(), "YouTubeCheck");
                host.setContentView(page[0]);
                page[0].loadDataWithBaseURL("https://www.youtube.com/watch?v=fixture", html, "text/html", "UTF-8", null);
            });
            if (!completed.await(25, TimeUnit.SECONDS)) throw new AssertionError("YouTube WebView fixture timed out");
            if (!new JSONObject(result).optBoolean("pass")) throw new AssertionError(result);
            report.putString("result", "PASS offline YouTube system WebView fixture: " + result);
            status = Activity.RESULT_OK;
        } catch (Throwable error) { report.putString("error", android.util.Log.getStackTraceString(error)); }
        finally {
            final Activity host = activity;
            runOnMainSync(() -> { if (page[0] != null) page[0].destroy(); if (host != null) host.finish(); });
        }
        finish(status, report);
    }
}
