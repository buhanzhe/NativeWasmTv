package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Opt-in test APK only. Runs unmodified initial page scripts in QuickJS with real DOM RPC.
 * Not a browser replacement: bounded runtime, incomplete events/modules/dynamic script support.
 */
public final class QuickJsDomInstrumentation extends Instrumentation {
    private MainActivity activity;
    private WebView web;
    private String sites;
    private final StringBuilder report = new StringBuilder();
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    @Override public void onCreate(Bundle args) { super.onCreate(args); sites = args == null ? null : args.getString("sites"); start(); }
    private void log(String text) { report.append(text).append('\n'); android.util.Log.i("QuickJsDomTest", text); }
    private String read(InputStream in, int limit) throws Exception {
        try (InputStream source = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = source.read(buffer)) != -1) {
                if (out.size() + n > limit) throw new java.io.IOException("experiment download size limit");
                out.write(buffer, 0, n);
            }
            return out.toString("UTF-8");
        }
    }
    private String asset(String name) throws Exception { return read(getContext().getAssets().open(name), 128 * 1024); }
    private String download(String url) throws Exception {
        URL target = new URL(url);
        for (int hops = 0; hops < 6; hops++) {
            if (!"http".equals(target.getProtocol()) && !"https".equals(target.getProtocol())) throw new java.io.IOException("unsupported scheme");
            HttpURLConnection c = NetworkClient.open(target);
            try {
                c.setConnectTimeout(8000); c.setReadTimeout(8000); c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent", UA); int status = c.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    target = new URL(target, c.getHeaderField("Location")); continue;
                }
                if (status != 200) throw new java.io.IOException("HTTP " + status);
                return read(c.getInputStream(), 2 * 1024 * 1024);
            } finally { c.disconnect(); }
        }
        throw new java.io.IOException("redirect limit");
    }
    private String evaluate(String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1); String[] result = {null};
        final WebView target = web;
        runOnMainSync(() -> target.evaluateJavascript(script, value -> { result[0] = value; done.countDown(); }));
        if (!done.await(3, TimeUnit.SECONDS)) throw new java.io.IOException("WebView RPC timed out");
        Object value = new JSONTokener(result[0] == null ? "null" : result[0]).nextValue();
        return value == JSONObject.NULL ? "null" : String.valueOf(value);
    }
    private void load(String url, String html) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        runOnMainSync(() -> {
            if (web != null) { ((android.view.ViewGroup) web.getParent()).removeView(web); web.destroy(); }
            web = new WebView(activity);
            web.getSettings().setUserAgentString(UA);
            web.getSettings().setDomStorageEnabled(true);
            web.getSettings().setAllowFileAccess(false);
            web.getSettings().setAllowContentAccess(false);
            web.getSettings().setJavaScriptEnabled(false); // Prevent dual execution while parsing HTML.
            web.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String u) { loaded.countDown(); }
                @Override public boolean shouldOverrideUrlLoading(WebView view, String u) { return true; }
            });
            activity.addContentView(web, new FrameLayout.LayoutParams(-1, -1));
            web.loadDataWithBaseURL(url, html, "text/html", "UTF-8", null);
        });
        loaded.await(6, TimeUnit.SECONDS);
        runOnMainSync(() -> { web.stopLoading(); web.getSettings().setJavaScriptEnabled(true); });
    }
    private String inspect() throws Exception {
        return evaluate("JSON.stringify({title:document.title,text:document.body?document.body.innerText.slice(0,160):'',"
                + "nodes:document.getElementsByTagName('*').length,proof:document.getElementById('proof')?document.getElementById('proof').textContent:null,"
                + "timer:document.body.getAttribute('data-timer'),loopMs:document.body.getAttribute('data-loop-ms'),modern:document.body.getAttribute('data-modern')})");
    }
    private final class Host implements NativeQuickJs.Host {
        int calls; long rpcMs; final long deadline = SystemClock.elapsedRealtime() + 15000;
        @Override public boolean isCancelled() { return SystemClock.elapsedRealtime() >= deadline; }
        @Override public String invoke(int op, String[] args) throws Exception {
            if (op == 0) {
                if (++calls > 2000 || isCancelled()) throw new java.io.IOException("experiment RPC budget");
                long start = SystemClock.elapsedRealtime();
                try { return evaluate("window.__qdom(" + args[0] + ")"); }
                finally { rpcMs += SystemClock.elapsedRealtime() - start; }
            }
            if (op == 4) log(args[0]);
            else if (op == 5) { SystemClock.sleep(20); }
            else if (op == 6) { readyEvents(); }
            return null;
        }
    }
    private void readyEvents() throws Exception {
        evaluate("window.__qscript=-1;(function(){var e=document.createEvent('Event');e.initEvent('DOMContentLoaded',true,false);document.dispatchEvent(e);"
                + "e=document.createEvent('Event');e.initEvent('load',false,false);window.dispatchEvent(e);})()");
    }
    private JSONArray collect() throws Exception {
        return new JSONArray(evaluate("(function(){var a=[],s=document.querySelectorAll('script');"
                + "for(var i=0;i<s.length;i++){a.push({url:s[i].src,code:s[i].textContent,type:s[i].type,index:i});s[i].type='application/x-ntv-inert';}"
                + "window.__qscript=-1;Object.defineProperty(document,'currentScript',{configurable:true,get:function(){return document.scripts[window.__qscript]||null;}});"
                + "var ns=document.querySelectorAll('noscript');for(var j=0;j<ns.length;j++)ns[j].parentNode.removeChild(ns[j]);"
                + "var nodes=document.querySelectorAll('*');for(var n=0;n<nodes.length;n++){var attrs=nodes[n].attributes;"
                + "for(var k=attrs.length-1;k>=0;k--)if(/^on/i.test(attrs[k].name))nodes[n].removeAttribute(attrs[k].name);}"
                + "return JSON.stringify(a);})()"));
    }
    private void execute(JSONArray scripts, boolean quick) throws Exception {
        long started = SystemClock.elapsedRealtime();
        if (quick) {
            evaluate(asset("quickjs-dom-web.js")); Host host = new Host();
            String program = asset("quickjs-dom-runtime.js") + "\nvar __scripts=" + scripts + ";\n"
                    + "__scripts.forEach(function(s){try{__browser.__qscript=s.index;(0,eval)(s.code);__host.log('QJS OK '+s.name);}catch(e){__host.log('QJS FAIL '+s.name+' '+e+' '+String(e.stack).slice(0,350));}});"
                    + "__browser.__qscript=-1;__host.fail('ready');"
                    + "var __until=Date.now()+1000;while(Date.now()<__until){__pump();__host.complete('wait');}";
            try { NativeQuickJs.execute(program, host); }
            catch (Exception error) { log("QJS STOP " + error); }
            log("QJS elapsedMs=" + (SystemClock.elapsedRealtime() - started) + " rpcCalls=" + host.calls + " rpcMs=" + host.rpcMs);
        } else {
            for (int i = 0; i < scripts.length(); i++) {
                JSONObject script = scripts.getJSONObject(i);
                String result = evaluate("window.__qscript=" + script.optInt("index", -1) + ";(function(){try{(0,eval)(" + JSONObject.quote(script.getString("code")) + ");return 'OK';}catch(e){return String(e);}})()");
                log("WEB " + script.getString("name") + " " + result);
            }
            readyEvents(); SystemClock.sleep(1000); log("WEB elapsedMs=" + (SystemClock.elapsedRealtime() - started));
        }
    }
    private void compare(String url, String html, boolean proof) throws Exception {
        load(url, html); JSONArray found = collect(), scripts = new JSONArray(); int bytes = 0;
        if (proof) {
            scripts.put(new JSONObject().put("name", "dom-proof").put("code", asset("quickjs-dom-proof.js")));
            scripts.put(new JSONObject().put("name", "modern-syntax").put("code", "const f = (x = 7) => x * 6; document.body.setAttribute('data-modern', String(f()));"));
        } else {
            log("SITE " + url + " initialScripts=" + found.length());
            for (int i = 0; i < found.length(); i++) {
                JSONObject s = found.getJSONObject(i); String type = s.optString("type");
                if (!type.isEmpty() && !type.contains("javascript") && !type.contains("ecmascript")) { log("SKIP type=" + type); continue; }
                if (scripts.length() >= 20 || bytes > 4 * 1024 * 1024) { log("STOP script budget"); break; }
                String name = s.optString("url");
                try {
                    String code = name.isEmpty() ? s.optString("code") : download(name);
                    bytes += code.length(); scripts.put(new JSONObject().put("name", name.isEmpty() ? "inline-" + i : name).put("code", code).put("index", i));
                } catch (Exception e) { log("FETCH FAIL " + name + " " + e); }
            }
        }
        for (int mode = 0; mode < 2; mode++) {
            if (mode > 0) { load(url, html); collect(); }
            execute(scripts, mode == 1); String state = inspect(); log((mode == 1 ? "QJS STATE " : "WEB STATE ") + url + " " + state);
            if (proof) {
                JSONObject data = new JSONObject(state);
                if (!"Clicked".equals(data.optString("proof")) || !"done".equals(data.optString("timer"))) throw new AssertionError("DOM/callback/timer failed");
                if (mode == 1 && !"42".equals(data.optString("modern"))) throw new AssertionError("modern syntax failed");
            }
            android.graphics.Bitmap shot = getUiAutomation().takeScreenshot();
            if (shot != null) {
                java.io.File file = new java.io.File(activity.getExternalFilesDir(null), "quickjs-dom-" + (proof ? "proof" : new URL(url).getHost()) + "-" + mode + ".png");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
                finally { shot.recycle(); }
            }
        }
    }
    @Override public void onStart() {
        int code = -1;
        try {
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            compare("https://experiment.invalid/", "<!doctype html><html><body></body></html>", true);
            if (!"proof".equals(sites)) {
                String list = sites == null ? "https://live.jstv.com/?channelId=676|https://music.163.com/|https://www.bilibili.com/" : sites;
                for (String url : list.split("\\|")) {
                    try { compare(url, download(url), false); } catch (Exception e) { log("SITE FAIL " + url + " " + e); }
                }
            }
        } catch (Throwable e) { code = 0; log(android.util.Log.getStackTraceString(e)); }
        finally {
            if (web != null) runOnMainSync(() -> { ((android.view.ViewGroup) web.getParent()).removeView(web); web.destroy(); web = null; });
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null), "quickjs-dom-report.txt"))) { out.write(report.toString().getBytes("UTF-8")); }
            catch (Exception ignored) { }
        }
        Bundle result = new Bundle(); result.putString("stream", report.toString()); finish(code, result);
    }
}
