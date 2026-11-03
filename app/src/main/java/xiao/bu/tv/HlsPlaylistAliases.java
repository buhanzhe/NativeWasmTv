package xiao.bu.tv;

import java.util.LinkedHashMap;
import java.util.Map;

/** Per-proxy playlist aliases keep IJK's initial/reload URL below 1024 bytes. */
final class HlsPlaylistAliases {
    private static final int LIMIT = 128;
    private final LinkedHashMap<String, String> origins =
            new LinkedHashMap<String, String>(16, 0.75f, true);
    private long sequence;

    synchronized String token(String origin) {
        String existing = null;
        for (Map.Entry<String, String> entry : origins.entrySet()) {
            if (origin.equals(entry.getValue())) {
                existing = entry.getKey();
                break;
            }
        }
        if (existing != null) {
            origins.get(existing); // Keep the active playlist in the bounded cache.
            return existing;
        }
        String token = "~" + Long.toString(sequence++, 36);
        origins.put(token, origin);
        if (origins.size() > LIMIT) origins.remove(origins.keySet().iterator().next());
        return token;
    }

    synchronized String resolve(String token) {
        String origin = origins.get(token);
        if (origin == null) throw new IllegalArgumentException("Unknown HLS playlist alias");
        return origin;
    }

    synchronized void clear() { origins.clear(); }
}
