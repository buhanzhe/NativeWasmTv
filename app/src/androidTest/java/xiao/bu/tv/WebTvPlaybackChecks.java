package xiao.bu.tv;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.SystemClock;
import android.view.ViewGroup;
import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real WebView/legacy IPC, with deterministic local TV controls and media requests. */
final class WebTvPlaybackChecks {
    static String run(Instrumentation test, Activity activity) throws Exception {
        AtomicInteger clicks = new AtomicInteger(), sniffed = new AtomicInteger();
        ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        Thread responder = new Thread(() -> {
            while (!server.isClosed()) try (Socket socket = server.accept()) {
                socket.setSoTimeout(2000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                String request = reader.readLine(), line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                boolean manifest = request != null && request.startsWith("GET /live.m3u8 ");
                if (manifest) clicks.incrementAndGet();
                String html = "<!doctype html><html><head><title>waiting</title></head><body>"
                        + "<div class='video-js' style='width:320px;height:180px;background:#123'><video style='width:320px;height:180px'></video>"
                        + "<button class='vjs-big-play-button' onclick=\"this.disabled=true;document.title='clicked';var x=new XMLHttpRequest();x.open('GET','/live.m3u8');x.send();\">播放</button></div></body></html>";
                byte[] body = (manifest ? "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\nseg.ts\n" : html).getBytes("UTF-8");
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: " + (manifest ? "application/vnd.apple.mpegurl" : "text/html; charset=utf-8")
                        + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                socket.getOutputStream().write(body);
            } catch (Exception ignored) { }
        }, "tv-playback-fixture"); responder.setDaemon(true); responder.start();
        WebSourceView[] page = {null};
        String url = "http://127.0.0.1:" + server.getLocalPort() + "/tv";
        try {
            test.runOnMainSync(() -> {
                page[0] = new WebSourceView(activity);
                page[0].setListener((WebSourceView.Listener) java.lang.reflect.Proxy.newProxyInstance(
                        WebSourceView.Listener.class.getClassLoader(), new Class[]{WebSourceView.Listener.class}, (proxy, method, args) -> {
                            if (method.getName().equals("onStreamDiscovered")) sniffed.incrementAndGet(); return null;
                        }));
                ((ViewGroup) activity.findViewById(android.R.id.content)).addView(page[0], new ViewGroup.LayoutParams(-1, -1));
                page[0].applyAutoPlay(false); page[0].open(200, url, "", false, "自动播放测试", "本地合成测试");
            });
            title(test, page[0], "waiting"); Thread.sleep(2000);
            if (clicks.get() != 0 || sniffed.get() != 0) throw new AssertionError("Auto-play OFF triggered a click/media request");
            long earlyDeadline = SystemClock.elapsedRealtime() + 4500;
            boolean[] earlyFullscreen = {false};
            while (!earlyFullscreen[0] && SystemClock.elapsedRealtime() < earlyDeadline) {
                test.runOnMainSync(() -> earlyFullscreen[0] = page[0].isInBrowserFullscreen()
                        && page[0].currentWebMediaState().optBoolean("tvFullscreen")); Thread.sleep(100);
            }
            if (!earlyFullscreen[0]) throw new AssertionError("Player did not automatically fill the page before playback/sniffing");
            test.runOnMainSync(() -> page[0].applyAutoPlay(true));
            // On slow KitKat startup, fullscreen can overwrite the fixture's
            // transient "clicked" title before the next IPC heartbeat. Verify
            // the actual request/callback rather than a 200 ms title window.
            long until = SystemClock.elapsedRealtime() + 18000;
            while ((clicks.get() == 0 || sniffed.get() == 0) && SystemClock.elapsedRealtime() < until) Thread.sleep(100);
            if (clicks.get() != 1 || sniffed.get() != 1) throw new AssertionError("Built-in play click/sniff count " + clicks + "/" + sniffed);
            until = SystemClock.elapsedRealtime() + 18000;
            boolean[] fullscreen = {false};
            while (!fullscreen[0] && SystemClock.elapsedRealtime() < until) {
                test.runOnMainSync(() -> fullscreen[0] = page[0].isInBrowserFullscreen()
                        && page[0].currentWebMediaState().optBoolean("tvFullscreen")); Thread.sleep(100);
            }
            if (!fullscreen[0]) throw new AssertionError("CSS fallback did not hide native browser chrome");
            test.runOnMainSync(() -> { page[0].closePage(); page[0].applyAutoPlay(false); page[0].open(201, url + "-off", "", false, "", ""); });
            title(test, page[0], "waiting"); Thread.sleep(2000);
            if (clicks.get() != 1) throw new AssertionError("Autoplay OFF lost across navigation/IPC");
            return "PASS built-in TV helper: player automatically fullscreen before playback/sniffing, native browser chrome hidden; autoplay OFF no clicks/requests; ON actual DOM play click + XHR + sniff callback exactly once while fullscreen; OFF retained across new page and legacy IPC. Synthetic fixture, not real broadcast decoding.\n";
        } catch (Throwable error) {
            String[] sample = {""};
            test.runOnMainSync(() -> sample[0] = page[0].currentWebMediaState().toString());
            throw new AssertionError(error.getMessage() + "; native snapshot " + sample[0], error);
        } finally {
            server.close();
            test.runOnMainSync(() -> { if (page[0] != null) { page[0].destroyPage(); ((ViewGroup) page[0].getParent()).removeView(page[0]); } });
        }
    }
    private static void title(Instrumentation test, WebSourceView page, String value) throws Exception {
        long until = SystemClock.elapsedRealtime() + 18000;
        while (SystemClock.elapsedRealtime() < until) {
            String[] title = {""}; test.runOnMainSync(() -> title[0] = page.currentPageTitle());
            if (value.equals(title[0])) return;
            Thread.sleep(100);
        }
        throw new AssertionError("TV helper title timed out: " + value);
    }
}
