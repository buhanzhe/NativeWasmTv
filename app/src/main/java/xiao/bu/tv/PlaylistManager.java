package xiao.bu.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PlaylistManager {
    static final String RECOMMENDED_URL = "https://gh-proxy.com/raw.githubusercontent.com/"
            + "vbskycn/iptv/refs/heads/master/tv/iptv4.txt";
    private static final String PREFS = "management";
    private static final String PLAYLIST_URL = "playlist_url";
    private static final String CACHE_FILE = "online-playlist.txt";
    private static final int MAX_DOWNLOAD_BYTES = 2 * 1024 * 1024;
    private static final int MAX_CHANNELS = 2000;

    private final Context context;
    private final SharedPreferences preferences;

    PlaylistManager(Context context) {
        this.context = context.getApplicationContext();
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    String getPlaylistUrl() {
        return preferences.getString(PLAYLIST_URL, "");
    }

    ChannelCatalog.Group[] loadCached() {
        if (getPlaylistUrl().length() == 0) {
            return new ChannelCatalog.Group[0];
        }
        try {
            InputStream input = context.openFileInput(CACHE_FILE);
            try {
                return parse(readAll(input));
            } finally {
                input.close();
            }
        } catch (IOException ignored) {
            return new ChannelCatalog.Group[0];
        }
    }

    ChannelCatalog.Group[] downloadAndSave(String sourceUrl) throws IOException {
        String normalized = sourceUrl == null ? "" : sourceUrl.trim();
        if (normalized.length() == 0) {
            preferences.edit().remove(PLAYLIST_URL).apply();
            try {
                context.deleteFile(CACHE_FILE);
            } catch (RuntimeException ignored) {
            }
            return new ChannelCatalog.Group[0];
        }
        URL url = new URL(normalized);
        String protocol = url.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            throw new IOException("频道源地址仅支持 HTTP 或 HTTPS");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "nTv/1.2");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("下载频道源失败：HTTP " + status);
            }
            int length = connection.getContentLength();
            if (length > MAX_DOWNLOAD_BYTES) {
                throw new IOException("频道源文件超过 2 MB");
            }
            byte[] bytes = readAll(connection.getInputStream());
            ChannelCatalog.Group[] groups = parse(bytes);
            java.io.FileOutputStream output = context.openFileOutput(
                    CACHE_FILE, Context.MODE_PRIVATE);
            try {
                output.write(bytes);
            } finally {
                output.close();
            }
            preferences.edit().putString(PLAYLIST_URL, normalized).apply();
            return groups;
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > MAX_DOWNLOAD_BYTES) {
                throw new IOException("频道源文件超过 2 MB");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static ChannelCatalog.Group[] parse(byte[] bytes) throws IOException {
        String text = decode(bytes);
        Map<String, List<Channel>> groups = new LinkedHashMap<String, List<Channel>>();
        String currentGroup = "在线频道";
        String pendingName = null;
        String pendingGroup = null;
        int count = 0;
        String[] lines = text.replace("\r", "").split("\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.length() == 0) {
                continue;
            }
            if (line.startsWith("#EXTINF:")) {
                pendingName = attribute(line, "tvg-name");
                pendingGroup = attribute(line, "group-title");
                int comma = line.lastIndexOf(',');
                if (comma >= 0 && comma + 1 < line.length()) {
                    pendingName = line.substring(comma + 1).trim();
                }
                continue;
            }
            if (line.startsWith("#")) {
                continue;
            }
            if (pendingName != null && isStreamUrl(line)) {
                String groupName = emptyToDefault(pendingGroup, currentGroup);
                add(groups, groupName, pendingName, line, count++);
                pendingName = null;
                pendingGroup = null;
            } else {
                int comma = line.indexOf(',');
                if (comma <= 0 || comma + 1 >= line.length()) {
                    continue;
                }
                String name = line.substring(0, comma).trim();
                String value = line.substring(comma + 1).trim();
                if ("#genre#".equalsIgnoreCase(value)) {
                    currentGroup = name.length() == 0 ? "在线频道" : name;
                } else if (isStreamUrl(value)) {
                    add(groups, currentGroup, name, value, count++);
                }
            }
            if (count >= MAX_CHANNELS) {
                break;
            }
        }
        if (count == 0) {
            throw new IOException("频道源中没有找到可播放的 HTTP 或 WebView 地址");
        }
        ChannelCatalog.Group[] result = new ChannelCatalog.Group[groups.size()];
        int index = 0;
        for (Map.Entry<String, List<Channel>> entry : groups.entrySet()) {
            List<Channel> channels = entry.getValue();
            result[index++] = new ChannelCatalog.Group("在线 · " + entry.getKey(),
                    ChannelCatalog.SOURCE_CUSTOM,
                    channels.toArray(new Channel[channels.size()]));
        }
        return result;
    }

    private static String decode(byte[] bytes) {
        int offset = bytes.length >= 3 && (bytes[0] & 0xff) == 0xef
                && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf ? 3 : 0;
        try {
            return new String(bytes, offset, bytes.length - offset, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            return new String(bytes, offset, bytes.length - offset);
        }
    }

    private static void add(Map<String, List<Channel>> groups, String groupName,
            String name, String url, int index) {
        String safeGroup = groupName == null || groupName.trim().length() == 0
                ? "在线频道" : groupName.trim();
        List<Channel> channels = groups.get(safeGroup);
        if (channels == null) {
            channels = new ArrayList<Channel>();
            groups.put(safeGroup, channels);
        }
        String safeName = name == null || name.trim().length() == 0
                ? "频道 " + (index + 1) : name.trim();
        for (int position = 0; position < channels.size(); position++) {
            Channel existing = channels.get(position);
            if (existing.name.equals(safeName)) {
                channels.set(position, existing.withAdditionalUrl(url));
                return;
            }
        }
        channels.add(new Channel(String.valueOf(channels.size() + 1), safeName,
                "custom_" + index, url, null, null));
    }

    private static boolean isStreamUrl(String text) {
        return text.startsWith("http://") || text.startsWith("https://")
                || text.startsWith("webview://http://")
                || text.startsWith("webview://https://");
    }

    private static String emptyToDefault(String value, String fallback) {
        return value == null || value.trim().length() == 0 ? fallback : value.trim();
    }

    private static String attribute(String line, String name) {
        String marker = name + "=\"";
        int start = line.indexOf(marker);
        if (start < 0) {
            return null;
        }
        start += marker.length();
        int end = line.indexOf('"', start);
        return end < 0 ? null : line.substring(start, end);
    }
}
