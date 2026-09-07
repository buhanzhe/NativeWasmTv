package xiao.bu.tv;

import android.media.MediaCodec;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Small single-viewer RTSP server with H.264/H.265 and optional AAC RTP tracks. */
final class RtspCastServer implements Closeable {
    private static final String TAG = "RtspCastServer";
    private static final int FIRST_PORT = 8554;
    private static final int LAST_PORT = 8564;
    private static final int RTP_PAYLOAD_BYTES = 1200;
    // Interleaved TCP has no datagram MTU. Larger chunks avoid thousands of
    // packet allocations/locks per second at 4K bitrates; UDP retains 1200 bytes.
    private static final int TCP_RTP_PAYLOAD_BYTES = 16 * 1024;
    private static final int VIDEO_PAYLOAD_TYPE = 96;
    private static final int AUDIO_PAYLOAD_TYPE = 97;
    // Old IJK/MediaCodec receivers can briefly stop draining their TCP socket while
    // the decoder changes buffers. Treat only a sustained kernel write stall as a
    // dead receiver; a 100 ms audio hiccup is normal on Android 7 and must not tear
    // down the whole RTSP session.
    static final long WRITE_STALL_NS = 1_500_000_000L;

    private final Object clientLock = new Object();
    // Serialize complete access units: cached startup frames must not interleave
    // FU-A fragments with the live encoder thread.
    private final Object videoSendLock = new Object();
    private final Object audioSendLock = new Object();
    private final byte[] videoPacket = new byte[4 + 12 + TCP_RTP_PAYLOAD_BYTES];
    private final byte[] audioPacket = new byte[12 + 4 + 8191];
    private final int sendBufferBytes;
    private final String videoCodec;
    private final int videoFps;
    private final int videoSsrc = new Random().nextInt();
    private final int audioSsrc = new Random().nextInt();
    private final DatagramSocket videoSocket;
    private final DatagramSocket audioSocket;
    private final ServerSocket serverSocket;
    private volatile boolean running = true;
    private volatile boolean audioEnabled;
    private Thread acceptThread;
    private Client client;
    private int videoSequence = new Random().nextInt(0xffff);
    private int audioSequence = new Random().nextInt(0xffff);
    private byte[] sps;
    private byte[] pps;
    private byte[] vps;
    private byte[] audioConfig = new byte[] { 0x11, (byte) 0x90 };
    private volatile boolean syncFrameRequested;
    private volatile long slowWriteDisconnects;
    private volatile long sentVideoBytes;
    private volatile long sentAudioBytes;

    RtspCastServer(boolean audioEnabled) throws IOException {
        this(audioEnabled, 8_000_000, "h264", 30);
    }

    RtspCastServer(boolean audioEnabled, int bitrate) throws IOException {
        this(audioEnabled, bitrate, "h264", 30);
    }

    RtspCastServer(boolean audioEnabled, int bitrate, String videoCodec) throws IOException {
        this(audioEnabled, bitrate, videoCodec, 30);
    }

    RtspCastServer(boolean audioEnabled, int bitrate, String videoCodec, int videoFps)
            throws IOException {
        this.audioEnabled = audioEnabled;
        this.videoCodec = "h265".equals(videoCodec) ? "h265" : "h264";
        this.videoFps = Math.max(1, videoFps);
        // About one access unit, bounded even for large requested rates.
        // A fixed 32 KiB window throttles 30 Mbps streams unnecessarily.
        sendBufferBytes = Math.max(32 * 1024,
                Math.min(192 * 1024, bitrate / 8 / this.videoFps));
        serverSocket = bindServer();
        serverSocket.setSoTimeout(100);
        videoSocket = new DatagramSocket();
        audioSocket = new DatagramSocket();
    }

