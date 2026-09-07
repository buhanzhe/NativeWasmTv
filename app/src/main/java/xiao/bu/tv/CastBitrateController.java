package xiao.bu.tv;

/** Runtime-only congestion control. The user's saved bitrate remains the ceiling. */
final class CastBitrateController {
    private static final long WINDOW_NS = 500_000_000L;
    private final int ceiling, floor;
    private final long frameNs;
    private int bitrate, badWindows;
    private long windowAt, changedAt, healthyAt, drops, disconnects, sendNs, sends;
    private boolean initialized, disabled;

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
        if (disabled) return 0;
        if (!initialized) {
            initialized = true; windowAt = healthyAt = now;
            changedAt = now - 2_000_000_000L;
            drops = dropped; disconnects = slowDisconnects;
            sendNs = sends = 0;
            return 0;
        }
        if (now - windowAt < WINDOW_NS) return 0;
        boolean disconnectedSlowly = slowDisconnects > disconnects;
        boolean pressure = dropped > drops || pendingWriteNs > 80_000_000L
                || (sends >= 4 && sendNs / sends > frameNs * 4 / 5);
        long completedSends = sends;
        windowAt = now; drops = dropped; disconnects = slowDisconnects;
        sendNs = sends = 0;
        if (!connected && !disconnectedSlowly) {
            badWindows = 0; healthyAt = now;
            return 0; // no viewer is not evidence of congestion or recovery
        }
        if (pressure || disconnectedSlowly) {
            healthyAt = now;
            badWindows++;
            if ((badWindows >= 2 || disconnectedSlowly || pendingWriteNs > 120_000_000L)
                    && now - changedAt >= 1_500_000_000L && bitrate > floor) {
                // Converge quickly enough that an overloaded link cannot keep
                // several seconds of key-frame recovery pressure alive.
                bitrate = Math.max(floor, bitrate * 2 / 3);
                changedAt = now; badWindows = 0;
                return bitrate;
            }
        } else {
            badWindows = 0;
            if (completedSends == 0) healthyAt = now;
            if (now - healthyAt >= 10_000_000_000L && bitrate < ceiling) {
                bitrate = Math.min(ceiling, bitrate + Math.max(100_000, bitrate / 10));
                changedAt = healthyAt = now;
                return bitrate;
            }
        }
        return 0;
    }

    synchronized int bitrate() { return bitrate; }

    synchronized void reject(int previous) {
        bitrate = previous;
        disabled = true; // old/vendor codecs may reject runtime bitrate changes
    }
}
