package xiao.bu.tv;

import java.io.*;
import java.net.*;
import java.util.*;

public final class GithubConnectionTest {
    static final String RAW = "https://raw.githubusercontent.com/a/b/main/list.txt?token=a%2Bb&x=1,2";
    static final class Fake extends HttpURLConnection {
        final int status; boolean closed;
        Fake(URL url, int status) { super(url); this.status = status; }
        public int getResponseCode() throws IOException { if (status < 0) throw new SocketTimeoutException("offline"); return status; }
        public String getHeaderField(String name) { return "Content-Length".equalsIgnoreCase(name) ? "4" : null; }
        public InputStream getInputStream() { return new ByteArrayInputStream(new byte[]{1,2,3,4}); }
        public void connect() { }
        public void disconnect() { closed = true; }
        public boolean usingProxy() { return false; }
    }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void reset() { GithubProxy.setBaseUrl(""); GithubProxy.setEnabled(false); GithubProxy.setEnabled(true); }
    public static void main(String[] args) throws Exception {
        for (int failure : new int[]{500,403,429,-1}) {
            reset(); List<Fake> connections = new ArrayList<Fake>();
            GithubConnection connection = new GithubConnection(new URL(GithubProxy.apply(RAW)), url -> {
                Fake fake = new Fake(url, url.toString().startsWith(GithubProxy.DEFAULT_BASE_URL) ? failure : 200);
                connections.add(fake); return fake;
            });
            connection.setRequestProperty("Range", "bytes=10-");
            connection.setRequestProperty("User-Agent", "test-agent");
            connection.setConnectTimeout(12000); connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(false);
            check(connection.getResponseCode() == 200, "Failover response");
            check(connections.size() == 2 && connections.get(0).closed, "Previous attempt leaked");
            Fake success = connections.get(1);
            check(success.getURL().toString().equals(GithubProxy.BACKUP_BASE_URL + RAW), "Signed URL changed");
            check("bytes=10-".equals(success.getRequestProperty("Range")), "Range lost");
            check(success.getConnectTimeout() == 4000 && success.getReadTimeout() == 8000, "Attempt timeout unbounded");
            check(!success.getInstanceFollowRedirects(), "Redirect policy lost");
            check(connection.getContentLength() == 4 && connection.getInputStream().read() == 1, "Body/header lost");
            check(GithubProxy.apply(RAW).equals(GithubProxy.BACKUP_BASE_URL + RAW), "Healthy route was not remembered");
            connection.disconnect(); check(success.closed, "Final connection leaked");
        }
        reset(); List<Fake> attempts = new ArrayList<Fake>();
        GithubConnection missing = new GithubConnection(new URL(GithubProxy.apply(RAW)), url -> { Fake f = new Fake(url,404);attempts.add(f);return f; });
        check(missing.getResponseCode() == 404 && attempts.size() == 1, "404 must not cycle accelerators");missing.disconnect();
        reset(); attempts.clear();
        GithubConnection failed = new GithubConnection(new URL(GithubProxy.apply(RAW)), url -> {Fake f=new Fake(url,503);attempts.add(f);return f;});
        check(failed.getResponseCode() == 503 && attempts.size() == 5, "All-failed attempts not bounded");failed.disconnect();
        reset(); GithubProxy.setEnabled(false); attempts.clear();
        GithubConnection direct = new GithubConnection(new URL(GithubProxy.DEFAULT_BASE_URL + RAW), url -> {Fake f=new Fake(url,500);attempts.add(f);return f;});
        check(direct.getResponseCode()==500 && attempts.size()==1 && attempts.get(0).getURL().toString().equals(RAW),"Direct used an accelerator");direct.disconnect();
        reset(); GithubProxy.setBaseUrl("https://custom.example/proxy/");
        String[] routes=GithubProxy.candidates(RAW);
        check(routes.length==6 && routes[0].startsWith("https://custom.example/proxy/") && routes[1].startsWith(GithubProxy.DEFAULT_BASE_URL),"Custom fallback order");
        System.out.println("PASS default/backup, timeouts, HTTP errors, cooldown, headers, body, signed URLs, 404, all failed, direct and custom routing");
    }
}
