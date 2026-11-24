package xiao.bu.tv;

/** One tap seeks five seconds; a held key previews a target until release. */
final class RemoteSeekState {
    private final long holdDelay;
    private long duration, anchor, started, target;
    private int direction;
    private boolean holding;

    RemoteSeekState(long holdDelay) { this.holdDelay = Math.max(1, holdDelay); }
    boolean active() { return direction != 0; }
    int direction() { return direction; }
    boolean holding() { return holding; }
    void begin(int direction, long position, long duration, long now) {
        cancel();
        if (duration <= 0 || direction != -1 && direction != 1) return;
        this.direction = direction; this.duration = duration;
        anchor = target = clamp(position); started = now;
    }
    long preview(long now) {
        if (!active() || now < started || now - started < holdDelay) return -1;
        holding = true;
        long elapsed = Math.min(86400000L, now - started - holdDelay);
        long distance = 5000 + Math.min(elapsed, 1800) / 120 * 1000;
        if (elapsed > 1800) distance += Math.min(elapsed - 1800, 3000) / 120 * 3000;
        if (elapsed > 4800) distance += (elapsed - 4800) / 120 * 5000;
        target = offset(anchor, distance);
        return target;
    }
    long finish(long now, long position, boolean cancelled) {
        if (!active()) return -1;
        preview(now);
        long value = cancelled ? -1 : holding ? target : offset(clamp(position), 5000);
        cancel(); return value;
    }
    void cancel() { direction = 0; holding = false; }
    private long offset(long position, long distance) {
        return direction < 0 ? Math.max(0, position - distance)
                : distance >= duration - 1 - position ? duration - 1 : position + distance;
    }
    private long clamp(long value) { return Math.max(0, Math.min(duration - 1, value)); }
}
