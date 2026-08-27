package xiao.bu.tv;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.Enumeration;

final class LocalControlServer implements Closeable {
    interface Listener {
        String stateJson();
        String control(JSONObject request) throws Exception;
        String pointer(JSONObject request) throws Exception;
        String settings(JSONObject request) throws Exception;
        String uploadPlaylist(String sourceId, String fileName, byte[] body) throws Exception;
        Resource recording(String token) throws Exception;
    }

    static final class Resource {
        final String contentType;
        final byte[] body;

        Resource(String contentType, byte[] body) {
            this.contentType = contentType;
            this.body = body;
        }
    }

    private static final String TAG = "LocalControlServer";
    static final int PREFERRED_PORT = 9966;
    static final int MAX_PORT = 9975;
    private static final int MAX_REQUEST_BYTES = 2 * 1024 * 1024;
    private final byte[] indexHtml;
    private final Listener listener;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    LocalControlServer(byte[] indexHtml, Listener listener) {
        this.indexHtml = indexHtml;
        this.listener = listener;
    }

    void start() throws IOException {
        IOException lastError = null;
        for (int port = PREFERRED_PORT; port <= MAX_PORT; port++) {
            ServerSocket socket = new ServerSocket();
            try {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(port));
                serverSocket = socket;
                if (port != PREFERRED_PORT) {
                    Log.w(TAG, "Management port " + PREFERRED_PORT
                            + " occupied; using " + port);
                }
                break;
            } catch (IOException error) {
                lastError = error;
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
        if (serverSocket == null) {
            throw new IOException("Management ports " + PREFERRED_PORT + "-" + MAX_PORT
                    + " are unavailable", lastError);
        }
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "local-control-server");
        acceptThread.start();
        Log.i(TAG, "Management server listening on " + getPort());
    }

    int getPort() {
        return serverSocket == null ? 0 : serverSocket.getLocalPort();
    }

    String getLanUrl() {
        String address = findLanAddress();
        return address == null ? null : "http://" + address + ":" + getPort() + "/index.html";
    }

