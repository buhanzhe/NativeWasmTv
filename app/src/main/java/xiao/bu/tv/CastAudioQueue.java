package xiao.bu.tv;

import java.util.ArrayDeque;

/** AAC access units are independent. Prefer a short audio gap to stale audio. */
final class CastAudioQueue {
    static final int MAX_FRAMES = 2;
    static final long MAX_AGE_NS = 45_000_000L;
    static final class Frame {
        final byte[] data;
        final long ptsUs, queuedNs;
        Frame(byte[] data, long ptsUs, long queuedNs) {
            this.data = data; this.ptsUs = ptsUs; this.queuedNs = queuedNs;
        }
    }
    private final ArrayDeque<Frame> frames = new ArrayDeque<Frame>();
    private boolean closed;
    private long dropped;

    synchronized void offer(Frame frame) {
        if (closed) return;
        if (frame.data.length > 8191) { dropped++; return; } // RTP AAC 13-bit AU size
        while (!frames.isEmpty() && (frames.size() >= MAX_FRAMES
                || frame.queuedNs - frames.peek().queuedNs > MAX_AGE_NS)) {
            frames.remove(); dropped++;
        }
        frames.add(frame); notifyAll();
    }

    synchronized Frame take() throws InterruptedException {
        while (!closed) {
            if (frames.isEmpty()) { wait(); continue; }
            Frame frame = frames.remove();
            if (System.nanoTime() - frame.queuedNs <= MAX_AGE_NS) return frame;
            dropped++;
        }
        return null;
    }

    synchronized long droppedFrames() { return dropped; }
    synchronized void close() { closed = true; frames.clear(); notifyAll(); }
}
