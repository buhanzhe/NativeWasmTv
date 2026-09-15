package xiao.bu.tv;

import android.app.Instrumentation;
import android.os.Bundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local fixtures served on port 19981, forwarded with adb reverse. */
public final class SniffedMediaProbeInstrumentation extends Instrumentation {
    private SniffedMediaProbe probe;
    private SniffedMediaProbe.Result inspect(String path) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        SniffedMediaProbe.Result[] result = {null};
        probe.submit("http://127.0.0.1:19981/" + path, "http://127.0.0.1:19981/", "nTv probe test", "",
                value -> { result[0] = value; done.countDown(); });
        if (!done.await(15, TimeUnit.SECONDS)) throw new AssertionError("Probe callback timeout: " + path);
        return result[0];
    }
    private void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private Object field(Object owner, String name) throws Exception {
        java.lang.reflect.Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private void waitResource(android.app.Activity activity, String suffix) throws Exception {
        java.lang.reflect.Method method = MainActivity.class.getDeclaredMethod("sniffedResourcesJson"); method.setAccessible(true);
        long until = android.os.SystemClock.elapsedRealtime() + 6000;
        while (android.os.SystemClock.elapsedRealtime() < until) {
            org.json.JSONArray resources = (org.json.JSONArray) method.invoke(activity);
            if (resources.length() == 1 && resources.getJSONObject(0).getString("url").endsWith(suffix)) return;
            android.os.SystemClock.sleep(100);
        }
        throw new AssertionError("Current page resource mismatch: " + method.invoke(activity));
    }
    private void checkNavigation() throws Exception {
        MainActivity activity = (MainActivity) startActivitySync(new android.content.Intent(getTargetContext(), MainActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            android.os.SystemClock.sleep(1000);
            WebSourceView source = (WebSourceView) field(activity, "webSourceView");
            java.lang.reflect.Field id = MainActivity.class.getDeclaredField("playRequestId"); id.setAccessible(true);
            java.lang.reflect.Field manual = MainActivity.class.getDeclaredField("manualWebPlaybackRequestId"); manual.setAccessible(true);
            int request = id.getInt(activity) + 1;
            runOnMainSync(() -> {
                try { id.setInt(activity, request); manual.setInt(activity, request); }
                catch (Exception error) { throw new RuntimeException(error); }
                source.open(request, "http://127.0.0.1:19981/a.html");
            });
            waitResource(activity, "video.mp4");
            android.webkit.WebView web = (android.webkit.WebView) field(source, "webView");
            runOnMainSync(() -> web.loadUrl("http://127.0.0.1:19981/b.html"));
            waitResource(activity, "audio.mp3");
            runOnMainSync(() -> web.loadUrl("http://127.0.0.1:19981/a.html"));
            waitResource(activity, "video.mp4");
        } finally { runOnMainSync(activity::finish); }
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle output = new Bundle(); int code = -1;
        probe = new SniffedMediaProbe();
        try {
            SniffedMediaProbe.Result video = inspect("video.mp4");
            check("video".equals(video.type) && video.width == 320 && video.height == 180
                    && video.durationMs >= 2900 && video.bitrate > 0, "MP4: " + video.json());
            SniffedMediaProbe.Result audio = inspect("audio.mp3");
            check("audio".equals(audio.type) && audio.durationMs >= 2900 && audio.bitrate > 0, "MP3: " + audio.json());
            SniffedMediaProbe.Result vod = inspect("vod.m3u8");
            check("video".equals(vod.type) && vod.durationMs >= 2900 && vod.width == 320 && vod.bitrate > 1000, "VOD: " + vod.json());
            SniffedMediaProbe.Result live = inspect("master.m3u8");
            check("live".equals(live.type) && live.durationMs == 0 && live.width == 320 && live.bitrate > 0, "Live: " + live.json());
            SniffedMediaProbe.Result missing = inspect("missing.mp4");
            check("unavailable".equals(missing.status), "Failed probe removed fallback");
            AtomicBoolean stale = new AtomicBoolean();
            probe.submit("http://127.0.0.1:19981/slow.mp4", "", "nTv", "", result -> stale.set(true));
            probe.clear();
            inspect("audio.mp3");
            check(!stale.get(), "Old-page probe callback escaped reset");
            checkNavigation();
            output.putString("stream", "PASS MP4 " + video.json() + " MP3 " + audio.json()
                    + " HLS VOD " + vod.json() + " HLS live " + live.json() + " failure, page reset and actual A/B/A WebView navigation\n");
        } catch (Throwable error) { code = 0; output.putString("stream", android.util.Log.getStackTraceString(error)); }
        finally { probe.close(); }
        finish(code, output);
    }
}
