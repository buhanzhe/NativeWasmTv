package xiao.bu.tv;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;

/** Per-proxy metadata only; finite playlists must not evict their first segments. */
final class HlsKeyRegistry<T> {
    private final Map<String, T> live = new LinkedHashMap<String, T>(64, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, T> eldest) { return size() > 512; }
    };
    private final Map<String, T> vod = new HashMap<String, T>();

    synchronized void put(String url, T value, boolean finite) throws IOException {
        if (finite) {
            if (!vod.containsKey(url) && vod.size() >= 16384)
                throw new IOException("加密点播分片超过安全上限 16384，拒绝返回未解密数据");
            vod.put(url, value);
            live.remove(url);
        } else {
            live.put(url, value);
            vod.remove(url);
        }
    }

    synchronized T get(String url) { T value = vod.get(url); return value == null ? live.get(url) : value; }
    synchronized boolean containsKey(String url) { return vod.containsKey(url) || live.containsKey(url); }
}
