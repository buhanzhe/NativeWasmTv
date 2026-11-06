package xiao.bu.tv;

import android.os.Process;
import xiao.bu.tv.AppLog;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;

/** One-shot LAN discovery. The television responder blocks without polling. */
final class CastDeviceDiscovery implements Closeable {
    private static final String TAG = "CastDiscovery";
    private static final int DISCOVERY_PORT = 9966;
    private static final String QUERY_PREFIX = "NTV_DISCOVER/1 ";
    private static final String RESPONSE_PREFIX = "NTV_TELEVISION/1 ";

    private final int controlPort;
    private volatile boolean running;
    private DatagramSocket socket;
    private Thread responderThread;

    CastDeviceDiscovery(int controlPort) {
        this.controlPort = controlPort;
    }

    void startTelevisionResponder() {
        if (running || controlPort <= 0) return;
        try {
            DatagramSocket responder = new DatagramSocket(null);
            responder.setReuseAddress(true);
            responder.bind(new InetSocketAddress(DISCOVERY_PORT));
            socket = responder;
            running = true;
            responderThread = new Thread(new Runnable() {
                @Override public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    respondLoop();
                }
            }, "cast-device-discovery");
            responderThread.start();
        } catch (IOException error) {
            AppLog.w(TAG, "Unable to start LAN discovery responder", error);
        }
    }

    private void respondLoop() {
        byte[] buffer = new byte[160];
        while (running) {
            try {
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                socket.receive(request);
                String message = new String(request.getData(), request.getOffset(),
                        request.getLength(), "UTF-8");
                if (!message.startsWith(QUERY_PREFIX)) continue;
                String nonce = message.substring(QUERY_PREFIX.length()).trim();
                if (!nonce.matches("[0-9a-f]{8,32}")) continue;
                byte[] response = (RESPONSE_PREFIX + nonce + " " + controlPort)
                        .getBytes("UTF-8");
                socket.send(new DatagramPacket(response, response.length,
                        request.getAddress(), request.getPort()));
            } catch (SocketException closed) {
                if (running) AppLog.w(TAG, "LAN discovery socket stopped", closed);
                break;
            } catch (IOException error) {
                if (running) AppLog.w(TAG, "LAN discovery response failed", error);
            }
        }
    }

    @Override public void close() {
        running = false;
        if (socket != null) socket.close();
        socket = null;
        if (responderThread != null) responderThread.interrupt();
        responderThread = null;
    }
}
