package xiao.bu.tv;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-shot copies only: no recording permission, extra decoder or per-frame work. */
final class VideoScreenshot {
    static final String PATH = "/api/recording/screenshot";
    private static final int MAX_BYTES = 12 * 1024 * 1024;
    private final AtomicBoolean busy = new AtomicBoolean();

    interface Source {
        Target current() throws IOException;
    }

    static final class Target {
        final SurfaceView view;
        final Object session;
        final int width;
        final int height;

        Target(SurfaceView view, Object session, int width, int height) {
            this.view = view;
            this.session = session;
            float scale = Math.min(1f, Math.min(1920f / width, 1080f / height));
            this.width = Math.max(1, Math.round(width * scale));
            this.height = Math.max(1, Math.round(height * scale));
        }
    }

    byte[] capture(final Source source) throws IOException {
        if (Build.VERSION.SDK_INT < 24) {
            return captureLegacy(source);
        }
        if (!busy.compareAndSet(false, true)) {
            throw new IOException("正在截屏，请稍后再试");
        }
        final Capture request = new Capture();
        final Handler main = new Handler(Looper.getMainLooper());
        main.post(new Runnable() {
            @Override public void run() {
                if (request.isAbandoned()) {
                    busy.set(false);
                    return;
                }
                Bitmap bitmap = null;
                try {
                    Target target = source.current();
                    bitmap = Bitmap.createBitmap(target.width, target.height,
                            Bitmap.Config.ARGB_8888);
                    requestCopy(source, target, bitmap, request, main);
                } catch (Exception error) {
                    if (bitmap != null) bitmap.recycle();
                    request.complete(null, new IOException(error.getMessage(), error));
                    busy.set(false);
                } catch (OutOfMemoryError error) {
                    if (bitmap != null) bitmap.recycle();
                    request.complete(null, new IOException("内存不足，暂时无法截屏"));
                    busy.set(false);
                }
            }
        });
        Bitmap bitmap;
        try {
            bitmap = request.await();
        } catch (IOException error) {
            // A timeout can race the callback just after it publishes the image.
            // Pending copies release this flag themselves when they finally finish.
            if (request.ready.getCount() == 0) busy.set(false);
            throw error;
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IOException("截屏图片生成失败");
            }
            return output.toByteArray();
        } catch (OutOfMemoryError error) {
            throw new IOException("内存不足，暂时无法保存截屏");
        } finally {
            bitmap.recycle();
            busy.set(false);
        }
    }

    private static <T> T onMain(java.util.concurrent.Callable<T> action) throws IOException {
        java.util.concurrent.FutureTask<T> task = new java.util.concurrent.FutureTask<T>(action);
        new Handler(Looper.getMainLooper()).post(task);
        try { return task.get(8, TimeUnit.SECONDS); }
        catch (Exception error) { task.cancel(false); throw new IOException("截图切换输出失败", error); }
    }

    private byte[] captureLegacy(final Source source) throws IOException {
        if (!busy.compareAndSet(false, true)) throw new IOException("正在截屏，请稍后再试");
        LegacyVideoFrame frame = null;
        Bitmap bitmap = null;
        Target target = null;
        try {
            target = onMain(() -> source.current());
            if (!(target.session instanceof tv.danmaku.ijk.media.player.IMediaPlayer))
                throw new IOException("当前播放器不支持截图");
            final Target current = target;
            frame = new LegacyVideoFrame(target.width, target.height);
            final android.view.Surface surface = frame.surface();
            onMain(() -> {
                if (source.current().session != current.session) throw new IOException("频道已切换，请重试");
                tv.danmaku.ijk.media.player.IMediaPlayer player =
                        (tv.danmaku.ijk.media.player.IMediaPlayer) current.session;
                player.setDisplay(null);
                player.setSurface(surface);
                return null;
            });
            bitmap = frame.read();
            onMain(() -> {
                if (source.current().session != current.session) throw new IOException("频道已切换，请重试");
                return null;
            });
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("截图图片生成失败");
            return out.toByteArray();
        } catch (OutOfMemoryError error) {
            throw new IOException("内存不足，暂时无法截图");
        } finally {
            final Target restore = target;
            try {
                if (restore != null) onMain(() -> {
                    Target active;
                    try { active = source.current(); } catch (IOException stopped) { return null; }
                    if (active.session == restore.session) {
                        tv.danmaku.ijk.media.player.IMediaPlayer player =
                                (tv.danmaku.ijk.media.player.IMediaPlayer) restore.session;
                        player.setSurface(null);
                        player.setDisplay(restore.view.getHolder());
                    }
                    return null;
                });
            } finally {
                if (bitmap != null) bitmap.recycle();
                if (frame != null) frame.close();
                busy.set(false);
            }
        }
    }

    @TargetApi(24)
    private void requestCopy(final Source source, final Target target, final Bitmap bitmap,
            final Capture request, Handler handler) {
        PixelCopy.request(target.view, bitmap, new PixelCopy.OnPixelCopyFinishedListener() {
            @Override public void onPixelCopyFinished(int result) {
                IOException failure = null;
                try {
                    if (result != PixelCopy.SUCCESS) {
                        throw new IOException("当前视频画面尚不可截取，请出画后重试（" + result + "）");
                    }
                    if (source.current().session != target.session) {
                        throw new IOException("截屏时频道已切换，请重试");
                    }
                } catch (Exception error) {
                    failure = new IOException(error.getMessage(), error);
                } finally {
                    if (failure != null) bitmap.recycle();
                    request.complete(failure == null ? bitmap : null, failure);
                    if (failure != null || request.isAbandoned()) busy.set(false);
                }
            }
        }, handler);
    }

    private static final class Capture {
        final CountDownLatch ready = new CountDownLatch(1);
        private Bitmap bitmap;
        private IOException error;
        private boolean abandoned;

        synchronized boolean isAbandoned() { return abandoned; }

        synchronized void complete(Bitmap image, IOException failure) {
            if (abandoned) {
                if (image != null) image.recycle();
            } else {
                bitmap = image;
                error = failure;
            }
            ready.countDown();
        }

        Bitmap await() throws IOException {
            boolean completed = false;
            try {
                completed = ready.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            synchronized (this) {
                if (!completed) {
                    abandoned = true;
                    if (bitmap != null) bitmap.recycle();
                    throw new IOException("截屏超时，请稍后重试");
                }
                if (error != null) throw error;
                if (bitmap == null) throw new IOException("未获取到视频画面");
                return bitmap;
            }
        }
    }

    static byte[] download(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(10000);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        try {
            int status = connection.getResponseCode();
            InputStream input = status == 200
                    ? connection.getInputStream() : connection.getErrorStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (input != null) {
                try {
                    byte[] buffer = new byte[16384];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (output.size() + count > MAX_BYTES) {
                            throw new IOException("截屏响应过大");
                        }
                        output.write(buffer, 0, count);
                    }
                } finally { input.close(); }
            }
            byte[] bytes = output.toByteArray();
            if (status != 200) {
                String message = "截屏失败：HTTP " + status;
                try { message = new JSONObject(new String(bytes, "UTF-8"))
                        .optString("message", message); } catch (Exception ignored) { }
                throw new IOException(message);
            }
            if (bytes.length < 8 || bytes[0] != (byte) 137 || bytes[1] != 80
                    || bytes[2] != 78 || bytes[3] != 71 || bytes[4] != 13
                    || bytes[5] != 10 || bytes[6] != 26 || bytes[7] != 10) {
                throw new IOException("设备未返回有效的 PNG 截屏，请更新设备端 APP");
            }
            return bytes;
        } finally { connection.disconnect(); }
    }
}
