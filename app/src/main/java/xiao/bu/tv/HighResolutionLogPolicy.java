package xiao.bu.tv;

/** Reuses the existing one-second stats tick; no separate task or per-frame work. */
final class HighResolutionLogPolicy {
    private int request = Integer.MIN_VALUE;
    private long lastAt;
    private boolean lastBuffering;

    boolean shouldLog(int currentRequest, int width, int height, boolean buffering, long now) {
        if (width < 3000 && height < 2000) return false;
        if (currentRequest == request && now >= lastAt
                && now - lastAt < (buffering != lastBuffering ? 1000L : 15000L)) return false;
        request = currentRequest;
        lastAt = now;
        lastBuffering = buffering;
        return true;
    }
}
