package com.bu.cc.tv;

import android.os.SystemClock;
import android.os.Process;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class HlsProxyServer implements Closeable {
    private static final String TAG = "HlsProxyServer";
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final Pattern ATTRIBUTE_URI = Pattern.compile("URI=\"([^\"]+)\"");
    private static final Pattern STREAM_BANDWIDTH = Pattern.compile("BANDWIDTH=(\\d+)");
    private static final Pattern STREAM_RESOLUTION = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)");
    private static final Pattern MEDIA_SEQUENCE = Pattern.compile("#EXT-X-MEDIA-SEQUENCE:(\\d+)");
    private static final Pattern YANGSHIPIN_SEGMENT_NUMBER =
            Pattern.compile("^(.*_web-)(\\d+)(\\.ts(?:[?#].*)?)$");
    private static final int CMG_SEGMENT_CACHE_LIMIT = 6;
    private static final int LIVE_PLAYLIST_HISTORY_LIMIT = 12;
    /* H5E is a stream state machine: type-25 control NALs affect later segments.
     * A single worker preserves ordering and also keeps only one wasm heap alive. */
    private static final int CCTV_PARALLEL_DECRYPT_THREADS = 1;
    private static final int CCTV_LOW_RAM_DECRYPT_THREADS = 1;
    private static final int CCTV_PARALLEL_PREFETCH_WINDOW = 2;
    private static final int CMG_PREFETCH_WINDOW = 1;
    private static final int CMG_MAX_GAP_PREWARM_SEGMENTS = 6;
    private static final int CMG_INITIAL_PREWARM_SEGMENTS = 0;
    private static final int CMG_MAX_VCL_PER_RUNTIME = 150;
    private static final int UPSTREAM_MAX_ATTEMPTS = 3;
    private static final int UPSTREAM_CONNECT_TIMEOUT_MS = 3500;
    private static final int UPSTREAM_READ_TIMEOUT_MS = 5500;
    private static final int UPSTREAM_RETRY_DELAY_MS = 250;
    private static final int TS_RESOLUTION_PROBE_BYTES = 384 * 1024;
    private static final int MAX_PREALLOCATED_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final String DEFAULT_USER_AGENT = "nTv/1.0";
    private static final String YANGSHIPIN_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    private static final Object CMG_DECRYPT_LOCK = new Object();
    private static final PesBuffer CMG_PES_BUFFER = new PesBuffer();
    private static final AtomicInteger CMG_DETAIL_LOGS = new AtomicInteger();
    private static final AtomicInteger CMG_DECODE_DETAIL_LOGS = new AtomicInteger();
    private static volatile boolean cmgVerboseLogging;
    private static boolean cmgSessionWarmed;
    private static boolean cmgLiveVideoDecodeEnabled;
    private static int cmgInitialUpdateTag;
    private static int cmgStableUpdateTag;
    private static boolean cmgFirstStateNalPending;
    private static int cmgVclSinceRuntimeRestart;
    private static String cmgDebugPlayerTag = "";
    private static String cmgDebugInitialTag = "";
    private static String cmgDebugStableTag = "";
    private static long cmgDebugInitTimeMs;
    private static long cmgDebugUpdateBaseTimeMs;
    private static long cmgDebugClockBaseTimeMs;
    private static long cmgDebugClockBaseElapsedMs;
    private static int cmgDebugClockOffsetMs;
    private static String cmgDebugUpdateTrace = "";
    // The CMG Live decryptor is a stateful stream machine. Segment requests must not
    // advance it concurrently, or later NALs are decoded with the wrong state.
    private final ExecutorService workers;
    private final ExecutorService cctvPrefetchWorkers;
    private final boolean parallelCctvDecrypt;
    private final ScheduledExecutorService cctvPlaylistMonitor =
            Executors.newSingleThreadScheduledExecutor();
    private final File cmgDebugDir;
    private int cmgSegmentCacheLimit = CMG_SEGMENT_CACHE_LIMIT;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;
    private final AtomicInteger cmgTsRequestIndex = new AtomicInteger();
    private final AtomicInteger cmgDumpIndex = new AtomicInteger();
    private final Map<String, byte[]> cmgSegmentCache =
            new LinkedHashMap<String, byte[]>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > cmgSegmentCacheLimit;
                }
            };
    private final Map<String, LinkedHashMap<String, PlaylistSegment>> playlistSegmentHistory =
            new LinkedHashMap<String, LinkedHashMap<String, PlaylistSegment>>();
    private int cctvSegmentTaskLimit = 2;
    private final Map<String, FutureTask<byte[]>> cctvSegmentTasks =
            new LinkedHashMap<String, FutureTask<byte[]>>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<String, FutureTask<byte[]>> eldest) {
                    return size() > cctvSegmentTaskLimit;
                }
            };
    private final Map<String, FutureTask<byte[]>> cmgSegmentTasks =
            new LinkedHashMap<String, FutureTask<byte[]>>(4, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<String, FutureTask<byte[]>> eldest) {
                    return size() > CMG_PREFETCH_WINDOW + 1;
                }
            };
    private final Map<String, String> cctvNextSegments =
            new LinkedHashMap<String, String>();
    private String lastCctvRequestedUrl;
    private String lastCctvPlaylistUrl;
    private volatile String monitoredCctvPlaylistUrl;
    private boolean cctvPlaylistMonitorStarted;
    private long cmgLastYangshipinSegment = -1L;

    HlsProxyServer() {
        this(null, true);
    }

    HlsProxyServer(File cmgDebugDir) {
        this(cmgDebugDir, true);
    }

    HlsProxyServer(File cmgDebugDir, boolean statefulCmgSource) {
        this(cmgDebugDir, statefulCmgSource, false);
    }

    HlsProxyServer(File cmgDebugDir, boolean statefulCmgSource, boolean lowResourceDevice) {
        this.cmgDebugDir = cmgDebugDir;
        cmgSegmentCacheLimit = lowResourceDevice ? 2 : CMG_SEGMENT_CACHE_LIMIT;
        cmgVerboseLogging = cmgDebugDir != null;
        parallelCctvDecrypt = !statefulCmgSource;
        cctvSegmentTaskLimit = parallelCctvDecrypt
                ? CCTV_PARALLEL_PREFETCH_WINDOW + 2 : 2;
        workers = statefulCmgSource
                ? Executors.newSingleThreadExecutor()
                : Executors.newFixedThreadPool(lowResourceDevice ? 2 : 4);
        int decryptThreads = !parallelCctvDecrypt ? 1
                : (lowResourceDevice
                ? CCTV_LOW_RAM_DECRYPT_THREADS : CCTV_PARALLEL_DECRYPT_THREADS);
        cctvPrefetchWorkers = newCctvPrefetchExecutor(decryptThreads);
        Log.i(TAG, "CCTV decrypt profile parallel=" + parallelCctvDecrypt
                + " prefetchThreads=" + decryptThreads
                + " statefulSession=" + parallelCctvDecrypt);
    }

    void start() throws IOException {
        serverSocket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "hls-proxy-accept");
        acceptThread.start();
        Log.i(TAG, "Proxy started port=" + serverSocket.getLocalPort());
    }

    String proxyUrl(String originUrl) {
        String token = Base64.encodeToString(originUrl.getBytes(UTF_8),
                Base64.NO_WRAP | Base64.URL_SAFE);
        return "http://127.0.0.1:" + serverSocket.getLocalPort() + "/proxy/" + token;
    }

    static void configureCmgUpdateTags(int initialUpdateTag, int stableUpdateTag) {
        synchronized (CMG_DECRYPT_LOCK) {
            cmgInitialUpdateTag = initialUpdateTag;
            cmgStableUpdateTag = stableUpdateTag;
            cmgFirstStateNalPending = initialUpdateTag != 0 && initialUpdateTag != stableUpdateTag;
            cmgSessionWarmed = false;
            cmgLiveVideoDecodeEnabled = false;
            CMG_DETAIL_LOGS.set(0);
            CMG_DECODE_DETAIL_LOGS.set(0);
            Log.i(TAG, "CMG proxy update tags initial="
                    + String.format(Locale.US, "%08x", initialUpdateTag)
                    + " stable=" + String.format(Locale.US, "%08x", stableUpdateTag));
        }
    }

    static void configureCmgDebugContext(String playerTag,
            String initialUpdateTag, String stableUpdateTag,
            long initTimeMs, long updateBaseTimeMs, String updateTrace) {
        synchronized (CMG_DECRYPT_LOCK) {
            cmgDebugPlayerTag = playerTag == null ? "" : playerTag;
            cmgDebugInitialTag = initialUpdateTag == null ? "" : initialUpdateTag;
            cmgDebugStableTag = stableUpdateTag == null ? "" : stableUpdateTag;
            cmgDebugInitTimeMs = initTimeMs;
            cmgDebugUpdateBaseTimeMs = updateBaseTimeMs;
            cmgDebugClockBaseTimeMs = updateBaseTimeMs > 0L ? updateBaseTimeMs : initTimeMs;
            cmgDebugClockBaseElapsedMs = SystemClock.elapsedRealtime();
            cmgDebugClockOffsetMs = 0;
            cmgDebugUpdateTrace = updateTrace == null ? "" : updateTrace;
        }
    }

    static void configureCmgRuntimeClock(long baseTimeMs, int clockOffsetMs) {
        synchronized (CMG_DECRYPT_LOCK) {
            if (baseTimeMs > 0L) {
                cmgDebugClockBaseTimeMs = baseTimeMs;
                cmgDebugClockBaseElapsedMs = SystemClock.elapsedRealtime();
            }
            long inferredOffsetMs = baseTimeMs - System.currentTimeMillis();
            if (clockOffsetMs == 0 && Math.abs(inferredOffsetMs) > 5000L
                    && inferredOffsetMs >= Integer.MIN_VALUE
                    && inferredOffsetMs <= Integer.MAX_VALUE) {
                cmgDebugClockOffsetMs = (int) inferredOffsetMs;
            } else {
                cmgDebugClockOffsetMs = clockOffsetMs;
            }
            Log.i(TAG, "CMG proxy runtime clock base=" + cmgDebugClockBaseTimeMs
                    + " offsetMs=" + cmgDebugClockOffsetMs);
        }
    }

    static void resetCmgSessionForChannelSwitch() {
        synchronized (CMG_DECRYPT_LOCK) {
            NativeCmgDecryptor.resetRuntimeForProbe();
            cmgSessionWarmed = false;
            cmgLiveVideoDecodeEnabled = false;
            cmgInitialUpdateTag = 0;
            cmgStableUpdateTag = 0;
            cmgFirstStateNalPending = false;
            cmgVclSinceRuntimeRestart = 0;
            cmgDebugPlayerTag = "";
            cmgDebugInitialTag = "";
            cmgDebugStableTag = "";
            cmgDebugInitTimeMs = 0L;
            cmgDebugUpdateBaseTimeMs = 0L;
            cmgDebugClockBaseTimeMs = 0L;
            cmgDebugClockBaseElapsedMs = 0L;
            cmgDebugClockOffsetMs = 0;
            cmgDebugUpdateTrace = "";
            CMG_DETAIL_LOGS.set(0);
            CMG_DECODE_DETAIL_LOGS.set(0);
            Log.i(TAG, "CMG session reset for channel switch");
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket socket = serverSocket.accept();
                workers.execute(new Runnable() {
                    @Override
                    public void run() {
                        handle(socket);
                    }
                });
            } catch (IOException error) {
                if (running) {
                    Log.e(TAG, "Proxy accept failed", error);
                }
            }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(15000);
            socket.setTcpNoDelay(true);
            socket.setSendBufferSize(256 * 1024);
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
            String requestLine = readAsciiLine(input);
            drainHeaders(input);
            if (requestLine == null || !requestLine.startsWith("GET ")) {
                writeError(output, 400, "Bad request");
                return;
            }

            int pathEnd = requestLine.indexOf(' ', 4);
            String path = pathEnd < 0 ? "" : requestLine.substring(4, pathEnd);
            String prefix = "/proxy/";
            if (!path.startsWith(prefix)) {
                writeError(output, 404, "Not found");
                return;
            }

            String token = path.substring(prefix.length());
            String originUrl = new String(Base64.decode(token, Base64.URL_SAFE), UTF_8);
            ProxyResponse response = fetch(originUrl);
            if (!running) {
                return;
            }
            writeOk(output, response.contentType, response.body);
        } catch (Exception error) {
            if (!running || isPlayerDisconnect(error)) {
                return;
            }
            Log.e(TAG, "Proxy request failed", error);
            try {
                writeError(socket.getOutputStream(), 502, "Upstream failed");
            } catch (IOException ignored) {
                // The player may already have closed the connection.
            }
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static boolean isPlayerDisconnect(Exception error) {
        return error instanceof SocketException && "Broken pipe".equals(error.getMessage());
    }

    private ProxyResponse fetch(String originUrl) throws IOException {
        IOException lastError = null;
        for (int attempt = 1; attempt <= UPSTREAM_MAX_ATTEMPTS; attempt++) {
            try {
                return fetchOnce(originUrl);
            } catch (IOException error) {
                lastError = error;
                if (attempt == UPSTREAM_MAX_ATTEMPTS || !isRetryableUpstreamError(error)) {
                    throw error;
                }
                Log.w(TAG, "Retrying upstream request attempt=" + (attempt + 1)
                        + "/" + UPSTREAM_MAX_ATTEMPTS + " " + segmentName(originUrl)
                        + " after " + error.getClass().getSimpleName());
                SystemClock.sleep((long) UPSTREAM_RETRY_DELAY_MS * attempt);
            }
        }
        throw lastError == null ? new IOException("Upstream request failed") : lastError;
    }

    private static boolean isRetryableUpstreamError(IOException error) {
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
        return message != null && message.startsWith("Upstream HTTP 5");
    }

    private ProxyResponse fetchOnce(String originUrl) throws IOException {
        if (!running) {
            throw new SocketException("Proxy closed");
        }
        if (isTransportStream(originUrl, null) && needsH5eDecrypt(originUrl)) {
            return new ProxyResponse("video/MP2T", getCctvSegment(originUrl));
        }
        if (isTransportStream(originUrl, null) && needsCmgDecrypt(originUrl)) {
            return new ProxyResponse("video/MP2T", getCmgSegment(originUrl));
        }

        HttpURLConnection connection = (HttpURLConnection) URI.create(originUrl).toURL().openConnection();
        connection.setConnectTimeout(UPSTREAM_CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(needsH5eDecrypt(originUrl)
                ? 10000 : UPSTREAM_READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        applyRequestHeaders(connection, originUrl);
        connection.connect();

        boolean responseConsumed = false;
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Upstream HTTP " + status);
            }

            String contentType = connection.getContentType();
            byte[] body = readFully(connection.getInputStream(), connection.getContentLength());
            responseConsumed = true;
            if (!running) {
                throw new SocketException("Proxy closed");
            }
            if (isPlaylist(originUrl, contentType)) {
                String playlist = rewritePlaylist(originUrl, new String(body, UTF_8));
                return new ProxyResponse("application/vnd.apple.mpegurl", playlist.getBytes(UTF_8));
            }

            return new ProxyResponse(contentType == null ? "application/octet-stream" : contentType, body);
        } finally {
            if (!responseConsumed) {
                connection.disconnect();
            }
        }
    }

    private String rewritePlaylist(String playlistUrl, String body) throws IOException {
        URI base = URI.create(playlistUrl);
        String[] lines = body.split("\\r?\\n", -1);
        if (body.contains("#EXT-X-STREAM-INF")) {
            return rewriteMasterPlaylist(base, lines);
        }
        String buffered = rewriteBufferedMediaPlaylist(playlistUrl, base, lines);
        if (buffered != null) {
            return buffered;
        }
        StringBuilder result = new StringBuilder(body.length() + 256);
        for (String line : lines) {
            String rewritten = rewritePlaylistTagUris(base, line);
            if (!line.startsWith("#") && line.length() > 0) {
                rewritten = proxyUrl(base.resolve(line).toString());
            }
            result.append(rewritten).append('\n');
        }
        return result.toString();
    }

    private String rewriteBufferedMediaPlaylist(String playlistUrl, URI base, String[] lines) {
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
            String absolute = base.resolve(line).toString();
            PlaylistSegment segment = new PlaylistSegment(nextSequence++, absolute,
                    new ArrayList<String>(pendingTags));
            currentSegments.add(segment);
            pendingTags.clear();
        }
        if (currentSegments.isEmpty()) {
            return null;
        }

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
                Log.w(TAG, "Buffered media playlist reset after live gap "
                        + segmentName(last.url) + " -> " + segmentName(firstCurrent.url));
                history.clear();
            }
        }
        for (PlaylistSegment segment : currentSegments) {
            history.put(segment.url, segment);
        }
        while (history.size() > LIVE_PLAYLIST_HISTORY_LIMIT) {
            String firstKey = history.keySet().iterator().next();
            history.remove(firstKey);
        }

        List<PlaylistSegment> merged = new ArrayList<PlaylistSegment>(history.values());
        Collections.sort(merged, new Comparator<PlaylistSegment>() {
            @Override
            public int compare(PlaylistSegment left, PlaylistSegment right) {
                return left.sequence < right.sequence ? -1 : (left.sequence == right.sequence ? 0 : 1);
            }
        });
        if (merged.size() > LIVE_PLAYLIST_HISTORY_LIMIT) {
            merged = merged.subList(merged.size() - LIVE_PLAYLIST_HISTORY_LIMIT, merged.size());
        }
        if (isYangshipinUrl(playlistUrl)) {
            List<String> prefetchWindow;
            synchronized (cmgSegmentTasks) {
                if (!playlistUrl.equals(lastCctvPlaylistUrl)) {
                    cmgSegmentTasks.clear();
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
            startCctvPlaylistMonitor(playlistUrl);
        } else {
            List<String> prefetchWindow;
            synchronized (cctvSegmentTasks) {
                if (!playlistUrl.equals(lastCctvPlaylistUrl)) {
                    cctvSegmentTasks.clear();
                    cctvNextSegments.clear();
                    lastCctvRequestedUrl = null;
                    lastCctvPlaylistUrl = playlistUrl;
                }
                for (int index = 0; index + 1 < merged.size(); index++) {
                    cctvNextSegments.put(merged.get(index).url, merged.get(index + 1).url);
                }
                while (cctvNextSegments.size() > LIVE_PLAYLIST_HISTORY_LIMIT * 2) {
                    String first = cctvNextSegments.keySet().iterator().next();
                    cctvNextSegments.remove(first);
                }
                prefetchWindow = buildCctvPrefetchWindowLocked(merged);
            }
            prefetchCctvSegments(prefetchWindow);
            startCctvPlaylistMonitor(playlistUrl);
        }
        long firstSequence = merged.get(0).sequence;
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
        for (PlaylistSegment segment : merged) {
            for (String tag : segment.tags) {
                result.append(tag).append('\n');
            }
            result.append(proxyUrl(segment.url)).append('\n');
        }
        Log.i(TAG, "Buffered media playlist segments current=" + currentSegments.size()
                + " merged=" + merged.size()
                + " seq=" + firstSequence + "-" + merged.get(merged.size() - 1).sequence
                + " " + segmentName(merged.get(0).url)
                + ".." + segmentName(merged.get(merged.size() - 1).url));
        return result.toString();
    }

    private byte[] getCctvSegment(final String originUrl) throws IOException {
        FutureTask<byte[]> task;
        boolean wasPrefetched;
        boolean created = false;
        synchronized (cctvSegmentTasks) {
            task = cctvSegmentTasks.get(originUrl);
            if (task == null) {
                task = newCctvSegmentTask(originUrl);
                cctvSegmentTasks.put(originUrl, task);
                created = true;
            }
            wasPrefetched = task.isDone();
        }
        /* Never run this FutureTask on a proxy request thread. Doing so lets
         * simultaneous HLS requests bypass the single ordered decrypt worker,
         * creating several independent wasm states and decrypting N+1 before N. */
        if (created) {
            cctvPrefetchWorkers.execute(task);
        }
        try {
            long waitStartedAt = SystemClock.elapsedRealtime();
            byte[] body = task.get();
            String next;
            synchronized (cctvSegmentTasks) {
                cctvSegmentTasks.remove(originUrl);
                lastCctvRequestedUrl = originUrl;
                next = cctvNextSegments.get(originUrl);
            }
            if (next != null) {
                prefetchCctvSegment(next);
            }
            if (parallelCctvDecrypt) {
                Log.i(TAG, "CCTV TS ready " + segmentName(originUrl)
                        + " bytes=" + body.length
                        + " prefetched=" + wasPrefetched
                        + " waitMs=" + (SystemClock.elapsedRealtime() - waitStartedAt)
                        + " decryptMs=background");
                return body;
            }
            long decryptStartedAt = SystemClock.elapsedRealtime();
            byte[] decrypted = decryptCctvSegment(body, originUrl, false);
            Log.i(TAG, "CCTV TS ready " + segmentName(originUrl)
                    + " bytes=" + decrypted.length
                    + " prefetched=" + wasPrefetched
                    + " waitMs=" + (decryptStartedAt - waitStartedAt)
                    + " decryptMs=" + (SystemClock.elapsedRealtime() - decryptStartedAt));
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
        synchronized (cmgSegmentCache) {
            cached = cmgSegmentCache.get(originUrl);
        }
        if (cached != null) {
            String next;
            synchronized (cmgSegmentTasks) {
                lastCctvRequestedUrl = originUrl;
                next = cctvNextSegments.get(originUrl);
            }
            if (next != null) {
                prefetchCmgSegment(next);
            }
            Log.i(TAG, "CMG cache hit " + segmentName(originUrl)
                    + " bytes=" + cached.length);
            return cached;
        }
        FutureTask<byte[]> task;
        synchronized (cmgSegmentTasks) {
            task = cmgSegmentTasks.get(originUrl);
            if (task == null) {
                task = newCmgSegmentTask(originUrl);
                cmgSegmentTasks.put(originUrl, task);
                cctvPrefetchWorkers.execute(task);
            }
        }
        long waitStartedAt = SystemClock.elapsedRealtime();
        try {
            byte[] body = task.get();
            String next;
            synchronized (cmgSegmentTasks) {
                cmgSegmentTasks.remove(originUrl);
                lastCctvRequestedUrl = originUrl;
                next = cctvNextSegments.get(originUrl);
            }
            if (next != null) {
                prefetchCmgSegment(next);
            }
            Log.i(TAG, "CMG TS ready " + segmentName(originUrl)
                    + " waitMs=" + (SystemClock.elapsedRealtime() - waitStartedAt));
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
        if (!running || playlistUrl == null) {
            return;
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(playlistUrl).toURL().openConnection();
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(2500);
            connection.setInstanceFollowRedirects(true);
            applyRequestHeaders(connection, playlistUrl);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                return;
            }
            String body = new String(readFully(connection.getInputStream(),
                    connection.getContentLength()), UTF_8);
            if (!playlistUrl.equals(monitoredCctvPlaylistUrl)) {
                return;
            }
            URI base = URI.create(playlistUrl);
            List<String> segments = new ArrayList<String>();
            for (String line : body.split("\\r?\\n")) {
                if (line.length() > 0 && !line.startsWith("#")) {
                    segments.add(base.resolve(line).toString());
                }
            }
            if (segments.isEmpty()) {
                return;
            }
            List<String> prefetchWindow;
            boolean yangshipin = isYangshipinUrl(playlistUrl);
            synchronized (yangshipin ? cmgSegmentTasks : cctvSegmentTasks) {
                for (int index = 0; index + 1 < segments.size(); index++) {
                    cctvNextSegments.put(segments.get(index), segments.get(index + 1));
                }
                prefetchWindow = yangshipin
                        ? buildCmgPrefetchWindowFromUrlsLocked(segments)
                        : buildCctvPrefetchWindowFromUrlsLocked(segments);
            }
            if (yangshipin) {
                prefetchCmgSegments(prefetchWindow);
            } else {
                prefetchCctvSegments(prefetchWindow);
            }
        } catch (Exception error) {
            if (running) {
                Log.d(TAG, "CCTV playlist prefetch poll skipped: " + error.getMessage());
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void prefetchCctvSegment(final String originUrl) {
        if (!running) {
            return;
        }
        FutureTask<byte[]> task;
        boolean created = false;
        synchronized (cctvSegmentTasks) {
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
        if (!running) {
            return;
        }
        FutureTask<byte[]> task;
        boolean created = false;
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
                created = true;
            }
        }
        if (created) {
            cctvPrefetchWorkers.execute(task);
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
        List<String> result = new ArrayList<String>(CMG_PREFETCH_WINDOW);
        String next = lastCctvRequestedUrl == null
                ? (segments.isEmpty() ? null : segments.get(0))
                : cctvNextSegments.get(lastCctvRequestedUrl);
        while (next != null && result.size() < CMG_PREFETCH_WINDOW) {
            result.add(next);
            next = cctvNextSegments.get(next);
        }
        return result;
    }

    private List<String> buildCctvPrefetchWindowLocked(List<PlaylistSegment> segments) {
        List<String> urls = new ArrayList<String>(segments.size());
        for (PlaylistSegment segment : segments) {
            urls.add(segment.url);
        }
        return buildCctvPrefetchWindowFromUrlsLocked(urls);
    }

    private List<String> buildCctvPrefetchWindowFromUrlsLocked(List<String> segments) {
        int count = parallelCctvDecrypt ? CCTV_PARALLEL_PREFETCH_WINDOW : 1;
        List<String> result = new ArrayList<String>(count);
        String next = lastCctvRequestedUrl == null
                ? (segments.isEmpty() ? null : segments.get(0))
                : cctvNextSegments.get(lastCctvRequestedUrl);
        while (next != null && result.size() < count) {
            result.add(next);
            next = cctvNextSegments.get(next);
        }
        return result;
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
                            NativeH5eDecryptor.releaseThreadContext();
                        }
                    }
                }, "cctv-decrypt-" + threadIds.incrementAndGet());
            }
        });
    }

    private FutureTask<byte[]> newCctvSegmentTask(final String originUrl) {
        return new FutureTask<byte[]>(new Callable<byte[]>() {
            @Override
            public byte[] call() throws Exception {
                try {
                    byte[] body = downloadCctvSegment(originUrl);
                    if (!running || Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("CCTV channel switched");
                    }
                    return parallelCctvDecrypt
                            ? decryptCctvSegment(body, originUrl, true) : body;
                } catch (Exception error) {
                    synchronized (cctvSegmentTasks) {
                        cctvSegmentTasks.remove(originUrl);
                    }
                    throw error;
                }
            }
        });
    }

    private FutureTask<byte[]> newCmgSegmentTask(final String originUrl) {
        return new FutureTask<byte[]>(new Callable<byte[]>() {
            @Override
            public byte[] call() throws Exception {
                try {
                    byte[] original = downloadCctvSegment(originUrl);
                    int requestIndex = cmgTsRequestIndex.incrementAndGet();
                    YangshipinSegment segment = parseYangshipinSegment(originUrl);
                    Log.i(TAG, "CMG prefetch TS #" + requestIndex
                            + " " + segmentName(originUrl));
                    prewarmYangshipinState(originUrl, segment, requestIndex);
                    restartCmgRuntime("TS #" + requestIndex);
                    byte[] decrypted = decryptYangshipinTransportStream(original);
                    if (segment != null) {
                        cmgLastYangshipinSegment = Math.max(
                                cmgLastYangshipinSegment, segment.number);
                    }
                    synchronized (cmgSegmentCache) {
                        cmgSegmentCache.put(originUrl, decrypted);
                    }
                    dumpCmgSegmentIfNeeded(requestIndex, originUrl, original, decrypted);
                    return decrypted;
                } catch (Exception error) {
                    synchronized (cmgSegmentTasks) {
                        cmgSegmentTasks.remove(originUrl);
                    }
                    throw error;
                }
            }
        });
    }

    private byte[] decryptCctvSegment(byte[] body, String originUrl, boolean background)
            throws IOException {
        long startedAt = SystemClock.elapsedRealtime();
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);
        } catch (RuntimeException ignored) {
        }
        byte[] decrypted = NativeH5eDecryptor.decryptTransportStream(body);
        if (decrypted == null) {
            throw new IOException("Native H5E decryptor rejected transport stream");
        }
        if (background) {
            Log.i(TAG, "CCTV TS decrypted " + segmentName(originUrl)
                    + " bytes=" + decrypted.length
                    + " decryptMs=" + (SystemClock.elapsedRealtime() - startedAt));
        }
        return decrypted;
    }

    private byte[] downloadCctvSegment(String originUrl) throws IOException {
        HttpURLConnection connection =
                (HttpURLConnection) URI.create(originUrl).toURL().openConnection();
        connection.setConnectTimeout(UPSTREAM_CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(10000);
        connection.setInstanceFollowRedirects(true);
        applyRequestHeaders(connection, originUrl);
        boolean responseConsumed = false;
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Upstream HTTP " + status);
            }
            byte[] body = readFully(connection.getInputStream(), connection.getContentLength());
            responseConsumed = true;
            if (!running) {
                throw new SocketException("Proxy closed");
            }
            return body;
        } finally {
            if (!responseConsumed) {
                connection.disconnect();
            }
        }
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

    private String rewritePlaylistTagUris(URI base, String line) {
        if (!line.startsWith("#")) {
            return line;
        }
        Matcher matcher = ATTRIBUTE_URI.matcher(line);
        StringBuffer updated = new StringBuffer();
        while (matcher.find()) {
            String absolute = base.resolve(matcher.group(1)).toString();
            matcher.appendReplacement(updated,
                    "URI=\"" + Matcher.quoteReplacement(proxyUrl(absolute)) + "\"");
        }
        matcher.appendTail(updated);
        return updated.toString();
    }

    private String rewriteMasterPlaylist(URI base, String[] lines) throws IOException {
        List<Variant> variants = new ArrayList<Variant>();
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (!line.startsWith("#EXT-X-STREAM-INF")) {
                continue;
            }
            Matcher matcher = STREAM_BANDWIDTH.matcher(line);
            int bandwidth = matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
            Matcher resolutionMatcher = STREAM_RESOLUTION.matcher(line);
            int width = 0;
            int height = 0;
            if (resolutionMatcher.find()) {
                width = Integer.parseInt(resolutionMatcher.group(1));
                height = Integer.parseInt(resolutionMatcher.group(2));
            }
            for (int uriIndex = index + 1; uriIndex < lines.length; uriIndex++) {
                String uri = lines[uriIndex];
                if (uri.length() == 0 || uri.startsWith("#")) {
                    continue;
                }
                variants.add(new Variant(line, uri, bandwidth, width, height));
                break;
            }
        }
        if (variants.isEmpty()) {
            return "#EXTM3U\n";
        }
        Collections.sort(variants, new Comparator<Variant>() {
            @Override
            public int compare(Variant left, Variant right) {
                return right.bandwidth - left.bandwidth;
            }
        });

        VariantCandidate selected = null;
        for (Variant variant : variants) {
            String absolute = base.resolve(variant.uri).toString();
            VariantCandidate candidate = inspectVariant(absolute, variant);
            if (!candidate.available) {
                Log.w(TAG, "Skipping unavailable HLS variant bandwidth=" + variant.bandwidth
                        + " uri=" + variant.uri);
                continue;
            }
            if (selected == null || candidate.actualPixels() > selected.actualPixels()) {
                selected = candidate;
            }
            if (candidate.matchesAdvertisedResolution()) {
                selected = candidate;
                break;
            }
            Log.w(TAG, "Skipping mislabeled HLS variant bandwidth=" + variant.bandwidth
                    + " advertised=" + variant.width + "x" + variant.height
                    + " actual=" + candidate.actualDescription()
                    + " uri=" + variant.uri);
        }
        if (selected == null) {
            selected = new VariantCandidate(variants.get(0), true, null);
        }
        Variant variant = selected.variant;
        Log.i(TAG, "Selected HLS variant bandwidth=" + variant.bandwidth
                + " advertised=" + variant.width + "x" + variant.height
                + " actual=" + selected.actualDescription()
                + " uri=" + variant.uri);
        return "#EXTM3U\n" + variant.info + '\n'
                + proxyUrl(base.resolve(variant.uri).toString()) + '\n';
    }

    private static VariantCandidate inspectVariant(String url, Variant variant) {
        HttpURLConnection connection = null;
        boolean responseConsumed = false;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(true);
            applyRequestHeaders(connection, url);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                return new VariantCandidate(variant, false, null);
            }
            String playlist = new String(readFully(connection.getInputStream(),
                    connection.getContentLength()), UTF_8);
            responseConsumed = true;
            String firstSegment = firstMediaSegment(url, playlist);
            if (firstSegment == null) {
                return new VariantCandidate(variant, true, null);
            }
            return new VariantCandidate(variant, true, probeTransportStreamResolution(firstSegment));
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
                return base.resolve(line).toString();
            }
        }
        return null;
    }

    private static Resolution probeTransportStreamResolution(String url) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
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
            return parseTransportStreamResolution(readAtMost(
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

    private static boolean isTransportStream(String url, String contentType) {
        String lowerPath = URI.create(url).getPath().toLowerCase(Locale.US);
        String lowerType = contentType == null ? "" : contentType.toLowerCase(Locale.US);
        return lowerPath.endsWith(".ts") || lowerType.contains("mp2t");
    }

    private static void applyRequestHeaders(HttpURLConnection connection, String url) {
        if (isYangshipinUrl(url)) {
            connection.setRequestProperty("User-Agent", YANGSHIPIN_USER_AGENT);
            connection.setRequestProperty("Referer", "https://www.yangshipin.cn/");
            connection.setRequestProperty("Origin", "https://www.yangshipin.cn");
        } else {
            connection.setRequestProperty("User-Agent", DEFAULT_USER_AGENT);
        }
    }

    private static boolean needsH5eDecrypt(String url) {
        String lower = url.toLowerCase(Locale.US);
        return lower.contains("cdrmld") || lower.contains("cctvwbcd");
    }

    private static boolean needsCmgDecrypt(String url) {
        return isYangshipinUrl(url);
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
            Log.i(TAG, "CMG initial prewarm "
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
            Log.w(TAG, "CMG segment gap too large, prewarming tail only gap=" + missingCount
                    + " current=" + segmentName(originUrl));
        } else {
            Log.i(TAG, "CMG segment gap detected gap=" + missingCount
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
            connection = (HttpURLConnection) URI.create(segmentUrl).toURL().openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(true);
            applyRequestHeaders(connection, segmentUrl);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                Log.w(TAG, "CMG prewarm segment HTTP " + status
                        + " " + segmentName(segmentUrl));
                return null;
            }
            byte[] previous = readFully(connection.getInputStream(),
                    connection.getContentLength());
            responseConsumed = true;
            Log.i(TAG, "CMG prewarm segment " + segmentName(segmentUrl)
                    + " bytes=" + previous.length);
            byte[] decrypted = decryptYangshipinTransportStream(previous);
            cmgSegmentCache.put(segmentUrl, decrypted);
            dumpCmgSegmentIfNeeded(0, segmentUrl, previous, decrypted);
            return decrypted;
        } catch (IOException error) {
            Log.w(TAG, "CMG prewarm segment failed " + segmentName(segmentUrl), error);
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
        synchronized (CMG_DECRYPT_LOCK) {
            long startedAt = SystemClock.elapsedRealtime();
            int videoPid = findVideoPid(ts);
            if (videoPid < 0) {
                return ts;
            }
            // Production never needs the encrypted copy after this point. Mutating the
            // download buffer avoids one 1.5-3 MB Dalvik allocation per live segment.
            // Debug captures still preserve both before/after transport streams.
            byte[] output = cmgVerboseLogging ? ts.clone() : ts;
            DecodeStats totalStats = new DecodeStats();
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
                        totalStats.add(decryptPesNals(output, currentPes));
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
                totalStats.add(decryptPesNals(output, currentPes));
            }
            normalizeVideoContinuityCounters(output, videoPid);
            if (cmgVerboseLogging && totalStats.seen > 0) {
                Log.i(TAG, "CMG decoded TS nals=" + totalStats.decoded
                        + " changed=" + totalStats.changed
                        + " short=" + totalStats.shortOutput
                        + " grew=" + totalStats.grewOutput
                        + " null=" + totalStats.nullOutput
                        + " state=" + totalStats.stateOnly
                        + " seen=" + totalStats.seen);
                if (totalStats.sample.length() > 0
                        && CMG_DETAIL_LOGS.getAndIncrement() < 8) {
                    Log.i(TAG, "CMG NAL sample " + totalStats.sample.toString());
                }
            }
            long elapsedMs = SystemClock.elapsedRealtime() - startedAt;
            if (cmgVerboseLogging || elapsedMs >= 500L) {
                Log.i(TAG, "CMG TS decrypt elapsed=" + elapsedMs + "ms bytes=" + ts.length);
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
            Log.i(TAG, "CMG runtime restarted for " + reason
                    + " initResult=" + String.format(Locale.US, "%08x",
                    NativeCmgDecryptor.getPlayerInitResultForProbe()));
        }
    }

    private static void restartCmgRuntimeAtAccessUnitBoundary(int nalType) throws IOException {
        if (nalType == 7 && cmgVclSinceRuntimeRestart >= CMG_MAX_VCL_PER_RUNTIME) {
            restartCmgRuntime("VCL budget " + cmgVclSinceRuntimeRestart);
        }
    }

    private static byte[] decryptVideoPayloadNals(byte[] data, DecodeStats stats) throws IOException {
        ByteArrayOutputStream rebuilt = new ByteArrayOutputStream(data.length);
        int writeOffset = 0;
        for (int offset = 0; offset < data.length - 4; offset++) {
            int prefix = startCodeLength(data, offset);
            if (prefix == 0) {
                continue;
            }
            int nalStart = offset + prefix;
            if (nalStart >= data.length) {
                continue;
            }
            int nalEnd = data.length;
            for (int next = nalStart + 1; next < data.length - 4; next++) {
                if (startCodeLength(data, next) > 0) {
                    nalEnd = next;
                    break;
                }
            }
            int nalType = data[nalStart] & 0x1f;
            byte[] replacement = null;
            boolean replaceNal = needsCmgNalDecode(nalType);
            boolean stateOnlyNal = needsCmgStateDecode(nalType);
            int updateTag = advanceCmgSessionForNal(nalType);
            if (replaceNal || stateOnlyNal) {
                stats.seen++;
                byte[] nal = new byte[nalEnd - nalStart];
                System.arraycopy(data, nalStart, nal, 0, nal.length);
                updateCmgLiveVideoFlag(nalType, nal);
                long nalStartedAt = SystemClock.elapsedRealtime();
                if (cmgVerboseLogging && replaceNal && nal.length > 100000) {
                    Log.i(TAG, "CMG decoding NAL type=" + nalType + " len=" + nal.length);
                }
                byte[] decoded = NativeCmgDecryptor.decodeNalForProbe(nal, true, true);
                long nalElapsed = SystemClock.elapsedRealtime() - nalStartedAt;
                if (replaceNal && (nalElapsed > 500L
                        || (cmgVerboseLogging && nal.length > 100000))) {
                    Log.i(TAG, "CMG decoded NAL type=" + nalType + " len=" + nal.length
                            + " out=" + (decoded == null ? -1 : decoded.length)
                            + " mode=live"
                            + " elapsed=" + nalElapsed + "ms");
                }
                if (stateOnlyNal) {
                    stats.seen--;
                    stats.stateOnly++;
                    if (decoded == null) {
                        stats.nullOutput++;
                        stats.sampleNal(nalType, nal.length, -1, "state-null");
                        Log.w(TAG, "CMG state NAL rejected type=" + nalType + " len=" + nal.length);
                    } else if (decoded.length > nal.length) {
                        stats.grewOutput++;
                        stats.sampleNal(nalType, nal.length, decoded.length, "state-grew");
                        Log.w(TAG, "Skipping CMG state NAL replacement because length grew type="
                                + nalType + " before=" + nal.length + " after=" + decoded.length);
                    } else if (decoded.length != nal.length || bytesDiffer(decoded, nal)) {
                        stats.sampleNal(nalType, nal.length, decoded.length, "state-changed");
                        Log.w(TAG, "CMG state NAL changed type=" + nalType
                                + " before=" + nal.length + " after=" + decoded.length
                                + "; keeping original bytes");
                    } else {
                        stats.sampleNal(nalType, nal.length, decoded.length, "state");
                    }
                } else if (decoded == null) {
                    stats.nullOutput++;
                    stats.sampleNal(nalType, nal.length, -1, "null");
                    Log.w(TAG, "Skipping CMG NAL replacement because native rejected type="
                            + nalType + " len=" + nal.length);
                } else if (decoded.length > nal.length) {
                    stats.grewOutput++;
                    stats.sampleNal(nalType, nal.length, decoded.length, "grew");
                    Log.w(TAG, "Skipping CMG NAL replacement because length grew type="
                            + nalType + " before=" + nal.length + " after=" + decoded.length);
                } else {
                    boolean nalChanged = bytesDiffer(decoded, nal);
                    if (decoded.length < nal.length) {
                        stats.shortOutput++;
                        stats.sampleNal(nalType, nal.length, decoded.length,
                                nalChanged ? "short-changed" : "short-same");
                        if (cmgVerboseLogging && stats.shortOutput <= 3) {
                            Log.w(TAG, "CMG NAL output shrank type=" + nalType
                                    + " before=" + nal.length + " after=" + decoded.length
                                    + "; repacking PES payload");
                        }
                        replacement = decoded;
                    } else {
                        replacement = decoded;
                        stats.sampleNal(nalType, nal.length, decoded.length,
                                nalChanged ? "changed" : "same");
                    }
                    stats.decoded++;
                }
                if (replacement != null && bytesDiffer(replacement, nal)) {
                    stats.changed++;
                }
            }
            rebuilt.write(data, writeOffset, nalStart - writeOffset);
            if (replacement == null) {
                rebuilt.write(data, nalStart, nalEnd - nalStart);
            } else {
                rebuilt.write(replacement, 0, replacement.length);
            }
            writeOffset = nalEnd;
            offset = nalEnd - 1;
        }
        rebuilt.write(data, writeOffset, data.length - writeOffset);
        return rebuilt.toByteArray();
    }

    private static void normalizeVideoContinuityCounters(byte[] ts, int videoPid) {
        int lastCounter = -1;
        int adjusted = 0;
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
                adjusted++;
            }
            lastCounter = nextCounter;
        }
        if (cmgVerboseLogging && adjusted > 0) {
            Log.i(TAG, "CMG normalized video continuity counters adjusted=" + adjusted);
        }
    }

    private static DecodeStats decryptPesNals(byte[] ts, PesBuffer pes) throws IOException {
        DecodeStats stats = new DecodeStats();
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
                stats.seen++;
                byte[] nal = new byte[nalEnd - nalStart];
                System.arraycopy(data, nalStart, nal, 0, nal.length);
                updateCmgLiveVideoFlag(nalType, nal);
                byte[] decoded = NativeCmgDecryptor.decodeNalForProbe(nal, true, true);
                stats.seen--;
                stats.stateOnly++;
                if (decoded == null) {
                    stats.nullOutput++;
                    Log.w(TAG, "CMG state NAL rejected type=" + nalType + " len=" + nal.length);
                } else if (decoded.length > nal.length) {
                    stats.grewOutput++;
                    Log.w(TAG, "Skipping CMG state NAL replacement because length grew type="
                            + nalType + " before=" + nal.length + " after=" + decoded.length);
                } else if (decoded.length != nal.length || bytesDiffer(decoded, nal)) {
                    Log.w(TAG, "CMG state NAL changed type=" + nalType
                            + " before=" + nal.length + " after=" + decoded.length
                            + "; keeping original bytes");
                }
            } else if (replaceNal) {
                int nalLength = nalEnd - nalStart;
                stats.seen++;
                long nalStartedAt = SystemClock.elapsedRealtime();
                int decodedLength = NativeCmgDecryptor.decodeNalRangeInPlace(
                        data, nalStart, nalLength, true, true);
                long nalElapsed = SystemClock.elapsedRealtime() - nalStartedAt;
                if (nalElapsed > 500L || (cmgVerboseLogging && nalLength > 100000)) {
                    Log.i(TAG, "CMG decoded NAL type=" + nalType + " len=" + nalLength
                            + " out=" + decodedLength + " mode=live-in-place"
                            + " elapsed=" + nalElapsed + "ms");
                }
                if (decodedLength == -2) {
                    stats.grewOutput++;
                    Log.w(TAG, "Skipping CMG NAL replacement because length grew type="
                            + nalType + " before=" + nalLength);
                } else if (decodedLength < 0) {
                    stats.nullOutput++;
                    Log.w(TAG, "Skipping CMG NAL replacement because native rejected type="
                            + nalType + " len=" + nalLength);
                } else {
                    replacementLength = decodedLength;
                    stats.decoded++;
                    stats.changed++;
                    if (decodedLength < nalLength) {
                        stats.shortOutput++;
                        if (cmgVerboseLogging && stats.shortOutput <= 3) {
                            Log.w(TAG, "CMG NAL output shrank type=" + nalType
                                    + " before=" + nalLength + " after=" + decodedLength
                                    + "; repacking PES payload");
                        }
                    }
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
        return stats;
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

    private static int firstDiff(byte[] left, byte[] right) {
        int length = Math.min(left.length, right.length);
        for (int index = 0; index < length; index++) {
            if (left[index] != right[index]) {
                return index;
            }
        }
        return left.length == right.length ? -1 : length;
    }

    private static int diffCount(byte[] left, byte[] right) {
        int length = Math.min(left.length, right.length);
        int diff = Math.abs(left.length - right.length);
        for (int index = 0; index < length; index++) {
            if (left[index] != right[index]) {
                diff++;
            }
        }
        return diff;
    }

    private static String hexHead(byte[] data, int length) {
        StringBuilder builder = new StringBuilder(length * 2);
        int count = Math.min(data.length, length);
        for (int index = 0; index < count; index++) {
            builder.append(String.format(Locale.US, "%02x", data[index] & 0xff));
        }
        return builder.toString();
    }

    private static int advanceCmgSessionForNal(int nalType) {
        int updateTag;
        String mediaTag = cmgDebugPlayerTag;
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
        if (cmgVerboseLogging && CMG_DECODE_DETAIL_LOGS.get() < 24) {
            Log.i(TAG, "CMG UpdatePlayer before NAL type=" + nalType + " tag="
                    + String.format(Locale.US, "%08x", updateTag)
                    + " mediaTag=" + mediaTag);
        }
        return updateTag;
    }

    private static void syncCmgRuntimeClockForNative() {
        if (cmgDebugClockBaseTimeMs <= 0L) {
            return;
        }
        // The browser wrapper ultimately reads Date.now(). Use the wall clock here too,
        // so suspend, NTP corrections, and date changes do not leave wasm on a stale epoch.
        NativeCmgDecryptor.setClockForProbe(System.currentTimeMillis() + cmgDebugClockOffsetMs);
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
        if (cmgVerboseLogging) {
            Log.i(TAG, "CMG SPS live video decode flag bits=" + bits
                    + " enabled=" + cmgLiveVideoDecodeEnabled);
        }
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

    private void dumpCmgSegmentIfNeeded(int requestIndex, String originUrl,
            byte[] original, byte[] decrypted) {
        if (cmgDebugDir == null) {
            return;
        }
        int dumpIndex = cmgDumpIndex.incrementAndGet();
        if (dumpIndex > 120) {
            return;
        }
        if (!cmgDebugDir.exists() && !cmgDebugDir.mkdirs()) {
            Log.w(TAG, "Unable to create CMG debug dir " + cmgDebugDir);
            return;
        }
        String prefix = String.format(Locale.US, "seg-%03d", dumpIndex);
        try {
            writeFile(new File(cmgDebugDir, prefix + "-original.ts"), original);
            writeFile(new File(cmgDebugDir, prefix + "-app.ts"), decrypted);
            StringBuilder meta = new StringBuilder();
            synchronized (CMG_DECRYPT_LOCK) {
                meta.append("requestIndex=").append(requestIndex).append('\n');
                meta.append("url=").append(originUrl).append('\n');
                meta.append("activeURL=https://www.yangshipin.cn\n");
                meta.append("playerTag=").append(cmgDebugPlayerTag).append('\n');
                meta.append("initialUpdateTag=").append(cmgDebugInitialTag).append('\n');
                meta.append("stableUpdateTag=").append(cmgDebugStableTag).append('\n');
                meta.append("initialUpdateTagInt=")
                        .append(String.format(Locale.US, "%08x", cmgInitialUpdateTag)).append('\n');
                meta.append("stableUpdateTagInt=")
                        .append(String.format(Locale.US, "%08x", cmgStableUpdateTag)).append('\n');
                meta.append("initTimeMs=").append(cmgDebugInitTimeMs).append('\n');
                meta.append("updateBaseTimeMs=").append(cmgDebugUpdateBaseTimeMs).append('\n');
                meta.append("updateTrace=").append(cmgDebugUpdateTrace).append('\n');
            }
            writeFile(new File(cmgDebugDir, prefix + "-meta.txt"), meta.toString().getBytes(UTF_8));
            Log.i(TAG, "CMG dumped TS " + prefix + " dir=" + cmgDebugDir.getAbsolutePath()
                    + " original=" + original.length + " app=" + decrypted.length);
        } catch (IOException error) {
            Log.w(TAG, "Unable to dump CMG TS " + prefix, error);
        }
    }

    private static void writeFile(File file, byte[] body) throws IOException {
        FileOutputStream output = new FileOutputStream(file);
        try {
            output.write(body);
        } finally {
            output.close();
        }
    }

    private static byte[] readFully(InputStream input) throws IOException {
        return readFully(input, -1);
    }

    private static byte[] readFully(InputStream input, int expectedLength) throws IOException {
        try {
            if (expectedLength > 0 && expectedLength <= MAX_PREALLOCATED_RESPONSE_BYTES) {
                byte[] body = new byte[expectedLength];
                int offset = 0;
                while (offset < body.length) {
                    int count = input.read(body, offset, body.length - offset);
                    if (count == -1) {
                        break;
                    }
                    offset += count;
                }
                if (offset == body.length) {
                    return body;
                }
                return Arrays.copyOf(body, offset);
            }
            int initialCapacity = expectedLength > 0
                    && expectedLength <= MAX_PREALLOCATED_RESPONSE_BYTES
                    ? expectedLength : 256 * 1024;
            ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity);
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            input.close();
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

    private static void drainHeaders(InputStream input) throws IOException {
        String line;
        do {
            line = readAsciiLine(input);
        } while (line != null && line.length() > 0);
    }

    private static void writeOk(OutputStream output, String contentType, byte[] body) throws IOException {
        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(UTF_8));
        output.write(body);
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

    @Override
    public void close() {
        running = false;
        monitoredCctvPlaylistUrl = null;
        NativeH5eDecryptor.cancelPendingDecrypts();
        synchronized (cctvSegmentTasks) {
            for (FutureTask<byte[]> task : cctvSegmentTasks.values()) {
                task.cancel(true);
            }
            cctvSegmentTasks.clear();
            cctvNextSegments.clear();
            lastCctvRequestedUrl = null;
            lastCctvPlaylistUrl = null;
        }
        synchronized (cmgSegmentTasks) {
            for (FutureTask<byte[]> task : cmgSegmentTasks.values()) {
                task.cancel(true);
            }
            cmgSegmentTasks.clear();
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
        workers.shutdownNow();
        Log.i(TAG, "Proxy closed port=" + port);
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
                    if (streamType == 0x1b || streamType == 0x24) {
                        return elementaryPid;
                    }
                    index += 5 + infoLength;
                }
            }
        }
        return -1;
    }

    private static int payloadOffset(byte[] ts, int packetOffset) {
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

    private static final class DecodeStats {
        int seen;
        int decoded;
        int changed;
        int shortOutput;
        int grewOutput;
        int nullOutput;
        int stateOnly;
        final StringBuilder sample = new StringBuilder();
        private int sampledNalCount;

        void add(DecodeStats other) {
            seen += other.seen;
            decoded += other.decoded;
            changed += other.changed;
            shortOutput += other.shortOutput;
            grewOutput += other.grewOutput;
            nullOutput += other.nullOutput;
            stateOnly += other.stateOnly;
        }

        void sampleNal(int nalType, int beforeLength, int afterLength, String status) {
            if (sampledNalCount >= 48) {
                return;
            }
            if (sample.length() > 0) {
                sample.append(' ');
            }
            sample.append(nalType)
                    .append(':')
                    .append(beforeLength)
                    .append("->")
                    .append(afterLength)
                    .append(':')
                    .append(status);
            sampledNalCount++;
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
                Log.w(TAG, "Skipping CMG video payload copy because length changed before="
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
                Log.w(TAG, "Rebuilt PES payload did not fit original TS packets before="
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
                Log.w(TAG, "Cannot update PES length because rebuilt payload is too large: "
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

    private static final class ProxyResponse {
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
