package xiao.bu.tv;

import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

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
            Log.w(TAG, "Unable to start LAN discovery responder", error);
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
                if (running) Log.w(TAG, "LAN discovery socket stopped", closed);
                break;
            } catch (IOException error) {
                if (running) Log.w(TAG, "LAN discovery response failed", error);
            }
        }
    }

    static String discoverTelevision(int timeoutMs) {
        DatagramSocket client = null;
        try {
            client = new DatagramSocket();
            client.setBroadcast(true);
            String nonce = Long.toHexString(System.nanoTime())
                    + Integer.toHexString((int) (Math.random() * Integer.MAX_VALUE));
            byte[] query = (QUERY_PREFIX + nonce).getBytes("UTF-8");
            Set<InetAddress> broadcasts = broadcastAddresses();
            for (InetAddress address : broadcasts) {
                try {
                    client.send(new DatagramPacket(query, query.length,
                            address, DISCOVERY_PORT));
                } catch (IOException ignored) {
                    // Another active interface may still reach the receiver.
                }
            }
            long deadline = SystemClock.elapsedRealtime() + Math.max(100, timeoutMs);
            byte[] responseBytes = new byte[192];
            while (SystemClock.elapsedRealtime() < deadline) {
                int remaining = (int) Math.max(1L,
                        deadline - SystemClock.elapsedRealtime());
                client.setSoTimeout(Math.min(remaining, 180));
                DatagramPacket response = new DatagramPacket(
                        responseBytes, responseBytes.length);
                try {
                    client.receive(response);
                } catch (SocketTimeoutException timeout) {
                    continue;
                }
                String message = new String(response.getData(), response.getOffset(),
                        response.getLength(), "UTF-8");
                String expected = RESPONSE_PREFIX + nonce + " ";
                if (!message.startsWith(expected)) continue;
                int port;
                try {
                    port = Integer.parseInt(message.substring(expected.length()).trim());
                } catch (NumberFormatException invalid) {
                    continue;
                }
                if (port < 1 || port > 65535) continue;
                return String.format(Locale.US, "http://%s:%d",
                        response.getAddress().getHostAddress(), port);
            }
        } catch (IOException error) {
            Log.d(TAG, "LAN discovery unavailable", error);
        } finally {
            if (client != null) client.close();
        }
        return "";
    }

    private static Set<InetAddress> broadcastAddresses() throws IOException {
        LinkedHashSet<InetAddress> result = new LinkedHashSet<InetAddress>();
        result.add(InetAddress.getByName("255.255.255.255"));
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces == null) return result;
        for (NetworkInterface network : Collections.list(interfaces)) {
            try {
                if (!network.isUp() || network.isLoopback()) continue;
            } catch (SocketException ignored) {
                continue;
            }
            for (InterfaceAddress address : network.getInterfaceAddresses()) {
                if (address.getBroadcast() != null) result.add(address.getBroadcast());
            }
        }
        return result;
    }

    @Override public void close() {
        running = false;
        if (socket != null) socket.close();
        socket = null;
        if (responderThread != null) responderThread.interrupt();
        responderThread = null;
    }
}
