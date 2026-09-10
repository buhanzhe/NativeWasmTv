package xiao.bu.tv;

import android.app.Instrumentation;
import android.os.Bundle;
import java.io.*;
import java.net.*;
import java.lang.reflect.Method;

/** HTTP regressions exercise the real updater without launching playback or installation. */
public final class UpdateManifestInstrumentation extends Instrumentation {
    private ServerSocket server;
    private volatile int primaryStatus = 200, legacyStatus = 200, legacyReads;
    private volatile String primaryBody = "{\"versionCode\":7}";
    private static final String LEGACY = "{\"versionCode\":6}";

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private void pass(String message) {
        Bundle out = new Bundle(); out.putString("stream", "PASS " + message + "\n"); sendStatus(0, out);
    }
    @Override public void onStart() {
        Bundle result = new Bundle(); int status = -1;
        try {
            server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            Thread fixture = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(2000);
                        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                        String request = in.readLine(), line;
                        if (request == null) continue;
                        while ((line = in.readLine()) != null && !line.isEmpty()) { }
                        boolean legacy = request.contains("/version.json?");
                        if (legacy) legacyReads++;
                        int code = legacy ? legacyStatus : primaryStatus;
                        byte[] body = (legacy ? LEGACY : primaryBody).getBytes("UTF-8");
                        OutputStream out = socket.getOutputStream();
                        out.write(("HTTP/1.1 " + code + " Test\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: "
                                + body.length + "\r\n\r\n").getBytes("UTF-8"));
                        out.write(body); out.flush();
                    } catch (IOException ignored) { }
                }
            }, "update-fixture");
            fixture.start();
            String base = "http://127.0.0.1:" + server.getLocalPort();
            AutoUpdater updater = new AutoUpdater(null);
            check(updater.readManifest(base + "/version-lite.json", base + "/version.json").equals(primaryBody), "Lite result");
            check(legacyReads == 0, "Lite must not request legacy");
            primaryStatus = 404;
            check(updater.readManifest(base + "/version-lite.json", base + "/version.json").equals(LEGACY), "Legacy fallback");
            check(legacyReads == 1, "Single fallback");
            pass("missing lightweight metadata falls back once; present metadata needs one request");
            for (int code : new int[]{403, 500}) {
                primaryStatus = code;
                try { updater.readManifest(base + "/version-lite.json", base + "/version.json"); throw new AssertionError("Expected HTTP error"); }
                catch (IOException expected) { check(expected.getMessage().contains(String.valueOf(code)), "Preserve HTTP error"); }
            }
            check(legacyReads == 1, "No fallback on network/server errors");
            primaryStatus = 404;
            try { updater.readManifest(base + "/important.json", null); throw new AssertionError("Expected missing important metadata"); }
            catch (IOException expected) { }
            legacyStatus = 404;
            try { updater.readManifest(base + "/version-lite.json", base + "/version.json"); throw new AssertionError("Expected both missing"); }
            catch (IOException expected) { }
            pass("403/500 and both-missing remain errors; important checks have no fallback");
            Method load = AutoUpdater.class.getDeclaredMethod("loadUpdateInfo", String.class, boolean.class);
            load.setAccessible(true);
            Object info = load.invoke(updater, "https://github.com/buhanzhe/NativeWasmTv/releases/latest/download/version-lite.json", true);
            check(info != null, "Live metadata parsed");
            pass("live GitHub accelerated release check and architecture-specific metadata parsing");
            result.putString("stream", "ALL UPDATE MANIFEST CHECKS PASSED\n");
        } catch (Throwable error) { status = 0; result.putString("stream", android.util.Log.getStackTraceString(error)); }
        finally { try { if (server != null) server.close(); } catch (IOException ignored) { } }
        finish(status, result);
    }
}
