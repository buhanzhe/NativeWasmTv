package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Isolated debug-package regression checks; never run against production app data. */
public final class PerformanceRegressionInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }

    private static Object field(Class<?> type, Object instance, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private void dnsSave() throws Exception {
        NetworkClient.initialize(getTargetContext());
        final String original = NetworkClient.getDnsMode();
        final ServerSocket server = new ServerSocket(0);
        final CountDownLatch accepted = new CountDownLatch(1), release = new CountDownLatch(1),
                completed = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread peer = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Socket socket = server.accept();
                    try {
                        socket.setSoTimeout(5000);
                        socket.getInputStream().read();
                        accepted.countDown();
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test peer timed out");
                        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n"
                                + "Connection: close\r\n\r\nok").getBytes("UTF-8"));
                    } finally { socket.close(); }
                } catch (Throwable error) { failure.compareAndSet(null, error); }
            }
        }, "audit-http-peer");
        peer.start();
        try {
            String url = "http://127.0.0.1:" + server.getLocalPort() + "/";
            NetworkClient.open(new URL(url)).disconnect();
            OkHttpClient client = (OkHttpClient) field(NetworkClient.class, null, "client");
            final Call call = client.newCall(new Request.Builder().url(url).build());
            call.enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException error) {
                    failure.compareAndSet(null, error); completed.countDown();
                }
                @Override public void onResponse(Call call, Response response) throws IOException {
                    try { check("ok".equals(response.body().string()), "Active response corrupted"); }
                    catch (Throwable error) { failure.compareAndSet(null, error); }
                    finally { response.close(); completed.countDown(); }
                }
            });
            check(accepted.await(5, TimeUnit.SECONDS), "HTTP request did not start");
            for (int i = 0; i < 100; i++) NetworkClient.setDnsMode(original);
            check(!call.isCanceled(), "Unchanged DNS cancelled playback request");
            check(field(NetworkClient.class, null, "client") == client, "Unchanged DNS recreated HTTP pool");
            release.countDown();
            check(completed.await(5, TimeUnit.SECONDS), "Active request did not finish");
            if (failure.get() != null) throw new AssertionError(failure.get());
            String other = NetworkClient.DNS_SYSTEM.equals(original) ? NetworkClient.DNS_ALI : NetworkClient.DNS_SYSTEM;
            NetworkClient.setDnsMode(other);
            check(other.equals(NetworkClient.getDnsMode()), "DNS change not saved");
            check(field(NetworkClient.class, null, "client") == null, "Changed DNS did not invalidate pool");
        } finally {
            release.countDown(); server.close(); peer.join(1000);
            NetworkClient.setDnsMode(original);
        }
    }

    private long playlistLifecycle() throws Exception {
        long start = SystemClock.elapsedRealtime();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable error) {
                if (thread.getName().equals("ku9-live-playlist")) failure.compareAndSet(null, error);
                else if (old != null) old.uncaughtException(thread, error);
            }
        });
        Ku9PlaylistServer server = new Ku9PlaylistServer();
        try {
            server.start(); server.update("#EXTM3U\n#EXT-X-ENDLIST\n");
            URL url = new URL(server.url());
            Socket stalled = new Socket(url.getHost(), url.getPort());
            try {
                stalled.getOutputStream().write("GET /live.m3u8 HTTP/1.1\r\n".getBytes("UTF-8"));
                long until = SystemClock.elapsedRealtime() + 2000;
                while (field(Ku9PlaylistServer.class, server, "activeClient") == null
                        && SystemClock.elapsedRealtime() < until) SystemClock.sleep(10);
                check(field(Ku9PlaylistServer.class, server, "activeClient") != null, "Stalled client not accepted");
                server.close(); stalled.setSoTimeout(500);
                try { check(stalled.getInputStream().read() == -1, "Closed server sent stale data"); }
                catch (SocketTimeoutException error) { throw new AssertionError("Old channel connection lingered", error); }
                catch (SocketException expected) { }
            } finally { stalled.close(); }
            for (int i = 0; i < 100; i++) { server.start(); server.close(); }
            server.start(); server.update("#EXTM3U\n#EXT-X-ENDLIST\n");
            check(Ku9HttpClient.getText(server.url(), null, 4096).startsWith("#EXTM3U"), "Restarted server unusable");
            SystemClock.sleep(50);
            if (failure.get() != null) throw new AssertionError(failure.get());
        } finally { server.close(); Thread.setDefaultUncaughtExceptionHandler(old); }
        return SystemClock.elapsedRealtime() - start;
    }

    private void pluginProbes() throws Exception {
        final int[] reads = {0};
        Context isolated = new ContextWrapper(getTargetContext()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() {
                reads[0]++;
                return new File(super.getFilesDir(), "audit-missing-" + android.os.Process.myPid());
            }
        };
        CjsPluginRuntime.initialize(isolated);
        for (int i = 0; i < 100; i++) check(!CjsPluginRuntime.hasCatalog(), "Unexpected catalog");
        check(reads[0] == 1, "Missing catalog repeatedly accessed disk: " + reads[0]);
        JSONObject entry = new JSONObject().put("id", "audit").put("module", "audit")
                .put("config", "https://example.test/audit.json")
                .put("qualities", new JSONObject().put("high", "high"))
                .put("hosts", new JSONArray().put("example.test"));
        Method publish = CjsPluginRuntime.class.getDeclaredMethod("useCatalog", JSONObject.class);
        publish.setAccessible(true);
        publish.invoke(null, new JSONObject().put("sites", new JSONArray().put(entry)));
        check(CjsPluginRuntime.hasCatalog(), "Downloaded catalog did not replace cached miss");
        Object site = ((Map<?, ?>) field(CjsPluginRuntime.class, null, "states")).get("audit");
        Field runtime = site.getClass().getDeclaredField("runtime"); runtime.setAccessible(true);
        JSONObject metadata = new JSONObject().put("entry", "main").put("jsApi", "ku9")
                .put("scripts", new JSONObject().put("main", "function main(){return 'https://example.test/live.m3u8';}"));
        runtime.set(site, metadata);
        for (int i = 0; i < 100; i++) check(CjsPluginRuntime.supportsSite("https://example.test/channel"), "Site not recognized");
        check(Boolean.FALSE.equals(field(site.getClass(), site, "used")), "Capability probe pinned script version");
        check(CjsPluginRuntime.siteForUrl("https://example.test/channel") != null, "Site execution lookup failed");
        check(Boolean.TRUE.equals(field(site.getClass(), site, "used")), "Execution did not pin script version");
        metadata.put("entry", "missing");
        check(!CjsPluginRuntime.supportsSite("https://example.test/channel"), "Missing entry advertised as supported");
    }

    @Override public void onStart() {
        Bundle results = new Bundle();
        try {
            dnsSave();
            long lifecycleMs = playlistLifecycle();
            pluginProbes();
            results.putString("stream", "PASS: 100 unchanged DNS saves preserve in-flight HTTP and connection pool; DNS changes apply; "
                    + "stalled playlist closes within 500 ms, 100 restarts without uncaught errors (suite " + lifecycleMs + " ms); "
                    + "100 missing-catalog probes read disk once; 100 capability probes do not pin scripts; execution pins scripts.\n");
            finish(-1, results);
        } catch (Throwable error) {
            results.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error));
            finish(0, results);
        }
    }
}
