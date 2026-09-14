package xiao.bu.tv;

import android.os.SystemClock;
import org.json.JSONObject;
import java.io.Closeable;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/** Latest absolute cursor state, separate from the encoded video and its queues. */
final class CastCursorChannel {
    interface Listener { void receive(JSONObject state) throws Exception; }

    static final class Receiver implements Closeable {
        private final DatagramSocket socket;
        private volatile boolean closed;
        Receiver(final InetAddress peer, final String session, final Listener listener) throws Exception {
            socket = new DatagramSocket(0);
            Thread worker = new Thread(() -> {
                byte[] bytes = new byte[1536];
                long latest = -1;
                while (!closed) {
                    try {
                        DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
                        socket.receive(packet);
                        if (!peer.equals(packet.getAddress())) continue;
                        JSONObject state = new JSONObject(new String(bytes, 0, packet.getLength(), "UTF-8"));
                        if (!session.equals(state.optString("sessionId"))) continue;
                        long sequence = state.optLong("sequence", -1);
                        if (sequence <= latest) continue;
                        listener.receive(state);
                        latest = sequence;
                        byte[] ack = Long.toString(sequence).getBytes("UTF-8");
                        socket.send(new DatagramPacket(ack, ack.length, packet.getAddress(), packet.getPort()));
                    } catch (Exception ignored) { /* malformed/stale packets never affect the lease */ }
                }
            }, "cast-cursor-receive");
            worker.setDaemon(true);
            worker.start();
        }
        int port() { return socket.getLocalPort(); }
        public void close() { closed = true; socket.close(); }
    }

    static final class Sender implements Closeable {
        private final DatagramSocket socket;
        private JSONObject pending;
        private volatile boolean closed;
        private volatile long acknowledgedAt;
        Sender(InetAddress peer, int port, final String session) throws Exception {
            socket = new DatagramSocket();
            socket.connect(peer, port);
            socket.setSoTimeout(150);
            Thread worker = new Thread(() -> {
                long sequence = 0;
                while (!closed) {
                    try {
                        JSONObject state;
                        synchronized (this) {
                            while (pending == null && !closed) wait();
                            if (closed) return;
                            state = pending;
                            pending = null;
                        }
                        state.put("type", "cursor").put("sessionId", session).put("sequence", ++sequence);
                        byte[] bytes = state.toString().getBytes("UTF-8");
                        socket.send(new DatagramPacket(bytes, bytes.length));
                        byte[] ack = new byte[32];
                        DatagramPacket packet = new DatagramPacket(ack, ack.length);
                        socket.receive(packet);
                        if (Long.toString(sequence).equals(new String(ack, 0, packet.getLength(), "UTF-8"))) {
                            acknowledgedAt = SystemClock.elapsedRealtime();
                        }
                    } catch (Exception ignored) { /* keep only the newest position; no replay backlog */ }
                }
            }, "cast-cursor-send");
            worker.setDaemon(true);
            worker.start();
        }
        synchronized void offer(JSONObject state) { if (!closed) { pending = state; notifyAll(); } }
        boolean ready() { return !closed && acknowledgedAt > 0 && SystemClock.elapsedRealtime() - acknowledgedAt < 700; }
        public synchronized void close() { closed = true; pending = null; socket.close(); notifyAll(); }
    }
}
