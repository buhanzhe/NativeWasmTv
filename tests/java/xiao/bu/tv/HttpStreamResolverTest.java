package xiao.bu.tv;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;

/** Host regression test; compile with HttpStreamResolver.java and this file only. */
public final class HttpStreamResolverTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    private static HttpStreamResolver.InvalidSourceUrlException rejected(String url) throws Exception {
        try {
            HttpStreamResolver.resolve(url);
            throw new AssertionError("Invalid source accepted");
        } catch (IOException expected) {
            check(expected.getMessage().contains("地址"));
            check(expected instanceof HttpStreamResolver.InvalidSourceUrlException);
            HttpStreamResolver.InvalidSourceUrlException detail =
                    (HttpStreamResolver.InvalidSourceUrlException) expected;
            check(detail.userMessage().contains(detail.invalidUrl));
            check(detail.userMessage().contains("分组名称,#genre#"));
            return detail;
        }
    }
    public static void main(String[] args) throws Exception {
        String base = "https://nflldr.oss-cn-beijing.aliyuncs.com/ku9js.txt#genre#";
        for (String invalid : new String[] {base + ",", "[" + base + "](" + base + "),",
                "https://example.com/a b", "http://example.com/1.m3u8?mode=1&$8M FHD",
                "https:///missing-host", "file:///test", "", null}) {
            NetworkClient.reset();
            HttpStreamResolver.InvalidSourceUrlException detail = rejected(invalid);
            check(detail.invalidUrl.equals(invalid == null ? "（空地址）" : invalid));
            check(NetworkClient.opened.isEmpty());
        }
        NetworkClient.reset();
        NetworkClient.reply(302, "/bad#genre#", "");
        check(rejected("https://example.com/start").invalidUrl.equals("/bad#genre#"));
        check(NetworkClient.opened.size() == 1 && NetworkClient.disconnected == 1);

        NetworkClient.reset();
        NetworkClient.reply(302, "file:///bad", "");
        rejected("https://example.com/start");
        check(NetworkClient.opened.size() == 1 && NetworkClient.disconnected == 1);

        NetworkClient.reset();
        NetworkClient.reply(200, null, "https://example.com/live.m3u8#genre#,");
        rejected("https://example.com/resolve");
        check(NetworkClient.opened.size() == 1 && NetworkClient.disconnected == 1);

        NetworkClient.reset();
        String signed = "https://example.com/live.m3u8?token=a%2Bb%2F%3D&x=1,2&token2=$abc#ok";
        NetworkClient.reply(302, "/live.m3u8?token=a%2Bb%2F%3D&x=1,2&token2=$abc#ok", "");
        NetworkClient.reply(200, null, "#EXTM3U\n#EXTINF:1,\n1.ts");
        HttpStreamResolver.Result result = HttpStreamResolver.resolve("https://example.com/start");
        check(signed.equals(result.url) && !result.directMedia);
        check(NetworkClient.disconnected == 2);

        NetworkClient.reset();
        NetworkClient.reply(200, null, "{\"url\":\"https://example.com/movie.mp4?token=a%2Bb\"}");
        result = HttpStreamResolver.resolve("https://example.com/resolve");
        check(result.directMedia && result.url.equals("https://example.com/movie.mp4?token=a%2Bb"));
        System.out.println("PASS malformed input/redirect/body, connection cleanup, signed URLs and media extraction");
    }
}

/** Host-only transport double; production NetworkClient is deliberately not compiled here. */
final class NetworkClient {
    static final ArrayDeque<Object[]> replies = new ArrayDeque<>();
    static final ArrayList<String> opened = new ArrayList<>();
    static int disconnected;
    static void reset() { replies.clear(); opened.clear(); disconnected = 0; }
    static void reply(int status, String location, String body) { replies.add(new Object[] {status, location, body}); }
    static HttpURLConnection open(URL url) {
        opened.add(url.toString());
        final Object[] reply = replies.remove();
        return new HttpURLConnection(url) {
            public void connect() {}
            public boolean usingProxy() { return false; }
            public void disconnect() { disconnected++; }
            public int getResponseCode() { return (Integer) reply[0]; }
            public String getHeaderField(String key) { return "Location".equals(key) ? (String) reply[1] : null; }
            public String getContentType() { return "text/plain"; }
            public InputStream getInputStream() throws IOException {
                return new ByteArrayInputStream(((String) reply[2]).getBytes("UTF-8"));
            }
        };
    }
}
