package xiao.bu.tv;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;

/** One producer, independent replaying readers; incomplete/failed bodies never enter the cache. */
final class ProgressiveMediaBuffer {
    private final List<byte[]> chunks = new ArrayList<byte[]>();
    private final int limit;
    private int size;
    private int expectedLength = -1;
    private byte[] contiguous;
    private boolean complete;
    private byte[] completedBody;
    private IOException failure;
    ProgressiveMediaBuffer(int limit) { this.limit = limit; }
    synchronized void expectLength(int length) throws IOException {
        if (length < 0 || length > limit || size != 0 || complete || failure != null)
            throw new IOException("Invalid progressive media length");
        expectedLength = length;
    }
    synchronized int expectedLength() { return expectedLength; }
    synchronized void append(byte[] ownedBytes) throws IOException {
        if (failure != null) throw failure;
        if (complete) throw new IOException("Media already complete");
        if (ownedBytes.length > limit - size) throw new IOException("Progressive media exceeds limit");
        if (expectedLength >= 0 && ownedBytes.length > expectedLength - size)
            throw new IOException("Progressive media exceeds declared length");
        if (ownedBytes.length == 0) return;
        // Prefetch can announce several downloads before ordered decryption
        // starts. Reserve the full body only when its first clear PES arrives.
        if (expectedLength >= 0 && contiguous == null)
            contiguous = ownedBytes.length == expectedLength ? ownedBytes : new byte[expectedLength];
        if (contiguous != null) {
            if (contiguous != ownedBytes) System.arraycopy(ownedBytes, 0, contiguous, size, ownedBytes.length);
        }
        else chunks.add(ownedBytes);
        size += ownedBytes.length; notifyAll();
    }
    synchronized byte[] finish() throws IOException {
        if (failure != null) throw failure;
        if (completedBody != null) return completedBody;
        if (expectedLength >= 0 && size != expectedLength)
            throw new IOException("Incomplete progressive media " + size + "/" + expectedLength);
        byte[] body = contiguous != null ? contiguous : chunks.size() == 1 ? chunks.get(0) : new byte[size]; int offset = 0;
        if (contiguous == null && chunks.size() != 1)
            for (byte[] chunk : chunks) { System.arraycopy(chunk, 0, body, offset, chunk.length); offset += chunk.length; }
        completedBody = body;
        // Readers retain absolute positions, so consolidating storage cannot
        // replay/skip data for a reader midway through an old chunk.
        chunks.clear();
        complete = true; notifyAll(); return body;
    }
    synchronized void fail(IOException error) {
        if (complete || failure != null) return;
        failure = error; notifyAll();
    }
    InputStream open() {
        return new InputStream() {
            private int chunkIndex, offset, position;
            private boolean closed;
            public int read() throws IOException {
                byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
            }
            public int read(byte[] target, int start, int count) throws IOException {
                if (start < 0 || count < 0 || count > target.length - start) throw new IndexOutOfBoundsException();
                if (count == 0) return 0;
                synchronized (ProgressiveMediaBuffer.this) {
                    for (;;) {
                        if (closed) throw new IOException("Media reader closed");
                        if (failure != null) throw failure;
                        if (completedBody != null) {
                            if (position == size) return -1;
                            int n = Math.min(count, size - position);
                            System.arraycopy(completedBody, position, target, start, n);
                            position += n;
                            return n;
                        }
                        if (contiguous != null && position < size) {
                            int n = Math.min(count, size - position);
                            System.arraycopy(contiguous, position, target, start, n);
                            position += n;
                            return n;
                        }
                        if (chunkIndex < chunks.size()) {
                            byte[] chunk = chunks.get(chunkIndex);
                            int n = Math.min(count, chunk.length - offset);
                            System.arraycopy(chunk, offset, target, start, n);
                            offset += n; position += n;
                            if (offset == chunk.length) { chunkIndex++; offset = 0; }
                            return n;
                        }
                        if (complete) return -1;
                        try { ProgressiveMediaBuffer.this.wait(); }
                        catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new InterruptedIOException("Media reader cancelled");
                        }
                    }
                }
            }
            public void close() { synchronized (ProgressiveMediaBuffer.this) { closed = true; ProgressiveMediaBuffer.this.notifyAll(); } }
        };
    }
}
