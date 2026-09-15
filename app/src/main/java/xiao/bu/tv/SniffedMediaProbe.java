package xiao.bu.tv;

import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;
import tv.danmaku.ijk.media.player.IjkMediaMeta;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/** One bounded, silent probe at a time; never plays or renders the discovered media. */
final class SniffedMediaProbe {
    interface Callback { void complete(Result result); }
    static final class Result {
        String type = "unknown", status = "unavailable";
        long durationMs, bitrate;
        int width, height;
        boolean manifestKnown, live, bitrateEstimated;
        String variant, sampleSegment;
        double sampleSeconds;
        JSONObject json() throws JSONException {
            return new JSONObject().put("type", type).put("probeStatus", status)
                    .put("durationMs", durationMs).put("bitrate", bitrate)
                    .put("width", width).put("height", height).put("bitrateEstimated", bitrateEstimated);
        }
    }
    private final AtomicInteger generation = new AtomicInteger();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(30), runnable -> {
                Thread thread = new Thread(runnable, "sniffed-media-probe");
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            }, new ThreadPoolExecutor.DiscardPolicy());

    SniffedMediaProbe() { worker.allowCoreThreadTimeOut(true); }
    void clear() { generation.incrementAndGet(); worker.getQueue().clear(); }
    void close() { clear(); worker.shutdownNow(); }
    void submit(String url, String pageUrl, String agent, String cookies, Callback callback) {
        final int expected = generation.get();
        worker.execute(() -> {
            if (expected != generation.get()) return;
            Result result = new Result();
            long deadline = SystemClock.elapsedRealtime() + 10000;
            IjkMediaPlayer player = null;
            String probeUrl = url;
            try {
                if (url.toLowerCase(java.util.Locale.US).matches(".*(?:\\.m3u8(?:[?#].*)?|[?&](?:format|type)=m3u8(?:[&#].*)?)$")) {
                    String target = url;
                    for (int depth = 0; depth < 3 && expected == generation.get(); depth++) {
                        String manifest = readManifest(target, pageUrl, agent, cookies, expected, deadline);
                        inspectManifest(manifest, result);
                        if (result.variant == null) {
                            if (result.bitrate <= 0 && result.sampleSegment != null && result.sampleSeconds > 0
                                    && expected == generation.get() && SystemClock.elapsedRealtime() < deadline) {
                                result.bitrate = segmentBitrate(new URL(new URL(target), result.sampleSegment).toString(),
                                        pageUrl, agent, cookies, result.sampleSeconds);
                                result.bitrateEstimated = result.bitrate > 0;
                            }
                            break;
                        }
                        target = new URL(new URL(target), result.variant).toString();
                        probeUrl = target;
                        result.variant = null;
                    }
                }
            } catch (Exception ignored) { /* IJK may support a transport the Java HTTP stack cannot open. */ }
            if (expected != generation.get()) return;
            try {
                IjkMediaPlayer.loadLibrariesOnce(null);
                player = new IjkMediaPlayer();
                final CountDownLatch ready = new CountDownLatch(1);
                final boolean[] prepared = {false};
                player.setOnPreparedListener(mp -> { prepared[0] = true; ready.countDown(); });
                player.setOnErrorListener((mp, what, extra) -> { ready.countDown(); return true; });
                player.setVolume(0, 0);
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0);
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 0);
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0);
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rw_timeout", 3000000);
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 3000000);
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 512 * 1024);
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 1500000);
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "headers", headers(pageUrl, agent, cookies));
                player.setDataSource(probeUrl);
                player.prepareAsync();
                while (expected == generation.get() && SystemClock.elapsedRealtime() < deadline) {
                    if (ready.await(100, TimeUnit.MILLISECONDS)) break;
                }
                if (expected != generation.get()) return;
                if (prepared[0]) {
                    IjkMediaMeta meta = IjkMediaMeta.parse(player.getMediaMeta());
                    if (meta != null) applyMeta(result, meta);
                }
            } catch (Exception | LinkageError ignored) {
                // A failed probe must not remove the resource or prevent explicit playback.
            } finally {
                if (player != null) {
                    player.setOnPreparedListener(null); player.setOnErrorListener(null);
                    try { player.release(); } catch (RuntimeException ignored) { }
                }
            }
            if (expected == generation.get()) callback.complete(result);
        });
    }

    private static void applyMeta(Result result, IjkMediaMeta meta) {
        if (meta.mVideoStream == null && meta.mAudioStream == null) return;
        result.status = "ready";
        if (!result.manifestKnown) result.durationMs = Math.max(0, meta.mDurationUS / 1000);
        if (meta.mVideoStream != null) {
            if (meta.mVideoStream.mWidth > 0) result.width = meta.mVideoStream.mWidth;
            if (meta.mVideoStream.mHeight > 0) result.height = meta.mVideoStream.mHeight;
            result.type = result.manifestKnown ? (result.live ? "live" : "video")
                    : (result.durationMs > 0 ? "video" : "unknown");
        } else result.type = result.manifestKnown && result.live ? "live" : "audio";
        long bitrate = meta.mBitrate;
        if (bitrate <= 0) {
            if (meta.mVideoStream != null) bitrate = Math.max(0, meta.mVideoStream.mBitrate);
            if (meta.mAudioStream != null) bitrate += Math.max(0, meta.mAudioStream.mBitrate);
        }
        // Some vendor IJK HLS builds report a tiny container bitrate (e.g. 442 bps).
        // Prefer the rendition's BANDWIDTH or a segment size/duration estimate.
        if (bitrate >= 1000 && (!result.manifestKnown || result.bitrate <= 0)) {
            result.bitrate = bitrate; result.bitrateEstimated = false;
        }
    }

    static void inspectManifest(String text, Result result) {
        if (!text.trim().startsWith("#EXTM3U")) return;
        String[] lines = text.replace("\r", "").split("\n");
        String variantInfo = null;
        double seconds = 0, segmentSeconds = 0;
        boolean segments = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-STREAM-INF:")) variantInfo = line;
            else if (variantInfo != null && line.length() > 0 && !line.startsWith("#")) {
                result.variant = line;
                Matcher size = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)").matcher(variantInfo);
                if (size.find()) { result.width = Integer.parseInt(size.group(1)); result.height = Integer.parseInt(size.group(2)); }
                Matcher rate = Pattern.compile("(?:[:,])(?:AVERAGE-)?BANDWIDTH=(\\d+)").matcher(variantInfo);
                if (rate.find()) result.bitrate = Long.parseLong(rate.group(1));
                return;
            } else if (line.startsWith("#EXTINF:")) {
                try { segmentSeconds = Double.parseDouble(line.substring(8).split(",", 2)[0]);
                    if (segmentSeconds > 0 && !Double.isInfinite(segmentSeconds)) { seconds += segmentSeconds; segments = true; } }
                catch (NumberFormatException ignored) { }
            } else if (segments && result.sampleSegment == null && !line.startsWith("#") && !line.isEmpty()) {
                result.sampleSegment = line; result.sampleSeconds = segmentSeconds;
            }
        }
        if (segments) {
            result.manifestKnown = true;
            result.live = !text.contains("#EXT-X-ENDLIST");
            result.durationMs = result.live ? 0 : Math.max(0, (long) (seconds * 1000));
            if (result.live) result.type = "live";
        }
    }

    private static long segmentBitrate(String url, String page, String agent, String cookies, double seconds) {
        if (!(seconds > 0) || Double.isInfinite(seconds)) return 0;
        HttpURLConnection connection = null;
        try {
            connection = NetworkClient.open(new URL(url));
            connection.setConnectTimeout(2000); connection.setReadTimeout(2000);
            connection.setRequestMethod("HEAD");
            connection.setRequestProperty("Referer", clean(page));
            connection.setRequestProperty("User-Agent", clean(agent));
            connection.setRequestProperty("Cookie", clean(cookies));
            if (connection.getResponseCode() != 200) return 0;
            long bytes = connection.getContentLength();
            return bytes > 0 ? (long) (bytes * 8d / seconds) : 0;
        } catch (Exception ignored) { return 0; }
        finally { if (connection != null) connection.disconnect(); }
    }

    private String readManifest(String url, String page, String agent, String cookies, int expected, long deadline) throws Exception {
        HttpURLConnection connection = NetworkClient.open(new URL(url));
        try {
            connection.setConnectTimeout(2000); connection.setReadTimeout(2000);
            connection.setRequestProperty("Referer", clean(page));
            connection.setRequestProperty("User-Agent", clean(agent));
            connection.setRequestProperty("Cookie", clean(cookies));
            if (connection.getResponseCode() != 200) return "";
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while (expected == generation.get() && SystemClock.elapsedRealtime() < deadline
                        && (count = input.read(buffer)) >= 0) {
                    if (bytes.size() + count > 256 * 1024) return "";
                    bytes.write(buffer, 0, count);
                }
                return bytes.toString("UTF-8");
            }
        } finally { connection.disconnect(); }
    }
    private static String clean(String text) { return text == null ? "" : text.replace("\r", "").replace("\n", ""); }
    private static String headers(String page, String agent, String cookies) {
        return "Referer: " + clean(page) + "\r\nUser-Agent: " + clean(agent) + "\r\nCookie: " + clean(cookies) + "\r\n";
    }
}
