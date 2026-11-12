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
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Visible rows only: main-thread bookkeeping, bounded background I/O. */
final class ChannelLogoLoader {
    static final int MEMORY_BYTES = android.os.Build.VERSION.SDK_INT <= 19 ? 512 * 1024 : 1024 * 1024;
    static final int MAX_DIMENSION = 512;
    private static int targetEdge(android.content.Context context) {
        float scale = context instanceof xiao.bu.tv.skin.SharedTvUi.Viewport
                ? ((xiao.bu.tv.skin.SharedTvUi.Viewport) context).themeScale()
                : context.getResources().getDisplayMetrics().density;
        int requested = Math.max(64, Math.min(256, Math.round(56f * scale * 1.5f)));
        int edge = 64; while (edge < requested) edge *= 2;
        return edge;
    }
    static String cacheKey(android.content.Context context, String name, String url) {
        // A size revision prevents reusing old, visibly undersampled PNGs.
        return "size-v2\n" + name.trim() + "\n" + url.trim() + "\n" + targetEdge(context);
    }
    private static Bitmap trimTransparent(Bitmap source) {
        if (!source.hasAlpha()) return source;
        int width = source.getWidth(), height = source.getHeight();
        int[] pixels = new int[width * height];
        source.getPixels(pixels, 0, width, 0, 0, width, height);
        int left = width, right = -1, top = height, bottom = -1;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            if ((pixels[y * width + x] >>> 24) == 0) continue;
            left = Math.min(left, x); right = Math.max(right, x);
            top = Math.min(top, y); bottom = Math.max(bottom, y);
        }
        if (right < left || left == 0 && top == 0 && right == width - 1 && bottom == height - 1) return source;
        Bitmap cropped = Bitmap.createBitmap(right - left + 1, bottom - top + 1, Bitmap.Config.ARGB_8888);
        new android.graphics.Canvas(cropped).drawBitmap(source,
                new android.graphics.Rect(left, top, right + 1, bottom + 1),
                new android.graphics.Rect(0, 0, cropped.getWidth(), cropped.getHeight()), null);
        source.recycle(); return cropped;
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, ChannelLogoCache.Entry> cache = new LruCache<String, ChannelLogoCache.Entry>(MEMORY_BYTES) {
        @Override protected int sizeOf(String key, ChannelLogoCache.Entry entry) {
            return entry.bitmap.getRowBytes() * entry.bitmap.getHeight();
        }
    };
    private final LruCache<String, Long> failures = new LruCache<String, Long>(64);
    private final HashMap<String, Job> pending = new HashMap<String, Job>();
    private final ThreadPoolExecutor worker;
    private ChannelLogoCache disk;
    private boolean closed;
    private volatile int generation;
    ChannelLogoLoader() {
        int threads = android.os.Build.VERSION.SDK_INT <= 19 ? 1 : 2;
        worker = new ThreadPoolExecutor(threads, threads, 10L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(24), new ThreadPoolExecutor.AbortPolicy());
        worker.allowCoreThreadTimeOut(true);
    }
    private static final class Request {
        final ChannelLogoLoader owner;
        final String name, key;
        final WeakReference<ImageView> view;
        boolean cancelled;
        long savedAt;
        Job job;
        Request(ChannelLogoLoader owner, ImageView target, String name, String key) {
            this.owner = owner;
            view = new WeakReference<ImageView>(target); this.name = name; this.key = key;
        }
    }
    boolean matchesChannel(ImageView view, String name) {
        Object tag = view.getTag();
        return tag instanceof Request && ((Request) tag).name.equals(name == null ? "" : name.trim());
    }
    void clear(ImageView view) {
        Object old = view.getTag(); view.setTag(null);
        if (old instanceof Request) {
            Request request = (Request) old; request.cancelled = true;
            Job job = request.job;
            if (job != null && !job.hasTargets()) {
                job.cancelled = true; worker.remove(job);
                if (pending.get(job.key) == job) pending.remove(job.key);
            }
        }
        view.setImageDrawable(null); view.setVisibility(View.GONE);
    }
    void load(ImageView view, String channelName, String value) {
        String name = channelName == null ? "" : channelName.trim();
        String url = value == null ? "" : value.trim();
        String key = cacheKey(view.getContext(), name, url);
        long now = System.currentTimeMillis(); Object tag = view.getTag();
        ChannelLogoCache.Entry hit = cache.get(key); Long failedUntil = failures.get(key);
        if (tag instanceof Request) {
            Request old = (Request) tag;
            if (old.owner == this && !old.cancelled && old.key.equals(key)
                    && (old.job != null && !old.job.cancelled && pending.get(key) == old.job
                    || old.savedAt > 0 && now >= old.savedAt && now - old.savedAt < ChannelLogoCache.VALID_MS
                    || hit != null && hit.fresh(now) || failedUntil != null && now < failedUntil
                    || !url.startsWith("https://") && !url.startsWith("http://"))) return;
        }
        clear(view);
        if (closed || name.length() == 0) return;
        // Keep every title in one column, including pending/missing logos.
        view.setVisibility(View.INVISIBLE);
        Request request = new Request(this, view, name, key); view.setTag(request);
        if (hit != null && hit.fresh(now)) { show(request, hit); return; }
        cache.remove(key);
        if (failedUntil != null && now < failedUntil) return;
        if (!url.startsWith("https://") && !url.startsWith("http://")) return;
        Job job = pending.get(key);
        if (job == null) {
            if (disk == null) disk = new ChannelLogoCache(view.getContext().getApplicationContext().getCacheDir());
            job = new Job(key, url, targetEdge(view.getContext()), generation, disk); pending.put(key, job);
            request.job = job; job.targets.add(request);
            try { worker.execute(job); }
            catch (java.util.concurrent.RejectedExecutionException full) { pending.remove(key); job.cancelled = true; }
        } else { request.job = job; job.targets.add(request); }
    }
    private void show(Request request, ChannelLogoCache.Entry entry) {
        ImageView target = request.view.get();
        if (target == null || target.getTag() != request || request.cancelled) return;
        request.savedAt = entry.savedAt;
        target.setImageBitmap(entry.bitmap); target.setVisibility(View.VISIBLE);
    }
    private final class Job implements Runnable {
        final String key, url; final int edge, epoch; final ChannelLogoCache store;
        final ArrayList<Request> targets = new ArrayList<Request>();
        volatile boolean cancelled;
        Job(String key, String url, int edge, int epoch, ChannelLogoCache store) { this.key = key; this.url = url; this.edge = edge; this.epoch = epoch; this.store = store; }
        // Main only. Workers never inspect View state.
        boolean hasTargets() {
            for (Request target : targets) {
                ImageView view = target.view.get();
                if (!target.cancelled && view != null && view.getTag() == target) return true;
            }
            return false;
        }
        boolean stopped() { return cancelled || epoch != generation || Thread.currentThread().isInterrupted(); }
        @Override public void run() {
            if (stopped()) return;
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            ChannelLogoCache.Entry found = store.read(key, System.currentTimeMillis());
            if (found == null && !stopped()) {
                HttpURLConnection connection = null;
                try {
                    // A total deadline also covers DNS, redirects and proxy retries.
                    connection = NetworkClient.openComponent(new URL(url), false, 4000);
                    connection.setConnectTimeout(2000); connection.setReadTimeout(2000);
                    byte[] data;
                    try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[4096]; int count;
                        while ((count = input.read(buffer)) != -1) {
                            if (stopped()) return;
                            if (bytes.size() + count > 512 * 1024) throw new java.io.IOException("Large logo");
                            bytes.write(buffer, 0, count);
                        }
                        data = bytes.toByteArray();
                    }
                    if (stopped()) return;
                    BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(data, 0, data.length, options);
                    if (options.outWidth <= 0 || options.outHeight <= 0
                            || options.outWidth > 8192 || options.outHeight > 8192
                            || (long) options.outWidth * options.outHeight > 16L * 1024 * 1024)
                        throw new java.io.IOException("Invalid logo dimensions");
                    options.inSampleSize = 1;
                    // Retain more source pixels than the physical display slot;
                    // reducing 300px logos to 75px then enlarging them causes blur.
                    while (Math.max(options.outWidth, options.outHeight) / (2L * options.inSampleSize) >= edge) options.inSampleSize *= 2;
                    options.inJustDecodeBounds = false;
                    Bitmap decoded = BitmapFactory.decodeByteArray(data, 0, data.length, options);
                    if (decoded != null && !stopped()) {
                        decoded = trimTransparent(decoded);
                        long saved = System.currentTimeMillis(); found = new ChannelLogoCache.Entry(decoded, saved);
                        try { store.write(key, decoded, saved); } catch (java.io.IOException ignored) { }
                    }
                } catch (Exception ignored) { /* An absent logo retains its aligned empty slot. */ }
                finally { if (connection != null) connection.disconnect(); }
            }
            final ChannelLogoCache.Entry result = found;
            main.post(new Runnable() { @Override public void run() {
                if (closed || stopped() || pending.get(key) != Job.this) return;
                pending.remove(key);
                if (result == null) failures.put(key, System.currentTimeMillis() + 60000L);
                else { cache.put(key, result); failures.remove(key); }
                for (Request target : targets) { target.job = null; if (result != null) show(target, result); }
            }});
        }
    }
    void cancelPending() {
        generation++; for (Job job : pending.values()) job.cancelled = true;
        pending.clear(); worker.getQueue().clear();
    }
    void close() {
        closed = true; cancelPending(); worker.shutdownNow();
        cache.evictAll(); failures.evictAll(); main.removeCallbacksAndMessages(null);
    }
}
