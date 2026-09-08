package xiao.bu.tv;

import android.content.Context;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashMap;

/** Explicit online smoke test: -e ku9sources true. Downloads original scripts unchanged. */
final class Ku9SourceSmokeTest {
    private static final String BASE = "https://raw.githubusercontent.com/buhanzhe/webSourceM3U8/main/k-web/ku9/js/";

    private static final class Host implements NativeQuickJs.Host {
        final JSONArray http = new JSONArray();
        final HashMap<String, String> cache = new HashMap<String, String>();
        String result, failure;
        @Override public boolean isCancelled() { return false; }
        @Override public String invoke(int op, String[] args) throws Exception {
            if (op <= 2) {
                long start = SystemClock.elapsedRealtime();
                String value;
                if (op == 0) {
                    try { value = Ku9HttpClient.getText(args[0], Ku9HttpClient.parseHeaders(args[1]), 8 * 1024 * 1024); }
                    catch (Exception error) {
                        http.put(new JSONObject().put("url", safeUrl(args[0])).put("error", error.toString()));
                        return "";
                    }
                } else if (op == 1) value = Ku9HttpClient.postText(args[0], args[1], args[2], 8 * 1024 * 1024);
                else value = Ku9HttpClient.requestJson(args[0], args[1], args[2], args[3], Boolean.parseBoolean(args[4]), 8 * 1024 * 1024);
                http.put(new JSONObject().put("url", safeUrl(args[0])).put("ms", SystemClock.elapsedRealtime()-start).put("chars", value.length()));
                return value;
            }
            switch (op) {
                case 3: return hex(MessageDigest.getInstance("MD5").digest(args[0].getBytes("UTF-8")));
                case 4: android.util.Log.i("Ku9SourceTest", args[0]); return null;
                case 5: result = args[0]; return null;
                case 6: failure = args[0]; return null;
                case 7: return cache.containsKey(args[0]) ? cache.get(args[0]) : "";
                case 8: cache.put(args[0], args[1]); return null;
                default: throw new IllegalArgumentException("Unknown bridge operation " + op);
            }
        }
    }

    static String run(Context context) throws Exception {
        NetworkClient.initialize(context);
        String[][] cases = {
            {"hnyx.js", "id=zjws4K&dur=10.0105&offset=175728140"},
            {"hnyx.js", "id=CCTV_4K&dur=10.001&offset=162424040"},
            {"fenghuang.js", "id=1016529"},
            {"cbg.js", "id=https%3A%2F%2Fsj.cbg.cn%2Fwap%2Flist%2F4918%2F1.html"},
            {"jlntv.js", "id=jlws"}, {"jlntv.js", "id=ds"}, {"jlntv.js", "id=zy"},
            {"hnyx.js", "id=zjws4K&dur=bad&offset=175728140"}
        };
        HashMap<String,String> scripts = new HashMap<String,String>();
        JSONArray rows = new JSONArray();
        Host capabilities = new Host();
        NativeQuickJs.execute("NtvCjsBridge.complete(JSON.stringify({atob:typeof atob,btoa:typeof btoa,console:typeof console,escape:typeof escape,unescape:typeof unescape}))", capabilities);
        JSONObject report = new JSONObject().put("api", android.os.Build.VERSION.SDK_INT)
                .put("abi", BuildConfig.CJS_PLUGIN_ABI).put("rawEngineGlobals", new JSONObject(capabilities.result)).put("cases", rows);
        capabilities = new Host();
        NativeQuickJs.execute(Ku9JsContract.bootstrap("NtvCjsBridge")
                + "NtvCjsBridge.complete(JSON.stringify({atob:typeof atob,btoa:typeof btoa,console:typeof console}))", capabilities);
        report.put("ku9Globals", new JSONObject(capabilities.result));
        for (String[] entry : cases) {
            JSONObject row = new JSONObject().put("script", BASE+entry[0]).put("query", entry[1]);
            rows.put(row);
            long start = SystemClock.elapsedRealtime();
            Host host = new Host();
            try {
                String script = scripts.get(entry[0]);
                if (script == null) {
                    script = Ku9HttpClient.getText(GithubProxy.apply(BASE+entry[0]), null, 2 * 1024 * 1024);
                    scripts.put(entry[0], script);
                }
                row.put("sha256", hex(MessageDigest.getInstance("SHA-256").digest(script.getBytes("UTF-8"))));
                NativeQuickJs.execute(CjsSiteResolver.buildJavascript(script,
                        new JSONObject().put("url", BASE+entry[0]+"?"+entry[1]).put("name", entry[0])), host);
                if (host.failure != null) throw new Exception(host.failure);
                Ku9JsContract.Output output = Ku9JsContract.parse(host.result);
                row.put("parseMs", SystemClock.elapsedRealtime()-start).put("http", host.http);
                if (output.playlist.length()>0) {
                    row.put("resolved", "m3u8").put("lines", output.playlist.split("\n").length);
                    for (String line : output.playlist.split("\n")) if (line.startsWith("http")) { row.put("media", probe(line)); break; }
                } else if (output.url.length()>0) {
                    row.put("resolved", safeUrl(output.url));
                    row.put("media", probe(output.url));
                } else row.put("error", "empty playback result");
            } catch (Throwable error) {
                row.put("error", error.toString()).put("http", host.http);
            }
            row.put("totalMs", SystemClock.elapsedRealtime()-start);
            android.util.Log.i("Ku9SourceTest", row.toString());
            FileOutputStream file = new FileOutputStream(new File(context.getFilesDir(), "ku9-source-smoke.json"));
            try { file.write(report.toString(2).getBytes("UTF-8")); } finally { file.close(); }
        }
        return report.toString(2)+"\n";
    }

