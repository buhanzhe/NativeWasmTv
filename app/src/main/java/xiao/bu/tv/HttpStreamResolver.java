package xiao.bu.tv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
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

        Result(String url, boolean directMedia) {
            this.url = url;
            this.directMedia = directMedia;
        }
    }

    private HttpStreamResolver() {
    }

    static boolean shouldResolve(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false;
        }
        return !isKnownMediaUrl(lower);
    }

    static Result resolve(String value) throws IOException {
        IOException lastError = null;
        for (int attempt = 1; attempt <= MAX_TRANSIENT_ATTEMPTS; attempt++) {
            try {
                return resolveInternal(value);
            } catch (HttpStatusException error) {
                String fallback = legacyFallback(value, error.statusCode);
                if (fallback != null) {
                    return resolveInternal(fallback);
                }
                if (!isRetryableStatus(error.statusCode)) {
                    throw error;
                }
                lastError = error;
            } catch (RetryableSourceException error) {
                lastError = error;
            }
            if (attempt < MAX_TRANSIENT_ATTEMPTS) {
                try {
                    Thread.sleep((long) RETRY_DELAY_MS * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("源地址解析已取消", interrupted);
                }
            }
        }
        throw lastError == null ? new IOException("源地址解析失败") : lastError;
    }

    private static Result resolveInternal(String value) throws IOException {
        String current = value;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            HttpURLConnection connection = (HttpURLConnection) URI.create(current)
                    .toURL().openConnection();
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(7000);
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
                    current = URI.create(current).resolve(location).toString();
                    continue;
                }
                if (status < 200 || status >= 300) {
                    throw new HttpStatusException(status);
                }

                String contentType = normalizeContentType(connection.getContentType());
                if (isHlsUrl(current) || contentType.contains("mpegurl")) {
                    return new Result(current, false);
                }
                if (isDirectMediaUrl(current) || isDirectMediaType(contentType)) {
                    return new Result(current, true);
                }
                if (!isTextType(contentType)) {
                    throw new IOException("源地址没有返回可识别的视频内容");
                }

                byte[] probe = readAtMost(connection.getInputStream(), MAX_TEXT_BYTES);
                if (startsWithFlv(probe)) {
                    return new Result(current, true);
                }
                if (startsWithTransportStream(probe)) {
                    return new Result(current, true);
                }
                String body = new String(probe, "UTF-8").trim();
                if (startsWithPlaylist(body)) {
                    return new Result(current, false);
                }
                String mediaUrl = findMediaUrl(body);
                if (mediaUrl != null) {
                    if (isHlsUrl(mediaUrl)) {
                        return new Result(mediaUrl, false);
                    }
                    if (isDirectMediaUrl(mediaUrl)) {
                        return new Result(mediaUrl, true);
                    }
                    current = mediaUrl;
                    continue;
                }
                if (isTransientFailureBody(body)) {
                    throw new RetryableSourceException(body.length() == 0
                            ? "源地址返回空内容" : body);
                }
                throw new IOException("源地址返回网页，未找到视频地址");
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("源地址重定向次数过多");
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
