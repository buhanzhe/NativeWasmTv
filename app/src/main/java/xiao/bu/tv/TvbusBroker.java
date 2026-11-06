package xiao.bu.tv;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** One channel's loopback broker; forwards the original binary/authentication data unchanged. */
final class TvbusBroker implements Closeable {
    private static final int LIMIT = 1024 * 1024;
    private final ServerSocket server;
    private final TvbusAddress.Route route;
    private volatile String failure = "";
    private final Set<Socket> sockets = Collections.synchronizedSet(new HashSet<Socket>());
    private final Set<HttpURLConnection> connections = Collections.synchronizedSet(new HashSet<HttpURLConnection>());
    private final ExecutorService clients = new ThreadPoolExecutor(0, 4, 15, TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>(), runnable -> { Thread t = new Thread(runnable, "tvbus-broker-client"); t.setDaemon(true); return t; });
    TvbusBroker(TvbusAddress.Route route) throws IOException {
        this.route = route;
        server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        Thread accept = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept(); sockets.add(socket);
                    try { clients.execute(() -> serve(socket)); }
                    catch (RejectedExecutionException busy) { sockets.remove(socket); socket.close(); }
                } catch (IOException ignored) { }
            }
        }, "tvbus-broker");
        accept.setDaemon(true); accept.start();
    }
    String address() { return "sop://127.0.0.1:" + server.getLocalPort() + route.path; }
    String failureDetail() { return failure; }
    private void serve(Socket socket) {
        try {
            Socket client=socket;
            client.setSoTimeout(15000);
            InputStream input = new BufferedInputStream(client.getInputStream());
            String[] request = line(input).split(" ", 3);
            Map<String,String> headers = new HashMap<>(); int headerBytes = 0;
            for (String header; !(header = line(input)).isEmpty();) {
                headerBytes += header.length(); if (headerBytes > 16384) throw new IOException("Headers too large");
                int colon = header.indexOf(':');
                if (colon <= 0) throw new IOException("Invalid header");
                String name = header.substring(0, colon).toLowerCase(Locale.US);
                if (headers.containsKey(name)) throw new IOException("Duplicate header");
                headers.put(name, header.substring(colon + 1).trim());
            }
            OutputStream output = client.getOutputStream();
            if (request.length != 3 || !request[0].equals("POST")) { reply(output, 405, new byte[0], "text/plain", null); return; }
            // Channel binding prevents old requests from being forwarded to a newly selected source.
            String path = request[1].split("\\?", 2)[0], expected = route.path.split("\\?", 2)[0];
            if ((!path.equals(expected) && !path.matches("/v1/[a-z_]+")) || request[1].contains("#")) {
                reply(output, 404, new byte[0], "text/plain", null); return;
            }
            if (headers.containsKey("transfer-encoding")) { reply(output, 400, new byte[0], "text/plain", null); return; }
            int length;
            try { length = Integer.parseInt(headers.containsKey("content-length") ? headers.get("content-length") : "0"); }
            catch (NumberFormatException invalid) { length = -1; }
            if (length < 0 || length > LIMIT) { reply(output, 413, new byte[0], "text/plain", null); return; }
            byte[] body = new byte[length]; new DataInputStream(input).readFully(body);
            HttpURLConnection upstream = NetworkClient.openHttp1(new URL(route.broker + request[1]), 15000);
            connections.add(upstream);
            upstream.setConnectTimeout(10000); upstream.setReadTimeout(15000);
            upstream.setInstanceFollowRedirects(false); upstream.setRequestMethod("POST"); upstream.setDoOutput(true);
            upstream.setRequestProperty("Content-Type", "application/vnd.brk");
            upstream.setRequestProperty("Accept-Encoding", "identity");
            String seed = headers.get("x-message-seed");
            if (seed == null) seed = headers.get("x-message-seed-1");
            if (seed != null) upstream.setRequestProperty("X-Message-Seed", seed);
            try {
                if(server.isClosed())throw new IOException("Broker closed");
                upstream.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = upstream.getOutputStream()) { out.write(body); }
                int status = upstream.getResponseCode();
                if(status>=400)failure="上游 broker 返回 HTTP " + status;
                xiao.bu.tv.AppLog.i("TvbusPlugin", "Broker upstream HTTP " + status);
                InputStream response = status >= 400 ? upstream.getErrorStream() : upstream.getInputStream();
                byte[] bytes = response == null ? new byte[0] : read(response);
                reply(output, status, bytes, "application/vnd.brk", upstream.getHeaderField("X-Message-Seed"));
            } finally { upstream.disconnect(); connections.remove(upstream); }
        } catch (IOException error) {
            if(!server.isClosed()) {
                failure=error instanceof UnknownHostException?"上游 broker 域名无法解析":error instanceof java.net.SocketTimeoutException?"上游 broker 连接超时":error instanceof javax.net.ssl.SSLException?"上游 broker TLS 连接失败":"上游 broker 连接失败";
                xiao.bu.tv.AppLog.w("TvbusPlugin", "Broker relay failed: " + error.getClass().getSimpleName());
            }
            try { reply(socket.getOutputStream(), 502, new byte[0], "text/plain", null); } catch (IOException closed) { }
        } finally { sockets.remove(socket); try {socket.close();}catch(IOException ignored){} }
    }
    private static byte[] read(InputStream stream) throws IOException {
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) { if (out.size() + count > LIMIT) throw new IOException("Broker response too large"); out.write(buffer, 0, count); }
            return out.toByteArray();
        }
    }
    private static void reply(OutputStream out, int status, byte[] body, String type, String seed) throws IOException {
        String extra = seed != null && !seed.contains("\r") && !seed.contains("\n") ? "X-Message-Seed: " + seed + "\r\n" : "";
        out.write(("HTTP/1.1 " + status + " Broker\r\nContent-Type: " + type + "\r\n" + extra + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
        out.write(body); out.flush();
    }
    private static String line(InputStream input) throws IOException {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 4096; i++) { int c = input.read(); if (c < 0) throw new EOFException(); if (c == '\n') return text.toString().replace("\r", ""); text.append((char)c); }
        throw new IOException("HTTP line too large");
    }
    @Override public void close() {
        try { server.close(); } catch (IOException ignored) { }
        synchronized (connections) { for (HttpURLConnection connection : connections) connection.disconnect(); }
        synchronized (sockets) { for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { } }
        clients.shutdownNow();
    }
}
