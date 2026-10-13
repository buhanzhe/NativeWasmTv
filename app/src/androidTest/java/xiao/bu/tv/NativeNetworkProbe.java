package xiao.bu.tv;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import okhttp3.MediaType;
import org.json.JSONArray;
import org.json.JSONObject;

/** Test-only public endpoint diagnostics through the application's unchanged HTTP stack. */
final class NativeNetworkProbe {
    private static final String TAG = "NtvNativeNetwork";
    private static final String HEBEI = "https://www.hebtv.com/19/19js/st/xdszb/index.shtml";
    private static final String API = "https://api.cmc.hebrts.cn/cmsback/api/com/article/getArticleList?catalogId=32557&siteId=1";
    private static final String HAINAN = "https://www.hnntv.cn/live.html?playType=livePlay&channelId=5&referPage=home";
    private static final String UA = "Mozilla/5.0 (Linux; Android 4.4.2) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/30.0.0.0 Mobile Safari/537.36";

    static void run(Instrumentation owner) {
        Bundle result = new Bundle();
        try {
            NetworkClient.initialize(owner.getTargetContext());
            JSONObject report = new JSONObject();
            report.put("sdk", android.os.Build.VERSION.SDK_INT);
            report.put("dnsMode", NetworkClient.getDnsMode());
            report.put("dnsClass", NetworkClient.sharedClient().dns().getClass().getName());
            report.put("note", "Existing application TLS/DNS configuration, no proxy or trust changes");
            JSONArray rows = new JSONArray();
            rows.put(request(HEBEI, false));
            rows.put(request(API, true));
            rows.put(request(HAINAN, false));
            report.put("requests", rows);
            String output = report.toString();
            Log.i(TAG, output);
            result.putString("stream", output + "\n");
            owner.finish(-1, result); // Completion is not a claim that all endpoints succeeded.
        } catch (Throwable error) {
            result.putString("stream", Log.getStackTraceString(error));
            owner.finish(0, result);
        }
    }

    private static JSONObject request(String original, boolean emptyPost) throws Exception {
        JSONObject row = new JSONObject();
        row.put("url", original).put("method", emptyPost ? "POST" : "GET");
        long begin = SystemClock.elapsedRealtime();
        HttpURLConnection connection = null;
        try {
            // Disable automatic redirects so the original server result is visible.
            connection = NetworkClient.openBounded(new URL(original), 15000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(emptyPost ? "POST" : "GET");
            connection.setRequestProperty("User-Agent", UA);
            if (emptyPost) {
                connection.setRequestProperty("Referer", HEBEI);
                connection.setRequestProperty("Origin", "https://www.hebtv.com");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(0);
                connection.getOutputStream().close();
            }
            int status = connection.getResponseCode();
            row.put("status", status);
            row.put("contentType", connection.getContentType());
            row.put("location", connection.getHeaderField("Location"));
            row.put("headerMs", SystemClock.elapsedRealtime() - begin);
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream != null) {
                try {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] block = new byte[512];
                    while (bytes.size() < 2048) {
                        int n = stream.read(block, 0, Math.min(block.length, 2048 - bytes.size()));
                        if (n < 0) break;
                        bytes.write(block, 0, n);
                    }
                    Charset charset = Charset.forName("UTF-8");
                    MediaType mime = MediaType.parse(connection.getContentType() == null ? "" : connection.getContentType());
                    if (mime != null) charset = mime.charset(charset);
                    row.put("sampleBytes", bytes.size());
                    row.put("sample", new String(bytes.toByteArray(), charset));
                } finally { stream.close(); }
            }
        } catch (Throwable error) {
            row.put("error", error.getClass().getName());
            row.put("message", String.valueOf(error.getMessage()));
            Throwable cause = error.getCause();
            if (cause != null) row.put("cause", cause.getClass().getName() + ": " + cause.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
            row.put("elapsedMs", SystemClock.elapsedRealtime() - begin);
        }
        Log.i(TAG, row.toString());
        return row;
    }
}
