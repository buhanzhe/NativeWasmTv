package xiao.bu.tv.skin;

/** Time-based title pan in theme coordinates; no frame buffers or text allocations. */
final class ChannelTitleMarquee {
    private static final long START_PAUSE = 1000, END_PAUSE = 1200;
    private float distance;
    private long started, travel;

    void configure(float width, float room, boolean selected, long now) {
        float next = selected && room > 0 ? Math.max(0, width - room) : 0;
        if (next != distance) {
            distance = next;
            started = now;
            travel = Math.max(1, (long) Math.ceil(distance * 1000 / 24));
        }
    }

    void stop() { distance = 0; }

    float offset(long now) {
        if (distance <= 0) return 0;
        long elapsed = phase(now) - START_PAUSE;
        return elapsed <= 0 ? 0 : elapsed >= travel ? distance : distance * elapsed / travel;
    }

    long delay(long now) {
        if (distance <= 0) return -1;
        long phase = phase(now);
        if (phase < START_PAUSE) return START_PAUSE - phase;
        if (phase < START_PAUSE + travel) return Math.min(33, START_PAUSE + travel - phase);
        return START_PAUSE + travel + END_PAUSE - phase;
    }

    private long phase(long now) {
        return Math.max(0, now - started) % (START_PAUSE + travel + END_PAUSE);
    }
}
