package xiao.bu.tv;

/** Manual vertical placement across the whole screen, without preset margins. */
final class SubtitlePlacement {
    static final int DEFAULT_PERCENT = 50;

    static int clamp(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    static int top(int containerHeight, int textHeight, int percent) {
        int travel = Math.max(0, containerHeight - Math.max(0, textHeight));
        return Math.round(travel * (clamp(percent) / 100f));
    }
}
