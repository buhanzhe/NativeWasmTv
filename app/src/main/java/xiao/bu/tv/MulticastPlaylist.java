package xiao.bu.tv;

import java.io.IOException;
import java.net.URI;

/** A direct RTP/UDP/RTSP source is one channel, never an HTTP playlist download. */
final class MulticastPlaylist {
    static boolean matches(String location) {
        return location != null && (location.regionMatches(true, 0, "rtp://", 0, 6)
                || location.regionMatches(true, 0, "udp://", 0, 6)
                || location.regionMatches(true, 0, "rtsp://", 0, 7));
    }

    static boolean valid(String location) {
        if (!matches(location) || location.matches(".*[\\s<>\"\\\\^`{}|].*")) return false;
        try {
            URI uri = new URI(location);
            String authority = uri.getRawAuthority();
            if (authority == null) return false;
            authority = authority.substring(authority.lastIndexOf('@') + 1);
            URI endpoint = new URI("http://" + authority);
            int port = endpoint.getPort();
            return endpoint.getHost() != null && (port == -1 || port > 0 && port <= 65535)
                    && !authority.endsWith(":");
        } catch (Exception invalid) { return false; }
    }

    static byte[] bytes(String name, String location) throws IOException {
        if (!valid(location)) throw new IOException("RTP/UDP/RTSP 频道地址或端口无效");
        String title = name == null || name.trim().isEmpty() ? "直播频道" : name;
        return ("#EXTM3U\n#EXTINF:-1," + title.replace('\r', ' ').replace('\n', ' ')
                + "\n" + location + "\n").getBytes("UTF-8");
    }
}
