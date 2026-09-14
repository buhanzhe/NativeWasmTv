package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.webkit.JavascriptInterface;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Same generated program, host operations and assertions against both real Android engines. */
public final class Ku9UnifiedInstrumentation extends Instrumentation {
    private Ku9ScriptEngine engine;
    private Ku9JsContract contract;

    public static final class Host extends Ku9Host {
        final HashMap<String, String> cache = new HashMap<>();
        final CountDownLatch done = new CountDownLatch(1);
        volatile String output, error;
        volatile int calls, http;
        @Override protected boolean isRequestCancelled() { return false; }
        @Override protected void onComplete(String value) { output = value; calls++; done.countDown(); }
        @Override protected void onFailure(String value) { error = value; calls++; done.countDown(); }
        @Override @JavascriptInterface public String getCache(String key) { return cache.containsKey(key) ? cache.get(key) : ""; }
        @Override @JavascriptInterface public void setCache(String key, String value, double ttl) { cache.put(key, value); }
        @Override @JavascriptInterface public String post(String url, String body, String headers) {
            http++;
            return body + ":" + headers;
        }
        @Override @JavascriptInterface public String request(String url, String method, String headers, String body, boolean follow) {
            http++;
            return "{\"code\":403,\"url\":\"https://example.test/denied\",\"headers\":{\"X-Test\":\"yes\"}}";
        }
    }

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private static void check(boolean condition, String why) { if (!condition) throw new AssertionError(why); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            // Remove only an interrupted fixture selection before startup can
            // resume it (a 4K fixture may itself hang an old device).
            android.content.SharedPreferences prefs = getTargetContext().getSharedPreferences(MainActivity.PREFERENCES, 0);
            String snapshot = prefs.getString("last_channel_snapshot_v2", "");
            if (snapshot.length() > 0) {
                try {
                    String name = new JSONObject(snapshot).optString("name", "");
                    if (name.matches("MATRIX_[0-9]+ .*"))
                        prefs.edit().remove("last_channel_snapshot_v2").commit();
                } catch (org.json.JSONException ignored) { }
            }
            MainActivity activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(() -> engine = new Ku9ScriptEngine(activity));
            contract = new Ku9JsContract(activity);
            String report = Ku9CjsContractTest.run(activity);
            check(!Ku9EnginePolicy.usesWebView("// document\nfunction main(){var s='fetch';return {url:'https://x'};}"), "plain script policy");
            check(Ku9EnginePolicy.usesWebView("function main(){return document.createElement('canvas');}"), "DOM routing");
            check(Ku9EnginePolicy.usesWebView("function main(){return window['document'];}"), "bracket routing");
            check(!Ku9EnginePolicy.usesWebView("// @ku9-engine: quickjs\nfunction main(){var document={};}"), "explicit QuickJS");
            check(Ku9EnginePolicy.usesWebView("// @ku9-engine webview\nfunction main(){}"), "explicit WebView");
            String script = "var CryptoJS=require('crypto'),RSA=require('jsencrypt');"
                    + "module.exports.main=function(item){function ok(v,s){if(!v)throw Error(s);}"
                    + "ok(item.id==='中文 +'&&item.name==='keep','query');"
                    + "var u=ku9.Uri('https://e.test:8443/a/b?x=a+b#hash');"
                    + "ok(u.Port===8443&&u.Params.x==='a b'&&u.Path==='/a/','URI');"
                    + "ok(CryptoJS.MD5('abc').toString()===ku9.md5('abc'),'MD5');"
                    + "ok(CryptoJS.SHA256('abc').toString()===ku9.sha256('abc'),'SHA');"
                    + "ok(typeof RSA==='function','RSA');ok(cjs===ku9,'alias');"
                    + "ok(ku9.decodeBase64(ku9.encodeBase64('中文'))==='中文','base64');"
                    + "ku9.setCache('obj',{n:1,a:[true,'x']});ok(ku9.getCache('obj').a[1]==='x','object cache');"
                    + "ku9.setCache('str','123');ok(ku9.getCache('str')==='123','string cache');"
                    + "ku9.setCache('num',123);ok(ku9.getCache('num')===123,'number cache');"
                    + "ku9.setCache('null',null);ok(ku9.getCache('null')===null,'null cache');"
                    + "ok(ku9.post('https://e.test','body',{})==='body:{}','post');"
                    + "ok(ku9.post('https://e.test',{},'body')==='body:{}','post overload');"
                    + "ok(ku9.request('https://e.test').furl==='https://example.test/denied','furl');"
                    + "ok(JSON.parse(ku9.getHeaders('https://e.test'))['X-Test']==='yes','headers');"
                    + "ok(ku9.toDate(ku9.toTimestamp('2026-09-14','yyyy-MM-dd','UTC'),'yyyy-MM-dd','UTC')==='2026-09-14','dates');"
                    + "return {playUrl:'https://e.test/live.m3u8',referer:'https://e.test/',userAgent:'test'};};";
            String expected = null;
            for (boolean web : new boolean[]{false, true}) {
                Host host = run(script, web);
                if (web && android.os.Build.VERSION.SDK_INT < 17) {
                    check(host.error != null && host.error.contains("Android 4.2"), "legacy browser bridge blocked");
                    report += "PASS API <17 rejects unsafe browser bridge; QuickJS remains available\n";
                    continue;
                }
                check(host.error == null && host.calls == 1 && host.http == 4, "contract " + web + ": " + host.error);
                if (expected == null) expected = host.output;
                else check(expected.equals(host.output), "engine outputs differ");
                Host failed = run("function main(){if(ku9.request('https://e.test').code===403)throw Error('HTTP 403');}", web);
                check(failed.error.contains("HTTP 403") && failed.http == 1 && failed.calls == 1, "403 must not retry");
                Host duplicate = run("function main(){NtvCjsBridge.complete('{\"url\":\"https://first\"}');return 'https://second';}", web);
                SystemClock.sleep(100);
                check(duplicate.calls == 1 && duplicate.output.contains("first"), "one terminal callback");
                report += "PASS " + (web ? "WebView" : "QuickJS") + ": same contract, CommonJS/crypto/RSA, typed cache, URI/query, POST, headers, dates, 403/no retry, one callback\n";
            }
            if (android.os.Build.VERSION.SDK_INT >= 17) {
            Host dom = run("function main(){return {then:function(resolve){setTimeout(function(){var c=document.createElement('canvas');resolve({url:'https://canvas/'+(c.getContext('2d')?'ok':'bad')});},10);}};}", true);
            check(dom.error == null && dom.output.contains("/ok"), "browser DOM + asynchronous result: " + dom.error);
            Host cancelled = new Host();
            String delayed = contract.build("function main(){setTimeout(function(){NtvCjsBridge.complete('{}');},500);return new Promise(function(){});}", input());
            runOnMainSync(() -> engine.execute(delayed, cancelled, true));
            SystemClock.sleep(100);
            runOnMainSync(() -> engine.cancel());
            SystemClock.sleep(650);
            check(cancelled.calls == 0, "cancelled browser callback");
            Host next = run("function main(){return 'https://next';}", false);
            check(next.error == null && next.output.contains("next"), "switch engine after cancellation");
            report += "PASS DOM/Canvas/async, cancellation and engine switch\n";
            }
            report += Ku9PageResultTest.run(this, activity);
            result.putString("stream", report);
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL " + android.util.Log.getStackTraceString(error));
            finish(0, result);
        } finally {
            runOnMainSync(() -> { if (engine != null) engine.cancel(); });
        }
    }

    private JSONObject input() throws Exception {
        return new JSONObject().put("url", "https://example.test/script.js?id=%E4%B8%AD%E6%96%87+%2B&name=override").put("name", "keep");
    }
    private Host run(String script, boolean web) throws Exception {
        Host host = new Host();
        String program = contract.build(script, input());
        runOnMainSync(() -> engine.execute(program, host, web));
        check(host.done.await(35, TimeUnit.SECONDS), "execution timed out");
        return host;
    }
}
