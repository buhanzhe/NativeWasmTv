package xiao.bu.tv;

import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import org.json.JSONObject;
import java.io.IOException;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** One host API for QuickJS and WebView, including CJS site scripts. */
abstract class Ku9Host implements NativeQuickJs.Host {
    private static final String TAG = "Ku9Host";
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private volatile boolean terminal;
    final void abort() { terminal = true; }
    protected abstract boolean isRequestCancelled();
    protected abstract void onComplete(String json);
    protected abstract void onFailure(String reason);
    @JavascriptInterface public abstract String getCache(String key);
    @JavascriptInterface public abstract void setCache(String key, String value, double ttlMs);
    @Override public final boolean isCancelled() { return terminal || isRequestCancelled(); }
    @JavascriptInterface public final synchronized void complete(String json) {
        if (isCancelled()) return;
        terminal = true;
        onComplete(json);
    }
    @JavascriptInterface public final synchronized void fail(String reason) {
        if (isCancelled()) return;
        terminal = true;
        onFailure(reason);
    }
    @Override public String invoke(int operation, String[] args) throws Exception {
        if (isCancelled()) throw new IOException("Ku9 request cancelled");
        switch (operation) {
            case 0: return get(args[0], args[1]);
            case 1: return post(args[0], args[1], args[2]);
            case 2: return request(args[0], args[1], args[2], args[3], Boolean.parseBoolean(args[4]));
            case 3: return md5(args[0]);
            case 4: log(args[0]); return null;
            case 5: complete(args[0]); return null;
            case 6: fail(args[0]); return null;
            case 7: return getCache(args[0]);
            case 8: setCache(args[0], args[1], Double.parseDouble(args[2])); return null;
            case 9: return digest(args[0], args[1]);
            case 10: return encodeBase64(args[0]);
            case 11: return decodeBase64(args[0]);
            case 12: return String.valueOf(toTimestamp(args[0], args[1], args[2]));
            case 13: return toDate(Double.parseDouble(args[0]), args[1], args[2]);
            case 14: return formatDateTime(args[0], args[1], args[2],
                    Double.parseDouble(args[3]), args[4], args[5]);
            case 15: return parseUri(args[0]);
            default: throw new IOException("Unknown Ku9 host operation");
        }
    }

    @JavascriptInterface
    public String get(String url, String headersJson) {
        url = url == null ? "" : url.trim();
        if (isCancelled() || !isOnline(url)) return "";
        try {
            return Ku9HttpClient.getText(url, Ku9HttpClient.parseHeaders(headersJson),
                    MAX_RESPONSE_BYTES);
        } catch (Exception error) {
            Log.w(TAG, "Ku9 GET failed: " + url, error);
            return "";
        }
    }

    @JavascriptInterface
    public String post(String url, String body, String headersJson) {
        url = url == null ? "" : url.trim();
        if (isCancelled() || !isOnline(url)) return "";
        return Ku9HttpClient.postText(url, body, headersJson, MAX_RESPONSE_BYTES);
    }

    @JavascriptInterface
    public String request(String url, String method, String headersJson, String body,
            boolean followRedirects) {
        url = url == null ? "" : url.trim();
        if (isCancelled() || !isOnline(url)) return "{\"code\":0,\"error\":\"online URL required or request cancelled\"}";
        String result = Ku9HttpClient.requestJson(url, method, headersJson, body,
                followRedirects, MAX_RESPONSE_BYTES);
        try {
            JSONObject response = new JSONObject(result);
            if (response.optInt("code") == 0) Log.w(TAG, "HTTP transport failure: " + response.optString("error"));
        } catch (Exception ignored) { }
        return result;
    }

    private static boolean isOnline(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    // Native equivalent of the browser anchor parser used by ku9.Uri.
    @JavascriptInterface
    public String parseUri(String value) throws Exception {
        java.net.URI uri = java.net.URI.create(value);
        String path = uri.getRawPath();
        if (path == null || path.length() == 0) path = "/";
        String query = uri.getRawQuery(), fragment = uri.getRawFragment();
        JSONObject params = new JSONObject();
        if (query != null && query.length() > 0) {
            for (String part : query.split("&")) {
                int equals = part.indexOf('=');
                String key = equals < 0 ? part : part.substring(0, equals);
                String val = equals < 0 ? "" : part.substring(equals + 1);
                if (key.length() > 0) params.put(java.net.URLDecoder.decode(key, "UTF-8"),
                        java.net.URLDecoder.decode(val, "UTF-8"));
            }
        }
        return new JSONObject().put("Scheme", uri.getScheme() == null ? "" : uri.getScheme())
                .put("Host", uri.getHost() == null ? "" : uri.getHost())
                .put("Port", uri.getPort()).put("FullPath", path)
                .put("Path", path.substring(0, path.lastIndexOf('/') + 1))
                .put("Query", query == null ? "" : "?" + query)
                .put("Fragment", fragment == null ? "" : "#" + fragment)
                .put("Params", params).toString();
    }

    @JavascriptInterface
    public String md5(String value) {
        return digest("MD5", value);
    }

    @JavascriptInterface
    public String digest(String algorithm, String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] bytes = digest.digest(value.getBytes("UTF-8"));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                result.append(String.format(Locale.US, "%02x", item & 0xff));
            }
            return result.toString();
        } catch (Exception error) {
            return "";
        }
    }

    @JavascriptInterface
    public String encodeBase64(String value) {
        try {
            return Base64.encodeToString(value.getBytes("UTF-8"), Base64.NO_WRAP);
        } catch (Exception error) {
            return "";
        }
    }

    @JavascriptInterface
    public String decodeBase64(String value) {
        try {
            return new String(Base64.decode(value, Base64.DEFAULT), "UTF-8");
        } catch (Exception error) {
            return "";
        }
    }

    @JavascriptInterface
    public double toTimestamp(String value, String format, String timezone) {
        try {
            return dateFormat(format, timezone).parse(value).getTime();
        } catch (Exception error) {
            return 0;
        }
    }

    @JavascriptInterface
    public String toDate(double timestamp, String format, String timezone) {
        try {
            return dateFormat(format, timezone).format(new Date((long) timestamp));
        } catch (Exception error) {
            return "";
        }
    }

    @JavascriptInterface
    public String formatDateTime(String value, String inputFormat, String outputFormat,
            double daysOffset, String inputTimezone, String outputTimezone) {
        try {
            Date date = dateFormat(inputFormat, inputTimezone).parse(value);
            Calendar calendar = Calendar.getInstance(timeZone(inputTimezone));
            calendar.setTime(date);
            calendar.add(Calendar.DAY_OF_MONTH, (int) daysOffset);
            return dateFormat(outputFormat, outputTimezone).format(calendar.getTime());
        } catch (Exception error) {
            return "";
        }
    }

    @JavascriptInterface
    public void log(String value) {
        Log.d(TAG, value);
    }


    private static SimpleDateFormat dateFormat(String pattern, String timezone) {
        SimpleDateFormat format = new SimpleDateFormat(
                TextUtils.isEmpty(pattern) ? "yyyy-MM-dd HH:mm:ss" : pattern, Locale.US);
        format.setLenient(false);
        format.setTimeZone(timeZone(timezone));
        return format;
    }

    private static TimeZone timeZone(String timezone) {
        return TextUtils.isEmpty(timezone) ? TimeZone.getDefault()
                : TimeZone.getTimeZone(timezone);
    }

}
