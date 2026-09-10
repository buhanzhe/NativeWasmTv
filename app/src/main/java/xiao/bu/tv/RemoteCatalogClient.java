package xiao.bu.tv;

import android.util.Base64;
import android.os.Build;
import android.os.SystemClock;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Loads a channel catalog from another nTv device without copying its settings. */
final class RemoteCatalogClient {
    private final java.util.Map<String, String> playbackRoutes = new java.util.HashMap<String, String>();

    synchronized void changePlaybackRoute(String previous, String next) {
        for (java.util.Map.Entry<String, String> route : playbackRoutes.entrySet()) {
            route.setValue(next);
        }
        playbackRoutes.put(previous, next);
        playbackRoutes.remove(next);
    }

    synchronized void clearPlaybackRoutes() { playbackRoutes.clear(); }

    private synchronized String playbackHost(String original) {
        String next = playbackRoutes.get(original);
        return next == null ? original : next;
    }
    static final int TAKEOVER_PROTOCOL = 1;
    static final int APK_TRANSFER_PROTOCOL = 1;
    interface TakeoverStateProvider {
        JSONObject snapshot() throws JSONException;
        int catalogGeneration();
        long networkDelayMs();
        long encodeDelayMs();
        long videoBitrate();
        long audioBitrate();
        String encodeDetail();
        long videoQueueDelayMs();
        long videoSendDelayMs();
        void onRoundTrip(long delayMs);
        void onMessage(JSONObject message) throws Exception;
    }

    private static final String SOURCE_PREFIX = "ntvremote:";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int POINTER_CONNECT_TIMEOUT_MS = 1500;
    private static final int POINTER_READ_TIMEOUT_MS = 2500;
    private static final long RESOLVE_TIMEOUT_MS = 30000L;
    private static final long TAKEOVER_HEARTBEAT_MS = 1000L;
    private final Object takeoverSessionLock = new Object();
    private volatile int takeoverSessionGeneration;
    private volatile String takeoverSessionId = "";
    private volatile long lastTakeoverResponseAt;

    long lastTakeoverResponseAt() {
        return lastTakeoverResponseAt;
    }
    private Socket takeoverSessionSocket;
    private static volatile int[][] hardwareAvcCastProfiles;
    private static volatile int[][] hardwareHevcCastProfiles;
    private static volatile Boolean hardwareHevcDecoder;

    boolean isTelevisionAvailable(String serverUrl) {
        try {
            String baseUrl = normalizeServerUrl(serverUrl);
            if (baseUrl.length() == 0) return false;
            JSONObject state = requestJson(baseUrl + "/api/state?view=home",
                    "GET", null, 450, 650);
            return state.optBoolean("ok", false)
                    && state.optBoolean("isTelevision", false)
                    && state.optInt("takeoverProtocol", 0) >= TAKEOVER_PROTOCOL;
        } catch (Exception unavailable) {
            return false;
        }
    }

