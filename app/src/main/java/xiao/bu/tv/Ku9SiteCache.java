package xiao.bu.tv;

import android.content.Context;
import android.content.SharedPreferences;

/** Ku9 string cache and millisecond TTL, partitioned by the CJS site ID. */
final class Ku9SiteCache {
    private final SharedPreferences preferences;
    private final boolean legacyKeys;

    Ku9SiteCache(Context context, String siteId) {
        preferences = context.getSharedPreferences("cjs-ku9-cache-" + siteId, 0);
        legacyKeys = false;
    }

    private Ku9SiteCache(SharedPreferences preferences) {
        this.preferences = preferences;
        legacyKeys = true;
    }

    static Ku9SiteCache legacy(Context context) {
        return new Ku9SiteCache(context.getSharedPreferences("ku9_script_cache", 0));
    }

    private String valueKey(String key) { return legacyKeys ? key : "v:" + key; }
    private String expiryKey(String key) { return legacyKeys ? key + "__expires" : "e:" + key; }

    String get(String key) {
        long expires = preferences.getLong(expiryKey(key), 0L);
        if (expires > 0 && expires <= System.currentTimeMillis()) {
            preferences.edit().remove(valueKey(key)).remove(expiryKey(key)).apply();
            return "";
        }
        return preferences.getString(valueKey(key), "");
    }

    void put(String key, String value, double ttlMs) {
        long now = System.currentTimeMillis();
        long expires = ttlMs > 0 ? now + Math.min(Long.MAX_VALUE - now, (long) ttlMs) : 0L;
        preferences.edit().putString(valueKey(key), value == null ? "" : value)
                .putLong(expiryKey(key), expires).apply();
    }
}
