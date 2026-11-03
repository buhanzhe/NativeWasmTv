package xiao.bu.tv;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
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

        String stateJson(String view);
        String catalogJson();
        String playbackJson();
        String mediaJson(boolean detailed) throws Exception;
        MediaFileDownload mediaDownload(String sourceKey, String range, String ifRange) throws Exception;
        String browserAction(long afterId) throws Exception;
        String control(JSONObject request) throws Exception;
        String mediaControl(JSONObject request) throws Exception;
        String pointer(JSONObject request) throws Exception;

        String wifiDirect(JSONObject request) throws Exception;
        void takeoverSessionOpened(JSONObject request) throws Exception;
        void takeoverSessionMessage(JSONObject request) throws Exception;
        void takeoverSessionClosed(String sessionId);
        String settings(JSONObject request) throws Exception;
        String playlistChannels(String groupId) throws Exception;
        String importUserScript(JSONObject request) throws Exception;
        String checkUpdate() throws Exception;
        String installUpdate() throws Exception;
        default String uploadBackground(byte[] body) throws Exception {throw new IOException("不支持自定义背景");}
        default File createBackgroundUploadFile() throws Exception {throw new IOException("不支持图片上传");}
        default String uploadBackgroundFile(File image) throws Exception {throw new IOException("不支持图片上传");}
        default Resource backgroundImage() throws Exception {throw new IOException("尚无背景图片");}
        default String bingWallpapers() throws Exception {throw new IOException("不支持 Bing 壁纸图库");}
        default String quickMedia(String name, byte[] body) throws Exception { throw new IOException("不支持临时媒体"); }
        default String uploadTvbusPlugin(byte[] body) throws Exception { throw new IOException("不支持 TVBus 插件"); }
        String uploadPlaylist(String sourceId, String fileName, byte[] body) throws Exception;
        String uploadKu9Script(String fileName, byte[] body) throws Exception;
        String radioUi(byte[] body) throws Exception;
        default String tvUi(byte[] body) throws Exception { throw new java.io.IOException("不支持电视界面插件"); }
        String pushApk(String receiverUrl, String fileName, byte[] body) throws Exception;
        String installApk(String sessionId, String fileName, byte[] body) throws Exception;
        Resource playlistSource(String location) throws Exception;
        String mergePlaylist(JSONObject request) throws Exception;
        default String playlistImportStatus(String id) throws Exception { throw new ControlRequestException(404,"不支持异步导入"); }
        Resource recording(String token) throws Exception;
        Resource screenshot(boolean localOnly, boolean preview) throws Exception;
        Resource artwork(String key, boolean localOnly) throws Exception;
        Resource browserDownload(long eventId) throws Exception;
        Resource page(String path) throws Exception;
    }

    static final class Resource implements Closeable {
        final String contentType;
        final byte[] body;
        final String downloadName;
        final InputStream stream;
        final long length;

        Resource(String contentType, byte[] body) {
            this(contentType, body, "");
        }

        Resource(String contentType, byte[] body, String downloadName) {
            this.contentType = contentType;
            this.body = body;
            this.downloadName = downloadName == null ? "" : downloadName;
            this.stream = null;
            this.length = body.length;
        }

        Resource(String contentType, InputStream stream, long length) {
            this.contentType = contentType;
            this.body = null;
            this.downloadName = "";
            this.stream = stream;
            this.length = length;
        }

        @Override public void close() throws IOException {
            if (stream != null) stream.close();
        }
    }

    private static final String TAG = "LocalControlServer";
    // One reusable buffer per bounded server worker, never one full HLS segment.
    private static final ThreadLocal<byte[]> transferBuffer = new ThreadLocal<byte[]>() {
        @Override protected byte[] initialValue() { return new byte[64 * 1024]; }
    };
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
            long contentLength = 0;
            boolean invalidContentLength = false;
            boolean chunkedBody = false;
            String downloadRange = null, downloadIfRange = null;
            String line;
            while ((line = readLine(input)) != null && line.length() > 0) {
                int colon = line.indexOf(':');
                String headerName = colon > 0 ? line.substring(0, colon).trim() : "";
                String headerValue = colon > 0 ? line.substring(colon + 1).trim() : "";
                if ("range".equalsIgnoreCase(headerName)) {
                    downloadRange = headerValue;
                } else if ("if-range".equalsIgnoreCase(headerName)) {
                    downloadIfRange = headerValue;
                } else if ("content-length".equalsIgnoreCase(headerName)) {
                    try {
                        long parsedLength = Long.parseLong(headerValue);
                        if (parsedLength < 0L) {
                            invalidContentLength = true;
                        } else {
                            contentLength = parsedLength;
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
            if ("GET".equals(method) && "/api/media/download".equals(path.split("\\?", 2)[0])) {
                MediaFileDownload download = listener.mediaDownload(queryParameter(path, "sourceKey"),
                        downloadRange, downloadIfRange);
                sendMediaDownload(socket, download);
                return;
            }
            if ("POST".equals(method) && ("/api/background/upload".equals(path) || path.startsWith("/api/background/upload?"))) {
                receiveBackgroundUpload(socket,input,chunkedBody,contentLength);
                return;
            }
            int bodyLimit = path.startsWith("/api/ku9/script/upload") || path.equals("/api/radio-ui") || path.equals("/api/tv-ui")
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
                    : readFixedBody(input, (int) contentLength);
            route(socket, method, path, body);
        } catch (Exception error) {
            if(error instanceof ControlRequestException)
                Log.i(TAG,"Management request rejected: "+error.getMessage());
            else Log.w(TAG, "Management request failed", error);
            try {
                int status = error instanceof ControlRequestException
                        ? ((ControlRequestException) error).status
                        : error instanceof org.json.JSONException || error instanceof IllegalArgumentException ? 400 : 500;
                String message = error instanceof ControlRequestException ? error.getMessage()
                        : status == 400 ? "请求参数无效" : "服务器错误，请查看设备日志";
                send(socket, status, "application/json; charset=utf-8", jsonError(message));
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
        if ("POST".equals(method) && (body == null || body.length == 0)
                && java.util.Arrays.asList("/api/quick/media", "/api/playlist/upload",
                        "/api/ku9/script/upload", "/api/apk/upload", "/api/tvbus/plugin").contains(path))
            throw new ControlRequestException(400,"文件为空，请重新选择文件");
        if ("GET".equals(method) && ControlSite.contains(path)) {
            Resource resource = listener.page("/".equals(path) ? "index.html" : path.substring(1));
            send(socket, 200, resource.contentType, resource.body,
                    "public, max-age=300");
        } else if ("GET".equals(method) && "/pointer-queue.js".equals(path)) {
            // The standalone flymouse page is also published as a static page and
            // therefore uses this root-relative companion path. Serve the same
            // bundled queue as the management page instead of maintaining a third copy.
            Resource resource = listener.page("js/pointer-queue.js");
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
        } else if ("GET".equals(method) && "/api/logs".equals(path)) {
            String filter = queryParameter(requestTarget, "filter");
            String snapshot = AppLogReader.read(filter);
            if ("1".equals(queryParameter(requestTarget, "download"))) {
                JSONObject logs = new JSONObject(snapshot);
                String text = logs.optString("text");
                if (text.isEmpty()) text = logs.optBoolean("available", true)
                        ? "当前筛选没有日志。\n" : logs.optString("note") + "\n";
                String label = "error".equals(filter) ? "error" : "crash".equals(filter) ? "crash" : "all";
                String timestamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",
                        java.util.Locale.US).format(new java.util.Date());
                sendDownload(socket, "text/plain; charset=utf-8", text.getBytes("UTF-8"),
                        "ntv-logs-" + label + "-" + timestamp + ".txt");
            } else {
                send(socket, 200, "application/json; charset=utf-8", snapshot.getBytes("UTF-8"));
            }
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
        } else if ("GET".equals(method) && "/api/browser/action".equals(path)) {
            long afterId;
            try {
                afterId = Long.parseLong(queryParameter(requestTarget, "after"));
            } catch (NumberFormatException invalidId) {
                afterId = 0L;
            }
            send(socket, 200, "application/json; charset=utf-8",
                    listener.browserAction(afterId).getBytes("UTF-8"));
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
        } else if ("POST".equals(method) && "/api/wifi-direct".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8",
                    listener.wifiDirect(new JSONObject(new String(body, "UTF-8")))
                            .getBytes("UTF-8"));
        } else if (("POST".equals(method) || "GET".equals(method)) && "/api/tv-ui".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8", listener.tvUi(
                    "POST".equals(method) ? body : null).getBytes("UTF-8"));
        } else if (("POST".equals(method) || "GET".equals(method)) && "/api/radio-ui".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8", listener.radioUi(
                    "POST".equals(method) ? body : null).getBytes("UTF-8"));
        } else if ("POST".equals(method) && "/api/settings".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8",
                    listener.settings(new JSONObject(new String(body, "UTF-8"))).getBytes("UTF-8"));
        } else if ("POST".equals(method) && "/api/user-script/import".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8",
                    listener.importUserScript(new JSONObject(new String(body, "UTF-8")))
                            .getBytes("UTF-8"));
        } else if ("POST".equals(method) && "/api/update/install".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8",
                    listener.installUpdate().getBytes("UTF-8"));
        } else if ("POST".equals(method) && "/api/update/check".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8",
                    listener.checkUpdate().getBytes("UTF-8"));
        } else if ("POST".equals(method) && "/api/tvbus/plugin".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8", listener.uploadTvbusPlugin(body).getBytes("UTF-8"));
        } else if ("POST".equals(method) && "/api/background/upload".equals(path)) {
            send(socket,200,"application/json; charset=utf-8",listener.uploadBackground(body).getBytes("UTF-8"));
        } else if ("GET".equals(method) && "/api/background/image".equals(path)) {
            Resource image=listener.backgroundImage();send(socket,200,image.contentType,image.body);
        } else if ("GET".equals(method) && "/api/background/bing".equals(path)) {
            send(socket,200,"application/json; charset=utf-8",listener.bingWallpapers().getBytes("UTF-8"));
        } else if ("POST".equals(method) && "/api/quick/media".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8",
                    listener.quickMedia(queryParameter(requestTarget, "name"), body).getBytes("UTF-8"));
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
        } else if ("GET".equals(method) && "/api/playlist/channels".equals(path)) {
            send(socket, 200, "application/json; charset=utf-8",
                    listener.playlistChannels(queryParameter(requestTarget, "group")).getBytes("UTF-8"));
        } else if ("GET".equals(method) && "/api/playlist/merge/status".equals(path)) {
            send(socket,200,"application/json; charset=utf-8",listener.playlistImportStatus(queryParameter(requestTarget,"id")).getBytes("UTF-8"));
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
                    queryParameter(requestTarget, "local")),
                    "1".equals(queryParameter(requestTarget, "preview")));
            send(socket, 200, resource.contentType, resource.body);
        } else if ("GET".equals(method) && "/api/browser/download".equals(path)) {
            long eventId;
            try {
                eventId = Long.parseLong(queryParameter(requestTarget, "id"));
            } catch (NumberFormatException invalidId) {
                throw new IOException("下载任务无效");
            }
            Resource resource = listener.browserDownload(eventId);
            sendDownload(socket, resource.contentType, resource.body, resource.downloadName);
        } else if ("GET".equals(method) && "/api/recording/playlist".equals(path)) {
            Resource resource = listener.recording(null);
            sendResource(socket, resource);
        } else if ("GET".equals(method)
                && path.startsWith("/api/recording/resource/")) {
            String token = path.substring("/api/recording/resource/".length());
            Resource resource = listener.recording(token);
            sendResource(socket, resource);
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
        } catch (Exception invalid) {
            throw new IllegalArgumentException("请求参数编码无效");
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
                throw new ControlRequestException(400, "请求头过长");
            }
        }
        return output.size() == 0 ? null : output.toString("ISO-8859-1");
    }

    private void receiveBackgroundUpload(Socket socket,InputStream input,boolean chunked,long length) throws Exception {
        File temporary=listener.createBackgroundUploadFile();
        try {
            try(OutputStream output=new BufferedOutputStream(new FileOutputStream(temporary))){
                if(chunked)copyChunkedUpload(input,output);else copyFixedUpload(input,output,length);
            }
            send(socket,200,"application/json; charset=utf-8",listener.uploadBackgroundFile(temporary).getBytes("UTF-8"));
        } finally {temporary.delete();}
    }
    // File uploads use bounded buffers; compressed file size does not determine heap allocation.
    private static void copyFixedUpload(InputStream input,OutputStream output,long remaining) throws IOException {
        byte[] buffer=transferBuffer.get();
        while(remaining>0){
            int count=input.read(buffer,0,(int)Math.min((long)buffer.length,remaining));
            if(count<0)throw new IOException("图片上传不完整");
            if(count==0)continue;
            output.write(buffer,0,count);remaining-=count;
        }
    }
    private static void copyChunkedUpload(InputStream input,OutputStream output) throws IOException {
        while(true){
            String line=readLine(input);
            if(line==null)throw new IOException("分块请求不完整");
            int extension=line.indexOf(';');long size;
            try{size=Long.parseLong((extension<0?line:line.substring(0,extension)).trim(),16);}
            catch(NumberFormatException error){throw new IOException("分块请求格式错误",error);}
            if(size<0)throw new IOException("分块请求格式错误");
            if(size==0){
                while((line=readLine(input))!=null){if(line.length()==0)return;}
                throw new IOException("分块请求不完整");
            }
            copyFixedUpload(input,output,size);
            line=readLine(input);
            if(line==null||line.length()!=0)throw new IOException("分块请求结尾格式错误");
        }
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
        send(socket, status, contentType, body, cacheControl, "");
    }

    private static void sendDownload(Socket socket, String contentType, byte[] body,
            String fileName) throws IOException {
        String safeName = fileName == null ? "" : fileName
                .replace("\r", "").replace("\n", "").replace("\"", "");
        if (safeName.length() == 0) safeName = "ntv-image";
        send(socket, 200, contentType, body, "no-store",
                "Content-Disposition: attachment; filename=\"" + safeName + "\"\r\n");
    }

    private static void sendMediaDownload(Socket socket, MediaFileDownload download) {
        // Never append a JSON/HTTP error after a partially sent binary response.
        try {
            BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
            StringBuilder headers = new StringBuilder("HTTP/1.1 ").append(download.status)
                    .append(download.status == 206 ? " Partial Content\r\n" : " OK\r\n")
                    .append("Content-Type: ").append(download.contentType).append("\r\n")
                    .append("Content-Disposition: attachment; filename=\"nTv-media\"; filename*=UTF-8''")
                    .append(java.net.URLEncoder.encode(download.name, "UTF-8").replace("+", "%20"))
                    .append("\r\nCache-Control: no-store\r\nConnection: close\r\n");
            if (download.length >= 0) headers.append("Content-Length: ").append(download.length).append("\r\n");
            else headers.append("Transfer-Encoding: chunked\r\n");
            for (String name : new String[]{"Content-Range", "Accept-Ranges", "ETag", "Last-Modified"}) {
                String value = download.connection.getHeaderField(name);
                if (value != null && value.indexOf('\r') < 0 && value.indexOf('\n') < 0)
                    headers.append(name).append(": ").append(value).append("\r\n");
            }
            output.write(headers.append("\r\n").toString().getBytes("ISO-8859-1"));
            output.flush(); // Commit the download response even if the upstream fails on its first read.
            LiteChunkedOutputStream chunks = download.length < 0 ? new LiteChunkedOutputStream(output) : null;
            OutputStream mediaOutput = chunks != null ? chunks : output;
            byte[] buffer = transferBuffer.get(); int count;
            long received = 0;
            while ((count = download.body.read(buffer)) != -1) {
                mediaOutput.write(buffer, 0, count);
                received += count;
            }
            if (download.length >= 0 && received != download.length)
                throw new IOException("Incomplete media download");
            if (chunks != null) chunks.finish();
            output.flush();
        } catch (IOException error) {
            Log.w(TAG, "Media download closed before completion", error);
        } finally {
            try { download.close(); } catch (IOException ignored) { }
        }
    }

    private static void sendResource(Socket socket, Resource resource) throws IOException {
        if (resource.stream == null) {
            send(socket, 200, resource.contentType, resource.body);
            return;
        }
        // Errors after headers must only close the connection, never append JSON
        // to a partially transferred media file. Closing also cancels upstream.
        try {
            BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
            boolean chunked = resource.length < 0;
            output.write(responseHeaders(200, resource.contentType, resource.length,
                    "no-store", chunked ? "Transfer-Encoding: chunked\r\n" : "").getBytes("ISO-8859-1"));
            output.flush();
            LiteChunkedOutputStream chunks = chunked ? new LiteChunkedOutputStream(output) : null;
            OutputStream mediaOutput = chunked ? chunks : output;
            byte[] buffer = transferBuffer.get();
            long received = 0; int count;
            while ((count = resource.stream.read(buffer)) != -1) {
                mediaOutput.write(buffer, 0, count);
                received += count;
            }
            if (resource.length >= 0 && received != resource.length)
                throw new IOException("Incomplete recording resource");
            if (chunks != null) chunks.finish();
            output.flush();
        } catch (IOException error) {
            Log.w(TAG, "Recording transfer closed before completion", error);
        } finally {
            try { resource.close(); } catch (IOException ignored) { }
        }
    }

    private static void send(Socket socket, int status, String contentType, byte[] body,
            String cacheControl, String extraHeaders) throws IOException {
        BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
        output.write(responseHeaders(status, contentType, body.length, cacheControl,
                extraHeaders).getBytes("ISO-8859-1"));
        output.write(body);
        output.flush();
    }

    private static String responseHeaders(int status, String contentType, long length,
            String cacheControl, String extraHeaders) {
        String reason = status == 200 ? "OK" : status == 204 ? "No Content"
                : status == 400 ? "Bad Request" : status == 404 ? "Not Found"
                : status == 403 ? "Forbidden" : status == 409 ? "Conflict"
                : status == 413 ? "Payload Too Large" : "Internal Server Error";
        return "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + (length >= 0 ? "Content-Length: " + length + "\r\n" : "")
                + "Cache-Control: " + cacheControl + "\r\n"
                + "Permissions-Policy: accelerometer=(self), gyroscope=(self)\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Headers: Content-Type\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Access-Control-Allow-Private-Network: true\r\n"
                + "Private-Network-Access-Name: ntv-tv\r\n"
                + "Private-Network-Access-ID: 4e:54:56:54:56:01\r\n"
                + extraHeaders
                + "Connection: close\r\n\r\n";
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
