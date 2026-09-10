package xiao.bu.tv;

/** Runtime-only congestion control. The user's saved bitrate remains the ceiling. */
final class CastBitrateController {
    private static final long WINDOW_NS = 500_000_000L;
    private final int ceiling, floor;
    private final long frameNs;
    private int bitrate, badWindows;
    private long windowAt, changedAt, healthyAt, drops, disconnects, sendNs, sends;
    private boolean initialized, disabled;
    private int encodingWindows;
    private boolean resolutionRequested;
    private String reason = "";

    CastBitrateController(int bitrate, int fps) {
        this.ceiling = this.bitrate = bitrate;
        floor = Math.min(bitrate, 2_000_000);
        frameNs = 1_000_000_000L / Math.max(1, fps);
    }

    synchronized void recordSend(long durationNs) {
        sendNs += Math.max(0L, durationNs);
        sends++;
    }

    /** Returns a new codec target, or zero. Times are monotonic, not wall clock. */
    synchronized int update(long now, long dropped, long slowDisconnects,
            boolean connected, long pendingWriteNs) {
        return update(now, dropped, slowDisconnects, connected, pendingWriteNs, false);
    }

    synchronized int update(long now, long dropped, long slowDisconnects,
            boolean connected, long pendingWriteNs, boolean encoderPressure) {
        if (!initialized) {
            initialized = true; windowAt = healthyAt = now;
            changedAt = now - 2_000_000_000L;
            drops = dropped; disconnects = slowDisconnects;
            sendNs = sends = 0;
            return 0;
        }
        if (now - windowAt < WINDOW_NS) return 0;
        boolean disconnectedSlowly = slowDisconnects > disconnects;
        boolean pressure = encoderPressure || dropped > drops || pendingWriteNs > 80_000_000L
                || (sends >= 4 && sendNs / sends > frameNs * 4 / 5);
        long completedSends = sends;
        windowAt = now; drops = dropped; disconnects = slowDisconnects;
        sendNs = sends = 0;
        if (!connected && !disconnectedSlowly) {
            encodingWindows = 0; resolutionRequested = false;
            badWindows = 0; healthyAt = now;
            return 0; // no viewer is not evidence of congestion or recovery
        }
        encodingWindows = encoderPressure ? encodingWindows + 1 : 0;
        if (!encoderPressure) resolutionRequested = false;
        // First try cheaper rate control. Resolution only falls for sustained
        // encoder pressure, never just a large IDR or a blocked network writer.
        if (encodingWindows >= 12 && now - changedAt >= 2_000_000_000L
                && (disabled || bitrate <= Math.max(floor, ceiling * 3 / 5))) {
            resolutionRequested = true;
        }
        if (pressure || disconnectedSlowly) {
            reason = encoderPressure ? "编码积压" : "发送拥塞";
            healthyAt = now;
            badWindows++;
            if ((badWindows >= 2 || disconnectedSlowly || pendingWriteNs > 120_000_000L)
                    && now - changedAt >= 1_500_000_000L && bitrate > floor && !disabled) {
                bitrate = Math.max(floor, bitrate * 3 / 4);
                changedAt = now; badWindows = 0;
                return bitrate;
            }
        } else {
            badWindows = 0;
            if (completedSends == 0) healthyAt = now;
            if (now - healthyAt >= 20_000_000_000L && bitrate < ceiling && !disabled) {
                reason = "负载恢复";
                bitrate = Math.min(ceiling, bitrate + Math.max(100_000, bitrate / 10));
                changedAt = healthyAt = now;
                return bitrate;
            }
        }
        return 0;
    }

    synchronized int bitrate() { return bitrate; }
    synchronized boolean needsLowerResolution() { return resolutionRequested; }
    synchronized String reason() { return reason; }
    synchronized void resolutionHandled() { resolutionRequested = false; encodingWindows = 0; }

    synchronized void reject(int previous) {
        bitrate = previous;
        disabled = true; // old/vendor codecs may reject runtime bitrate changes
    }
}
