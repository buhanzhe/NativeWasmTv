package xiao.bu.tv;

/** Estimates encoded or transport bitrate from byte counts over a bounded time window. */
final class MediaBitrateEstimator {
    private static final int SAMPLE_COUNT = 8;
    private static final long MIN_WINDOW_MS = 250L;
    private static final long TARGET_WINDOW_MS = 4000L;
    private final long[] bytes = new long[SAMPLE_COUNT];
    private final long[] times = new long[SAMPLE_COUNT];
    private int next;
    private int count;
    private long lastBitrate = -1L;

    synchronized long sampleCumulativeBytes(long totalBytes, long nowMs) {
        if (totalBytes < 0L || nowMs <= 0L) return -1L;
        if (count > 0) {
            int newest = (next - 1 + SAMPLE_COUNT) % SAMPLE_COUNT;
            if (totalBytes < bytes[newest] || nowMs < times[newest]
                    || nowMs - times[newest] > 15000L) reset();
            else if (nowMs - times[newest] < MIN_WINDOW_MS) return lastBitrate;
        }
        bytes[next] = totalBytes;
        times[next] = nowMs;
        next = (next + 1) % SAMPLE_COUNT;
        if (count < SAMPLE_COUNT) count++;
        if (count < 2) return -1L;

        int newest = (next - 1 + SAMPLE_COUNT) % SAMPLE_COUNT;
        int selected = -1;
        for (int offset = 1; offset < count; offset++) {
            int index = (newest - offset + SAMPLE_COUNT) % SAMPLE_COUNT;
            long elapsed = nowMs - times[index];
            if (elapsed < MIN_WINDOW_MS) continue;
            selected = index;
            if (elapsed >= TARGET_WINDOW_MS) break;
        }
        if (selected < 0) return -1L;
        long elapsedMs = nowMs - times[selected];
        long deltaBytes = totalBytes - bytes[selected];
        if (deltaBytes < 0L || deltaBytes > Long.MAX_VALUE / 8000L) return -1L;
        lastBitrate = deltaBytes * 8000L / elapsedMs;
        return lastBitrate;
    }

    synchronized void reset() {
        next = 0;
        count = 0;
        lastBitrate = -1L;
    }

    static long fromPayload(long payloadBytes, long durationMs, long minimumDurationMs,
            long minimumBitrate, long maximumBitrate) {
        if (payloadBytes <= 0L || durationMs < minimumDurationMs
                || payloadBytes > Long.MAX_VALUE / 8000L) return -1L;
        long bitrate = payloadBytes * 8000L / durationMs;
        return bitrate >= minimumBitrate && bitrate <= maximumBitrate ? bitrate : -1L;
    }
}
