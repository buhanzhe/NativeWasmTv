package xiao.bu.tv;

import java.util.ArrayDeque;

/** Bounded encoded access units. After a drop, resume only at a fresh IDR. */
final class CastVideoQueue {
    // A large HEVC IDR can occupy the network sender for several display
    // intervals. Keep enough room for that short, predictable burst. The age
    // bound still prevents a slow link from turning this into pointer latency.
    // Old receivers occasionally stop reading for 100-120 ms while submitting a
    // frame to their decoder. A 150 ms ceiling absorbs that short scheduling
    // pause without turning it into another IDR, while still bounding input lag.
    static final long MAX_AGE_NS = 150_000_000L;
    static final int MAX_FRAMES = 8;
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
    private boolean keyInFlight, drainingKeyChain;
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
        if (frame.key) keyInFlight=true;
        if (System.nanoTime()-frame.queuedNs > MAX_AGE_NS
                && !keyInFlight && !drainingKeyChain) {
            dropped++; invalidate(); return null;
        }
        return frame;
    }

    /** Preserve the short, continuous P-frame chain produced while one IDR is sent. */
    synchronized void frameSent(Frame frame) {
        if (frame.key) {
            keyInFlight=false;
            drainingKeyChain=!frames.isEmpty();
        } else if (drainingKeyChain && frames.isEmpty()) {
            drainingKeyChain=false;
        }
    }

    synchronized boolean needsSyncFrame() { return syncRequested; }
    synchronized long droppedFrames() { return dropped; }
    synchronized int size() { return frames.size(); }
    synchronized boolean isClosed() { return closed; }
    private void invalidate() {
        dropped+=frames.size(); frames.clear(); bytes=0;
        awaitingKey=true; syncRequested=true; drainingKeyChain=false;
    }
    synchronized void close() { closed=true; frames.clear(); bytes=0; notifyAll(); }
}
