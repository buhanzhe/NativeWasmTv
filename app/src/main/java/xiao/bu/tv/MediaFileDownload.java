package xiao.bu.tv;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Locale;

/** Progressive file download. The management server copies this stream with bounded memory. */
final class MediaFileDownload implements Closeable {
    final HttpURLConnection connection;
    final InputStream body;
    final String name;
    final String contentType;
    final long length;
    final int status;
    private long received;
    private boolean eof, closed;

    private MediaFileDownload(HttpURLConnection connection, String title, String url) throws IOException {
        this.connection = connection;
        status = connection.getResponseCode();
        String type = connection.getContentType();
        contentType = type == null ? "application/octet-stream" : type.replace("\r", "").replace("\n", "");
        String lower = contentType.toLowerCase(Locale.US);
        if (lower.contains("mpegurl") || lower.startsWith("text/") || lower.contains("json")) {
            throw new IOException("当前资源不是可直接下载的媒体文件");
        }
        if (status != 200 && status != 206) throw new IOException("媒体下载 HTTP " + status);
        if (status == 206 && connection.getHeaderField("Content-Range") == null)
            throw new IOException("媒体下载缺少分段范围");
        long size = -1;
        try { size = Long.parseLong(connection.getHeaderField("Content-Length")); }
        catch (RuntimeException ignored) { }
        length = size;
        String extension = extension(url);
        if (extension.length() == 0) {
            extension = lower.contains("audio/mpeg") ? ".mp3"
                    : lower.contains("flac") ? ".flac"
                    : lower.contains("audio/mp4") ? ".m4a"
                    : lower.contains("video/mp4") ? ".mp4" : ".bin";
        }
        String base = title == null ? "" : title.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_").trim();
        if (base.length() == 0) base = "nTv-media";
        if (base.length() > 100) base = base.substring(0, 100);
        name = base.toLowerCase(Locale.US).endsWith(extension) ? base : base + extension;
        body = new FilterInputStream(connection.getInputStream()) {
            @Override public int read() throws IOException {
                int value = in.read();
                if (value < 0) eof = true; else received++;
                return value;
            }
            @Override public int read(byte[] bytes, int offset, int count) throws IOException {
                int size = in.read(bytes, offset, count);
                if (size < 0) eof = true; else received += size;
                return size;
            }
        };
    }

    static boolean isFile(String url, long durationMs, boolean directMedia) {
        if (durationMs <= 0 || url == null) return false; // Includes endless MP3 radio streams.
        String lower = url.toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;
        if (lower.contains(".m3u8") || lower.contains("/api/recording/")
                || lower.contains("format=m3u8") || lower.contains("type=m3u8")) return false;
        return extension(url).length() > 0 || directMedia;
    }

    private static String extension(String url) {
        try {
            String path = URI.create(url).getPath().toLowerCase(Locale.US);
            for (String suffix : new String[]{".mp4", ".mp3", ".m4a", ".flac", ".aac", ".wav",
                    ".ogg", ".oga", ".opus", ".webm", ".mkv", ".mov", ".avi", ".flv"}) {
                if (path.endsWith(suffix)) return suffix;
            }
        } catch (RuntimeException ignored) { }
        return "";
    }

    static MediaFileDownload open(String url, String headers, String title,
            String range, String ifRange) throws IOException {
        if (range != null && !range.matches("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)"))
            throw new IOException("下载范围无效");
        URL initial = new URL(url), current = initial;
        for (int redirect = 0; redirect <= 6; redirect++) {
            if (!"http".equalsIgnoreCase(current.getProtocol())
                    && !"https".equalsIgnoreCase(current.getProtocol())) throw new IOException("媒体协议不支持下载");
            HttpURLConnection connection = NetworkClient.open(current);
            boolean handedOff = false;
            try {
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "nTv/" + BuildConfig.VERSION_NAME);
                if (headers != null) for (String line : headers.split("\r?\n")) {
                    int colon = line.indexOf(':');
                    if (colon <= 0) continue;
                    String key = line.substring(0, colon).trim(), value = line.substring(colon + 1).trim();
                    boolean credentials = "Cookie".equalsIgnoreCase(key) || "Authorization".equalsIgnoreCase(key);
                    if ((credentials && sameOrigin(initial, current)) || "Referer".equalsIgnoreCase(key)
                            || "User-Agent".equalsIgnoreCase(key) || "Origin".equalsIgnoreCase(key)) {
                        connection.setRequestProperty(key, value);
                    }
                }
                connection.setRequestProperty("Accept-Encoding", "identity");
                if (range != null) connection.setRequestProperty("Range", range);
                if (ifRange != null && ifRange.length() <= 1024 && ifRange.indexOf('\r') < 0 && ifRange.indexOf('\n') < 0)
                    connection.setRequestProperty("If-Range", ifRange);
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("媒体下载重定向无效");
                    current = new URL(current, location);
                    continue;
                }
                MediaFileDownload result = new MediaFileDownload(connection, title, url);
                handedOff = true;
                return result;
            } finally { if (!handedOff) connection.disconnect(); }
        }
        throw new IOException("媒体下载重定向过多");
    }

    private static boolean sameOrigin(URL a, URL b) {
        return a.getProtocol().equalsIgnoreCase(b.getProtocol()) && a.getHost().equalsIgnoreCase(b.getHost())
                && (a.getPort() < 0 ? a.getDefaultPort() : a.getPort())
                == (b.getPort() < 0 ? b.getDefaultPort() : b.getPort());
    }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        // Completed downloads can return the connection to the shared pool.
        // On cancellation disconnect first, so close() cannot drain a large body.
        if (!(length >= 0 ? received == length : eof)) connection.disconnect();
        try { body.close(); } catch (IOException error) {
            connection.disconnect(); throw error;
        }
    }
}
