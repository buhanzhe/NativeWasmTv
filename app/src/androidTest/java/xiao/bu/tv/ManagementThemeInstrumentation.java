package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.webkit.WebView;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Exercises the real management WebView against bundled pages on an isolated origin. */
public final class ManagementThemeInstrumentation extends Instrumentation {
    private ManagementActivity activity;
    private ServerSocket server;
    private Thread peer;
    private String origin;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    private static byte[] bytes(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = input.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private String shell(String command) throws IOException {
        ParcelFileDescriptor fd = getUiAutomation().executeShellCommand(command);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            return new String(bytes(in), "UTF-8").trim();
        }
    }

    private void serve() throws IOException {
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        origin = "http://127.0.0.1:" + server.getLocalPort();
        peer = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                    String line = in.readLine();
                    if (line == null) continue;
                    String path = line.split(" ")[1].split("\\?")[0];
                    while ((line = in.readLine()) != null && !line.isEmpty()) { }
                    byte[] body = "{}".getBytes("UTF-8");
                    String type = "application/json";
                    if (ControlSite.contains(path)) {
                        try (InputStream asset = getTargetContext().getAssets().open(ControlSite.assetPath(path))) { body = bytes(asset); }
                        type = ControlSite.contentType(path);
                    }
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nConnection: close\r\nCache-Control: no-store\r\nContent-Type: " + type
                            + "\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes("UTF-8"));
                    out.write(body); out.flush();
                } catch (Exception error) { if (server.isClosed()) return; }
            }
        }, "theme-fixture-http");
        peer.start();
    }

    private WebView web() {
        try { Field field = ManagementActivity.class.getDeclaredField("webView"); field.setAccessible(true); return (WebView)field.get(activity); }
        catch (Exception error) { throw new RuntimeException(error); }
    }

    private String js(String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        String[] result = new String[1];
        runOnMainSync(() -> web().evaluateJavascript(script, value -> { result[0] = value; done.countDown(); }));
        if (!done.await(5, TimeUnit.SECONDS)) throw new AssertionError("JavaScript timed out");
        return result[0];
    }

    private void theme(String expected) throws Exception {
        long end = SystemClock.uptimeMillis() + 8000L;
        String value;
        do {
            value = js("document.documentElement.getAttribute('data-theme')");
            if (("\"" + expected + "\"").equals(value)) {
                if ("dark".equals(expected) && !"\"rgb(17, 17, 19)\"".equals(js("getComputedStyle(document.body).backgroundColor"))) {
                    throw new AssertionError("Dark CSS not applied");
                }
                return;
            }
            SystemClock.sleep(150);
        } while (SystemClock.uptimeMillis() < end);
        throw new AssertionError("Expected " + expected + ", got " + value + "; " + js("JSON.stringify({query:matchMedia('(prefers-color-scheme:dark)').matches,native:NtvDevice.isSystemDark()})"));
    }

    private void screenshot(String name) throws Exception {
        // Let the OS night-mode transition finish before capturing the compositor.
        SystemClock.sleep(1200);
        Bitmap image = getUiAutomation().takeScreenshot();
        try (FileOutputStream out = new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null), name))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally { image.recycle(); }
    }

    private void pass(String message) { Bundle out = new Bundle(); out.putString("stream", "PASS " + message + "\n"); sendStatus(0, out); }

    private ManagementActivity openPage() throws Exception {
        ActivityMonitor monitor = addMonitor(ManagementActivity.class.getName(), null, false);
        try {
            getTargetContext().startActivity(new Intent(getTargetContext(), ManagementActivity.class)
                    .putExtra(ManagementActivity.EXTRA_URL, origin + "/")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            ManagementActivity page = (ManagementActivity)waitForMonitorWithTimeout(monitor, 10000);
            if (page == null) throw new AssertionError("Management activity did not open within 10 seconds");
            return page;
        } finally { removeMonitor(monitor); }
    }

    @Override public void onStart() {
        Bundle result = new Bundle(); String original = null; int status = -1;
        try {
            original = shell("cmd uimode night").replace("Night mode: ", "").trim();
            if (!original.matches("yes|no|auto|custom")) throw new AssertionError("Unknown original night mode: " + original);
            serve(); shell("cmd uimode night no");
            SystemClock.sleep(500);
            activity = openPage();
            theme("light");
            if (!"true".equals(js("document.getElementById('pageTheme')===null"))) throw new AssertionError("Appearance still on home page: "
                    + js("JSON.stringify({url:location.href,element:document.getElementById('pageTheme')&&document.getElementById('pageTheme').outerHTML})"));
            pass("cold open follows system light; " + js("matchMedia('(prefers-color-scheme:dark)').matches"));
            js("window.themeDocumentToken='same-page';setPageTheme('system')");
            shell("cmd uimode night yes"); theme("dark");
            if (!"\"same-page\"".equals(js("window.themeDocumentToken"))) throw new AssertionError("System theme change reloaded the page");
            screenshot("management-system-dark.png");
            pass("system dark updates the existing page without reload; " + js("JSON.stringify({query:matchMedia('(prefers-color-scheme:dark)').matches,native:NtvDevice.isSystemDark()})"));
            js("setPageTheme('light')"); theme("light");
            shell("cmd uimode night no"); shell("cmd uimode night yes"); theme("light");
            js("setPageTheme('dark')"); shell("cmd uimode night no"); theme("dark");
            pass("manual light and dark choices survive system changes");
            js("setPageTheme('system')"); theme("light");
            screenshot("management-system-light.png");
            shell("cmd uimode night yes");
            runOnMainSync(() -> web().loadUrl(origin + "/pages/media.html"));
            theme("dark");
            pass("secondary media page follows the same system setting");
            runOnMainSync(() -> web().loadUrl(origin + "/pages/advanced.html"));
            long ready = SystemClock.uptimeMillis() + 5000;
            while (!"\"system\"".equals(js("document.getElementById('pageTheme')&&document.getElementById('pageTheme').value"))) {
                if (SystemClock.uptimeMillis() > ready) throw new AssertionError("Advanced appearance entry missing/default not system");
                SystemClock.sleep(100);
            }
            theme("dark");
            if (!"\"rgb(17, 17, 19)\"".equals(js("getComputedStyle(document.querySelector('.channel-config-page .top')).backgroundColor"))) {
                throw new AssertionError("Advanced header still has a light background");
            }
            screenshot("advanced-system-dark.png");
            shell("cmd uimode night no"); theme("light");
            if (!"true".equals(js("location.pathname==='/pages/advanced.html'&&!!document.getElementById('pageTheme')"))) {
                throw new AssertionError("Theme switch navigated away from Advanced");
            }
            screenshot("advanced-system-light.png");
            shell("cmd uimode night yes"); theme("dark");
            pass("appearance lives in Advanced, defaults to system, updates while visible");
            runOnMainSync(() -> activity.finish()); activity = null;
            activity = openPage();
            theme("dark"); pass("cold reopen in system dark");
            result.putString("stream", "ALL SYSTEM THEME CHECKS PASSED\n");
        } catch (Throwable error) { status = 0; result.putString("stream", android.util.Log.getStackTraceString(error)); }
        finally {
            try { if (original != null && original.matches("yes|no|auto|custom")) shell("cmd uimode night " + original); } catch (Exception ignored) { }
            if (activity != null) runOnMainSync(() -> activity.finish());
            try { if (server != null) server.close(); if (peer != null) peer.join(2500); } catch (Exception ignored) { }
        }
        finish(status, result);
    }
}
