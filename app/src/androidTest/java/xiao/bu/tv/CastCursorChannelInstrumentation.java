package xiao.bu.tv;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Wire-level coverage on legacy Android; no Activity, catalog or settings changes. */
public final class CastCursorChannelInstrumentation extends Instrumentation {
    private void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int code = -1;
        CastCursorChannel.Receiver receiver = null;
        CastCursorChannel.Sender sender = null;
        try {
            InetAddress peer = InetAddress.getByName("127.0.0.1");
            AtomicInteger latest = new AtomicInteger(-1);
            receiver = new CastCursorChannel.Receiver(peer, "test-session", state -> latest.set(state.getInt("x")));
            // Out-of-order and wrong-session datagrams must not replace the latest position.
            try (DatagramSocket raw = new DatagramSocket()) {
                raw.setSoTimeout(300);
                for (int i = 0; i < 3; i++) {
                    byte[] data = new JSONObject().put("sessionId", i == 2 ? "old-session" : "test-session")
                            .put("sequence", i == 0 ? 10 : i == 1 ? 9 : 11).put("x", i).toString().getBytes("UTF-8");
                    raw.send(new DatagramPacket(data, data.length, peer, receiver.port()));
                    if (i == 0) raw.receive(new DatagramPacket(new byte[32], 32));
                }
                SystemClock.sleep(100);
                check(latest.get() == 0, "Stale packet or old session moved cursor");
            }
            receiver.close();
            receiver = new CastCursorChannel.Receiver(peer, "fresh-session", state -> latest.set(state.getInt("x")));
            sender = new CastCursorChannel.Sender(peer, receiver.port(), "fresh-session");
            for (int i = 0; i < 1000; i++) sender.offer(new JSONObject().put("x", i));
            long deadline = SystemClock.elapsedRealtime() + 2000;
            while ((!sender.ready() || latest.get() != 999) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10);
            check(sender.ready() && latest.get() == 999, "Latest coordinate not delivered/acknowledged");
            receiver.close();
            SystemClock.sleep(750);
            check(!sender.ready(), "Broken channel did not permit video-cursor fallback");
            result.putString("stream", "PASS old-session/out-of-order rejection, 1000 updates coalesced to latest, acknowledgement and disconnect fallback\n");
        } catch (Throwable error) {
            code = 0; result.putString("stream", android.util.Log.getStackTraceString(error));
        } finally {
            if (sender != null) sender.close();
            if (receiver != null) receiver.close();
        }
        finish(code, result);
    }
}
