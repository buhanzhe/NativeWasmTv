package xiao.bu.tv;

import java.io.*;
import java.net.*;
import java.util.UUID;

/** Serves only a provider-supplied auth response, on loopback, for the engine lifetime. */
final class TvbusAuthReply implements Closeable {
    private final ServerSocket server;
    private final String response;
    private final String token = "/" + UUID.randomUUID();
    TvbusAuthReply(String response) throws IOException {
        this.response = response;
        if (response.startsWith("https://") || response.startsWith("http://")) TvbusLiveConfig.online(response);
        if (response.getBytes("UTF-8").length > 256 * 1024) throw new IOException("TVBus auth response too large");
        server = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
        Thread thread = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    InputStream input = new BufferedInputStream(socket.getInputStream());
                    String request = line(input);
                    int length = 0, headerBytes = 0;
                    String header;
                    while (!(header = line(input)).isEmpty()) {
                        headerBytes += header.length();
                        if (headerBytes > 16384) throw new IOException("Headers too large");
                        if (header.toLowerCase(java.util.Locale.US).startsWith("content-length:"))
                            try { length = Integer.parseInt(header.substring(15).trim()); }
                            catch (NumberFormatException invalid) { throw new IOException("Invalid length"); }
                    }
                    if (length < 0 || length > 256 * 1024) throw new IOException("Request too large");
                    for (int i = 0; i < length; i++) if (input.read() < 0) throw new EOFException();
                    String[] parts = request.split(" ", 3);
                    boolean match = parts.length == 3 && (parts[0].equals("GET") || parts[0].equals("POST"))
                            && parts[1].split("\\?", 2)[0].equals(token);
                    byte[] reply = !match ? new byte[0] : response.startsWith("https://") || response.startsWith("http://")
                            ? TvbusLiveConfig.fetch(response, 256 * 1024) : response.getBytes("UTF-8");
                    OutputStream output = socket.getOutputStream();
                    output.write(("HTTP/1.1 " + (match ? "200 OK" : "404 Not Found")
                            + "\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "
                            + reply.length + "\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
                    output.write(reply); output.flush();
                } catch (IOException ignored) { }
            }
        }, "tvbus-auth");
        thread.setDaemon(true); thread.start();
    }
    private static String line(InputStream input) throws IOException {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < 4096; i++) {
            int c = input.read();
            if (c < 0) throw new EOFException();
            if (c == '\n') return value.toString().replace("\r", "");
            value.append((char) c);
        }
        throw new IOException("HTTP line too long");
    }
    String url() { return "http://127.0.0.1:" + server.getLocalPort() + token; }
    @Override public void close() { try { server.close(); } catch (IOException ignored) { } }
}
