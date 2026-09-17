package xiao.bu.tv;

import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.Timer;
import java.util.TimerTask;

/** Total request budget, including redirects/retries, not just per-socket reads. */
final class VodDeadline {
    private static final Timer TIMER = new Timer("vod-request-deadline", true);
    private volatile boolean expired;
    private final TimerTask task;
    VodDeadline(final HttpURLConnection connection, long milliseconds) {
        task = new TimerTask() { public void run() { expired = true; connection.disconnect(); } };
        TIMER.schedule(task, milliseconds);
    }
    void check() throws SocketTimeoutException { if (expired) throw new SocketTimeoutException("VOD request deadline"); }
    void close() { task.cancel(); TIMER.purge(); }
}
