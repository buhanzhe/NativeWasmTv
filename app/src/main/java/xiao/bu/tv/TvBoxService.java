package xiao.bu.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

/** Ordinary type-1 JSON VOD only. No downloaded scripts or spider execution. */
final class TvBoxService {
    interface Store { String read(); boolean save(String value); }
    interface Transport { HttpURLConnection open(URL url) throws IOException; }
    private final Store store;
    private final Transport transport;
    private JSONArray sites = new JSONArray();
    private JSONArray results = new JSONArray();
    private volatile Thread worker;
    private volatile boolean cancelled;
    private volatile HttpURLConnection checkingConnection;
    private int total;
    private VodSearch searchJob;
    private VodProbe probe;
    private final java.util.LinkedHashMap<String, String> searchCache = new java.util.LinkedHashMap<String, String>();
    private final java.util.HashMap<String, Long> cacheTimes = new java.util.HashMap<String, Long>();
    private String revision = java.util.UUID.randomUUID().toString();

    TvBoxService(Store store, Transport transport) {
        this.store = store;
        this.transport = transport;
        try { sites = new JSONArray(store.read()); } catch (Exception ignored) { }
    }

    static URL httpUrl(String value) throws Exception {
        if (value.length() > 8192 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
            throw new IOException("地址无效");
        URL url = new URL(value);
        if (!("http".equals(url.getProtocol()) || "https".equals(url.getProtocol()))
                || url.getUserInfo() != null) throw new IOException("仅支持 HTTP/HTTPS 地址");
        return url;
    }

    private JSONObject get(String address, boolean checking) throws Exception {
        HttpURLConnection c = transport.open(httpUrl(address));
        VodSearch job = VodSearch.CURRENT.get();
        if (job != null) job.attach(c);
        if (checking) checkingConnection = c;
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "nTv-VOD/1.0");
        c.setInstanceFollowRedirects(true);
        VodDeadline budget = new VodDeadline(c, 15000);
        try {
            if (checking && cancelled) throw new IOException("已取消");
            int status = c.getResponseCode();
            budget.check();
            if (status != 200) throw new IOException("HTTP " + status);
            InputStream input = c.getInputStream();
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                long deadline = System.currentTimeMillis() + 12000;
                while ((count = input.read(buffer)) != -1) {
                    budget.check();
                    if (out.size() + count > 2 * 1024 * 1024) throw new IOException("响应超过 2 MB");
                    if (System.currentTimeMillis() > deadline || (checking && cancelled) || (job != null && job.stopped()))
                        throw new IOException("读取超时或已取消");
                    out.write(buffer, 0, count);
                }
                budget.check();
                return new JSONObject(out.toString("UTF-8").replaceFirst("^\\uFEFF", ""));
            } finally { input.close(); }
        } catch (Exception error) {
            budget.check(); throw error;
        } finally {
            budget.close();
            c.disconnect();
            if (job != null) job.detach(c);
            if (checking) checkingConnection = null;
        }
    }

    synchronized JSONObject state() throws Exception {
        JSONArray publicSites = new JSONArray();
        for (int i = 0; i < sites.length(); i++) {
            JSONObject s = sites.getJSONObject(i);
            publicSites.put(new JSONObject().put("id", i).put("key", sourceKey(s)).put("name", s.optString("name"))
                    .put("supported", s.optInt("type", -1) == 1));
        }
        return new JSONObject().put("ok", true).put("sites", publicSites)
                .put("revision", revision)
                .put("searchTask", searchJob != null ? searchJob.id : "")
                .put("results", new JSONArray(results.toString())).put("running", worker != null)
                .put("cancelled", cancelled).put("total", total);
    }

    void subscribe(String address) throws Exception {
        JSONObject config = get(address, false);
        JSONArray input = config.optJSONArray("sites");
        if (input == null || input.length() == 0 || input.length() > 1000)
            throw new IOException("需要包含 sites 的 TVBox 单仓配置（最多 1000 个源）");
        JSONArray clean = new JSONArray();
        for (int i = 0; i < input.length(); i++) {
            JSONObject s = input.optJSONObject(i);
            if (s == null) continue;
            clean.put(new JSONObject().put("name", s.optString("name", "未命名源"))
                    .put("type", s.optInt("type", -1)).put("api", s.optInt("type", -1) == 1
                            ? new URL(httpUrl(address), s.optString("api")).toString() : s.optString("api")));
        }
        synchronized (this) {
            if (worker != null) throw new IOException("请先停止检测，等待结束后再更换订阅");
            if (searchJob != null && searchJob.running()) throw new IOException("请先停止多源搜索，等待结束后更换订阅");
            if (probe != null && probe.running()) throw new IOException("请先停止媒体抽检");
            if (!store.save(clean.toString())) throw new IOException("保存订阅失败");
            sites = clean;
            revision = java.util.UUID.randomUUID().toString();
            results = new JSONArray();
            total = 0;
            searchJob = null;
            probe = null;
            searchCache.clear(); cacheTimes.clear();
        }
    }

    private synchronized JSONObject site(int id) throws Exception {
        if (id < 0 || id >= sites.length()) throw new IOException("源不存在，请刷新页面");
        JSONObject s = sites.getJSONObject(id);
        if (s.optInt("type", -1) != 1) throw new IOException("首版仅支持 type 1 JSON 采集接口");
        return s;
    }

    synchronized boolean matches(String value) { return revision.equals(value); }

    private static String sourceKey(JSONObject site) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(site.optString("api").getBytes("UTF-8"));
        StringBuilder out = new StringBuilder();
        for (byte b : digest) out.append("0123456789abcdef".charAt((b & 255) >>> 4)).append("0123456789abcdef".charAt(b & 15));
        return out.toString();
    }

    synchronized JSONObject startSearch(String keyword, int selected, int pages) throws Exception {
        if (keyword.trim().length() == 0 || keyword.length() > 100) throw new VodErrors.Input("请输入 1–100 字的片名");
        if (worker != null) throw new VodErrors.Input("请先停止源检测");
        if (probe != null && probe.running()) throw new VodErrors.Input("请先停止媒体抽检");
        if (searchJob != null && searchJob.running()) throw new VodErrors.Input("请先停止上一轮搜索，等待结束后再搜索");
        if (selected < -1 || selected >= sites.length()) throw new IOException("源不存在");
        JSONArray chosen = new JSONArray();
        java.util.HashSet<String> keys = new java.util.HashSet<String>();
        for (int i = 0; i < sites.length(); i++) {
            JSONObject s = sites.getJSONObject(i);
            if ((selected == -1 || selected == i) && s.optInt("type", -1) == 1 && keys.add(sourceKey(s)))
                chosen.put(new JSONObject().put("id", i).put("name", s.optString("name")));
        }
        if (chosen.length() == 0) throw new VodErrors.Input("没有可搜索的 type 1 源，请先导入订阅");
        searchJob = new VodSearch(revision, keyword.trim(), chosen, Math.max(1, Math.min(pages, 3)), new VodSearch.Fetcher() {
            public JSONObject search(int site, String word, int page) throws Exception { return TvBoxService.this.search(site, word, page, false); }
        });
        searchJob.start();
        return searchJob.state();
    }

    synchronized JSONObject searchState(String task, boolean cancel) throws Exception {
        if (searchJob == null || !searchJob.id.equals(task)) throw new VodErrors.Input("搜索任务已过期，请刷新页面后重新搜索");
        if (cancel) searchJob.cancel();
        return searchJob.state();
    }

    synchronized JSONObject probe(int site, String video, int line) throws Exception {
        if ((searchJob != null && searchJob.running()) || worker != null || (probe != null && probe.running()))
            throw new VodErrors.Input("请先停止其他搜索或检测任务");
        JSONObject detail = detail(site, video);
        JSONArray lines = detail.getJSONArray("lines");
        if (line < 0 || line >= lines.length() || lines.getJSONObject(line).getJSONArray("episodes").length() == 0)
            throw new IOException("没有可抽检的剧集");
        probe = new VodProbe(lines.getJSONObject(line).getJSONArray("episodes").getJSONObject(0).getString("url"), transport);
        probe.start(); return probe.state();
    }

    synchronized JSONObject probeState(String task, boolean cancel) throws Exception {
        if (probe == null || !probe.id.equals(task)) throw new IOException("抽检任务已过期");
        if (cancel) probe.cancel();
        return probe.state();
    }

    void close() {
        cancel();
        if (searchJob != null) searchJob.cancel();
        if (probe != null) probe.cancel();
    }

    static String query(String api, String key, String value, int page) throws Exception {
        httpUrl(api);
        // Preserve authentication parameters, replace only VOD query parameters.
        String base = api.split("#", 2)[0];
        int mark = base.indexOf('?');
        StringBuilder out = new StringBuilder(mark < 0 ? base : base.substring(0, mark));
        out.append('?');
        if (mark >= 0) for (String part : base.substring(mark + 1).split("&")) {
            String name = java.net.URLDecoder.decode(part.split("=", 2)[0], "UTF-8");
            if (!"ac".equals(name) && !"wd".equals(name) && !"ids".equals(name)
                    && !"pg".equals(name) && part.length() > 0) out.append(part).append('&');
        }
        return out.append("ac=detail&").append(key).append('=').append(URLEncoder.encode(value, "UTF-8"))
                .append("&pg=").append(Math.max(1, Math.min(page, 10000))).toString();
    }

    JSONObject search(int id, String keyword, int page, boolean checking) throws Exception {
        if (keyword.trim().length() == 0 || keyword.length() > 100) throw new IOException("请输入 1–100 字的片名");
        JSONObject source = site(id);
        String stableSourceKey = sourceKey(source);
        String cacheKey = stableSourceKey + "\n" + keyword.trim() + "\n" + page;
        if (!checking) synchronized (this) {
            Long time = cacheTimes.get(cacheKey);
            if (time != null && System.currentTimeMillis() - time < 120000)
                return new JSONObject(searchCache.get(cacheKey)).put("cached", true);
        }
        JSONObject data = get(query(source.getString("api"), "wd", keyword.trim(), page), checking);
        JSONArray list = data.optJSONArray("list");
        if (list == null) throw new IOException("接口未返回 JSON list");
        JSONArray items = new JSONArray();
        for (int i = 0; i < Math.min(list.length(), 200); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item != null && item.optString("vod_id").length() > 0) {
                String category = shortText(item.optString("type_name"), 100);
                String type = category.contains("电影") || (category.endsWith("片") && !category.contains("动漫")) ? "movie"
                        : category.contains("剧") ? "tv" : category.contains("动漫") ? "anime"
                        : category.contains("综艺") ? "variety" : "unknown";
                java.util.regex.Matcher year = java.util.regex.Pattern.compile("(?:19|20)\\d{2}").matcher(item.optString("vod_year"));
                if (item.optString("vod_id").length() > 100) continue;
                items.put(new JSONObject().put("id", item.optString("vod_id")).put("site", id)
                    .put("sourceKey", stableSourceKey).put("sourceName", shortText(source.optString("name"), 120))
                    .put("year", year.find() ? year.group() : "").put("type", type)
                    .put("name", shortText(item.optString("vod_name"), 200)).put("remarks", shortText(item.optString("vod_remarks"), 200)));
            }
        }
        JSONObject response = new JSONObject().put("ok", true).put("items", items).put("page", page)
                .put("truncated", list.length() > 200).put("pagecount", Math.max(1, data.optInt("pagecount", 1)));
        if (!checking && items.length() > 0) synchronized (this) {
            searchCache.put(cacheKey, response.toString()); cacheTimes.put(cacheKey, System.currentTimeMillis());
            int chars = 0; for (String value : searchCache.values()) chars += value.length();
            while (searchCache.size() > 16 || chars > 256 * 1024) {
                String first = searchCache.keySet().iterator().next();
                chars -= searchCache.remove(first).length(); cacheTimes.remove(first);
            }
        }
        return response;
    }

    private static String shortText(String value, int max) { return value.length() > max ? value.substring(0, max) : value; }

    JSONObject detail(int id, String video) throws Exception {
        if (video.length() == 0 || video.length() > 100) throw new IOException("影片 ID 无效");
        String api = site(id).getString("api");
        JSONObject data = get(query(api, "ids", video, 1), false);
        JSONArray list = data.optJSONArray("list");
        if (list == null || list.length() == 0) throw new IOException("影片详情为空");
        JSONObject detail = parseDetail(list.getJSONObject(0), api);
        // Compare structure, not signed URLs which can rotate legitimately per fetch.
        JSONArray shape = new JSONArray().put(detail.optString("name"));
        JSONArray lines = detail.getJSONArray("lines");
        for (int i = 0; i < lines.length(); i++) {
            JSONObject line = lines.getJSONObject(i);
            JSONArray names = new JSONArray().put(line.optString("name"));
            JSONArray episodes = line.getJSONArray("episodes");
            for (int j = 0; j < episodes.length(); j++) names.put(episodes.getJSONObject(j).optString("name"));
            shape.put(names);
        }
        return detail.put("fingerprint", sourceKey(new JSONObject().put("api", shape.toString())));
    }

    String libraryKey(int id, String video, int line) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(
                (site(id).getString("api") + "\n" + video + "\n" + line).getBytes("UTF-8"));
        StringBuilder key = new StringBuilder();
        for (byte b : digest) key.append(String.format(Locale.US, "%02x", b & 255));
        return key.toString();
    }

    static boolean direct(String address) {
        try {
            String path = httpUrl(address).getPath().toLowerCase(Locale.US);
            return path.endsWith(".m3u8") || path.endsWith(".mp4");
        } catch (Exception ignored) { return false; }
    }

    static JSONObject parseDetail(JSONObject item) throws Exception {
        return parseDetail(item, null);
    }

    static JSONObject parseDetail(JSONObject item, String base) throws Exception {
        String[] names = item.optString("vod_play_from").split("\\$\\$\\$", -1);
        String[] groups = item.optString("vod_play_url").split("\\$\\$\\$", -1);
        JSONArray lines = new JSONArray();
        for (int i = 0; i < Math.min(groups.length, 50); i++) {
            JSONArray episodes = new JSONArray();
            int skipped = 0;
            for (String entry : groups[i].split("#")) {
                int at = entry.indexOf('$');
                String url = (at < 0 ? entry : entry.substring(at + 1)).trim();
                if (base != null && url.length() > 0) {
                    try { url = new URL(httpUrl(base), url).toString(); } catch (Exception ignored) { }
                }
                if (!direct(url)) { if (url.length() > 0) skipped++; continue; }
                if (episodes.length() >= 1000) throw new IOException("单线路超过 1000 集");
                episodes.put(new JSONObject().put("name", at < 0 ? "第" + (episodes.length() + 1) + "集" : entry.substring(0, at))
                        .put("url", url));
            }
            lines.put(new JSONObject().put("name", i < names.length ? names[i] : "线路" + (i + 1))
                    .put("episodes", episodes).put("skipped", skipped));
        }
        return new JSONObject().put("ok", true).put("name", item.optString("vod_name", "未命名影片")).put("lines", lines);
    }

    synchronized void check(final String keyword, final int selected) throws Exception {
        if (worker != null) throw new IOException("检测正在进行");
        if (searchJob != null && searchJob.running()) throw new VodErrors.Input("请先停止多源搜索");
        if (probe != null && probe.running()) throw new VodErrors.Input("请先停止媒体抽检");
        if (keyword.trim().length() == 0 || keyword.length() > 100) throw new IOException("请填写检测片名");
        if (selected >= sites.length() || selected < -1) throw new IOException("源不存在");
        results = new JSONArray(); cancelled = false;
        total = selected < 0 ? sites.length() : 1;
        worker = new Thread(new Runnable() { public void run() {
            try {
                for (int index = 0; index < total && !cancelled; index++) {
                    int id = selected < 0 ? index : selected;
                    long start = System.currentTimeMillis();
                    String status = "failed";
                    int count = 0;
                    try {
                        if (sites.getJSONObject(id).optInt("type", -1) != 1) status = "unsupported";
                        else {
                            count = search(id, keyword, 1, true).getJSONArray("items").length();
                            status = count > 0 ? "available" : "empty";
                        }
                    } catch (Exception ignored) { }
                    if (cancelled) break;
                    synchronized (TvBoxService.this) {
                        results.put(new JSONObject().put("id", id).put("status", status).put("count", count)
                                .put("ms", System.currentTimeMillis() - start).put("checkedAt", System.currentTimeMillis()));
                    }
                }
            } catch (Exception ignored) { }
            finally { synchronized (TvBoxService.this) { worker = null; } }
        } }, "tvbox-source-check");
        worker.start();
    }

    void cancel() {
        cancelled = true;
        HttpURLConnection c = checkingConnection;
        if (c != null) c.disconnect();
    }
}
