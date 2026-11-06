package xiao.bu.tv;

import android.os.SystemClock;
import xiao.bu.tv.AppLog;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/** Bounded IJK/FFmpeg header inspection of bytes already delivered by the proxy. */
final class CachedVideoMetadataProbe {
    private static final int MAX_BYTES = 384 * 1024;
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1,
            10, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(2), work -> {
                Thread thread = new Thread(work, "cached-video-metadata");
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });
    static { WORKER.allowCoreThreadTimeOut(true); }
    private final File directory;
    private volatile VideoStreamDescription description = VideoStreamDescription.UNKNOWN;
    private volatile boolean closed;
    private FutureTask<Void> task;
    private int attempts;
    private long lastAttempt;

    CachedVideoMetadataProbe(File directory) { this(directory, VideoStreamDescription.UNKNOWN); }
    CachedVideoMetadataProbe(File directory, VideoStreamDescription initial) {
        this.directory = directory;
        description = initial;
    }
    VideoStreamDescription description() { return description; }

    synchronized void submit(byte[] bytes) {
        // API 14/15 HLS plays through MediaPlayer. Loading IJK just for advisory
        // metadata can SIGILL on old TV CPUs (Bugly 1139), outside Java catches.
        if (android.os.Build.VERSION.SDK_INT <= 15) return;
        if (closed || directory == null || bytes == null || bytes.length < 188 * 16 || attempts >= 3
                || description.complete() && !"unknown".equals(description.scanType)
                || task != null && !task.isDone()
                || attempts > 0 && SystemClock.elapsedRealtime() - lastAttempt < 1500) return;
        byte[] sample = Arrays.copyOf(bytes, Math.min(bytes.length, MAX_BYTES));
        FutureTask<Void> next = new FutureTask<>(() -> { inspect(sample); return null; });
        try {
            WORKER.execute(next);
            task = next;
            attempts++;
            lastAttempt = SystemClock.elapsedRealtime();
        } catch (RejectedExecutionException busy) {
            // Playback wins. Another already-downloaded segment can retry later.
            next.cancel(false);
        }
    }

    private void inspect(byte[] sample) {
        File file = null;
        IjkMediaPlayer probe = null;
        long started = SystemClock.elapsedRealtime();
        try {
            if (closed || Thread.currentThread().isInterrupted()) return;
            file = File.createTempFile("ijk-source-", ".ts", directory);
            try (FileOutputStream output = new FileOutputStream(file)) { output.write(sample); }
            AppLog.loadIjkLibraries();
            probe = new IjkMediaPlayer();
            CountDownLatch ready = new CountDownLatch(1);
            final boolean[] prepared = {false};
            probe.setOnPreparedListener(player -> { prepared[0] = true; ready.countDown(); });
            probe.setOnErrorListener((player, what, extra) -> { ready.countDown(); return true; });
            probe.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0);
            probe.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "ntv-metadata-only", 1);
            probe.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 0);
            probe.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "an", 1);
            probe.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0);
            probe.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", MAX_BYTES);
            probe.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "threads", 1);
            probe.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", MAX_BYTES);
            // This is a finite local prefix: let demuxing reach its video headers
            // even when audio timestamps advance before the first video packet.
            probe.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 2000000);
            probe.setVolume(0, 0);
            // No Surface and no start(): cannot replace the active hardware or HDR output.
            probe.setDataSource(file.getAbsolutePath());
            probe.prepareAsync();
            while (!closed && SystemClock.elapsedRealtime() - started < 4000) {
                if (ready.await(100, TimeUnit.MILLISECONDS)) break;
            }
            if (!closed && prepared[0]) {
                VideoStreamDescription found = IjkStreamMetrics.videoDescription(probe);
                description = found.withFallback(description);
                AppLog.i("CachedVideoMetadata", "IJK source headers " + description.debugLabel()
                        + " tookMs=" + (SystemClock.elapsedRealtime() - started));
            }
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
        } catch (Exception | LinkageError error) {
            if (!closed) AppLog.w("CachedVideoMetadata", "Source headers unavailable", error);
        } finally {
            if (probe != null) {
                probe.setOnPreparedListener(null);
                probe.setOnErrorListener(null);
                try { probe.release(); } catch (RuntimeException ignored) { }
            }
            if (file != null) file.delete();
        }
    }

    synchronized void close() {
        closed = true;
        if (task != null) {
            task.cancel(true);
            WORKER.remove(task);
        }
    }
}
