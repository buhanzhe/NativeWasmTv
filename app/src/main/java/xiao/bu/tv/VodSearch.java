package xiao.bu.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.UUID;

/** Bounded, cancellable cross-source search. All state is a per-job snapshot. */
final class VodSearch {
    interface Fetcher { JSONObject search(int site, String word, int page) throws Exception; }
    static final ThreadLocal<VodSearch> CURRENT = new ThreadLocal<VodSearch>();
    final String id = UUID.randomUUID().toString();
    final String revision;
    private final String word;
    private final JSONArray sites;
    private final Fetcher fetcher;
    private final int maxPages;
    private final ArrayList<HttpURLConnection> connections = new ArrayList<HttpURLConnection>();
    private final LinkedHashMap<String, JSONObject> groups = new LinkedHashMap<String, JSONObject>();
    private final HashSet<String> seen = new HashSet<String>();
    private final JSONArray statuses = new JSONArray();
    private int next, workers, candidates;
    private boolean stopped;
    private volatile boolean limited;
    private final long created = System.currentTimeMillis();

    VodSearch(String revision, String word, JSONArray sites, int maxPages, Fetcher fetcher) {
        this.revision = revision; this.word = word; this.sites = sites;
        this.maxPages = maxPages; this.fetcher = fetcher;
    }

    synchronized void start() {
        workers = Math.min(2, sites.length());
        for (int i = 0; i < workers; i++) new Thread(new Runnable() {
            public void run() { runWorker(); }
        }, "vod-search").start();
    }

    synchronized void attach(HttpURLConnection c) throws IOException {
        if (stopped) { c.disconnect(); throw new IOException("搜索已停止"); }
        connections.add(c);
    }
    synchronized void detach(HttpURLConnection c) { connections.remove(c); }
    synchronized boolean stopped() { return stopped; }
    void cancel() {
        ArrayList<HttpURLConnection> copy;
        synchronized (this) { stopped = true; copy = new ArrayList<HttpURLConnection>(connections); }
        for (HttpURLConnection c : copy) c.disconnect();
    }
    synchronized boolean running() { return workers > 0; }

    private void runWorker() {
        CURRENT.set(this);
        try {
            while (true) {
                JSONObject site;
                synchronized (this) {
                    if (stopped || limited || next >= sites.length()) return;
                    site = sites.optJSONObject(next++);
                }
                long start = System.currentTimeMillis();
                int count = 0, pages = 0;
                boolean truncated = false;
                String status = "empty";
                String code = "";
                try {
                    for (int page = 1; page <= maxPages && !stopped(); page++) {
                        JSONObject result = fetcher.search(site.getInt("id"), word, page);
                        JSONArray items = result.getJSONArray("items");
                        synchronized (this) {
                            if (stopped) return;
                            for (int i = 0; i < items.length(); i++) {
                                JSONObject item = items.getJSONObject(i);
                                String identity = item.getString("sourceKey") + "\n" + item.getString("id");
                                if (seen.contains(identity)) continue;
                                if (candidates >= 2000) { limited = true; truncated = true; break; }
                                seen.add(identity); candidates++; count++;
                                String key = groupKey(item);
                                JSONObject group = groups.get(key);
                                if (group == null) {
                                    group = new JSONObject().put("key", key).put("name", item.getString("name"))
                                            .put("year", item.optString("year")).put("type", item.optString("type"))
                                            .put("sources", new JSONArray());
                                    groups.put(key, group);
                                }
                                group.getJSONArray("sources").put(item);
                            }
                        }
                        pages = page;
                        truncated = truncated || result.optBoolean("truncated") || result.optInt("pagecount", 1) > maxPages;
                        if (result.optInt("pagecount", 1) <= page || limited) break;
                    }
                    status = count > 0 ? "available" : "empty";
                } catch (Exception error) {
                    status = count > 0 ? "partial" : "failed";
                    try { code = new JSONObject(VodErrors.json("search", error)).optString("code"); } catch (Exception ignored) { code = "IO"; }
                }
                synchronized (this) {
                    if (stopped) return;
                    try { statuses.put(new JSONObject().put("id", site.optInt("id")).put("name", site.optString("name"))
                            .put("status", status).put("code", code).put("count", count).put("pages", pages).put("truncated", truncated)
                            .put("ms", System.currentTimeMillis() - start)); } catch (Exception ignored) { }
                }
            }
        } finally { CURRENT.remove(); synchronized (this) { workers--; } }
    }

    synchronized JSONObject state() throws Exception {
        JSONArray list = new JSONArray();
        for (JSONObject group : groups.values()) list.put(group);
        return new JSONObject(new JSONObject().put("ok", true).put("task", id).put("revision", revision)
                .put("keyword", word).put("running", workers > 0).put("cancelled", stopped).put("limited", limited)
                .put("total", sites.length()).put("completed", statuses.length()).put("count", candidates)
                .put("maxPages", maxPages).put("createdAt", created).put("groups", list).put("statuses", statuses).toString());
    }

    static String groupKey(JSONObject item) {
        // Keep season numbers, bracket contents and edition markers. Unknown metadata stays isolated.
        String title = item.optString("name").toLowerCase(Locale.US).replaceAll("[\\s\\p{Punct}　（）【】《》]", "");
        String year = item.optString("year");
        String type = item.optString("type");
        return title + "|" + year + "|" + type +
                ((title.length() == 0 || year.length() == 0 || "unknown".equals(type))
                ? "|" + item.optString("sourceKey") + "|" + item.optString("id") : "");
    }
}
