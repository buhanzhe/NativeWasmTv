package xiao.bu.tv;

import android.app.Instrumentation;
import android.os.Bundle;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/** End-to-end regression for authenticated carrier PLTV URLs and proxy headers. */
public final class CarrierIptvInstrumentation extends Instrumentation {
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private final StringBuilder report = new StringBuilder();

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        int code = -1;
        HlsProxyServer proxy = null;
        ServerSocket fixture = null;
        try {
            fixture = new ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"));
            final ServerSocket server = fixture;
            final List<String> requests = new ArrayList<String>();
            final String query = "fmt=ts2hls,244,01.m3u8&accountinfo=A+B/9,END&tenantId=8601";
            Thread upstream = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        for (int index = 0; index < 2; index++) {
                            Socket socket = server.accept();
                            BufferedReader reader = new BufferedReader(new InputStreamReader(
                                    socket.getInputStream(), Charset.forName("ISO-8859-1")));
                            StringBuilder request = new StringBuilder();
                            String line;
                            while ((line = reader.readLine()) != null && line.length() > 0) {
                                request.append(line).append('\n');
                            }
                            requests.add(request.toString());
                            byte[] body = index == 0
                                    ? ("#EXTM3U\n#EXT-X-TARGETDURATION:4\n"
                                            + "#EXT-X-MEDIA-SEQUENCE:1\n#EXTINF:4,\n"
                                            + "01.ts?" + query + "\n").getBytes(UTF_8)
                                    : new byte[] { 0x47, 0x00, 0x00, 0x10 };
                            String type = index == 0 ? "application/vnd.apple.mpegurl" : "video/MP2T";
                            OutputStream output = socket.getOutputStream();
                            output.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type
                                    + "\r\nContent-Length: " + body.length
                                    + "\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));
                            output.write(body);
                            output.flush();
                            socket.close();
                        }
                    } catch (Exception error) {
                        report.append("FIXTURE ").append(Log.getStackTraceString(error));
                    }
                }
            }, "carrier-iptv-fixture");
            upstream.start();

            String source = "http://127.0.0.1:" + fixture.getLocalPort()
                    + "/PLTV/88888973/224/test/01.m3u8?" + query;
            check(CarrierNetworkRoute.isCarrierIptvUrl(source), "PLTV URL not detected");
            proxy = new HlsProxyServer(getTargetContext(), true, false, true,
                    2, true, HlsProxyServer.VARIANT_QUALITY_HIGH, 2, 2);
            proxy.start();
            String manifest = text(proxy.proxyUrl(source));
            String segment = null;
            for (String line : manifest.split("\\r?\\n")) {
                if (line.startsWith("http://127.0.0.1:")) segment = line;
            }
            check(segment != null, "Segment URL was not rewritten");
            byte[] segmentBody = bytes(segment);
            check(segmentBody.length == 4 && segmentBody[0] == 0x47, "Segment proxy failed");
            upstream.join(5000L);
            check(requests.size() == 2, "Expected two upstream requests: " + requests.size());
            String first = requests.get(0);
            String second = requests.get(1);
            check(first.contains("accountinfo=A+B/9,END&tenantId=8601"),
                    "Manifest query changed: " + first);
            check(second.contains("accountinfo=A+B/9,END&tenantId=8601"),
                    "Segment query changed: " + second);
            check(first.toLowerCase().contains("user-agent: okhttp/3.10.0"),
                    "Carrier User-Agent missing: " + first);
            check(first.toLowerCase().contains("connection: keep-alive"),
                    "Carrier keep-alive missing: " + first);
            report.append("PASS carrier route fallback, exact authenticated query, headers and segment proxy\n");
            code = 0;
        } catch (Throwable error) {
            report.append("FAIL ").append(Log.getStackTraceString(error));
        } finally {
            if (proxy != null) proxy.close();
            if (fixture != null) try { fixture.close(); } catch (Exception ignored) {}
        }
        Bundle result = new Bundle();
        result.putString("stream", report.toString());
        finish(code, result);
    }

    private static String text(String url) throws Exception {
        return new String(bytes(url), UTF_8);
    }

    private static byte[] bytes(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        InputStream input = connection.getInputStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        input.close();
        return output.toByteArray();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
