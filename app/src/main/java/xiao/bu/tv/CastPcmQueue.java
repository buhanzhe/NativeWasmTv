package xiao.bu.tv;

import java.util.ArrayDeque;

/** Bounded PCM between AudioRecord and AAC. Old sound must never delay interaction. */
final class CastPcmQueue {
    static final int MAX_FRAMES = 2;
    static final long MAX_AGE_NS = 45_000_000L;

    static final class Frame {
        final byte[] data;
        final int length;
        final long ptsUs;
        final long queuedNs;

        Frame(byte[] data, int length, long ptsUs, long queuedNs) {
            this.data = data;
            this.length = length;
            this.ptsUs = ptsUs;
            this.queuedNs = queuedNs;
        }

        boolean isStale(long nowNs) {
            return nowNs - queuedNs > MAX_AGE_NS;
        }
    }

    private final int bufferBytes;
    private final ArrayDeque<Frame> frames = new ArrayDeque<Frame>();
    private final ArrayDeque<byte[]> recycled = new ArrayDeque<byte[]>();
    private boolean closed;
    private long dropped;

    CastPcmQueue(int bufferBytes) {
        this.bufferBytes = bufferBytes;
    }

    synchronized byte[] obtain() {
        byte[] value = recycled.poll();
        return value == null ? new byte[bufferBytes] : value;
    }

    synchronized void recycle(byte[] value) {
        if (value != null && value.length == bufferBytes && recycled.size() < MAX_FRAMES + 2) {
            recycled.add(value);
        }
    }

    synchronized void offer(Frame frame) {
        if (closed) {
            recycle(frame.data);
            return;
        }
        while (!frames.isEmpty() && (frames.size() >= MAX_FRAMES
                || frame.queuedNs - frames.peek().queuedNs > MAX_AGE_NS)) {
            recycle(frames.remove().data);
            dropped++;
        }
        frames.add(frame);
        notifyAll();
    }

    synchronized Frame take() throws InterruptedException {
        while (!closed) {
            if (frames.isEmpty()) {
                wait();
                continue;
            }
            Frame frame = frames.remove();
            if (!frame.isStale(System.nanoTime())) return frame;
            recycle(frame.data);
            dropped++;
        }
        return null;
    }

    synchronized long droppedFrames() {
        return dropped;
    }

    synchronized void close() {
        closed = true;
        while (!frames.isEmpty()) recycle(frames.remove().data);
        notifyAll();
    }
}
