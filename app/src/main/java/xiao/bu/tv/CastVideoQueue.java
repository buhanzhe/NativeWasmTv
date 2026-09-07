package xiao.bu.tv;

import java.util.ArrayDeque;

/** Bounded encoded access units. After a drop, resume only at a fresh IDR. */
final class CastVideoQueue {
    // A large HEVC IDR can occupy the network sender for several display
    // intervals. Keep enough room for that short, predictable burst. The age
    // bound still prevents a slow link from turning this into pointer latency.
    static final long MAX_AGE_NS = 100_000_000L;
    static final int MAX_FRAMES = 6;
    private static final int MAX_BYTES = 3 * 1024 * 1024;
    static final class Frame {
        final byte[] data;
        final long ptsUs, queuedNs;
        final int flags;
        final boolean key;
        Frame(byte[] data, long ptsUs, int flags, boolean key, long now) {
            this.data=data; this.ptsUs=ptsUs; this.flags=flags;
            this.key=key; this.queuedNs=now;
        }
    }
    private final ArrayDeque<Frame> frames = new ArrayDeque<Frame>();
    private boolean closed, awaitingKey = true, syncRequested = true;
    private int bytes;
    private long dropped;

    synchronized void offer(Frame frame) {
        if (closed) return;
        if (frame.data.length > MAX_BYTES) { invalidate(); dropped++; return; }
        if (frames.size() >= MAX_FRAMES || bytes + frame.data.length > MAX_BYTES
                || (!frames.isEmpty() && frame.queuedNs - frames.peek().queuedNs > MAX_AGE_NS)) {
            invalidate();
        }
        if (awaitingKey && !frame.key) { dropped++; return; }
        if (frame.key) { awaitingKey=false; syncRequested=false; }
        frames.add(frame); bytes+=frame.data.length; notifyAll();
    }

    synchronized Frame take() throws InterruptedException {
        while (!closed && frames.isEmpty()) wait();
        if (closed) return null;
        Frame frame=frames.remove(); bytes-=frame.data.length;
        if (System.nanoTime()-frame.queuedNs > MAX_AGE_NS) {
            dropped++; invalidate(); return null;
        }
        return frame;
    }

    synchronized boolean needsSyncFrame() { return syncRequested; }
    synchronized long droppedFrames() { return dropped; }
    synchronized int size() { return frames.size(); }
    synchronized boolean isClosed() { return closed; }
    private void invalidate() {
        dropped+=frames.size(); frames.clear(); bytes=0;
        awaitingKey=true; syncRequested=true;
    }
    synchronized void close() { closed=true; frames.clear(); bytes=0; notifyAll(); }
}
