package xiao.bu.tv;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaCodec;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-shot captures; legacy systems decode one cached segment only on demand. */
final class VideoScreenshot {
    static final String PATH = "/api/recording/screenshot";
    // Allow detailed full-resolution captures without silently downscaling them.
    private static final int MAX_BYTES = 64 * 1024 * 1024;
    private static final int PREVIEW_WIDTH = 640;
    private static final int PREVIEW_HEIGHT = 360;
    private static final int LEGACY_PREVIEW_WIDTH = 320;
    private static final int LEGACY_PREVIEW_HEIGHT = 180;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile LegacyImage cachedLegacy;

    private static final class LegacyImage {
        final WeakReference<Object> session;
        final WeakReference<byte[]> segment;
        final byte[] image;
        final byte[] preview;
        final long at = SystemClock.elapsedRealtime();
        LegacyImage(Target target, byte[] image, byte[] preview) {
            session = new WeakReference<Object>(target.session);
            segment = new WeakReference<byte[]>(target.segment);
            this.image = image;
            this.preview = preview;
        }
    }

    void clear() { cachedLegacy = null; }

    static boolean needsSegmentCache() {
        // Older Amlogic video planes can report PixelCopy.SUCCESS while exposing
        // an empty YUV buffer. Keep one existing TS body for on-demand fallback.
        return Build.VERSION.SDK_INT < 24 || (Build.VERSION.SDK_INT <= 28
                && "amlogic".equalsIgnoreCase(Build.HARDWARE));
    }

    static boolean isGreenPlaceholder(Bitmap bitmap) {
        int green = 0, low = 255, high = 0;
        // Sample cell centres: the broken plane can have a thin bright top edge.
        for (int y = 0; y < 18; y++) for (int x = 0; x < 32; x++) {
            int color = bitmap.getPixel((2 * x + 1) * bitmap.getWidth() / 64,
                    (2 * y + 1) * bitmap.getHeight() / 36);
            int r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
            if (r <= 2 && b <= 2 && g >= 130 && g <= 138) {
                green++; low = Math.min(low, g); high = Math.max(high, g);
            }
        }
        return green >= 565 && high - low <= 2;
    }

    interface Source {
        Target current() throws IOException;
    }

    static final class Target {
        final DirectVideoView view;
        final Object session;
        final int width;
        final int height;
        final byte[] segment;
        final File cacheDirectory;

        Target(DirectVideoView view, Object session, int width, int height) {
            this(view, session, width, height, null, null);
        }

        Target(DirectVideoView view, Object session, int width, int height,
                byte[] segment, File cacheDirectory) {
            this.view = view;
            this.session = session;
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
            this.segment = segment;
            this.cacheDirectory = cacheDirectory;
        }

        Target forPreview() {
            float scale = Math.min(1f, Math.min((float) PREVIEW_WIDTH / width,
                    (float) PREVIEW_HEIGHT / height));
            return new Target(view, session, Math.max(1, Math.round(width * scale)),
                    Math.max(1, Math.round(height * scale)));
        }
    }

    byte[] capture(final Source source) throws IOException {
        return capture(source, false);
    }

    byte[] capturePreview(final Source source) throws IOException {
        return capture(source, true);
    }

