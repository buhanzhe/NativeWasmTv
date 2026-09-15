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
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class LocalControlServer implements Closeable {
    interface Listener {
        String multimediaControl(JSONObject request) throws Exception;
        String uploadMultimedia(String url, String name, InputStream input, int length, boolean preserveAudio) throws Exception;
        String stateJson(String view);
        String catalogJson();
        String playbackJson();
        String mediaJson(boolean detailed) throws Exception;
        String control(JSONObject request) throws Exception;
        String mediaControl(JSONObject request) throws Exception;
        String pointer(JSONObject request) throws Exception;
        String startCast(JSONObject request) throws Exception;
        String stopCast() throws Exception;
        String takeover(JSONObject request) throws Exception;
        String wifiDirect(JSONObject request) throws Exception;
        void takeoverSessionOpened(JSONObject request) throws Exception;
        void takeoverSessionMessage(JSONObject request) throws Exception;
        void takeoverSessionClosed(String sessionId);
        String settings(JSONObject request) throws Exception;
        String checkUpdate() throws Exception;
        String installUpdate() throws Exception;
        String uploadPlaylist(String sourceId, String fileName, byte[] body) throws Exception;
        String uploadKu9Script(String fileName, byte[] body) throws Exception;
        String pushApk(String receiverUrl, String fileName, byte[] body) throws Exception;
        String installApk(String sessionId, String fileName, byte[] body) throws Exception;
        Resource playlistSource(String location) throws Exception;
        String mergePlaylist(JSONObject request) throws Exception;
        Resource recording(String token) throws Exception;
        Resource screenshot(boolean localOnly) throws Exception;
        Resource artwork(String key, boolean localOnly) throws Exception;
        Resource page(String path) throws Exception;
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
    private static final int SOCKET_TIMEOUT_MS = 30000;
    private static final int MAX_KU9_SCRIPT_BYTES = 2 * 1024 * 1024;
    private static final int MIN_REQUEST_BYTES = 8 * 1024 * 1024;
    private static final int MAX_REQUEST_BYTES = 64 * 1024 * 1024;
    private final Listener listener;
    private final ExecutorService clientWorkers = new ThreadPoolExecutor(
            3, 5, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(16),
            new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean running;
    private volatile String advertisedLanAddress;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile long advertisedLanAddressAt;
    private final Object takeoverOutputLock = new Object();
    private BufferedOutputStream takeoverOutput;
    private String takeoverSessionId = "";
    private Socket takeoverSocket;

    void closeTakeoverSession(String expectedSession) {
        Socket socket;
        synchronized (takeoverOutputLock) {
            if (!expectedSession.equals(takeoverSessionId)) return;
            socket = takeoverSocket;
            takeoverSocket = null;
            takeoverOutput = null;
            takeoverSessionId = "";
        }
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }

    LocalControlServer(Listener listener) {
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
        long now = android.os.SystemClock.elapsedRealtime();
        String address = advertisedLanAddress;
        if (advertisedLanAddressAt == 0L || now - advertisedLanAddressAt >= 30000L) {
            address = findLanAddress();
            advertisedLanAddress = address;
            advertisedLanAddressAt = now;
        }
        return address == null ? null : "http://" + address + ":" + getPort() + "/index.html";
    }

    /** Select the local interface that can actually reach a particular receiver.
     * This matters on a hotspot phone where cellular and SoftAP are both private IPv4. */
    String getLanUrlForPeer(String peerUrl) {
        try {
            InetAddress peer = InetAddress.getByName(new URL(peerUrl).getHost());
            byte[] peerBytes = peer.getAddress();
            if (peerBytes.length == 4) {
                Enumeration<NetworkInterface> interfaces = NetworkInterface
                        .getNetworkInterfaces();
                if (interfaces != null) {
                    for (NetworkInterface network : Collections.list(interfaces)) {
                        if (!network.isUp() || network.isLoopback()) continue;
                        for (InterfaceAddress candidate : network.getInterfaceAddresses()) {
                            InetAddress local = candidate.getAddress();
                            if (local == null || local.getAddress().length != 4
                                    || local.isLoopbackAddress()) continue;
                            if (sameSubnet(local.getAddress(), peerBytes,
                                    candidate.getNetworkPrefixLength())) {
                                return "http://" + local.getHostAddress() + ":"
                                        + getPort() + "/index.html";
                            }
                        }
                    }
                }
            }
        } catch (Exception error) {
            Log.d(TAG, "Unable to select peer-facing LAN address", error);
        }
        return getLanUrl();
    }

    String getAdvertisedLanAddress() {
        String address = advertisedLanAddress;
        return address == null ? "" : address;
    }

    boolean ownsOrigin(String url) {
        return running && ControlSite.ownsOrigin(url, getPort(), advertisedLanAddress);
    }

    String getLoopbackUrl() {
        return "http://127.0.0.1:" + getPort() + "/index.html";
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket socket = serverSocket.accept();
                try {
                    clientWorkers.execute(new Runnable() {
                        @Override
                        public void run() {
                            handle(socket);
                        }
                    });
                } catch (RejectedExecutionException error) {
                    socket.close();
                    if (running) {
                        Log.w(TAG, "Management worker pool rejected connection", error);
                    }
                }
            } catch (IOException error) {
                if (running) {
                    Log.w(TAG, "Unable to accept management connection", error);
                }
            }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            String requestLine = readLine(input);
            if (requestLine == null) {
                return;
            }
            if ("NTV-TAKEOVER/1".equals(requestLine)) {
                handleTakeoverSession(socket, input);
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
            boolean invalidContentLength = false;
            boolean chunkedBody = false;
            String line;
            while ((line = readLine(input)) != null && line.length() > 0) {
                int colon = line.indexOf(':');
                String headerName = colon > 0 ? line.substring(0, colon).trim() : "";
                String headerValue = colon > 0 ? line.substring(colon + 1).trim() : "";
                if ("content-length".equalsIgnoreCase(headerName)) {
                    try {
                        long parsedLength = Long.parseLong(headerValue);
                        if (parsedLength < 0L || parsedLength > Integer.MAX_VALUE) {
                            invalidContentLength = true;
                        } else {
                            contentLength = (int) parsedLength;
                        }
                    } catch (NumberFormatException error) {
                        invalidContentLength = true;
                    }
                } else if ("transfer-encoding".equalsIgnoreCase(headerName)
                        && headerValue.toLowerCase(java.util.Locale.US).contains("chunked")) {
                    chunkedBody = true;
                }
            }
            if (invalidContentLength) {
                send(socket, 400, "application/json; charset=utf-8",
                        jsonError("请求长度无效"));
                return;
            }
            if ("POST".equals(method) && path.split("\\?", 2)[0].equals("/api/multimedia/upload")) {
                if (chunkedBody || contentLength <= 0) throw new IOException("上传文件需要明确长度");
                socket.setSoTimeout(30000);
                send(socket, 200, "application/json; charset=utf-8", listener.uploadMultimedia(
                        queryParameter(path, "receiverUrl"), queryParameter(path, "name"),
                        input, contentLength, "1".equals(queryParameter(path, "preserveAudio"))).getBytes("UTF-8"));
                return;
            }
            int bodyLimit = path.startsWith("/api/ku9/script/upload")
                    ? MAX_KU9_SCRIPT_BYTES : requestBodyLimit();
            if (contentLength > bodyLimit) {
                send(socket, 413, "application/json; charset=utf-8",
                        jsonError(path.startsWith("/api/ku9/script/upload")
                                ? "JS 文件不能超过 2 MB"
                                : path.startsWith("/api/apk/")
                                        ? "APK 文件过大，当前设备最多接收 "
                                                + (bodyLimit / 1024 / 1024) + " MB"
                                        : "请求内容超过设备可安全处理的大小"));
                return;
            }
            byte[] body = chunkedBody
                    ? readChunkedBody(input, bodyLimit)
                    : readFixedBody(input, contentLength);
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

    /**
     * Small persistent, bidirectional control channel used only while one device
     * controls another. HTTP remains available for the larger catalog/playback
     * requests, while this socket proves that the controller is still alive.
     */
    private void handleTakeoverSession(Socket socket, BufferedInputStream input) {
        String sessionId = "";
        boolean opened = false;
        CastCursorChannel.Receiver cursor = null;
        try {
            socket.setSoTimeout(18000);
            BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
            String helloLine = readLine(input);
            if (helloLine == null) {
                return;
            }
            JSONObject hello = new JSONObject(decodeProtocolLine(helloLine));
            sessionId = hello.optString("sessionId", "").trim();
            if (sessionId.length() == 0) {
                throw new IOException("接管会话缺少标识");
            }
            listener.takeoverSessionOpened(hello);
            opened = true;
            synchronized (takeoverOutputLock) {
                Socket previous = takeoverSocket;
                takeoverSocket = socket;
                takeoverOutput = output;
                takeoverSessionId = sessionId;
                if (previous != null && previous != socket) {
                    try { previous.close(); } catch (IOException ignored) { }
                }
            }
            try {
                cursor = new CastCursorChannel.Receiver(socket.getInetAddress(), sessionId,
                        state -> listener.takeoverSessionMessage(state));
            } catch (Exception ignored) { /* video-embedded cursor remains compatible */ }
            writeTakeoverLine(output, new JSONObject().put("ok", true)
                    .put("protocol", 1).put("cursorPort", cursor == null ? 0 : cursor.port()));
            while (running && !socket.isClosed()) {
                String line = readLine(input);
                if (line == null) {
                    break;
                }
                JSONObject message = new JSONObject(decodeProtocolLine(line));
                if (!sessionId.equals(message.optString("sessionId", ""))) {
                    throw new IOException("接管会话标识不一致");
                }
                listener.takeoverSessionMessage(message);
                writeTakeoverLine(output, new JSONObject().put("ok", true));
            }
        } catch (Exception error) {
            if (running) {
                Log.i(TAG, "Takeover session ended: " + error.getMessage());
            }
        } finally {
            if (cursor != null) cursor.close();
            synchronized (takeoverOutputLock) {
                if (takeoverSocket == socket) {
                    takeoverSocket = null;
                    takeoverOutput = null;
                    takeoverSessionId = "";
                }
            }
            if (opened) {
                listener.takeoverSessionClosed(sessionId);
            }
        }
    }

    private static String decodeProtocolLine(String line) throws IOException {
        return new String(line.getBytes("ISO-8859-1"), "UTF-8");
    }

    private void writeTakeoverLine(BufferedOutputStream output, JSONObject message)
            throws IOException {
        synchronized (takeoverOutputLock) {
            output.write(message.toString().getBytes("UTF-8"));
            output.write('\n');
            output.flush();
        }
    }

    boolean sendTakeoverSessionMessage(JSONObject message) {
        synchronized (takeoverOutputLock) {
            if (takeoverOutput == null || takeoverSessionId.length() == 0) {
                return false;
            }
            try {
                message.put("sessionId", takeoverSessionId);
                takeoverOutput.write(message.toString().getBytes("UTF-8"));
                takeoverOutput.write('\n');
                takeoverOutput.flush();
                return true;
            } catch (Exception error) {
                Log.w(TAG, "Unable to send takeover session message", error);
                return false;
            }
        }
    }

    private void route(Socket socket, String method, String path, byte[] body) throws Exception {
        String requestTarget = path;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        if ("GET".equals(method) && ControlSite.contains(path)) {
            Resource resource = listener.page("/".equals(path) ? "index.html" : path.substring(1));
            send(socket, 200, resource.contentType, resource.body,
                    "public, max-age=300");
        } else if ("GET".equals(method) && ("/flymouse.html".equals(path)
                || "/video-recorder.html".equals(path)
                || "/mp4-finalizer.js".equals(path))) {
            Resource resource = listener.page(path.substring(1));
            send(socket, 200, resource.contentType, resource.body);
        } else if ("GET".equals(method) && "/api/state".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8",
                    listener.stateJson(queryParameter(requestTarget, "view"))
                            .getBytes("UTF-8"));
        } else if ("GET".equals(method) && "/api/catalog".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8",
                    listener.catalogJson().getBytes("UTF-8"));
        } else if ("GET".equals(method) && "/api/playback".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8",
                    listener.playbackJson().getBytes("UTF-8"));
        } else if ("POST".equals(method) && "/api/multimedia/control".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8",
                    listener.multimediaControl(new JSONObject(new String(body, "UTF-8"))).getBytes("UTF-8"));
        } else if ("GET".equals(method) && "/api/media".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8",
                    listener.mediaJson(!"0".equals(queryParameter(
                            requestTarget, "detail"))).getBytes("UTF-8"));
        } else if ("POST".equals(method) && "/api/control".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8",
                    listener.control(new JSONObject(new String(body, "UTF-8"))).getBytes("UTF-8"));
        } else if ("POST".equals(method) && "/api/media/control".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8",
                    listener.mediaControl(new JSONObject(new String(body, "UTF-8")))
                            .getBytes("UTF-8"));
        } else if ("POST".equals(method) && "/api/pointer".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8",
                    listener.pointer(new JSONObject(new String(body, "UTF-8"))).getBytes("UTF-8"));
        } else if ("POST".equals(method) && "/api/cast/start".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8",
                    listener.startCast(new JSONObject(new String(body, "UTF-8")))
                            .getBytes("UTF-8"));
        } else if ("POST".equals(method) && "/api/cast/stop".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8",
                    listener.stopCast().getBytes("UTF-8"));
        } else if ("POST".equals(method) && "/api/takeover".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8",
                    listener.takeover(new JSONObject(new String(body, "UTF-8")))
                            .getBytes("UTF-8"));
        } else if ("POST".equals(method) && "/api/wifi-direct".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8",
                    listener.wifiDirect(new JSONObject(new String(body, "UTF-8")))
                            .getBytes("UTF-8"));
        } else if ("POST".equals(method) && "/api/settings".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8",
                    listener.settings(new JSONObject(new String(body, "UTF-8"))).getBytes("UTF-8"));
        } else if ("POST".equals(method) && "/api/update/install".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8",
                    listener.installUpdate().getBytes("UTF-8"));
        } else if ("POST".equals(method) && "/api/update/check".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8",
                    listener.checkUpdate().getBytes("UTF-8"));
        } else if ("POST".equals(method) && "/api/playlist/upload".equals(path)) {
            String sourceId = queryParameter(requestTarget, "id");
            String fileName = queryParameter(requestTarget, "name");
            send(socket, 200, "application/json; charset=utf-8",
                    listener.uploadPlaylist(sourceId, fileName, body).getBytes("UTF-8"));
        } else if ("POST".equals(method) && "/api/ku9/script/upload".equals(path)) {
            String fileName = queryParameter(requestTarget, "name");
            send(socket, 200, "application/json; charset=utf-8",
                    listener.uploadKu9Script(fileName, body).getBytes("UTF-8"));
        } else if ("POST".equals(method) && "/api/apk/push".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8",
                    listener.pushApk(queryParameter(requestTarget, "receiverUrl"),
                            queryParameter(requestTarget, "name"), body)
                            .getBytes("UTF-8"));
        } else if ("POST".equals(method) && "/api/apk/upload".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8",
                    listener.installApk(queryParameter(requestTarget, "sessionId"),
                            queryParameter(requestTarget, "name"), body).getBytes("UTF-8"));
        } else if ("GET".equals(method) && "/api/playlist/source".equals(path)) {
            Resource resource = listener.playlistSource(
                    queryParameter(requestTarget, "location"));
            send(socket, 200, resource.contentType, resource.body);
        } else if ("POST".equals(method) && "/api/playlist/merge".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8",
                    listener.mergePlaylist(new JSONObject(new String(body, "UTF-8")))
                            .getBytes("UTF-8"));
        } else if ("GET".equals(method) && "/api/media/artwork".equals(path)) {
            Resource resource = listener.artwork(queryParameter(requestTarget, "key"),
                    "1".equals(queryParameter(requestTarget, "local")));
            send(socket, 200, resource.contentType, resource.body);
        } else if ("GET".equals(method) && VideoScreenshot.PATH.equals(path)) {
            Resource resource = listener.screenshot("1".equals(
                    queryParameter(requestTarget, "local")));
            send(socket, 200, resource.contentType, resource.body);
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

    private static byte[] readFixedBody(InputStream input, int contentLength)
            throws IOException {
        byte[] body = new byte[contentLength];
        int offset = 0;
        while (offset < body.length) {
            int count = input.read(body, offset, body.length - offset);
            if (count < 0) {
                break;
            }
            offset += count;
        }
        if (offset == body.length) {
            return body;
        }
        byte[] partial = new byte[offset];
        System.arraycopy(body, 0, partial, 0, offset);
        return partial;
    }

    private static byte[] readChunkedBody(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        while (true) {
            String sizeLine = readLine(input);
            if (sizeLine == null) {
                throw new IOException("分块请求不完整");
            }
            int extension = sizeLine.indexOf(';');
            String sizeText = (extension < 0 ? sizeLine : sizeLine.substring(0, extension)).trim();
            final long chunkSize;
            try {
                chunkSize = Long.parseLong(sizeText, 16);
            } catch (NumberFormatException error) {
                throw new IOException("分块请求格式错误", error);
            }
            if (chunkSize < 0L || chunkSize > limit - output.size()) {
                throw new IOException("请求内容超过设备可安全处理的大小");
            }
            if (chunkSize == 0L) {
                while ((sizeLine = readLine(input)) != null && sizeLine.length() > 0) {
                    // Consume optional trailer headers.
                }
                return output.toByteArray();
            }
            byte[] chunk = readFixedBody(input, (int) chunkSize);
            if (chunk.length != (int) chunkSize) {
                throw new IOException("分块请求内容不完整");
            }
            output.write(chunk);
            String terminator = readLine(input);
            if (terminator == null || terminator.length() != 0) {
                throw new IOException("分块请求结尾格式错误");
            }
        }
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
        send(socket, status, contentType, body, "no-store");
    }

    private static void send(Socket socket, int status, String contentType, byte[] body,
            String cacheControl) throws IOException {
        String reason = status == 200 ? "OK" : status == 204 ? "No Content"
                : status == 400 ? "Bad Request" : status == 404 ? "Not Found"
                : status == 413 ? "Payload Too Large" : "Internal Server Error";
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: " + cacheControl + "\r\n"
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
        String preferred = null;
        int preferredRank = Integer.MAX_VALUE;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return null;
            }
            for (NetworkInterface network : Collections.list(interfaces)) {
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                String interfaceName = network.getName() == null ? ""
                        : network.getName().toLowerCase(java.util.Locale.US);
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    String host = address.getHostAddress();
                    if (!address.isLoopbackAddress() && host.indexOf(':') < 0
                            && address.isSiteLocalAddress()) {
                        int rank = ipv4Preference(host) * 10
                                + interfacePreference(interfaceName);
                        if (rank < preferredRank) {
                            preferred = host;
                            preferredRank = rank;
                            if (rank == 0) {
                                return preferred;
                            }
                        }
                    }
                }
            }
        } catch (SocketException error) {
            Log.w(TAG, "Unable to inspect network interfaces", error);
        }
        return preferred;
    }

    private static boolean sameSubnet(byte[] local, byte[] peer, short prefixLength) {
        if (local.length != peer.length || prefixLength < 0
                || prefixLength > local.length * 8) return false;
        int fullBytes = prefixLength / 8;
        int remainingBits = prefixLength % 8;
        for (int index = 0; index < fullBytes; index++) {
            if (local[index] != peer[index]) return false;
        }
        if (remainingBits == 0) return true;
        int mask = 0xff << (8 - remainingBits);
        return (local[fullBytes] & mask) == (peer[fullBytes] & mask);
    }

    private static int interfacePreference(String name) {
        if (name.startsWith("wlan") || name.startsWith("wifi")
                || name.startsWith("ap") || name.startsWith("swlan")
                || name.startsWith("softap") || name.startsWith("eth")) return 0;
        if (name.contains("p2p")) return 1;
        if (name.startsWith("rmnet") || name.startsWith("ccmni")
                || name.startsWith("pdp")) return 8;
        return 4;
    }

    private static int requestBodyLimit() {
        long heapBudget = Runtime.getRuntime().maxMemory() / 4L;
        return (int) Math.max(MIN_REQUEST_BYTES,
                Math.min(MAX_REQUEST_BYTES, heapBudget));
    }

    static int maxRequestBytes() {
        return Math.min(ApkTransferInstaller.MAX_APK_BYTES, requestBodyLimit());
    }

    private static int ipv4Preference(String address) {
        if (address != null && address.startsWith("192.")) {
            return 0;
        }
        if (address != null && address.startsWith("10.")) {
            return 1;
        }
        if (address != null && address.startsWith("172.")) {
            return 2;
        }
        return 3;
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
        clientWorkers.shutdownNow();
    }
}
