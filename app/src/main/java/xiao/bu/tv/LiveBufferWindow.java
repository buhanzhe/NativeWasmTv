package xiao.bu.tv;

/** The unplayed A/V window, not a retained history or a remote DVR timeline. */
final class LiveBufferWindow {
    static long duration(long videoMs, long audioMs, boolean video, boolean audio) {
        videoMs = Math.max(0L, videoMs); audioMs = Math.max(0L, audioMs);
        return video && audio ? Math.min(videoMs, audioMs) : video ? videoMs : audio ? audioMs : 0L;
    }
    static long seekMaximum(long bufferedMs) { return Math.max(0L, bufferedMs - 250L); }
    static long clamp(long advanceMs, long bufferedMs) {
        return Math.max(0L, Math.min(advanceMs, seekMaximum(bufferedMs)));
    }
    private LiveBufferWindow() { }
}
