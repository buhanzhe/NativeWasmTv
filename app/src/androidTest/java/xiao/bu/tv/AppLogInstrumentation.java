package xiao.bu.tv;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/** Runs in an isolated test app; verifies the packaged native library and file sink. */
public final class AppLogInstrumentation extends Instrumentation {
    private final org.json.JSONObject metrics = new org.json.JSONObject();
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        IjkMediaPlayer player = null;
        LocalControlServer server = null;
        try {
            AppLog.initialize(getTargetContext());
            AppLog.loadIjkLibraries();
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (!AppLog.note().contains("包含 IJK") && SystemClock.elapsedRealtime() < deadline)
                Thread.sleep(20);
            if (!AppLog.note().contains("包含 IJK")) throw new AssertionError(AppLog.note());
            AppLog.i("AppLogCheck", "java-log-check 中文");
            IjkMediaPlayer.loadLibrariesOnce(null);
            player = new IjkMediaPlayer();
            player.setDataSource("/missing-ntv-app-log-check.mp4");
            player.prepareAsync();
            deadline = SystemClock.elapsedRealtime() + 5000;
            String snapshot;
            do {
                Thread.sleep(50);
                snapshot = AppLog.snapshot();
            } while (!snapshot.contains("IJKMEDIA:") && SystemClock.elapsedRealtime() < deadline);
            if (!snapshot.contains("java-log-check 中文") || !snapshot.contains("IJKMEDIA:"))
                throw new AssertionError("Missing Java or native log: " + snapshot);
            LocalControlServer.Listener listener = (LocalControlServer.Listener) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] { LocalControlServer.Listener.class },
                    (proxy, method, args) -> null);
            server = new LocalControlServer(listener);
            server.start();
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(
                    "http://127.0.0.1:" + server.getPort() + "/api/logs?filter=all&download=1").openConnection();
            connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
            try {
                if (connection.getResponseCode() != 200
                        || !connection.getHeaderField("Content-Disposition").contains(".txt"))
                    throw new AssertionError("Download response missing");
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                try (java.io.InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[4096]; int count;
                    while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                }
                String downloaded = bytes.toString("UTF-8");
                if (!downloaded.contains("java-log-check 中文") || !downloaded.contains("IJKMEDIA:"))
                    throw new AssertionError("Download omitted Java/native logs");
            } finally { connection.disconnect(); }
            player.release(); player = null;
            measurePerformance();
            result.putString("metrics", metrics.toString());
            result.putString("result", "PASS Java/IJK native file capture and HTTP text download");
            finish(android.app.Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("metrics", metrics.toString());
            result.putString("error", android.util.Log.getStackTraceString(error));
            finish(android.app.Activity.RESULT_CANCELED, result);
        } finally { if (server != null) server.close(); if (player != null) player.release(); }
    }

    private void measurePerformance() throws Exception {
        long cpu = android.os.Process.getElapsedCpuTime();
        Thread.sleep(3000);
        metrics.put("idleCpuMsPer3s", android.os.Process.getElapsedCpuTime() - cpu);
        long[] times = new long[2000];
        long callerCpu = android.os.Debug.threadCpuTimeNanos();
        long started = System.nanoTime();
        for (int i = 0; i < times.length; i++) {
            long before = System.nanoTime();
            AppLog.i("AppLogPerf", "test message " + i);
            times[i] = System.nanoTime() - before;
        }
        metrics.put("info2000ElapsedMs", (System.nanoTime() - started) / 1000000.0);
        metrics.put("info2000CallerCpuMs", (android.os.Debug.threadCpuTimeNanos() - callerCpu) / 1000000.0);
        java.util.Arrays.sort(times);
        metrics.put("infoCallP95Us", times[1900] / 1000.0);
        metrics.put("infoCallMaxMs", times[1999] / 1000000.0);
        AppLog.snapshot();
        java.io.File fixture = new java.io.File("/data/local/tmp/ntv-app-log-perf.mp3");
        final IjkMediaPlayer[] audio = new IjkMediaPlayer[1];
        java.util.concurrent.CountDownLatch prepared = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> failure = new java.util.concurrent.atomic.AtomicReference<String>();
        runOnMainSync(() -> {
            try {
                audio[0] = new IjkMediaPlayer();
                audio[0].setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0);
                audio[0].setOnPreparedListener(mp -> { mp.start(); prepared.countDown(); });
                audio[0].setOnErrorListener((mp, what, extra) -> { failure.set(what + "/" + extra); prepared.countDown(); return true; });
                audio[0].setDataSource(fixture.getAbsolutePath()); audio[0].prepareAsync();
            } catch (Exception error) { failure.set(error.toString()); prepared.countDown(); }
        });
        try {
            if (!prepared.await(8, java.util.concurrent.TimeUnit.SECONDS) || failure.get() != null)
                throw new AssertionError("Audio prepare failed: " + failure.get());
            Thread.sleep(500);
            long position = audio[0].getCurrentPosition();
            cpu = android.os.Process.getElapsedCpuTime();
            Thread.sleep(3000);
            metrics.put("playbackCpuMsPer3s", android.os.Process.getElapsedCpuTime() - cpu);
            metrics.put("playbackClockAdvanceMs", audio[0].getCurrentPosition() - position);
            position = audio[0].getCurrentPosition();
            cpu = android.os.Process.getElapsedCpuTime();
            started = SystemClock.elapsedRealtime();
            for (int i = 0; i < 300; i++) {
                AppLog.i("AppLogPerf", "playback stress " + i);
                Thread.sleep(10);
            }
            long advance = audio[0].getCurrentPosition() - position;
            metrics.put("loggingPlaybackCpuMs", android.os.Process.getElapsedCpuTime() - cpu);
            metrics.put("loggingPlaybackElapsedMs", SystemClock.elapsedRealtime() - started);
            metrics.put("loggingPlaybackClockAdvanceMs", advance);
            if (advance < 2500 || failure.get() != null) throw new AssertionError("Playback stalled: " + advance);
            android.os.Debug.MemoryInfo memory = new android.os.Debug.MemoryInfo();
            android.os.Debug.getMemoryInfo(memory);
            metrics.put("totalPssKb", memory.getTotalPss());
        } finally { if (audio[0] != null) audio[0].release(); }
    }
}
