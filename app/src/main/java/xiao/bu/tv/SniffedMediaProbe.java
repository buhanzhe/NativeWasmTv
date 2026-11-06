package xiao.bu.tv;

import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
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
    private static final Pattern HLS_URL = Pattern.compile(".*(?:\\.m3u8(?:[?#].*)?|[?&](?:format|type)=m3u8(?:[&#].*)?)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESOLUTION = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)");
    private static final Pattern BANDWIDTH = Pattern.compile("(?:[:,])(?:AVERAGE-)?BANDWIDTH=(\\d+)");
    interface Callback { void complete(Result result); }
    static final class Result {
        String type = "unknown", status = "unavailable";
        String error = "";
        long durationMs, bitrate;
        int width, height;
        String videoCodec = "--";
        float frameRate;
        VideoStreamDescription videoDescription = VideoStreamDescription.UNKNOWN;
        boolean manifestKnown, live, bitrateEstimated;
        String variant, sampleSegment;
        double sampleSeconds;
        JSONObject json() throws JSONException {
            return new JSONObject().put("type", type).put("probeStatus", status).put("probeError", error)
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
        submit(url, pageUrl, agent, cookies, false, callback);
    }
    void submitLightweight(String url, Callback callback) {
        submit(url, null, HlsProxyServer.DEFAULT_USER_AGENT, null, true, callback);
    }
    private void submit(String url, String pageUrl, String agent, String cookies,
            boolean lightweight, Callback callback) {
        final int expected = generation.get();
        worker.execute(() -> {
            if (expected != generation.get()) return;
            Result result = new Result();
            long deadline = SystemClock.elapsedRealtime() + (lightweight ? 4000 : 10000);
            IjkMediaPlayer player = null;
            HlsProxyServer probeProxy = null;
            String probeUrl = url;
            final boolean hls = HLS_URL.matcher(url).matches();
            try {
                try {
                    if (hls || url.regionMatches(true,0,"https://",0,8)) {
                        probeProxy = new HlsProxyServer(null, false, true, true,
                                0, true, "high", 1, 0, 0);
                        probeProxy.setMetadataProbeMode();
                        probeProxy.setWebRequestHeaders(pageUrl, agent, cookies);
                        probeProxy.start();
                        probeUrl = hls ? probeProxy.playerHlsUrl(url) : probeProxy.mediaUrl(url);
                    }
                    if (hls) {
                        // Inspect the same selected rendition and cached manifests
                        // that the native probe will use; avoid a second TLS walk.
                        String target = probeUrl;
                        for (int depth = 0; depth < 3 && expected == generation.get(); depth++) {
                            String manifest = readManifest(target, pageUrl, agent, cookies, expected, deadline);
                            inspectManifest(manifest, result);
                            if (result.variant == null) {
                                if (!lightweight && result.bitrate <= 0 && result.sampleSegment != null && result.sampleSeconds > 0
                                        && expected == generation.get() && SystemClock.elapsedRealtime() < deadline) {
                                    String segment=HlsUri.resolve(java.net.URI.create(target), result.sampleSegment).toString();
                                    if(probeProxy!=null)segment=probeProxy.probeOriginUrl(segment);
                                    result.bitrate = segmentBitrate(segment,
                                            pageUrl, agent, cookies, result.sampleSeconds);
                                    result.bitrateEstimated = result.bitrate > 0;
                                }
                                break;
                            }
                            target = HlsUri.resolve(java.net.URI.create(target), result.variant).toString();
                            result.variant = null;
                        }
                    }
                    else if (java.net.URI.create(url).getPath().toLowerCase(java.util.Locale.US).endsWith(".mp4")
                            && initializationOnly(probeUrl,pageUrl,agent,cookies,deadline)) {
                        result.type="fragment";
                        throw new ManifestFailure("这是直播初始化分片，继续在网页中播放");
                    }
                } catch (Exception error) {
                    result.error = probeFailure(error);
                    if (error instanceof ManifestFailure) throw error;
                    // Native HTTP may still support this transport; only a failed native probe blocks opening.
                }
                if (expected != generation.get()) return;
                if (SystemClock.elapsedRealtime() >= deadline)
                    throw new java.net.SocketTimeoutException("Media probe deadline exceeded");
                AppLog.loadIjkLibraries();
                player = new IjkMediaPlayer();
                final CountDownLatch ready = new CountDownLatch(1);
                final boolean[] prepared = {false};
                player.setOnPreparedListener(mp -> { prepared[0] = true; ready.countDown(); });
                player.setOnErrorListener((mp, what, extra) -> {
                    if (PlaybackHttpError.isForbidden(what, extra)) result.error = PlaybackHttpError.FORBIDDEN_MESSAGE;
                    else if (result.error.length() == 0) result.error = HlsProbeFailure.nativeError(what, extra);
                    ready.countDown(); return true;
                });
                player.setOnNativeInvokeListener((what, values) -> {
                    if ((what == IjkMediaPlayer.OnNativeInvokeListener.EVENT_DID_HTTP_OPEN
                            || what == IjkMediaPlayer.OnNativeInvokeListener.EVENT_DID_HTTP_SEEK) && values != null) {
                        int code = values.getInt(IjkMediaPlayer.OnNativeInvokeListener.ARG_HTTP_CODE);
                        if (code >= 400 && result.error.length() == 0) result.error = httpFailure(code);
                    }
                    return false;
                });
                player.setVolume(0, 0);
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0);
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "ntv-metadata-only", 1);
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 0);
                // Header discovery needs no frame-thread pipeline. Bound the
                // decoder used by find_stream_info on high-resolution sources.
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "threads", 1);
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_frame", "all");
                // The resource card exposes dimensions/tracks, not a frame-rate
                // sample. The native metadata probe fills missing TS dimensions
                // from bounded SPS headers without decoding even a UHD keyframe.
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fpsprobesize", 0);
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0);
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rw_timeout", 3000000);
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 3000000);
                // A UDP probe can join after the first SPS/keyframe. Allow the
                // next GOP before concluding that only the early audio is present.
                boolean rtp = url.regionMatches(true,0,"rtp://",0,6);
                if (rtp) player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "buffer_size", 1024 * 1024);
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", rtp ? 4 * 1024 * 1024 : 512 * 1024);
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", rtp ? 3000000 : 1500000);
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
                    result.videoDescription = IjkStreamMetrics.videoDescription(player);
                    // A short-lived live segment can expire before HEAD, or the
                    // origin can omit Content-Length. Reuse the segment already
                    // fetched for discovery instead of downloading another one.
                    if (result.bitrate <= 0 && probeProxy != null) {
                        long video = Math.max(0, probeProxy.measuredMediaBitrate(true));
                        long audio = Math.max(0, probeProxy.measuredMediaBitrate(false));
                        if (video + audio >= 1000) {
                            result.bitrate = video + audio;
                            result.bitrateEstimated = true;
                        }
                    }
                }
                if (!"ready".equals(result.status) && result.error.length() == 0)
                    result.error = prepared[0] ? "资源没有可播放的音视频轨道" : "资源探测超时";
            } catch (Exception | LinkageError error) {
                if (result.error.length() == 0) result.error = probeFailure(error);
            } finally {
                if (!"ready".equals(result.status) && probeProxy != null
                        && probeProxy.lastPlaybackFailure() != null)
                    result.error = probeFailure(probeProxy.lastPlaybackFailure());
                if (player != null) {
                    player.setOnPreparedListener(null); player.setOnErrorListener(null);
                    try { player.release(); } catch (RuntimeException ignored) { }
                }
                if (probeProxy != null) probeProxy.close();
            }
            // Only the advisory cache can retain a completed result after cancellation.
            // Resource-card callers still discard obsolete page/media results.
            if (expected == generation.get() || lightweight && "ready".equals(result.status)) callback.complete(result);
        });
    }

    private static void applyMeta(Result result, IjkMediaMeta meta) {
        if (meta.mVideoStream == null && meta.mAudioStream == null) return;
        result.status = "ready";
        result.error = "";
        if (!result.manifestKnown) result.durationMs = Math.max(0, meta.mDurationUS / 1000);
        if (meta.mVideoStream != null) {
            String codec = meta.mVideoStream.mCodecName;
            result.videoCodec = "h264".equals(codec) ? "H.264" : "hevc".equals(codec) ? "H.265" : codec;
            if (meta.mVideoStream.mFpsDen > 0)
                result.frameRate = (float) meta.mVideoStream.mFpsNum / meta.mVideoStream.mFpsDen;
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
                Matcher size = RESOLUTION.matcher(variantInfo);
                if (size.find()) { result.width = Integer.parseInt(size.group(1)); result.height = Integer.parseInt(size.group(2)); }
                Matcher rate = BANDWIDTH.matcher(variantInfo);
                if (rate.find()) {
                    long advertised = Long.parseLong(rate.group(1));
                    result.bitrate = advertised >= 1000 ? advertised : 0;
                }
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
            connection = openConnection(url, page, agent, cookies);
            connection.setRequestMethod("HEAD");
            if (connection.getResponseCode() != 200) {
                InputStream error = connection.getErrorStream();
                if (error != null) error.close();
                return 0;
            }
            // HEAD has no payload, but OkHttp still owns a response body. Cancel
            // alone does not close it and can leave a pooled connection leaked.
            try (InputStream body = connection.getInputStream()) {
                long bytes = connection.getContentLength();
                return bytes > 0 ? (long) (bytes * 8d / seconds) : 0;
            }
        } catch (Exception ignored) { return 0; }
        finally { if (connection != null) connection.disconnect(); }
    }

    private String readManifest(String url, String page, String agent, String cookies, int expected, long deadline) throws Exception {
        HttpURLConnection connection = openConnection(url, page, agent, cookies);
        // The local proxy owns TLS and bounded upstream retries. A 2-second
        // client timeout can cancel it before its first HTTP/2 retry completes.
        connection.setReadTimeout((int)Math.max(1L,Math.min(10000L,deadline-SystemClock.elapsedRealtime())));
        try {
            int status = connection.getResponseCode();
            if (status != 200) {
                InputStream error = connection.getErrorStream();
                if (error != null) error.close();
                throw new IOException(httpFailure(status));
            }
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while (expected == generation.get() && SystemClock.elapsedRealtime() < deadline
                        && (count = input.read(buffer)) >= 0) {
                    if (bytes.size() + count > 256 * 1024) throw new IOException("HLS playlist exceeds probe limit");
                    bytes.write(buffer, 0, count);
                }
                if (SystemClock.elapsedRealtime() >= deadline)
                    throw new java.net.SocketTimeoutException("HLS manifest probe deadline exceeded");
                String manifest = bytes.toString("UTF-8");
                String failure = HlsProbeFailure.manifest(manifest);
                if (failure.length() > 0) throw new ManifestFailure(failure);
                return manifest.replace("\uFEFF", "").trim();
            }
        } finally { connection.disconnect(); }
    }
    private static boolean initializationOnly(String url,String page,String agent,String cookies,long deadline)throws Exception {
        HttpURLConnection connection=openConnection(url,page,agent,cookies);
        connection.setRequestProperty("Range","bytes=0-65535");
        connection.setReadTimeout((int)Math.max(1,Math.min(3000,deadline-SystemClock.elapsedRealtime())));
        try{
            int status=connection.getResponseCode();if(status!=200&&status!=206)return false;
            byte[] bytes=new byte[65536];int count=0,n;boolean complete=false;
            try(InputStream in=connection.getInputStream()){
                while(count<bytes.length&&(n=in.read(bytes,count,bytes.length-count))>0){count+=n;if(count>=12&&!LiteMp4Probe.isMp4(bytes,count))return false;}
                if(count<bytes.length)complete=true;
            }
            if(status==206){String range=connection.getHeaderField("Content-Range");complete=range!=null&&range.endsWith("/"+count);}
            return LiteMp4Probe.initializationOnly(bytes,count,complete);
        }finally{connection.disconnect();}
    }
    private static HttpURLConnection openConnection(String url, String page, String agent, String cookies) throws java.io.IOException {
        HttpURLConnection connection = NetworkClient.open(new URL(url));
        connection.setConnectTimeout(2000); connection.setReadTimeout(2000);
        connection.setRequestProperty("Referer", clean(page));
        connection.setRequestProperty("User-Agent", clean(agent));
        connection.setRequestProperty("Cookie", clean(cookies));
        return connection;
    }

    private static String clean(String text) { return text == null ? "" : text.replace("\r", "").replace("\n", ""); }
    private static final class ManifestFailure extends IOException {
        ManifestFailure(String message) { super(message); }
    }
    private static String httpFailure(int status) {
        if (status == 403) return PlaybackHttpError.FORBIDDEN_MESSAGE;
        if (status == 404) return "HTTP 404：资源不存在或链接已失效";
        if (status == 401) return "HTTP 401：资源需要授权";
        return "HTTP " + status + "：资源请求失败";
    }
    private static String probeFailure(Throwable error) {
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
            if (error instanceof ManifestFailure) return error.getMessage();
            if (error instanceof java.net.SocketTimeoutException) return "资源连接超时";
            if (error instanceof javax.net.ssl.SSLException) return "资源 TLS / 证书连接失败";
            if (error instanceof java.net.UnknownHostException) return "资源域名无法解析";
            if (error instanceof LinkageError) return "播放器组件加载失败";
            String message = error.getMessage();
            if (message != null) {
                if (message.contains("HLS playlist response is empty")) return "清单请求返回空内容（HTTP 200）";
                if (message.contains("HLS playlist exceeds probe limit")) return "HLS 清单超过 256 KB 探测上限";
                Matcher status = Pattern.compile("\\bHTTP\\s+(\\d{3})\\b").matcher(message);
                if (message.contains("AES-128 key request failed"))
                    return "AES-128 密钥请求失败：" + (status.find() ? httpFailure(Integer.parseInt(status.group(1))) : "连接失败或服务器未返回密钥");
                if (status.find()) return httpFailure(Integer.parseInt(status.group(1)));
                if (message.contains("Invalid AES-128 key length")) return "AES-128 密钥内容错误：密钥必须是 16 字节";
                if (message.contains("AES-128 segment length is not block aligned")) return "AES-128 加密分片长度错误：数据可能不完整";
            }
            if (error instanceof javax.crypto.BadPaddingException) return "AES-128 解密失败：密钥或 IV 不匹配，也可能是分片损坏";
            if (error instanceof java.net.ConnectException) return "无法连接资源服务器";
        }
        return "资源连接或格式解析失败";
    }
    private static String headers(String page, String agent, String cookies) {
        return "Referer: " + clean(page) + "\r\nUser-Agent: " + clean(agent) + "\r\nCookie: " + clean(cookies) + "\r\n";
    }
}
