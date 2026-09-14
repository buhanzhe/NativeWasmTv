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
        ServerSocket server = new ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"));
        Thread responder = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = input.readLine()) != null && line.length() > 0) { }
                    byte[] body = "<!doctype html><html><head><title>plain</title></head><body>Ku9 lifecycle</body></html>".getBytes("UTF-8");
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                    socket.getOutputStream().write(body);
                } catch (Exception ignored) { }
            }
        }, "ku9-page-test-server");
        responder.setDaemon(true); responder.start();
        WebSourceView[] page = {null};
        String url = "http://127.0.0.1:" + server.getLocalPort() + "/";
        try {
            test.runOnMainSync(() -> {
                page[0] = new WebSourceView(activity);
                ((ViewGroup) activity.findViewById(android.R.id.content)).addView(page[0], new ViewGroup.LayoutParams(320, 180));
                page[0].open(100, url, "window.times=(window.times||0)+1;document.title='injected-'+window.times;");
            });
            awaitTitle(test, page[0], "injected-1");
            Field browser = member(WebView.class); browser.setAccessible(true);
            Field client = member(WebViewClient.class); client.setAccessible(true);
            test.runOnMainSync(() -> {
                try { ((WebViewClient) client.get(page[0])).onPageFinished((WebView) browser.get(page[0]), url); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            SystemClock.sleep(200);
            awaitTitle(test, page[0], "injected-1");
            test.runOnMainSync(() -> { page[0].closePage(); page[0].open(101, url + "next", ""); });
            awaitTitle(test, page[0], "plain");
            SystemClock.sleep(200);
            awaitTitle(test, page[0], "plain");
            return "PASS webview/jscode: executes once, duplicate callback guarded, next channel has no script\n";
        } finally {
            server.close();
            test.runOnMainSync(() -> {
                if (page[0] != null) { page[0].destroyPage(); ((ViewGroup) page[0].getParent()).removeView(page[0]); }
            });
        }
    }
    private static Field member(Class<?> type) throws Exception {
        for (Field f : WebSourceView.class.getDeclaredFields()) {
            if (type.isAssignableFrom(f.getType())) { f.setAccessible(true); return f; }
        }
        throw new NoSuchFieldException(type.getName());
    }
    private static void awaitTitle(Instrumentation test, WebSourceView page, String expected) throws Exception {
        Field browser = member(WebView.class); browser.setAccessible(true);
        String[] title = {null};
        for (int i = 0; i < 100; i++) {
            test.runOnMainSync(() -> {
                try { WebView view = (WebView) browser.get(page); title[0] = view == null ? null : view.getTitle(); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            if (expected.equals(title[0])) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError("page title: expected " + expected + ", got " + title[0]);
    }
}
