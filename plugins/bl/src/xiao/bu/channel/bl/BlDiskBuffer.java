package xiao.bu.channel.bl;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import okhttp3.Response;

/** Private read-through buffer for one VOD. Only consumed bytes reach the disk. */
final class BlDiskBuffer {
    static final String FILE_NAME = "bl-vod.buffer";
    static final int LIMIT = 32 * 1024 * 1024;
    private static final long RESERVE = 128L * 1024 * 1024;
    private static final int BLOCK = 256 * 1024;
    static final int WRITE_BYTES = 32 * 1024;
    private static final int PENDING = 8;
    private static final int MAX_RESOURCES = 24;
    private final Context context;
    private final LinkedHashMap<String,Resource> resources = new LinkedHashMap<>(16, .75f, true);
    private final LinkedHashMap<Key,Span> spans = new LinkedHashMap<>(32, .75f, true);
    private final Semaphore copies = new Semaphore(PENDING);
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(PENDING), task -> {
                Thread thread = new Thread(task, "bl-vod-buffer");
                thread.setDaemon(true);
                return thread;
            });
    private volatile boolean unavailable;
    private File directory, path;
    private RandomAccessFile lease, file;
    private int slots;
    private long retryAt;

    BlDiskBuffer(Context context) { this.context = context; }
    boolean acceptsWrites() { return !unavailable; }

    static final class Resource {
        final String url, resolved, type, tag, modified;
        final long length;

        Resource(String url, Response response, long length) {
            this.url = url;
            resolved = response.request().url().toString();
            type = response.header("Content-Type", "application/octet-stream");
            tag = response.header("ETag", "");
            modified = response.header("Last-Modified", "");
            this.length = length;
        }

        boolean matches(Response response, BlMediaRange range) {
            return range.cacheable(response) && length == range.total
                    && resolved.equals(response.request().url().toString())
                    && type.equals(response.header("Content-Type", "application/octet-stream"))
                    && tag.equals(response.header("ETag", ""))
                    && modified.equals(response.header("Last-Modified", ""));
        }
    }

    synchronized Resource observe(String url, Response response, BlMediaRange range) {
        if (unavailable) return null;
        Resource current = resources.get(url);
        if (current != null && current.matches(response, range)) return current;
        if (current != null) resources.remove(url);
        if (!range.cacheable(response)) return null;
        if (resources.size() == MAX_RESOURCES) {
            Iterator<String> oldest = resources.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        Resource resource = new Resource(url, response, range.total);
        resources.put(url, resource);
        return resource;
    }

    synchronized void invalidate(Resource resource) {
        if (resources.get(resource.url) == resource) resources.remove(resource.url);
    }

    synchronized Resource find(String url, long position) {
        Resource resource = resources.get(url);
        Span span = resource == null ? null : spanAt(resource, position);
        return span != null && span.available((int)(position % BLOCK)) > 0 ? resource : null;
    }

    private Span spanAt(Resource resource, long position) {
        if (file == null || resources.get(resource.url) != resource) return null;
        return spans.get(new Key(resource, position / BLOCK));
    }

    synchronized int read(Resource resource, long position, byte[] buffer, int offset, int count) {
        Span span = spanAt(resource, position);
        if (span == null || unavailable) return 0;
        int within = (int)(position % BLOCK);
        int size = Math.min(count, span.available(within));
        if (size == 0) return 0;
        try {
            file.seek((long)span.slot * BLOCK + within);
            file.readFully(buffer, offset, size);
            return size;
        } catch (IOException failure) {
            stop();
            return 0;
        }
    }

    void offer(Resource resource, long position, byte[] buffer, int offset, int count) {
        if (resource == null || position < 0 || count <= 0 || position > resource.length - count) return;
        for (int i = 0; i < count && !unavailable && copies.tryAcquire();) {
            int size = Math.min(WRITE_BYTES, count - i);
            byte[] data = new byte[size];
            System.arraycopy(buffer, offset + i, data, 0, size);
            final long at = position + i;
            try {
                writer.execute(() -> {
                    try { store(resource, at, data); }
                    finally { copies.release(); }
                });
            } catch (RejectedExecutionException closed) {
                copies.release();
                return;
            }
            i += size;
        }
    }

    private synchronized void store(Resource resource, long position, byte[] data) {
        if (unavailable || resources.get(resource.url) != resource) return;
        long started = System.nanoTime();
        try {
            if (!open()) return;
            for (int copied = 0; copied < data.length;) {
                Key key = new Key(resource, position / BLOCK);
                int within = (int)(position % BLOCK);
                int size = Math.min(BLOCK - within, data.length - copied);
                Span span = spans.get(key);
                if (span == null) {
                    if (directory.getUsableSpace() < RESERVE + BLOCK) {
                        stop();
                        return;
                    }
                    span = allocate(key);
                }
                file.seek((long)span.slot * BLOCK + within);
                file.write(data, copied, size);
                span.include(within, within + size);
                position += size;
                copied += size;
            }
            if (System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(250)) stop();
        } catch (IOException | RuntimeException failure) {
            stop();
        }
    }

    private Span allocate(Key key) {
        int slot;
        if (slots < LIMIT / BLOCK) slot = slots++;
        else {
            Iterator<Span> oldest = spans.values().iterator();
            slot = oldest.next().slot;
            oldest.remove();
        }
        Span span = new Span(slot);
        spans.put(key, span);
        return span;
    }

    private boolean open() throws IOException {
        if (file != null) return true;
        if (retryAt != 0 && System.nanoTime() - retryAt < 0) return false;
        directory = context.getCacheDir();
        if (directory == null) throw new IOException("No cache directory");
        lease = new RandomAccessFile(new File(directory, "bl-vod.lock"), "rw");
        boolean acquired;
        try { acquired = lease.getChannel().tryLock() != null; }
        catch (java.nio.channels.OverlappingFileLockException busy) { acquired = false; }
        if (!acquired) {
            lease.close();
            lease = null;
            retryAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            return false;
        }
        path = new File(directory, FILE_NAME);
        file = new RandomAccessFile(path, "rw");
        file.setLength(0);
        return true;
    }

    synchronized void close() { stop(); }

    private void stop() {
        unavailable = true;
        writer.shutdownNow();
        spans.clear();
        resources.clear();
        if (file != null) try { file.close(); } catch (IOException ignored) {}
        if (path != null) try { path.delete(); } catch (SecurityException ignored) {}
        if (lease != null) try { lease.close(); } catch (IOException ignored) {}
        file = null;
        directory = null;
        path = null;
        lease = null;
    }

    private static final class Key {
        final Resource resource;
        final long block;
        Key(Resource resource, long block) { this.resource = resource; this.block = block; }
        @Override public int hashCode() { return 31 * resource.hashCode() + (int)(block ^ (block >>> 32)); }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key)other;
            return resource == key.resource && block == key.block;
        }
    }

    private static final class Span {
        private static final int MAX_INTERVALS = 16;
        final int slot;
        private final TreeMap<Integer,Integer> coverage = new TreeMap<>();
        Span(int slot) { this.slot = slot; }

        int available(int offset) {
            Map.Entry<Integer,Integer> range = coverage.floorEntry(offset);
            return range == null ? 0 : Math.max(0, range.getValue() - offset);
        }

        void include(int first, int last) {
            Map.Entry<Integer,Integer> before = coverage.floorEntry(first);
            if (before != null && before.getValue() >= first) {
                first = before.getKey();
                last = Math.max(last, before.getValue());
            }
            Map.Entry<Integer,Integer> next;
            while ((next = coverage.ceilingEntry(first)) != null && next.getKey() <= last) {
                last = Math.max(last, next.getValue());
                coverage.remove(next.getKey());
            }
            coverage.put(first, last);
            // A skipped write leaves a hole. Preserve earlier coverage within the block.
            if (coverage.size() > MAX_INTERVALS) coverage.pollLastEntry();
        }
    }
}
