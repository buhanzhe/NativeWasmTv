package com.bu.cc.tv;

import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

final class LiveUrlResolver {
    private static final String TAG = "LiveUrlResolver";
    private static final String PREFERENCES = "live_url_resolver";
    private static final String UID = "uid";
    private static final String VDN_API = "https://vdnx.live.cntv.cn/api/v3/vdn/live";
    private static final String VDN_BACKUP_API = "https://vdnxbk.live.cntv.cn/api/v3/vdn/live";
    private static final String AUTH_SALT = "a4220a71b31746908fa3e7fdd7a6852a";
    private static final String CACHE_URL_PREFIX = "cached_url_";
    private static final String CACHE_TIME_PREFIX = "cached_at_";
    private static final long CACHE_TTL_MS = 10L * 60L * 1000L;
    private static final long STALE_CACHE_TTL_MS = 6L * 60L * 60L * 1000L;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";

    private final SharedPreferences preferences;
    private final Random random = new Random();
    private final Map<String, CachedUrl> memoryCache = new HashMap<String, CachedUrl>();

    LiveUrlResolver(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    String resolve(Channel channel) throws IOException {
        CachedUrl cached = getCached(channel.streamId);
        long now = System.currentTimeMillis();
        long cacheAge = cached == null ? Long.MAX_VALUE : now - cached.cachedAt;
        if (cached != null && cacheAge >= 0L && cacheAge < CACHE_TTL_MS) {
            Log.i(TAG, "Resolved HLS from cache for " + channel.streamId);
            return cached.url;
        }

        IOException lastError = null;
        try {
            String url = resolveFromApi(VDN_API, channel);
            cache(channel.streamId, url, now);
            return url;
        } catch (IOException error) {
            lastError = error;
            Log.w(TAG, "Primary VDN resolve failed for " + channel.streamId, error);
        }
        try {
            String url = resolveFromApi(VDN_BACKUP_API, channel);
            cache(channel.streamId, url, now);
            return url;
        } catch (IOException error) {
            Log.w(TAG, "Backup VDN resolve failed for " + channel.streamId, error);
            if (cached != null && cacheAge >= 0L && cacheAge < STALE_CACHE_TTL_MS) {
                Log.i(TAG, "Using stale HLS cache after resolve failure for "
                        + channel.streamId);
                return cached.url;
            }
            if (lastError != null) {
                throw lastError;
            }
            throw error;
        }
    }

    private CachedUrl getCached(String streamId) {
        synchronized (memoryCache) {
            CachedUrl cached = memoryCache.get(streamId);
            if (cached != null) {
                return cached;
            }
            String url = preferences.getString(CACHE_URL_PREFIX + streamId, null);
            long cachedAt = preferences.getLong(CACHE_TIME_PREFIX + streamId, 0L);
            if (url == null || url.length() == 0 || cachedAt <= 0L) {
                return null;
            }
            cached = new CachedUrl(url, cachedAt);
            memoryCache.put(streamId, cached);
            return cached;
        }
    }

    private void cache(String streamId, String url, long cachedAt) {
        CachedUrl cached = new CachedUrl(url, cachedAt);
        synchronized (memoryCache) {
            memoryCache.put(streamId, cached);
        }
        preferences.edit()
                .putString(CACHE_URL_PREFIX + streamId, url)
                .putLong(CACHE_TIME_PREFIX + streamId, cachedAt)
                .apply();
    }

    private String resolveFromApi(String endpoint, Channel channel) throws IOException {
        long timestamp = System.currentTimeMillis();
        int nonce = random.nextInt(1001);
        if (nonce < 100) {
            nonce += 100;
        }
        String authKey = authKey(channel.streamId, timestamp, nonce);
        String uid = getOrCreateUid();
        String url = endpoint + "?channel=" + encode(channel.streamId)
                + "&vn=1&pdrm=1&uid=" + encode(uid)
                + "&hbss=" + timestamp;

        HttpURLConnection connection =
                (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(8000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Referer", "https://tv.cctv.com/");
        connection.setRequestProperty("auth-key", authKey);
        connection.connect();
        boolean responseConsumed = false;
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("VDN HTTP " + status);
            }
            String body = new String(readFully(connection.getInputStream()), "UTF-8");
            responseConsumed = true;
            String hls = parseHlsUrl(body);
            if (hls == null || hls.length() == 0) {
                throw new IOException("VDN response did not include HLS URL");
            }
            hls = ChannelCatalog.preferHighBitrate(hls);
            Log.i(TAG, "Resolved dynamic HLS for " + channel.streamId + ": " + hls);
            return hls;
        } finally {
            if (!responseConsumed) {
                connection.disconnect();
            }
        }
    }

    private static String parseHlsUrl(String body) throws IOException {
        try {
            JSONObject root = new JSONObject(body);
            if (!"yes".equalsIgnoreCase(root.optString("ack"))) {
                throw new IOException("VDN ack=" + root.optString("ack")
                        + " status=" + root.optString("status")
                        + " tip=" + root.optString("tip_num"));
            }
            String manifest = optNestedString(root, "manifest", "hls_cdrm");
            if (manifest != null && manifest.length() > 0) {
                return manifest;
            }
            return optNestedString(root, "backup", "hls_cdrm");
        } catch (JSONException error) {
            throw new IOException("Unable to parse VDN response", error);
        }
    }

    private static String optNestedString(JSONObject root, String objectName, String key) {
        JSONObject object = root.optJSONObject(objectName);
        return object == null ? null : object.optString(key, null);
    }

    private String getOrCreateUid() {
        String uid = preferences.getString(UID, null);
        if (uid != null && uid.length() >= 20) {
            return uid;
        }
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        uid = Base64.encodeToString(bytes, Base64.NO_WRAP);
        preferences.edit().putString(UID, uid).apply();
        return uid;
    }

    private static String authKey(String channel, long timestamp, int nonce) throws IOException {
        return timestamp + "-" + nonce + "-"
                + md5(channel + timestamp + nonce + AUTH_SALT).toLowerCase(Locale.US);
    }

    private static String md5(String value) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(value.getBytes("UTF-8"));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                int number = item & 0xff;
                if (number < 16) {
                    builder.append('0');
                }
                builder.append(Integer.toHexString(number));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("MD5 is unavailable", error);
        }
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static byte[] readFully(InputStream input) throws IOException {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(4096);
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static final class CachedUrl {
        final String url;
        final long cachedAt;

        CachedUrl(String url, long cachedAt) {
            this.url = url;
            this.cachedAt = cachedAt;
        }
    }
}