    private static ServerSocket bindServer() throws IOException {
        IOException last = null;
        for (int port = FIRST_PORT; port <= LAST_PORT; port++) {
            ServerSocket socket = new ServerSocket();
            try {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(port));
                return socket;
            } catch (IOException error) {
                last = error;
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
        throw new IOException("RTSP 端口 " + FIRST_PORT + "-" + LAST_PORT + " 均不可用", last);
    }

    void start() {
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "webview-cast-rtsp");
        acceptThread.start();
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    boolean needsSyncFrame() { return syncFrameRequested; }

    boolean hasVideoClient() {
        Client target = playingClient();
        return target != null && target.videoSetup && !target.socket.isClosed();
    }

    int socketSendBufferBytes() {
        Client target = playingClient();
        try { return target == null ? 0 : target.socket.getSendBufferSize(); }
        catch (SocketException ignored) { return 0; }
    }

    long slowWriteDisconnects() { return slowWriteDisconnects; }

    long sentVideoBytes() { return sentVideoBytes; }

    long sentAudioBytes() { return sentAudioBytes; }

    long pendingVideoWriteNs() {
        Client target = playingClient();
        long started = target == null ? 0 : target.videoWriteStartedNs;
        return started == 0 ? 0 : Math.max(0L, System.nanoTime() - started);
    }

    void setAudioEnabled(boolean enabled) {
        audioEnabled = enabled;
    }

    void setVideoConfig(ByteBuffer... buffers) {
        List<byte[]> values = new ArrayList<byte[]>();
        if (buffers != null) {
            for (ByteBuffer buffer : buffers) appendNals(values, copyRemaining(buffer));
        }
        for (byte[] value : values) {
            int type = videoNalType(value);
            if (isHevc() && type == 32) {
                vps = value;
            } else if ((isHevc() && type == 33) || (!isHevc() && type == 7)) {
                sps = value;
            } else if ((isHevc() && type == 34) || (!isHevc() && type == 8)) {
                pps = value;
            }
        }
    }

    void setAudioConfig(ByteBuffer config) {
        byte[] value = copyRemaining(config);
        if (value.length > 0) {
            audioConfig = value;
        }
    }

    void sendVideo(ByteBuffer buffer, MediaCodec.BufferInfo info) {
        if (info == null || info.size <= 0
                || (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return;
        sendVideo(copy(buffer, info.offset, info.size), info.presentationTimeUs, info.flags);
    }

    void sendVideo(byte[] data, long ptsUs, int flags) {
        synchronized (videoSendLock) {
            sendVideoLocked(data, ptsUs, flags);
        }
    }

    private void sendVideoLocked(byte[] data, long ptsUs, int flags) {
        if (!running || data == null || data.length == 0
                || (flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            return;
        }
        long timestamp = (ptsUs * 90L / 1000L) & 0xffffffffL;
        boolean keyFrame = (flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        Client snapshot = playingClient();
        if (snapshot == null) return;
        if (snapshot.awaitingKeyFrame && !keyFrame) return;
        if (keyFrame) { snapshot.awaitingKeyFrame = false; syncFrameRequested = false; }
        if (keyFrame) {
            if (vps != null) {
                sendVideoNal(snapshot, vps, 0, vps.length, timestamp, false);
            }
            if (sps != null) {
                sendVideoNal(snapshot, sps, 0, sps.length, timestamp, false);
            }
            if (pps != null) {
                sendVideoNal(snapshot, pps, 0, pps.length, timestamp, false);
            }
        }
        sendAccessUnit(snapshot, data, timestamp);
        flush(snapshot, true);
    }

    void sendAudio(ByteBuffer buffer, MediaCodec.BufferInfo info) {
        if (!audioEnabled || info == null || info.size <= 0
                || (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            return;
        }
        sendAudio(copy(buffer, info.offset, info.size), info.presentationTimeUs);
    }

    void sendAudio(byte[] accessUnit, long ptsUs) {
        if (!audioEnabled || accessUnit == null || accessUnit.length == 0
                || accessUnit.length > 8191) return;
        synchronized (audioSendLock) {
            Client snapshot = playingClient();
            if (snapshot == null || !snapshot.audioSetup) {
                return;
            }
            int size = accessUnit.length;
            int sequence = nextAudioSequence();
            long timestamp = (ptsUs * 48000L / 1000000L) & 0xffffffffL;
            writeRtpHeader(audioPacket, AUDIO_PAYLOAD_TYPE, true, sequence, timestamp, audioSsrc);
            audioPacket[12] = 0;
            audioPacket[13] = 16;
            audioPacket[14] = (byte) ((size >> 5) & 0xff);
            audioPacket[15] = (byte) ((size & 0x1f) << 3);
            System.arraycopy(accessUnit, 0, audioPacket, 16, size);
            send(snapshot, false, audioPacket, 0, 16 + size, true);
        }
    }

    private void sendVideoNal(Client target, byte[] nal, int start, int length,
            long timestamp, boolean marker) {
        if (length == 0 || !target.videoSetup || target.socket.isClosed()) {
            return;
        }
        final int payload = target.tcp ? TCP_RTP_PAYLOAD_BYTES : RTP_PAYLOAD_BYTES;
        final byte[] packet = videoPacket; // protected by videoSendLock
        if (length <= payload) {
            writeRtpHeader(packet, 4, VIDEO_PAYLOAD_TYPE, marker,
                    nextVideoSequence(), timestamp, videoSsrc);
            System.arraycopy(nal, start, packet, 16, length);
            send(target, true, packet, 4, 12 + length, false);
            return;
        }
        int nalHeader = nal[start] & 0xff;
        int offset = isHevc() ? 2 : 1;
        if (length <= offset) return;
        while (offset < length) {
            if (target.socket.isClosed()) return;
            int fragmentHeaderBytes = isHevc() ? 3 : 2;
            int count = Math.min(payload - fragmentHeaderBytes, length - offset);
            boolean first = offset == (isHevc() ? 2 : 1);
            boolean last = offset + count >= length;
            writeRtpHeader(packet, 4, VIDEO_PAYLOAD_TYPE, marker && last,
                    nextVideoSequence(), timestamp, videoSsrc);
            if (isHevc()) {
                packet[16] = (byte) ((nalHeader & 0x81) | (49 << 1));
                packet[17] = nal[start + 1];
                packet[18] = (byte) ((first ? 0x80 : 0) | (last ? 0x40 : 0)
                        | ((nalHeader >> 1) & 0x3f));
                System.arraycopy(nal, start + offset, packet, 19, count);
                send(target, true, packet, 4, 15 + count, false);
            } else {
                packet[16] = (byte) ((nalHeader & 0xe0) | 28);
                packet[17] = (byte) ((first ? 0x80 : 0) | (last ? 0x40 : 0)
                        | (nalHeader & 0x1f));
                System.arraycopy(nal, start + offset, packet, 18, count);
                send(target, true, packet, 4, 14 + count, false);
            }
            offset += count;
        }
    }

    /** Walk offsets, not copies: the encoder output has already been copied once. */
    private void sendAccessUnit(Client target, byte[] data, long timestamp) {
        // Prefer a fully valid AVCC unit: lengths such as 0x00000101 otherwise
        // look like an Annex-B start code and corrupt the first NAL byte.
        int end = 0;
        while (end + 4 <= data.length) {
            int length = nalLength(data, end);
            if (length <= 0 || length > data.length - end - 4) break;
            end += 4 + length;
        }
        if (end == data.length) {
            for (int offset = 0; offset < end;) {
                int length = nalLength(data, offset);
                int next = offset + 4 + length;
                sendVideoNal(target, data, offset + 4, length, timestamp, next == end);
                offset = next;
            }
            return;
        }
        int prefix = startCode(data, 0);
        if (prefix >= 0) {
            while (prefix >= 0) {
                int start = prefix + (data[prefix + 2] == 1 ? 3 : 4);
                int next = startCode(data, start);
                int nalEnd = next < 0 ? data.length : next;
                sendVideoNal(target, data, start, nalEnd - start, timestamp, next < 0);
                prefix = next;
            }
            return;
        }
        sendVideoNal(target, data, 0, data.length, timestamp, true);
    }

    private static int startCode(byte[] data, int start) {
        for (int i = start; i + 2 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0
                    && (data[i + 2] == 1 || (i + 3 < data.length
                            && data[i + 2] == 0 && data[i + 3] == 1))) return i;
        }
        return -1;
    }

    private static int nalLength(byte[] data, int offset) {
        return ((data[offset] & 255) << 24) | ((data[offset + 1] & 255) << 16)
                | ((data[offset + 2] & 255) << 8) | (data[offset + 3] & 255);
    }

    private Client playingClient() {
        synchronized (clientLock) {
            return client != null && client.playing ? client : null;
        }
    }

    private int nextVideoSequence() {
        synchronized (clientLock) {
            return videoSequence++ & 0xffff;
        }
    }

    private int nextAudioSequence() {
        synchronized (clientLock) {
            return audioSequence++ & 0xffff;
        }
    }

    private void send(Client target, boolean video, byte[] packet, boolean flush) {
        send(target, video, packet, 0, packet.length, flush);
    }

    private void send(Client target, boolean video, byte[] packet, int offset,
            int length, boolean flush) {
        try {
            if (target.tcp) {
                int channel = video ? target.videoChannel : target.audioChannel;
                synchronized (target.output) {
                    setWriteStarted(target, video, System.nanoTime());
                    try {
                        if (offset >= 4) {
                            packet[offset - 4] = '$';
                            packet[offset - 3] = (byte) channel;
                            packet[offset - 2] = (byte) (length >> 8);
                            packet[offset - 1] = (byte) length;
                            target.output.write(packet, offset - 4, length + 4);
                        } else {
                            target.output.write('$');
                            target.output.write(channel);
                            target.output.write((length >> 8) & 0xff);
                            target.output.write(length & 0xff);
                            target.output.write(packet, offset, length);
                        }
                        if (flush) {
                            target.output.flush();
                        }
                        if (video) sentVideoBytes += length + 4L;
                        else sentAudioBytes += length + 4L;
                    } finally {
                        setWriteStarted(target, video, 0L);
                    }
                }
            } else {
                int port = video ? target.videoPort : target.audioPort;
                if (port <= 0) {
                    return;
                }
                DatagramSocket socket = video ? videoSocket : audioSocket;
                socket.send(new DatagramPacket(packet, offset, length, target.address, port));
                if (video) sentVideoBytes += length;
                else sentAudioBytes += length;
            }
        } catch (IOException error) {
            disconnectFailedClient(target, error);
        }
    }

    private void flush(Client target, boolean video) {
        if (target == null || !target.tcp) {
            return;
        }
        try {
            synchronized (target.output) {
                setWriteStarted(target, video, System.nanoTime());
                try {
                    target.output.flush();
                } finally {
                    setWriteStarted(target, video, 0L);
                }
            }
        } catch (IOException error) {
            disconnectFailedClient(target, error);
        }
    }

    private static void setWriteStarted(Client target, boolean video, long startedNs) {
        if (video) target.videoWriteStartedNs = startedNs;
        else target.audioWriteStartedNs = startedNs;
    }

    static boolean isWriteStalled(long nowNs, long videoStartedNs, long audioStartedNs) {
        return videoStartedNs != 0L && nowNs - videoStartedNs > WRITE_STALL_NS
                || audioStartedNs != 0L && nowNs - audioStartedNs > WRITE_STALL_NS;
    }

    private void disconnectFailedClient(Client target, IOException error) {
        synchronized (clientLock) {
            if (client != target) return;
            if (running) Log.i(TAG, "RTSP send disconnected: " + error.getMessage());
            closeClientLocked();
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket socket = serverSocket.accept();
                final Client next;
                try {
                    socket.setSoTimeout(30000);
                    socket.setKeepAlive(true);
                    next = new Client(socket, sendBufferBytes);
                } catch (IOException error) {
                    try { socket.close(); } catch (IOException ignored) { }
                    throw error;
                }
                synchronized (clientLock) {
                    if (!running) { next.close(); return; }
                    closeClientLocked();
                    client = next;
                }
                // A silent/stale control socket must not prevent a receiver from
                // reconnecting. There is still only one active viewer/socket.
                new Thread(new Runnable() {
                    @Override public void run() { handle(next); }
                }, "webview-cast-rtsp-client").start();
            } catch (SocketTimeoutException idle) {
                // Socket SO_TIMEOUT covers reads, not writes. A blocked writer must
                // not hold an obsolete access unit indefinitely after Wi-Fi loss.
                synchronized (clientLock) {
                    long now = System.nanoTime();
                    if (client != null && isWriteStalled(now,
                            client.videoWriteStartedNs, client.audioWriteStartedNs)) {
                        Log.i(TAG, "RTSP slow writer: reconnect at a fresh key frame");
                        slowWriteDisconnects++;
                        closeClientLocked();
                    }
                }
            } catch (IOException error) {
                if (running) {
                    Log.w(TAG, "Unable to accept RTSP client", error);
                }
            }
        }
    }

    private void handle(Client next) {
        Socket socket = next.socket;
        try {
            while (running && !socket.isClosed()) {
                String requestLine = readRequestLine(next.input);
                if (requestLine == null) {
                    break;
                }
                if (requestLine.length() == 0 || requestLine.charAt(0) == '$') {
                    continue;
                }
                String[] request = requestLine.split(" ");
                if (request.length < 2) {
                    break;
                }
                String method = request[0].toUpperCase(Locale.US);
                String uri = request[1];
                String cseq = "1";
                String transport = "";
                String line;
                while ((line = readLine(next.input)) != null && line.length() > 0) {
                    int colon = line.indexOf(':');
                    if (colon <= 0) {
                        continue;
                    }
                    String name = line.substring(0, colon).trim();
                    String value = line.substring(colon + 1).trim();
                    if ("CSeq".equalsIgnoreCase(name)) {
                        cseq = value;
                    } else if ("Transport".equalsIgnoreCase(name)) {
                        transport = value;
                    }
                }
                if ("OPTIONS".equals(method)) {
                    respond(next, cseq, "Public: OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER\r\n", "");
                } else if ("DESCRIBE".equals(method)) {
                    String sdp = sdp(socket.getLocalAddress());
                    respond(next, cseq, "Content-Type: application/sdp\r\nContent-Base: "
                            + baseUri(uri) + "/\r\n", sdp);
                } else if ("SETUP".equals(method)) {
                    boolean audio = uri.toLowerCase(Locale.US).contains("trackid=1");
                    setupTransport(next, transport, audio);
                    String responseTransport = next.tcp
                            ? "RTP/AVP/TCP;unicast;interleaved="
                                    + (audio ? next.audioChannel : next.videoChannel) + "-"
                                    + ((audio ? next.audioChannel : next.videoChannel) + 1)
                            : "RTP/AVP/UDP;unicast;client_port="
                                    + (audio ? next.audioPort : next.videoPort) + "-"
                                    + ((audio ? next.audioPort : next.videoPort) + 1)
                                    + ";server_port="
                                    + (audio ? audioSocket.getLocalPort() : videoSocket.getLocalPort())
                                    + "-" + ((audio ? audioSocket.getLocalPort()
                                            : videoSocket.getLocalPort()) + 1);
                    respond(next, cseq, "Transport: " + responseTransport
                            + "\r\nSession: " + next.session + "\r\n", "");
                } else if ("PLAY".equals(method)) {
                    synchronized (videoSendLock) {
                        // IJK can receive continuously without sending another RTSP
                        // request. Silence on the control reader is not a dead stream.
                        socket.setSoTimeout(0);
                        respond(next, cseq, "Session: " + next.session + "\r\nRange: npt=0.000-\r\n", "");
                        // An old IDR plus current P frames is not a valid GOP.
                        next.awaitingKeyFrame = true;
                        next.playing = true;
                        syncFrameRequested = true;
                    }
                } else if ("PAUSE".equals(method)) {
                    next.playing = false;
                    socket.setSoTimeout(30000);
                    respond(next, cseq, "Session: " + next.session + "\r\n", "");
                } else if ("TEARDOWN".equals(method)) {
                    respond(next, cseq, "Session: " + next.session + "\r\n", "");
                    break;
                } else if ("GET_PARAMETER".equals(method)) {
                    respond(next, cseq, "Session: " + next.session + "\r\n", "");
                } else {
                    respond(next, cseq, "", "");
                }
            }
        } catch (IOException error) {
            if (running) {
                Log.i(TAG, "RTSP client disconnected: " + error.getMessage());
            }
        } finally {
            synchronized (clientLock) {
                if (client == next) {
                    closeClientLocked();
                } else if (next != null) {
                    next.close();
                }
            }
        }
    }

    private void setupTransport(Client target, String transport, boolean audio) {
        String lower = transport == null ? "" : transport.toLowerCase(Locale.US);
        target.tcp = lower.contains("rtp/avp/tcp") || lower.contains("interleaved=");
        if (target.tcp) {
            int[] channels = parsePair(lower, "interleaved=", audio ? 2 : 0);
            if (audio) {
                target.audioChannel = channels[0];
                target.audioSetup = true;
            } else {
                target.videoChannel = channels[0];
                target.videoSetup = true;
            }
        } else {
            int[] ports = parsePair(lower, "client_port=", 0);
            if (audio) {
                target.audioPort = ports[0];
                target.audioSetup = true;
            } else {
                target.videoPort = ports[0];
                target.videoSetup = true;
            }
        }
    }

    private String sdp(InetAddress localAddress) {
        String host = localAddress == null || localAddress.isAnyLocalAddress()
                ? "0.0.0.0" : localAddress.getHostAddress();
        StringBuilder result = new StringBuilder();
        result.append("v=0\r\n")
                .append("o=- 0 0 IN IP4 ").append(host).append("\r\n")
                .append("s=nTv WebView Cast\r\n")
                .append("c=IN IP4 ").append(host).append("\r\n")
                .append("t=0 0\r\n")
                .append("a=control:*\r\n")
                .append("m=video 0 RTP/AVP ").append(VIDEO_PAYLOAD_TYPE).append("\r\n")
                .append("a=rtpmap:").append(VIDEO_PAYLOAD_TYPE).append(isHevc()
                        ? " H265/90000\r\n" : " H264/90000\r\n")
                .append("a=framerate:").append(videoFps).append("\r\n")
                .append("a=fmtp:").append(VIDEO_PAYLOAD_TYPE).append(' ');
        if (isHevc()) {
            boolean separator = false;
            if (vps != null) {
                result.append("sprop-vps=").append(Base64.encodeToString(vps, Base64.NO_WRAP));
                separator = true;
            }
            if (sps != null) {
                if (separator) result.append(';');
                result.append("sprop-sps=").append(Base64.encodeToString(sps, Base64.NO_WRAP));
                separator = true;
            }
            if (pps != null) {
                if (separator) result.append(';');
                result.append("sprop-pps=").append(Base64.encodeToString(pps, Base64.NO_WRAP));
            }
        } else {
            result.append("packetization-mode=1;profile-level-id=").append(profileLevelId());
            if (sps != null && pps != null) {
                result.append(";sprop-parameter-sets=")
                        .append(Base64.encodeToString(sps, Base64.NO_WRAP)).append(',')
                        .append(Base64.encodeToString(pps, Base64.NO_WRAP));
            }
        }
        result.append("\r\na=control:trackID=0\r\n");
        if (audioEnabled) {
            result.append("m=audio 0 RTP/AVP ").append(AUDIO_PAYLOAD_TYPE).append("\r\n")
                    .append("a=rtpmap:").append(AUDIO_PAYLOAD_TYPE)
                    .append(" MPEG4-GENERIC/48000/2\r\n")
                    .append("a=fmtp:").append(AUDIO_PAYLOAD_TYPE)
                    .append(" streamtype=5;profile-level-id=1;mode=AAC-hbr;config=")
                    .append(toHex(audioConfig))
                    .append(";SizeLength=13;IndexLength=3;IndexDeltaLength=3\r\n")
                    .append("a=control:trackID=1\r\n");
        }
        return result.toString();
    }

    private String profileLevelId() {
        if (sps != null && sps.length >= 4) {
            return String.format(Locale.US, "%02x%02x%02x", sps[1] & 0xff,
                    sps[2] & 0xff, sps[3] & 0xff);
        }
        return "42e01f";
    }

    private boolean isHevc() {
        return "h265".equals(videoCodec);
    }

    private int videoNalType(byte[] value) {
        if (value == null || value.length == 0) return -1;
        return isHevc() ? (value[0] >> 1) & 0x3f : value[0] & 0x1f;
    }

    private static String toHex(byte[] value) {
        StringBuilder result = new StringBuilder();
        for (byte item : value) {
            result.append(String.format(Locale.US, "%02x", item & 0xff));
        }
        return result.toString();
    }

    private static void respond(Client target, String cseq, String headers, String body)
            throws IOException {
        byte[] content = body == null ? new byte[0] : body.getBytes("UTF-8");
        String head = "RTSP/1.0 200 OK\r\nCSeq: " + cseq
                + "\r\nServer: nTv-WebView-Cast\r\n" + headers
                + "Content-Length: " + content.length + "\r\n\r\n";
        synchronized (target.output) {
            target.output.write(head.getBytes("UTF-8"));
            target.output.write(content);
            target.output.flush();
        }
    }

    private static String baseUri(String uri) {
        int track = uri == null ? -1 : uri.toLowerCase(Locale.US).indexOf("/trackid=");
        return track > 0 ? uri.substring(0, track) : uri;
    }

    private static int[] parsePair(String value, String marker, int fallback) {
        int start = value.indexOf(marker);
        if (start < 0) {
            return new int[] { fallback, fallback + 1 };
        }
        start += marker.length();
        int end = value.indexOf(';', start);
        String[] values = value.substring(start, end < 0 ? value.length() : end).split("-");
        try {
            int first = Integer.parseInt(values[0].trim());
            int second = values.length > 1 ? Integer.parseInt(values[1].trim()) : first + 1;
            return new int[] { first, second };
        } catch (NumberFormatException error) {
            return new int[] { fallback, fallback + 1 };
        }
    }

    private static String readLine(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int previous = -1;
        int value;
        while ((value = input.read()) != -1) {
            if (previous == '\r' && value == '\n') {
                byte[] bytes = output.toByteArray();
                int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r'
                        ? bytes.length - 1 : bytes.length;
                return new String(bytes, 0, length, "UTF-8");
            }
            output.write(value);
            previous = value;
            if (output.size() > 8192) {
                throw new IOException("RTSP 请求头过长");
            }
        }
        return output.size() == 0 ? null : output.toString("UTF-8");
    }

    /** Skips interleaved RTCP packets sent by TCP clients between RTSP requests. */
    private static String readRequestLine(BufferedInputStream input) throws IOException {
        input.mark(4);
        int first = input.read();
        if (first < 0) return null;
        if (first != '$') {
            input.reset();
            return readLine(input);
        }
        int channel = input.read();
        int high = input.read();
        int low = input.read();
        if (channel < 0 || high < 0 || low < 0) return null;
        int remaining = (high << 8) | low;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= (int) skipped;
            } else if (input.read() < 0) {
                return null;
            } else {
                remaining--;
            }
        }
        return "";
    }

    private static void writeRtpHeader(byte[] packet, int payloadType, boolean marker,
            int sequence, long timestamp, int ssrc) {
        writeRtpHeader(packet, 0, payloadType, marker, sequence, timestamp, ssrc);
    }

    private static void writeRtpHeader(byte[] packet, int offset, int payloadType,
            boolean marker, int sequence, long timestamp, int ssrc) {
        packet[offset] = (byte) 0x80;
        packet[offset + 1] = (byte) ((marker ? 0x80 : 0) | payloadType);
        packet[offset + 2] = (byte) (sequence >> 8);
        packet[offset + 3] = (byte) sequence;
        packet[offset + 4] = (byte) (timestamp >> 24);
        packet[offset + 5] = (byte) (timestamp >> 16);
        packet[offset + 6] = (byte) (timestamp >> 8);
        packet[offset + 7] = (byte) timestamp;
        packet[offset + 8] = (byte) (ssrc >> 24);
        packet[offset + 9] = (byte) (ssrc >> 16);
        packet[offset + 10] = (byte) (ssrc >> 8);
        packet[offset + 11] = (byte) ssrc;
    }

    private static byte[] copy(ByteBuffer buffer, int offset, int size) {
        ByteBuffer source = buffer.duplicate();
        source.position(offset);
        source.limit(offset + size);
        byte[] result = new byte[size];
        source.get(result);
        return result;
    }

    private static byte[] copyRemaining(ByteBuffer value) {
        if (value == null) {
            return new byte[0];
        }
        ByteBuffer source = value.duplicate();
        byte[] result = new byte[source.remaining()];
        source.get(result);
        return result;
    }

    private static void appendNals(List<byte[]> result, byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        List<Integer> starts = new ArrayList<Integer>();
        List<Integer> prefixes = new ArrayList<Integer>();
        for (int index = 0; index + 3 < data.length; index++) {
            int prefix = data[index] == 0 && data[index + 1] == 0
                    && data[index + 2] == 1 ? 3
                    : data[index] == 0 && data[index + 1] == 0
                            && data[index + 2] == 0 && data[index + 3] == 1 ? 4 : 0;
            if (prefix > 0) {
                starts.add(index + prefix);
                prefixes.add(index);
                index += prefix - 1;
            }
        }
        if (!starts.isEmpty()) {
            for (int index = 0; index < starts.size(); index++) {
                int start = starts.get(index);
                int end = index + 1 < prefixes.size() ? prefixes.get(index + 1) : data.length;
                if (end > start) {
                    byte[] nal = new byte[end - start];
                    System.arraycopy(data, start, nal, 0, nal.length);
                    result.add(nal);
                }
            }
            return;
        }
        int offset = 0;
        while (offset + 4 <= data.length) {
            int length = ((data[offset] & 0xff) << 24) | ((data[offset + 1] & 0xff) << 16)
                    | ((data[offset + 2] & 0xff) << 8) | (data[offset + 3] & 0xff);
            if (length <= 0 || offset + 4 + length > data.length) {
                break;
            }
            byte[] nal = new byte[length];
            System.arraycopy(data, offset + 4, nal, 0, length);
            result.add(nal);
            offset += 4 + length;
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        videoSocket.close();
        audioSocket.close();
        synchronized (clientLock) {
            closeClientLocked();
        }
    }

    private void closeClientLocked() {
        if (client != null) {
            client.close();
            client = null;
        }
    }

    private static final class Client implements Closeable {
        final Socket socket;
        final InetAddress address;
        final BufferedInputStream input;
        final BufferedOutputStream output;
        final String session = Integer.toHexString(new Random().nextInt());
        volatile boolean playing;
        volatile boolean awaitingKeyFrame = true;
        volatile long videoWriteStartedNs;
        volatile long audioWriteStartedNs;
        volatile boolean tcp;
        volatile boolean videoSetup;
        volatile boolean audioSetup;
        int videoPort;
        int audioPort;
        int videoChannel;
        int audioChannel = 2;

        Client(Socket socket, int sendBufferBytes) throws IOException {
            this.socket = socket;
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setSendBufferSize(sendBufferBytes);
            address = socket.getInetAddress();
            input = new BufferedInputStream(socket.getInputStream());
            output = new BufferedOutputStream(socket.getOutputStream(), 32 * 1024);
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