    private byte[] capture(final Source source, final boolean preview) throws IOException {
        acquire(preview);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            try { return captureLegacy(source, preview); }
            finally { busy.set(false); }
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
                    if (preview) target = target.forPreview();
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
            if (needsSegmentCache() && isGreenPlaceholder(bitmap)) {
                bitmap.recycle(); bitmap = null;
                // Decode a cached keyframe without changing the playing decoder
                // or fetching the stream again. Genuine green content stays valid.
                return captureLegacy(source, preview);
            }
            return encode(bitmap, preview);
        } catch (OutOfMemoryError error) {
            throw new IOException("内存不足，暂时无法保存截屏");
        } finally {
            if (bitmap != null) bitmap.recycle();
            busy.set(false);
        }
    }

    private Target currentOnMain(final Source source) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) return source.current();
        final Target[] target = new Target[1];
        final IOException[] error = new IOException[1];
        final CountDownLatch ready = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try { target[0] = source.current(); }
                catch (Exception failure) { error[0] = new IOException(failure.getMessage(), failure); }
                finally { ready.countDown(); }
            }
        });
        try {
            if (!ready.await(3, TimeUnit.SECONDS)) throw new IOException("获取截图状态超时");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("截图已取消", failure);
        }
        if (error[0] != null) throw error[0];
        return target[0];
    }

    private byte[] captureLegacy(Source source, boolean preview) throws IOException {
        Target target = currentOnMain(source);
        LegacyImage cached = cachedLegacy;
        if (cached != null && cached.session.get() != target.session) {
            cachedLegacy = null; cached = null;
        }
        if (cached != null && preview && cached.preview != null
                && (cached.segment.get() == target.segment
                || SystemClock.elapsedRealtime() - cached.at < 3000L)) return cached.preview;
        if (cached != null && !preview && cached.image != null
                && (cached.segment.get() == target.segment
                || SystemClock.elapsedRealtime() - cached.at < 3000L)) return cached.image;
        if (target.segment == null || target.cacheDirectory == null)
            throw new IOException("当前视频没有可抽帧的缓存分片，请稍后重试");
        File file = null;
        File remuxed = null;
        Bitmap bitmap = null;
        MediaMetadataRetriever retriever = null;
        long begin = SystemClock.elapsedRealtime(), written, muxed, decoded;
        try {
            remuxed = File.createTempFile("video-frame-", ".mp4", target.cacheDirectory);
            boolean avcFrame = AvcFrameMp4.write(target.segment, remuxed, target.width, target.height);
            written = SystemClock.elapsedRealtime();
            if (!avcFrame) {
                file = File.createTempFile("video-frame-", ".ts", target.cacheDirectory);
                FileOutputStream output = new FileOutputStream(file);
                try { output.write(target.segment); } finally { output.close(); }
                if (Build.VERSION.SDK_INT >= 18) SyncFrameMuxer.write(file, remuxed);
                else { remuxed.delete(); remuxed = null; }
            }
            muxed = SystemClock.elapsedRealtime();
            String path = (remuxed == null ? file : remuxed).getAbsolutePath();
            if (avcFrame) bitmap = NativeVideoFrame.capture(path,
                    LEGACY_PREVIEW_WIDTH, LEGACY_PREVIEW_HEIGHT, preview);
            boolean nativeFrame = bitmap != null;
            if (bitmap == null) {
                retriever = new MediaMetadataRetriever();
                retriever.setDataSource(path);
                // A sync frame avoids decoding a whole GOP for an arbitrary timestamp.
                bitmap = retriever.getFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            decoded = SystemClock.elapsedRealtime();
            if (bitmap == null) throw new IOException("系统无法从当前视频分片抽取画面");
            byte[] image = preview ? null : encode(bitmap, false);
            float scale = Math.min(1f, Math.min((float) LEGACY_PREVIEW_WIDTH / bitmap.getWidth(),
                    (float) LEGACY_PREVIEW_HEIGHT / bitmap.getHeight()));
            if (scale < 1f) {
                Bitmap small = Bitmap.createScaledBitmap(bitmap,
                        Math.max(1, Math.round(bitmap.getWidth() * scale)),
                        Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
                bitmap.recycle(); bitmap = small;
            }
            byte[] smallImage = encode(bitmap, true);
            android.util.Log.i("VideoScreenshot", "legacy preview=" + preview + " native=" + nativeFrame
                    + " writeMs=" + (written - begin)
                    + " muxMs=" + (muxed - written) + " decodeMs=" + (decoded - muxed)
                    + " encodeMs=" + (SystemClock.elapsedRealtime() - decoded));
            if (currentOnMain(source).session != target.session)
                throw new IOException("截屏时频道已切换，请重试");
            cachedLegacy = new LegacyImage(target, image, smallImage);
            return preview ? smallImage : image;
        } catch (RuntimeException failure) {
            throw new IOException("系统无法抽取当前视频画面", failure);
        } catch (OutOfMemoryError failure) {
            throw new IOException("内存不足，暂时无法截屏");
        } finally {
            if (bitmap != null) bitmap.recycle();
            if (retriever != null) try { retriever.release(); } catch (RuntimeException ignored) { }
            if (file != null) file.delete();
            if (remuxed != null) remuxed.delete();
        }
    }

    @TargetApi(18)
    private static final class SyncFrameMuxer {
        static void write(File input, File output) throws IOException {
            MediaExtractor extractor = new MediaExtractor();
            MediaMuxer muxer = null;
            boolean started = false;
            try {
                extractor.setDataSource(input.getAbsolutePath());
                int video = -1;
                MediaFormat format = null;
                for (int track = 0; track < extractor.getTrackCount(); track++) {
                    MediaFormat candidate = extractor.getTrackFormat(track);
                    String mime = candidate.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("video/")) {
                        video = track; format = candidate; break;
                    }
                }
                if (video < 0) throw new IOException("缓存分片没有可抽取的视频轨道");
                extractor.selectTrack(video);
                muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                int track = muxer.addTrack(format);
                muxer.start(); started = true;
                // One compressed key frame, without decoding audio or intervening frames.
                ByteBuffer data = ByteBuffer.allocate(Math.min(8 * 1024 * 1024, (int) input.length()));
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                for (int sample = 0; sample < 300; sample++) {
                    int size = extractor.readSampleData(data, 0);
                    if (size < 0) break;
                    // Android 4.4's TS extractor can omit SAMPLE_FLAG_SYNC even for
                    // an IDR. Inspect compressed NAL headers, without decoding frames.
                    if ((extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                            || hasSyncNal(data, size, format.getString(MediaFormat.KEY_MIME))) {
                        info.set(0, size, 0L, MediaCodec.BUFFER_FLAG_SYNC_FRAME);
                        muxer.writeSampleData(track, data, info);
                        // Old muxers need a non-zero sample duration. Repeating the
                        // compressed frame establishes it without another decode.
                        data.position(0);
                        info.presentationTimeUs = 40000L;
                        muxer.writeSampleData(track, data, info);
                        muxer.stop(); started = false;
                        return;
                    }
                    if (!extractor.advance()) break;
                }
                throw new IOException("缓存分片尚无关键帧，请稍后重试");
            } finally {
                extractor.release();
                if (muxer != null) {
                    if (started) try { muxer.stop(); } catch (RuntimeException ignored) { }
                    try { muxer.release(); } catch (RuntimeException ignored) { }
                }
            }
        }

        private static boolean hasSyncNal(ByteBuffer data, int size, String mime) {
            if (!"video/avc".equals(mime) && !"video/hevc".equals(mime)) return false;
            for (int at = 0; at + 3 < size; at++) {
                if (data.get(at) == 0 && data.get(at + 1) == 0 && data.get(at + 2) == 1) {
                    int header = data.get(at + 3) & 255;
                    int type = "video/avc".equals(mime) ? header & 31 : (header >> 1) & 63;
                    if ("video/avc".equals(mime) ? type == 5 : type >= 16 && type <= 21) return true;
                }
            }
            return false;
        }
    }

    private void acquire(boolean preview) throws IOException {
        if (busy.compareAndSet(false, true)) return;
        // The explicit screenshot may arrive just after the controller's tiny preview.
        // Give that one-shot preview a moment to finish rather than rejecting the tap.
        long deadline = SystemClock.elapsedRealtime() + (preview ? 0L : 1500L);
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(40L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("截图已取消", error);
            }
            if (busy.compareAndSet(false, true)) return;
        }
        throw new IOException("正在截屏，请稍后再试");
    }

    private static byte[] encode(Bitmap bitmap, boolean preview) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, preview ? 78 : 95, output)) {
            throw new IOException(preview ? "预览图片生成失败" : "截图图片生成失败");
        }
        return output.toByteArray();
    }

    @TargetApi(24)
    private void requestCopy(final Source source, final Target target, final Bitmap bitmap,
            final Capture request, Handler handler) {
        PixelCopy.request(target.view.getSurfaceView(), bitmap, new PixelCopy.OnPixelCopyFinishedListener() {
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
                completed = ready.await(20, TimeUnit.SECONDS);
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
        connection.setReadTimeout(60000);
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
            boolean jpeg = bytes.length >= 4 && bytes[0] == (byte) 0xff
                    && bytes[1] == (byte) 0xd8
                    && bytes[bytes.length - 2] == (byte) 0xff
                    && bytes[bytes.length - 1] == (byte) 0xd9;
            boolean png = bytes.length >= 8 && bytes[0] == (byte) 137
                    && bytes[1] == 80 && bytes[2] == 78 && bytes[3] == 71
                    && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26
                    && bytes[7] == 10;
            if (!jpeg && !png) {
                throw new IOException("设备未返回有效的 JPEG/PNG 截屏，请更新设备端 APP");
            }
            return bytes;
        } finally { connection.disconnect(); }
    }
}