    private static JSONObject probe(String url) throws Exception {
        return probe(url, 0);
    }

    private static JSONObject probe(String url, int depth) throws Exception {
        JSONObject result = new JSONObject().put("url", safeUrl(url));
        HttpURLConnection connection = null;
        long start = SystemClock.elapsedRealtime();
        try {
            connection = NetworkClient.open(new URL(url));
            connection.setConnectTimeout(4000); connection.setReadTimeout(4000);
            connection.setRequestProperty("Range", "bytes=0-65535");
            result.put("code", connection.getResponseCode()).put("type", connection.getContentType());
            if (connection.getResponseCode() < 400) {
                InputStream input = connection.getInputStream();
                byte[] data = new byte[65536]; int total = 0, count;
                try { while (total < data.length && (count=input.read(data,total,data.length-total))>0) total+=count; }
                finally { input.close(); }
                result.put("bytes", total);
                result.put("tsSync", total > 376 && data[0] == 0x47 && data[188] == 0x47 && data[376] == 0x47);
                String prefix = new String(data, 0, Math.min(total, 128), "UTF-8").trim();
                result.put("hls", prefix.startsWith("#EXTM3U"));
                if (prefix.startsWith("#EXTM3U")) {
                    String playlist = new String(data,0,total,"UTF-8");
                    for (String line:playlist.split("\n")) if (line.trim().length()>0 && !line.startsWith("#")) {
                        String child=new URL(new URL(url),line.trim()).toString();
                        result.put("childUrl",safeUrl(child));
                        if (depth < 2) result.put("child",probe(child,depth+1));
                        break;
                    }
                }
            }
        } catch (Exception error) { result.put("error",error.toString()); }
        finally { if(connection!=null)connection.disconnect(); }
        return result.put("ms",SystemClock.elapsedRealtime()-start);
    }

    private static String safeUrl(String url) { int query=url.indexOf('?');return query<0?url:url.substring(0,query); }
    private static String hex(byte[] bytes) {
        StringBuilder text=new StringBuilder();for(byte b:bytes)text.append(String.format(java.util.Locale.US,"%02x",b&255));return text.toString();
    }
}
