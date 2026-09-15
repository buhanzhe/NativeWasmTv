package xiao.bu.tv;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okhttp3.internal.cache.DiskLruCache;
import okhttp3.internal.io.FileSystem;
import okio.BufferedSink;
import okio.Okio;

/** OkHttp 3.12 disk journal/atomic entries, with FIFO eviction instead of its default LRU. */
final class AlbumArtCache implements Closeable {
    static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;
    private final File directory;
    private final long limit;
    private final DiskLruCache disk;
    private final LinkedHashMap<String, Long> order = new LinkedHashMap<String, Long>();
    private long sequence;

    AlbumArtCache(File directory, long limit) throws IOException {
        this.directory = directory;
        this.limit = limit;
        // This internal API is pinned with OkHttp 3.12 for Android 4.x compatibility.
        // Disable automatic LRU eviction; all writes and FIFO trimming are serialized here.
        disk = DiskLruCache.create(FileSystem.SYSTEM, directory, 1, 2, Long.MAX_VALUE);
        disk.initialize();
        List<Map.Entry<String, Long>> saved = new ArrayList<Map.Entry<String, Long>>();
        Iterator<DiskLruCache.Snapshot> snapshots = disk.snapshots();
        while (snapshots.hasNext()) {
            try (DiskLruCache.Snapshot snapshot = snapshots.next()) {
                try {
                    if (snapshot.getLength(1) > 128) throw new IOException("Invalid cover metadata");
                    String metadata = Okio.buffer(snapshot.getSource(1)).readUtf8();
                    long number = Long.parseLong(metadata.split("\n")[0]);
                    saved.add(new java.util.AbstractMap.SimpleImmutableEntry<String, Long>(snapshot.key(), number));
                    sequence = Math.max(sequence, number);
                } catch (Exception invalid) { disk.remove(snapshot.key()); }
            }
        }
        Collections.sort(saved, new Comparator<Map.Entry<String, Long>>() {
            @Override public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
                return a.getValue().compareTo(b.getValue());
            }
        });
        for (Map.Entry<String, Long> entry : saved) order.put(entry.getKey(), entry.getValue());
        trim(0);
    }

    static String key(String value) throws IOException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));
            StringBuilder text = new StringBuilder(64);
            for (byte b : hash) text.append("0123456789abcdef".charAt((b & 255) >> 4))
                    .append("0123456789abcdef".charAt(b & 15));
            return text.toString();
        } catch (Exception error) { throw new IOException("Cannot name cover cache", error); }
    }

    synchronized byte[] get(String key) throws IOException {
        byte[] result = null;
        try (DiskLruCache.Snapshot snapshot = disk.get(key)) {
            if (snapshot == null) { order.remove(key); return null; }
            try {
                if (snapshot.getLength(0) > Id3Artwork.MAX_TAG_BYTES || snapshot.getLength(1) > 128)
                    throw new IOException("Oversize cover cache entry");
                String[] metadata = Okio.buffer(snapshot.getSource(1)).readUtf8().split("\n");
                long saved = Long.parseLong(metadata[1]), now = System.currentTimeMillis();
                if (now >= saved && now - saved < MAX_AGE_MS)
                    result = Okio.buffer(snapshot.getSource(0)).readByteArray();
            } catch (RuntimeException invalid) { /* Treat damaged metadata as a cache miss. */ }
        }
        if (result == null) remove(key);
        // Reading never changes our persisted insertion order. Include the journal in the limit.
        trim(0);
        return result;
    }

    synchronized void put(String key, byte[] image) throws IOException {
        if (image == null || image.length == 0 || image.length > Id3Artwork.MAX_TAG_BYTES
                || image.length + 512 > limit) return;
        remove(key);
        String metadata = (++sequence) + "\n" + System.currentTimeMillis() + "\n";
        trim(image.length + metadata.length() + 256);
        DiskLruCache.Editor editor = disk.edit(key);
        if (editor == null) return;
        boolean committed = false;
        try {
            try (BufferedSink bytes = Okio.buffer(editor.newSink(0))) { bytes.write(image); }
            try (BufferedSink info = Okio.buffer(editor.newSink(1))) { info.writeUtf8(metadata); }
            editor.commit();
            committed = true;
            order.put(key, sequence);
        } finally { if (!committed) editor.abort(); }
        disk.flush();
        trim(0);
    }

    synchronized void remove(String key) throws IOException { disk.remove(key); order.remove(key); }

    private void trim(long incoming) throws IOException {
        disk.flush();
        while (!order.isEmpty() && directoryBytes() + incoming > limit) {
            String oldest = order.keySet().iterator().next();
            remove(oldest);
            disk.flush();
        }
    }

    private long directoryBytes() {
        long total = 0;
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) if (file.isFile()) total += file.length();
        return total;
    }

    @Override public synchronized void close() throws IOException { disk.close(); }
}
