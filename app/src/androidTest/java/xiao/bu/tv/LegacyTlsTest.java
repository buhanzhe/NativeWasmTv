package xiao.bu.tv;

import android.content.Context;
import android.os.SystemClock;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509TrustManager;

/** Run explicitly with am instrument -w -e tls true ...QuickJsInstrumentation. */
final class LegacyTlsTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static String githubDownload(Context context) throws Exception {
        NetworkClient.initialize(context);
        long started = SystemClock.elapsedRealtime();
        String manifestUrl = "https://raw.githubusercontent.com/TvWasm/cjs/main/sites/tv.gxtv.cn/plugin.json";
        String url = GithubProxy.apply(manifestUrl);
        check(url.equals("https://gh-proxy.com/" + manifestUrl), "Default HTTPS accelerator");
        org.json.JSONObject manifest = new org.json.JSONObject(Ku9HttpClient.getText(url, null, 1024 * 1024));
        org.json.JSONArray files = manifest.getJSONArray("files");
        int downloaded = 0, total = 0;
        for (int i = 0; i < files.length(); i++) {
            org.json.JSONObject file = files.getJSONObject(i);
            if (!"all".equals(file.getString("abi")) && !BuildConfig.CJS_PLUGIN_ABI.equals(file.getString("abi"))) continue;
            HttpURLConnection request = NetworkClient.open(new URL(GithubProxy.apply(file.getString("url"))));
            try {
                check(request.getResponseCode() == 200, "Plugin download HTTP status");
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                java.io.InputStream input = request.getInputStream();
                try {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        check(total < 16 * 1024 * 1024, "Plugin download size limit");
                        digest.update(buffer, 0, count);
                    }
                } finally { input.close(); }
                StringBuilder hash = new StringBuilder();
                for (byte value : digest.digest()) hash.append(String.format(java.util.Locale.US, "%02x", value & 255));
                check(file.getString("sha256").equals(hash.toString()), "Plugin integrity: " + file.getString("name"));
                downloaded++;
            } finally { request.disconnect(); }
        }
        check(downloaded == 2, "Runtime and native plugin were not both downloaded");
        return "PASS GitHub HTTPS: manifest, runtime and " + BuildConfig.CJS_PLUGIN_ABI
                + " SO verified; " + total + " bytes in " + (SystemClock.elapsedRealtime() - started) + " ms\n";
    }
    static String run(Context context) throws Exception {
        NetworkClient.initialize(context);
        long start = SystemClock.elapsedRealtime();
        String endpoint = "https://api2019.gxtv.cn/memberApi/channel/channelList";
        for (int i = 0; i < 3; i++) {
            HttpURLConnection request = NetworkClient.open(new URL(endpoint));
            try {
                request.setRequestMethod("POST"); request.setDoOutput(true);
                request.getOutputStream().write("pageNo=1&pageSize=1000".getBytes("UTF-8"));
                request.getOutputStream().close();
                check(request.getResponseCode() == 200, "Gxtv HTTPS status");
                check(Ku9HttpClient.readUtf8(request.getInputStream(), 1024 * 1024).contains("rows"), "Gxtv HTTPS body");
            } finally { request.disconnect(); }
        }
        long networkMs = SystemClock.elapsedRealtime() - start;
        // The native engine must not bypass the Java certificate trust policy.
        X509TrustManager reject = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a) throws CertificateException { throw new CertificateException("reject"); }
            @Override public void checkServerTrusted(X509Certificate[] c, String a) throws CertificateException { throw new CertificateException("expected rejection"); }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLSocket tls = (SSLSocket) new LegacyTlsSocket.Factory(reject).createSocket("api2019.gxtv.cn", 443);
        try {
            tls.setSoTimeout(5000);
            boolean rejected = false;
            try { tls.startHandshake(); } catch (SSLException expected) { rejected = true; }
            check(rejected, "Java trust manager bypassed");
        } finally { tls.close(); }
        stalledPeer(false);
        stalledPeer(true);
        return "PASS TLS: 3 Gxtv HTTPS requests in " + networkMs
                + " ms; certificate rejection, handshake timeout, concurrent close cancellation\n";
    }

    private static void stalledPeer(final boolean cancel) throws Exception {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final CountDownLatch hello = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread peer = new Thread(new Runnable() { @Override public void run() {
            try {
                Socket socket = server.accept();
                try { socket.getInputStream().read(); hello.countDown(); release.await(5, TimeUnit.SECONDS); }
                finally { socket.close(); }
            } catch (Exception ignored) { }
        }}, "tls-stalled-peer");
        peer.start();
        final SSLSocket tls = (SSLSocket) new LegacyTlsSocket.Factory(TlsCompat.trustManager())
                .createSocket("127.0.0.1", server.getLocalPort());
        tls.setSoTimeout(cancel ? 5000 : 150);
        final Throwable[] result = new Throwable[1];
        Thread client = new Thread(new Runnable() { @Override public void run() {
            try { tls.startHandshake(); } catch (Throwable error) { result[0] = error; }
        }}, "tls-stalled-client");
        try {
            client.start();
            check(hello.await(2, TimeUnit.SECONDS), "TLS ClientHello missing");
            long start = SystemClock.elapsedRealtime();
            if (cancel) tls.close();
            client.join(2000);
            check(!client.isAlive() && SystemClock.elapsedRealtime() - start < 2000, "TLS I/O did not stop promptly");
            check(cancel ? result[0] instanceof IOException : result[0] instanceof SocketTimeoutException,
                    "TLS exception was lost: " + result[0]);
        } finally {
            release.countDown(); tls.close(); server.close(); peer.join(2000); client.join(2000);
        }
    }
}
