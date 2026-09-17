package xiao.bu.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public final class TvBoxServiceTest {
    static int assertions;
    static void check(boolean value, String name) {
        assertions++;
        if (!value) throw new AssertionError(name);
    }
    static final class Memory implements TvBoxService.Store {
        String value = "[]";
        public String read() { return value; }
        public boolean save(String text) { value = text; return true; }
    }
    static final class Fake extends HttpURLConnection {
        final String body;
        Fake(URL url, String body) { super(url); this.body = body; }
        public int getResponseCode() { return 200; }
        public InputStream getInputStream() throws java.io.IOException { return new ByteArrayInputStream(body.getBytes("UTF-8")); }
        public void disconnect() { }
        public boolean usingProxy() { return false; }
        public void connect() { }
    }
    public static void main(String[] args) throws Exception {
        JSONObject detail = TvBoxService.parseDetail(new JSONObject()
                .put("vod_name", "测试剧").put("vod_play_from", "hls$$$web")
                .put("vod_play_url", "第01集$https://media.example/1.m3u8?token=a$b#第02集$https://media.example/2.mp4$$$观看$https://media.example/player"));
        JSONArray lines = detail.getJSONArray("lines");
        check(lines.length() == 2, "group delimiter");
        check(lines.getJSONObject(0).getJSONArray("episodes").length() == 2, "episodes");
        check(lines.getJSONObject(0).getJSONArray("episodes").getJSONObject(0).getString("url").endsWith("a$b"), "dollar in URL");
        check(lines.getJSONObject(1).getInt("skipped") == 1, "webpage skipped");
        JSONObject relative = TvBoxService.parseDetail(new JSONObject().put("vod_play_url", "第一集$/media/1.m3u8?token=a$b"), "https://api.example/api/vod");
        check(relative.getJSONArray("lines").getJSONObject(0).getJSONArray("episodes").getJSONObject(0).getString("url").equals("https://api.example/media/1.m3u8?token=a$b"), "relative URL keeps query");
        check(!TvBoxService.direct("file:///1.m3u8"), "file rejected");
        check(!TvBoxService.direct("https://media.example/1.m3u8\ninject"), "newline rejected");
        check(!TvBoxService.direct("https://u:p@media.example/1.m3u8"), "userinfo rejected");
        String query = TvBoxService.query("https://api.example/?token=private&ac=list&wd=old&pg=9#x", "wd", "仙剑", 2);
        check(query.contains("token=private") && !query.contains("wd=old") && query.endsWith("pg=2"), "query replacement preserves auth");
        final Memory memory = new Memory();
        TvBoxService service = new TvBoxService(memory, new TvBoxService.Transport() {
            public HttpURLConnection open(URL url) {
                String body = url.getPath().equals("/config")
                    ? "{\"sites\":[{\"name\":\"Good\",\"type\":1,\"api\":\"https://api.example/good?token=private\"},{\"name\":\"Empty\",\"type\":1,\"api\":\"https://api.example/empty\"},{\"name\":\"Spider\",\"type\":3,\"api\":\"csp_x\"},{\"name\":\"Bad\",\"type\":1,\"api\":\"https://api.example/bad\"}]}"
                    : url.getPath().equals("/good") ? "{\"pagecount\":3,\"list\":[{\"vod_id\":555,\"vod_name\":\"仙剑\"}]}"
                    : url.getPath().equals("/empty") ? "{\"list\":[]}" : "<html>not JSON</html>";
                return new Fake(url, body);
            }
        });
        service.subscribe("https://api.example/config");
        check(service.state().getJSONArray("sites").length() == 4, "subscription count");
        check(!service.state().toString().contains("private"), "state redacts endpoints/token");
        check(service.search(0, "仙剑", 2, false).getInt("pagecount") == 3, "pagination");
        check(service.search(0, "仙剑", 2, false).getBoolean("cached"), "bounded successful page cache");
        check(service.search(0, "仙剑", 2, true).optBoolean("cached") == false, "source check bypasses cache");
        check(service.libraryKey(0, "555", 0).equals(service.libraryKey(0, "555", 0)), "stable import id");
        service.check("仙剑", -1);
        long deadline = System.currentTimeMillis() + 5000;
        while (service.state().getBoolean("running") && System.currentTimeMillis() < deadline) Thread.sleep(10);
        JSONArray results = service.state().getJSONArray("results");
        check(results.length() == 4, "all sites checked");
        String[] statuses = {"available", "empty", "unsupported", "failed"};
        for (int i = 0; i < statuses.length; i++) check(results.getJSONObject(i).getString("status").equals(statuses[i]), statuses[i]);
        service.cancel();
        check(service.state().getBoolean("cancelled"), "cancel state");
        System.out.println("PASS " + assertions + " assertions");
    }
}
