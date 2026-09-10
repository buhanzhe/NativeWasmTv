package xiao.bu.tv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Loads bound rows only, on one background worker; recycled requests are cancelled. */
final class ChannelLogoLoader {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, ChannelLogoCache.Entry> cache =
            new LruCache<String, ChannelLogoCache.Entry>(16);
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 10L,
            TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(32),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private boolean closed;
    private volatile int generation;

    private static final class Request {
        final String name, url;
        final long startedAt;
        long until;
        int generation;
        volatile boolean cancelled;
        Runnable job;
        Request(String name, String url, long now) {
            this.name = name; this.url = url; startedAt = now; until = now + 60000L;
        }
    }

    boolean matchesChannel(ImageView view, String name) {
        Object tag = view.getTag();
        return tag instanceof Request && ((Request) tag).name.equals(name.trim());
    }

    void clear(ImageView view) {
        Object old = view.getTag();
        if (old instanceof Request) {
            Request request = (Request) old;
            request.cancelled = true;
            if (request.job != null) worker.remove(request.job);
        }
        view.setTag(null);
        view.setImageDrawable(null);
        view.setVisibility(View.GONE);
    }

    void load(final ImageView view, String channelName, String value) {
        final String name = channelName == null ? "" : channelName.trim();
        final String url = value == null ? "" : value;
        long now = System.currentTimeMillis();
        Object tag = view.getTag();
        if (tag instanceof Request) {
            Request previous = (Request) tag;
            if (name.equals(previous.name) && url.equals(previous.url)
                    && previous.generation == generation
                    && now >= previous.startedAt && now < previous.until) return;
        }
        clear(view);
        if (closed || name.length() == 0) return;
        final Request request = new Request(name, url, now);
        request.generation = generation;
        view.setTag(request);
        ChannelLogoCache.Entry hit = cache.get(name);
        if (hit != null && hit.fresh(now)) {
            request.until = hit.savedAt + ChannelLogoCache.VALID_MS;
            view.setImageBitmap(hit.bitmap);
            view.setVisibility(View.VISIBLE);
            return;
        }
        cache.remove(name);
        final ChannelLogoCache disk = new ChannelLogoCache(view.getContext().getApplicationContext().getCacheDir());
        request.job = new Runnable() {
            @Override public void run() {
                if (request.cancelled || request.generation != generation) return;
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                ChannelLogoCache.Entry entry = disk.read(name, System.currentTimeMillis());
                if (entry != null) { deliver(view, request, entry); return; }
                if (request.cancelled || request.generation != generation) return;
                if (!url.startsWith("https://") && !url.startsWith("http://")) {
                    deliver(view, request, null); return;
                }
                Bitmap decoded = null;
                HttpURLConnection connection = null;
                try {
                    connection = NetworkClient.open(new URL(url));
                    connection.setConnectTimeout(3000);
                    connection.setReadTimeout(3000);
                    InputStream input = connection.getInputStream();
                    byte[] data;
                    try {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        byte[] buffer = new byte[4096];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            if (bytes.size() + count > 512 * 1024) throw new java.io.IOException("Large logo");
                            bytes.write(buffer, 0, count);
                        }
                        data = bytes.toByteArray();
                    } finally { input.close(); }
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(data, 0, data.length, options);
                    options.inSampleSize = 1;
                    while (options.outWidth / options.inSampleSize > 128
                            || options.outHeight / options.inSampleSize > 128) options.inSampleSize *= 2;
                    options.inJustDecodeBounds = false;
                    decoded = BitmapFactory.decodeByteArray(data, 0, data.length, options);
                } catch (Exception ignored) {
                    // Missing and unsupported logos leave no placeholder or gap.
                } finally { if (connection != null) connection.disconnect(); }
                if (decoded != null) {
                    long saved = System.currentTimeMillis();
                    entry = new ChannelLogoCache.Entry(decoded, saved);
                    try { disk.write(name, decoded, saved); } catch (java.io.IOException ignored) {
                        // A full/unavailable disk must not hide a successfully loaded logo.
                    }
                }
                deliver(view, request, entry);
            }
        };
        worker.execute(request.job);
    }

    private void deliver(final ImageView view, final Request request, final ChannelLogoCache.Entry result) {
        main.post(new Runnable() {
            @Override public void run() {
                if (closed) return;
                if (result != null) cache.put(request.name, result);
                if (view.getTag() != request || request.cancelled
                        || request.generation != generation) return;
                request.until = result == null ? System.currentTimeMillis() + 60000L
                        : result.savedAt + ChannelLogoCache.VALID_MS;
                if (result != null) {
                    view.setImageBitmap(result.bitmap);
                    view.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    void cancelPending() {
        generation++;
        worker.getQueue().clear();
    }

    void close() {
        closed = true;
        worker.shutdownNow();
        cache.evictAll();
        main.removeCallbacksAndMessages(null);
    }
}
