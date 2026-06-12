package com.bu.cc.tv;

import android.os.SystemClock;
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
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class HlsProxyServer implements Closeable {
    private static final String TAG = "HlsProxyServer";
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final Pattern ATTRIBUTE_URI = Pattern.compile("URI=\"([^\"]+)\"");
    private static final Pattern STREAM_BANDWIDTH = Pattern.compile("BANDWIDTH=(\\d+)");
    private static final Pattern STREAM_RESOLUTION = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)");
    private static final String DEFAULT_USER_AGENT = "nTv/1.0";
    private static final String YANGSHIPIN_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    private static final Object CMG_DECRYPT_LOCK = new Object();
    private static final AtomicInteger CMG_DETAIL_LOGS = new AtomicInteger();
    private static final AtomicInteger CMG_DECODE_DETAIL_LOGS = new AtomicInteger();
    private static boolean cmgSessionWarmed;
    private static int cmgInitialUpdateTag;
    private static int cmgStableUpdateTag;
    private static boolean cmgFirstStateNalPending;
    private static String cmgDebugPlayerTag = "";
    private static String cmgDebugInitialTag = "";
    private static String cmgDebugStableTag = "";
    private final ExecutorService workers = Executors.newFixedThreadPool(3);
    private final File cmgDebugDir;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;
    private final AtomicInteger cmgTsRequestIndex = new AtomicInteger();
    private final AtomicInteger cmgDumpIndex = new AtomicInteger();

    HlsProxyServer() {
        this(null);
    }

    HlsProxyServer(File cmgDebugDir) {
        this.cmgDebugDir = cmgDebugDir;
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
            CMG_DETAIL_LOGS.set(0);
            CMG_DECODE_DETAIL_LOGS.set(0);
            Log.i(TAG, "CMG proxy update tags initial="
                    + String.format(Locale.US, "%08x", initialUpdateTag)
                    + " stable=" + String.format(Locale.US, "%08x", stableUpdateTag));
        }
    }

    static void configureCmgDebugContext(String playerTag,
            String initialUpdateTag, String stableUpdateTag) {
        synchronized (CMG_DECRYPT_LOCK) {
            cmgDebugPlayerTag = playerTag == null ? "" : playerTag;
            cmgDebugInitialTag = initialUpdateTag == null ? "" : initialUpdateTag;
            cmgDebugStableTag = stableUpdateTag == null ? "" : stableUpdateTag;
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
            writeOk(output, response.contentType, response.body);
        } catch (Exception error) {
            if (isPlayerDisconnect(error)) {
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
        HttpURLConnection connection = (HttpURLConnection) URI.create(originUrl).toURL().openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(true);
        applyRequestHeaders(connection, originUrl);
        connection.connect();

        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Upstream HTTP " + status);
            }

            String contentType = connection.getContentType();
            byte[] body = readFully(connection.getInputStream());
            if (isPlaylist(originUrl, contentType)) {
                String playlist = rewritePlaylist(originUrl, new String(body, UTF_8));
                return new ProxyResponse("application/vnd.apple.mpegurl", playlist.getBytes(UTF_8));
            }

            if (isTransportStream(originUrl, contentType) && needsH5eDecrypt(originUrl)) {
                byte[] decrypted = NativeH5eDecryptor.decryptTransportStream(body);
                if (decrypted == null) {
                    throw new IOException("Native H5E decryptor rejected transport stream");
                }
                body = decrypted;
            }
            if (isTransportStream(originUrl, contentType) && needsCmgDecrypt(originUrl)) {
                int requestIndex = cmgTsRequestIndex.incrementAndGet();
                Log.i(TAG, "CMG fetch TS #" + requestIndex
                        + " " + segmentName(originUrl));
                byte[] original = body;
                body = decryptYangshipinTransportStream(body);
                dumpCmgSegmentIfNeeded(requestIndex, originUrl, original, body);
            }
            return new ProxyResponse(contentType == null ? "application/octet-stream" : contentType, body);
        } finally {
            connection.disconnect();
        }
    }

    private String rewritePlaylist(String playlistUrl, String body) throws IOException {
        URI base = URI.create(playlistUrl);
        String[] lines = body.split("\\r?\\n", -1);
        if (body.contains("#EXT-X-STREAM-INF")) {
            return rewriteMasterPlaylist(base, lines);
        }
        StringBuilder result = new StringBuilder(body.length() + 256);
        for (String line : lines) {
            String rewritten = line;
            if (line.startsWith("#")) {
                Matcher matcher = ATTRIBUTE_URI.matcher(line);
                StringBuffer updated = new StringBuffer();
                while (matcher.find()) {
                    String absolute = base.resolve(matcher.group(1)).toString();
                    matcher.appendReplacement(updated, "URI=\"" + Matcher.quoteReplacement(proxyUrl(absolute)) + "\"");
                }
                matcher.appendTail(updated);
                rewritten = updated.toString();
            } else if (line.length() > 0) {
                rewritten = proxyUrl(base.resolve(line).toString());
            }
            result.append(rewritten).append('\n');
        }
        return result.toString();
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
            String playlist = new String(readFully(connection.getInputStream()), UTF_8);
            String firstSegment = firstMediaSegment(url, playlist);
            if (firstSegment == null) {
                return new VariantCandidate(variant, true, null);
            }
            return new VariantCandidate(variant, true, probeTransportStreamResolution(firstSegment));
        } catch (IOException error) {
            return new VariantCandidate(variant, false, null);
        } finally {
            if (connection != null) {
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
            applyRequestHeaders(connection, url);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                return null;
            }
            return parseTransportStreamResolution(readFully(connection.getInputStream()));
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

    private static byte[] decryptYangshipinTransportStream(byte[] ts) throws IOException {
        synchronized (CMG_DECRYPT_LOCK) {
            long startedAt = SystemClock.elapsedRealtime();
            int videoPid = findVideoPid(ts);
            if (videoPid < 0) {
                return ts;
            }
            byte[] output = ts.clone();
            DecodeStats totalStats = new DecodeStats();
            PesBuffer currentPes = null;
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
                    if (currentPes != null && currentPes.size() > 0) {
                        totalStats.add(decryptPesNals(output, currentPes));
                    }
                    currentPes = null;
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
                        currentPes = new PesBuffer();
                        currentPes.setHeader(payloadOffset, pesHeaderLength, pesPacketLength);
                        payloadOffset += pesHeaderLength;
                    }
                }
                if (currentPes != null && payloadOffset < packetOffset + 188) {
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
            if (currentPes != null && currentPes.size() > 0) {
                totalStats.add(decryptPesNals(output, currentPes));
            }
            if (totalStats.seen > 0) {
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
            Log.i(TAG, "CMG TS decrypt elapsed=" + (SystemClock.elapsedRealtime() - startedAt)
                    + "ms bytes=" + ts.length);
            return output;
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
            int updateTag = advanceCmgSessionForNal();
            boolean replaceNal = needsCmgNalDecode(nalType);
            boolean stateOnlyNal = needsCmgStateDecode(nalType);
            if (replaceNal || stateOnlyNal) {
                stats.seen++;
                byte[] nal = new byte[nalEnd - nalStart];
                System.arraycopy(data, nalStart, nal, 0, nal.length);
                long nalStartedAt = SystemClock.elapsedRealtime();
                if (replaceNal && nal.length > 100000) {
                    Log.i(TAG, "CMG decoding NAL type=" + nalType + " len=" + nal.length);
                }
                byte[] decoded = NativeCmgDecryptor.decodeNalForProbe(nal, true, true);
                long nalElapsed = SystemClock.elapsedRealtime() - nalStartedAt;
                if (replaceNal && (nalElapsed > 500L || nal.length > 100000)) {
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
                    } else if (decoded.length != nal.length || bytesDiffer(decoded, nal)) {
                        stats.sampleNal(nalType, nal.length, decoded.length, "state-changed");
                        Log.w(TAG, "CMG state NAL changed type=" + nalType + " len=" + nal.length
                                + " out=" + decoded.length + "; keeping original bytes");
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
                        if (stats.shortOutput <= 3) {
                            Log.w(TAG, "CMG NAL output shrank type=" + nalType
                                    + " before=" + nal.length + " after=" + decoded.length
                                    + "; rebuilding PES without padding");
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

    private static DecodeStats decryptPesNals(byte[] ts, PesBuffer pes) throws IOException {
        DecodeStats stats = new DecodeStats();
        byte[] data = pes.toByteArray();
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
            int updateTag = advanceCmgSessionForNal();
            boolean replaceNal = needsCmgNalDecode(nalType);
            boolean stateOnlyNal = needsCmgStateDecode(nalType);
            if (replaceNal || stateOnlyNal) {
                stats.seen++;
                byte[] nal = new byte[nalEnd - nalStart];
                System.arraycopy(data, nalStart, nal, 0, nal.length);
                long nalStartedAt = SystemClock.elapsedRealtime();
                if (replaceNal && nal.length > 100000) {
                    Log.i(TAG, "CMG decoding NAL type=" + nalType + " len=" + nal.length);
                }
                byte[] decoded = NativeCmgDecryptor.decodeNalForProbe(nal, true, true);
                long nalElapsed = SystemClock.elapsedRealtime() - nalStartedAt;
                if (replaceNal && (nalElapsed > 500L || nal.length > 100000)) {
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
                        Log.w(TAG, "CMG state NAL rejected type=" + nalType + " len=" + nal.length);
                    } else if (decoded.length != nal.length || bytesDiffer(decoded, nal)) {
                        Log.w(TAG, "CMG state NAL changed type=" + nalType + " len=" + nal.length
                                + " out=" + decoded.length + "; keeping original bytes");
                    }
                } else if (decoded == null) {
                    stats.nullOutput++;
                    Log.w(TAG, "Skipping CMG NAL replacement because native rejected type="
                            + nalType + " len=" + nal.length);
                } else if (decoded.length > nal.length) {
                    stats.grewOutput++;
                    Log.w(TAG, "Skipping CMG NAL replacement because length grew type="
                            + nalType + " before=" + nal.length + " after=" + decoded.length);
                } else {
                    int nalDiff = firstDiff(decoded, nal);
                    if (replaceNal && CMG_DECODE_DETAIL_LOGS.getAndIncrement() < 24) {
                        Log.i(TAG, "CMG PES NAL detail type=" + nalType
                                + " len=" + nal.length
                                + " out=" + decoded.length
                                + " firstDiff=" + nalDiff
                                + " diffCount=" + diffCount(decoded, nal)
                                + " tag=" + String.format(Locale.US, "%08x", updateTag)
                                + " before32=" + hexHead(nal, 48)
                                + " after32=" + hexHead(decoded, 48));
                    }
                    if (decoded.length < nal.length) {
                        stats.shortOutput++;
                        if (stats.shortOutput <= 3) {
                            Log.w(TAG, "CMG NAL output shrank type=" + nalType
                                    + " before=" + nal.length + " after=" + decoded.length);
                        }
                    }
                    replacement = decoded;
                    stats.decoded++;
                }
                if (replacement != null
                        && (replacement.length != nal.length || bytesDiffer(replacement, nal))) {
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
        pes.copyPayloadToTransportStream(ts, rebuilt.toByteArray());
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

    private static int advanceCmgSessionForNal() {
        int updateTag = NativeCmgDecryptor.updateSessionForProbe();
        if (CMG_DECODE_DETAIL_LOGS.get() < 24) {
            Log.i(TAG, "CMG UpdatePlayer before NAL tag="
                    + String.format(Locale.US, "%08x", updateTag));
        }
        return updateTag;
    }

    private static boolean needsCmgNalDecode(int nalType) {
        return nalType == 1 || nalType == 5;
    }

    private static boolean needsCmgStateDecode(int nalType) {
        return nalType == 7;
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
        if (dumpIndex > 3) {
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
                meta.append("playerTag=").append(cmgDebugPlayerTag).append('\n');
                meta.append("initialUpdateTag=").append(cmgDebugInitialTag).append('\n');
                meta.append("stableUpdateTag=").append(cmgDebugStableTag).append('\n');
                meta.append("initialUpdateTagInt=")
                        .append(String.format(Locale.US, "%08x", cmgInitialUpdateTag)).append('\n');
                meta.append("stableUpdateTagInt=")
                        .append(String.format(Locale.US, "%08x", cmgStableUpdateTag)).append('\n');
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
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(256 * 1024);
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
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        workers.shutdownNow();
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
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
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

        byte[] toByteArray() {
            byte[] data = bytes.toByteArray();
            int payloadLength = expectedPayloadLength();
            if (payloadLength >= 0 && payloadLength < data.length) {
                return Arrays.copyOf(data, payloadLength);
            }
            return data;
        }

        int size() {
            return bytes.size();
        }

        void copyPayloadToTransportStream(byte[] ts, byte[] data) {
            updatePesLength(ts, data.length);
            int dataOffset = 0;
            for (PesSlot slot : slots) {
                int packetEnd = slot.packetOffset + 188;
                int capacity = packetEnd - slot.transportOffset;
                int remaining = data.length - dataOffset;
                int count = Math.min(capacity, Math.max(remaining, 0));
                if (count == capacity) {
                    System.arraycopy(data, dataOffset, ts, slot.transportOffset, count);
                    dataOffset += count;
                    continue;
                }
                Arrays.fill(ts, slot.packetOffset + 4, packetEnd, (byte) 0xff);
                int header = ts[slot.packetOffset + 3] & 0xff;
                if (count <= 0) {
                    ts[slot.packetOffset + 1] = (byte) (ts[slot.packetOffset + 1] & ~0x40);
                    ts[slot.packetOffset + 3] = (byte) ((header & 0xcf) | 0x20);
                    ts[slot.packetOffset + 4] = (byte) 183;
                    ts[slot.packetOffset + 5] = 0;
                } else {
                    int payloadOffset = packetEnd - count;
                    int adaptationLength = payloadOffset - slot.packetOffset - 5;
                    ts[slot.packetOffset + 3] = (byte) ((header & 0xcf) | 0x30);
                    ts[slot.packetOffset + 4] = (byte) adaptationLength;
                    if (adaptationLength > 0) {
                        ts[slot.packetOffset + 5] = 0;
                    }
                    System.arraycopy(data, dataOffset, ts, payloadOffset, count);
                    dataOffset += count;
                }
            }
            if (dataOffset < data.length) {
                Log.w(TAG, "Rebuilt PES payload did not fit original TS packets before="
                        + bytes.size() + " after=" + data.length);
            }
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
