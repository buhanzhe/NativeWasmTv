package xiao.bu.tv;

import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Audio-service Binder calls must not block the playback/UI ticker (Bugly ANR 5281). */
final class AudioModeMonitor implements java.io.Closeable {
    interface Reader { int mode(); }
    interface Listener { void onCallState(boolean active); }
    private final Reader reader;
    private final ExecutorService worker;
    private final Executor delivery;
    private Listener listener;
    private boolean pending, closed;

    AudioModeMonitor(AudioManager manager, Listener listener) {
        this(manager::getMode, Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "audio-mode-check");
            thread.setDaemon(true);
            return thread;
        }), task -> new Handler(Looper.getMainLooper()).post(task), listener);
    }

    AudioModeMonitor(Reader reader, ExecutorService worker, Executor delivery, Listener listener) {
        this.reader = reader;
        this.worker = worker;
        this.delivery = delivery;
        this.listener = listener;
    }

    synchronized void refresh() {
        // A broken service can hang indefinitely. Never queue one Binder call per UI tick.
        if (closed || pending) return;
        pending = true;
        worker.execute(() -> {
            boolean active;
            try {
                int mode = reader.mode();
                active = mode == AudioManager.MODE_IN_CALL
                        || mode == AudioManager.MODE_IN_COMMUNICATION;
            } catch (RuntimeException error) {
                active = true; // Unknown service state must not unmute a call.
                Log.w("AudioModeMonitor", "Unable to check audio service mode", error);
            }
            final boolean callActive = active;
            delivery.execute(() -> {
                Listener target;
                synchronized (AudioModeMonitor.this) {
                    pending = false;
                    target = closed ? null : listener;
                }
                if (target != null) target.onCallState(callActive);
            });
        });
    }

    @Override public synchronized void close() {
        closed = true;
        listener = null;
        worker.shutdownNow();
    }
}
