package xiao.bu.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One cancellable, bounded cover read; failure leaves the default record visible. */
final class AlbumArtLoader {
    interface Callback { void loaded(Bitmap art); }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 10L,
            TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), task -> {
                Thread thread = new Thread(task, "album-art");
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });
    private Future<?> pending;
    private static AlbumArtCache coverCache;

    private static synchronized AlbumArtCache cache(Context app) throws IOException {
        if (coverCache == null) coverCache = new AlbumArtCache(
                new java.io.File(app.getCacheDir(), "album-art-v1"), AlbumArtCache.MAX_BYTES);
        return coverCache;
    }
    private int generation;

    AlbumArtLoader() { worker.allowCoreThreadTimeOut(true); }

    void clear() {
        generation++;
        if (pending != null) pending.cancel(true);
        worker.getQueue().clear();
        pending = null;
    }

    void load(Context context, String address, String headers, String logoUrl, Callback callback) {
        clear();
        final int request = generation;
        final Context app = context.getApplicationContext();
        pending = worker.submit(() -> {
            Bitmap bitmap = readArtwork(app, address, headers, false);
            // A station logo is a fallback, not the album cover of the current song.
            if (bitmap == null && !Thread.currentThread().isInterrupted() && logoUrl != null
                    && (logoUrl.startsWith("https://") || logoUrl.startsWith("http://")))
                bitmap = readArtwork(app, logoUrl, null, true);
            final Bitmap art = bitmap;
            main.post(() -> {
                if (request == generation) callback.loaded(art);
                else if (art != null) art.recycle();
            });
        });
    }

    private static Bitmap readArtwork(Context app, String address, String headers, boolean image) {
        HttpURLConnection connection = null;
        AlbumArtCache disk = null;
        String cacheKey = null;
        try {
            Uri uri = Uri.parse(address);
            if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) {
                try {
                    disk = cache(app);
                    // Distinguish an embedded APIC from an image URL and authenticated variants.
                    cacheKey = AlbumArtCache.key((image ? "image\n" : "apic\n") + address + "\n" + (headers == null ? "" : headers));
                    byte[] cached = disk.get(cacheKey);
                    if (cached != null) {
                        Bitmap hit = decodeArtwork(cached);
                        if (hit != null) return hit;
                        disk.remove(cacheKey);
                    }
                } catch (IOException ignored) { disk = null; } // Disk failure must not block playback.
            }
            InputStream input;
            if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) {
                connection = NetworkClient.open(new URL(address));
                connection.setConnectTimeout(2500);
                connection.setReadTimeout(2500);
                connection.setRequestProperty("User-Agent", "nTv/" + BuildConfig.VERSION_NAME);
                if (!image) {
                    connection.setRequestProperty("Range", "bytes=0-" + (Id3Artwork.MAX_TAG_BYTES + 9));
                    connection.setRequestProperty("Icy-MetaData", "0");
                }
                if (headers != null) for (String line : headers.split("\r?\n")) {
                    int colon = line.indexOf(':');
                    if (colon > 0) connection.setRequestProperty(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
                }
                input = connection.getInputStream();
            } else {
                if (uri.getScheme() == null) uri = Uri.fromFile(new java.io.File(address));
                input = app.getContentResolver().openInputStream(uri);
            }
            try (InputStream stream = input) {
                byte[] art = stream == null ? null : image ? readImage(stream) : Id3Artwork.read(stream);
                if (art != null && !Thread.currentThread().isInterrupted()) {
                    Bitmap decoded = decodeArtwork(art);
                    if (decoded != null && disk != null && !Thread.currentThread().isInterrupted()) {
                        try { disk.put(cacheKey, art); } catch (IOException ignored) { }
                    }
                    return decoded;
                }
            }
        } catch (Exception ignored) {
            // Live MP3 usually has no APIC tag. Artwork must never delay or fail playback.
        } finally {
            if (connection != null) connection.disconnect();
        }
        return null;
    }

    private static Bitmap decodeArtwork(byte[] art) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(art, 0, art.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(art, 0, art.length, options);
    }

    private static byte[] readImage(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long deadline = System.nanoTime() + 6000000000L;
        while (bytes.size() <= Id3Artwork.MAX_TAG_BYTES) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) return null;
            int count = input.read(buffer, 0, Math.min(buffer.length, Id3Artwork.MAX_TAG_BYTES - bytes.size() + 1));
            if (count < 0) return bytes.toByteArray();
            if (count > Id3Artwork.MAX_TAG_BYTES - bytes.size()) return null;
            bytes.write(buffer, 0, count);
        }
        return null;
    }

    void close() { clear(); worker.shutdownNow(); }
}
