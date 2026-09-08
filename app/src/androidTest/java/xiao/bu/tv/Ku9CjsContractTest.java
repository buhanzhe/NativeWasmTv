package xiao.bu.tv;

import android.content.Context;
import android.os.SystemClock;
import org.json.JSONObject;

final class Ku9CjsContractTest {
    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }

    private static final class Host implements NativeQuickJs.Host {
        final Ku9SiteCache cache;
        String output, error;
        int httpCalls;
        Host(Context context) { cache = new Ku9SiteCache(context, "contract-test-a"); }
        @Override public boolean isCancelled() { return false; }
        @Override public String invoke(int op, String[] args) throws Exception {
            switch (op) {
                case 0:
                    check(new JSONObject(args[1]).getString("Accept").equals("test"), "get headers");
                    httpCalls++; return "get body";
                case 1:
                    check(args[1].equals("body") && args[2].equals("{}"), "post arguments");
                    httpCalls++; return "post body";
                case 2:
                    check(args[1].equals("HEAD") && args[4].equals("false"), "request redirects");
                    httpCalls++; return "{\"code\":200,\"body\":\"ok\",\"headers\":{}}";
                case 3: return "digest:" + args[0];
                case 4: return null;
                case 5: output = args[0]; return null;
                case 6: error = args[0]; return null;
                case 7: return cache.get(args[0]);
                case 8: cache.put(args[0], args[1], Double.parseDouble(args[2])); return null;
                default: throw new AssertionError("Unknown operation " + op);
            }
        }
    }

    static String run(Context context) throws Exception {
        String script = "function main(item){"
                + "function ok(v){if(!v)throw Error('Ku9 contract assertion');}"
                + "ok(atob(' Y Q==\\n')==='a');ok(atob('YWI')==='ab');ok(btoa('abc')==='YWJj');"
                + "var binary='';for(var i=0;i<256;i++)binary+=String.fromCharCode(i);"
                + "ok(atob(btoa(binary))===binary);ok(btoa('')==='');ok(atob('')==='');"
                + "var failed=false;try{atob('a');}catch(e){failed=true;}ok(failed);"
                + "failed=false;try{atob('AA=');}catch(e){failed=true;}ok(failed);"
                + "failed=false;try{btoa('中文');}catch(e){failed=true;}ok(failed);"
                + "console.log('Ku9',123);console.warn('warning');"
                + "ok(cjs===ku9);ok(ku9.getQuery(item.url,'id')==='中文 +');"
                + "ok(ku9.getQuery(item.url,'missing')==='');"
                + "ok(ku9.get('https://example.test/',{Accept:'test'})==='get body');"
                + "ok(ku9.post('https://example.test/','body','{}')==='post body');"
                + "ok(ku9.request('https://example.test/','HEAD',{},'',false).code===200);"
                + "ku9.setCache('token','值',10000);ok(cjs.getCache('token')==='值');"
                + "ok(ku9.md5('abc')==='digest:abc');ku9.log('ok');"
                + "return Promise.resolve({playUrl:'https://example.test/live.m3u8'});}";
        JSONObject item = new JSONObject().put("url", "https://example.test/a.cjs?id=%E4%B8%AD%E6%96%87+%2B");
        Host host = new Host(context);
        NativeQuickJs.execute(CjsSiteResolver.buildJavascript(script, item), host);
        check(host.error == null && host.httpCalls == 3, "CJS Ku9 bridge: " + host.error);
        check(Ku9JsContract.parse(host.output).url.equals("https://example.test/live.m3u8"), "async Ku9 result");
        String[] results = {"\"https://e.test/a\"", "{\"url\":\"https://e.test/a\"}",
                "{\"playUrl\":\"https://e.test/a\"}", "{\"playurl\":\"https://e.test/a\"}",
                "{\"urls\":[\"https://e.test/a\"]}", "{\"url\":null,\"playUrl\":\"https://e.test/a\"}"};
        for (String value : results) check("https://e.test/a".equals(Ku9JsContract.parse(value).url), "result alias " + value);
        check(Ku9JsContract.isDirectDataSource("https://e.test/a.mp4?token=abc"), "signed MP4 uses direct player");
        check(Ku9JsContract.isDirectDataSource("rtsp://e.test/live"), "RTSP uses direct player");
        check(!Ku9JsContract.isDirectDataSource("https://e.test/live.m3u8"), "HLS uses playlist proxy");
        String playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nhttps://e.test/a.ts\n";
        check(Ku9JsContract.parse(JSONObject.quote(playlist)).playlist.equals(playlist), "string playlist");
        check(Ku9JsContract.parse(new JSONObject().put("content", playlist).toString()).playlist.equals(playlist), "content playlist");
        check(Ku9JsContract.parse(new JSONObject().put("m3u8", playlist).toString()).playlist.equals(playlist), "m3u8 playlist");
        Ku9PlaylistServer server = new Ku9PlaylistServer();
        server.start();
        try {
            server.update(playlist);
            check(playlist.equals(Ku9HttpClient.getText(server.url(), null, 8192)), "generated playlist server");
            JSONObject head = new JSONObject(Ku9HttpClient.requestJson(server.url(), "HEAD", "{}", "", true, 8192));
            check(head.optInt("code") == 200 && head.optString("body").length() == 0, "HEAD sends no request body");
            server.update(playlist + "#EXT-X-ENDLIST\n");
            check(Ku9HttpClient.getText(server.url(), null, 8192).contains("#EXT-X-ENDLIST"), "playlist refresh");
        } finally { server.close(); }
        Ku9SiteCache a = new Ku9SiteCache(context, "contract-test-a");
        Ku9SiteCache b = new Ku9SiteCache(context, "contract-test-b");
        a.put("token", "a", 0); b.put("token", "b", 0);
        check("a".equals(a.get("token")) && "b".equals(b.get("token")), "site cache isolation");
        a.put("token", "short", 10); SystemClock.sleep(25);
        check("".equals(a.get("token")), "millisecond cache expiry");
        a.put("token", "persistent", 0);
        check("persistent".equals(new Ku9SiteCache(context, "contract-test-a").get("token")), "cache survives resolver recreation");
        host = new Host(context);
        NativeQuickJs.execute(CjsSiteResolver.buildJavascript("function main(){throw Error('test failure');}", item), host);
        check(host.output == null && host.error.contains("test failure"), "script failure delivery");
        return "PASS Ku9/CJS: shared API, Promise, query, headers, result aliases, live playlist, isolated cache/TTL, failure\n";
    }
}
