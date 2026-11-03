package xiao.bu.tv;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;

/** Bounded one-producer/one-consumer handoff with notifications in both directions. */
final class CmgBytePipe extends InputStream {
    private final byte[] buffer;
    private int head, size;
    private boolean readerClosed, writerClosed;
    private long readWaitNanos, writeWaitNanos;

    CmgBytePipe(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Invalid pipe capacity");
        buffer = new byte[capacity];
    }

    OutputStream output() {
        return new OutputStream() {
            public void write(int value) throws IOException {
                write(new byte[] {(byte) value}, 0, 1);
            }
            public void write(byte[] data, int offset, int count) throws IOException {
                checkBounds(data, offset, count);
                synchronized (CmgBytePipe.this) {
                    while (count > 0) {
                        if (readerClosed || writerClosed) throw new IOException("CMG pipe closed");
                        if (size == buffer.length) {
                            long start = System.nanoTime();
                            try { awaitData(); }
                            finally { writeWaitNanos += System.nanoTime() - start; }
                            continue;
                        }
                        int tail = (head + size) % buffer.length;
                        int n = Math.min(count, Math.min(buffer.length - size, buffer.length - tail));
                        System.arraycopy(data, offset, buffer, tail, n);
                        size += n; offset += n; count -= n;
                        CmgBytePipe.this.notifyAll();
                    }
                }
            }
            public void close() {
                synchronized (CmgBytePipe.this) {
                    writerClosed = true;
                    CmgBytePipe.this.notifyAll();
                }
            }
        };
    }

    public synchronized int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
    }

    public synchronized int read(byte[] data, int offset, int count) throws IOException {
        checkBounds(data, offset, count);
        if (count == 0) return 0;
        while (size == 0 && !readerClosed && !writerClosed) {
            long start = System.nanoTime();
            try { awaitData(); }
            finally { readWaitNanos += System.nanoTime() - start; }
        }
        if (readerClosed) throw new IOException("CMG pipe reader closed");
        if (size == 0) return -1;
        int n = Math.min(count, Math.min(size, buffer.length - head));
        System.arraycopy(buffer, head, data, offset, n);
        head = (head + n) % buffer.length; size -= n;
        notifyAll(); // A producer waiting for space must not wait for another read.
        return n;
    }

    private void awaitData() throws InterruptedIOException {
        try { wait(); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("CMG pipe cancelled");
        }
    }

    private static void checkBounds(byte[] data, int offset, int count) {
        if (offset < 0 || count < 0 || count > data.length - offset)
            throw new IndexOutOfBoundsException();
    }

    synchronized long readWaitMs() { return readWaitNanos / 1000000L; }
    synchronized long writeWaitMs() { return writeWaitNanos / 1000000L; }

    public synchronized void close() {
        readerClosed = true;
        notifyAll();
    }
}
