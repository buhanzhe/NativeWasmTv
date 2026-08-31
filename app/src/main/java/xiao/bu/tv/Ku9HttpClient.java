package xiao.bu.tv;

import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Minimal HTTP adapter exposed to Ku9 scripts. */
final class Ku9HttpClient {
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 5.0; TV) AppleWebKit/537.36 "
                    + "Chrome/120.0 Mobile Safari/537.36";

    private Ku9HttpClient() {
    }

    static String getText(String url, JSONObject headers, int maxBytes) throws IOException {
        HttpURLConnection connection = open(url, "GET", headers, true);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("酷9脚本请求失败: HTTP " + code);
            }
            return readUtf8(connection.getInputStream(), maxBytes);
        } finally {
            connection.disconnect();
        }
    }

    static String postText(String url, String body, String headersJson, int maxBytes) {
        try {
            return new JSONObject(requestJson(url, "POST", headersJson, body, true, maxBytes))
                    .optString("body", "");
        } catch (JSONException error) {
            return "";
        }
    }

    static String requestJson(String url, String method, String headersJson, String body,
            boolean followRedirects, int maxBytes) {
        JSONObject result = new JSONObject();
        HttpURLConnection connection = null;
        try {
            String requestMethod = TextUtils.isEmpty(method) ? "GET"
                    : method.toUpperCase(Locale.US);
            connection = open(url, requestMethod, parseHeaders(headersJson), followRedirects);
            if (!"GET".equals(requestMethod) && body != null) {
                connection.setDoOutput(true);
                connection.getOutputStream().write(body.getBytes("UTF-8"));
                connection.getOutputStream().close();
            }
            int code = connection.getResponseCode();
            InputStream input = code >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            result.put("code", code);
            result.put("body", input == null ? "" : readUtf8(input, maxBytes));
            result.put("url", connection.getURL().toString());
            JSONObject headers = new JSONObject();
            for (Map.Entry<String, List<String>> entry
                    : connection.getHeaderFields().entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null
                        && !entry.getValue().isEmpty()) {
                    headers.put(entry.getKey(), entry.getValue().get(0));
                }
            }
            result.put("headers", headers);
        } catch (Exception error) {
            try {
                result.put("code", 0);
                result.put("body", "");
                result.put("error", message(error));
            } catch (JSONException ignored) {
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return result.toString();
    }

    static JSONObject parseHeaders(String json) {
        if (!TextUtils.isEmpty(json)) {
            try {
                return new JSONObject(json);
            } catch (JSONException ignored) {
            }
        }
        return new JSONObject();
    }

    static String readUtf8(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > maxBytes) {
                throw new IOException("酷9脚本响应超过 " + maxBytes + " 字节限制");
            }
            output.write(buffer, 0, count);
        }
        return output.toString("UTF-8");
    }

    private static HttpURLConnection open(String url, String method, JSONObject headers,
            boolean followRedirects) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL()
                .openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(followRedirects);
        connection.setRequestMethod(method == null ? "GET" : method.toUpperCase(Locale.US));
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "*/*");
        Iterator<String> names = (headers == null ? new JSONObject() : headers).keys();
        while (names.hasNext()) {
            String name = names.next();
            if (!TextUtils.isEmpty(name)) {
                connection.setRequestProperty(name, headers.optString(name, ""));
            }
        }
        return connection;
    }

    private static String message(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return TextUtils.isEmpty(message) ? "未知错误" : message;
    }
}
