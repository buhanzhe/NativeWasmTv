package xiao.bu.tv;

import android.content.Context;
import android.content.SharedPreferences;

/** Ku9 string cache and millisecond TTL, partitioned by the CJS site ID. */
final class Ku9SiteCache {
    private final SharedPreferences preferences;

    Ku9SiteCache(Context context, String siteId) {
        preferences = context.getSharedPreferences("cjs-ku9-cache-" + siteId, 0);
    }

    String get(String key) {
        long expires = preferences.getLong("e:" + key, 0L);
        if (expires > 0 && expires <= System.currentTimeMillis()) {
            preferences.edit().remove("v:" + key).remove("e:" + key).apply();
            return "";
        }
        return preferences.getString("v:" + key, "");
    }

    void put(String key, String value, double ttlMs) {
        long now = System.currentTimeMillis();
        long expires = ttlMs > 0 ? now + Math.min(Long.MAX_VALUE - now, (long) ttlMs) : 0L;
        preferences.edit().putString("v:" + key, value == null ? "" : value)
                .putLong("e:" + key, expires).apply();
    }
}