    /** Maximum decoder size for each useful frame rate, independent of screen size. */
    private static int[][] hardwareCastProfiles(String mimeType) {
        int[][] cached = "video/hevc".equals(mimeType)
                ? hardwareHevcCastProfiles : hardwareAvcCastProfiles;
        if (cached != null) return cached;
        int[] rates = new int[] {120, 60, 30};
        int[][] best = new int[rates.length][3];
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                    String name = codec.getName().toLowerCase(java.util.Locale.US);
                    if (codec.isEncoder() || name.contains(".google.") || name.startsWith("c2.android.")
                            || name.contains(".secure") || name.contains(".sw.")) continue;
                    for (String type : codec.getSupportedTypes()) {
                        if (!mimeType.equalsIgnoreCase(type)) continue;
                        MediaCodecInfo.VideoCapabilities video = codec.getCapabilitiesForType(type).getVideoCapabilities();
                        for (int rateIndex = 0; rateIndex < rates.length; rateIndex++) {
                            int rate = rates[rateIndex];
                            for (int[] size : new int[][] {{3840,2160},{2560,1440},
                                    {1920,1080},{1280,720},{960,540},{640,360}}) {
                                if (!video.areSizeAndRateSupported(size[0], size[1], rate)) continue;
                                if ((long) size[0] * size[1]
                                        > (long) best[rateIndex][0] * best[rateIndex][1]) {
                                    best[rateIndex] = new int[] {size[0], size[1], rate};
                                }
                                break;
                            }
                        }
                    }
                }
            } catch (RuntimeException ignored) { /* Keep the conservative display fallback. */ }
        }
        if ("video/hevc".equals(mimeType)) hardwareHevcCastProfiles = best;
        else hardwareAvcCastProfiles = best;
        return best;
    }

    private static int[] hardwareCastLimit() {
        int[] best = new int[] {0, 0, 30};
        for (int[] profile : hardwareCastProfiles("video/avc")) {
            if (profile[0] > best[0]
                    || profile[0] == best[0] && profile[2] > best[2]) best = profile;
        }
        return best;
    }

    private static JSONArray hardwareCastProfilesJson() throws JSONException {
        JSONArray result = new JSONArray();
        appendHardwareCastProfiles(result, "h264", hardwareCastProfiles("video/avc"));
        appendHardwareCastProfiles(result, "h265", hardwareCastProfiles("video/hevc"));
        return result;
    }

    private static void appendHardwareCastProfiles(JSONArray result, String codec,
            int[][] profiles) throws JSONException {
        for (int[] profile : profiles) {
            if (profile[0] <= 0 || profile[1] <= 0 || profile[2] <= 0) continue;
            result.put(new JSONObject().put("codec", codec).put("width", profile[0])
                    .put("height", profile[1]).put("fps", profile[2]));
        }
    }

    private static boolean hasHardwareHevcDecoder() {
        Boolean cached = hardwareHevcDecoder;
        if (cached != null) return cached;
        boolean supported = false;
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                for (MediaCodecInfo codec
                        : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                    String name = codec.getName().toLowerCase(java.util.Locale.US);
                    if (codec.isEncoder() || name.contains(".google.")
                            || name.startsWith("c2.android.") || name.contains(".secure")
                            || name.contains(".sw.")) continue;
                    for (String type : codec.getSupportedTypes()) {
                        if ("video/hevc".equalsIgnoreCase(type)) {
                            supported = true;
                            break;
                        }
                    }
                    if (supported) break;
                }
            } catch (RuntimeException ignored) { }
        }
        hardwareHevcDecoder = supported;
        return supported;
    }

    ChannelCatalog.Group[] loadCatalog(String serverUrl) throws IOException, JSONException {
        String baseUrl = normalizeServerUrl(serverUrl);
        JSONObject state;
        try {
            state = getJson(baseUrl + "/api/catalog");
        } catch (IOException unavailable) {
            state = getJson(baseUrl + "/api/state");
        }
        String upstream = state.optString("remoteCatalogUrl", "").trim();
        if (upstream.length() == 0) {
            JSONObject settings = state.optJSONObject("settings");
            upstream = settings == null ? ""
                    : settings.optString("remoteCatalogUrl", "").trim();
        }
        if (upstream.length() > 0) {
            throw new IOException("所选手机正在接管另一台设备，请先退出接管模式");
        }
        JSONArray jsonGroups = state.optJSONArray("groups");
        if (jsonGroups == null) {
            throw new IOException("手机没有返回频道目录");
        }
        List<ChannelCatalog.Group> groups = new ArrayList<ChannelCatalog.Group>();
        int fallbackNumber = 1;
        for (int groupIndex = 0; groupIndex < jsonGroups.length(); groupIndex++) {
            JSONObject jsonGroup = jsonGroups.optJSONObject(groupIndex);
            if (jsonGroup == null) {
                continue;
            }
            String groupName = jsonGroup.optString("name", "").trim();
            // Favorites are deliberately local to each device. The TV builds its own
            // fixed favorites entry when setCustomGroups() is called.
            if (groupName.length() == 0 || "我的收藏".equals(groupName)) {
                continue;
            }
            JSONArray jsonChannels = jsonGroup.optJSONArray("channels");
            if (jsonChannels == null || jsonChannels.length() == 0) {
                continue;
            }
            List<Channel> channels = new ArrayList<Channel>();
            for (int channelIndex = 0; channelIndex < jsonChannels.length(); channelIndex++) {
                JSONObject jsonChannel = jsonChannels.optJSONObject(channelIndex);
                if (jsonChannel == null) {
                    continue;
                }
                String name = jsonChannel.optString("name", "").trim();
                if (name.length() == 0) {
                    continue;
                }
                String number = jsonChannel.optString("number", "").trim();
                if (number.length() == 0) {
                    number = String.valueOf(fallbackNumber);
                }
                fallbackNumber++;
                int sourceCount = Math.max(1, jsonChannel.optInt("sourceCount", 1));
                String[] sources = new String[sourceCount];
                for (int sourceIndex = 0; sourceIndex < sourceCount; sourceIndex++) {
                    sources[sourceIndex] = encodeSource(baseUrl, groupIndex,
                            channelIndex, sourceIndex);
                }
                channels.add(new Channel(number, name,
                        "remote_" + groupIndex + "_" + channelIndex,
                        sources, null, null, null,
                        jsonChannel.optString("epgId", null)).withLogo(jsonChannel.optString("logoUrl", "")));
            }
            if (!channels.isEmpty()) {
                groups.add(new ChannelCatalog.Group(groupName,
                        ChannelCatalog.SOURCE_CUSTOM,
                        channels.toArray(new Channel[channels.size()])));
            }
        }
        if (groups.isEmpty()) {
            throw new IOException("手机频道目录为空");
        }
        return groups.toArray(new ChannelCatalog.Group[groups.size()]);
    }

    Result resolve(String encodedSource, int receiverWidth, int receiverHeight,
            boolean lowResourceReceiver, String receiverUrl)
            throws IOException, JSONException {
        Source source = decodeSource(encodedSource);
        // A retained catalog still contains its original LAN URLs. Resolve them
        // against the authenticated session route without rebuilding the catalog.
        source = new Source(playbackHost(source.baseUrl), source.groupIndex,
                source.channelIndex, source.sourceIndex);
        JSONObject command = new JSONObject();
        command.put("action", "play");
        command.put("group", source.groupIndex);
        command.put("channel", source.channelIndex);
        command.put("source", source.sourceIndex);
        command.put("receiver", true);
        String normalizedReceiverUrl = normalizeServerUrl(receiverUrl);
        if (normalizedReceiverUrl.length() > 0) {
            command.put("receiverUrl", normalizedReceiverUrl);
        }
        // Tell the phone how much video this receiver can consume. The sender keeps
        // the user's selected quality, capped by hardware decoder capabilities.
        // KitKat/low-RAM receivers stay at 1080p: several old
        // Qualcomm decoders abort (and then exhaust memory through software fallback)
        // when handed a 2160p H.264 stream.
        int landscapeWidth = Math.max(receiverWidth, receiverHeight);
        int landscapeHeight = Math.min(receiverWidth, receiverHeight);
        int castWidth;
        int castHeight;
        if (lowResourceReceiver) {
            castWidth = 1920;
            castHeight = 1080;
        } else if (landscapeHeight <= 720) {
            castWidth = 1280;
            castHeight = 720;
        } else if (landscapeHeight <= 1080) {
            castWidth = 1920;
            castHeight = 1080;
        } else if (landscapeHeight <= 1440) {
            castWidth = 2560;
            castHeight = 1440;
        } else {
            castWidth = 3840;
            castHeight = 2160;
        }
        // Old/low-RAM receivers in practice top out near 25 decoded frames per
        // second at 720p. Sending 30 fps fills their UDP/socket queue by roughly
        // four frames every second and turns a healthy 20 ms link into 500+ ms
        // pointer lag. Modern receivers still advertise 30/60/120 fps profiles.
        int castFps = lowResourceReceiver ? 25 : 30;
        if (!lowResourceReceiver) {
            int[] limit = hardwareCastLimit();
            if (limit[0] > 0) {
                castWidth = limit[0];
                castHeight = limit[1];
                castFps = limit[2];
            }
        }
        int castBitrate = lowResourceReceiver ? 4_000_000 : 3_000_000;
        command.put("castWidth", castWidth);
        command.put("castHeight", castHeight);
        command.put("castFps", castFps);
        command.put("castH265", !lowResourceReceiver && hasHardwareHevcDecoder());
        if (!lowResourceReceiver) command.put("castProfiles", hardwareCastProfilesJson());
        command.put("castBitrate", castBitrate);
        command.put("castLowResource", lowResourceReceiver);
        JSONObject accepted = postJson(source.baseUrl + "/api/control", command);
        if (!accepted.optBoolean("ok", false)) {
            throw new IOException(accepted.optString("message", "手机拒绝播放频道"));
        }
        final int acceptedRequestId = accepted.optInt("playRequestId", -1);

        long deadline = System.currentTimeMillis() + RESOLVE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            JSONObject state;
            try {
                state = getJson(source.baseUrl + "/api/playback");
            } catch (IOException unavailable) {
                state = getJson(source.baseUrl + "/api/state");
            }
            JSONObject current = state.optJSONObject("current");
            JSONObject playback = state.optJSONObject("remotePlayback");
            if (current != null && playback != null
                    && (acceptedRequestId < 0
                        || state.optInt("playRequestId", -1) == acceptedRequestId)
                    && current.optInt("groupIndex", -1) == source.groupIndex
                    && current.optInt("channelIndex", -1) == source.channelIndex
                    && current.optInt("sourceIndex", -1) == source.sourceIndex
                    && playback.optBoolean("available", false)
                    && playback.optInt("sourceIndex", -1) == source.sourceIndex) {
                String mode = playback.optString("sourceMode", "proxy");
                if ("cast".equals(mode)) {
                    String castUrl = playback.optString("sourceUrl", "").trim();
                    if (castUrl.length() > 0) {
                        return new Result(castUrl, true, playback.optString("castTransport", "tcp"));
                    }
                }
                if ("direct".equals(mode)) {
                    String directUrl = playback.optString("sourceUrl", "").trim();
                    if (directUrl.length() > 0) {
                        detachPhonePlayer(source.baseUrl);
                        return new Result(directUrl, true);
                    }
                }
                String path = playback.optString(
                        "playlistPath", "/api/recording/playlist");
                detachPhonePlayer(source.baseUrl);
                return new Result(absoluteUrl(source.baseUrl, path), true);
            }
            try {
                Thread.sleep(80L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("等待手机解析时已取消");
            }
        }
        throw new IOException("等待手机解析频道超时");
    }

    private static void detachPhonePlayer(String baseUrl) {
        try {
            postJson(baseUrl + "/api/control",
                    new JSONObject().put("action", "detachRemote"));
        } catch (Exception ignored) {
            // The returned stream remains usable even when talking to an older host.
        }
    }

    void release(String serverUrl, String sessionId) {
        try {
            String baseUrl = normalizeServerUrl(serverUrl);
            if (baseUrl.length() > 0) {
                postJson(baseUrl + "/api/control",
                        new JSONObject().put("action", "releaseReceiver")
                                .put("sessionId", sessionId));
            }
        } catch (Exception ignored) {
            // The receiver can still return to its local catalog when the source
            // device is already offline.
        }
    }

    void disconnectReceiver(String receiverUrl, String sessionId) {
        try {
            String baseUrl = normalizeServerUrl(receiverUrl);
            if (baseUrl.length() > 0 && sessionId.length() > 0) {
                requestJson(baseUrl + "/api/control", "POST",
                        new JSONObject().put("action", "endTakeover")
                                .put("sessionId", sessionId).toString().getBytes("UTF-8"),
                        600, 900);
            }
        } catch (Exception ignored) {
            // Local takeover state is still released if the receiver went offline.
        }
    }

    void claimReceiver(String receiverUrl, String hostUrl,
            TakeoverStateProvider stateProvider)
            throws IOException, JSONException {
        final int claimGeneration = takeoverSessionGeneration;
        final String claimSessionId = UUID.randomUUID().toString();
        String receiverBase = normalizeServerUrl(receiverUrl);
        String hostBase = normalizeServerUrl(hostUrl);
        if (receiverBase.length() == 0 || hostBase.length() == 0) {
            throw new IOException("接管地址无效");
        }
        if (receiverBase.equalsIgnoreCase(hostBase)) {
            throw new IOException("不能接管当前设备");
        }
        JSONObject receiverState;
        try {
            receiverState = getJson(receiverBase + "/api/state");
        } catch (IOException error) {
            throw new IOException("无法连接电视。请确认电视端 nTv 正在运行，"
                    + "输入的是电视调试信息中的 IP，并将电视端更新到最新版");
        }
        if (receiverState.optInt("takeoverProtocol", 0) < TAKEOVER_PROTOCOL) {
            throw new IOException("电视端版本较旧，不支持当前接管协议，请先更新电视端 nTv");
        }
        if (receiverState.optString("takeoverReceiverUrl", "").trim().length() > 0) {
            throw new IOException("目标设备正在接管另一台电视");
        }
        JSONObject settings = receiverState.optJSONObject("settings");
        String currentHost = settings == null ? ""
                : settings.optString("remoteCatalogUrl", "").trim();
        if (currentHost.length() > 0) {
            currentHost = normalizeServerUrl(currentHost);
            if (!hostBase.equalsIgnoreCase(currentHost)) {
                throw new IOException("电视已被另一台设备接管");
            }
        }
        JSONObject result = postJson(receiverBase + "/api/settings",
                new JSONObject().put("remoteCatalogUrl", hostBase)
                        .put("claimSessionId", claimSessionId));
        if (!result.optBoolean("ok", false)) {
            throw new IOException(result.optString("message", "电视拒绝接管请求"));
        }
        synchronized (takeoverSessionLock) {
            if (claimGeneration == takeoverSessionGeneration) {
                startTakeoverSession(receiverBase, hostBase, stateProvider,
                        claimSessionId, null, null);
                return;
            }
        }
        disconnectReceiver(receiverBase, claimSessionId);
        throw new IOException("接管操作已取消");
    }

    JSONObject receiverState(String receiverUrl) throws IOException, JSONException {
        return requestJson(normalizeServerUrl(receiverUrl) + "/api/state?view=cast",
                "GET", null, 600, 900);
    }

    JSONObject wifiDirect(String receiverUrl, String action)
            throws IOException, JSONException {
        return wifiDirect(receiverUrl, action, false);
    }

    JSONObject wifiDirect(String receiverUrl, String action, boolean controllerGroupOwner)
            throws IOException, JSONException {
        return wifiDirect(receiverUrl, action, controllerGroupOwner, "");
    }

    JSONObject wifiDirect(String receiverUrl, String action, boolean controllerGroupOwner,
            String controllerDeviceAddress) throws IOException, JSONException {
        return wifiDirect(receiverUrl, action, controllerGroupOwner,
                controllerDeviceAddress, "");
    }

    JSONObject wifiDirect(String receiverUrl, String action, boolean controllerGroupOwner,
            String controllerDeviceAddress, String controllerDeviceName)
            throws IOException, JSONException {
        return postJson(normalizeServerUrl(receiverUrl) + "/api/wifi-direct",
                new JSONObject().put("action", action)
                        .put("controllerGroupOwner", controllerGroupOwner)
                        .put("controllerDeviceAddress", controllerDeviceAddress)
                        .put("controllerDeviceName", controllerDeviceName));
    }

    /** Status polling crosses the brief STA/P2P route handover on old Android.
     * Keep each attempt short so one transient socket failure cannot consume the
     * complete Direct connection window. */
    JSONObject wifiDirectStatus(String receiverUrl) throws IOException, JSONException {
        return requestJson(normalizeServerUrl(receiverUrl) + "/api/wifi-direct", "POST",
                new JSONObject().put("action", "status").toString().getBytes("UTF-8"),
                600, 900);
    }

    String activeTakeoverSessionId() { return takeoverSessionId; }

    private static final class SessionConnection {
        final Socket socket;
        final BufferedReader input;
        final BufferedWriter output;
        SessionConnection(Socket socket) throws IOException {
            this.socket = socket;
            input = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));
        }
    }

    /** Make the new authenticated connection before replacing the working LAN socket. */
    boolean switchReceiverRoute(String receiverUrl, String previousHostUrl, String hostUrl,
            String expectedSessionId, TakeoverStateProvider provider) throws Exception {
        if (expectedSessionId.length() == 0 || !expectedSessionId.equals(takeoverSessionId)) return false;
        String nextSessionId = UUID.randomUUID().toString();
        Socket socket = new Socket();
        boolean accepted = false;
        try {
            URL receiver = new URL(normalizeServerUrl(receiverUrl));
            socket.connect(new InetSocketAddress(receiver.getHost(),
                    receiver.getPort() > 0 ? receiver.getPort() : receiver.getDefaultPort()), 1000);
            socket.setSoTimeout(1000);
            socket.setTcpNoDelay(true);
            SessionConnection connection = new SessionConnection(socket);
            JSONObject hello = sessionMessage(provider, "hello", nextSessionId, hostUrl)
                    .put("previousSessionId", expectedSessionId)
                    .put("previousHostUrl", previousHostUrl);
            connection.output.write("NTV-TAKEOVER/1\r\n" + hello.toString() + "\r\n");
            connection.output.flush();
            requireSessionAck(connection.input);
            accepted = startTakeoverSession(receiverUrl, hostUrl, provider,
                    nextSessionId, connection, expectedSessionId);
            return accepted;
        } finally {
            if (!accepted) closeQuietly(socket);
        }
    }

    private boolean startTakeoverSession(final String receiverBase, final String hostBase,
            final TakeoverStateProvider stateProvider, final String sessionId,
            final SessionConnection ready, String expectedSessionId) {
        final int generation;
        synchronized (takeoverSessionLock) {
            if (expectedSessionId != null && !expectedSessionId.equals(takeoverSessionId)) return false;
            closeTakeoverSessionLocked();
            generation = ++takeoverSessionGeneration;
            takeoverSessionId = sessionId;
            lastTakeoverResponseAt = SystemClock.elapsedRealtime();
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                SessionConnection prepared = ready;
                if (generation != takeoverSessionGeneration && prepared != null) closeQuietly(prepared.socket);
                while (generation == takeoverSessionGeneration) {
                    Socket socket = null;
                    try {
                        SessionConnection connection = prepared;
                        prepared = null;
                        if (connection != null) {
                            socket = connection.socket;
                        } else {
                            URL receiver = new URL(receiverBase);
                            socket = new Socket();
                            synchronized (takeoverSessionLock) {
                                if (generation != takeoverSessionGeneration) { socket.close(); return; }
                                takeoverSessionSocket = socket;
                            }
                            socket.connect(new InetSocketAddress(receiver.getHost(),
                                    receiver.getPort() > 0 ? receiver.getPort() : receiver.getDefaultPort()), 1500);
                            socket.setSoTimeout(1000);
                            socket.setTcpNoDelay(true);
                            connection = new SessionConnection(socket);
                            connection.output.write("NTV-TAKEOVER/1\r\n");
                            connection.output.write(sessionMessage(stateProvider, "hello", sessionId, hostBase).toString());
                            connection.output.write("\r\n");
                            connection.output.flush();
                            requireSessionAck(connection.input);
                        }
                        synchronized (takeoverSessionLock) {
                            if (generation != takeoverSessionGeneration) { socket.close(); return; }
                            takeoverSessionSocket = socket;
                        }
                        BufferedReader input = connection.input;
                        BufferedWriter output = connection.output;
                        recordTakeoverResponse(generation);
                        socket.setSoTimeout(1000);
                        long nextHeartbeatAt = 0L;
                        long heartbeatSentAt = 0L;
                        while (generation == takeoverSessionGeneration) {
                            long now = SystemClock.elapsedRealtime();
                            if (now >= nextHeartbeatAt) {
                                JSONObject heartbeat = sessionMessage(stateProvider,
                                        "heartbeat", sessionId, hostBase);
                                output.write(heartbeat.toString());
                                output.write("\r\n");
                                output.flush();
                                heartbeatSentAt = SystemClock.elapsedRealtime();
                                nextHeartbeatAt = now + TAKEOVER_HEARTBEAT_MS;
                            }
                            try {
                                String line = input.readLine();
                                if (line == null) {
                                    throw new IOException("接管会话已断开");
                                }
                                JSONObject incoming = new JSONObject(line);
                                if (incoming.optBoolean("ok", false)) {
                                    recordTakeoverResponse(generation);
                                    if (generation == takeoverSessionGeneration && stateProvider != null && heartbeatSentAt > 0L) {
                                        stateProvider.onRoundTrip(Math.max(0L,
                                                SystemClock.elapsedRealtime()
                                                        - heartbeatSentAt));
                                    }
                                } else if (stateProvider != null) {
                                    stateProvider.onMessage(incoming);
                                }
                            } catch (SocketTimeoutException timeout) {
                                // Wake periodically so heartbeat scheduling does not
                                // need another thread; incoming pointer data wakes the
                                // read immediately and is not delayed by this timeout.
                            }
                            if (SystemClock.elapsedRealtime() - lastTakeoverResponseAt >= 3000L) {
                                throw new IOException("接管端心跳响应超时");
                            }
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Exception ignored) {
                        // Wi-Fi handovers can break an established socket. Reconnect
                        // with the same session id before the receiver's 3 s lease
                        // expires instead of leaving a stale takeover behind.
                    } finally {
                        synchronized (takeoverSessionLock) {
                            if (takeoverSessionSocket == socket) {
                                takeoverSessionSocket = null;
                            }
                        }
                        closeQuietly(socket);
                    }
                    if (generation == takeoverSessionGeneration) {
                        try {
                            Thread.sleep(750L);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }
        }, "takeover-session");
        thread.start();
        return true;
    }

    void stopTakeoverSession() {
        detachTakeoverSession();
    }

    String detachTakeoverSession() {
        synchronized (takeoverSessionLock) {
            String stoppedSession = takeoverSessionId;
            takeoverSessionGeneration++;
            takeoverSessionId = "";
            lastTakeoverResponseAt = 0L;
            closeTakeoverSessionLocked();
            return stoppedSession;
        }
    }

    private void recordTakeoverResponse(int generation) {
        synchronized (takeoverSessionLock) {
            if (generation == takeoverSessionGeneration) {
                lastTakeoverResponseAt = SystemClock.elapsedRealtime();
            }
        }
    }

    boolean acceptsRelease(String sessionId) {
        String active = takeoverSessionId;
        return active.length() == 0 || active.equals(sessionId);
    }

    private void closeTakeoverSessionLocked() {
        closeQuietly(takeoverSessionSocket);
        takeoverSessionSocket = null;
    }

    private static JSONObject sessionMessage(TakeoverStateProvider provider, String type,
            String sessionId, String hostBase) throws JSONException {
        JSONObject message;
        if (provider == null) {
            message = new JSONObject();
        } else if ("heartbeat".equals(type)) {
            message = new JSONObject()
                    .put("catalogGeneration", provider.catalogGeneration())
                    .put("networkDelayMs", provider.networkDelayMs())
                    .put("encodeDelayMs", provider.encodeDelayMs())
                    .put("castVideoBitrate", provider.videoBitrate())
                    .put("castAudioBitrate", provider.audioBitrate())
                    .put("encodeDetail", provider.encodeDetail())
                    .put("videoQueueDelayMs", provider.videoQueueDelayMs())
                    .put("videoSendDelayMs", provider.videoSendDelayMs());
        } else {
            message = provider.snapshot();
        }
        if (message == null) {
            message = new JSONObject();
        }
        message.put("type", type)
                .put("protocol", 1)
                .put("sessionId", sessionId)
                .put("hostUrl", hostBase);
        return message;
    }

    private static void requireSessionAck(BufferedReader input)
            throws IOException, JSONException {
        String line = input.readLine();
        if (line == null) {
            throw new IOException("接管会话已断开");
        }
        JSONObject ack = new JSONObject(line);
        if (!ack.optBoolean("ok", false)) {
            throw new IOException(ack.optString("message", "接管会话被拒绝"));
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    JSONObject pointer(String serverUrl, JSONObject request)
            throws IOException, JSONException {
        return requestJson(normalizeServerUrl(serverUrl) + "/api/pointer", "POST",
                request.toString().getBytes("UTF-8"), POINTER_CONNECT_TIMEOUT_MS,
                POINTER_READ_TIMEOUT_MS);
    }

    JSONObject controlReceiver(String receiverUrl, JSONObject request)
            throws IOException, JSONException {
        return postJson(normalizeServerUrl(receiverUrl) + "/api/control", request);
    }

    JSONObject adjustReceiverVolume(String receiverUrl, int direction)
            throws IOException, JSONException {
        return requestJson(normalizeServerUrl(receiverUrl) + "/api/control", "POST",
                new JSONObject().put("action", "volume").put("direction", direction)
                        .toString().getBytes("UTF-8"), 1000, 1000);
    }

    JSONObject mediaState(String receiverUrl) throws IOException, JSONException {
        return mediaState(receiverUrl, true);
    }

    JSONObject mediaState(String receiverUrl, boolean detailed)
            throws IOException, JSONException {
        return getJson(normalizeServerUrl(receiverUrl) + "/api/media"
                + (detailed ? "" : "?detail=0"));
    }

    JSONObject mediaControl(String receiverUrl, JSONObject request)
            throws IOException, JSONException {
        return postJson(normalizeServerUrl(receiverUrl) + "/api/media/control", request);
    }

    JSONObject pushApk(String receiverUrl, String fileName, byte[] apk)
            throws IOException, JSONException {
        String receiverBase = normalizeServerUrl(receiverUrl);
        String activeSession = takeoverSessionId;
        if (receiverBase.length() == 0) {
            throw new IOException("请填写电视 IP，再发送 APK");
        }
        JSONObject receiverState;
        try {
            receiverState = getJson(receiverBase + "/api/state");
        } catch (IOException error) {
            throw new IOException("无法连接电视，请确认电视端 nTv 正在运行");
        }
        if (receiverState.optInt("apkTransferProtocol", 0) < APK_TRANSFER_PROTOCOL) {
            throw new IOException("电视端版本较旧，不支持 APK 投送，请先更新电视端 nTv");
        }
        int remoteLimit = receiverState.optInt("apkTransferMaxBytes", 0);
        if (remoteLimit > 0 && apk.length > remoteLimit) {
            throw new IOException("APK 超过电视可接收的 "
                    + Math.max(1, remoteLimit / 1024 / 1024) + " MB");
        }
        String url = receiverBase + "/api/apk/upload?name="
                + URLEncoder.encode(fileName == null ? "" : fileName, "UTF-8");
        if (activeSession.length() > 0) {
            url += "&sessionId=" + URLEncoder.encode(activeSession, "UTF-8");
        }
        return requestApk(url, apk);
    }

    private static JSONObject requestApk(String url, byte[] body)
            throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(120000);
        connection.setUseCaches(false);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/vnd.android.package-archive");
        connection.setFixedLengthStreamingMode(body.length);
        OutputStream output = connection.getOutputStream();
        try {
            output.write(body);
        } finally {
            output.close();
        }
        int status = connection.getResponseCode();
        InputStream input = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        byte[] response;
        try {
            response = readFully(input);
        } finally {
            if (input != null) input.close();
            connection.disconnect();
        }
        String text = new String(response, "UTF-8");
        if (status < 200 || status >= 300) {
            try {
                throw new IOException(new JSONObject(text).optString("message", "电视拒绝接收 APK"));
            } catch (JSONException ignored) {
                throw new IOException("电视接口返回 " + status);
            }
        }
        return new JSONObject(text);
    }

    static boolean isRemoteSource(String sourceUrl) {
        return sourceUrl != null && sourceUrl.startsWith(SOURCE_PREFIX);
    }

    static String normalizeServerUrl(String value) throws IOException {
        if (value == null) {
            return "";
        }
        String result = value.trim();
        if (result.length() == 0) {
            return "";
        }
        if (!result.startsWith("http://") && !result.startsWith("https://")) {
            throw new IOException("手机地址仅支持 HTTP 或 HTTPS");
        }
        if (result.endsWith("index.html")) {
            result = result.substring(0, result.length() - "index.html".length());
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String encodeSource(String baseUrl, int groupIndex,
            int channelIndex, int sourceIndex) {
        String encodedBase = Base64.encodeToString(baseUrl.getBytes(),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return SOURCE_PREFIX + encodedBase + ":" + groupIndex + ":"
                + channelIndex + ":" + sourceIndex;
    }

    private static Source decodeSource(String encodedSource) throws IOException {
        if (!isRemoteSource(encodedSource)) {
            throw new IOException("远程频道描述无效");
        }
        String[] parts = encodedSource.substring(SOURCE_PREFIX.length()).split(":", -1);
        if (parts.length != 4) {
            throw new IOException("远程频道描述不完整");
        }
        try {
            String baseUrl = new String(Base64.decode(parts[0],
                    Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
            return new Source(normalizeServerUrl(baseUrl),
                    Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]));
        } catch (Exception error) {
            throw new IOException("无法读取远程频道描述");
        }
    }

    private static JSONObject getJson(String url) throws IOException, JSONException {
        return requestJson(url, "GET", null);
    }

    private static JSONObject postJson(String url, JSONObject body)
            throws IOException, JSONException {
        return requestJson(url, "POST", body.toString().getBytes("UTF-8"));
    }

    private static JSONObject requestJson(String url, String method, byte[] body)
            throws IOException, JSONException {
        return requestJson(url, method, body, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }

    private static JSONObject requestJson(String url, String method, byte[] body,
            int connectTimeoutMs, int readTimeoutMs) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.setUseCaches(false);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(body.length);
            OutputStream output = connection.getOutputStream();
            try {
                output.write(body);
            } finally {
                output.close();
            }
        }
        int status = connection.getResponseCode();
        InputStream input = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        byte[] response;
        try {
            response = readFully(input);
        } finally {
            if (input != null) {
                input.close();
            }
            connection.disconnect();
        }
        String text = new String(response, "UTF-8");
        if (status < 200 || status >= 300) {
            throw new IOException("手机接口返回 " + status + "：" + text);
        }
        return new JSONObject(text);
    }

    private static byte[] readFully(InputStream input) throws IOException {
        if (input == null) {
            return new byte[0];
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String absoluteUrl(String baseUrl, String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        return baseUrl + (path.startsWith("/") ? path : "/" + path);
    }

    static final class Result {
        final String url;
        final boolean directDataSource;
        final String castTransport;

        Result(String url, boolean directDataSource) {
            this(url, directDataSource, "tcp");
        }

        Result(String url, boolean directDataSource, String transport) {
            this.castTransport = "udp".equals(transport) ? "udp" : "tcp";
            this.url = url;
            this.directDataSource = directDataSource;
        }
    }

    private static final class Source {
        final String baseUrl;
        final int groupIndex;
        final int channelIndex;
        final int sourceIndex;

        Source(String baseUrl, int groupIndex, int channelIndex, int sourceIndex) {
            this.baseUrl = baseUrl;
            this.groupIndex = groupIndex;
            this.channelIndex = channelIndex;
            this.sourceIndex = sourceIndex;
        }
    }
}
