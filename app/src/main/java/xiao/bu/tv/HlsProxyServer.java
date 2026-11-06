package xiao.bu.tv;

import com.bu.cc.tv.NativeCmgDecryptor;
import com.bu.cc.tv.NativeGxtvTransformer;
import com.bu.cc.tv.NativeH5eDecryptor;

import android.os.SystemClock;
import android.os.Build;
import android.os.Process;
import android.net.Uri;
import android.util.Base64;
import xiao.bu.tv.AppLog;
import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class HlsProxyServer implements Closeable {
    interface CatchupListener { void onTimeline(int state,long actual,String error); }
    private long catchupStart=-1,catchupStop;
    private boolean requireCatchupTime;
    private volatile boolean catchupVerified;
    private volatile String catchupTimeError="";
    private CatchupListener catchupListener;
    private final java.util.Set<String> checkedCatchupPlaylists=new java.util.LinkedHashSet<>();
    void setCatchupWindow(long start,long stop,boolean strict,CatchupListener listener){
        catchupStart=start;catchupStop=stop;requireCatchupTime=strict;catchupListener=listener;
    }
    boolean catchupTimeVerified(){return catchupVerified;}
    private synchronized void verifyCatchupTimeline(String url,String body)throws IOException{
        if(catchupStart<0||body.contains("#EXT-X-STREAM-INF")||!body.contains("#EXTINF:"))return;
        if(!catchupTimeError.isEmpty())throw new IOException(catchupTimeError);
        CatchupTimeline.Result result=CatchupTimeline.inspect(body,catchupStart,catchupStop,!checkedCatchupPlaylists.contains(url));
        String error="";
        if(result.state==CatchupTimeline.MISMATCH){
            java.text.SimpleDateFormat format=new java.text.SimpleDateFormat("MM-dd HH:mm:ss",Locale.US);
            error="回看时间不符：请求 "+format.format(new java.util.Date(catchupStart))+"，返回 "+format.format(new java.util.Date(result.actual));
        }else if(result.state==CatchupTimeline.UNKNOWN&&requireCatchupTime&&!catchupVerified){
            error="回看未确认：服务器未提供可核验的节目时间";
        }
        if(!error.isEmpty()){
            catchupTimeError=error;if(catchupListener!=null)catchupListener.onTimeline(CatchupTimeline.MISMATCH,result.actual,error);
            throw new IOException(error);
        }
        if(result.state==CatchupTimeline.VERIFIED){
            if(checkedCatchupPlaylists.size()>=64)checkedCatchupPlaylists.remove(checkedCatchupPlaylists.iterator().next());
            checkedCatchupPlaylists.add(url);
            if(!catchupVerified){catchupVerified=true;if(catchupListener!=null)catchupListener.onTimeline(result.state,result.actual,"");}
        }
    }
    interface SegmentCostListener { void onSegmentCost(long durationMs, long downloadMs, long decryptMs, boolean encrypted); }
    private volatile SegmentCostListener segmentCostListener;
    private Boolean h5eStreamingSupported;
    private final Map<String, ProgressiveMediaBuffer> h5eProgressive = new LinkedHashMap<>();
    private synchronized boolean supportsH5eStreaming() {
        if (h5eStreamingSupported == null) {
            try { h5eStreamingSupported = NativeH5eDecryptor.supportsStreamingTransportStream(); }
            catch (LinkageError unavailable) { h5eStreamingSupported = false; }
            AppLog.i(TAG, "H5E streaming supported=" + h5eStreamingSupported);
        }
        return h5eStreamingSupported;
    }
    interface StartupCostListener { void onCost(String stage, long durationMs, long now); }
    private volatile StartupCostListener startupCostListener;
    void setStartupCostListener(StartupCostListener listener) { startupCostListener = listener; }
    private void startupCost(String stage, long durationMs) {
        StartupCostListener listener = startupCostListener;
        if (running && listener != null) listener.onCost(stage, durationMs, SystemClock.elapsedRealtime());
    }
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> startupMilestones =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final long createdAt = SystemClock.elapsedRealtime();
    private void startupMilestone(String stage, String url) {
        if (!running || startupMilestones.putIfAbsent(stage, Boolean.TRUE) != null) return;
        AppLog.i(TAG, "Startup pipeline stage=" + stage + " port="
                + (serverSocket == null ? -1 : serverSocket.getLocalPort())
                + " proxyElapsedMs=" + (SystemClock.elapsedRealtime() - createdAt)
                + (url == null ? "" : " segment=" + segmentName(url)));
        startupCost(stage, -1);
    }
    private void noteClearVideo(byte[] clear, int videoPid, String url) {
        if (startupMilestones.containsKey("first-clear-video-pes")) return;
        for (int i = 0; i + 188 <= clear.length; i += 188) {
            if ((((clear[i + 1] & 31) << 8) | (clear[i + 2] & 255)) == videoPid
                    && (clear[i + 1] & 64) != 0 && (clear[i + 3] & 16) != 0) {
                startupMilestone("first-clear-video-pes", url);
                return;
            }
        }
    }
    private volatile int adaptiveBufferLevel;
    void setAdaptiveBufferLevel(int level) { adaptiveBufferLevel = Math.max(0, Math.min(2, level)); }
    void setSegmentCostListener(SegmentCostListener listener) { segmentCostListener = listener; }
    private final Map<String, Long> segmentDurations = Collections.synchronizedMap(
            new LinkedHashMap<String, Long>() {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Long> entry) { return size() > 512; }
            });
    private final Map<String, Long> segmentDownloadTimes = Collections.synchronizedMap(
            new LinkedHashMap<String, Long>() {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Long> entry) { return size() > 128; }
            });
    private void rememberSegmentDuration(String url, List<String> tags) {
        for (String tag : tags) if (tag.startsWith("#EXTINF:")) try {
            long duration = Math.round(Double.parseDouble(tag.substring(8).split(",", 2)[0]) * 1000);
            if (duration > 0) segmentDurations.put(url, duration);
        } catch (NumberFormatException ignored) { }
    }
    private void logSegmentCost(String url, long downloadMs, long decryptMs, boolean encrypted) {
        Long duration = segmentDurations.get(url);
        if (duration == null || downloadMs < 0 || !running) return;
        AppLog.i(TAG, "Segment cost segment=" + segmentName(url) + " durationMs=" + duration
                + " downloadMs=" + downloadMs + " decryptMs=" + decryptMs
                + " encrypted=" + encrypted + " processingMs=" + (downloadMs + decryptMs));
        SegmentCostListener listener = segmentCostListener;
        if (listener != null) listener.onSegmentCost(duration, downloadMs, decryptMs, encrypted);
    }
    private void logDecryptedSegmentCost(String url, long decryptMs) {
        Long download = segmentDownloadTimes.remove(url);
        if (download != null) logSegmentCost(url, download, decryptMs, true);
    }
    private final File metadataCacheDirectory;
    private final List<Socket> clientSockets=new ArrayList<Socket>();
    // IJK's HLS child requests cache sockaddr (including port) by host only.
    // A distinct numeric loopback host per proxy isolates stale entries without DNS.
    private static final AtomicInteger LOOPBACK_SEQUENCE = new AtomicInteger();
    private final String loopbackHost = nextLoopbackHost();
    private static String nextLoopbackHost() {
        int sequence = LOOPBACK_SEQUENCE.getAndIncrement() & 0x00ffffff;
        return "127." + (1 + (sequence / 65025) % 254) + "."
                + (1 + (sequence / 255) % 255) + "." + (1 + sequence % 255);
    }
    private volatile boolean ownsH5eWork;
    private volatile boolean readingStartupManifest;
    private final HlsStartupPlaylistCache startupPlaylists = new HlsStartupPlaylistCache();
    private final HlsStartupPlaylistCache resolvedPlaylists = new HlsStartupPlaylistCache();
    private final HlsPlaylistAliases playlistAliases = new HlsPlaylistAliases();
    // One upcoming manifest for a dynamic live gateway. Media bodies are never
    // prefetched here; the player's next reload joins this same read-only GET.
    private final ExecutorService manifestAheadWorker = Executors.newSingleThreadExecutor();
    private volatile String manifestAheadUrl;
    private static final class TimedManifest {
        final ProxyResponse response;
        final long completedAt = SystemClock.elapsedRealtime();
        TimedManifest(ProxyResponse response) { this.response = response; }
    }
    private FutureTask<TimedManifest> manifestAhead;
    private void startManifestAhead(String url) {
        synchronized (resolvedPlaylists) {
            if (!running || playbackSuspended || !url.equals(manifestAheadUrl) || manifestAhead != null) return;
            FutureTask<TimedManifest> next = new FutureTask<>(() -> new TimedManifest(fetchNetwork(url)));
            manifestAhead = next;
            try { manifestAheadWorker.execute(next); }
            catch (java.util.concurrent.RejectedExecutionException stopped) { manifestAhead = null; }
        }
    }
    private ProxyResponse takeManifestAhead(String url) throws IOException {
        FutureTask<TimedManifest> next;
        synchronized (resolvedPlaylists) {
            if (!url.equals(manifestAheadUrl) || manifestAhead == null) return null;
            next = manifestAhead;
        }
        try {
            TimedManifest result = next.get();
            long age = SystemClock.elapsedRealtime() - result.completedAt;
            return age >= 0 && age < 1000 ? result.response : null;
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException("HLS reload cancelled");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException failed) {
            if (!running) throw new SocketException("Proxy closed");
            return null; // Normal bounded retry policy handles a failed speculative GET.
        } finally {
            synchronized (resolvedPlaylists) { if (manifestAhead == next) manifestAhead = null; }
        }
    }
    void rememberResolvedPlaylist(HttpStreamResolver.Result result) {
        if (result.playlist == null || needsSpecialDecrypt(result.url) || needsCjsTransform(result.url)) return;
        long ageMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - result.playlistReceivedNanos);
        if (ageMs < 0 || ageMs >= 1000) return;
        resolvedPlaylists.put(result.url, result.playlist, SystemClock.elapsedRealtime() - ageMs);
        String body = new String(result.playlist, UTF_8);
        if (!HttpStreamResolver.isExplicitHlsUrl(result.url) && body.contains("#EXTINF:")
                && !body.contains("#EXT-X-ENDLIST") && !body.contains("#EXT-X-KEY:")) manifestAheadUrl = result.url;
        synchronized (knownHlsPlaylists) { knownHlsPlaylists.put(result.url, true); }
    }
    HlsStartupManifest.Result prepareManifest(String url)throws IOException {
        if(needsSpecialDecrypt(url)||needsCjsTransform(url))return null;
        readingStartupManifest=true;
        try {return HlsStartupManifest.prepare(new HlsStartupManifest.Transport() {
            public String playlist(String address)throws IOException {
                ProxyResponse response=fetch(address);
                if(isPlaylistBody(response.body))startupPlaylists.put(address,response.body,SystemClock.elapsedRealtime());
                return new String(response.body,UTF_8);
            }
            public String origin(String address)throws IOException {
                String prefix="http://"+loopbackHost+":"+serverSocket.getLocalPort()+"/proxy/";
                if(!address.startsWith(prefix))throw new IOException("Unrecognized rewritten HLS resource");
                try{return decodeProxyToken(address.substring(prefix.length()));}
                catch(IllegalArgumentException error){throw new IOException(error);}
            }
        },url);} finally {readingStartupManifest=false;}
    }
    String playerHlsUrl(String url) {
        systemPlayerStartsAtFirst = Build.VERSION.SDK_INT <= 15;
        synchronized (sourceProbeLock) { deferStartupMetadata = true; playbackVideoReady = false; }
        return systemPlayerHlsUrl(url);
    }
    boolean canUseDirectHttpHlsUrl(String url) {
        if(catchupStart>=0)return false;
        // The native ARM profile has neither HTTPS nor the crypto protocol.
        return url != null && url.startsWith("http://")
                && !carrierIptvSession && webReferer == null && webCookies == null && webUserAgent == null
                && !webNoReferer && !webHttp1 && webSignedHeaders.isEmpty() && webMediaHeaders.isEmpty()
                && !CarrierNetworkRoute.isCarrierIptvUrl(url)
                && canUseNativeHlsDns(url) && !needsSpecialDecrypt(url)
                && !needsCjsTransform(url) && !hasAesSegmentKey(url);
    }
    String selectDirectHttpHlsUrl(HlsStartupManifest.Result prepared) {
        // prepare() excludes encryption, VOD, separate renditions and byte ranges.
        if (prepared == null || !canUseDirectHttpHlsUrl(prepared.originUrl)
                || !canUseDirectHttpHlsUrl(prepared.mediaUrl)) return null;
        for (String segment : prepared.segmentUrls) {
            if (!canUseDirectHttpHlsUrl(segment)) return null;
        }
        nativeMediaDownloads=true;
        return prepared.mediaUrl;
    }
    private static boolean canUseNativeHlsDns(String url) {
        // FFmpeg uses system DNS for both the manifest and every segment host.
        String host = Uri.parse(url).getHost();
        return NetworkClient.DNS_SYSTEM.equals(NetworkClient.getDnsMode())
                || host != null && (host.matches("[0-9.]+") || host.indexOf(':') >= 0);
    }
    private static final String TAG = "HlsProxyServer";
    static final String VARIANT_QUALITY_HIGH = "high";
    static final String VARIANT_QUALITY_MEDIUM = "medium";
    static final String VARIANT_QUALITY_LOW = "low";
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final Pattern ATTRIBUTE_URI = Pattern.compile("URI=\"([^\"]+)\"");
    private static final Pattern STREAM_BANDWIDTH = Pattern.compile("BANDWIDTH=(\\d+)");
    private static final Pattern STREAM_RESOLUTION = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)");
    private static final Pattern MEDIA_SEQUENCE = Pattern.compile("#EXT-X-MEDIA-SEQUENCE:(\\d+)");
    private static final Pattern HLS_KEY_METHOD = Pattern.compile("(?:^|,)METHOD=([^,]+)");
    private static final Pattern HLS_KEY_IV = Pattern.compile("(?:^|,)IV=0[xX]([0-9a-fA-F]+)");
    private static final Pattern YANGSHIPIN_SEGMENT_NUMBER =
            Pattern.compile("^(.*_web-)(\\d+)(\\.ts(?:[?#].*)?)$");
    private static final int CMG_SEGMENT_CACHE_LIMIT = 6;
    private static final int CCTV_SEGMENT_CACHE_LIMIT = 6;
    private static final int CCTV_LOW_RAM_SEGMENT_CACHE_LIMIT = 3;
    // Keep a one-hour event-like window so the player can build and preserve a
    // meaningful offset from the unusually shallow three-segment CCTV origin.
    private static final int LIVE_PLAYLIST_HISTORY_LIMIT = 900;
    /* H5E is a stream state machine: type-25 control NALs affect later segments.
     * A single worker preserves ordering and also keeps only one wasm heap alive. */
    private static final int CCTV_PARALLEL_DECRYPT_THREADS = 1;
    private static final int CCTV_LOW_RAM_DECRYPT_THREADS = 1;
    private static final long CCTV_DECRYPT_SHUTDOWN_WAIT_MS = 750L;
    private static final int CCTV_PARALLEL_PREFETCH_WINDOW = 2;
    private static final int CMG_LOCAL_PREFETCH_WINDOW = 2;
    private static final int CMG_REMOTE_PREFETCH_WINDOW = 2;
    private static final int CMG_MAX_GAP_PREWARM_SEGMENTS = 6;
    private static final int CMG_INITIAL_PREWARM_SEGMENTS = 0;
    private static final int CMG_MAX_VCL_PER_RUNTIME = 150;
    private static final int UPSTREAM_MAX_ATTEMPTS = 3;
    private static final int CCTV_EDGE_MAX_ATTEMPTS = 5;
    private static final int CCTV_SEGMENT_MAX_ATTEMPTS = 3;
    private static final int UPSTREAM_CONNECT_TIMEOUT_MS = 3500;
    private static final int UPSTREAM_READ_TIMEOUT_MS = 5500;
    private static final int UPSTREAM_RETRY_DELAY_MS = 250;
    private static final int CCTV_EDGE_RETRY_DELAY_MS = 400;
    private static final int CCTV_SEGMENT_RETRY_DELAY_MS = 250;
    private static final int CCTV_SEGMENT_READ_TIMEOUT_MS = 5000;
    private static final int TS_PACKET_SIZE = 188;
    // CCTV web playlists normally expose only three 4-second segments. Hide one segment on
    // the first response and two once history has caught up, keeping playback 4-8 seconds
    // behind a CDN edge that may advertise a segment before every node can serve it.
    private static final int CCTV_LIVE_EDGE_HOLD_BACK_SEGMENTS = 2;
    private static final int CCTV_MIN_PLAYABLE_SEGMENTS = 2;
    private static final int TS_RESOLUTION_PROBE_BYTES = 384 * 1024;
    private static final int MAX_PLAYLIST_RESPONSE_BYTES = 2 * 1024 * 1024;
    // Allow room for concurrent workers, decrypt buffers and the player on small heaps.
    // This bounds whole-response buffering, not streamed media bitrate/resolution.
    private static final int MAX_BUFFERED_RESPONSE_BYTES = (int) Math.max(256 * 1024,
            Math.min(16 * 1024 * 1024L, Runtime.getRuntime().maxMemory() / 16));
    private static final int STREAM_COPY_BUFFER_BYTES = 64 * 1024;
    static final String DEFAULT_USER_AGENT = "nTv/1.0";
    private static final String CARRIER_IPTV_USER_AGENT = "okhttp/3.10.0";
    private static final String YANGSHIPIN_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    private static final Object CMG_DECRYPT_LOCK = new Object();
    // One ordered stream across proxies, including raw signed URLs whose generic
    // proxy otherwise permits parallel segment workers. Never hold the JNI lock
    // while waiting on network input; channel configuration remains responsive.
    private static final ExecutorService CMG_STREAM_WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cmg-stream"); t.setDaemon(true); return t;
    });
    private static final PesBuffer CMG_PES_BUFFER = new PesBuffer();
    private static volatile long cmgConfigurationGeneration;
    private final Map<String, ProgressiveMediaBuffer> cmgProgressive = new LinkedHashMap<String, ProgressiveMediaBuffer>();
    private static boolean cmgSessionWarmed;
    private static boolean cmgLiveVideoDecodeEnabled;
    private static int cmgInitialUpdateTag;
    private static int cmgStableUpdateTag;
    private static boolean cmgFirstStateNalPending;
    private static int cmgVclSinceRuntimeRestart;
    private static String cmgPlayerTag = "";
    private static long cmgClockBaseTimeMs;
    private static int cmgClockOffsetMs;
    // The CMG Live decryptor is a stateful stream machine. Segment requests must not
    // advance it concurrently, or later NALs are decoded with the wrong state.
    private final ExecutorService workers;
    private final ExecutorService cctvPrefetchWorkers;
    private final ExecutorService genericPrefetchWorkers;
    private final boolean parallelCctvDecrypt;
    private volatile boolean systemPlayerStartsAtFirst;
    private volatile boolean readAheadPaused;
    private final int cctvLiveEdgeHoldBackSegments;
    private final int cctvStartupDownloadSegments;
    private final int cctvStartupDecryptSegments;
    private final int genericStartupPrefetchSegments;
    private final boolean configuredVariantQualityEnabled;
    private final CarrierNetworkRoute carrierNetworkRoute;
    private final String variantQualityMode;
    private volatile HlsMediaTracks.Manifest mediaTrackManifest;
    private volatile String mediaTrackPolicyUrl;
    private final Map<String,Boolean> knownHlsPlaylists=new LinkedHashMap<String,Boolean>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Boolean> entry){return size()>16;}
    };
    boolean knowsHlsPlaylist(String url) {
        synchronized(knownHlsPlaylists){return knownHlsPlaylists.containsKey(url);}
    }
    private volatile String requestedVideoVariant = "";
    private volatile boolean byteRangeMediaPlaylist;
    private final HlsSegmentBitrate segmentBitrate = new HlsSegmentBitrate();
    private final PlaybackHttpError.Attempt playbackHttpError = new PlaybackHttpError.Attempt();
    private volatile Throwable lastPlaybackFailure;
    private final AtomicInteger sourceVideoProbeAttempts = new AtomicInteger();
    private volatile boolean sourceVideoProbeEnabled;
    private volatile CachedVideoMetadataProbe sourceMetadataProbe;
    private final Object sourceProbeLock = new Object();
    private boolean deferStartupMetadata, playbackVideoReady;
    private byte[] deferredMetadataSample;
    private volatile float sourceVideoFrameRate;
    private volatile int sourceVideoWidth;
    private volatile int sourceVideoHeight;
    private volatile long lastMediaSegmentServedAt;
    private volatile byte[] screenshotSegment;
    private volatile int selectedVariantWidth;
    private volatile int selectedVariantHeight;
    private volatile int selectedVariantBandwidth;
    private volatile String selectedVideoCodecs = "";
    private final java.util.Set<String> dolbyInitUrls = java.util.Collections.synchronizedSet(
            new java.util.HashSet<String>());
    private volatile boolean selectedAtmos;
    private final PlaybackReplayCache replayCache;
    private volatile boolean finiteMedia;
    private volatile String sourceAudioCodec = "--";
    private volatile String sourceVideoMime = "";
    private final AtomicInteger startupCodecProbeAttempts = new AtomicInteger();
    String sourceVideoMime() {
        return sourceVideoMime.isEmpty() ? VideoDecoderSupport.mime(selectedVideoCodecs) : sourceVideoMime;
    }
    private void inspectStartupVideoCodec(byte[] body) {
        if(!sourceVideoMime.isEmpty()||startupCodecProbeAttempts.incrementAndGet()>3)return;
        String mime=HlsVideoCodecProbe.mime(body);
        if(!mime.isEmpty())sourceVideoMime=mime;
    }

    void beginPlaybackAttempt() {
        readAheadPaused = false;
        startupMilestones.clear();
        synchronized (sourceProbeLock) {
            deferStartupMetadata = false; playbackVideoReady = false; deferredMetadataSample = null;
        }
        playbackHttpError.reset();
        lastPlaybackFailure = null;
        sourceVideoMime = "";
        startupCodecProbeAttempts.set(0);
        sourceVideoProbeEnabled = false;
        sourceVideoProbeAttempts.set(0);
        sourceVideoFrameRate = 0f;
        sourceVideoWidth = 0;
        sourceVideoHeight = 0;
        lastMediaSegmentServedAt = 0L;
        screenshotSegment = null;
        // The prepared manifest already selected this channel's variant. Keep its
        // metadata when handing the child playlist directly to the player.
        sourceAudioCodec = "--";
    }
    boolean wasPlaybackForbidden() { return playbackHttpError.isForbidden(); }
    Throwable lastPlaybackFailure() { return lastPlaybackFailure; }
    void enableSourceVideoProbe() {
        if (sourceVideoProbeEnabled) return;
        sourceVideoProbeEnabled = true;
        // Start from an existing decrypted body when possible; do not request it again.
        byte[] saved = screenshotSegment;
        if (saved == null) synchronized(cctvSegmentCache) { saved=cctvSegmentCache.get(lastCctvRequestedUrl); }
        if (saved != null) sampleSourceVideo(saved);
    }
    void disableSourceVideoProbe() { sourceVideoProbeEnabled = false; }
    float sourceVideoFrameRate() { return sourceVideoFrameRate; }
    int sourceVideoWidth() { return sourceVideoWidth; }
    int sourceVideoHeight() { return sourceVideoHeight; }
    VideoStreamDescription sourceVideoDescription() { return sourceMetadataProbe.description(); }
    // Reuse a decrypted segment already delivered to the player, without downloading
    // or copying it again. Retaining only the latest one bounds the extra lifetime.
    byte[] screenshotSegment() {
        return running ? screenshotSegment : null;
    }
    boolean servedMediaSegmentRecently(long maxAgeMs) {
        long last = lastMediaSegmentServedAt;
        return last > 0L && SystemClock.elapsedRealtime() - last <= maxAgeMs;
    }
    String selectedVariantDescription() {
        return selectedVariantWidth > 0 && selectedVariantHeight > 0
                ? selectedVariantWidth + "x" + selectedVariantHeight + " / "
                        + (selectedVariantBandwidth / 1000) + " kbps" : "unknown";
    }
    boolean finiteMedia() { return finiteMedia; }
    boolean selectedAtmos() { return selectedAtmos; }
    boolean selectedDolbyVision() { return DolbyFormats.isDolbyVision(selectedVideoCodecs); }
    String selectedVideoCodecs() { return selectedVideoCodecs; }
    String sourceAudioCodec() { return sourceAudioCodec; }

    long measuredMediaBitrate(boolean video) {
        return segmentBitrate.bitrate(video, SystemClock.elapsedRealtime());
    }

    HlsMediaTracks.Manifest mediaTracks(String sourceUrl) {
        HlsMediaTracks.Manifest manifest = mediaTrackManifest;
        return manifest != null && (sourceUrl.equals(mediaTrackPolicyUrl)
                || sourceUrl.equals(manifest.url)) ? manifest : null;
    }

    void selectVideoVariant(String url) {
        requestedVideoVariant = url;
    }

    boolean usesByteRangeMediaPlaylist() {
        return byteRangeMediaPlaylist;
    }
    private final boolean spsCompatibilityMode;
    private final ScheduledExecutorService cctvPlaylistMonitor =
            Executors.newSingleThreadScheduledExecutor();
    private volatile long lastMediaProgressAt;
    private void recordUpstreamBytes(long count,boolean media) {
        if (count <= 0L) return;
        upstreamDownloadedBytes.addAndGet(count);
        if(media)lastMediaProgressAt = SystemClock.elapsedRealtime();
    }
    boolean mediaMakingProgress(long withinMs) {
        // A refreshing playlist or key is not progress on a stalled media body.
        long last = lastMediaProgressAt;
        return running && last > 0L && SystemClock.elapsedRealtime() - last < withinMs;
    }
    private final AtomicLong upstreamDownloadedBytes = new AtomicLong();
    private volatile boolean nativeMediaDownloads;
    boolean usesNativeMediaDownloads(){return nativeMediaDownloads;}
    private final AtomicLong streamedResponseCount = new AtomicLong();
    private final AtomicLong streamedResponseBytes = new AtomicLong();
    private final ThreadLocal<byte[]> streamCopyBuffer = new ThreadLocal<byte[]>() {
        @Override
        protected byte[] initialValue() {
            return new byte[STREAM_COPY_BUFFER_BYTES];
        }
    };
    private int cmgSegmentCacheLimit = CMG_SEGMENT_CACHE_LIMIT;
    private int cctvSegmentCacheLimit = CCTV_SEGMENT_CACHE_LIMIT;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;
    private volatile boolean playbackSuspended;

    void setPlaybackSuspended(boolean suspended) {
        playbackSuspended = suspended;
        upstreamConnections.setSuspended(suspended);
        if (!suspended) return;
        disconnectPlayerRequests();
        synchronized (resolvedPlaylists) {
            if (manifestAhead != null) manifestAhead.cancel(true);
            manifestAhead = null;
        }
        cancelPrefetch(cctvSegmentTasks);
        cancelPrefetch(cmgSegmentTasks);
        cancelPrefetch(genericSegmentTasks);
        // Keep resolved playlists, keys, parsed stream metadata and completed cache entries.
    }

    private static void cancelPrefetch(Map<String, FutureTask<byte[]>> tasks) {
        List<FutureTask<byte[]>> pending;
        synchronized (tasks) {
            pending = new ArrayList<>();
            Iterator<Map.Entry<String,FutureTask<byte[]>>> entries=tasks.entrySet().iterator();
            while(entries.hasNext()) {
                FutureTask<byte[]> task=entries.next().getValue();
                if(!task.isDone() || task.isCancelled()) { pending.add(task); entries.remove(); }
            }
        }
        for (FutureTask<byte[]> task : pending) task.cancel(true);
    }
    private final AtomicInteger cmgTsRequestIndex = new AtomicInteger();
    private final Map<String, byte[]> cmgSegmentCache =
            new LinkedHashMap<String, byte[]>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > cmgSegmentCacheLimit;
                }
            };
    /* Completed H5E segments must outlive their FutureTask. Keeping the playable bytes in
     * a small LRU lets playlist polling and player requests share one successful download. */
    private final Map<String, byte[]> cctvSegmentCache =
            new LinkedHashMap<String, byte[]>(12, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > cctvSegmentCacheLimit;
                }
            };
    private final Map<String, Boolean> cctvStartupReady =
            new LinkedHashMap<String, Boolean>();
    private final Object cctvStartupLock = new Object();
    private long cctvStartupGeneration;
    private final Map<String, LinkedHashMap<String, PlaylistSegment>> playlistSegmentHistory =
            new LinkedHashMap<String, LinkedHashMap<String, PlaylistSegment>>();
    private final Map<String, FutureTask<byte[]>> cctvSegmentTasks =
            new LinkedHashMap<String, FutureTask<byte[]>>(8, 0.75f, true);
    private final Map<String, FutureTask<byte[]>> cmgSegmentTasks =
            new LinkedHashMap<String, FutureTask<byte[]>>(4, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<String, FutureTask<byte[]>> eldest) {
                    return size() > CMG_REMOTE_PREFETCH_WINDOW + 1;
                }
            };
    private final Map<String, String> cctvNextSegments =
            Collections.synchronizedMap(new LinkedHashMap<String, String>());
    private final Map<String, Boolean> recordingTokens =
            new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 2048;
                }
            };
    /* The compact IJK build deliberately omits FFmpeg's crypto protocol. Standard
     * AES-128 HLS therefore has to be decrypted by the Java proxy before the clear
     * MPEG-TS segment is returned to IJK. Both maps are per-channel/proxy instance. */
    private final Map<String, AesSegmentKey> aesSegmentKeys =
            new LinkedHashMap<String, AesSegmentKey>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, AesSegmentKey> eldest) {
                    return size() > 512;
                }
            };
    private final Map<String, byte[]> aesKeyCache =
            new LinkedHashMap<String, byte[]>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > 16;
                }
            };
    private final LinkedHashSet<String> consumedGenericSegments = new LinkedHashSet<String>();
    private final Map<String, FutureTask<byte[]>> genericSegmentTasks =
            new LinkedHashMap<String, FutureTask<byte[]>>(6, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<String, FutureTask<byte[]>> eldest) {
                    return size() > 6;
                }
            };
    private final Map<String, Boolean> genericStartupReady =
            new LinkedHashMap<String, Boolean>(4, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 4;
                }
            };
    private volatile String lastCctvRequestedUrl;
    private volatile String lastCctvPlaylistUrl;
    private volatile String monitoredCctvPlaylistUrl;
    private volatile String webReferer;
    private volatile boolean webNoReferer, webHttp1;
    private volatile java.util.Map<String, String> webSignedHeaders = java.util.Collections.emptyMap();
    private volatile java.util.Map<String, String> webMediaHeaders = java.util.Collections.emptyMap();
    private volatile String webUserAgent;
    private volatile String webCookies;
    private volatile boolean remoteConsumer;
    private volatile boolean carrierIptvSession;
    private volatile String cjsTransformer;
    private volatile String[] cjsTransformerArgs;
    private volatile String[] cjsMediaHosts;
    private boolean cctvPlaylistMonitorStarted;
    private long cmgLastYangshipinSegment = -1L;

    HlsProxyServer() {
        this(null, true, false, true, CCTV_LIVE_EDGE_HOLD_BACK_SEGMENTS,
                true, VARIANT_QUALITY_HIGH, 2, 2);
    }

    HlsProxyServer(boolean statefulCmgSource, boolean lowResourceDevice,
            boolean spsCompatibilityMode, int liveEdgeHoldBackSegments,
            boolean configuredVariantQualityEnabled, String variantQualityMode,
            int startupDownloadSegments, int startupDecryptSegments) {
        this(null, statefulCmgSource, lowResourceDevice, spsCompatibilityMode,
                liveEdgeHoldBackSegments, configuredVariantQualityEnabled, variantQualityMode,
                startupDownloadSegments, startupDecryptSegments);
    }

    HlsProxyServer(Context context, boolean statefulCmgSource, boolean lowResourceDevice,
            boolean spsCompatibilityMode, int liveEdgeHoldBackSegments,
            boolean configuredVariantQualityEnabled, String variantQualityMode,
            int startupDownloadSegments, int startupDecryptSegments) {
        this(context, statefulCmgSource, lowResourceDevice, spsCompatibilityMode,
                liveEdgeHoldBackSegments, configuredVariantQualityEnabled, variantQualityMode,
                startupDownloadSegments, startupDecryptSegments, 0);
    }

    HlsProxyServer(Context context, boolean statefulCmgSource, boolean lowResourceDevice,
            boolean spsCompatibilityMode, int liveEdgeHoldBackSegments,
            boolean configuredVariantQualityEnabled, String variantQualityMode,
            int startupDownloadSegments, int startupDecryptSegments,
            int genericStartupPrefetchSegments) {
        metadataCacheDirectory=context==null?null:context.getCacheDir();
        replayCache = new PlaybackReplayCache(metadataCacheDirectory);
        carrierNetworkRoute = new CarrierNetworkRoute(context);
        sourceMetadataProbe = new CachedVideoMetadataProbe(metadataCacheDirectory);
        cctvLiveEdgeHoldBackSegments = Math.max(0, Math.min(3, liveEdgeHoldBackSegments));
        cctvStartupDownloadSegments = Math.max(0, Math.min(2, startupDownloadSegments));
        cctvStartupDecryptSegments = Math.max(0,
                Math.min(cctvStartupDownloadSegments, startupDecryptSegments));
        this.genericStartupPrefetchSegments = Math.max(0,
                Math.min(2, genericStartupPrefetchSegments));
        this.configuredVariantQualityEnabled = configuredVariantQualityEnabled;
        this.variantQualityMode = sanitizeVariantQualityMode(variantQualityMode);
        this.spsCompatibilityMode = spsCompatibilityMode;
        cmgSegmentCacheLimit = lowResourceDevice ? 2 : CMG_SEGMENT_CACHE_LIMIT;
        cctvSegmentCacheLimit = lowResourceDevice
                ? CCTV_LOW_RAM_SEGMENT_CACHE_LIMIT : CCTV_SEGMENT_CACHE_LIMIT;
        parallelCctvDecrypt = !statefulCmgSource;
        workers = statefulCmgSource
                ? Executors.newSingleThreadExecutor()
                : Executors.newFixedThreadPool(lowResourceDevice ? 2 : 4);
        int decryptThreads = !parallelCctvDecrypt ? 1
                : (lowResourceDevice
                ? CCTV_LOW_RAM_DECRYPT_THREADS : CCTV_PARALLEL_DECRYPT_THREADS);
        cctvPrefetchWorkers = newCctvPrefetchExecutor(decryptThreads);
        genericPrefetchWorkers = Executors.newFixedThreadPool(3);
        AppLog.i(TAG, "CCTV decrypt profile parallel=" + parallelCctvDecrypt
                + " prefetchThreads=" + decryptThreads
                + " statefulSession=" + parallelCctvDecrypt
                + " liveEdgeHoldBack=" + cctvLiveEdgeHoldBackSegments
                + " startupDownload=" + cctvStartupDownloadSegments
                + " startupDecrypt=" + cctvStartupDecryptSegments
                + " genericStartupPrefetch=" + this.genericStartupPrefetchSegments
                + " variantQuality=" + this.variantQualityMode
                + " variantQualityEnabled=" + configuredVariantQualityEnabled
                + " spsCompatibility=" + spsCompatibilityMode);
    }

    private static String sanitizeVariantQualityMode(String mode) {
        if (VARIANT_QUALITY_MEDIUM.equals(mode) || VARIANT_QUALITY_LOW.equals(mode)) {
            return mode;
        }
        return VARIANT_QUALITY_HIGH;
    }

    void start() throws IOException {
        serverSocket = new ServerSocket(0, 8, InetAddress.getByName(loopbackHost));
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "hls-proxy-accept");
        acceptThread.start();
        AppLog.i(TAG, "Proxy started port=" + serverSocket.getLocalPort());
    }

    /** Media identified by its response MIME type may have no filename extension. */
    String mediaUrl(String originUrl) {
        return proxyUrl(originUrl).replace("/proxy/", "/media/");
    }

    String proxyUrl(String originUrl) {
        if (CarrierNetworkRoute.isCarrierIptvUrl(originUrl)) {
            carrierIptvSession = true;
            carrierNetworkRoute.prepare();
        }
        String token = Base64.encodeToString(originUrl.getBytes(UTF_8),
                Base64.NO_WRAP | Base64.URL_SAFE);
        synchronized (recordingTokens) {
            recordingTokens.put(token, Boolean.TRUE);
        }
        return "http://"+loopbackHost+":" + serverSocket.getLocalPort() + "/proxy/" + token;
    }

    /** A manifest probe needs the origin's Content-Length, not a streamed proxy response. */
    String probeOriginUrl(String value) {
        String prefix="http://"+loopbackHost+":"+serverSocket.getLocalPort()+"/proxy/";
        if(!value.startsWith(prefix))return value;
        String token=stripProxyTokenSuffix(value.substring(prefix.length()));
        synchronized(recordingTokens) { if(!recordingTokens.containsKey(token))return value; }
        try { return decodeProxyToken(token); }
        catch(IllegalArgumentException invalid){return value;}
    }

    /** Android 4.0 selects its native HLS engine from the URL's m3u8 suffix. */
    String systemPlayerHlsUrl(String originUrl) {
        String encoded = proxyUrl(originUrl) + ".m3u8";
        if (encoded.length() < 1024) return encoded;
        // IJK's ijklongurl wrapper opens the first manifest, but its HLS reload
        // loses the real URL option and reaches EOF after the initial segments.
        // Alias only the player entry; upstream URLs/signatures stay byte-exact.
        String token = playlistAliases.token(originUrl);
        synchronized (recordingTokens) { recordingTokens.put(token, Boolean.TRUE); }
        return "http://" + loopbackHost + ":" + serverSocket.getLocalPort()
                + "/proxy/" + token + ".m3u8";
    }

    private String decodeProxyToken(String token) {
        token = stripProxyTokenSuffix(token);
        if (token.startsWith("~")) return playlistAliases.resolve(token);
        return new String(Base64.decode(token, Base64.URL_SAFE), UTF_8);
    }

    private static String stripProxyTokenSuffix(String token) {
        if (token.endsWith(".m3u8")) return token.substring(0, token.length() - 5);
        if (token.endsWith(".mp3")) return token.substring(0, token.length() - 4);
        return token;
    }

    /** Keep the MP3 type visible to Android 4.0's native media service. */
    String systemPlayerMp3Url(String originUrl) {
        return mediaUrl(originUrl) + ".mp3";
    }

    private HttpURLConnection openUpstreamConnection(String originUrl) throws IOException {
        String host = URI.create(originUrl).getHost();
        return upstreamConnections.track((webHttp1 || host != null && http1FallbackHosts.containsKey(host)) && !carrierIptvSession
                ? NetworkClient.openHttp1(new java.net.URL(originUrl))
                : carrierNetworkRoute.openConnection(originUrl, carrierIptvSession));
    }

    private final ProxyHttpConnections upstreamConnections = new ProxyHttpConnections();
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> http1FallbackHosts =
            new java.util.concurrent.ConcurrentHashMap<>();
    private boolean metadataProbeMode;
    private final java.util.LinkedHashMap<String, ProbeManifestSnapshot> probeManifests = new java.util.LinkedHashMap<>();
    private static final class ProbeManifestSnapshot {
        final ProxyResponse response;
        final long created = SystemClock.elapsedRealtime();
        ProbeManifestSnapshot(ProxyResponse response) { this.response = response; }
    }
    void setMetadataProbeMode() { metadataProbeMode = true; }

    private void noteHttp2Failure(String originUrl, IOException error) {
        if (carrierIptvSession) return;
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            boolean http2 = cause.getClass().getName().startsWith("okhttp3.internal.http2.");
            StackTraceElement[] trace = cause.getStackTrace();
            for (int i = 0; !http2 && i < Math.min(trace.length, 32); i++)
                http2 = trace[i].getClassName().startsWith("okhttp3.internal.http2.");
            if (http2) {
                String host = URI.create(originUrl).getHost();
                if (host != null && http1FallbackHosts.size() < 32
                        && http1FallbackHosts.putIfAbsent(host, Boolean.TRUE) == null)
                    AppLog.w(TAG, "HTTP/2 stream failed; retry host over HTTP/1.1: " + host);
                return;
            }
        }
    }

    private String recordingUrl(String originUrl, String token) throws IOException {
        String requestedUrl = originUrl;
        if (token != null) {
            token = stripProxyTokenSuffix(token);
            synchronized (recordingTokens) {
                if (!recordingTokens.containsKey(token)) {
                    throw new IOException("录制资源令牌无效或已过期");
                }
            }
            try {
                requestedUrl = decodeProxyToken(token);
            } catch (IllegalArgumentException error) {
                throw new IOException("录制资源令牌格式错误");
            }
        }
        if (requestedUrl == null || requestedUrl.length() == 0) {
            throw new IOException("当前频道还没有可录制的视频地址");
        }
        return requestedUrl;
    }

    LocalControlServer.Resource openForRecording(String originUrl, String token, String publicPrefix)
            throws IOException {
        String requestedUrl = recordingUrl(originUrl, token);
        if (!running || playbackSuspended) throw new SocketException("Proxy unavailable");
        // Only ordinary media can bypass buffered transforms. Keep playlists,
        // AES/proprietary decryption, Dolby init repair and pending prefetch intact.
        if (token != null && canStreamWithoutRewrite(requestedUrl)
                && !knowsHlsPlaylist(requestedUrl) && !needsCjsTransform(requestedUrl)
                && !hasAesSegmentKey(requestedUrl) && !hasGenericSegmentTask(requestedUrl)) {
            return openRecordingStream(requestedUrl, publicPrefix);
        }
        ProxyResponse response = recordingResponse(requestedUrl, fetch(requestedUrl), publicPrefix);
        return new LocalControlServer.Resource(response.contentType, response.body);
    }

    private LocalControlServer.Resource openRecordingStream(String requestedUrl, String publicPrefix)
            throws IOException {
        for (int attempt = 1; ; attempt++) {
            HttpURLConnection connection = null;
            boolean handedOff = false;
            try {
                if (!running || playbackSuspended) throw new SocketException("Proxy unavailable");
                connection = openUpstreamConnection(requestedUrl);
                connection.setConnectTimeout(UPSTREAM_CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(UPSTREAM_READ_TIMEOUT_MS);
                connection.setInstanceFollowRedirects(true);
                applyRequestHeaders(connection, requestedUrl);
                connection.setRequestProperty("Accept-Encoding", "identity");
                int status = connection.getResponseCode();
                if (status != HttpURLConnection.HTTP_OK)
                    throw new IOException("Upstream HTTP " + status);
                String type = sanitizeHeaderValue(connection.getContentType());
                if (type == null || type.length() == 0) type = "application/octet-stream";
                long length = contentLength(connection);
                BufferedInputStream input = new BufferedInputStream(
                        new UpstreamInputStream(connection.getInputStream(), true));
                // Some gateways return a playlist even for a .ts URL. Inspect
                // its bytes before committing a binary response, without a second GET.
                input.mark(64);
                byte[] prefix = new byte[64]; int size = 0, count;
                while (size < prefix.length
                        && (count = input.read(prefix, size, prefix.length - size)) > 0) size += count;
                input.reset();
                if (isPlaylist(requestedUrl, type) || isPlaylistBody(prefix)) {
                    byte[] body = BoundedResponseReader.read(input, length, MAX_PLAYLIST_RESPONSE_BYTES);
                    String rewritten = rewritePlaylist(requestedUrl, connection.getURL().toString(),
                            new String(body, UTF_8));
                    ProxyResponse response = recordingResponse(requestedUrl,
                            new ProxyResponse("application/vnd.apple.mpegurl", rewritten.getBytes(UTF_8)), publicPrefix);
                    handedOff = true; // BoundedResponseReader has consumed and closed the body.
                    return new LocalControlServer.Resource(response.contentType, response.body);
                }
                LocalControlServer.Resource resource = new LocalControlServer.Resource(type,
                        new RecordingInputStream(input, connection, length), length);
                handedOff = true;
                return resource;
            } catch (IOException error) {
                noteHttp2Failure(requestedUrl, error);
                if (attempt >= UPSTREAM_MAX_ATTEMPTS || !running || playbackSuspended
                        || !isRetryableUpstreamError(error, requestedUrl)) throw error;
                SystemClock.sleep((long) UPSTREAM_RETRY_DELAY_MS * attempt);
            } finally {
                if (!handedOff && connection != null) connection.disconnect();
            }
        }
    }

    private final class RecordingInputStream extends FilterInputStream {
        private final HttpURLConnection connection;
        private final long length;
        private long received;
        private boolean eof, closed;
        RecordingInputStream(InputStream input, HttpURLConnection connection, long length) {
            super(input); this.connection = connection; this.length = length;
        }
        @Override public int read() throws IOException {
            if (!running || playbackSuspended || closed) throw new SocketException("Proxy unavailable");
            int value = in.read(); if (value < 0) eof = true; else received++;
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int count) throws IOException {
            if (!running || playbackSuspended || closed) throw new SocketException("Proxy unavailable");
            int size = in.read(buffer, offset, count);
            if (size < 0) eof = true; else received += size;
            return size;
        }
        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            boolean complete = length >= 0 ? received == length : eof;
            // Cancel first on abort; closing an unconsumed OkHttp body may try
            // to drain it. Fully consumed responses retain the pooled connection.
            if (!complete) connection.disconnect();
            try { in.close(); } catch (IOException error) {
                connection.disconnect(); throw error;
            }
        }
    }

    ProxyResponse fetchForRecording(String originUrl, String token, String publicPrefix)
            throws IOException {
        String requestedUrl = recordingUrl(originUrl, token);
        return recordingResponse(requestedUrl, fetch(requestedUrl), publicPrefix);
    }

    private ProxyResponse recordingResponse(String requestedUrl, ProxyResponse response,
            String publicPrefix) {
        if (!isPlaylist(requestedUrl, response.contentType) && !isPlaylistBody(response.body)) {
            return response;
        }
        String internalPrefix = "http://"+loopbackHost+":" + serverSocket.getLocalPort()
                + "/proxy/";
        // Playback may return a cached manifest with direct segment URLs. Recording
        // must also forward those resources: their CDN need not allow browser CORS.
        String playlist = HlsRecordingPlaylist.rewrite(new String(response.body, UTF_8),
                requestedUrl, internalPrefix, publicPrefix, this::proxyUrl);
        return new ProxyResponse(response.contentType, playlist.getBytes(UTF_8));
    }

    static void configureCmgUpdateTags(int initialUpdateTag, int stableUpdateTag) {
        synchronized (CMG_DECRYPT_LOCK) {
            cmgConfigurationGeneration++;
            cmgInitialUpdateTag = initialUpdateTag;
            cmgStableUpdateTag = stableUpdateTag;
            cmgFirstStateNalPending = initialUpdateTag != 0 && initialUpdateTag != stableUpdateTag;
            cmgSessionWarmed = false;
            cmgLiveVideoDecodeEnabled = false;
            AppLog.i(TAG, "CMG proxy update tags initial="
                    + String.format(Locale.US, "%08x", initialUpdateTag)
                    + " stable=" + String.format(Locale.US, "%08x", stableUpdateTag));
        }
    }

    static void configureCmgContext(String playerTag, long initTimeMs, long updateBaseTimeMs) {
        synchronized (CMG_DECRYPT_LOCK) {
            cmgConfigurationGeneration++;
            cmgPlayerTag = playerTag == null ? "" : playerTag;
            cmgClockBaseTimeMs = updateBaseTimeMs > 0L ? updateBaseTimeMs : initTimeMs;
            cmgClockOffsetMs = 0;
        }
    }

    static void configureCmgRuntimeClock(long baseTimeMs, int clockOffsetMs) {
        synchronized (CMG_DECRYPT_LOCK) {
            cmgConfigurationGeneration++;
            if (baseTimeMs > 0L) {
                cmgClockBaseTimeMs = baseTimeMs;
            }
            long inferredOffsetMs = baseTimeMs - System.currentTimeMillis();
            if (clockOffsetMs == 0 && Math.abs(inferredOffsetMs) > 5000L
                    && inferredOffsetMs >= Integer.MIN_VALUE
                    && inferredOffsetMs <= Integer.MAX_VALUE) {
                cmgClockOffsetMs = (int) inferredOffsetMs;
            } else {
                cmgClockOffsetMs = clockOffsetMs;
            }
            AppLog.i(TAG, "CMG proxy runtime clock base=" + cmgClockBaseTimeMs
                    + " offsetMs=" + cmgClockOffsetMs);
        }
    }

    static void resetCmgSessionForChannelSwitch() {
        synchronized (CMG_DECRYPT_LOCK) {
            cmgConfigurationGeneration++;
            if (CjsPluginRuntime.isNativeLoaded("yangshipin.cn")) {
                NativeCmgDecryptor.resetRuntimeForProbe();
            }
            cmgSessionWarmed = false;
            cmgLiveVideoDecodeEnabled = false;
            cmgInitialUpdateTag = 0;
            cmgStableUpdateTag = 0;
            cmgFirstStateNalPending = false;
            cmgVclSinceRuntimeRestart = 0;
            cmgPlayerTag = "";
            cmgClockBaseTimeMs = 0L;
            cmgClockOffsetMs = 0;
            AppLog.i(TAG, "CMG session reset for channel switch");
        }
    }

    void setWebRequestHeaders(String referer, String userAgent, String cookies) {
        setWebRequestHeaders(referer, userAgent, cookies, false, false);
    }

    void setWebRequestHeaders(String referer, String userAgent, String cookies,
            boolean noReferer, boolean http1) {
        setWebRequestHeaders(referer, userAgent, cookies, noReferer, http1,
                java.util.Collections.<String, String>emptyMap());
    }

    void setWebRequestHeaders(String referer, String userAgent, String cookies,
            boolean noReferer, boolean http1, java.util.Map<String, String> signedHeaders) {
        setWebRequestHeaders(referer, userAgent, cookies, noReferer, http1, signedHeaders,
                java.util.Collections.<String, String>emptyMap());
    }

    void setWebRequestHeaders(String referer, String userAgent, String cookies,
            boolean noReferer, boolean http1, java.util.Map<String, String> signedHeaders,
            java.util.Map<String, String> mediaHeaders) {
        webMediaHeaders = java.util.Collections.unmodifiableMap(new java.util.HashMap<>(mediaHeaders));
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        for (String name : new String[] {"UID", "APPID", "APPSIGN", "APPRANDOMSTR"}) {
            String value = sanitizeHeaderValue(signedHeaders.get(name));
            if (value != null) headers.put(name, value);
        }
        webSignedHeaders = java.util.Collections.unmodifiableMap(headers);
        webReferer = sanitizeHeaderValue(referer);
        webUserAgent = sanitizeHeaderValue(userAgent);
        webCookies = sanitizeHeaderValue(cookies);
        webNoReferer = noReferer;
        webHttp1 = http1;
    }

    void configureCjsTransformer(String transformer, String[] arguments,
            String[] mediaHosts) {
        cjsTransformer = transformer == null ? null : transformer.trim();
        cjsTransformerArgs = arguments == null ? null : arguments.clone();
        cjsMediaHosts = mediaHosts == null ? null : mediaHosts.clone();
        AppLog.i(TAG, "Configured CJS media transformer=" + cjsTransformer);
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket socket = serverSocket.accept();
                synchronized(clientSockets) {
                    if(!running){socket.close();continue;}
                    clientSockets.add(socket);
                }
                try {
                    workers.execute(new Runnable() {
                        @Override
                        public void run() {
                            handle(socket);
                        }
                    });
                } catch (RejectedExecutionException closing) {
                    // close() can stop the pool just after accept() returns.
                    try { socket.close(); } catch (IOException ignored) { }
                    synchronized(clientSockets){clientSockets.remove(socket);}
                    if (running) AppLog.w(TAG, "Proxy worker rejected connection", closing);
                }
            } catch (IOException error) {
                if (running) {
                    AppLog.e(TAG, "Proxy accept failed", error);
                }
            }
        }
    }

    private void handle(Socket socket) {
        final long httpAttempt = playbackHttpError.token();
        try {
            socket.setSoTimeout(15000);
            socket.setTcpNoDelay(true);
            socket.setSendBufferSize(256 * 1024);
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            BufferedOutputStream output = new BufferedOutputStream(new PlayerOutputStream(socket.getOutputStream()));
            String requestLine = readAsciiLine(input);
            String rangeHeader = readRangeHeader(input);
            if (requestLine == null || !requestLine.startsWith("GET ")) {
                writeError(output, 400, "Bad request");
                return;
            }

            int pathEnd = requestLine.indexOf(' ', 4);
            String path = pathEnd < 0 ? "" : requestLine.substring(4, pathEnd);
            boolean directMedia = path.startsWith("/media/");
            String prefix = directMedia ? "/media/" : "/proxy/";
            if (!path.startsWith(prefix)) {
                writeError(output, 404, "Not found");
                return;
            }

            String token = path.substring(prefix.length());
            if (!directMedia && token.endsWith(".m3u8")) {
                token = token.substring(0, token.length() - ".m3u8".length());
            } else if (directMedia && token.endsWith(".mp3")) {
                token = token.substring(0, token.length() - ".mp3".length());
            }
            String originUrl = decodeProxyToken(token);
            if (!running || playbackSuspended) throw new java.io.InterruptedIOException("Playback suspended");
            PlaybackReplayCache.Hit cached = replayCache.open(originUrl, rangeHeader);
            if (cached != null) {
                try {
                    writeStreamingHeaders(output, cached.partial ? 206 : 200, cached.type,
                            cached.end - cached.start + 1, cached.partial
                            ? "bytes " + cached.start + "-" + cached.end + "/" + cached.total : null);
                    cached.copy(output, streamCopyBuffer.get());
                    lastMediaSegmentServedAt = SystemClock.elapsedRealtime();
                } catch (IOException error) {
                    if (error instanceof PlaybackReplayCache.ReadFailure) replayCache.readFailed(originUrl);
                    throw new StreamingResponseException(error);
                } finally { try { cached.close(); } catch (IOException ignored) {} }
                return;
            }
            if (!needsCjsTransform(originUrl) && (directMedia || canStreamWithoutRewrite(originUrl))
                    && !hasAesSegmentKey(originUrl)
                    && !hasGenericSegmentTask(originUrl)) {
                streamUpstream(originUrl, rangeHeader, output, directMedia);
                return;
            }
            if (!metadataProbeMode && !finiteMedia && needsCmgDecrypt(originUrl)
                    && isTransportStream(originUrl, null) && aesSegmentKey(originUrl) == null) {
                streamCmgSegment(originUrl, output);
                return;
            }
            if (!metadataProbeMode && !finiteMedia && needsH5eDecrypt(originUrl)
                    && isTransportStream(originUrl, null) && aesSegmentKey(originUrl) == null
                    && supportsH5eStreaming()) {
                streamH5eSegment(originUrl, output);
                return;
            }
            ProxyResponse response = fetch(originUrl);
            if (!running) {
                return;
            }
            boolean transportStream = isTransportStream(originUrl, response.contentType);
            if (transportStream) inspectStartupVideoCodec(response.body);
            if (transportStream || segmentDurations.containsKey(originUrl)) startupCost("media-ready", -1);
            if (sourceVideoProbeEnabled && transportStream) {
                sampleSourceVideo(response.body);
            }
            writeOk(output, response.contentType, response.body);
            // Cache the delivered, rewritten/decrypted representation, never ciphertext.
            // fetch() returns the complete transformed body with a 200 response,
            // including when IJK sent Range: bytes=0- for the manifest.
            if (finiteMedia)
                replayCache.offer(originUrl, response.contentType, null, response.body.length,
                        0L, response.body, 0, response.body.length);
            if (transportStream && httpAttempt == playbackHttpError.token()) {
                if (VideoScreenshot.needsSegmentCache() && response.body.length <= 16 * 1024 * 1024)
                    screenshotSegment = response.body;
                lastMediaSegmentServedAt = SystemClock.elapsedRealtime();
            }
            if (rangeHeader == null) {
                HlsSegmentBitrate.Sample sample = segmentBitrate.begin(originUrl);
                if (sample != null) {
                    sample.add(response.body, 0, response.body.length);
                    segmentBitrate.complete(sample, SystemClock.elapsedRealtime());
                }
            }
        } catch (Exception error) {
            if (!running || isPlayerDisconnect(error)) {
                return;
            }
            AppLog.e(TAG, "Proxy request failed", error);
            playbackHttpError.record(httpAttempt, error);
            if (httpAttempt == playbackHttpError.token()) lastPlaybackFailure = error;
            // The player already received a 200/206 header and part of the media.
            // Close that response so its HTTP reader can reconnect; appending a
            // second HTTP error response here corrupts the compressed stream.
            if (error instanceof StreamingResponseException) {
                return;
            }
            try {
                boolean forbidden = PlaybackHttpError.isForbidden(error);
                writeError(socket.getOutputStream(), forbidden ? 403 : 502,
                        forbidden ? "Forbidden" : "Upstream failed");
            } catch (IOException ignored) {
                // The player may already have closed the connection.
            }
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            synchronized(clientSockets){clientSockets.remove(socket);}
        }
    }

    private static boolean isPlayerDisconnect(Exception error) {
        // Only failures from the local player output are benign. An upstream
        // socket failure (including EOF/reset) must still reach recovery logic.
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 16; depth++) {
            if (cause instanceof PlayerOutputFailure) return true;
            cause = cause.getCause();
        }
        return false;
    }

    private static final class PlayerOutputFailure extends IOException {
        PlayerOutputFailure(IOException cause) { super(cause); }
    }

    private static final class PlayerOutputStream extends java.io.OutputStream {
        private final java.io.OutputStream output;
        PlayerOutputStream(java.io.OutputStream output) { this.output = output; }
        @Override public void write(int value) throws IOException {
            try { output.write(value); } catch (IOException e) { throw new PlayerOutputFailure(e); }
        }
        @Override public void write(byte[] bytes, int offset, int count) throws IOException {
            try { output.write(bytes, offset, count); } catch (IOException e) { throw new PlayerOutputFailure(e); }
        }
        @Override public void flush() throws IOException {
            try { output.flush(); } catch (IOException e) { throw new PlayerOutputFailure(e); }
        }
    }

    private ProxyResponse fetch(String originUrl) throws IOException {
        if (!running) throw new SocketException("Proxy closed");
        if (playbackSuspended) throw new java.io.InterruptedIOException("HLS downloads suspended");
        byte[] startup = startupPlaylists.take(originUrl, SystemClock.elapsedRealtime());
        if (startup != null) return new ProxyResponse("application/vnd.apple.mpegurl", startup);
        byte[] resolved = resolvedPlaylists.take(originUrl, SystemClock.elapsedRealtime());
        if (resolved != null) {
            ProxyResponse response = new ProxyResponse("application/vnd.apple.mpegurl",
                    rewritePlaylist(originUrl, originUrl, new String(resolved, UTF_8)).getBytes(UTF_8));
            startManifestAhead(originUrl);
            return response;
        }
        ProxyResponse ahead = takeManifestAhead(originUrl);
        if (ahead != null) { startManifestAhead(originUrl); return ahead; }
        ProxyResponse response = fetchNetwork(originUrl);
        startManifestAhead(originUrl);
        return response;
    }

    private ProxyResponse fetchNetwork(String originUrl) throws IOException {
        if (playbackSuspended) throw new java.io.InterruptedIOException("HLS downloads suspended");
        if (metadataProbeMode) synchronized (probeManifests) {
            ProbeManifestSnapshot saved = probeManifests.get(originUrl);
            if (saved != null && SystemClock.elapsedRealtime() - saved.created < 10000L) return saved.response;
            probeManifests.remove(originUrl);
        }
        IOException lastError = null;
        boolean cctvSegment = needsH5eDecrypt(originUrl)
                && isTransportStream(originUrl, null);
        int attemptLimit = cctvSegment ? 1 : CCTV_EDGE_MAX_ATTEMPTS;
        for (int attempt = 1; attempt <= attemptLimit; attempt++) {
            if (playbackSuspended || Thread.currentThread().isInterrupted())
                throw new java.io.InterruptedIOException("HLS download cancelled");
            try {
                ProxyResponse response = fetchOnce(originUrl);
                if (metadataProbeMode && response.body.length <= 256 * 1024 && isPlaylistBody(response.body))
                    synchronized (probeManifests) {
                        probeManifests.put(originUrl,new ProbeManifestSnapshot(response));
                        while (probeManifests.size() > 8) probeManifests.remove(probeManifests.keySet().iterator().next());
                    }
                return response;
            } catch (IOException error) {
                lastError = error;
                boolean edgeNotReady = isCctvEdgeNotReady(error, originUrl);
                noteHttp2Failure(originUrl, error);
                // CCTV segment tasks already perform fresh-connection retries. Repeating
                // that whole task here multiplied one bad segment into a long request stall.
                int maxAttempts = cctvSegment ? 1 : edgeNotReady
                        ? CCTV_EDGE_MAX_ATTEMPTS : UPSTREAM_MAX_ATTEMPTS;
                if (attempt == maxAttempts
                        || !isRetryableUpstreamError(error, originUrl)) {
                    throw error;
                }
                AppLog.w(TAG, "Retrying upstream request attempt=" + (attempt + 1)
                        + "/" + maxAttempts + " " + segmentName(originUrl)
                        + " after " + error.getClass().getSimpleName()
                        + (edgeNotReady ? " edge-not-ready" : ""));
                int delayMs = edgeNotReady
                        ? CCTV_EDGE_RETRY_DELAY_MS : UPSTREAM_RETRY_DELAY_MS;
                long retryStarted = SystemClock.elapsedRealtime();
                SystemClock.sleep((long) delayMs * attempt);
                startupCost("重试", SystemClock.elapsedRealtime() - retryStarted);
            }
        }
        throw lastError == null ? new IOException("Upstream request failed") : lastError;
    }

    private static boolean isRetryableUpstreamError(IOException error, String originUrl) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof SocketTimeoutException
                    || cause instanceof ConnectException
                    || cause instanceof UnknownHostException
                    || cause instanceof ProtocolException
                    || cause instanceof SocketException) {
                return true;
            }
            cause = cause.getCause();
        }
        String message = error.getMessage();
        return message != null && (message.startsWith("Upstream HTTP 5")
                || isCctvEdgeNotReady(error, originUrl));
    }

    private static boolean isCctvEdgeNotReady(IOException error, String originUrl) {
        if (!needsH5eDecrypt(originUrl) || !isTransportStream(originUrl, null)) {
            return false;
        }
        String message = error.getMessage();
        return message != null && (message.startsWith("Upstream HTTP 404")
                || message.startsWith("Upstream HTTP 408")
                || message.startsWith("Upstream HTTP 425")
                || message.startsWith("Upstream HTTP 429"));
    }

    private ProxyResponse fetchOnce(String originUrl) throws IOException {
        long fetchStartedAt = SystemClock.elapsedRealtime();
        if (!running) {
            throw new SocketException("Proxy closed");
        }
        byte[] prefetched = genericPrefetchedSegment(originUrl);
        if (prefetched != null) {
            return new ProxyResponse("video/MP2T", prefetched);
        }
        AesSegmentKey aesKey = aesSegmentKey(originUrl);
        if (aesKey != null) {
            return new ProxyResponse("video/MP2T", decryptAes128Segment(originUrl, aesKey));
        }
        if (isTransportStream(originUrl, null) && needsH5eDecrypt(originUrl)) {
            return new ProxyResponse("video/MP2T", getCctvSegment(originUrl));
        }
        if (isTransportStream(originUrl, null) && needsCmgDecrypt(originUrl)) {
            return new ProxyResponse("video/MP2T", getCmgSegment(originUrl));
        }
        if (isTransportStream(originUrl, null) && needsCjsTransform(originUrl)) {
            return new ProxyResponse("video/MP2T", transformCjsSegment(originUrl));
        }

        HttpURLConnection connection = openUpstreamConnection(originUrl);
        connection.setConnectTimeout(UPSTREAM_CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(needsH5eDecrypt(originUrl)
                ? 10000 : UPSTREAM_READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        applyRequestHeaders(connection, originUrl);
        boolean responseConsumed = false;
        try {
            connection.connect();
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Upstream HTTP " + status);
            }

            String contentType = connection.getContentType();
            boolean mediaResponse = !isPlaylist(originUrl,contentType)
                    && (segmentDurations.containsKey(originUrl) || isTransportStream(originUrl,contentType));
            byte[] body = readUpstreamFully(connection.getInputStream(),
                    contentLength(connection), isPlaylist(originUrl, contentType)
                            ? MAX_PLAYLIST_RESPONSE_BYTES : MAX_BUFFERED_RESPONSE_BYTES,mediaResponse);
            responseConsumed = true;
            if (body.length == 0 && isPlaylist(originUrl, contentType)) {
                throw new IOException("HLS playlist response is empty");
            }
            if (!running) {
                throw new SocketException("Proxy closed");
            }
            if (isPlaylist(originUrl, contentType) || isPlaylistBody(body)) {
                startupCost("清单", SystemClock.elapsedRealtime() - fetchStartedAt);
                long rewriteStarted = SystemClock.elapsedRealtime();
                /* HttpURLConnection follows redirects, but HLS relative URIs are
                 * relative to the final playlist response URL, not the URL that
                 * initiated the request.  Some IPTV gateways redirect the manifest
                 * to a short-lived edge host; resolving its segments against the
                 * gateway produces valid-looking URLs that all return 404. */
                String responseUrl = connection.getURL().toString();
                String playlist = rewritePlaylist(originUrl, responseUrl,
                        new String(body, UTF_8));
                startupCost("清单处理", SystemClock.elapsedRealtime() - rewriteStarted);
                return new ProxyResponse("application/vnd.apple.mpegurl", playlist.getBytes(UTF_8));
            }

            if (dolbyInitUrls.contains(originUrl)) normalizeDolbyInit(body, 0, body.length);
            return new ProxyResponse(contentType == null ? "application/octet-stream" : contentType, body);
        } finally {
            if (!responseConsumed) {
                startupCost("失败请求", SystemClock.elapsedRealtime() - fetchStartedAt);
                connection.disconnect();
            }
        }
    }

    final HlsDecoderHandoff decoderHandoff = new HlsDecoderHandoff();

    private String rewritePlaylist(String policyUrl, String responseUrl, String body)
            throws IOException {
        return decoderHandoff.playlist(responseUrl,
                rewritePlaylistContent(policyUrl, responseUrl, body));
    }

    private String rewritePlaylistContent(String policyUrl, String responseUrl, String body)
            throws IOException {
        verifyCatchupTimeline(responseUrl,body);
        synchronized(knownHlsPlaylists){knownHlsPlaylists.put(policyUrl,true);knownHlsPlaylists.put(responseUrl,true);}
        URI base = URI.create(responseUrl);
        body = body.replace("\r", "");
        if(catchupStart>=0&&!body.contains("#EXT-X-STREAM-INF")){
            // A requested, completed programme starts at its first returned segment.
            // Live-edge defaults and EXT-X-START must not move playback to the end.
            body=body.replaceAll("(?m)^#EXT-X-START:[^\\n]*(?:\\n|$)","");
            body=body.replaceFirst("#EXTM3U","#EXTM3U\n#EXT-X-START:TIME-OFFSET=0,PRECISE=YES");
            finiteMedia=true;
        }
        String[] lines = body.split("\n", -1);
        if (!body.contains("#EXT-X-STREAM-INF")) segmentBitrate.register(responseUrl, lines);
        /* Generic third-party HLS may still use the proxy when a WebView supplied
         * Referer/Cookie headers. It only needs URL rewriting in that case: do not
         * apply rendition probing, CCTV live-edge holdback, segment prefetch or
         * decrypt startup gates. */
        if (!needsSpecialDecrypt(policyUrl)) {
            if (body.contains("#EXT-X-STREAM-INF")) {
                HlsMediaTracks.Manifest manifest = HlsMediaTracks.parseMaster(responseUrl, body);
                String rewritten = rewriteGenericMasterPlaylist(base, lines, manifest);
                mediaTrackPolicyUrl = policyUrl;
                mediaTrackManifest = manifest;
                return rewritten;
            }
            if (!remoteConsumer && !readingStartupManifest
                    && canUseDirectHttpHlsUrl(policyUrl) && canUseDirectHttpHlsUrl(responseUrl)) {
                String nativePlaylist = HlsStartupManifest.directMediaPlaylist(responseUrl, body,
                        this::canUseDirectHttpHlsUrl);
                if (nativePlaylist != null){nativeMediaDownloads=true;return nativePlaylist;}
            }
            return rewriteGenericMediaPlaylist(policyUrl, base, lines, body.length(),
                    catchupStart<0&&!isFiniteMediaPlaylist(body));
        }
        if (body.contains("#EXT-X-STREAM-INF")) {
            return rewriteMasterPlaylist(base, lines);
        }
        /*
         * The startup gate and rolling history below are live-stream behavior. Applying
         * them to VOD used to discard most segments and remove #EXT-X-ENDLIST, which made
         * CCTV programme pages start on a grey frame or expose a broken duration. Keep a
         * finite playlist intact and only rewrite its resource URLs through this proxy.
         */
        finiteMedia = catchupStart>=0||isFiniteMediaPlaylist(body);
        if (!finiteMedia) {
            String buffered = rewriteBufferedMediaPlaylist(policyUrl, base, lines);
            if (buffered != null) {
                return buffered;
            }
        }
        return rewriteDirectPlaylist(base, lines, body.length());
    }

    void setRemoteConsumer(boolean enabled) {
        remoteConsumer = enabled;
    }

    /**
     * Plain media objects do not need playlist rewriting or proprietary decryption.
     * Stream them as they arrive instead of allocating one full segment byte array and
     * waiting for the entire download before IJK can consume the first byte.
     */
    private boolean canStreamWithoutRewrite(String originUrl) {
        if (dolbyInitUrls.contains(originUrl)) return false;
        if (needsSpecialDecrypt(originUrl) || isPlaylist(originUrl, null)) {
            return false;
        }
        // Manifest-declared media need not have .ts/.m4s suffixes (PHP/signed URLs).
        if(segmentDurations.containsKey(originUrl))return true;
        try {
            String path = URI.create(originUrl).getPath().toLowerCase(Locale.US);
            return path.endsWith(".ts") || path.endsWith(".m4s")
                    || path.endsWith(".mp4") || path.endsWith(".flv")
                    || path.endsWith(".mkv") || path.endsWith(".webm")
                    || path.endsWith(".mov") || path.endsWith(".avi")
                    || path.endsWith(".cmfv")
                    || path.endsWith(".cmfa") || path.endsWith(".aac")
                    || path.endsWith(".mp3") || path.endsWith(".ac3")
                    || path.endsWith(".ec3") || path.endsWith(".vtt")
                    || path.endsWith(".webvtt") || path.endsWith(".key")
                    || path.endsWith(".bin");
        } catch (RuntimeException error) {
            return false;
        }
    }

    private void streamUpstream(String originUrl, String rangeHeader, OutputStream output, boolean directMedia)
            throws IOException {
        IOException lastError = null;
        long requestStarted = SystemClock.elapsedRealtime();
        for (int attempt = 1; attempt <= UPSTREAM_MAX_ATTEMPTS; attempt++) {
            HttpURLConnection connection = null;
            boolean responseStarted = false;
            boolean responseCompleted = false;
            try {
                connection = openUpstreamConnection(originUrl);
                connection.setConnectTimeout(UPSTREAM_CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(UPSTREAM_READ_TIMEOUT_MS);
                connection.setInstanceFollowRedirects(true);
                applyRequestHeaders(connection, originUrl);
                // Resume offsets must refer to the original media bytes, not
                // an automatically decompressed HTTP content encoding.
                connection.setRequestProperty("Accept-Encoding", "identity");
                if (rangeHeader != null) {
                    connection.setRequestProperty("Range", rangeHeader);
                    // HLS byte ranges are separate, fully consumed HTTP bodies.
                    // Keep their shared TLS connection instead of handshaking per range.
                    if (android.os.Build.VERSION.SDK_INT <= 19 && !byteRangeMediaPlaylist) {
                        connection.setRequestProperty("Connection", "close");
                    }
                }
                connection.connect();
                int status = connection.getResponseCode();
                if (status != HttpURLConnection.HTTP_OK
                        && status != HttpURLConnection.HTTP_PARTIAL) {
                    throw new IOException("Upstream HTTP " + status);
                }
                String contentType = sanitizeHeaderValue(connection.getContentType());
                if (contentType == null) {
                    contentType = "application/octet-stream";
                }
                String contentRange = sanitizeHeaderValue(
                        connection.getHeaderField("Content-Range"));
                long contentLength = contentLength(connection);
                responseStarted = true;
                boolean chunked=contentLength<0;
                writeStreamingHeaders(output, status, contentType, contentLength, contentRange,chunked);
                LiteChunkedOutputStream chunks=chunked?new LiteChunkedOutputStream(output):null;
                OutputStream mediaOutput=chunked?chunks:output;

                InputStream upstream = connection.getInputStream();
                StreamRange originalRange = status == HttpURLConnection.HTTP_PARTIAL
                        ? StreamRange.parse(contentRange, contentLength)
                        : contentLength > 0 && (directMedia || canStreamWithoutRewrite(originUrl))
                        ? new StreamRange(0L, contentLength - 1L, contentLength) : null;
                String entityTag = connection.getHeaderField("ETag");
                int bodyRetries = 0;
                HlsSegmentBitrate.Sample bitrateSample = rangeHeader == null
                        && status == HttpURLConnection.HTTP_OK ? segmentBitrate.begin(originUrl) : null;
                long received = 0;
                long networkMs = SystemClock.elapsedRealtime() - requestStarted;
                HlsVideoCodecProbe.Stream startupCodec = isTransportStream(originUrl,contentType)
                        &&sourceVideoMime().isEmpty()?new HlsVideoCodecProbe.Stream():null;
                try {
                    // Reuse one copy buffer per bounded worker. A live stream requests
                    // thousands of segments; avoiding a fresh 64 KiB allocation for every
                    // request significantly reduces young-generation GC on old TVs.
                    byte[] buffer = streamCopyBuffer.get();
                    while (running) {
                        int count;
                        try {
                            long readStarted = SystemClock.elapsedRealtime();
                            count = upstream.read(buffer);
                            networkMs += SystemClock.elapsedRealtime() - readStarted;
                            if (count < 0 && contentLength >= 0 && received < contentLength) {
                                throw new IOException("Truncated upstream media body");
                            }
                        } catch (IOException readError) {
                            noteHttp2Failure(originUrl, readError);
                            // Byte-range fMP4 can lose a TLS connection mid-fragment.
                            // Resume only a finite range with a strong entity validator;
                            // never stitch different revisions or restart at byte zero.
                            if (!running || originalRange == null || entityTag == null
                                    || !entityTag.startsWith("\"") || bodyRetries >= 2
                                    || received >= contentLength) throw readError;
                            bodyRetries++;
                            try { upstream.close(); } catch (IOException ignored) {}
                            connection.disconnect();
                            connection = openUpstreamConnection(originUrl);
                            connection.setConnectTimeout(UPSTREAM_CONNECT_TIMEOUT_MS);
                            connection.setReadTimeout(UPSTREAM_READ_TIMEOUT_MS);
                            applyRequestHeaders(connection, originUrl);
                            connection.setRequestProperty("Accept-Encoding", "identity");
                            long offset = originalRange.start + received;
                            connection.setRequestProperty("Range", "bytes=" + offset
                                    + "-" + originalRange.end);
                            connection.setRequestProperty("If-Range", entityTag);
                            if (android.os.Build.VERSION.SDK_INT <= 19 && !byteRangeMediaPlaylist) {
                                connection.setRequestProperty("Connection", "close");
                            }
                            connection.connect();
                            StreamRange resumedRange = StreamRange.parse(
                                    connection.getHeaderField("Content-Range"), contentLength(connection));
                            if (connection.getResponseCode() != HttpURLConnection.HTTP_PARTIAL
                                    || !entityTag.equals(connection.getHeaderField("ETag"))
                                    || resumedRange == null || resumedRange.start != offset
                                    || resumedRange.end != originalRange.end
                                    || resumedRange.total != originalRange.total) {
                                throw new IOException("Upstream cannot safely resume media range", readError);
                            }
                            upstream = connection.getInputStream();
                            AppLog.i(TAG, "Resumed media range offset=" + offset
                                    + " remaining=" + (contentLength - received)
                                    + " attempt=" + bodyRetries);
                            continue;
                        }
                        if (count < 0) break;
                        if (received == 0) startupCost("first-data", -1);
                        mediaOutput.write(buffer, 0, count);
                        // Even Content-Length segments may be paced by the CDN.
                        // Publish every read before the next network read can block.
                        output.flush();
                        if(startupCodec!=null&&sourceVideoMime.isEmpty()){
                            startupCodec.accept(buffer,0,count);
                            sourceVideoMime=startupCodec.mime();
                        }
                        if (originalRange != null && (finiteMedia || directMedia
                                && !contentType.toLowerCase(java.util.Locale.US).contains("mpegurl")))
                            replayCache.offer(originUrl, contentType, entityTag, originalRange.total,
                                    originalRange.start + received, buffer, 0, count);
                        received += count;
                        if (bitrateSample != null) bitrateSample.add(buffer, 0, count);
                        recordUpstreamBytes(count,true);
                        streamedResponseBytes.addAndGet(count);
                    }
                    if(chunks!=null&&running)chunks.finish();
                    output.flush();
                } finally {
                    upstream.close();
                }
                streamedResponseCount.incrementAndGet();
                if (running && (contentLength < 0 || received == contentLength)) {
                    segmentBitrate.complete(bitrateSample, SystemClock.elapsedRealtime());
                }
                responseCompleted = true;
                startupCost("media-ready", -1);
                if (rangeHeader == null && status == HttpURLConnection.HTTP_OK)
                    logSegmentCost(originUrl, networkMs, 0, false);
                return;
            } catch (IOException error) {
                lastError = error;
                noteHttp2Failure(originUrl, error);
                if (responseStarted) {
                    throw new StreamingResponseException(error);
                }
                if (attempt == UPSTREAM_MAX_ATTEMPTS
                        || !isRetryableUpstreamError(error, originUrl)) {
                    throw error;
                }
                SystemClock.sleep((long) UPSTREAM_RETRY_DELAY_MS * attempt);
            } finally {
                if (connection != null && !responseCompleted) {
                    connection.disconnect();
                }
            }
        }
        throw lastError == null ? new IOException("Upstream request failed") : lastError;
    }

    private static final class StreamingResponseException extends IOException {
        StreamingResponseException(IOException cause) {
            super(cause.getMessage(), cause);
        }
    }

    private static final class StreamRange {
        final long start, end, total;
        StreamRange(long start, long end, long total) {
            this.start = start; this.end = end; this.total = total;
        }
        static StreamRange parse(String header, long length) {
            if (header == null || length <= 0) return null;
            Matcher match = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)").matcher(header);
            if (!match.matches()) return null;
            try {
                long start = Long.parseLong(match.group(1));
                long end = Long.parseLong(match.group(2));
                long total = Long.parseLong(match.group(3));
                return end >= start && total > end && end - start == length - 1
                        ? new StreamRange(start, end, total) : null;
            } catch (NumberFormatException ignored) { return null; }
        }
    }

    private static long contentLength(HttpURLConnection connection) {
        String value = connection.getHeaderField("Content-Length");
        if (value == null) {
            return -1L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            return -1L;
        }
    }

    private String rewriteDirectPlaylist(URI base, String[] lines, int sourceLength) {
        StringBuilder result = new StringBuilder(sourceLength + 256);
        for (String line : lines) {
            String rewritten = rewritePlaylistTagUris(base, line);
            if (!line.startsWith("#") && line.length() > 0) {
                rewritten = proxyUrl(HlsUri.resolve(base, line).toString());
            }
            result.append(rewritten).append('\n');
        }
        return result.toString();
    }

    /** Rewrites ordinary HLS and removes standard AES-128 from IJK's responsibility. */
    private String rewriteGenericMediaPlaylist(String playlistUrl, URI base, String[] lines,
            int sourceLength, boolean livePlaylist) {
        finiteMedia = !livePlaylist;
        for (String line : lines) {
            String tag = line.trim();
            if (tag.startsWith("#EXT-X-BYTERANGE:")
                    || tag.startsWith("#EXT-X-MAP:") && tag.contains("BYTERANGE=")) {
                byteRangeMediaPlaylist = true;
                break;
            }
        }
        StringBuilder result = new StringBuilder(sourceLength + 256);
        List<String> mediaSegments = new ArrayList<String>();
        long sequence = parseMediaSequence(lines);
        AesPlaylistKey currentKey = null;
        String durationTag = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#EXTINF:")) durationTag = trimmed;
            if (trimmed.startsWith("#EXT-X-KEY:")) {
                String attributes = trimmed.substring("#EXT-X-KEY:".length());
                Matcher methodMatcher = HLS_KEY_METHOD.matcher(attributes);
                String method = methodMatcher.find() ? methodMatcher.group(1).trim() : "";
                if ("NONE".equalsIgnoreCase(method)) {
                    currentKey = null;
                    result.append("#EXT-X-KEY:METHOD=NONE\n");
                    continue;
                }
                Matcher uriMatcher = ATTRIBUTE_URI.matcher(trimmed);
                if ("AES-128".equalsIgnoreCase(method) && uriMatcher.find()) {
                    Matcher ivMatcher = HLS_KEY_IV.matcher(attributes);
                    byte[] explicitIv = ivMatcher.find() ? parseAesIv(ivMatcher.group(1)) : null;
                    currentKey = new AesPlaylistKey(
                            HlsUri.resolve(base, uriMatcher.group(1)).toString(), explicitIv);
                    // Segments are returned clear, so prevent old FFmpeg from constructing
                    // an unsupported crypto+http URL for them.
                    result.append("#EXT-X-KEY:METHOD=NONE\n");
                    continue;
                }
                currentKey = null;
                result.append(rewritePlaylistTagUris(base, line)).append('\n');
                continue;
            }
            if (!trimmed.startsWith("#") && trimmed.length() > 0) {
                String absolute = HlsUri.resolve(base, trimmed).toString();
                if (durationTag != null) {
                    rememberSegmentDuration(absolute, Collections.singletonList(durationTag));
                    durationTag = null;
                }
                mediaSegments.add(absolute);
                if (currentKey != null) {
                    byte[] iv = currentKey.explicitIv == null
                            ? sequenceIv(sequence) : currentKey.explicitIv;
                    synchronized (aesSegmentKeys) {
                        aesSegmentKeys.put(absolute,
                                new AesSegmentKey(currentKey.keyUrl, iv));
                    }
                }
                result.append(proxyUrl(absolute)).append('\n');
                sequence++;
                continue;
            }
            result.append(rewritePlaylistTagUris(base, line)).append('\n');
        }
        if (livePlaylist && !readingStartupManifest) {
            boolean startupPrefetched = false;
            if (genericStartupPrefetchSegments == 0 && !byteRangeMediaPlaylist) {
                // Both tasks run in the background. The player waits only for
                // whichever first segment it requests, never for the whole pair.
                prefetchGenericSegments(mediaSegments, 2 + adaptiveBufferLevel);
                startupPrefetched = true;
            }
            if (genericStartupPrefetchSegments > 0
                    && !genericStartupReady(playlistUrl)) {
                List<String> startupSegments = prefetchGenericSegments(
                        mediaSegments, genericStartupPrefetchSegments);
                if (!startupSegments.isEmpty()) {
                    awaitGenericPrefetch(startupSegments);
                    markGenericStartupReady(playlistUrl);
                    startupPrefetched = true;
                }
            }
            // Keep the existing rolling prefetch for the two IPTV gateway formats.
            // On the first playlist the configured startup reserve already covers it.
            if (!startupPrefetched && needsParallelGenericPrefetch(base.toString())) {
                prefetchGenericSegments(mediaSegments, 3);
            }
        }
        return result.toString();
    }

    /** Old IJK probes every rendition of some large master playlists. Select one
     * advertised rendition in the proxy so startup remains deterministic and fast. */
    private String rewriteGenericMasterPlaylist(URI base, String[] lines, HlsMediaTracks.Manifest manifest) {
        List<Variant> variants = parseVariants(lines);
        if (variants.isEmpty()) {
            return rewriteDirectPlaylist(base, lines, 256);
        }
        // A master may offer DV Profile 5 alongside ordinary HEVC/AVC.
        // Bitrate alone must not select a DV track that this device cannot decode.
        List<Variant> compatible = new ArrayList<Variant>();
        for (Variant variant : variants) {
            String codecs = HlsMediaTracks.attributes(variant.info).get("CODECS");
            if (!DolbyFormats.isDolbyVision(codecs) || Build.VERSION.SDK_INT >= 21
                    && DolbyVisionSupport.supportsPlaylistCodec(codecs)) {
                compatible.add(variant);
            }
        }
        if (!compatible.isEmpty() && (requestedVideoVariant == null
                || requestedVideoVariant.length() == 0)) variants = compatible;
        sortVariants(variants);
        Variant selected = variants.get(preferredVariantIndex(
                variants.size(), variantQualityMode));
        for (Variant candidate : variants) {
            if (HlsUri.resolve(base, candidate.uri).toString().equals(requestedVideoVariant)) {
                selected = candidate;
                break;
            }
        }
        // Prefer Dolby on a compatible output, AAC on speakers. Dolby-only
        // playlists still work through the FFmpeg AC-3/E-AC-3 decoder.
        boolean ac3Direct = false, eac3Direct = DolbyVisionSupport.supportsAtmosDecoder();
        try {
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                ac3Direct = DolbyAudioOutput.supports(5, 48000, 6);
                eac3Direct = eac3Direct || DolbyAudioOutput.supports(6, 48000, 6);
            }
        } catch (RuntimeException ignored) {}
        int audioRank = DolbyFormats.audioRank(HlsMediaTracks.attributes(selected.info).get("CODECS"),
                ac3Direct, eac3Direct);
        for (Variant candidate : variants) {
            int rank = DolbyFormats.audioRank(HlsMediaTracks.attributes(candidate.info).get("CODECS"),
                    ac3Direct, eac3Direct);
            if (candidate.uri.equals(selected.uri)
                    && rank > audioRank) {
                selected = candidate;
                audioRank = rank;
            }
        }
        selectedAtmos = false;
        for (String line : lines) {
            if (line.startsWith("#EXT-X-MEDIA:") && HlsMediaTracks.referencesRendition(selected.info, line)) {
                String channels = HlsMediaTracks.attributes(line).get("CHANNELS");
                if (channels != null && channels.toUpperCase(Locale.US).contains("JOC")) selectedAtmos = true;
            }
        }
        rememberSelectedVariant(selected);
        manifest.selectedVideoUrl = HlsUri.resolve(base, selected.uri).toString();
        StringBuilder result = new StringBuilder(512);
        result.append("#EXTM3U\n");
        java.util.Set<String> probeRenditions = metadataProbeMode ? probeRenditions(selected.info, lines) : null;
        for (String line : lines) {
            if (line.startsWith("#EXT-X-MEDIA:")
                    && !HlsMediaTracks.referencesRendition(selected.info, line)) {
                continue;
            }
            if (probeRenditions != null && line.startsWith("#EXT-X-MEDIA:")
                    && !probeRenditions.contains(line)) continue;
            if (line.startsWith("#EXT-X-VERSION")
                    || line.startsWith("#EXT-X-INDEPENDENT-SEGMENTS")
                    || line.startsWith("#EXT-X-START")
                    || line.startsWith("#EXT-X-SESSION-DATA")
                    || line.startsWith("#EXT-X-SESSION-KEY")
                    || line.startsWith("#EXT-X-MEDIA:")) {
                result.append(rewritePlaylistTagUris(base, line)).append('\n');
            }
        }
        result.append(selected.info).append('\n')
                .append(proxyUrl(HlsUri.resolve(base, selected.uri).toString())).append('\n');
        AppLog.i(TAG, "Selected generic HLS variant quality=" + variantQualityMode
                + " choices=" + variants.size() + " bandwidth=" + selected.bandwidth
                + " advertised=" + selected.width + "x" + selected.height
                + " codecs=" + HlsMediaTracks.attributes(selected.info).get("CODECS"));
        return result.toString();
    }

    /** A resource card needs the default audio/video, not every language or subtitle. */
    private static java.util.Set<String> probeRenditions(String variant, String[] lines) {
        Map<String,String> groups = HlsMediaTracks.attributes(variant);
        Map<String,String> preferred = new LinkedHashMap<>();
        Map<String,Integer> ranks = new LinkedHashMap<>();
        java.util.Set<String> selected = new LinkedHashSet<>();
        for (String line : lines) {
            if (!line.startsWith("#EXT-X-MEDIA:")) continue;
            Map<String,String> attributes = HlsMediaTracks.attributes(line);
            String type = attributes.get("TYPE"), group = attributes.get("GROUP-ID");
            if (group == null || !group.equals(groups.get(type)) || "SUBTITLES".equals(type)) continue;
            if (!"AUDIO".equals(type) && !"VIDEO".equals(type)) { selected.add(line); continue; }
            int candidate = "YES".equals(attributes.get("DEFAULT")) ? 2
                    : "YES".equals(attributes.get("AUTOSELECT")) ? 1 : 0;
            Integer rank = ranks.get(type);
            if (rank == null || candidate > rank) { preferred.put(type, line); ranks.put(type, candidate); }
        }
        selected.addAll(preferred.values());
        return selected;
    }

    private static List<Variant> parseVariants(String[] lines) {
        List<Variant> variants = new ArrayList<Variant>();
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (!line.startsWith("#EXT-X-STREAM-INF")) {
                continue;
            }
            Matcher bandwidthMatcher = STREAM_BANDWIDTH.matcher(line);
            int bandwidth = bandwidthMatcher.find()
                    ? Integer.parseInt(bandwidthMatcher.group(1)) : 0;
            Matcher resolutionMatcher = STREAM_RESOLUTION.matcher(line);
            int width = 0;
            int height = 0;
            if (resolutionMatcher.find()) {
                width = Integer.parseInt(resolutionMatcher.group(1));
                height = Integer.parseInt(resolutionMatcher.group(2));
            }
            for (int uriIndex = index + 1; uriIndex < lines.length; uriIndex++) {
                String uri = lines[uriIndex].trim();
                if (uri.length() == 0 || uri.startsWith("#")) {
                    continue;
                }
                variants.add(new Variant(line, uri, bandwidth, width, height));
                break;
            }
        }
        return variants;
    }

    private static void sortVariants(List<Variant> variants) {
        Collections.sort(variants, new Comparator<Variant>() {
            @Override
            public int compare(Variant left, Variant right) {
                long leftQuality = left.advertisedQuality();
                long rightQuality = right.advertisedQuality();
                if (leftQuality != rightQuality) {
                    return leftQuality < rightQuality ? -1 : 1;
                }
                return left.bandwidth < right.bandwidth ? -1
                        : (left.bandwidth == right.bandwidth ? 0 : 1);
            }
        });
    }

    private boolean hasAesSegmentKey(String originUrl) {
        synchronized (aesSegmentKeys) {
            return aesSegmentKeys.containsKey(originUrl);
        }
    }

    boolean buffersGenericLiveMedia() {
        synchronized(aesSegmentKeys) {
            return !aesSegmentKeys.isEmpty() || cjsTransformer != null && cjsTransformer.length() > 0;
        }
    }

    private boolean hasGenericSegmentTask(String originUrl) {
        synchronized (genericSegmentTasks) {
            return genericSegmentTasks.containsKey(originUrl);
        }
    }

    private byte[] genericPrefetchedSegment(String originUrl) throws IOException {
        FutureTask<byte[]> task;
        synchronized (genericSegmentTasks) {
            task = genericSegmentTasks.get(originUrl);
        }
        if (task == null) {
            return null;
        }
        try {
            byte[] body = task.get();
            synchronized (genericSegmentTasks) {
                if (genericSegmentTasks.get(originUrl) == task) genericSegmentTasks.remove(originUrl);
                consumedGenericSegments.add(originUrl);
                while (consumedGenericSegments.size() > 32)
                    consumedGenericSegments.remove(consumedGenericSegments.iterator().next());
            }
            return body;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("HLS segment prefetch interrupted", error);
        } catch (ExecutionException error) {
            synchronized (genericSegmentTasks) {
                genericSegmentTasks.remove(originUrl);
            }
            Throwable cause = error.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("HLS segment prefetch failed", cause);
        }
    }

    private List<String> prefetchGenericSegments(List<String> segments, int count) {
        List<String> selected = new ArrayList<String>();
        if (!running || playbackSuspended || segments == null || segments.isEmpty()) {
            return selected;
        }
        synchronized (genericSegmentTasks) {
            // Discard completed old windows instead of retaining a TS body for
            // every playlist refresh during a long live session. Running tasks
            // remain untouched because a player request may still be using them.
            Iterator<Map.Entry<String, FutureTask<byte[]>>> entries = genericSegmentTasks.entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<String, FutureTask<byte[]>> entry = entries.next();
                if (!segments.contains(entry.getKey()) && entry.getValue().isDone()) entries.remove();
            }
        }
        // Match IJK: low/auto starts at -2; buffered modes retain -3.
        int start = Math.max(0, segments.size() - (genericStartupPrefetchSegments == 0 ? 2 : 3));
        int end = Math.min(segments.size(), start + Math.max(1, count));
        for (int index = start; index < end; index++) {
            final String url = segments.get(index);
            // Clear media streams directly to IJK. Whole-body prefetch both delays
            // its first bytes and rejects 27-45 MiB UHD segments at the memory cap.
            if(!hasAesSegmentKey(url)&&!needsCjsTransform(url)&&canStreamWithoutRewrite(url))continue;
            selected.add(url);
            FutureTask<byte[]> task;
            synchronized (genericSegmentTasks) {
                if (consumedGenericSegments.contains(url)) continue;
                task = genericSegmentTasks.get(url);
                if (task != null) {
                    continue;
                }
                task = new FutureTask<byte[]>(new Callable<byte[]>() {
                    @Override
                    public byte[] call() throws Exception {
                        AesSegmentKey key = aesSegmentKey(url);
                        if (key != null) return decryptAes128Segment(url, key);
                        // Cached segments are served directly to the decoder, so they
                        // must be transformed just like a normal foreground fetch.
                        return needsCjsTransform(url) ? transformCjsSegment(url) : downloadRaw(url);
                    }
                });
                genericSegmentTasks.put(url, task);
            }
            genericPrefetchWorkers.execute(task);
        }
        return selected;
    }

    private boolean genericStartupReady(String playlistUrl) {
        synchronized (genericStartupReady) {
            return Boolean.TRUE.equals(genericStartupReady.get(playlistUrl));
        }
    }

    private void markGenericStartupReady(String playlistUrl) {
        synchronized (genericStartupReady) {
            genericStartupReady.put(playlistUrl, true);
        }
    }

    /** Waits for the user-selected live startup reserve before exposing the playlist. */
    private void awaitGenericPrefetch(List<String> urls) {
        for (String url : urls) {
            FutureTask<byte[]> task;
            synchronized (genericSegmentTasks) {
                task = genericSegmentTasks.get(url);
            }
            if (task == null) {
                continue;
            }
            try {
                task.get();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException error) {
                synchronized (genericSegmentTasks) {
                    if (genericSegmentTasks.get(url) == task) {
                        genericSegmentTasks.remove(url);
                    }
                }
                AppLog.w(TAG, "HLS startup prefetch failed " + segmentName(url),
                        error.getCause());
            }
        }
    }

    private static boolean needsParallelGenericPrefetch(String playlistUrl) {
        if (playlistUrl == null) {
            return false;
        }
        String value = playlistUrl.toLowerCase(Locale.US);
        return value.contains(":9901/tsfile/live/") || value.contains("key=txiptv");
    }

    private AesSegmentKey aesSegmentKey(String originUrl) {
        synchronized (aesSegmentKeys) {
            return aesSegmentKeys.get(originUrl);
        }
    }

    private byte[] decryptAes128Segment(String originUrl, AesSegmentKey segmentKey)
            throws IOException {
        byte[] encrypted = downloadRaw(originUrl);
        if ((encrypted.length & 15) != 0) {
            throw new IOException("AES-128 segment length is not block aligned");
        }
        byte[] key;
        synchronized (aesKeyCache) {
            key = aesKeyCache.get(segmentKey.keyUrl);
        }
        if (key == null) {
            long keyStarted = SystemClock.elapsedRealtime();
            try { key = downloadRaw(segmentKey.keyUrl); }
            catch (IOException failure) {
                throw new IOException("AES-128 key request failed: " + failure.getMessage(), failure);
            }
            startupCost("密钥", SystemClock.elapsedRealtime() - keyStarted);
            if (key.length != 16) {
                throw new IOException("Invalid AES-128 key length " + key.length);
            }
            synchronized (aesKeyCache) {
                aesKeyCache.put(segmentKey.keyUrl, key);
            }
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(segmentKey.iv));
            long started = SystemClock.elapsedRealtime();
            byte[] decrypted = cipher.doFinal(encrypted);
            logDecryptedSegmentCost(originUrl, SystemClock.elapsedRealtime() - started);
            return decrypted;
        } catch (Exception error) {
            throw new IOException("Unable to decrypt AES-128 HLS segment", error);
        }
    }

    private byte[] transformCjsSegment(String originUrl) throws IOException {
        byte[] encrypted = downloadRaw(originUrl);
        long started = SystemClock.elapsedRealtime();
        byte[] transformed = NativeGxtvTransformer.transformTransportStream(
                encrypted, cjsTransformer, cjsTransformerArgs);
        if (transformed == null) {
            throw new IOException("CJS native transformer rejected transport stream");
        }
        logDecryptedSegmentCost(originUrl, SystemClock.elapsedRealtime() - started);
        AppLog.i(TAG, "CJS media transformed " + segmentName(originUrl)
                + " bytes=" + transformed.length + " elapsedMs="
                + (SystemClock.elapsedRealtime() - started));
        return transformed;
    }

    private byte[] downloadRaw(String originUrl) throws IOException {
        long downloadStarted = SystemClock.elapsedRealtime();
        HttpURLConnection connection = null;
        boolean consumed = false;
        try {
            connection = openUpstreamConnection(originUrl);
            connection.setConnectTimeout(UPSTREAM_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(UPSTREAM_READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            applyRequestHeaders(connection, originUrl);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Upstream HTTP " + status);
            }
            byte[] body = readUpstreamFully(connection.getInputStream(),
                    connection.getContentLength(),MAX_BUFFERED_RESPONSE_BYTES,
                    segmentDurations.containsKey(originUrl) || isTransportStream(originUrl,connection.getContentType()));
            consumed = true;
            long elapsed = SystemClock.elapsedRealtime() - downloadStarted;
            if (segmentDurations.containsKey(originUrl)) {
                if (hasAesSegmentKey(originUrl) || cjsTransformer != null) segmentDownloadTimes.put(originUrl, elapsed);
                else logSegmentCost(originUrl, elapsed, 0, false);
            }
            return body;
        } finally {
            if (connection != null && !consumed) {
                startupCost("失败请求", SystemClock.elapsedRealtime() - downloadStarted);
                connection.disconnect();
            }
        }
    }

    private static byte[] parseAesIv(String value) {
        String hex = value == null ? "" : value.trim();
        if (hex.length() > 32) {
            hex = hex.substring(hex.length() - 32);
        }
        StringBuilder padded = new StringBuilder(32);
        for (int index = hex.length(); index < 32; index++) {
            padded.append('0');
        }
        padded.append(hex);
        byte[] iv = new byte[16];
        for (int index = 0; index < iv.length; index++) {
            try {
                iv[index] = (byte) Integer.parseInt(
                        padded.substring(index * 2, index * 2 + 2), 16);
            } catch (NumberFormatException ignored) {
                return new byte[16];
            }
        }
        return iv;
    }

    private static byte[] sequenceIv(long sequence) {
        byte[] iv = new byte[16];
        for (int index = 15; index >= 8; index--) {
            iv[index] = (byte) (sequence & 0xffL);
            sequence >>>= 8;
        }
        return iv;
    }

    private static boolean isFiniteMediaPlaylist(String body) {
        if (body == null) {
            return false;
        }
        String upper = body.toUpperCase(Locale.US);
        return upper.contains("#EXT-X-ENDLIST")
                || upper.contains("#EXT-X-PLAYLIST-TYPE:VOD");
    }

    private List<PlaylistSegment> rememberBufferedSegments(String playlistUrl,
            List<PlaylistSegment> currentSegments) {
        List<PlaylistSegment> merged;

        synchronized (playlistSegmentHistory) {
            LinkedHashMap<String, PlaylistSegment> history = playlistSegmentHistory.get(playlistUrl);
            if (history == null) {
                history = new LinkedHashMap<String, PlaylistSegment>();
                playlistSegmentHistory.put(playlistUrl, history);
            }
            PlaylistSegment last = lastPlaylistSegment(history);
            PlaylistSegment firstCurrent = currentSegments.get(0);
            if (last != null) {
                YangshipinSegment lastYangshipin = parseYangshipinSegment(last.url);
                YangshipinSegment firstYangshipin = parseYangshipinSegment(firstCurrent.url);
                boolean segmentGap = lastYangshipin != null && firstYangshipin != null
                        && firstYangshipin.number > lastYangshipin.number + 1L;
                boolean sequenceGap = firstCurrent.sequence > last.sequence + 1L;
                if (segmentGap || sequenceGap) {
                    AppLog.w(TAG, "Buffered media playlist reset after live gap "
                            + segmentName(last.url) + " -> " + segmentName(firstCurrent.url));
                    history.clear();
                    synchronized (cctvStartupLock) {
                        cctvStartupReady.remove(playlistUrl);
                        cctvStartupGeneration++;
                    }
                }
            }
            for (PlaylistSegment segment : currentSegments) {
                // Signed URLs can rotate while the media sequence stays the same.
                // Keep one entry per segment; duplicate sequence entries make the
                // small live window repeat a TS and invent a following sequence.
                history.put(Long.toString(segment.sequence), segment);
            }
            while (history.size() > LIVE_PLAYLIST_HISTORY_LIMIT) {
                String firstKey = history.keySet().iterator().next();
                history.remove(firstKey);
            }

            merged = new ArrayList<PlaylistSegment>(history.values());
        }
        return merged;
    }

    private String rewriteBufferedMediaPlaylist(String playlistUrl, URI base, String[] lines)
            throws IOException {
        List<String> header = new ArrayList<String>();
        List<String> pendingTags = new ArrayList<String>();
        List<PlaylistSegment> currentSegments = new ArrayList<PlaylistSegment>();
        long mediaSequence = parseMediaSequence(lines);
        long nextSequence = mediaSequence;
        boolean sawSegment = false;
        for (String line : lines) {
            if (line.length() == 0 || line.startsWith("#EXT-X-ENDLIST")) {
                continue;
            }
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE")) {
                continue;
            }
            if (line.startsWith("#")) {
                if (sawSegment || line.startsWith("#EXTINF")
                        || line.startsWith("#EXT-X-DISCONTINUITY")
                        || line.startsWith("#EXT-X-PROGRAM-DATE-TIME")
                        || line.startsWith("#EXT-X-KEY")
                        || line.startsWith("#EXT-X-MAP")) {
                    pendingTags.add(rewritePlaylistTagUris(base, line));
                } else {
                    header.add(rewritePlaylistTagUris(base, line));
                }
                continue;
            }
            sawSegment = true;
            String absolute = HlsUri.resolve(base, line).toString();
            PlaylistSegment segment = new PlaylistSegment(nextSequence++, absolute,
                    new ArrayList<String>(pendingTags));
            rememberSegmentDuration(absolute, pendingTags);
            currentSegments.add(segment);
            pendingTags.clear();
        }
        if (currentSegments.isEmpty()) {
            return null;
        }

        List<PlaylistSegment> merged = rememberBufferedSegments(playlistUrl, currentSegments);
        Collections.sort(merged, new Comparator<PlaylistSegment>() {
            @Override
            public int compare(PlaylistSegment left, PlaylistSegment right) {
                return left.sequence < right.sequence ? -1 : (left.sequence == right.sequence ? 0 : 1);
            }
        });
        if (merged.size() > LIVE_PLAYLIST_HISTORY_LIMIT) {
            merged = merged.subList(merged.size() - LIVE_PLAYLIST_HISTORY_LIMIT, merged.size());
        }
        // Low/auto modes start from the latest complete pair. Keep more history
        // once adaptive buffering detects that processing cannot keep pace.
        if (!remoteConsumer && cctvLiveEdgeHoldBackSegments == 0) {
            int window = 2 + adaptiveBufferLevel;
            merged = new ArrayList<PlaylistSegment>(merged.subList(
                    Math.max(0, merged.size() - window), merged.size()));
        }
        // Retained history is for replay, not additional source-side headroom.
        // Counting it increased a three-segment source's holdback from one to
        // two after the first refresh, withholding its next already-ready TS.
        int holdBackSegments = cctvLiveEdgeHoldBack(playlistUrl, currentSegments.size());
        List<PlaylistSegment> playable = new ArrayList<PlaylistSegment>(holdBackSegments == 0
                ? merged : merged.subList(0, merged.size() - holdBackSegments));
        boolean startupOpened = false;
        if (isYangshipinUrl(playlistUrl)) {
            List<String> prefetchWindow;
            synchronized (cmgSegmentTasks) {
                if (!playlistUrl.equals(lastCctvPlaylistUrl)) {
                    List<FutureTask<byte[]>> staleTasks =
                            new ArrayList<FutureTask<byte[]>>(cmgSegmentTasks.values());
                    cmgSegmentTasks.clear();
                    for (FutureTask<byte[]> task : staleTasks) task.cancel(true);
                    cctvNextSegments.clear();
                    lastCctvRequestedUrl = null;
                    lastCctvPlaylistUrl = playlistUrl;
                }
                for (int index = 0; index + 1 < merged.size(); index++) {
                    cctvNextSegments.put(merged.get(index).url, merged.get(index + 1).url);
                }
                prefetchWindow = buildCmgPrefetchWindowLocked(merged);
            }
            prefetchCmgSegments(prefetchWindow);
            // Its TS request receives complete, clear PES units progressively.
            // Sending the playlist first lets demux setup overlap the CMG worker,
            // instead of serializing both behind a second playlist-level gate.
            // ICS instead buffers a whole TS before feeding its demuxer, but its
            // manifest/HTTP setup can still overlap the ordered decrypt worker.
            // Read-ahead above retains the upcoming segments without gating
            // the first playlist on their complete decryption.
            startCctvPlaylistMonitor(playlistUrl);
        } else {
            List<String> prefetchWindow;
            synchronized (cctvSegmentTasks) {
                if (!playlistUrl.equals(lastCctvPlaylistUrl)) {
                    List<FutureTask<byte[]>> staleTasks =
                            new ArrayList<FutureTask<byte[]>>(cctvSegmentTasks.values());
                    cctvSegmentTasks.clear();
                    // FutureTask.cancel() invokes done() synchronously. Clear the map
                    // before cancelling so done() cannot mutate a live values iterator.
                    for (FutureTask<byte[]> task : staleTasks) {
                        task.cancel(true);
                    }
                    synchronized (cctvSegmentCache) {
                        cctvSegmentCache.clear();
                    }
                    synchronized (cctvStartupLock) {
                        cctvStartupReady.clear();
                        cctvStartupGeneration++;
                    }
                    cctvNextSegments.clear();
                    lastCctvRequestedUrl = null;
                    lastCctvPlaylistUrl = playlistUrl;
                }
                for (int index = 0; index + 1 < merged.size(); index++) {
                    cctvNextSegments.put(merged.get(index).url, merged.get(index + 1).url);
                }
                synchronized (cctvNextSegments) {
                while (cctvNextSegments.size() > LIVE_PLAYLIST_HISTORY_LIMIT * 2) {
                    String first = cctvNextSegments.keySet().iterator().next();
                    cctvNextSegments.remove(first);
                }
                }
                prefetchWindow = buildCctvPrefetchWindowLocked(
                        merged, needsH5eDecrypt(playlistUrl));
            }
            if (needsH5eDecrypt(playlistUrl)) {
                startupOpened = ensureCctvStartupGate(playlistUrl, playable);
            }
            // Start in the same order as the playlist consumed by IJK. Otherwise
            // edge prefetch delays the first playable segment behind later segments.
            if (!remoteConsumer && lastCctvRequestedUrl == null && needsH5eDecrypt(playlistUrl)) {
                for (int index = 0; index < Math.min(2, playable.size()); index++) {
                    prefetchCctvSegment(playable.get(index).url);
                }
            }
            prefetchCctvSegments(prefetchWindow);
            startCctvPlaylistMonitor(playlistUrl);
        }
        if (startupOpened && playable.size() > cctvStartupDecryptSegments) {
            /* The first playlist exposes exactly the number of segments promised by the
             * selected mode. Downloaded-only segments stay local until their background
             * decrypt task has completed. */
            playable = new ArrayList<PlaylistSegment>(
                    playable.subList(0, cctvStartupDecryptSegments));
        }
        if (needsH5eDecrypt(playlistUrl) && !supportsH5eStreaming()) {
            /* Do not advertise an edge segment until its complete, decrypted body is ready.
             * A failed prefetch is retried by the playlist monitor while IJK consumes
             * its existing buffer instead of receiving a 502 and rebuilding the channel. */
            // During low-latency startup retain the next queued segment as well:
            // its HTTP request waits for the existing decrypt task. Advertising only
            // one segment makes the demuxer wait an entire playlist refresh interval
            // even when the next segment has already finished in the background.
            int minimumWindow = !remoteConsumer && lastCctvRequestedUrl == null
                    && cctvStartupDecryptSegments == 0 ? 2 : Math.max(1, cctvStartupDecryptSegments);
            while (playable.size() > minimumWindow
                    && !isCctvSegmentReady(playable.get(playable.size() - 1).url)) {
                playable.remove(playable.size() - 1);
            }
        }
        long firstSequence = playable.get(0).sequence;
        StringBuilder result = new StringBuilder(lines.length * 64);
        boolean wroteSequence = false;
        for (String line : header) {
            result.append(line).append('\n');
            if (line.startsWith("#EXTM3U")) {
                result.append("#EXT-X-MEDIA-SEQUENCE:").append(firstSequence).append('\n');
                wroteSequence = true;
            }
        }
        if (!wroteSequence) {
            result.append("#EXT-X-MEDIA-SEQUENCE:").append(firstSequence).append('\n');
        }
        for (PlaylistSegment segment : playable) {
            for (String tag : segment.tags) {
                result.append(tag).append('\n');
            }
            result.append(proxyUrl(segment.url)).append('\n');
        }
        return result.toString();
    }

    private boolean ensureCctvStartupGate(String playlistUrl,
            List<PlaylistSegment> playable) throws IOException {
        if (needsH5eDecrypt(playlistUrl) && supportsH5eStreaming()) return false;
        if (cctvStartupDecryptSegments == 0) return false;
        boolean yangshipin = isYangshipinUrl(playlistUrl);
        final long generation;
        synchronized (cctvStartupLock) {
            if (!running) throw new IOException("Proxy closed");
            if (Boolean.TRUE.equals(cctvStartupReady.get(playlistUrl))) {
                return false;
            }
            if (playable.size() < cctvStartupDownloadSegments) {
                throw new IOException("Waiting for CCTV startup segments: need "
                        + cctvStartupDownloadSegments);
            }
            generation = cctvStartupGeneration;
        }
        // No task locks, network I/O or Future.get while holding the startup lock.
        long startedAt = SystemClock.elapsedRealtime();
        // Queue the complete startup window immediately. The existing pipeline
        // overlaps I/O and decrypts in order, but only playable segments gate the
        // first playlist: a slow downloaded-only segment must not delay first video.
        for (int index = 0; index < cctvStartupDownloadSegments; index++) {
            if (yangshipin) prefetchCmgSegment(playable.get(index).url);
            else prefetchCctvSegment(playable.get(index).url);
        }
        /* Decrypt startup data on the persistent ordered worker instead of this HTTP
         * request thread. Its wasm runtime is then reused by the rolling prefetch
         * tasks, avoiding a full module allocation on every channel start. */
        for (int index = 0; index < cctvStartupDecryptSegments; index++) {
            if (!running) throw new IOException("Proxy closed");
            PlaylistSegment segment = playable.get(index);
            if (yangshipin) {
                getCmgSegment(segment.url);
            } else if (!isCctvSegmentReady(segment.url)) {
                getCctvSegment(segment.url);
            }
        }
        synchronized (cctvStartupLock) {
            if (!running || generation != cctvStartupGeneration) {
                throw new IOException("CCTV startup invalidated");
            }
            if (Boolean.TRUE.equals(cctvStartupReady.get(playlistUrl))) return false;
            cctvStartupReady.put(playlistUrl, true);
        }
        AppLog.i(TAG, "CCTV startup gate ready queued="
                + cctvStartupDownloadSegments
                + " playable=" + cctvStartupDecryptSegments + " elapsedMs="
                + (SystemClock.elapsedRealtime() - startedAt)
                + " first=" + segmentName(playable.get(0).url)
                + " last=" + segmentName(
                        playable.get(cctvStartupDownloadSegments - 1).url));
        return true;
    }

    private int cctvLiveEdgeHoldBack(String playlistUrl, int segmentCount) {
        if (!needsH5eDecrypt(playlistUrl)) {
            return 0;
        }
        return cctvLiveEdgeHoldBackForSegmentCount(segmentCount);
    }

    private byte[] getCctvSegment(final String originUrl) throws IOException {
        ownsH5eWork=true;
        FutureTask<byte[]> task;
        boolean created = false;
        byte[] cached;
        String cachedNext = null;
        synchronized (cctvSegmentTasks) {
            synchronized (cctvSegmentCache) {
                cached = cctvSegmentCache.get(originUrl);
            }
            if (cached != null) {
                task = null;
                lastCctvRequestedUrl = originUrl;
                cachedNext = cctvNextSegments.get(originUrl);
            } else {
                task = cctvSegmentTasks.get(originUrl);
                if (task == null) {
                    task = newCctvSegmentTask(originUrl);
                    cctvSegmentTasks.put(originUrl, task);
                    created = true;
                }
            }
        }
        if (cached != null) {
            if (cachedNext != null) {
                prefetchCctvSegment(cachedNext);
            }
            return cached;
        }
        /* Never run this FutureTask on a proxy request thread. Doing so lets
         * simultaneous HLS requests bypass the single ordered decrypt worker,
         * creating several independent wasm states and decrypting N+1 before N. */
        if (created) {
            cctvPrefetchWorkers.execute(task);
        }
        try {
            byte[] body = task.get();
            String next;
            synchronized (cctvSegmentTasks) {
                if (cctvSegmentTasks.get(originUrl) == task) {
                    cctvSegmentTasks.remove(originUrl);
                }
                lastCctvRequestedUrl = originUrl;
                next = cctvNextSegments.get(originUrl);
            }
            if (next != null) {
                prefetchCctvSegment(next);
            }
            if (parallelCctvDecrypt || supportsH5eStreaming()) {
                return body;
            }
            byte[] decrypted = decryptCctvSegment(body, originUrl, false);
            synchronized (cctvSegmentCache) {
                cctvSegmentCache.put(originUrl, decrypted);
            }
            return decrypted;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while loading CCTV segment", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("Unable to load CCTV segment", cause);
        }
    }

    private byte[] getCmgSegment(final String originUrl) throws IOException {
        byte[] cached;
        String cachedNext = null;
        FutureTask<byte[]> task = null;
        synchronized (cmgSegmentTasks) {
            // Check cache and task together: completion may remove the task between
            // separate lookups, causing the same stateful segment to decrypt twice.
            synchronized (cmgSegmentCache) {
                cached = cmgSegmentCache.get(originUrl);
            }
            if (cached != null) {
                lastCctvRequestedUrl = originUrl;
                cachedNext = cctvNextSegments.get(originUrl);
            } else {
                task = cmgSegmentTasks.get(originUrl);
                if (task == null) {
                    task = newCmgSegmentTask(originUrl);
                    cmgSegmentTasks.put(originUrl, task);
                    CMG_STREAM_WORKER.execute(task);
                }
            }
        }
        if (cached != null) {
            if (cachedNext != null) prefetchCmgSegment(cachedNext);
            return cached;
        }
        try {
            byte[] body = task.get();
            String next;
            synchronized (cmgSegmentTasks) {
                if (cmgSegmentTasks.get(originUrl) == task) cmgSegmentTasks.remove(originUrl);
                lastCctvRequestedUrl = originUrl;
                next = cctvNextSegments.get(originUrl);
            }
            if (next != null) {
                prefetchCmgSegment(next);
            }
            return body;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while loading CMG segment", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("Unable to load CMG segment", cause);
        }
    }

    private void streamCmgSegment(String originUrl, OutputStream output) throws IOException {
        startupMilestone("player-ts-request", originUrl);
        byte[] cached;
        ProgressiveMediaBuffer media;
        synchronized (cmgSegmentTasks) {
            lastCctvRequestedUrl = originUrl;
            synchronized (cmgSegmentCache) { cached = cmgSegmentCache.get(originUrl); }
            if (cached == null && !cmgSegmentTasks.containsKey(originUrl)) {
                FutureTask<byte[]> task = newCmgSegmentTask(originUrl);
                cmgSegmentTasks.put(originUrl, task);
                CMG_STREAM_WORKER.execute(task);
            }
            media = cmgProgressive.get(originUrl);
        }
        writeProgressiveTs(output, cached, media);
        lastMediaSegmentServedAt = SystemClock.elapsedRealtime();
        synchronized (cmgSegmentCache) {
            byte[] delivered = cmgSegmentCache.get(originUrl);
            if (VideoScreenshot.needsSegmentCache() && delivered != null && delivered.length <= 16 * 1024 * 1024)
                screenshotSegment = delivered;
        }
        String next;
        synchronized (cmgSegmentTasks) {
            lastCctvRequestedUrl = originUrl;
            next = cctvNextSegments.get(originUrl);
        }
        if (next != null) prefetchCmgSegment(next);
    }

    private void streamH5eSegment(String originUrl, OutputStream output) throws IOException {
        startupMilestone("player-ts-request", originUrl);
        ownsH5eWork = true;
        byte[] cached;
        ProgressiveMediaBuffer media;
        synchronized (cctvSegmentTasks) {
            lastCctvRequestedUrl = originUrl;
            synchronized (cctvSegmentCache) { cached = cctvSegmentCache.get(originUrl); }
            if (cached == null && !cctvSegmentTasks.containsKey(originUrl)) {
                FutureTask<byte[]> task = newCctvSegmentTask(originUrl);
                cctvSegmentTasks.put(originUrl, task);
                cctvPrefetchWorkers.execute(task);
            }
            media = h5eProgressive.get(originUrl);
        }
        writeProgressiveTs(output, cached, media);
        lastMediaSegmentServedAt = SystemClock.elapsedRealtime();
        String next;
        synchronized (cctvSegmentTasks) {
            lastCctvRequestedUrl = originUrl;
            next = cctvNextSegments.get(originUrl);
        }
        if (VideoScreenshot.needsSegmentCache()) synchronized (cctvSegmentCache) {
            byte[] delivered = cctvSegmentCache.get(originUrl);
            if (delivered != null && delivered.length <= 16 * 1024 * 1024) screenshotSegment = delivered;
        }
        if (next != null) prefetchCctvSegment(next);
    }

    private void writeProgressiveTs(OutputStream output, byte[] cached,
            ProgressiveMediaBuffer media) throws IOException {
        if (cached != null) {
            writeOk(output, "video/MP2T", cached);
            startupCost("first-data", -1);
            startupMilestone("first-player-byte", null);
        } else {
            if (media == null) throw new IOException("Progressive TS unavailable");
            boolean started = false;
            try (InputStream input = media.open()) {
                byte[] block = streamCopyBuffer.get();
                int count;
                LiteChunkedOutputStream chunks = null;
                long length = -1;
                while ((count = input.read(block)) >= 0) {
                    if (!running) throw new IOException("TS proxy closed");
                    if (!started) {
                        // Both progressive transformers preserve TS packet slots.
                        // ICS fetchFile allocates from Content-Length; unknown size
                        // grows by 32 KiB and repeatedly copies the whole segment.
                        length = media.expectedLength();
                        writeStreamingHeaders(output, 200, "video/MP2T", length, null, length < 0);
                        if (length < 0) chunks = new LiteChunkedOutputStream(output);
                        started = true;
                        startupCost("first-data", -1);
                    }
                    if (chunks != null) chunks.write(block, 0, count);
                    else output.write(block, 0, count);
                    output.flush();
                    startupMilestone("first-player-byte", null);
                    lastMediaSegmentServedAt = SystemClock.elapsedRealtime();
                }
                if (!started) throw new IOException("Empty progressive TS");
                if (chunks != null) chunks.finish();
                output.flush();
            } catch (IOException error) {
                if (isPlayerDisconnect(error)) throw error;
                if (started) throw new StreamingResponseException(error);
                throw error;
            }
        }
    }

    private void startCctvPlaylistMonitor(String playlistUrl) {
        monitoredCctvPlaylistUrl = playlistUrl;
        synchronized (cctvSegmentTasks) {
            if (cctvPlaylistMonitorStarted) {
                return;
            }
            cctvPlaylistMonitorStarted = true;
        }
        cctvPlaylistMonitor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                pollCctvPlaylistForPrefetch();
            }
        }, 1000L, 2000L, TimeUnit.MILLISECONDS);
    }

    private void pollCctvPlaylistForPrefetch() {
        String playlistUrl = monitoredCctvPlaylistUrl;
        if (!running || playbackSuspended || readAheadPaused || playlistUrl == null) {
            return;
        }
        HttpURLConnection connection = null;
        try {
            connection = openUpstreamConnection(playlistUrl);
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(2500);
            connection.setInstanceFollowRedirects(true);
            applyRequestHeaders(connection, playlistUrl);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                return;
            }
            String body = new String(readUpstreamFully(connection.getInputStream(),
                    contentLength(connection), MAX_PLAYLIST_RESPONSE_BYTES), UTF_8);
            if (!playlistUrl.equals(monitoredCctvPlaylistUrl)) {
                return;
            }
            URI base = URI.create(connection.getURL().toString());
            List<String> segments = new ArrayList<String>();
            for (String line : body.split("\\r?\\n")) {
                if (line.length() > 0 && !line.startsWith("#")) {
                    segments.add(HlsUri.resolve(base, line).toString());
                }
            }
            if (segments.isEmpty()) {
                return;
            }
            // Keep observing the manifest even while the player waits for a
            // slow TS. Otherwise the next player refresh loses intermediate
            // segments already seen and downloaded by this monitor.
            List<PlaylistSegment> observed = new ArrayList<PlaylistSegment>();
            List<String> tags = new ArrayList<String>();
            long sequence = parseMediaSequence(body.split("\\r?\\n"));
            for (String line : body.split("\\r?\\n")) {
                if (line.length() == 0) continue;
                if (line.startsWith("#")) {
                    if (line.startsWith("#EXTINF") || line.startsWith("#EXT-X-KEY")
                            || line.startsWith("#EXT-X-MAP") || line.startsWith("#EXT-X-DISCONTINUITY")
                            || line.startsWith("#EXT-X-PROGRAM-DATE-TIME"))
                        tags.add(rewritePlaylistTagUris(base, line));
                } else {
                    String absolute = HlsUri.resolve(base, line).toString();
                    observed.add(new PlaylistSegment(sequence++, absolute, new ArrayList<String>(tags)));
                    rememberSegmentDuration(absolute, tags);
                    tags.clear();
                }
            }
            if (!observed.isEmpty()) rememberBufferedSegments(playlistUrl, observed);
            List<String> prefetchWindow;
            boolean yangshipin = isYangshipinUrl(playlistUrl);
            synchronized (yangshipin ? cmgSegmentTasks : cctvSegmentTasks) {
                for (int index = 0; index + 1 < segments.size(); index++) {
                    cctvNextSegments.put(segments.get(index), segments.get(index + 1));
                }
                prefetchWindow = yangshipin
                        ? buildCmgPrefetchWindowFromUrlsLocked(segments)
                        : buildCctvPrefetchWindowFromUrlsLocked(
                                segments, needsH5eDecrypt(playlistUrl));
            }
            if (yangshipin) {
                prefetchCmgSegments(prefetchWindow);
            } else {
                prefetchCctvSegments(prefetchWindow);
            }
        } catch (Exception error) {
            if (running) {
                AppLog.d(TAG, "CCTV playlist prefetch poll skipped: " + error.getMessage());
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void prefetchCctvSegment(final String originUrl) {
        ownsH5eWork=true;
        if (!running || playbackSuspended || readAheadPaused) {
            return;
        }
        FutureTask<byte[]> task;
        boolean created = false;
        synchronized (cctvSegmentTasks) {
            synchronized (cctvSegmentCache) {
                if (cctvSegmentCache.containsKey(originUrl)) {
                    return;
                }
            }
            task = cctvSegmentTasks.get(originUrl);
            if (task == null) {
                task = newCctvSegmentTask(originUrl);
                cctvSegmentTasks.put(originUrl, task);
                created = true;
            }
        }
        if (created) {
            cctvPrefetchWorkers.execute(task);
        }
    }

    private void prefetchCctvSegments(List<String> urls) {
        for (String url : urls) {
            prefetchCctvSegment(url);
        }
    }

    private void prefetchCmgSegment(final String originUrl) {
        if (!running || playbackSuspended || readAheadPaused) {
            return;
        }
        FutureTask<byte[]> task;
        synchronized (cmgSegmentTasks) {
            synchronized (cmgSegmentCache) {
                if (cmgSegmentCache.containsKey(originUrl)) {
                    return;
                }
            }
            task = cmgSegmentTasks.get(originUrl);
            if (task == null) {
                task = newCmgSegmentTask(originUrl);
                cmgSegmentTasks.put(originUrl, task);
                // Submit under the same lock as insertion, preserving CMG state order.
                CMG_STREAM_WORKER.execute(task);
            }
        }
    }

    private void prefetchCmgSegments(List<String> urls) {
        for (String url : urls) {
            prefetchCmgSegment(url);
        }
    }

    private List<String> buildCmgPrefetchWindowLocked(List<PlaylistSegment> segments) {
        List<String> urls = new ArrayList<String>(segments.size());
        for (PlaylistSegment segment : segments) {
            urls.add(segment.url);
        }
        return buildCmgPrefetchWindowFromUrlsLocked(urls);
    }

    private List<String> buildCmgPrefetchWindowFromUrlsLocked(List<String> segments) {
        int window = remoteConsumer
                ? CMG_REMOTE_PREFETCH_WINDOW : CMG_LOCAL_PREFETCH_WINDOW;
        window = Math.max(window, 1 + adaptiveBufferLevel);
        List<String> result = new ArrayList<String>(window);
        // Match the selected startup window; low/auto exposes the latest pair.
        int initialIndex = remoteConsumer || systemPlayerStartsAtFirst ? 0 : Math.max(0, segments.size()
                - (cctvLiveEdgeHoldBackSegments == 0 ? 2 + adaptiveBufferLevel : 3));
        String next = lastCctvRequestedUrl == null
                ? (segments.isEmpty() ? null : segments.get(initialIndex))
                : cctvNextSegments.get(lastCctvRequestedUrl);
        while (next != null && result.size() < window) {
            result.add(next);
            next = cctvNextSegments.get(next);
        }
        return result;
    }

    private List<String> buildCctvPrefetchWindowLocked(List<PlaylistSegment> segments,
            boolean protectLiveEdge) {
        List<String> urls = new ArrayList<String>(segments.size());
        for (PlaylistSegment segment : segments) {
            urls.add(segment.url);
        }
        return buildCctvPrefetchWindowFromUrlsLocked(urls, protectLiveEdge);
    }

    private List<String> buildCctvPrefetchWindowFromUrlsLocked(List<String> segments,
            boolean protectLiveEdge) {
        int count = parallelCctvDecrypt ? CCTV_PARALLEL_PREFETCH_WINDOW : 1;
        count = Math.max(count, 1 + adaptiveBufferLevel);
        List<String> result = new ArrayList<String>(count);
        int holdBackSegments = protectLiveEdge
                ? cctvLiveEdgeHoldBackForSegmentCount(segments.size()) : 0;
        int initialIndex = systemPlayerStartsAtFirst ? 0
                : Math.max(0, segments.size() - holdBackSegments - 1);
        String next = lastCctvRequestedUrl == null
                ? (segments.isEmpty() ? null : segments.get(initialIndex))
                : cctvNextSegments.get(lastCctvRequestedUrl);
        while (next != null && result.size() < count) {
            result.add(next);
            next = cctvNextSegments.get(next);
        }
        return result;
    }

    private int cctvLiveEdgeHoldBackForSegmentCount(int segmentCount) {
        return Math.min(cctvLiveEdgeHoldBackSegments,
                Math.max(0, segmentCount - CCTV_MIN_PLAYABLE_SEGMENTS));
    }

    private static ExecutorService newCctvPrefetchExecutor(final int threadCount) {
        final AtomicInteger threadIds = new AtomicInteger();
        return Executors.newFixedThreadPool(threadCount, new ThreadFactory() {
            @Override
            public Thread newThread(final Runnable task) {
                return new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            task.run();
                        } finally {
                            if (BuildConfig.H5E_STREAMING_CHECKS || CjsPluginRuntime.isNativeLoaded("tv.cctv.com")) {
                                NativeH5eDecryptor.releaseThreadContext();
                            }
                        }
                    }
                }, "cctv-decrypt-" + threadIds.incrementAndGet());
            }
        });
    }

    private FutureTask<byte[]> newCctvSegmentTask(final String originUrl) {
        if (supportsH5eStreaming()) return newH5eSegmentTask(originUrl);
        // Fetch on the I/O pool as soon as the ordered task is queued. The next
        // segment can arrive while this worker decrypts the current segment.
        // cctvSegmentTasks already coalesces all consumers of the same URL.
        final FutureTask<byte[]> download = new FutureTask<byte[]>(new Callable<byte[]>() {
            @Override public byte[] call() throws Exception {
                if (!running || Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("CCTV channel switched");
                }
                return downloadCctvSegment(originUrl);
            }
        });
        genericPrefetchWorkers.execute(download);
        final long queuedAt = SystemClock.elapsedRealtime();
        return new FutureTask<byte[]>(new Callable<byte[]>() {
            @Override
            public byte[] call() throws Exception {
                try {
                    long workerAt = SystemClock.elapsedRealtime();
                    startupCost("调度", workerAt - queuedAt);
                    byte[] body;
                    try { body = download.get(); }
                    catch (ExecutionException error) {
                        Throwable cause = error.getCause();
                        if (cause instanceof Exception) throw (Exception) cause;
                        throw new IOException("CCTV download failed", cause);
                    }
                    if (!running || Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("CCTV channel switched");
                    }
                    long decryptAt = SystemClock.elapsedRealtime();
                    long cpuAt = android.os.Debug.threadCpuTimeNanos();
                    byte[] result = parallelCctvDecrypt
                            ? decryptCctvSegment(body, originUrl, true) : body;
                    AppLog.i(TAG, "CCTV pipeline segment=" + segmentName(originUrl)
                            + " queueMs=" + (workerAt - queuedAt)
                            + " ioWaitMs=" + (decryptAt - workerAt)
                            + " decryptWallMs=" + (SystemClock.elapsedRealtime() - decryptAt)
                            + " decryptCpuMs=" + ((android.os.Debug.threadCpuTimeNanos() - cpuAt) / 1000000L));
                    if (parallelCctvDecrypt) {
                        synchronized (cctvSegmentCache) {
                            cctvSegmentCache.put(originUrl, result);
                        }
                    }
                    return result;
                } catch (Exception error) {
                    AppLog.w(TAG, "CCTV segment prefetch exhausted " + segmentName(originUrl)
                            + ": " + error.getMessage());
                    throw error;
                }
            }
        }) {
            @Override
            protected void done() {
                if (!download.isDone()) download.cancel(true);
                /* Remove only this exact generation. A late failed task must never delete
                 * a newer retry for the same URL. Successful bytes remain in the LRU. */
                synchronized (cctvSegmentTasks) {
                    if (cctvSegmentTasks.get(originUrl) == this) {
                        cctvSegmentTasks.remove(originUrl);
                    }
                }
            }
        };
    }

    private FutureTask<byte[]> newH5eSegmentTask(final String originUrl) {
        final long queuedAt = SystemClock.elapsedRealtime();
        ownsH5eWork = true;
        final ProgressiveMediaBuffer media = new ProgressiveMediaBuffer(MAX_BUFFERED_RESPONSE_BYTES);
        h5eProgressive.put(originUrl, media); // Caller holds cctvSegmentTasks.
        final CmgBytePipe encrypted = new CmgBytePipe(64 * 1024);
        final OutputStream pipe = encrypted.output();
        final FutureTask<Void> download = new FutureTask<Void>(() -> {
            try { downloadEncryptedTs(originUrl, pipe, media); return null; }
            finally { try { pipe.close(); } catch (IOException ignored) {} }
        });
        genericPrefetchWorkers.execute(download);
        return new FutureTask<byte[]>(() -> {
            long started = SystemClock.elapsedRealtime();
            startupCost("调度", started - queuedAt);
            final long[] nanos = {0};
            final boolean[] first = {true};
            try {
                try { Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND); }
                catch (RuntimeException ignored) { }
                NativeH5eDecryptor.setSpsCompatibilityMode(spsCompatibilityMode);
                CmgTsStream stream = new CmgTsStream(new CmgTsStream.Decoder() {
                    public int videoPid(byte[] prefix) { return findAvcVideoPid(prefix); }
                    public byte[] decode(byte[] packets, int pid) throws IOException {
                        if (!running || Thread.currentThread().isInterrupted())
                            throw new java.io.InterruptedIOException("H5E stream cancelled");
                        long begin = System.nanoTime();
                        byte[] clear = NativeH5eDecryptor.decryptTransportStreamPart(packets, first[0]);
                        first[0] = false;
                        long previousMs = nanos[0] / 1000000L;
                        nanos[0] += System.nanoTime() - begin;
                        if (clear == null) throw new IOException("H5E progressive decrypt rejected PES");
                        noteClearVideo(clear, pid, originUrl);
                        startupCost("解密", nanos[0] / 1000000L - previousMs);
                        return clear;
                    }
                }, 8 * 1024 * 1024, false);
                try { stream.copy(new BufferedInputStream(encrypted, 32 * 1024), media::append); }
                catch (IOException error) {
                    if (download.isDone()) awaitEncryptedTsDownload(download);
                    throw error;
                }
                awaitEncryptedTsDownload(download);
                if (!running || Thread.currentThread().isInterrupted())
                    throw new java.io.InterruptedIOException("H5E stream cancelled");
                byte[] clear = media.finish();
                if (!isCompleteTransportStream(clear)) throw new IOException("Incomplete H5E TS");
                startupMilestone("first-clear-segment", originUrl);
                synchronized (cctvSegmentCache) { cctvSegmentCache.put(originUrl, clear); }
                logDecryptedSegmentCost(originUrl, nanos[0] / 1000000L);
                AppLog.i(TAG, "H5E progressive segment=" + segmentName(originUrl)
                        + " wallMs=" + (SystemClock.elapsedRealtime() - started)
                        + " decryptMs=" + nanos[0] / 1000000L
                        + " pipeReadWaitMs=" + encrypted.readWaitMs());
                inspectStartupVideoCodec(clear);
                if (sourceVideoProbeEnabled) sampleSourceVideo(clear);
                return clear;
            } catch (Exception | LinkageError error) {
                media.fail(error instanceof IOException ? (IOException) error : new IOException("H5E stream failed", error));
                if (running) AppLog.w(TAG, "H5E progressive segment failed " + segmentName(originUrl), error);
                throw error;
            }
        }) {
            protected void done() {
                media.fail(new java.io.InterruptedIOException("H5E task cancelled"));
                if (!download.isDone()) download.cancel(true);
                try { encrypted.close(); pipe.close(); } catch (IOException ignored) { }
                synchronized (cctvSegmentTasks) {
                    if (cctvSegmentTasks.get(originUrl) == this) cctvSegmentTasks.remove(originUrl);
                    if (h5eProgressive.get(originUrl) == media) h5eProgressive.remove(originUrl);
                }
            }
        };
    }

    private boolean isCctvSegmentReady(String originUrl) {
        synchronized (cctvSegmentCache) {
            return cctvSegmentCache.containsKey(originUrl);
        }
    }

    private FutureTask<byte[]> newCmgSegmentTask(final String originUrl) {
        final long queuedAt = SystemClock.elapsedRealtime();
        final long generation = cmgConfigurationGeneration;
        final ProgressiveMediaBuffer media = new ProgressiveMediaBuffer(MAX_BUFFERED_RESPONSE_BYTES);
        cmgProgressive.put(originUrl, media); // Caller holds cmgSegmentTasks.
        final CmgBytePipe encrypted = new CmgBytePipe(64 * 1024);
        final OutputStream pipe = encrypted.output();
        final FutureTask<Void> download = new FutureTask<Void>(() -> {
            try { downloadEncryptedTs(originUrl, pipe, media); return null; }
            finally { try { pipe.close(); } catch (IOException ignored) {} }
        });
        genericPrefetchWorkers.execute(download);
        return new FutureTask<byte[]>(() -> {
            final long started = SystemClock.elapsedRealtime();
            final long cpuStarted = android.os.Debug.threadCpuTimeNanos();
            startupCost("调度", started - queuedAt);
            try {
                final long[] decryptNanos = {0};
                synchronized (CMG_DECRYPT_LOCK) {
                    checkCmgGeneration(generation);
                    restartCmgRuntime("progressive TS #" + cmgTsRequestIndex.incrementAndGet());
                }
                CmgTsStream stream = new CmgTsStream(new CmgTsStream.Decoder() {
                    public int videoPid(byte[] prefix) { return findVideoPid(prefix); }
                    public byte[] decode(byte[] packets, int pid) throws IOException {
                        synchronized (CMG_DECRYPT_LOCK) {
                            checkCmgGeneration(generation);
                            long begin = System.nanoTime();
                            byte[] clear = decryptYangshipinTransportStream(packets, spsCompatibilityMode, pid);
                            noteClearVideo(clear, pid, originUrl);
                            long previousMs = decryptNanos[0] / 1000000L;
                            decryptNanos[0] += System.nanoTime() - begin;
                            // Report before publishing clear PES bytes: the decoder
                            // can render its first frame before this segment ends.
                            startupCost("解密", decryptNanos[0] / 1000000L - previousMs);
                            return clear;
                        }
                    }
                }, 8 * 1024 * 1024);
                try { stream.copy(new BufferedInputStream(encrypted, 32 * 1024), media::append); }
                catch (IOException error) {
                    if (download.isDone()) awaitEncryptedTsDownload(download);
                    throw error;
                }
                awaitEncryptedTsDownload(download); // Content-Length and network failure must validate before caching.
                checkCmgGeneration(generation);
                byte[] clear = media.finish();
                if (!isCompleteTransportStream(clear)) throw new IOException("Incomplete clear CMG TS");
                startupMilestone("first-clear-segment", originUrl);
                synchronized (cmgSegmentCache) { cmgSegmentCache.put(originUrl, clear); }
                logDecryptedSegmentCost(originUrl, decryptNanos[0] / 1000000L);
                AppLog.i(TAG, "CMG pipeline segment=" + segmentName(originUrl)
                        + " wallMs=" + (SystemClock.elapsedRealtime() - started)
                        + " decryptMs=" + decryptNanos[0] / 1000000L
                        + " pipeReadWaitMs=" + encrypted.readWaitMs()
                        + " pipeWriteWaitMs=" + encrypted.writeWaitMs()
                        + " workerCpuMs=" + (android.os.Debug.threadCpuTimeNanos() - cpuStarted) / 1000000L);
                inspectStartupVideoCodec(clear);
                if (sourceVideoProbeEnabled) sampleSourceVideo(clear);
                YangshipinSegment segment = parseYangshipinSegment(originUrl);
                if (segment != null) cmgLastYangshipinSegment = Math.max(cmgLastYangshipinSegment, segment.number);
                return clear;
            } catch (Exception error) {
                media.fail(error instanceof IOException ? (IOException)error : new IOException("CMG stream failed", error));
                if (running) AppLog.w(TAG, "CMG progressive segment failed " + segmentName(originUrl) + ": " + error.getMessage());
                throw error;
            }
        }) {
            @Override protected void done() {
                media.fail(new java.io.InterruptedIOException("CMG task cancelled"));
                if (!download.isDone()) download.cancel(true);
                try { encrypted.close(); pipe.close(); } catch (IOException ignored) {}
                synchronized (cmgSegmentTasks) {
                    if (cmgSegmentTasks.get(originUrl) == this) cmgSegmentTasks.remove(originUrl);
                    if (cmgProgressive.get(originUrl) == media) cmgProgressive.remove(originUrl);
                }
            }
        };
    }

    private void checkCmgGeneration(long generation) throws IOException {
        if (!running || Thread.currentThread().isInterrupted() || generation != cmgConfigurationGeneration)
            throw new java.io.InterruptedIOException("CMG channel switched");
    }

    private static void awaitEncryptedTsDownload(FutureTask<Void> download) throws Exception {
        try { download.get(); }
        catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception)cause;
            throw new IOException("Encrypted TS download failed", cause);
        }
    }

    private void downloadEncryptedTs(String url, OutputStream pipe, ProgressiveMediaBuffer media) throws IOException {
        long started = SystemClock.elapsedRealtime();
        boolean h5e = needsH5eDecrypt(url);
        int attempts = h5e ? CCTV_SEGMENT_MAX_ATTEMPTS : UPSTREAM_MAX_ATTEMPTS;
        for (int attempt = 1; ; attempt++) {
            HttpURLConnection connection = openUpstreamConnection(url);
            connection.setConnectTimeout(UPSTREAM_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(h5e ? CCTV_SEGMENT_READ_TIMEOUT_MS : 10000);
            connection.setInstanceFollowRedirects(true);
            applyRequestHeaders(connection, url);
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (attempt > 1) connection.setRequestProperty("Connection", "close");
            boolean consumed = false;
            int received = 0;
            long networkMs = 0;
            try {
                long begin = SystemClock.elapsedRealtime();
                int code = connection.getResponseCode();
                networkMs += SystemClock.elapsedRealtime() - begin;
                if (code < 200 || code >= 300) throw new IOException("Upstream HTTP " + code);
                int expected = connection.getContentLength();
                if (expected > MAX_BUFFERED_RESPONSE_BYTES) throw new IOException("Encrypted TS exceeds limit");
                if (expected >= TS_PACKET_SIZE * 5 && expected % TS_PACKET_SIZE == 0)
                    media.expectLength(expected);
                try (InputStream input = connection.getInputStream()) {
                    byte[] block = new byte[16 * 1024]; int count;
                    for (;;) {
                        if (!running || Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Encrypted TS download cancelled");
                        begin = SystemClock.elapsedRealtime();
                        count = input.read(block);
                        networkMs += SystemClock.elapsedRealtime() - begin;
                        if (count < 0) break;
                        if (count > 0 && received == 0) startupMilestone("encrypted-first-byte", url);
                        received += count;
                        if (received > MAX_BUFFERED_RESPONSE_BYTES) throw new IOException("Encrypted TS exceeds limit");
                        recordUpstreamBytes(count, true);
                        pipe.write(block, 0, count);
                    }
                }
                if (expected >= 0 && received != expected) throw new IOException("Truncated encrypted TS " + received + "/" + expected);
                if (received < TS_PACKET_SIZE * 5 || received % TS_PACKET_SIZE != 0) throw new IOException("Incomplete encrypted TS body");
                consumed = true;
                segmentDownloadTimes.put(url, networkMs);
                return;
            } catch (IOException error) {
                // Published ciphertext cannot be rewound. Retry only before any body bytes entered the pipe.
                if (received != 0 || attempt >= attempts || !running || Thread.currentThread().isInterrupted()
                        || !isRetryableUpstreamError(error, url)) throw error;
                NetworkClient.invalidateDns(URI.create(url).getHost());
            } finally {
                if (!consumed) { startupCost("失败请求", SystemClock.elapsedRealtime() - started); connection.disconnect(); }
            }
        }
    }

    private byte[] decryptCctvSegment(byte[] body, String originUrl, boolean background)
            throws IOException {
        long decryptStarted = SystemClock.elapsedRealtime();
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);
        } catch (RuntimeException ignored) {
        }
        byte[] decrypted;
        try {
            NativeH5eDecryptor.setSpsCompatibilityMode(spsCompatibilityMode);
            decrypted = NativeH5eDecryptor.decryptTransportStream(body);
        } catch (LinkageError error) {
            // Missing/incompatible plugins are a source failure, not a fatal
            // Error that kills the request worker (and poisons class init).
            throw new IOException("央视网解密插件未安装或不兼容，请更新插件", error);
        }
        if (decrypted == null && running) {
            /* A wasm trap invalidates only this worker's thread-local runtime.
             * Recreate it and retry the untouched TS once instead of exposing a
             * transient 502 to the player and forcing a full channel restart. */
            AppLog.w(TAG, "Resetting H5E worker after rejected TS and retrying "
                    + segmentName(originUrl));
            NativeH5eDecryptor.releaseThreadContext();
            decrypted = NativeH5eDecryptor.decryptTransportStream(body);
        }
        if (decrypted == null) {
            throw new IOException("Native H5E decryptor rejected transport stream");
        }
        logDecryptedSegmentCost(originUrl, SystemClock.elapsedRealtime() - decryptStarted);
        return decrypted;
    }

    private byte[] downloadCctvSegment(String originUrl) throws IOException {
        long downloadStarted = SystemClock.elapsedRealtime();
        int attempts = needsH5eDecrypt(originUrl)
                ? CCTV_SEGMENT_MAX_ATTEMPTS : UPSTREAM_MAX_ATTEMPTS;
        int retryDelayMs = needsH5eDecrypt(originUrl)
                ? CCTV_SEGMENT_RETRY_DELAY_MS : UPSTREAM_RETRY_DELAY_MS;
        IOException lastError = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (!running) {
                throw new SocketException("Proxy closed");
            }
            try {
                byte[] body = downloadCctvSegmentOnce(originUrl, attempt);
                if (isTransportStream(originUrl, "video/mp2t")
                        && !isCompleteTransportStream(body)) {
                    throw new IOException("Incomplete MPEG-TS body bytes=" + body.length);
                }
                if (attempt > 1) {
                    AppLog.i(TAG, "CCTV segment recovered on attempt " + attempt + "/"
                            + attempts + " " + segmentName(originUrl));
                }
                segmentDownloadTimes.put(originUrl, SystemClock.elapsedRealtime() - downloadStarted);
                return body;
            } catch (IOException error) {
                lastError = error;
                if (running && (error instanceof ConnectException
                        || error instanceof SocketTimeoutException)) {
                    NetworkClient.invalidateDns(URI.create(originUrl).getHost());
                }
                if (attempt >= attempts || !running) {
                    break;
                }
                AppLog.w(TAG, "CCTV segment attempt " + attempt + "/" + attempts
                        + " failed " + segmentName(originUrl) + ": " + error.getMessage());
                SystemClock.sleep((long) retryDelayMs * attempt);
            }
        }
        throw lastError == null ? new IOException("Unable to download CCTV segment") : lastError;
    }

    private byte[] downloadCctvSegmentOnce(String originUrl, int attempt) throws IOException {
        long downloadStarted = SystemClock.elapsedRealtime();
        HttpURLConnection connection = openUpstreamConnection(originUrl);
        connection.setConnectTimeout(UPSTREAM_CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(needsH5eDecrypt(originUrl)
                ? CCTV_SEGMENT_READ_TIMEOUT_MS : 10000);
        connection.setInstanceFollowRedirects(true);
        applyRequestHeaders(connection, originUrl);
        if (attempt > 1) {
            // Avoid repeatedly reusing one stale keep-alive/CDN response after a partial read.
            connection.setRequestProperty("Connection", "close");
            connection.setRequestProperty("Cache-Control", "no-cache");
        }
        boolean responseConsumed = false;
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Upstream HTTP " + status);
            }
            byte[] body = readUpstreamFully(connection.getInputStream(),
                    connection.getContentLength(),MAX_BUFFERED_RESPONSE_BYTES,true);
            responseConsumed = true;
            int expectedLength = connection.getContentLength();
            if (expectedLength >= 0 && body.length != expectedLength) {
                throw new IOException("Truncated response " + body.length + "/" + expectedLength);
            }
            if (!running) {
                throw new SocketException("Proxy closed");
            }
            return body;
        } finally {
            if (!responseConsumed) {
                startupCost("失败请求", SystemClock.elapsedRealtime() - downloadStarted);
                connection.disconnect();
            }
        }
    }

    private static boolean isCompleteTransportStream(byte[] body) {
        if (body == null || body.length < TS_PACKET_SIZE * 5
                || body.length % TS_PACKET_SIZE != 0) {
            return false;
        }
        int packetsToCheck = Math.min(8, body.length / TS_PACKET_SIZE);
        for (int packet = 0; packet < packetsToCheck; packet++) {
            if ((body[packet * TS_PACKET_SIZE] & 0xff) != 0x47) {
                return false;
            }
        }
        return true;
    }

    private static long parseMediaSequence(String[] lines) {
        for (String line : lines) {
            Matcher matcher = MEDIA_SEQUENCE.matcher(line);
            if (matcher.matches()) {
                try {
                    return Long.parseLong(matcher.group(1));
                } catch (NumberFormatException ignored) {
                    return 0L;
                }
            }
        }
        return 0L;
    }

    private static PlaylistSegment lastPlaylistSegment(
            LinkedHashMap<String, PlaylistSegment> history) {
        PlaylistSegment last = null;
        for (PlaylistSegment segment : history.values()) {
            if (last == null || segment.sequence > last.sequence) {
                last = segment;
            }
        }
        return last;
    }

    /** FFmpeg 3.4 confuses dvh1 with legacy DVVIDEO. Keep dvcC/RPU; only alias the sample entry. */
    private static void normalizeDolbyInit(byte[] data, int start, int end) {
        for (int offset = start; offset + 8 <= end;) {
            long size = ((long)(data[offset] & 255) << 24) | ((long)(data[offset + 1] & 255) << 16)
                    | ((data[offset + 2] & 255) << 8) | (data[offset + 3] & 255);
            if (size < 8 || size > end - offset) return;
            String type = new String(data, offset + 4, 4, java.nio.charset.Charset.forName("US-ASCII"));
            int next = offset + (int)size;
            if ("dvh1".equals(type)) {
                data[offset + 4] = 'h'; data[offset + 5] = 'e'; data[offset + 6] = 'v';
            } else if ("moov".equals(type) || "trak".equals(type) || "mdia".equals(type)
                    || "minf".equals(type) || "stbl".equals(type) || "stsd".equals(type)) {
                normalizeDolbyInit(data, offset + ("stsd".equals(type) ? 16 : 8), next);
            }
            offset = next;
        }
    }

    private String rewritePlaylistTagUris(URI base, String line) {
        if (!line.startsWith("#")) {
            return line;
        }
        Matcher matcher = ATTRIBUTE_URI.matcher(line);
        StringBuffer updated = new StringBuffer();
        while (matcher.find()) {
            String absolute = HlsUri.resolve(base, matcher.group(1)).toString();
            if (selectedDolbyVision() && line.startsWith("#EXT-X-MAP:")) dolbyInitUrls.add(absolute);
            matcher.appendReplacement(updated,
                    "URI=\"" + Matcher.quoteReplacement(proxyUrl(absolute)) + "\"");
        }
        matcher.appendTail(updated);
        return updated.toString();
    }

    private String rewriteMasterPlaylist(URI base, String[] lines) throws IOException {
        List<Variant> variants = parseVariants(lines);
        if (variants.isEmpty()) {
            return "#EXTM3U\n";
        }
        sortVariants(variants);
        String requestedQuality = configuredVariantQualityEnabled
                ? variantQualityMode : VARIANT_QUALITY_HIGH;
        int preferredIndex = preferredVariantIndex(variants.size(), requestedQuality);
        VariantCandidate selected;
        if (variants.size() == 1) {
            // A single rendition needs no quality probing; let the player start immediately.
            selected = new VariantCandidate(variants.get(0), true, null);
        } else {
            selected = selectAvailableVariant(base, variants, preferredIndex, requestedQuality);
        }
        Variant variant = selected.variant;
        rememberSelectedVariant(variant);
        AppLog.i(TAG, "Selected HLS variant quality=" + requestedQuality
                + " choices=" + variants.size()
                + " bandwidth=" + variant.bandwidth
                + " advertised=" + variant.width + "x" + variant.height
                + " codecs=" + HlsMediaTracks.attributes(variant.info).get("CODECS")
                + " actual=" + selected.actualDescription()
                + " uri=" + variant.uri);
        return "#EXTM3U\n" + variant.info + '\n'
                + proxyUrl(HlsUri.resolve(base, variant.uri).toString()) + '\n';
    }

    private void rememberSelectedVariant(Variant variant) {
        String codecs = HlsMediaTracks.attributes(variant.info).get("CODECS");
        selectedVideoCodecs = codecs == null ? "" : codecs;
        selectedVariantWidth = variant.width;
        selectedVariantHeight = variant.height;
        selectedVariantBandwidth = variant.bandwidth;
    }

    private static int preferredVariantIndex(int count, String qualityMode) {
        if (count <= 1 || VARIANT_QUALITY_LOW.equals(qualityMode)) {
            return 0;
        }
        if (VARIANT_QUALITY_MEDIUM.equals(qualityMode)) {
            return (count - 1) / 2;
        }
        return count - 1;
    }

    private VariantCandidate selectAvailableVariant(URI base, List<Variant> variants,
            int preferredIndex, String qualityMode) {
        List<VariantCandidate> available = new ArrayList<VariantCandidate>();
        for (int step = 0; step < variants.size(); step++) {
            int index = variantProbeIndex(variants.size(), preferredIndex, qualityMode, step);
            if (index < 0) {
                continue;
            }
            Variant variant = variants.get(index);
            String absolute = HlsUri.resolve(base, variant.uri).toString();
            VariantCandidate candidate = inspectVariant(absolute, variant);
            if (!candidate.available) {
                AppLog.w(TAG, "Skipping unavailable HLS variant bandwidth=" + variant.bandwidth
                        + " uri=" + variant.uri);
                continue;
            }
            available.add(candidate);
            if (candidate.matchesAdvertisedResolution()) {
                return candidate;
            }
            AppLog.w(TAG, "Skipping mislabeled HLS variant bandwidth=" + variant.bandwidth
                    + " advertised=" + variant.width + "x" + variant.height
                    + " actual=" + candidate.actualDescription()
                    + " uri=" + variant.uri);
        }
        if (available.isEmpty()) {
            return new VariantCandidate(variants.get(preferredIndex), true, null);
        }
        Collections.sort(available, new Comparator<VariantCandidate>() {
            @Override
            public int compare(VariantCandidate left, VariantCandidate right) {
                int leftPixels = left.actualPixels();
                int rightPixels = right.actualPixels();
                return leftPixels < rightPixels ? -1 : (leftPixels == rightPixels ? 0 : 1);
            }
        });
        return available.get(preferredVariantIndex(available.size(), qualityMode));
    }

    private static int variantProbeIndex(int count, int preferredIndex,
            String qualityMode, int step) {
        if (VARIANT_QUALITY_HIGH.equals(qualityMode)) {
            return count - 1 - step;
        }
        if (VARIANT_QUALITY_LOW.equals(qualityMode)) {
            return step;
        }
        if (step == 0) {
            return preferredIndex;
        }
        int distance = (step + 1) / 2;
        int index = (step & 1) == 1
                ? preferredIndex + distance : preferredIndex - distance;
        return index >= 0 && index < count ? index : -1;
    }

    private VariantCandidate inspectVariant(String url, Variant variant) {
        HttpURLConnection connection = null;
        boolean responseConsumed = false;
        try {
            connection = openUpstreamConnection(url);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(true);
            applyRequestHeaders(connection, url);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                return new VariantCandidate(variant, false, null);
            }
            byte[] playlistBody = readUpstreamFully(connection.getInputStream(),
                    contentLength(connection), MAX_PLAYLIST_RESPONSE_BYTES);
            long receivedAt = SystemClock.elapsedRealtime();
            responseConsumed = true;
            String responseUrl = connection.getURL().toString();
            String firstSegment = firstMediaSegment(responseUrl, new String(playlistBody, UTF_8));
            Resolution resolution = firstSegment == null ? null : probeTransportStreamResolution(firstSegment);
            // The quality check already downloaded this manifest. Hand it to
            // the player's first request once, using its original receive time
            // so a slow TS probe cannot renew a stale live window. Redirected
            // bodies need their final base URL; leave those on the normal path.
            if (url.equals(responseUrl) && isPlaylistBody(playlistBody)) {
                resolvedPlaylists.put(url, playlistBody, receivedAt);
            }
            return new VariantCandidate(variant, true, resolution);
        } catch (IOException error) {
            return new VariantCandidate(variant, false, null);
        } finally {
            if (connection != null && !responseConsumed) {
                connection.disconnect();
            }
        }
    }

    private static String firstMediaSegment(String playlistUrl, String playlist) {
        URI base = URI.create(playlistUrl);
        String[] lines = playlist.split("\\r?\\n", -1);
        for (String line : lines) {
            if (line.length() > 0 && !line.startsWith("#")) {
                return HlsUri.resolve(base, line).toString();
            }
        }
        return null;
    }

    private Resolution probeTransportStreamResolution(String url) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = openUpstreamConnection(url);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Range",
                    "bytes=0-" + (TS_RESOLUTION_PROBE_BYTES - 1));
            applyRequestHeaders(connection, url);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                return null;
            }
            return parseTransportStreamResolution(readUpstreamAtMost(
                    connection.getInputStream(), TS_RESOLUTION_PROBE_BYTES));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean isPlaylist(String url, String contentType) {
        String lowerUrl = url.toLowerCase(Locale.US);
        String lowerType = contentType == null ? "" : contentType.toLowerCase(Locale.US);
        return lowerUrl.contains(".m3u8") || lowerType.contains("mpegurl");
    }

    private static boolean isPlaylistBody(byte[] body) {
        if (body == null || body.length < 7) {
            return false;
        }
        int offset = 0;
        if (body.length >= 3 && (body[0] & 0xff) == 0xef
                && (body[1] & 0xff) == 0xbb && (body[2] & 0xff) == 0xbf) {
            offset = 3;
        }
        while (offset < body.length && (body[offset] == ' ' || body[offset] == '\t'
                || body[offset] == '\r' || body[offset] == '\n')) {
            offset++;
        }
        byte[] marker = "#EXTM3U".getBytes(UTF_8);
        if (body.length - offset < marker.length) {
            return false;
        }
        for (int index = 0; index < marker.length; index++) {
            if (body[offset + index] != marker[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTransportStream(String url, String contentType) {
        String lowerPath = URI.create(url).getPath().toLowerCase(Locale.US);
        String lowerType = contentType == null ? "" : contentType.toLowerCase(Locale.US);
        return lowerPath.endsWith(".ts") || lowerType.contains("mp2t");
    }

    private void applyRequestHeaders(HttpURLConnection connection, String url) {
        String customUserAgent = webUserAgent;
        String customReferer = webReferer;
        String customCookies = webCookies;
        if (webHttp1) {
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Connection", "close");
        }
        if (customUserAgent != null) {
            connection.setRequestProperty("User-Agent", customUserAgent);
        } else if (isYangshipinUrl(url)) {
            connection.setRequestProperty("User-Agent", YANGSHIPIN_USER_AGENT);
        } else if (carrierIptvSession || CarrierNetworkRoute.isCarrierIptvUrl(url)) {
            connection.setRequestProperty("User-Agent", CARRIER_IPTV_USER_AGENT);
        } else {
            connection.setRequestProperty("User-Agent", DEFAULT_USER_AGENT);
        }
        if (carrierIptvSession || CarrierNetworkRoute.isCarrierIptvUrl(url)) {
            connection.setRequestProperty("Connection", "Keep-Alive");
            connection.setRequestProperty("Accept", "*/*");
        }
        if (customReferer != null) {
            connection.setRequestProperty("Referer", customReferer);
            try {
                URI refererUri = URI.create(customReferer);
                if (refererUri.getScheme() != null && refererUri.getAuthority() != null) {
                    connection.setRequestProperty("Origin", refererUri.getScheme()
                            + "://" + refererUri.getAuthority());
                }
            } catch (IllegalArgumentException ignored) {
                // A malformed optional Referer must not prevent the stream request.
            }
        } else if (!webNoReferer && isYangshipinUrl(url)) {
            connection.setRequestProperty("Referer", "https://www.yangshipin.cn/");
            connection.setRequestProperty("Origin", "https://www.yangshipin.cn");
        }
        if (customCookies != null) {
            connection.setRequestProperty("Cookie", customCookies);
        }
        for (java.util.Map.Entry<String, String> header : webMediaHeaders.entrySet())
            connection.setRequestProperty(header.getKey(), header.getValue());
        String host = connection.getURL().getHost().toLowerCase(Locale.US);
        if (host.endsWith(".cctv.cn") || host.endsWith(".cctv.com")
                || host.equals("cctv.cn") || host.equals("cctv.com")) {
            for (java.util.Map.Entry<String, String> header : webSignedHeaders.entrySet())
                connection.setRequestProperty(header.getKey(), header.getValue());
        }
    }

    private static String sanitizeHeaderValue(String value) {
        if (value == null) {
            return null;
        }
        String sanitized = value.replace('\r', ' ').replace('\n', ' ').trim();
        return sanitized.length() == 0 ? null : sanitized;
    }

    private static boolean needsH5eDecrypt(String url) {
        String lower = url.toLowerCase(Locale.US);
        return lower.contains("cdrmld") || lower.contains("cctvwbcd");
    }

    private static boolean needsCmgDecrypt(String url) {
        return isYangshipinUrl(url);
    }

    private boolean needsCjsTransform(String url) {
        String transformer = cjsTransformer;
        String[] hosts = cjsMediaHosts;
        if (transformer == null || transformer.length() == 0
                || hosts == null || hosts.length == 0 || url == null) {
            return false;
        }
        try {
            String host = URI.create(url).getHost();
            String lowerHost = host == null ? "" : host.toLowerCase(Locale.US);
            for (String configured : hosts) {
                String lowerConfigured = configured == null ? ""
                        : configured.trim().toLowerCase(Locale.US);
                if (lowerConfigured.length() > 0 && (lowerHost.equals(lowerConfigured)
                        || lowerHost.endsWith("." + lowerConfigured))) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException error) {
            return false;
        }
    }

    static boolean needsSpecialDecrypt(String url) {
        return needsH5eDecrypt(url) || needsCmgDecrypt(url);
    }

    private static boolean isYangshipinUrl(String url) {
        String lower = url.toLowerCase(Locale.US);
        return lower.contains("ysp.cctv.cn") || lower.contains("yangshipin.cn");
    }

    private byte[] prewarmYangshipinState(String originUrl, YangshipinSegment current,
            int requestIndex) throws IOException {
        if (!isCmgPrewarmEnabled() || current == null) {
            return null;
        }
        if (requestIndex == 1 && current.number > 0L) {
            long firstWarmup = Math.max(0L, current.number - CMG_INITIAL_PREWARM_SEGMENTS);
            AppLog.i(TAG, "CMG initial prewarm "
                    + segmentName(current.url(firstWarmup)) + ".."
                    + segmentName(current.url(current.number - 1L))
                    + " before " + segmentName(originUrl));
            for (long number = firstWarmup; number < current.number; number++) {
                byte[] decrypted = prewarmYangshipinSegment(current.url(number));
                if (decrypted != null) {
                    cmgLastYangshipinSegment = Math.max(cmgLastYangshipinSegment, number);
                }
            }
            return null;
        }
        if (cmgLastYangshipinSegment < 0L
                || current.number <= cmgLastYangshipinSegment + 1L) {
            return null;
        }
        long firstMissing = cmgLastYangshipinSegment + 1L;
        long lastMissing = current.number - 1L;
        long missingCount = lastMissing - firstMissing + 1L;
        if (missingCount > CMG_MAX_GAP_PREWARM_SEGMENTS) {
            firstMissing = lastMissing - CMG_MAX_GAP_PREWARM_SEGMENTS + 1L;
            AppLog.w(TAG, "CMG segment gap too large, prewarming tail only gap=" + missingCount
                    + " current=" + segmentName(originUrl));
        } else {
            AppLog.i(TAG, "CMG segment gap detected gap=" + missingCount
                    + " current=" + segmentName(originUrl));
        }
        ByteArrayOutputStream prefix = new ByteArrayOutputStream();
        for (long number = firstMissing; number <= lastMissing; number++) {
            byte[] decrypted = prewarmYangshipinSegment(current.url(number));
            if (decrypted != null) {
                prefix.write(decrypted);
                cmgLastYangshipinSegment = Math.max(cmgLastYangshipinSegment, number);
            }
        }
        return prefix.size() == 0 ? null : prefix.toByteArray();
    }

    private byte[] prewarmYangshipinSegment(String segmentUrl) {
        byte[] cached = cmgSegmentCache.get(segmentUrl);
        if (cached != null) {
            return cached;
        }
        HttpURLConnection connection = null;
        boolean responseConsumed = false;
        try {
            connection = openUpstreamConnection(segmentUrl);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(true);
            applyRequestHeaders(connection, segmentUrl);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                AppLog.w(TAG, "CMG prewarm segment HTTP " + status
                        + " " + segmentName(segmentUrl));
                return null;
            }
            byte[] previous = readUpstreamFully(connection.getInputStream(),
                    connection.getContentLength(),MAX_BUFFERED_RESPONSE_BYTES,true);
            responseConsumed = true;
            AppLog.i(TAG, "CMG prewarm segment " + segmentName(segmentUrl)
                    + " bytes=" + previous.length);
            byte[] decrypted = decryptYangshipinTransportStream(previous, spsCompatibilityMode);
            cmgSegmentCache.put(segmentUrl, decrypted);
            return decrypted;
        } catch (IOException error) {
            AppLog.w(TAG, "CMG prewarm segment failed " + segmentName(segmentUrl), error);
            return null;
        } finally {
            if (connection != null && !responseConsumed) {
                connection.disconnect();
            }
        }
    }

    private static boolean isCmgPrewarmEnabled() {
        return false;
    }

    private static YangshipinSegment parseYangshipinSegment(String originUrl) {
        Matcher matcher = YANGSHIPIN_SEGMENT_NUMBER.matcher(originUrl);
        if (!matcher.matches()) {
            return null;
        }
        try {
            long number = Long.parseLong(matcher.group(2));
            if (number < 0L) {
                return null;
            }
            return new YangshipinSegment(matcher.group(1), number, matcher.group(3));
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static byte[] decryptYangshipinTransportStream(byte[] ts) throws IOException {
        return decryptYangshipinTransportStream(ts, true);
    }

    private static byte[] decryptYangshipinTransportStream(byte[] ts, boolean spsCompatibility) throws IOException {
        return decryptYangshipinTransportStream(ts, spsCompatibility, findVideoPid(ts));
    }

    private static byte[] decryptYangshipinTransportStream(byte[] ts, boolean spsCompatibility, int videoPid) throws IOException {
        synchronized (CMG_DECRYPT_LOCK) {
            long startedAt = SystemClock.elapsedRealtime();
            if (videoPid < 0) {
                return ts;
            }
            // Production never needs the encrypted copy after this point. Mutating the
            // download buffer avoids one 1.5-3 MB Dalvik allocation per live segment.
            // Debug captures still preserve both before/after transport streams.
            byte[] output = ts;
            PesBuffer currentPes = CMG_PES_BUFFER;
            currentPes.reset();
            boolean pesActive = false;
            int remainingPesPayload = -1;
            for (int packetOffset = 0; packetOffset + 188 <= output.length; packetOffset += 188) {
                if (output[packetOffset] != 0x47) {
                    continue;
                }
                int pid = ((output[packetOffset + 1] & 0x1f) << 8) | (output[packetOffset + 2] & 0xff);
                if (pid != videoPid) {
                    continue;
                }
                int payloadOffset = payloadOffset(output, packetOffset);
                if (payloadOffset < 0) {
                    continue;
                }
                boolean payloadStart = (output[packetOffset + 1] & 0x40) != 0;
                if (payloadStart) {
                    if (pesActive && currentPes.size() > 0) {
                        decryptPesNals(output, currentPes, spsCompatibility);
                    }
                    currentPes.reset();
                    pesActive = false;
                    remainingPesPayload = -1;
                    if (payloadOffset + 9 < packetOffset + 188
                            && output[payloadOffset] == 0 && output[payloadOffset + 1] == 0
                            && output[payloadOffset + 2] == 1) {
                        int pesHeaderLength = 9 + (output[payloadOffset + 8] & 0xff);
                        int pesPacketLength = ((output[payloadOffset + 4] & 0xff) << 8)
                                | (output[payloadOffset + 5] & 0xff);
                        if (pesPacketLength > 0) {
                            remainingPesPayload = Math.max(0, pesPacketLength - (pesHeaderLength - 6));
                        }
                        currentPes.setHeader(payloadOffset, pesHeaderLength, pesPacketLength);
                        pesActive = true;
                        payloadOffset += pesHeaderLength;
                    }
                }
                if (pesActive && payloadOffset < packetOffset + 188) {
                    int payloadLength = packetOffset + 188 - payloadOffset;
                    if (remainingPesPayload >= 0) {
                        payloadLength = Math.min(payloadLength, remainingPesPayload);
                        remainingPesPayload -= payloadLength;
                    }
                    if (payloadLength > 0) {
                        currentPes.add(output, packetOffset, payloadOffset, payloadLength);
                    }
                }
            }
            if (pesActive && currentPes.size() > 0) {
                decryptPesNals(output, currentPes, spsCompatibility);
            }
            normalizeVideoContinuityCounters(output, videoPid);
            long elapsedMs = SystemClock.elapsedRealtime() - startedAt;
            if (elapsedMs >= 500L) {
                AppLog.i(TAG, "CMG TS decrypt elapsed=" + elapsedMs + "ms bytes=" + ts.length);
            }
            return output;
        }
    }

    private static void restartCmgRuntime(String reason) throws IOException {
        synchronized (CMG_DECRYPT_LOCK) {
            syncCmgRuntimeClockForNative();
            NativeCmgDecryptor.resetRuntimeForProbe();
            if (!NativeCmgDecryptor.initializeRuntimeForProbe()) {
                throw new IOException("Unable to initialize CMG runtime for " + reason);
            }
            cmgSessionWarmed = false;
            cmgLiveVideoDecodeEnabled = false;
            cmgFirstStateNalPending = false;
            cmgVclSinceRuntimeRestart = 0;
        }
    }

    private static void restartCmgRuntimeAtAccessUnitBoundary(int nalType) throws IOException {
        if (nalType == 7 && cmgVclSinceRuntimeRestart >= CMG_MAX_VCL_PER_RUNTIME) {
            restartCmgRuntime("VCL budget " + cmgVclSinceRuntimeRestart);
        }
    }

    private static void normalizeVideoContinuityCounters(byte[] ts, int videoPid) {
        int lastCounter = -1;
        for (int packetOffset = 0; packetOffset + 188 <= ts.length; packetOffset += 188) {
            if (ts[packetOffset] != 0x47) {
                continue;
            }
            int pid = ((ts[packetOffset + 1] & 0x1f) << 8)
                    | (ts[packetOffset + 2] & 0xff);
            if (pid != videoPid) {
                continue;
            }
            int adaptationControl = (ts[packetOffset + 3] >> 4) & 3;
            boolean hasPayload = (adaptationControl & 1) != 0;
            int originalCounter = ts[packetOffset + 3] & 0x0f;
            int nextCounter;
            if (lastCounter < 0) {
                nextCounter = originalCounter;
            } else if (hasPayload) {
                nextCounter = (lastCounter + 1) & 0x0f;
            } else {
                nextCounter = lastCounter;
            }
            if (originalCounter != nextCounter) {
                ts[packetOffset + 3] = (byte) ((ts[packetOffset + 3] & 0xf0) | nextCounter);
            }
            lastCounter = nextCounter;
        }
    }

    private static void decryptPesNals(byte[] ts, PesBuffer pes, boolean spsCompatibility) throws IOException {
        byte[] data = pes.data();
        int dataLength = pes.payloadSize();
        ByteArrayOutputStream rebuilt = null;
        int writeOffset = 0;
        for (int offset = 0; offset < dataLength - 4; offset++) {
            int prefix = startCodeLength(data, offset);
            if (prefix == 0) {
                continue;
            }
            int nalStart = offset + prefix;
            if (nalStart >= dataLength) {
                continue;
            }
            int nalEnd = dataLength;
            for (int next = nalStart + 1; next < dataLength - 4; next++) {
                if (startCodeLength(data, next) > 0) {
                    nalEnd = next;
                    break;
                }
            }
            int nalType = data[nalStart] & 0x1f;
            int replacementLength = -1;
            boolean replaceNal = needsCmgNalDecode(nalType);
            boolean stateOnlyNal = needsCmgStateDecode(nalType);
            restartCmgRuntimeAtAccessUnitBoundary(nalType);
            advanceCmgSessionForNal(nalType);
            if (stateOnlyNal) {
                byte[] nal = new byte[nalEnd - nalStart];
                System.arraycopy(data, nalStart, nal, 0, nal.length);
                updateCmgLiveVideoFlag(nalType, nal);
                byte[] decoded = NativeCmgDecryptor.decodeNalForProbe(nal, true, true);
                if (decoded == null) {
                    AppLog.w(TAG, "CMG state NAL rejected type=" + nalType + " len=" + nal.length);
                } else if (decoded.length > nal.length) {
                    AppLog.w(TAG, "Skipping CMG state NAL replacement because length grew type="
                            + nalType + " before=" + nal.length + " after=" + decoded.length);
                } else if (decoded.length != nal.length || bytesDiffer(decoded, nal)) {
                    AppLog.w(TAG, "CMG state NAL changed type=" + nalType
                            + " before=" + nal.length + " after=" + decoded.length
                            + "; keeping original bytes");
                }
                sanitizeH264SpsMarker(data, nalStart, nalEnd, spsCompatibility);
            } else if (replaceNal) {
                int nalLength = nalEnd - nalStart;
                long nalStartedAt = SystemClock.elapsedRealtime();
                int decodedLength = NativeCmgDecryptor.decodeNalRangeInPlace(
                        data, nalStart, nalLength, true, true);
                long nalElapsed = SystemClock.elapsedRealtime() - nalStartedAt;
                if (nalElapsed > 500L) {
                    AppLog.i(TAG, "CMG decoded NAL type=" + nalType + " len=" + nalLength
                            + " out=" + decodedLength + " mode=live-in-place"
                            + " elapsed=" + nalElapsed + "ms");
                }
                if (decodedLength == -2) {
                    AppLog.w(TAG, "Skipping CMG NAL replacement because length grew type="
                            + nalType + " before=" + nalLength);
                } else if (decodedLength < 0) {
                    AppLog.w(TAG, "Skipping CMG NAL replacement because native rejected type="
                            + nalType + " len=" + nalLength);
                } else {
                    replacementLength = decodedLength;
                }
            }
            if (nalType == 1 || nalType == 5) {
                cmgVclSinceRuntimeRestart++;
            }
            if (replacementLength >= 0 && replacementLength < nalEnd - nalStart
                    && rebuilt == null) {
                rebuilt = new ByteArrayOutputStream(dataLength);
            }
            if (rebuilt != null) {
                rebuilt.write(data, writeOffset, nalStart - writeOffset);
                if (replacementLength < 0) {
                    rebuilt.write(data, nalStart, nalEnd - nalStart);
                } else {
                    rebuilt.write(data, nalStart, replacementLength);
                }
                writeOffset = nalEnd;
            }
            offset = nalEnd - 1;
        }
        if (rebuilt == null) {
            pes.copyPayloadToTransportStream(ts, data, dataLength);
        } else {
            rebuilt.write(data, writeOffset, dataLength - writeOffset);
            byte[] repacked = rebuilt.toByteArray();
            pes.copyPayloadToTransportStream(ts, repacked, repacked.length);
        }
    }

    private static boolean bytesDiffer(byte[] left, byte[] right) {
        if (left.length != right.length) {
            return true;
        }
        for (int index = 0; index < left.length; index++) {
            if (left[index] != right[index]) {
                return true;
            }
        }
        return false;
    }

    private static int advanceCmgSessionForNal(int nalType) {
        int updateTag;
        String mediaTag = cmgPlayerTag;
        if (mediaTag.length() > 0) {
            NativeCmgDecryptor.setPlayerTagForProbe(mediaTag);
        }
        syncCmgRuntimeClockForNative();
        if (cmgInitialUpdateTag != 0 || cmgStableUpdateTag != 0) {
            updateTag = NativeCmgDecryptor.updateSessionForProbe();
            int capturedTag = capturedCmgUpdateTagForNal(nalType);
            if (updateTag == 0 && capturedTag != 0) {
                updateTag = capturedTag;
                NativeCmgDecryptor.setUpdateTagForProbe(updateTag);
            }
        } else {
            updateTag = NativeCmgDecryptor.updateSessionForProbe();
        }
        return updateTag;
    }

    private static void syncCmgRuntimeClockForNative() {
        if (cmgClockBaseTimeMs <= 0L) {
            return;
        }
        // The browser wrapper ultimately reads Date.now(). Use the wall clock here too,
        // so suspend, NTP corrections, and date changes do not leave wasm on a stale epoch.
        NativeCmgDecryptor.setClockForProbe(System.currentTimeMillis() + cmgClockOffsetMs);
    }

    private static void sanitizeH264SpsMarker(byte[] data, int nalStart, int nalEnd, boolean spsCompatibility) {
        if (!spsCompatibility || nalEnd - nalStart < 3) {
            return;
        }
        int before = data[nalStart + 2] & 0xff;
        int after = before & 0xfc;
        if (before == after) {
            return;
        }
        data[nalStart + 2] = (byte) after;
    }

    private static int capturedCmgUpdateTagForNal(int nalType) {
        if (cmgFirstStateNalPending && needsCmgStateDecode(nalType)) {
            cmgFirstStateNalPending = false;
            return cmgInitialUpdateTag;
        }
        if (cmgStableUpdateTag != 0) {
            return cmgStableUpdateTag;
        }
        return cmgInitialUpdateTag;
    }

    private static boolean needsCmgNalDecode(int nalType) {
        return cmgLiveVideoDecodeEnabled && (nalType == 1 || nalType == 5);
    }

    private static boolean needsCmgStateDecode(int nalType) {
        return nalType == 7;
    }

    private static void updateCmgLiveVideoFlag(int nalType, byte[] nal) {
        if (nalType != 7 || nal.length <= 2 || cmgLiveVideoDecodeEnabled) {
            return;
        }
        int bits = nal[2] & 3;
        cmgLiveVideoDecodeEnabled = bits == 1 || bits == 2;
    }

    private static String segmentName(String url) {
        try {
            String path = URI.create(url).getPath();
            int slash = path.lastIndexOf('/');
            return slash >= 0 ? path.substring(slash + 1) : path;
        } catch (RuntimeException ignored) {
            return url;
        }
    }

    long getUpstreamDownloadedBytes() {
        return upstreamDownloadedBytes.get();
    }

    private byte[] readUpstreamFully(InputStream input, long expectedLength, int limit)
            throws IOException {
        return readUpstreamFully(input,expectedLength,limit,false);
    }

    private byte[] readUpstreamFully(InputStream input, long expectedLength, int limit,boolean media)
            throws IOException {
        return BoundedResponseReader.read(new UpstreamInputStream(input,media), expectedLength,
                Math.min(limit, MAX_BUFFERED_RESPONSE_BYTES));
    }

    private byte[] readUpstreamAtMost(InputStream input, int limit) throws IOException {
        return readAtMost(new UpstreamInputStream(input,false), limit);
    }

    private final class UpstreamInputStream extends FilterInputStream {
        private final boolean media;
        UpstreamInputStream(InputStream input,boolean media) {
            super(input);
            this.media=media;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                recordUpstreamBytes(1L,media);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) {
                recordUpstreamBytes(count,media);
            }
            return count;
        }
    }

    private static byte[] readAtMost(InputStream input, int limit) throws IOException {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(limit);
            byte[] buffer = new byte[16 * 1024];
            int remaining = limit;
            while (remaining > 0) {
                int count = input.read(buffer, 0, Math.min(buffer.length, remaining));
                if (count == -1) {
                    break;
                }
                output.write(buffer, 0, count);
                remaining -= count;
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static String readAsciiLine(InputStream input) throws IOException {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                line.append((char) value);
            }
            if (line.length() > 8192) {
                throw new IOException("HTTP line is too long");
            }
        }
        return value == -1 && line.length() == 0 ? null : line.toString();
    }

    private static String readRangeHeader(InputStream input) throws IOException {
        String line;
        String range = null;
        do {
            line = readAsciiLine(input);
            if (line != null && line.regionMatches(true, 0, "Range:", 0, 6)) {
                String value = sanitizeHeaderValue(line.substring(6));
                if (value != null && value.startsWith("bytes=")) {
                    range = value;
                }
            }
        } while (line != null && line.length() > 0);
        return range;
    }

    private static void writeOk(OutputStream output, String contentType, byte[] body) throws IOException {
        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store, no-cache, must-revalidate\r\n"
                + "Pragma: no-cache\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(UTF_8));
        output.write(body);
        output.flush();
    }

    private static void writeStreamingHeaders(OutputStream output, int status,
            String contentType, long contentLength, String contentRange) throws IOException {
        writeStreamingHeaders(output,status,contentType,contentLength,contentRange,false);
    }
    private static void writeStreamingHeaders(OutputStream output,int status,String contentType,
            long contentLength,String contentRange,boolean chunked)throws IOException {
        StringBuilder headers = new StringBuilder(256);
        headers.append("HTTP/1.1 ").append(status)
                .append(status == HttpURLConnection.HTTP_PARTIAL
                        ? " Partial Content\r\n" : " OK\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n");
        if (contentLength >= 0L) {
            headers.append("Content-Length: ").append(contentLength).append("\r\n");
        }
        if(chunked)headers.append("Transfer-Encoding: chunked\r\n");
        if (contentRange != null) {
            headers.append("Content-Range: ").append(contentRange).append("\r\n");
        }
        // An unbounded live response cannot resume at a byte offset. Advertising
        // ranges makes older FFmpeg readers try to seek/reconnect into that stream.
        if (contentLength >= 0L || contentRange != null) {
            headers.append("Accept-Ranges: bytes\r\n");
        }
        headers.append("Cache-Control: no-store, no-cache, must-revalidate\r\n")
                .append("Pragma: no-cache\r\n")
                .append("Connection: close\r\n\r\n");
        output.write(headers.toString().getBytes(UTF_8));
        output.flush();
    }

    private static void writeError(OutputStream output, int status, String message) throws IOException {
        byte[] body = message.getBytes(UTF_8);
        String headers = "HTTP/1.1 " + status + " Error\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(UTF_8));
        output.write(body);
        output.flush();
    }

    // MediaPlayer.reset() on old Android can wait for an HTTP read to finish.
    // Closing the accepted socket wakes that reader even when upstream is stalled.
    void disconnectPlayerRequests() {
        synchronized(clientSockets) {
            for(Socket socket:clientSockets)if(socket.getInetAddress().isLoopbackAddress())
                try{socket.close();}catch(IOException ignored){}
        }
    }

    void pauseReadAhead() { readAheadPaused = true; }

    String releaseWorkSummary() {
        int clients, cmg, h5e;
        synchronized (clientSockets) { clients = clientSockets.size(); }
        synchronized (cmgSegmentTasks) { cmg = cmgSegmentTasks.size(); }
        synchronized (cctvSegmentTasks) { h5e = cctvSegmentTasks.size(); }
        long now = SystemClock.elapsedRealtime();
        return "clients=" + clients + " cmgTasks=" + cmg + " h5eTasks=" + h5e
                + " networkIdleMs=" + (lastMediaProgressAt == 0 ? -1 : now - lastMediaProgressAt)
                + " servingIdleMs=" + (lastMediaSegmentServedAt == 0 ? -1 : now - lastMediaSegmentServedAt);
    }

    void onPlaybackVideoReady() {
        final byte[] sample;
        synchronized (sourceProbeLock) {
            playbackVideoReady = true;
            sample = deferredMetadataSample; deferredMetadataSample = null;
        }
        if (sample != null) try {
            genericPrefetchWorkers.execute(() -> {
                if (running && sourceVideoProbeEnabled) sampleSourceVideo(sample);
            });
        } catch (RejectedExecutionException closing) { }
    }

    private void stopServing() {
        running=false;
        synchronized (resolvedPlaylists) {
            if (manifestAhead != null) manifestAhead.cancel(true);
            manifestAhead = null;
        }
        manifestAheadWorker.shutdownNow();
        synchronized(clientSockets) {
            for(Socket socket:clientSockets)try{socket.close();}catch(IOException ignored){}
            clientSockets.clear();
        }
        if(serverSocket!=null)try{serverSocket.close();}catch(IOException ignored){}
    }
    private static final ExecutorService CLEANUP_WORKER = Executors.newFixedThreadPool(2);
    private final java.util.concurrent.atomic.AtomicBoolean cleanupStarted = new java.util.concurrent.atomic.AtomicBoolean();
    void closeAsync() {
        // Shared H5E cancellation must finish before a new channel starts decrypting.
        if (ownsH5eWork) { close(); return; }
        stopServing();
        CLEANUP_WORKER.execute(new Runnable() { public void run() { close(); } });
    }
    @Override
    public void close() {
        if (!cleanupStarted.compareAndSet(false, true)) return;
        AppLog.i(TAG, "Proxy closing streamedResponses=" + streamedResponseCount.get()
                + " streamedBytes=" + streamedResponseBytes.get()
                + " upstreamBytes=" + upstreamDownloadedBytes.get());
        stopServing();
        upstreamConnections.close();
        replayCache.close();
        synchronized (probeManifests) { probeManifests.clear(); }
        sourceMetadataProbe.close();
        screenshotSegment = null;
        synchronized (sourceProbeLock) { deferredMetadataSample = null; }
        carrierNetworkRoute.close();
        monitoredCctvPlaylistUrl = null;
        if (ownsH5eWork && (BuildConfig.H5E_STREAMING_CHECKS || CjsPluginRuntime.isNativeLoaded("tv.cctv.com"))) {
            NativeH5eDecryptor.cancelPendingDecrypts();
        }
        List<FutureTask<byte[]>> pendingCctvTasks;
        synchronized (cctvSegmentTasks) {
            pendingCctvTasks =
                    new ArrayList<FutureTask<byte[]>>(cctvSegmentTasks.values());
            cctvSegmentTasks.clear();
            cctvNextSegments.clear();
            lastCctvRequestedUrl = null;
            lastCctvPlaylistUrl = null;
        }
        // cancel() may synchronously run FutureTask.done(), which removes the task
        // from cctvSegmentTasks. Cancel only after detaching the snapshot to avoid
        // ConcurrentModificationException on Android 4.x LinkedHashMap iterators.
        for (FutureTask<byte[]> task : pendingCctvTasks) {
            task.cancel(true);
        }
        synchronized (cctvSegmentCache) {
            cctvSegmentCache.clear();
        }
        // Startup only holds its lock for readiness state, never network waits.
        synchronized (cctvStartupLock) {
            cctvStartupGeneration++;
            cctvStartupReady.clear();
        }
        synchronized (recordingTokens) {
            recordingTokens.clear();
        }
        playlistAliases.clear();
        List<FutureTask<byte[]>> pendingGenericTasks;
        synchronized (genericSegmentTasks) {
            pendingGenericTasks =
                    new ArrayList<FutureTask<byte[]>>(genericSegmentTasks.values());
            genericSegmentTasks.clear();
        }
        for (FutureTask<byte[]> task : pendingGenericTasks) {
            task.cancel(true);
        }
        List<FutureTask<byte[]>> pendingCmgTasks;
        synchronized (cmgSegmentTasks) {
            pendingCmgTasks = new ArrayList<FutureTask<byte[]>>(cmgSegmentTasks.values());
            cmgSegmentTasks.clear();
        }
        for (FutureTask<byte[]> task : pendingCmgTasks) {
            task.cancel(true);
        }
        cctvPlaylistMonitor.shutdownNow();
        int port = serverSocket == null ? -1 : serverSocket.getLocalPort();
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        cctvPrefetchWorkers.shutdownNow();
        genericPrefetchWorkers.shutdownNow();
        workers.shutdownNow();
        try {
            if (!cctvPrefetchWorkers.awaitTermination(
                    CCTV_DECRYPT_SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)) {
                AppLog.w(TAG, "CCTV decrypt worker still stopping after proxy close port=" + port);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        AppLog.i(TAG, "Proxy closed port=" + port);
    }

    private void sampleSourceVideo(byte[] body) {
        synchronized (sourceProbeLock) {
            if (deferStartupMetadata && !playbackVideoReady) {
                deferredMetadataSample = body;
                return;
            }
        }
        long started = SystemClock.elapsedRealtime();
        try { sampleSourceVideoBody(body); }
        finally { startupCost("探测", SystemClock.elapsedRealtime() - started); }
    }
    private void sampleSourceVideoBody(byte[] body) {
        if (body != null && sourceVideoProbeEnabled) sourceMetadataProbe.submit(body);
        if (body == null || body.length < 188 * 16
                || sourceVideoFrameRate > 0f && !"--".equals(sourceAudioCodec)
                || sourceVideoProbeAttempts.incrementAndGet() > 6) return;
        // Only inspect a short prefix of an already-decrypted segment. Android 4.0's
        // MediaPlayer exposes neither output FPS nor reliable HLS video dimensions.
        byte[] sample = Arrays.copyOf(body, Math.min(body.length, TS_RESOLUTION_PROBE_BYTES));
        try {
            String audioCodec = parseTransportStreamAudioCodec(sample);
            if (!"--".equals(audioCodec)) sourceAudioCodec = audioCodec;
            float frameRate = parseTransportStreamFrameRate(sample);
            if (frameRate <= 0f) return;
            sourceVideoFrameRate = frameRate;
            Resolution resolution = Build.VERSION.SDK_INT <= 15 ? parseTransportStreamResolution(sample) : null;
            if (resolution != null) {
                sourceVideoWidth = resolution.width;
                sourceVideoHeight = resolution.height;
            }
            AppLog.i(TAG, "HLS source video " + sourceVideoWidth + "x" + sourceVideoHeight
                    + " " + frameRate + " fps (PTS)");
        } catch (RuntimeException error) {
            // Metadata sampling must never interrupt delivery of a playable segment.
            AppLog.w(TAG, "Unable to sample HLS source video", error);
        }
    }

    static String parseTransportStreamAudioCodec(byte[] ts) {
        int pmtPid = -1;
        for (int offset = 0; offset + 188 <= ts.length; offset += 188) {
            if (ts[offset] != 0x47 || (ts[offset + 1] & 0x40) == 0) continue;
            int payload = payloadOffset(ts, offset);
            if (payload < 0) continue;
            byte[] section = psiSection(ts, payload, offset + 188);
            if (section == null || section.length < 16) continue;
            int pid = ((ts[offset + 1] & 0x1f) << 8) | (ts[offset + 2] & 0xff);
            if (pid == 0) {
                for (int index = 8; index + 4 <= section.length - 4; index += 4) {
                    int program = ((section[index] & 0xff) << 8) | (section[index + 1] & 0xff);
                    if (program != 0) {
                        pmtPid = ((section[index + 2] & 0x1f) << 8)
                                | (section[index + 3] & 0xff);
                        break;
                    }
                }
            } else if (pid == pmtPid) {
                int programInfoLength = ((section[10] & 0x0f) << 8) | (section[11] & 0xff);
                int index = 12 + programInfoLength;
                while (index + 5 <= section.length - 4) {
                    int streamType = section[index] & 0xff;
                    int infoLength = ((section[index + 3] & 0x0f) << 8)
                            | (section[index + 4] & 0xff);
                    if (streamType == 0x0f) return "AAC";
                    if (streamType == 0x11) return "AAC-LATM";
                    if (streamType == 0x03 || streamType == 0x04) return "MP3";
                    if (streamType == 0x81) return "AC-3";
                    if (streamType == 0x87) return "E-AC-3";
                    if (streamType == 0x06) {
                        int end = Math.min(index + 5 + infoLength, section.length - 4);
                        for (int descriptor = index + 5; descriptor + 2 <= end;) {
                            int tag = section[descriptor] & 0xff;
                            int length = section[descriptor + 1] & 0xff;
                            if (descriptor + 2 + length > end) break;
                            if (tag == 0x6a) return "AC-3";
                            if (tag == 0x7a) return "E-AC-3";
                            descriptor += 2 + length;
                        }
                    }
                    index += 5 + infoLength;
                }
            }
        }
        return "--";
    }

    static float parseTransportStreamFrameRate(byte[] ts) {
        int videoPid = findVideoPid(ts);
        if (videoPid < 0) return 0f;
        long[] timestamps = new long[160];
        int count = 0;
        for (int offset = 0; offset + 188 <= ts.length && count < timestamps.length;
                offset += 188) {
            if (ts[offset] != 0x47
                    || (((ts[offset + 1] & 0x1f) << 8) | (ts[offset + 2] & 0xff)) != videoPid
                    || (ts[offset + 1] & 0x40) == 0) continue;
            int pes = payloadOffset(ts, offset);
            if (pes < 0 || pes + 14 > offset + 188 || ts[pes] != 0
                    || ts[pes + 1] != 0 || ts[pes + 2] != 1
                    || (ts[pes + 3] & 0xf0) != 0xe0
                    || (ts[pes + 7] & 0x80) == 0
                    || (ts[pes + 8] & 0xff) < 5) continue;
            long pts = ((long) (ts[pes + 9] & 0x0e) << 29)
                    | ((long) (ts[pes + 10] & 0xff) << 22)
                    | ((long) (ts[pes + 11] & 0xfe) << 14)
                    | ((long) (ts[pes + 12] & 0xff) << 7)
                    | ((ts[pes + 13] & 0xfe) >>> 1);
            boolean duplicate = false;
            for (int index = 0; index < count; index++) {
                if (timestamps[index] == pts) { duplicate = true; break; }
            }
            if (duplicate) continue;
            timestamps[count++] = pts;
        }
        if (count < 8) return 0f;
        Arrays.sort(timestamps, 0, count);
        long[] intervals = new long[count - 1];
        int intervalCount = 0;
        for (int index = 1; index < count; index++) {
            long interval = timestamps[index] - timestamps[index - 1];
            if (interval >= 750L && interval <= 18000L)
                intervals[intervalCount++] = interval;
        }
        if (intervalCount < 6) return 0f;
        Arrays.sort(intervals, 0, intervalCount);
        // Segment boundaries and B-frame reordering can leave a missing PTS.
        // The median interval preserves the advertised cadence in that case.
        float rate = 90000f / intervals[intervalCount / 2];
        return rate >= 5f && rate <= 120f ? rate : 0f;
    }

    private static Resolution parseTransportStreamResolution(byte[] ts) {
        int videoPid = findVideoPid(ts);
        if (videoPid < 0) {
            return null;
        }
        ByteArrayOutputStream video = new ByteArrayOutputStream(ts.length);
        for (int offset = 0; offset + 188 <= ts.length; offset += 188) {
            if (ts[offset] != 0x47) {
                continue;
            }
            int pid = ((ts[offset + 1] & 0x1f) << 8) | (ts[offset + 2] & 0xff);
            if (pid != videoPid) {
                continue;
            }
            int payloadOffset = payloadOffset(ts, offset);
            if (payloadOffset < 0) {
                continue;
            }
            boolean payloadStart = (ts[offset + 1] & 0x40) != 0;
            if (payloadStart && payloadOffset + 9 < offset + 188
                    && ts[payloadOffset] == 0 && ts[payloadOffset + 1] == 0
                    && ts[payloadOffset + 2] == 1) {
                payloadOffset += 9 + (ts[payloadOffset + 8] & 0xff);
            }
            if (payloadOffset < offset + 188) {
                video.write(ts, payloadOffset, offset + 188 - payloadOffset);
            }
        }
        byte[] h264 = video.toByteArray();
        for (int index = 0; index < h264.length - 4; index++) {
            int prefix = startCodeLength(h264, index);
            if (prefix == 0) {
                continue;
            }
            int nalStart = index + prefix;
            if (nalStart >= h264.length) {
                continue;
            }
            int nalEnd = h264.length;
            for (int next = nalStart + 1; next < h264.length - 4; next++) {
                if (startCodeLength(h264, next) > 0) {
                    nalEnd = next;
                    break;
                }
            }
            if ((h264[nalStart] & 0x1f) == 7) {
                return parseSps(h264, nalStart, nalEnd);
            }
            index = nalEnd - 1;
        }
        return null;
    }

    private static int findVideoPid(byte[] ts) {
        return findVideoPid(ts, false);
    }

    static int findAvcVideoPid(byte[] ts) { return findVideoPid(ts, true); }

    private static int findVideoPid(byte[] ts, boolean avcOnly) {
        int pmtPid = -1;
        for (int offset = 0; offset + 188 <= ts.length; offset += 188) {
            if (ts[offset] != 0x47) {
                continue;
            }
            int pid = ((ts[offset + 1] & 0x1f) << 8) | (ts[offset + 2] & 0xff);
            boolean payloadStart = (ts[offset + 1] & 0x40) != 0;
            int payloadOffset = payloadOffset(ts, offset);
            if (payloadOffset < 0 || !payloadStart) {
                continue;
            }
            byte[] section = psiSection(ts, payloadOffset, offset + 188);
            if (section == null) {
                continue;
            }
            if (pid == 0) {
                for (int index = 8; index + 4 <= section.length - 4; index += 4) {
                    int program = ((section[index] & 0xff) << 8) | (section[index + 1] & 0xff);
                    if (program != 0) {
                        pmtPid = ((section[index + 2] & 0x1f) << 8)
                                | (section[index + 3] & 0xff);
                        break;
                    }
                }
            } else if (pid == pmtPid) {
                int programInfoLength = ((section[10] & 0x0f) << 8) | (section[11] & 0xff);
                int index = 12 + programInfoLength;
                while (index + 5 <= section.length - 4) {
                    int streamType = section[index] & 0xff;
                    int elementaryPid = ((section[index + 1] & 0x1f) << 8)
                            | (section[index + 2] & 0xff);
                    int infoLength = ((section[index + 3] & 0x0f) << 8)
                            | (section[index + 4] & 0xff);
                    if (streamType == 0x1b || (!avcOnly && streamType == 0x24)) {
                        return elementaryPid;
                    }
                    index += 5 + infoLength;
                }
            }
        }
        return -1;
    }

    static int payloadOffset(byte[] ts, int packetOffset) {
        int adaptationControl = (ts[packetOffset + 3] >> 4) & 3;
        if ((adaptationControl & 1) == 0) {
            return -1;
        }
        int offset = packetOffset + 4;
        if ((adaptationControl & 2) != 0) {
            offset += 1 + (ts[offset] & 0xff);
        }
        return offset < packetOffset + 188 ? offset : -1;
    }

    private static byte[] psiSection(byte[] ts, int payloadOffset, int packetEnd) {
        int pointer = ts[payloadOffset] & 0xff;
        int sectionStart = payloadOffset + 1 + pointer;
        if (sectionStart + 3 > packetEnd) {
            return null;
        }
        int sectionLength = ((ts[sectionStart + 1] & 0x0f) << 8)
                | (ts[sectionStart + 2] & 0xff);
        int sectionEnd = sectionStart + 3 + sectionLength;
        if (sectionEnd > packetEnd) {
            sectionEnd = packetEnd;
        }
        byte[] section = new byte[sectionEnd - sectionStart];
        System.arraycopy(ts, sectionStart, section, 0, section.length);
        return section;
    }

    private static int startCodeLength(byte[] data, int offset) {
        if (offset + 3 < data.length && data[offset] == 0 && data[offset + 1] == 0) {
            if (data[offset + 2] == 1) {
                return 3;
            }
            if (offset + 4 < data.length && data[offset + 2] == 0 && data[offset + 3] == 1) {
                return 4;
            }
        }
        return 0;
    }

    private static Resolution parseSps(byte[] data, int start, int end) {
        byte[] rbsp = spsRbsp(data, start, end);
        BitReader reader = new BitReader(rbsp);
        int profile = reader.readBits(8);
        reader.readBits(8);
        reader.readBits(8);
        reader.readUnsignedExpGolomb();
        int chromaFormat = 1;
        if (profile == 100 || profile == 110 || profile == 122 || profile == 244
                || profile == 44 || profile == 83 || profile == 86 || profile == 118
                || profile == 128 || profile == 138 || profile == 139 || profile == 134
                || profile == 135) {
            chromaFormat = reader.readUnsignedExpGolomb();
            if (chromaFormat == 3) {
                reader.readBit();
            }
            reader.readUnsignedExpGolomb();
            reader.readUnsignedExpGolomb();
            reader.readBit();
            if (reader.readBit() == 1) {
                int count = chromaFormat == 3 ? 12 : 8;
                for (int index = 0; index < count; index++) {
                    if (reader.readBit() == 1) {
                        skipScalingList(reader, index < 6 ? 16 : 64);
                    }
                }
            }
        }
        reader.readUnsignedExpGolomb();
        int picOrderCountType = reader.readUnsignedExpGolomb();
        if (picOrderCountType == 0) {
            reader.readUnsignedExpGolomb();
        } else if (picOrderCountType == 1) {
            reader.readBit();
            reader.readSignedExpGolomb();
            reader.readSignedExpGolomb();
            int cycle = reader.readUnsignedExpGolomb();
            for (int index = 0; index < cycle; index++) {
                reader.readSignedExpGolomb();
            }
        }
        reader.readUnsignedExpGolomb();
        reader.readBit();
        int picWidthInMbsMinus1 = reader.readUnsignedExpGolomb();
        int picHeightInMapUnitsMinus1 = reader.readUnsignedExpGolomb();
        int frameMbsOnlyFlag = reader.readBit();
        if (frameMbsOnlyFlag == 0) {
            reader.readBit();
        }
        reader.readBit();
        int cropLeft = 0;
        int cropRight = 0;
        int cropTop = 0;
        int cropBottom = 0;
        if (reader.readBit() == 1) {
            cropLeft = reader.readUnsignedExpGolomb();
            cropRight = reader.readUnsignedExpGolomb();
            cropTop = reader.readUnsignedExpGolomb();
            cropBottom = reader.readUnsignedExpGolomb();
        }
        int subWidth = chromaFormat == 1 || chromaFormat == 2 ? 2 : 1;
        int subHeight = chromaFormat == 1 ? 2 : 1;
        int cropUnitX = subWidth;
        int cropUnitY = (frameMbsOnlyFlag == 1 ? 1 : 2) * subHeight;
        int width = (picWidthInMbsMinus1 + 1) * 16 - (cropLeft + cropRight) * cropUnitX;
        int height = (picHeightInMapUnitsMinus1 + 1) * 16
                * (frameMbsOnlyFlag == 1 ? 1 : 2) - (cropTop + cropBottom) * cropUnitY;
        return new Resolution(width, height);
    }

    private static byte[] spsRbsp(byte[] data, int start, int end) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(end - start);
        for (int index = start + 1; index < end; index++) {
            if (index + 2 < end && data[index] == 0 && data[index + 1] == 0
                    && data[index + 2] == 3) {
                output.write(0);
                output.write(0);
                index += 2;
            } else {
                output.write(data[index]);
            }
        }
        return output.toByteArray();
    }

    private static void skipScalingList(BitReader reader, int size) {
        int lastScale = 8;
        int nextScale = 8;
        for (int index = 0; index < size; index++) {
            if (nextScale != 0) {
                nextScale = (lastScale + reader.readSignedExpGolomb() + 256) % 256;
            }
            lastScale = nextScale == 0 ? lastScale : nextScale;
        }
    }

    private static final class VideoPayloadBuffer {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(512 * 1024);
        private final List<PesSlot> slots = new ArrayList<PesSlot>();

        void add(byte[] ts, int packetOffset, int offset, int length) {
            PesSlot slot = new PesSlot();
            slot.packetOffset = packetOffset;
            slot.transportOffset = offset;
            slot.pesOffset = bytes.size();
            slot.length = length;
            slots.add(slot);
            bytes.write(ts, offset, length);
        }

        int size() {
            return bytes.size();
        }

        byte[] toByteArray() {
            return bytes.toByteArray();
        }

        void copyBack(byte[] ts, byte[] data) {
            if (data.length != bytes.size()) {
                AppLog.w(TAG, "Skipping CMG video payload copy because length changed before="
                        + bytes.size() + " after=" + data.length);
                return;
            }
            int dataOffset = 0;
            for (PesSlot slot : slots) {
                System.arraycopy(data, dataOffset, ts, slot.transportOffset, slot.length);
                dataOffset += slot.length;
            }
        }
    }

    private static final class PesBuffer {
        private byte[] bytes = new byte[64 * 1024];
        private int size;
        private int[] packetOffsets = new int[512];
        private int[] transportOffsets = new int[512];
        private int slotCount;

        void add(byte[] ts, int packetOffset, int offset, int length) {
            ensureByteCapacity(size + length);
            ensureSlotCapacity(slotCount + 1);
            packetOffsets[slotCount] = packetOffset;
            transportOffsets[slotCount] = offset;
            slotCount++;
            System.arraycopy(ts, offset, bytes, size, length);
            size += length;
        }

        byte[] data() {
            return bytes;
        }

        int payloadSize() {
            int payloadLength = expectedPayloadLength();
            if (payloadLength >= 0 && payloadLength < size) {
                return payloadLength;
            }
            return size;
        }

        int size() {
            return size;
        }

        void copyPayloadToTransportStream(byte[] ts, byte[] data, int dataLength) {
            updatePesLength(ts, dataLength);
            int dataOffset = 0;
            for (int slot = 0; slot < slotCount; slot++) {
                int packetOffset = packetOffsets[slot];
                int transportOffset = transportOffsets[slot];
                int packetEnd = packetOffset + 188;
                int capacity = packetEnd - transportOffset;
                int remaining = dataLength - dataOffset;
                int count = Math.min(capacity, Math.max(remaining, 0));
                if (count == capacity) {
                    System.arraycopy(data, dataOffset, ts, transportOffset, count);
                    dataOffset += count;
                    continue;
                }
                Arrays.fill(ts, packetOffset + 4, packetEnd, (byte) 0xff);
                int header = ts[packetOffset + 3] & 0xff;
                if (count <= 0) {
                    ts[packetOffset + 1] = (byte) (ts[packetOffset + 1] & ~0x40);
                    ts[packetOffset + 3] = (byte) ((header & 0xcf) | 0x20);
                    ts[packetOffset + 4] = (byte) 183;
                    ts[packetOffset + 5] = 0;
                } else {
                    int payloadOffset = packetEnd - count;
                    int adaptationLength = payloadOffset - packetOffset - 5;
                    ts[packetOffset + 3] = (byte) ((header & 0xcf) | 0x30);
                    ts[packetOffset + 4] = (byte) adaptationLength;
                    if (adaptationLength > 0) {
                        ts[packetOffset + 5] = 0;
                    }
                    System.arraycopy(data, dataOffset, ts, payloadOffset, count);
                    dataOffset += count;
                }
            }
            if (dataOffset < dataLength) {
                AppLog.w(TAG, "Rebuilt PES payload did not fit original TS packets before="
                        + size + " after=" + dataLength);
            }
        }

        void reset() {
            size = 0;
            slotCount = 0;
            pesHeaderOffset = -1;
            pesHeaderLength = 0;
            pesPacketLength = 0;
        }

        void setHeader(int offset, int length, int packetLength) {
            pesHeaderOffset = offset;
            pesHeaderLength = length;
            pesPacketLength = packetLength;
        }

        private void updatePesLength(byte[] ts, int payloadLength) {
            if (pesHeaderOffset < 0 || pesPacketLength == 0) {
                return;
            }
            int updatedLength = payloadLength + pesHeaderLength - 6;
            if (updatedLength > 0xffff) {
                AppLog.w(TAG, "Cannot update PES length because rebuilt payload is too large: "
                        + updatedLength);
                return;
            }
            ts[pesHeaderOffset + 4] = (byte) ((updatedLength >> 8) & 0xff);
            ts[pesHeaderOffset + 5] = (byte) (updatedLength & 0xff);
        }

        private int expectedPayloadLength() {
            if (pesPacketLength <= 0 || pesHeaderLength <= 0) {
                return -1;
            }
            return Math.max(0, pesPacketLength - (pesHeaderLength - 6));
        }

        private void ensureByteCapacity(int required) {
            if (required <= bytes.length) {
                return;
            }
            int capacity = bytes.length;
            while (capacity < required) {
                capacity = capacity < 1024 * 1024 ? capacity << 1 : required;
            }
            bytes = Arrays.copyOf(bytes, capacity);
        }

        private void ensureSlotCapacity(int required) {
            if (required <= packetOffsets.length) {
                return;
            }
            int capacity = packetOffsets.length << 1;
            packetOffsets = Arrays.copyOf(packetOffsets, capacity);
            transportOffsets = Arrays.copyOf(transportOffsets, capacity);
        }

        private int pesHeaderOffset = -1;
        private int pesHeaderLength;
        private int pesPacketLength;
    }

    private static final class PesSlot {
        int packetOffset;
        int transportOffset;
        int pesOffset;
        int length;
    }

    static final class ProxyResponse {
        final String contentType;
        final byte[] body;

        ProxyResponse(String contentType, byte[] body) {
            this.contentType = contentType;
            this.body = body;
        }
    }

    private static final class YangshipinSegment {
        final String prefix;
        final long number;
        final String suffix;

        YangshipinSegment(String prefix, long number, String suffix) {
            this.prefix = prefix;
            this.number = number;
            this.suffix = suffix;
        }

        String url(long segmentNumber) {
            return prefix + segmentNumber + suffix;
        }
    }

    private static final class PlaylistSegment {
        final long sequence;
        final String url;
        final List<String> tags;

        PlaylistSegment(long sequence, String url, List<String> tags) {
            this.sequence = sequence;
            this.url = url;
            this.tags = tags;
        }
    }

    private static final class AesPlaylistKey {
        final String keyUrl;
        final byte[] explicitIv;

        AesPlaylistKey(String keyUrl, byte[] explicitIv) {
            this.keyUrl = keyUrl;
            this.explicitIv = explicitIv;
        }
    }

    private static final class AesSegmentKey {
        final String keyUrl;
        final byte[] iv;

        AesSegmentKey(String keyUrl, byte[] iv) {
            this.keyUrl = keyUrl;
            this.iv = iv;
        }
    }

    private static final class Variant {
        final String info;
        final String uri;
        final int bandwidth;
        final int width;
        final int height;

        Variant(String info, String uri, int bandwidth, int width, int height) {
            this.info = info;
            this.uri = uri;
            this.bandwidth = bandwidth;
            this.width = width;
            this.height = height;
        }

        long advertisedQuality() {
            if (width > 0 && height > 0) {
                return (long) width * height;
            }
            return bandwidth;
        }
    }

    private static final class VariantCandidate {
        final Variant variant;
        final boolean available;
        final Resolution actual;

        VariantCandidate(Variant variant, boolean available, Resolution actual) {
            this.variant = variant;
            this.available = available;
            this.actual = actual;
        }

        int actualPixels() {
            if (actual != null) {
                return actual.width * actual.height;
            }
            if (variant.width > 0 && variant.height > 0) {
                return variant.width * variant.height;
            }
            return variant.bandwidth;
        }

        boolean matchesAdvertisedResolution() {
            if (actual == null || variant.width <= 0 || variant.height <= 0) {
                return true;
            }
            return actual.width * 4 >= variant.width * 3
                    && actual.height * 4 >= variant.height * 3;
        }

        String actualDescription() {
            return actual == null ? "unknown" : actual.width + "x" + actual.height;
        }
    }

    private static final class Resolution {
        final int width;
        final int height;

        Resolution(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private static final class BitReader {
        private final byte[] data;
        private int bitOffset;

        BitReader(byte[] data) {
            this.data = data;
        }

        int readBit() {
            if (bitOffset >= data.length * 8) {
                return 0;
            }
            int value = (data[bitOffset >> 3] >> (7 - (bitOffset & 7))) & 1;
            bitOffset++;
            return value;
        }

        int readBits(int count) {
            int value = 0;
            while (count-- > 0) {
                value = (value << 1) | readBit();
            }
            return value;
        }

        int readUnsignedExpGolomb() {
            int zeros = 0;
            while (bitOffset < data.length * 8 && readBit() == 0) {
                zeros++;
            }
            return (1 << zeros) - 1 + readBits(zeros);
        }

        int readSignedExpGolomb() {
            int value = readUnsignedExpGolomb();
            return (value & 1) == 1 ? (value + 1) / 2 : -(value / 2);
        }
    }
}
