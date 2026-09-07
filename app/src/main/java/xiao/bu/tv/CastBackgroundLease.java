package xiao.bu.tv;

/** Tracks an active cast across Activity visibility changes without expiring it. */
final class CastBackgroundLease {
    private boolean active;
    private boolean foreground;

    synchronized void setActive(boolean value, long now) {
        active = value;
    }

    synchronized void setForeground(boolean value, long now) {
        foreground = value;
    }

    synchronized boolean isActive() { return active; }
    synchronized boolean isBackground() { return active && !foreground; }
    synchronized long remaining(long now) { return -1L; }
}
