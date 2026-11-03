package xiao.bu.tv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves script-style media URLs without buffering an unbounded media response. */
final class HttpStreamResolver {
    private static final int MAX_REDIRECTS = 6;
    private static final int MAX_TRANSIENT_ATTEMPTS = 10;
    private static final int RETRY_DELAY_MS = 80;
    private static final int MAX_TEXT_BYTES = 64 * 1024;
    private static final int MAX_RESOLVE_MS = 12000;
    private static final int PROBE_BYTES = 1024;
    private static final int MAX_PLAYLIST_BYTES = 128 * 1024;
    private static final Pattern HTML_START = Pattern.compile(
            "(?is)^(?:\\s|\\ufeff|<!--.*?-->|<\\?xml.*?\\?>)*<(?:!doctype\\s+html\\b|html\\b|head\\b|body\\b|script\\b|meta\\b|video\\b|iframe\\b)");
    private static final Pattern HTML_FRAGMENT_START = Pattern.compile(
            "(?is)^(?:\\s|\\ufeff|<!--.*?-->)*</?[a-z][a-z0-9:-]*(?:\\s[^<>]*)?/?>");
    private static final Pattern SDP_MEDIA = Pattern.compile("(?m)^m=(?:video|audio) +[0-9]+(?:/[0-9]+)? +RTP/",Pattern.CASE_INSENSITIVE);
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 9; TV) "
            + "AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36";
    private static final Pattern ABSOLUTE_MEDIA_URL = Pattern.compile(
            "https?://[^\\s\\\"'<>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAMED_MEDIA_URL = Pattern.compile(
            "(?i)[\\\"']?(?:url|playurl|play_url|hls|m3u8|flv)[\\\"']?\\s*[:=]\\s*"
                    + "[\\\"'](https?://[^\\\"']+)[\\\"']");

    static final class Result {
        final String url;
        final boolean directMedia;
        final boolean webPage;
        final boolean htmlMimeOverride;
        final byte[] playlist;
        final long playlistReceivedNanos;
        final boolean continuousMedia;
        final String playbackUrl;

        Result(String url, boolean directMedia) {
            this(url, directMedia, false);
        }

        Result(String url, boolean directMedia, boolean webPage) {
            this(url, directMedia, webPage, false);
        }

        Result(String url, boolean directMedia, boolean webPage, boolean htmlMimeOverride) {
            this(url, directMedia, webPage, htmlMimeOverride, null);
        }

        Result(String url, boolean directMedia, boolean webPage, boolean htmlMimeOverride, byte[] playlist) {
            this(url, directMedia, webPage, htmlMimeOverride, playlist, false);
        }

        Result(String url, boolean directMedia, boolean webPage, boolean htmlMimeOverride,
                byte[] playlist, boolean continuousMedia) {
            this(url, directMedia, webPage, htmlMimeOverride, playlist, continuousMedia, url);
        }

        Result(String url, boolean directMedia, boolean webPage, boolean htmlMimeOverride,
                byte[] playlist, boolean continuousMedia, String playbackUrl) {
            this.url = url;
            this.directMedia = directMedia;
            this.webPage = webPage;
            this.htmlMimeOverride = htmlMimeOverride;
            this.playlist = playlist;
            this.playlistReceivedNanos = System.nanoTime();
            this.continuousMedia = continuousMedia;
            this.playbackUrl = playbackUrl;
        }
    }

    static final class InvalidSourceUrlException extends IOException {
        final String invalidUrl;
        final String problem;

        InvalidSourceUrlException(String url, String reason, Throwable cause) {
            super("源地址格式错误：" + reason, cause);
            invalidUrl = url == null ? "（空地址）" : url;
            problem = reason;
        }

        String userMessage() {
            return "错误地址：\n" + invalidUrl + "\n\n格式问题：\n" + problem
                    + "\n\n地址填写示例：\nhttps://example.com/live.m3u8"
                    + "\n\n频道列表格式示例：\n分组名称,#genre#"
                    + "\n频道名称,https://example.com/live.m3u8"
                    + "\n\n#genre# 是独立的分组标记，不要拼在地址后面。";
        }
    }

    private HttpStreamResolver() {
    }

    /** Only explicit HLS URLs can try native demuxing before content classification. */
    static boolean isExplicitHlsUrl(String value) {
        if (value == null) return false;
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getUserInfo() != null) return false;
            String path = uri.getPath();
            if (path != null && path.toLowerCase(Locale.US).endsWith(".m3u8")) return true;
            String query = uri.getRawQuery();
            if (query != null) for (String parameter : query.split("&")) {
                if ("format=m3u8".equalsIgnoreCase(parameter)
                        || "type=m3u8".equalsIgnoreCase(parameter)) return true;
            }
        } catch (URISyntaxException invalid) { }
        return false;
    }

    static boolean shouldResolve(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false;
        }
        // A media-looking URL can redirect to HTML (or return it directly).
        return true;
    }

    static Result resolve(String value) throws IOException {
        long deadline = System.nanoTime() + MAX_RESOLVE_MS * 1000000L;
        IOException lastError = null;
        for (int attempt = 1; attempt <= MAX_TRANSIENT_ATTEMPTS; attempt++) {
            try {
                return resolveInternal(value, deadline);
            } catch (HttpStatusException error) {
                String fallback = legacyFallback(value, error.statusCode);
                if (fallback != null) {
                    return resolveInternal(fallback, deadline);
                }
                if (!isRetryableStatus(error.statusCode)) {
                    throw error;
                }
                lastError = error;
            } catch (RetryableSourceException error) {
                lastError = error;
            } catch (IOException error) {
                // A pooled socket can close between selection and response headers.
                // Retry this read-only classification within its original deadline;
                // cancellation and invalid content must still fail immediately.
                checkCancelled();
                if (!isRetryableTransport(error)) throw error;
                lastError = error;
            }
            if (attempt < MAX_TRANSIENT_ATTEMPTS) {
                try {
                    Thread.sleep(Math.min((long) RETRY_DELAY_MS * attempt, remainingMs(deadline)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("源地址解析已取消", interrupted);
                }
            }
        }
        throw lastError == null ? new IOException("源地址解析失败") : lastError;
    }

    private static boolean isRetryableTransport(IOException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.io.EOFException || cause instanceof java.net.SocketException
                    || cause instanceof java.net.SocketTimeoutException) return true;
        }
        return false;
    }

    private static int remainingMs(long deadline) throws IOException {
        checkCancelled();
        long remaining = (deadline - System.nanoTime()) / 1000000L;
        if (remaining <= 0) throw new java.net.SocketTimeoutException("源地址内容识别超时");
        return (int) Math.min(MAX_RESOLVE_MS, remaining);
    }

    private static boolean isHtmlType(String contentType) {
        return contentType.contains("text/html") || contentType.contains("application/xhtml+xml");
    }

    static boolean isHtmlPrefix(byte[] bytes) throws IOException {
        return HTML_START.matcher(decodeText(bytes)).find();
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("源地址解析已取消");
    }

    private static Result resolveInternal(String value, long deadline) throws IOException {
        String current = value;
        String mediaEntry = value;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            URI currentUri = httpUri(current);
            int remaining = remainingMs(deadline);
            HttpURLConnection connection = NetworkClient.openBounded(currentUri.toURL(), remaining);
            connection.setConnectTimeout(Math.min(7000, remaining));
            connection.setReadTimeout(Math.min(7000, remaining));
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept",
                    "application/vnd.apple.mpegurl,application/x-mpegURL,video/*,audio/*,*/*;q=0.8");
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("Pragma", "no-cache");
            try {
                int status = connection.getResponseCode();
                if (status == HttpURLConnection.HTTP_MOVED_PERM
                        || status == HttpURLConnection.HTTP_MOVED_TEMP
                        || status == HttpURLConnection.HTTP_SEE_OTHER
                        || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.length() == 0) {
                        throw new IOException("源地址重定向缺少目标地址");
                    }
                    try {
                        current = currentUri.resolve(new URI(location.trim())).toString();
                    } catch (URISyntaxException error) {
                        throw new InvalidSourceUrlException(location,
                                "服务器返回的重定向地址包含非法字符（位置 " + error.getIndex() + "）", error);
                    }
                    httpUri(current);
                    continue;
                }
                if (status < 200 || status >= 300) {
                    throw new HttpStatusException(status);
                }

                String contentType = normalizeContentType(connection.getContentType());
                InputStream input = connection.getInputStream();
                byte[] probe = readPrefix(input);
                if (startsWithFlv(probe)) {
                    return new Result(current, true, false, false, null,
                            connection.getContentLength() < 0,
                            connection.getContentLength() < 0 ? mediaEntry : current);
                }
                if (startsWithTransportStream(probe)) {
                    return new Result(current, true, false, false, null,
                            connection.getContentLength() < 0,
                            connection.getContentLength() < 0 ? mediaEntry : current);
                }
                if (startsWithMp4(probe) || startsWithOtherMedia(probe)) return new Result(current, true);
                String body = decodeText(probe);
                if (startsWithPlaylist(body)) {
                    // The player needs this manifest too. Hand off this exact
                    // response once instead of paying a slow PHP gateway twice.
                    ByteArrayOutputStream manifest = new ByteArrayOutputStream();
                    manifest.write(probe);
                    manifest.write(readAtMost(input, MAX_PLAYLIST_BYTES + 1 - probe.length));
                    byte[] bytes = manifest.toByteArray();
                    return new Result(current, false, false, false,
                            bytes.length <= MAX_PLAYLIST_BYTES ? decodeText(bytes).getBytes("UTF-8") : null);
                }
                // SDP describes dynamic RTP payloads; FFmpeg opens its referenced
                // RTP sockets. Preserve the descriptor instead of treating it as
                // an API response or an unrecognised HLS playlist.
                String descriptor=body.trim();
                if ((descriptor.startsWith("v=0\n") || descriptor.startsWith("v=0\r\n"))
                        && SDP_MEDIA.matcher(descriptor).find())
                    return new Result(current,true);
                // A real page keeps its navigation/player/scripts. Do not choose
                // an arbitrary .m3u8 link from its examples, menus or source code.
                if (HTML_START.matcher(body).find()) return new Result(current, false, true,
                        !isHtmlType(contentType));
                boolean apiText = body.startsWith("{") || body.startsWith("[")
                        || body.startsWith("http://") || body.startsWith("https://");
                // PHP gateways often inherit text/html for plain URLs, API
                // responses or upstream errors. MIME alone does not prove HTML.
                if (!apiText && isHtmlType(contentType) && HTML_FRAGMENT_START.matcher(body).find())
                    return new Result(current, false, true);
                if (!apiText && probe.length < PROBE_BYTES && isTransientFailureBody(body))
                    throw new RetryableSourceException(body.length() == 0 ? "源地址返回空内容" : body);
                if (!apiText && contentType.contains("mpegurl")) return new Result(current, false);
                if (!apiText && isDirectMediaType(contentType)) return new Result(current, true);
                // Preserve support for binary media with generic/missing MIME.
                if (isKnownMediaUrl(current.toLowerCase(Locale.US)) && !looksLikeText(probe))
                    return new Result(current, !isHlsUrl(current));
                if (!apiText && !isTextType(contentType))
                    throw new IOException("源地址没有返回可识别的视频内容");
                if (probe.length >= PROBE_BYTES) {
                    ByteArrayOutputStream text = new ByteArrayOutputStream();
                    text.write(probe);
                    text.write(readAtMost(input, MAX_TEXT_BYTES - probe.length));
                    body = decodeText(text.toByteArray());
                }
                if (HTML_START.matcher(body).find()) return new Result(current, false, true,
                        !isHtmlType(contentType));
                String mediaUrl = findMediaUrl(body);
                if (mediaUrl != null) {
                    httpUri(mediaUrl);
                    current = mediaUrl;
                    mediaEntry = mediaUrl;
                    continue;
                }
                if (isTransientFailureBody(body)) {
                    throw new RetryableSourceException(body.length() == 0
                            ? "源地址返回空内容" : body);
                }
                throw new IOException("源地址未返回可识别的视频地址");
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("源地址重定向次数过多");
    }

    private static URI httpUri(String value) throws IOException {
        if (value == null || value.trim().length() == 0) {
            throw new InvalidSourceUrlException(value, "地址不能为空", null);
        }
        try {
            URI uri = new URI(value.trim());
            if ((!"http".equalsIgnoreCase(uri.getScheme())
                    && !"https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getHost().length() == 0) {
                throw new InvalidSourceUrlException(value,
                        "需要以 http:// 或 https:// 开头，并包含完整的服务器域名或 IP", null);
            }
            return uri;
        } catch (URISyntaxException error) {
            // Callers handle IOException on the resolver thread. URI.create's
            // unchecked exception used to escape and terminate the application.
            String problem = value.contains("#genre#")
                    ? "地址中混入了 #genre# 分组标记；请将分组行与频道地址分开填写"
                    : "地址包含非法字符（位置 " + error.getIndex()
                            + "），请检查空格、方括号和 Markdown 链接格式";
            throw new InvalidSourceUrlException(value, problem, error);
        }
    }

    private static String legacyFallback(String value, int statusCode) {
        if (statusCode != 404) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            if (!"tonylee.wasmer.app".equalsIgnoreCase(uri.getHost())
                    || !"/yy.php".equalsIgnoreCase(uri.getPath())) {
                return null;
            }
            String id = queryParameter(uri.getRawQuery(), "id");
            return id == null || id.length() == 0
                    ? null : "https://live.metshop.top/yy/" + id;
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static String queryParameter(String query, String name) {
        if (query == null) {
            return null;
        }
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair : pair.substring(0, separator);
            if (name.equals(key)) {
                String value = separator < 0 ? "" : pair.substring(separator + 1);
                try {
                    return URLDecoder.decode(value, "UTF-8");
                } catch (Exception ignored) {
                    return value;
                }
            }
        }
        return null;
    }

    private static String findMediaUrl(String body) {
        String normalized = body.replace("\\/", "/").trim();
        if ((normalized.startsWith("http://") || normalized.startsWith("https://"))
                && normalized.indexOf('\n') < 0 && normalized.indexOf('\r') < 0) {
            return normalized;
        }
        Matcher namedMatcher = NAMED_MEDIA_URL.matcher(normalized);
        if (namedMatcher.find()) {
            return namedMatcher.group(1);
        }
        Matcher matcher = ABSOLUTE_MEDIA_URL.matcher(normalized);
        while (matcher.find()) {
            String value = matcher.group();
            while (value.endsWith(")") || value.endsWith(",") || value.endsWith(";")) {
                value = value.substring(0, value.length() - 1);
            }
            if (isKnownMediaUrl(value.toLowerCase(Locale.US))) {
                return value;
            }
        }
        return null;
    }

    private static boolean isKnownMediaUrl(String value) {
        return isHlsUrl(value) || isDirectMediaUrl(value)
                || value.startsWith("rtp://") || value.startsWith("udp://")
                || value.startsWith("rtmp://") || value.startsWith("rtsp://");
    }

    private static boolean isHlsUrl(String value) {
        String lower = value.toLowerCase(Locale.US);
        try {
            URI uri = URI.create(lower);
            String path = uri.getPath();
            if (path != null && path.endsWith(".m3u8")) {
                return true;
            }
            String query = uri.getRawQuery();
            return query != null && (query.contains("format=m3u8")
                    || query.contains("type=m3u8"));
        } catch (RuntimeException error) {
            return lower.endsWith(".m3u8") || lower.contains("format=m3u8")
                    || lower.contains("type=m3u8");
        }
    }

    private static boolean isDirectMediaUrl(String value) {
        try {
            String path = URI.create(value).getPath().toLowerCase(Locale.US);
            return path.endsWith(".flv") || path.endsWith(".mp4")
                    || path.endsWith(".mkv") || path.endsWith(".webm")
                    || path.endsWith(".mov") || path.endsWith(".avi")
                    || path.endsWith(".mp3") || path.endsWith(".aac")
                    || path.endsWith(".ts");
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static boolean isDirectMediaType(String type) {
        return type.startsWith("video/") || type.startsWith("audio/")
                || type.contains("flv");
    }

    private static boolean isTextType(String type) {
        return type.length() == 0 || type.startsWith("text/")
                || type.contains("json") || type.contains("javascript")
                || type.contains("xml") || type.contains("octet-stream");
    }

    private static String normalizeContentType(String value) {
        if (value == null) {
            return "";
        }
        int separator = value.indexOf(';');
        return (separator < 0 ? value : value.substring(0, separator))
                .trim().toLowerCase(Locale.US);
    }

    private static boolean isTransientFailureBody(String body) {
        if (body == null || body.length() == 0) {
            return true;
        }
        String lower = body.toLowerCase(Locale.US);
        return body.contains("获取播放地址失败") || body.contains("获取地址失败")
                || body.contains("解析失败") || body.contains("暂时无法")
                || lower.contains("temporarily unavailable")
                || lower.contains("try again");
    }

    private static boolean isRetryableStatus(int statusCode) {
        return statusCode == 408 || statusCode == 425 || statusCode == 429
                || statusCode >= 500;
    }

    private static boolean startsWithPlaylist(String body) {
        return body.startsWith("#EXTM3U") || body.startsWith("\ufeff#EXTM3U");
    }

    private static boolean startsWithFlv(byte[] body) {
        return body != null && body.length >= 3
                && body[0] == 'F' && body[1] == 'L' && body[2] == 'V';
    }

    private static boolean startsWithMp4(byte[] body) {
        if (body.length < 8) return false;
        String box = new String(body, 4, 4, java.nio.charset.Charset.forName("US-ASCII"));
        return "ftyp".equals(box) || "styp".equals(box) || "moov".equals(box)
                || "moof".equals(box) || "mdat".equals(box);
    }

    private static boolean startsWithOtherMedia(byte[] b) {
        if (b.length < 4) return false;
        return b[0]=='I' && b[1]=='D' && b[2]=='3' && b[3] < 5
                || b[0]=='O' && b[1]=='g' && b[2]=='g' && b[3]=='S'
                || b[0]=='R' && b[1]=='I' && b[2]=='F' && b[3]=='F'
                || (b[0]&255)==0x1a && (b[1]&255)==0x45 && (b[2]&255)==0xdf && (b[3]&255)==0xa3
                || (b[0]&255)==255 && (b[1]&0xe0)==0xe0 && (b[1]&255)!=254;
    }

    private static boolean looksLikeText(byte[] bytes) {
        if (bytes.length == 0) return true;
        for (byte value : bytes) {
            int b = value & 255;
            if (b == 0 || b < 32 && b != 9 && b != 10 && b != 13) return false;
        }
        return true;
    }

    private static String decodeText(byte[] bytes) throws IOException {
        boolean utf16 = bytes.length >= 2 && ((bytes[0]&255)==255 && (bytes[1]&255)==254
                || (bytes[0]&255)==254 && (bytes[1]&255)==255);
        String value = new String(bytes, utf16 ? "UTF-16" : "UTF-8").trim();
        return value.startsWith("\ufeff") ? value.substring(1).trim() : value;
    }

    /** Stop as soon as the prefix identifies media or HTML; never wait for EOF
     * on continuous HTTP media or download the whole page just to open it. */
    private static byte[] readPrefix(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(PROBE_BYTES);
        byte[] buffer = new byte[PROBE_BYTES];
        while (output.size() < PROBE_BYTES) {
            checkCancelled();
            int count = input.read(buffer, 0, PROBE_BYTES - output.size());
            if (count < 0) break;
            output.write(buffer, 0, count);
            byte[] bytes = output.toByteArray();
            String prefix = decodeText(bytes);
            if (startsWithPlaylist(prefix) || startsWithFlv(bytes) || startsWithMp4(bytes) || startsWithOtherMedia(bytes)
                    || startsWithTransportStream(bytes) || HTML_START.matcher(prefix).find()) break;
        }
        return output.toByteArray();
    }

    /** Detects raw MPEG-TS returned as application/octet-stream and without a suffix. */
    private static boolean startsWithTransportStream(byte[] body) {
        if (body == null) {
            return false;
        }
        // Standard TS packets are 188 bytes. Some gateways prepend four timestamp bytes
        // and expose 192-byte M2TS packets, so probe both layouts and small leading offsets.
        int[] packetSizes = {188, 192};
        for (int packetSize : packetSizes) {
            for (int offset = 0; offset <= 4; offset++) {
                if (body.length > offset + packetSize * 2
                        && (body[offset] & 0xff) == 0x47
                        && (body[offset + packetSize] & 0xff) == 0x47
                        && (body[offset + packetSize * 2] & 0xff) == 0x47) {
                    return true;
                }
            }
        }
        return false;
    }

    private static byte[] readAtMost(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 8192));
        byte[] buffer = new byte[4096];
        int total = 0;
        try {
            while (total < maximum) {
                checkCancelled();
                int count = input.read(buffer, 0, Math.min(buffer.length, maximum - total));
                if (count < 0) {
                    break;
                }
                output.write(buffer, 0, count);
                total += count;
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static final class HttpStatusException extends IOException {
        final int statusCode;

        HttpStatusException(int statusCode) {
            super("源地址返回 HTTP " + statusCode);
            this.statusCode = statusCode;
        }
    }

    private static final class RetryableSourceException extends IOException {
        RetryableSourceException(String message) {
            super(message);
        }
    }
}