    String getLoopbackUrl() {
        return "http://127.0.0.1:" + getPort() + "/index.html";
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket socket = serverSocket.accept();
                Thread worker = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        handle(socket);
                    }
                }, "local-control-client");
                worker.start();
            } catch (IOException error) {
                if (running) {
                    Log.w(TAG, "Unable to accept management connection", error);
                }
            }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(25000);
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            String requestLine = readLine(input);
            if (requestLine == null) {
                return;
            }
            String[] requestParts = requestLine.split(" ");
            if (requestParts.length < 2) {
                send(socket, 400, "application/json; charset=utf-8",
                        jsonError("请求格式错误"));
                return;
            }
            String method = requestParts[0];
            String path = requestParts[1];
            int contentLength = 0;
            String line;
            while ((line = readLine(input)) != null && line.length() > 0) {
                int colon = line.indexOf(':');
                if (colon > 0 && "content-length".equalsIgnoreCase(line.substring(0, colon))) {
                    try {
                        contentLength = Integer.parseInt(line.substring(colon + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (contentLength < 0 || contentLength > MAX_REQUEST_BYTES) {
                send(socket, 413, "application/json; charset=utf-8",
                        jsonError("请求内容过大"));
                return;
            }
            byte[] body = new byte[contentLength];
            int offset = 0;
            while (offset < body.length) {
                int count = input.read(body, offset, body.length - offset);
                if (count < 0) {
                    break;
                }
                offset += count;
            }
            if (offset != body.length) {
                byte[] partial = new byte[offset];
                System.arraycopy(body, 0, partial, 0, offset);
                body = partial;
            }
            route(socket, method, path, body);
        } catch (Exception error) {
            Log.w(TAG, "Management request failed", error);
            try {
                send(socket, 500, "application/json; charset=utf-8",
                        jsonError(error.getMessage() == null ? "服务器错误" : error.getMessage()));
            } catch (IOException ignored) {
            }
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void route(Socket socket, String method, String path, byte[] body) throws Exception {
        String requestTarget = path;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
            send(socket, 200, "text/html; charset=utf-8", indexHtml);
        } else if ("GET".equals(method) && "/api/state".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8",
                    listener.stateJson().getBytes("UTF-8"));
        } else if ("POST".equals(method) && "/api/control".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8",
                    listener.control(new JSONObject(new String(body, "UTF-8"))).getBytes("UTF-8"));
        } else if ("POST".equals(method) && "/api/pointer".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8",
                    listener.pointer(new JSONObject(new String(body, "UTF-8"))).getBytes("UTF-8"));
        } else if ("POST".equals(method) && "/api/settings".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8",
                    listener.settings(new JSONObject(new String(body, "UTF-8"))).getBytes("UTF-8"));
        } else if ("POST".equals(method) && "/api/playlist/upload".equals(path)) {
            String sourceId = queryParameter(requestTarget, "id");
            String fileName = queryParameter(requestTarget, "name");
            send(socket, 200, "application/json; charset=utf-8",
                    listener.uploadPlaylist(sourceId, fileName, body).getBytes("UTF-8"));
        } else if ("GET".equals(method) && "/api/recording/playlist".equals(path)) {
            Resource resource = listener.recording(null);
            send(socket, 200, resource.contentType, resource.body);
        } else if ("GET".equals(method)
                && path.startsWith("/api/recording/resource/")) {
            String token = path.substring("/api/recording/resource/".length());
            Resource resource = listener.recording(token);
            send(socket, 200, resource.contentType, resource.body);
        } else if ("OPTIONS".equals(method)) {
            send(socket, 204, "text/plain", new byte[0]);
        } else {
            send(socket, 404, "application/json; charset=utf-8", jsonError("接口不存在"));
        }
    }

    private static String queryParameter(String requestTarget, String name) {
        int marker = requestTarget.indexOf('?');
        if (marker < 0 || marker + 1 >= requestTarget.length()) {
            return "";
        }
        String[] parts = requestTarget.substring(marker + 1).split("&");
        for (String part : parts) {
            int equals = part.indexOf('=');
            String key = equals < 0 ? part : part.substring(0, equals);
            if (!name.equals(decodeQueryValue(key))) {
                continue;
            }
            return decodeQueryValue(equals < 0 ? "" : part.substring(equals + 1));
        }
        return "";
    }

    private static String decodeQueryValue(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception ignored) {
            return value;
        }
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int previous = -1;
        int value;
        while ((value = input.read()) != -1) {
            if (previous == '\r' && value == '\n') {
                byte[] bytes = output.toByteArray();
                int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r'
                        ? bytes.length - 1 : bytes.length;
                return new String(bytes, 0, length, "ISO-8859-1");
            }
            output.write(value);
            previous = value;
            if (output.size() > 8192) {
                throw new IOException("请求头过长");
            }
        }
        return output.size() == 0 ? null : output.toString("ISO-8859-1");
    }

    private static byte[] jsonError(String message) throws IOException {
        try {
            return new JSONObject().put("ok", false).put("message", message)
                    .toString().getBytes("UTF-8");
        } catch (org.json.JSONException impossible) {
            return "{\"ok\":false}".getBytes("UTF-8");
        }
    }

    private static void send(Socket socket, int status, String contentType, byte[] body)
            throws IOException {
        String reason = status == 200 ? "OK" : status == 204 ? "No Content"
                : status == 400 ? "Bad Request" : status == 404 ? "Not Found"
                : status == 413 ? "Payload Too Large" : "Internal Server Error";
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Permissions-Policy: accelerometer=(self), gyroscope=(self)\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Headers: Content-Type\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Access-Control-Allow-Private-Network: true\r\n"
                + "Private-Network-Access-Name: ntv-tv\r\n"
                + "Private-Network-Access-ID: 4e:54:56:54:56:01\r\n"
                + "Connection: close\r\n\r\n";
        BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
        output.write(headers.getBytes("ISO-8859-1"));
        output.write(body);
        output.flush();
    }

    private static String findLanAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return null;
            }
            for (NetworkInterface network : Collections.list(interfaces)) {
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    String host = address.getHostAddress();
                    if (!address.isLoopbackAddress() && host.indexOf(':') < 0
                            && address.isSiteLocalAddress()) {
                        return host;
                    }
                }
            }
        } catch (SocketException error) {
            Log.w(TAG, "Unable to inspect network interfaces", error);
        }
        return null;
    }

    @Override
    public void close() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
    }
}
