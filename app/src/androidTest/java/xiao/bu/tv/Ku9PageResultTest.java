package xiao.bu.tv;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Deterministic lifecycle regression for Ku9 webview + jscode results. */
final class Ku9PageResultTest {
    static String run(Instrumentation test, Activity activity) throws Exception {
        checkUjsParsing();
        java.util.concurrent.atomic.AtomicInteger clicks = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicLong clickAt = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicBoolean leakedUjs = new java.util.concurrent.atomic.AtomicBoolean();
        ServerSocket server = new ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"));
        Thread responder = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                    String request=input.readLine();
                    if(request!=null&&request.contains("ujs="))leakedUjs.set(true);
                    boolean clicked=request!=null&&request.startsWith("GET /click-evidence ");
                    if(clicked){clicks.incrementAndGet();clickAt.set(SystemClock.elapsedRealtime());}
                    String line;
                    while ((line = input.readLine()) != null && line.length() > 0) { }
                    boolean manifest=request!=null&&request.startsWith("GET /covered.m3u8 ");
                    byte[] body = (manifest?"#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\nsegment.ts\n":clicked?"ok":"<!doctype html><html><head><title>plain</title></head><body>"
                            +"<button id='go'>播放</button><script>document.getElementById('go').onclick=function(){"
                            +"var x=new XMLHttpRequest();x.open('GET','/click-evidence',true);"
                            +"x.onload=function(){document.title='clicked-中文-'+window.ujsClickHits+'-'+window.explicitHits;};x.send();};"
                            +"</script></body></html>").getBytes("UTF-8");
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: "+(manifest?"application/vnd.apple.mpegurl":"text/html; charset=utf-8")+"\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                    socket.getOutputStream().write(body);
                } catch (Exception ignored) { }
            }
        }, "ku9-page-test-server");
        responder.setDaemon(true); responder.start();
        WebSourceView[] page = {null};
        String url = "http://127.0.0.1:" + server.getLocalPort() + "/";
        try {
            long firstStart=SystemClock.elapsedRealtime();
            test.runOnMainSync(() -> {
                page[0] = new WebSourceView(activity);
                page[0].applyAutoPlay(false); // Explicit UJS is the subject; disable automatic TV control clicks.
                ((ViewGroup) activity.findViewById(android.R.id.content)).addView(page[0], new ViewGroup.LayoutParams(320, 180));
                page[0].open(100, url, "window.times=(window.times||0)+1;document.title='injected-'+window.times;", false, "", "");
            });
            awaitTitle(test, page[0], "injected-1");
            long firstPageMs=SystemClock.elapsedRealtime()-firstStart;
            // API 14-25 owns the browser in LegacyWebActivity's process. Read
            // the facade state there; a local WebView field is intentionally null.
            if(android.os.Build.VERSION.SDK_INT>=26){
                Field browser = member(WebView.class); browser.setAccessible(true);
                Field client = member(WebViewClient.class); client.setAccessible(true);
                test.runOnMainSync(() -> {
                    try { ((WebViewClient) client.get(page[0])).onPageFinished((WebView) browser.get(page[0]), url); }
                    catch (Exception e) { throw new RuntimeException(e); }
                });
            }
            SystemClock.sleep(200);
            awaitTitle(test, page[0], "injected-1");
            test.runOnMainSync(() -> { page[0].closePage(); page[0].open(101, url + "next", "", false, "", ""); });
            awaitTitle(test, page[0], "plain");
            SystemClock.sleep(200);
            awaitTitle(test, page[0], "plain");
            String ujs = "window.ujsHits=(window.ujsHits||0)+1;document.title='ujs-中文-'+window.ujsHits+'-'+window.explicitHits;";
            String encoded = java.net.URLEncoder.encode(android.util.Base64.encodeToString(
                    ujs.getBytes("UTF-8"), android.util.Base64.NO_WRAP), "UTF-8");
            test.runOnMainSync(() -> {
                page[0].closePage();
                page[0].open(102, url + "?keep=%2B&ujs=" + encoded + "#anchor",
                        "window.explicitHits=1;", false, "", "");
            });
            awaitTitle(test, page[0], "ujs-中文-1-1");
            if(page[0].currentPageUrl().contains("ujs=")) throw new AssertionError("ujs leaked into site URL");
            SystemClock.sleep(200);
            awaitTitle(test, page[0], "ujs-中文-1-1");
            test.runOnMainSync(() -> { page[0].closePage(); page[0].open(103, url + "clean", "", false, "", ""); });
            awaitTitle(test, page[0], "plain");
            String clickScript="window.ujsClickHits=(window.ujsClickHits||0)+1;document.getElementById('go').click();";
            String clickEncoded=java.net.URLEncoder.encode(android.util.Base64.encodeToString(
                    clickScript.getBytes("UTF-8"),android.util.Base64.NO_WRAP),"UTF-8");
            long clickStart=SystemClock.elapsedRealtime();
            test.runOnMainSync(()->{
                page[0].closePage();page[0].open(104,url+"?keep=%2B&ujs="+clickEncoded+"#anchor",
                        "window.explicitHits=1;",false,"","");
            });
            awaitTitle(test,page[0],"clicked-中文-1-1");
            SystemClock.sleep(200);
            if(clicks.get()!=1)throw new AssertionError("UJS click request count: "+clicks.get());
            if(leakedUjs.get())throw new AssertionError("UJS query reached the actual HTTP server");
            if(!page[0].currentPageUrl().equals(url+"?keep=%2B#anchor"))throw new AssertionError("Page query/hash changed");
            test.runOnMainSync(()->{page[0].closePage();page[0].open(105,url+"after-click","",false,"","");});
            awaitTitle(test,page[0],"plain");SystemClock.sleep(200);
            if(clicks.get()!=1)throw new AssertionError("Previous UJS clicked after channel replacement");
            java.util.concurrent.atomic.AtomicInteger coveredStreams=new java.util.concurrent.atomic.AtomicInteger();
            test.runOnMainSync(()->page[0].setListener((WebSourceView.Listener)java.lang.reflect.Proxy.newProxyInstance(
                    WebSourceView.Listener.class.getClassLoader(),new Class[]{WebSourceView.Listener.class},(proxy,method,args)->{
                        if(method.getName().equals("onStreamDiscovered")&&String.valueOf(args[1]).contains("/covered.m3u8"))coveredStreams.incrementAndGet();return null;
                    })));
            for(String style:new String[]{"status","black"}){
                int expected=coveredStreams.get()+1;
                test.runOnMainSync(()->{
                    page[0].closePage();page[0].applyPageCoverStyle(style);page[0].applyPageCover(true);
                    page[0].open(106,url+"cover-"+style,
                            "setTimeout(function(){requestAnimationFrame(function(){var x=new XMLHttpRequest();x.open('GET','/covered.m3u8',true);x.onload=function(){document.title='covered-"+style+"';};x.send();});},500);",false,"","");
                });
                awaitTitle(test,page[0],"covered-"+style);
                for(int i=0;i<50&&coveredStreams.get()<expected;i++)SystemClock.sleep(100);
                if(coveredStreams.get()!=expected)throw new AssertionError("Cover blocked sniffing: "+style);
            }
            test.runOnMainSync(()->{page[0].applyPageCover(false);page[0].closePage();});
            String[] defaultAgent={""};
            if(android.os.Build.VERSION.SDK_INT>=17)test.runOnMainSync(()->defaultAgent[0]=android.webkit.WebSettings.getDefaultUserAgent(activity));
            return android.os.Build.VERSION.SDK_INT>=26
                    ?"PASS webview/jscode/ujs: UTF-8 Base64 decoding, executes once, duplicate callback guarded, actual DOM click/XHR, channel cleanup; cover=status+black DOM/timer/RAF/XHR/sniff PASS; firstPageMs="+firstPageMs+" clickRequestMs="+(clickAt.get()-clickStart)+" defaultUA="+defaultAgent[0]+"\n"
                    :"PASS webview/jscode/ujs: UTF-8 Base64 decoding, executes once, legacy IPC lifecycle, actual DOM click/XHR, no query leak, channel cleanup; cover=status+black DOM/timer/RAF/XHR/sniff PASS; firstPageMs="+firstPageMs+" clickRequestMs="+(clickAt.get()-clickStart)+" defaultUA="+defaultAgent[0]+"\n";
        } finally {
            server.close();
            test.runOnMainSync(() -> {
                if (page[0] != null) { page[0].destroyPage(); ((ViewGroup) page[0].getParent()).removeView(page[0]); }
            });
        }
    }
    private static void checkUjsParsing() throws Exception {
        String script="window.value='中文 >>>';";
        String encoded=android.util.Base64.encodeToString(script.getBytes("UTF-8"),android.util.Base64.NO_WRAP);
        for(String value:new String[]{encoded,java.net.URLEncoder.encode(encoded,"UTF-8"),
                encoded.replace('+','-').replace('/','_').replace("=","")}) {
            WebSourceOptions parsed=WebSourceOptions.parse("https://example.test/live?x=%2B&ujs="+value+"&y=2#tab","");
            if(!parsed.url.equals("https://example.test/live?x=%2B&y=2#tab")||!parsed.script.equals(script))
                throw new AssertionError("ujs decode or URL preservation");
        }
        WebSourceOptions invalid=WebSourceOptions.parse("https://example.test/?ujs=%GG", "explicit();");
        if(!invalid.script.equals("explicit();")||!invalid.url.equals("https://example.test/"))
            throw new AssertionError("Malformed ujs must preserve explicit script and page");
        String hash="https://example.test/#/route?ujs="+encoded;
        if(!WebSourceOptions.parse(hash,"").url.equals(hash))throw new AssertionError("Fragment belongs to the site");
    }
    private static Field member(Class<?> type) throws Exception {
        for (Field f : WebSourceView.class.getDeclaredFields()) {
            if (type.isAssignableFrom(f.getType())) { f.setAccessible(true); return f; }
        }
        throw new NoSuchFieldException(type.getName());
    }
    private static void awaitTitle(Instrumentation test, WebSourceView page, String expected) throws Exception {
        String[] title = {null};
        for (int i = 0; i < 100; i++) {
            test.runOnMainSync(() -> {
                try { title[0] = page.currentPageTitle(); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            if (expected.equals(title[0])) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError("page title: expected " + expected + ", got " + title[0]);
    }
}
