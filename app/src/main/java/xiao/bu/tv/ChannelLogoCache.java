package xiao.bu.tv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;

/** Worker-thread disk cache; filenames depend only on the channel name. */
final class ChannelLogoCache {
    static final long VALID_MS = 24L * 60L * 60L * 1000L;
    private final File directory;

    static final class Entry {
        final Bitmap bitmap;
        final long savedAt;
        Entry(Bitmap bitmap, long savedAt) { this.bitmap = bitmap; this.savedAt = savedAt; }
        boolean fresh(long now) { return now >= savedAt && now - savedAt < VALID_MS; }
    }

    ChannelLogoCache(File cacheDirectory) { directory = new File(cacheDirectory, "channel-logos-v1"); }

    File fileFor(String name) throws IOException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(name.trim().getBytes("UTF-8"));
            char[] hex = "0123456789abcdef".toCharArray();
            StringBuilder key = new StringBuilder(68);
            for (byte b : hash) { key.append(hex[(b & 255) >>> 4]); key.append(hex[b & 15]); }
            return new File(directory, key.append(".png").toString());
        } catch (Exception error) { throw new IOException("Unable to name logo cache", error); }
    }

    Entry read(String name, long now) {
        try {
            File file = fileFor(name);
            long saved = file.lastModified();
            if (!file.isFile() || now < saved || now - saved >= VALID_MS
                    || file.length() <= 0 || file.length() > 512 * 1024) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                    || bounds.outWidth > 128 || bounds.outHeight > 128
                    || !"image/png".equals(bounds.outMimeType)) return null;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getPath());
            return bitmap == null ? null : new Entry(bitmap, saved);
        } catch (Exception ignored) { return null; }
    }

    void write(String name, Bitmap bitmap, long now) throws IOException {
        File target = fileFor(name);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create logo cache");
        File temporary = File.createTempFile("logo-", ".tmp", directory);
        try {
            FileOutputStream output = new FileOutputStream(temporary);
            try {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("Cannot encode logo PNG");
            } finally { output.close(); }
            if (!temporary.setLastModified(now) || !temporary.renameTo(target)) {
                throw new IOException("Cannot publish logo cache");
            }
        } finally { if (temporary.exists()) temporary.delete(); }
    }
}
