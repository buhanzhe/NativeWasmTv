package xiao.bu.tv;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;

/** FongMi live JSON adapter. Provider core descriptors remain separate from channel URLs. */
final class TvbusLiveConfig {
    private static final int LIMIT = 8 * 1024 * 1024;
    private final Context context;
    TvbusLiveConfig(Context context) { this.context = context.getApplicationContext(); }
    private SharedPreferences prefs() { return context.getSharedPreferences("tvbus-live-config", 0); }
    static boolean isJson(byte[] bytes) throws IOException {
        String text = new String(bytes, "UTF-8").replace("\uFEFF", "").trim();
        return text.startsWith("{") || text.startsWith("[");
    }
    byte[] expand(byte[] bytes, String location, boolean refresh) throws IOException {
        if (!isJson(bytes)) {
            if (refresh) try { prefs().edit().remove(hash(location)).apply(); } catch (Exception ignored) { }
            return bytes;
        }
        if (bytes.length > LIMIT) throw new IOException("直播 JSON 过大");
        try {
            String key = hash(location), fingerprint = hash(new String(bytes, "UTF-8"));
            JSONObject saved = new JSONObject(prefs().getString(key, "{}"));
            if (!refresh && fingerprint.equals(saved.optString("fingerprint")))
                return saved.getString("playlist").getBytes("UTF-8");
            if (!refresh) throw new IOException("JSON 频道源需要先刷新以缓存子列表");
            Result result = new Result();
            Object value = new JSONTokener(new String(bytes, "UTF-8").replace("\uFEFF", "")).nextValue();
            parse(value, location, null, result, 0);
            if (result.count == 0) throw new IOException("直播 JSON 中没有有效频道");
            JSONObject record = new JSONObject().put("location", location).put("fingerprint", fingerprint)
                    .put("playlist", result.text.toString()).put("cores", result.cores);
            if (!prefs().edit().putString(key, record.toString()).commit()) throw new IOException("TVBus 源描述保存失败");
            return result.text.toString().getBytes("UTF-8");
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("直播 JSON 格式无效：" + error.getMessage(), error); }
    }
    private static final class Result {
        final StringBuilder text = new StringBuilder("#EXTM3U\n");
        final JSONObject cores = new JSONObject();
        int count;
    }
    private void parse(Object value, String base, JSONObject inherited, Result out, int depth) throws Exception {
        if (depth > 4) throw new IOException("直播 JSON 引用层级过深");
        if (value instanceof JSONArray) {
            JSONArray items = (JSONArray) value;
            for (int i = 0; i < items.length(); i++) parse(items.get(i), base, inherited, out, depth + 1);
            return;
        }
        if (!(value instanceof JSONObject)) throw new IOException("直播 JSON 项目无效");
        JSONObject item = (JSONObject) value;
        JSONObject core = item.optJSONObject("core");
        core = core == null ? inherited : normalizeCore(core, base);
        JSONArray lives = item.optJSONArray("lives");
        if (lives != null) { parse(lives, base, core, out, depth + 1); return; }
        JSONArray groups = item.optJSONArray("groups");
        if (groups != null) {
            for (int i = 0; i < groups.length(); i++) {
                JSONObject group = groups.getJSONObject(i);
                JSONArray channels = group.optJSONArray("channels");
                if (channels == null) continue;
                for (int c = 0; c < channels.length(); c++) {
                    JSONObject channel = channels.getJSONObject(c);
                    JSONArray urls = channel.optJSONArray("urls");
                    if (urls == null) urls = new JSONArray().put(channel.optString("url", ""));
                    for (int u = 0; u < urls.length(); u++) {
                        String url = urls.optString(u, "").trim();
                        if (url.isEmpty()) continue;
                        append(out, group.optString("name", item.optString("name", "在线频道")),
                                channel.optString("name", "频道"), resolve(base, url), core);
                    }
                }
            }
            return;
        }
        JSONArray depot = item.optJSONArray("urls");
        if (depot != null && depot.length() > 0) {
            String url = online(resolve(base, depot.getJSONObject(0).getString("url")));
            parse(new JSONTokener(new String(fetch(url, LIMIT), "UTF-8")).nextValue(), url, core, out, depth + 1);
            return;
        }
        String url = item.optString("url", "").trim();
        if (url.isEmpty()) throw new IOException("直播 JSON 缺少 url 或 groups");
        url = online(resolve(base, url));
        byte[] body = fetch(url, LIMIT);
        if (isJson(body)) { parse(new JSONTokener(new String(body, "UTF-8")).nextValue(), url, core, out, depth + 1); return; }
        // The existing parser already handles M3U/TXT. Keep its attributes and names.
        String playlist = resolvePlaylist(new String(body, "UTF-8").replace("\uFEFF", ""), url);
        out.text.append(playlist).append('\n');
        java.util.regex.Matcher urls = java.util.regex.Pattern.compile("(?i)(?:tvbus|sop)://[^\\s,<>\"]+").matcher(playlist);
        while (urls.find()) { remember(out, urls.group(), core); out.count++; }
        if (!playlist.toLowerCase(Locale.US).contains("tvbus://") && !playlist.toLowerCase(Locale.US).contains("sop://")) out.count++;
    }
    static String resolvePlaylist(String text, String base) throws IOException {
        StringBuilder output = new StringBuilder();
        for (String raw : text.replace("\r", "").split("\n")) {
            String line = raw.trim();
            if (!line.isEmpty() && !line.startsWith("#") && !line.contains("#genre#")) {
                int comma = line.indexOf(',');
                String prefix = comma < 0 ? "" : line.substring(0, comma + 1);
                String address = comma < 0 ? line : line.substring(comma + 1).trim();
                // M3U/TXT channel URLs may be relative to the child list, not the JSON.
                if (!address.isEmpty()) line = prefix + resolve(base, address);
            }
            output.append(line).append('\n');
        }
        return output.toString();
    }
    private static void append(Result out, String group, String name, String url, JSONObject core) throws Exception {
        out.text.append("#EXTINF:-1 group-title=\"").append(group.replace('"', '\'').replace('\n', ' '))
                .append("\",").append(name.replace('\n', ' ')).append('\n').append(url).append('\n');
        remember(out, url, core); out.count++;
    }
    private static void remember(Result out, String url, JSONObject core) throws Exception {
        if (!TvbusPlugin.matches(url) || core == null) return;
        String key = hash(url);
        if (out.cores.has(key) && !canonical(out.cores.getJSONObject(key)).equals(canonical(core)))
            throw new IOException("同一 TVBus 地址使用了不同 core，无法自动选择");
        out.cores.put(key, core);
    }
    static JSONObject normalizeCore(JSONObject source, String base) throws Exception {
        JSONObject result = new JSONObject(source.toString());
        for (String key : new String[] {"so", "auth", "broker", "broker_url", "name", "pass", "domain", "resp"}) {
            String value = result.optString(key, "").trim();
            if (value.startsWith("./") || value.startsWith("../") || value.startsWith("/")) result.put(key, resolve(base, value));
        }
        String so = result.optString("so", "");
        if (!so.isEmpty()) result.put("so", online(resolve(base, so)));
        return result;
    }
    JSONObject find(String sourceId, String url, List<PlaylistManager.Source> sources) throws IOException {
        try {
            JSONObject found = null;
            for (PlaylistManager.Source source : sources) {
                if (!source.enabled || (!sourceId.isEmpty() && !sourceId.equals(source.id))) continue;
                JSONObject saved = new JSONObject(prefs().getString(hash(source.location), "{}"));
                JSONObject cores = saved.optJSONObject("cores");
                JSONObject core = cores == null ? null : cores.optJSONObject(hash(url));
                if (core == null) continue;
                if (found != null && !canonical(found).equals(canonical(core))) throw new IOException("TVBus 来源的 core 冲突");
                found = core;
            }
            return found == null ? new JSONObject() : new JSONObject(found.toString());
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("TVBus 源描述读取失败", error); }
    }
    static JSONObject runtimeCore(JSONObject source) throws Exception {
        JSONObject result = new JSONObject(source.toString());
        for (String key : new String[] {"name", "pass", "domain"}) {
            String value = result.optString(key, "");
            if (value.startsWith("https://") || value.startsWith("http://"))
                result.put(key, new String(fetch(value, 256 * 1024), "UTF-8").trim());
        }
        return result;
    }
    static String resolve(String base, String value) throws IOException {
        if (value.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) return value;
        try { return new URL(new URL(base), value).toString(); }
        catch (Exception error) { throw new IOException("直播 JSON 相对地址缺少有效基准网址", error); }
    }
    static String online(String value) throws IOException {
        try {
            URI uri = new URI(value);
            if (("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) && uri.getHost() != null) return value;
        } catch (Exception ignored) { }
        throw new IOException("TVBus 引擎或子列表地址需要 HTTP/HTTPS");
    }
    static byte[] fetch(String url, int limit) throws IOException {
        HttpURLConnection connection = NetworkClient.openComponent(new URL(online(url)), true, 25000);
        connection.setConnectTimeout(10000); connection.setReadTimeout(15000);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("源描述下载失败：HTTP " + connection.getResponseCode());
            if (connection.getContentLength() > limit) throw new IOException("源描述过大");
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() + count > limit) throw new IOException("源描述过大");
                    out.write(buffer, 0, count);
                }
                return out.toByteArray();
            }
        } finally { connection.disconnect(); }
    }
    static String canonical(Object value) throws JSONException {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            java.util.TreeSet<String> keys = new java.util.TreeSet<>();
            java.util.Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            StringBuilder text = new StringBuilder("{");
            for (String key : keys) {
                if (text.length() > 1) text.append(',');
                text.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key)));
            }
            return text.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder text = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) text.append(',');
                text.append(canonical(array.get(i)));
            }
            return text.append(']').toString();
        }
        return value instanceof String ? JSONObject.quote((String) value) : String.valueOf(value);
    }
    static String hash(String text) throws Exception {
        StringBuilder value = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8"))) value.append(String.format(Locale.US, "%02x", b & 255));
        return value.toString();
    }
}
