package xiao.bu.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.Choreographer;
import android.view.Surface;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.util.Enumeration;
import java.util.Locale;

/** Owns the main TV screen -> MediaCodec -> RTSP cast pipeline. */
final class WebViewCastManager implements Closeable {
    private static final String TAG = "WebViewCastManager";
    private static final long CODEC_TIMEOUT_US = 10000L;
    private static final int AUDIO_SAMPLE_RATE = 48000;
    private static final int AUDIO_CHANNELS = 2;
    private static final int AUDIO_FRAME_SAMPLES = 1024;
    private static final int AUDIO_PCM_BYTES_PER_FRAME = AUDIO_CHANNELS * 2;
    private static final int AUDIO_INPUT_BYTES = AUDIO_FRAME_SAMPLES * AUDIO_PCM_BYTES_PER_FRAME;
    private static final int AUDIO_BITRATE = 160000;
    // New viewers and packet-loss recovery request an IDR immediately below. A
    // one-second periodic IDR creates a large, visible encoder spike on animated
    // webpages, so retain only a sparse fallback for vendor codecs that reject
    // PARAMETER_KEY_REQUEST_SYNC_FRAME.
    private static final int PERIODIC_SYNC_FRAME_SECONDS = 5;
    private static volatile Boolean hevcEncodingSupported;

    private final Activity activity;
    private final Object lifecycleLock = new Object();
    private final CastNetworkLease networkLease = new CastNetworkLease();
    private volatile boolean running;
    private volatile String status = "未投送";
    private volatile String error = "";
    private volatile boolean audioActive;
    private volatile long encodedVideoFrames;
    private volatile long renderedUiFrames;
    private volatile long lastVideoPresentationTimeUs;
    private CastConfig config;
    private RtspCastServer rtspServer;
    private volatile MediaCodec videoEncoder;
    private Surface encoderSurface;
    private CastGlCompositor compositor;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable uiRenderTick;
    private Choreographer.FrameCallback uiFrameCallback;
    private Thread videoDrainThread;
    private Thread videoSendThread;
    private volatile CastVideoQueue videoQueue;
    private volatile long lastVideoSendUs;
    private volatile long peakVideoSendUs;
    private volatile CastBitrateController bitrateController;
    private volatile long encodedVideoBytes;
    private volatile double encodeDelayMs = -1d;
    private volatile String compatibilityNote = "";
    private volatile String advertisedAddress = "";
    private final MediaBitrateEstimator rtspVideoBitrate = new MediaBitrateEstimator();
    private final MediaBitrateEstimator rtspAudioBitrate = new MediaBitrateEstimator();
    private volatile long measuredRtspVideoBitrate = -1L;
    private volatile long measuredRtspAudioBitrate = -1L;
    private AudioCapture audioCapture;
    private MediaProjection mediaProjection;
    private MediaProjection.Callback projectionCallback;

    WebViewCastManager(Activity activity) {
        this.activity = activity;
    }

    void start(CastConfig requested, MediaProjection mediaProjection) throws IOException {
        start(requested, mediaProjection, true);
    }

    void start(CastConfig requested, MediaProjection mediaProjection,
            boolean includeVideoLayer) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            throw new IOException("网页投送仅支持 Android 5.0 及以上");
        }
        stop();
        config = requested;
        error = "";
        compatibilityNote = "";
        status = "正在启动";
        encodedVideoFrames = 0L;
        renderedUiFrames = 0L;
        lastVideoPresentationTimeUs = 0L;
        lastVideoSendUs = 0L;
        peakVideoSendUs = 0L;
        encodedVideoBytes = 0L;
        encodeDelayMs = -1d;
        rtspVideoBitrate.reset();
        rtspAudioBitrate.reset();
        measuredRtspVideoBitrate = -1L;
        measuredRtspAudioBitrate = -1L;
        boolean useAudio = requested.audio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && mediaProjection != null;
        try {
            if (useAudio) {
                this.mediaProjection = mediaProjection;
                registerProjectionCallback(mediaProjection);
            }
            networkLease.acquire(activity);
            CastConfig active = configureVideoEncoder(requested);
            config = active;
            bitrateController = new CastBitrateController(active.bitrate, active.fps);
            rtspServer = new RtspCastServer(
                    useAudio, active.bitrate, active.codec, active.fps);
            rtspServer.start();
            running = true;
            startVideoDrain();
            createGlCompositor(active, includeVideoLayer);
            if (useAudio) {
                startAudio(mediaProjection);
            }
            status = useAudio ? "正在投送电视界面和声音" : requested.audio
                    ? "正在投送电视界面；声音捕获尚未授权" : "正在投送电视界面";
        } catch (Exception failure) {
            error = message(failure);
            status = "启动失败";
            stop();
            throw failure instanceof IOException ? (IOException) failure
                    : new IOException(error, failure);
        }
    }

    private CastConfig configureVideoEncoder(CastConfig requested) throws IOException {
        String[] codecs = CastConfig.CODEC_H265.equals(requested.codec)
                ? new String[] { CastConfig.CODEC_H265, CastConfig.CODEC_H264 }
                : new String[] { CastConfig.CODEC_H264 };
        int[] rates = requested.fps >= 120 ? new int[] { 120, 60, 30, 25 }
                : requested.fps >= 60 ? new int[] { 60, 30, 25 }
                : requested.fps >= 30 ? new int[] { 30, 25 } : new int[] { 25 };
        Throwable lastFailure = null;
        for (String codec : codecs) {
            for (int fps : rates) {
                CastConfig candidate = requested.withVideo(codec, fps);
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        createVideoEncoder(videoEncoderFormat(candidate, attempt == 0),
                                candidate.videoMimeType());
                        if (!codec.equals(requested.codec) || fps != requested.fps) {
                            compatibilityNote = "设备能力回退为 "
                                    + (CastConfig.CODEC_H265.equals(codec) ? "H.265" : "H.264")
                                    + " · " + fps + " fps";
                            Log.w(TAG, compatibilityNote);
                        }
                        return candidate;
                    } catch (IOException failure) {
                        lastFailure = failure;
                        releaseVideoEncoder();
                    } catch (IllegalArgumentException failure) {
                        lastFailure = failure;
                        releaseVideoEncoder();
                    } catch (IllegalStateException failure) {
                        lastFailure = failure;
                        releaseVideoEncoder();
                    }
                }
            }
        }
        throw new IOException("设备没有可用的投屏视频编码器", lastFailure);
    }

    private MediaFormat videoEncoderFormat(CastConfig value, boolean lowLatency) {
        MediaFormat format = MediaFormat.createVideoFormat(
                value.videoMimeType(), value.width, value.height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, value.bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, value.fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, PERIODIC_SYNC_FRAME_SECONDS);
        if (lowLatency && Build.VERSION.SDK_INT >= 26) format.setInteger("latency", 0);
        if (lowLatency && Build.VERSION.SDK_INT >= 29) format.setInteger("max-bframes", 0);
        if (CastConfig.CODEC_H264.equals(value.codec) && value.width <= 1280
                && value.height <= 720 && (lowLatency || value.fps <= 30)) {
            format.setInteger(MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            format.setInteger(MediaFormat.KEY_LEVEL,
                    value.fps <= 30 ? MediaCodecInfo.CodecProfileLevel.AVCLevel31
                            : MediaCodecInfo.CodecProfileLevel.AVCLevel32);
        }
        format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        }
        return format;
    }

    private void createVideoEncoder(MediaFormat format, String mimeType) throws IOException {
        videoEncoder = MediaCodec.createEncoderByType(mimeType);
        videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoderSurface = videoEncoder.createInputSurface();
        videoEncoder.start();
    }

    private void releaseVideoEncoder() {
        if (encoderSurface != null) {
            try { encoderSurface.release(); } catch (RuntimeException ignored) { }
            encoderSurface = null;
        }
        if (videoEncoder != null) {
            try { videoEncoder.release(); } catch (RuntimeException ignored) { }
            videoEncoder = null;
        }
    }

    static boolean supportsHevcEncoding() {
        Boolean cached = hevcEncodingSupported;
        if (cached != null) return cached;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false;
        try {
            int count = MediaCodecList.getCodecCount();
            for (int index = 0; index < count; index++) {
                MediaCodecInfo info = MediaCodecList.getCodecInfoAt(index);
                if (info == null || !info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (!"video/hevc".equalsIgnoreCase(type)) continue;
                    MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                    for (int color : caps.colorFormats) {
                        if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) {
                            hevcEncodingSupported = true;
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable failure) {
            Log.w(TAG, "Unable to enumerate HEVC encoders", failure);
        }
        hevcEncodingSupported = false;
        return false;
    }

    private void createGlCompositor(final CastConfig value, boolean includeVideoLayer)
            throws IOException {
        compositor = new CastGlCompositor(encoderSurface, value.width, value.height,
                value.fps, includeVideoLayer);
        final long intervalNs = 1000000000L / Math.max(1, value.fps);
        final PowerManager power = (PowerManager) activity.getSystemService(
                Activity.POWER_SERVICE);
        final long[] lastVsyncNs = new long[] { 0L };
        final long[] nextFrameNs = new long[] { 0L };

        // Capture on the display clock while it is running. WebView's
        // requestAnimationFrame uses the same clock, so this avoids the slow
        // phase drift where an independent Handler periodically lands in the
        // middle of WebView rendering. The deadline accumulator intentionally
        // drops missed frames instead of issuing a catch-up burst.
        uiFrameCallback = new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                if (!running || compositor == null || uiFrameCallback != this) return;
                lastVsyncNs[0] = frameTimeNanos;
                if (nextFrameNs[0] == 0L || frameTimeNanos >= nextFrameNs[0]) {
                    renderUiFrame(value.width, value.height);
                    long following = nextFrameNs[0] == 0L
                            ? frameTimeNanos + intervalNs : nextFrameNs[0] + intervalNs;
                    nextFrameNs[0] = following <= frameTimeNanos
                            ? frameTimeNanos + intervalNs : following;
                }
                Choreographer.getInstance().postFrameCallback(this);
            }
        };
        uiRenderTick = new Runnable() {
            @Override
            public void run() {
                if (!running || compositor == null || uiRenderTick != this) return;
                Throwable compositorFailure = compositor.failure();
                if (compositorFailure != null) {
                    failAsync("GPU 投送合成器异常", compositorFailure);
                    return;
                }
                long nowNs = System.nanoTime();
                boolean interactive = power == null || Build.VERSION.SDK_INT < 20
                        || power.isInteractive();
                // Choreographer pauses when the display sleeps. Keep the encoder
                // and RTSP session alive from the monotonic fallback clock, but do
                // not race it while display VSYNC is healthy.
                if (!interactive || lastVsyncNs[0] == 0L
                        || nowNs - lastVsyncNs[0] > Math.max(50_000_000L,
                                intervalNs * 3L)) {
                    if (nextFrameNs[0] == 0L || nowNs >= nextFrameNs[0]) {
                        renderUiFrame(value.width, value.height);
                        nextFrameNs[0] = nowNs + intervalNs;
                    }
                }
                uiHandler.postDelayed(this, Math.max(1L,
                        (intervalNs + 999_999L) / 1_000_000L));
            }
        };
        Choreographer.getInstance().postFrameCallback(uiFrameCallback);
        uiHandler.post(uiRenderTick);
    }

    @SuppressLint("NewApi")
    private void renderUiFrame(int width, int height) {
        long drawBeganNs = System.nanoTime();
        CastGlCompositor active = compositor;
        Surface surface = active == null ? null : active.uiSurface();
        if (surface == null || !surface.isValid() || !active.tryAcquireUiFrame()) {
            return;
        }
        Canvas canvas = null;
        boolean submitted = false;
        try {
            if (activity instanceof MainActivity) {
                // Include the newest coalesced mouse coordinate in this exact
                // encoder frame instead of leaving it behind the capture task.
                ((MainActivity) activity).prepareCastUiFrame();
            }
            canvas = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? surface.lockHardwareCanvas() : surface.lockCanvas(null);
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).drawCastUi(canvas, width, height);
            }
            renderedUiFrames++;
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to draw cast UI", error);
        } finally {
            if (canvas != null) {
                try {
                    surface.unlockCanvasAndPost(canvas);
                    submitted = true;
                    if (BuildConfig.DEBUG && BuildConfig.CAST_LATENCY_TRACE)
                        Log.i("NtvCastLatency", "UI begin=" + drawBeganNs / 1000L
                                + " end=" + System.nanoTime() / 1000L);
                } catch (RuntimeException error) {
                    Log.w(TAG, "Unable to submit cast UI", error);
                }
            }
            if (!submitted) active.releaseUiFrame();
        }
    }

    Surface videoInputSurface() {
        CastGlCompositor active = compositor;
        return active == null ? null : active.videoSurface();
    }

    void setVideoSize(int width, int height, int sarNum, int sarDen) {
        CastGlCompositor active = compositor;
        if (active != null) {
            active.setVideoSize(width, height, sarNum, sarDen);
        }
    }

    private void registerProjectionCallback(final MediaProjection projection) {
        projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                if (!running || mediaProjection != projection) {
                    return;
                }
                mediaProjection = null;
                projectionCallback = null;
                failAsync("系统已停止屏幕捕获", new IOException("捕获授权已失效"));
            }
        };
        projection.registerCallback(projectionCallback, new Handler(Looper.getMainLooper()));
    }

    private void startVideoDrain() {
        final MediaCodec encoder = videoEncoder;
        final RtspCastServer server = rtspServer;
        final CastVideoQueue queue = new CastVideoQueue();
        final CastBitrateController bitrate = bitrateController;
        videoQueue = queue;
        videoSendThread = new Thread(new Runnable() {
            @Override public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
                try {
                    while (!queue.isClosed()) {
                        CastVideoQueue.Frame frame = queue.take();
                        if (frame == null) continue;
                        long begin = System.nanoTime();
                        boolean connected = server.hasVideoClient();
                        server.sendVideo(frame.data, frame.ptsUs, frame.flags);
                        lastVideoSendUs = (System.nanoTime() - begin) / 1000L;
                        peakVideoSendUs = Math.max(peakVideoSendUs, lastVideoSendUs);
                        if (connected) bitrate.recordSend(lastVideoSendUs * 1000L);
                        if (BuildConfig.DEBUG && BuildConfig.CAST_LATENCY_TRACE)
                            Log.i("NtvCastLatency", "SEND pts=" + frame.ptsUs
                                    + " begin=" + begin / 1000L + " end=" + System.nanoTime() / 1000L);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "webview-cast-send");
        videoSendThread.start();
        videoDrainThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
                drainVideo(encoder, server, queue, bitrate);
            }
        }, "webview-cast-video");
        videoDrainThread.start();
    }

    private void drainVideo(MediaCodec encoder, RtspCastServer server, CastVideoQueue queue,
            CastBitrateController bitrate) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long lastSyncRequestNs = 0;
        while (running && videoEncoder == encoder && !queue.isClosed()) {
            try {
                long now = System.nanoTime();
                int previous = bitrate.bitrate();
                int target = bitrate.update(now, queue.droppedFrames(),
                        server.slowWriteDisconnects(), server.hasVideoClient(),
                        server.pendingVideoWriteNs());
                if (target != 0) {
                    android.os.Bundle parameters = new android.os.Bundle();
                    parameters.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, target);
                    try {
                        encoder.setParameters(parameters);
                        Log.i(TAG, "Cast bitrate target " + previous + " -> " + target);
                    } catch (IllegalArgumentException unsupported) {
                        bitrate.reject(previous);
                        Log.w(TAG, "Encoder does not support adaptive bitrate", unsupported);
                    } catch (IllegalStateException unsupported) {
                        bitrate.reject(previous);
                        Log.w(TAG, "Encoder rejected adaptive bitrate", unsupported);
                    }
                }
                if ((queue.needsSyncFrame() || server.needsSyncFrame())
                        && now - lastSyncRequestNs > 100_000_000L) {
                    android.os.Bundle parameters = new android.os.Bundle();
                    parameters.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                    try { encoder.setParameters(parameters); }
                    catch (IllegalArgumentException unsupported) {
                        // A sparse periodic IDR remains the fallback on old codecs.
                        Log.w(TAG, "Encoder does not support on-demand sync frame", unsupported);
                    }
                    lastSyncRequestNs = now;
                }
                int index = encoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat format = encoder.getOutputFormat();
                    server.setVideoConfig(format.getByteBuffer("csd-0"),
                            format.getByteBuffer("csd-1"), format.getByteBuffer("csd-2"));
                } else if (index >= 0) {
                    try {
                        ByteBuffer output = encoder.getOutputBuffer(index);
                        if (output != null && info.size > 0
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            double sampleDelayMs = (System.nanoTime() / 1000L
                                    - info.presentationTimeUs) / 1000d;
                            if (sampleDelayMs >= 0d && sampleDelayMs < 5000d) {
                                encodeDelayMs = encodeDelayMs < 0d ? sampleDelayMs
                                        : encodeDelayMs * 0.8d + sampleDelayMs * 0.2d;
                            }
                            if (BuildConfig.DEBUG && BuildConfig.CAST_LATENCY_TRACE)
                                Log.i("NtvCastLatency", "ENCODE pts=" + info.presentationTimeUs
                                        + " end=" + System.nanoTime() / 1000L);
                            ByteBuffer copy = output.duplicate();
                            copy.position(info.offset); copy.limit(info.offset + info.size);
                            byte[] data = new byte[info.size]; copy.get(data);
                            queue.offer(new CastVideoQueue.Frame(data, info.presentationTimeUs,
                                    info.flags, (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0,
                                    System.nanoTime()));
                            encodedVideoFrames++;
                            encodedVideoBytes += info.size;
                            lastVideoPresentationTimeUs = info.presentationTimeUs;
                        }
                    } finally {
                        // Never retain a codec output buffer while the network is blocked.
                        encoder.releaseOutputBuffer(index, false);
                    }
                }
            } catch (IllegalStateException failure) {
                if (running && videoEncoder == encoder && !queue.isClosed()) {
                    failAsync("视频编码器异常", failure, encoder);
                }
                break;
            }
        }
    }

    @SuppressLint("NewApi")
    private void startAudio(MediaProjection projection) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        audioCapture = new AudioCapture(projection);
        audioCapture.start();
        audioActive = true;
    }

    JSONObject stateJson() {
        JSONObject value = new JSONObject();
        try {
            value.put("running", running)
                    .put("status", status)
                    .put("error", error)
                    .put("audioActive", audioActive)
                    .put("droppedPcmFrames", audioCapture == null
                            ? 0 : audioCapture.droppedPcmFrames())
                    .put("droppedAacFrames", audioCapture == null
                            ? 0 : audioCapture.droppedAacFrames())
                    .put("audioSupported", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    .put("h265Supported", supportsHevcEncoding())
                    .put("captureMode", "surface-video+system-app-audio")
                    .put("compatibilityNote", compatibilityNote)
                    .put("encodedVideoFrames", encodedVideoFrames)
                    .put("encodedVideoBytes", encodedVideoBytes)
                    .put("renderedUiFrames", renderedUiFrames)
                    .put("lastVideoPresentationTimeUs", lastVideoPresentationTimeUs)
                    .put("encodeDelayMs", encodeDelayMs < 0d
                            ? -1L : Math.round(encodeDelayMs))
                    .put("lastVideoSendMs", lastVideoSendUs / 1000d)
                    .put("peakVideoSendMs", peakVideoSendUs / 1000d)
                    .put("queuedVideoFrames", videoQueue == null ? 0 : videoQueue.size())
                    .put("droppedVideoFrames", videoQueue == null ? 0 : videoQueue.droppedFrames())
                    .put("rtspUrl", rtspUrl());
            CastBitrateController bitrate = bitrateController;
            RtspCastServer server = rtspServer;
            if (server != null) {
                long now = SystemClock.elapsedRealtime();
                long videoSample = rtspVideoBitrate.sampleCumulativeBytes(
                        server.sentVideoBytes(), now);
                long audioSample = rtspAudioBitrate.sampleCumulativeBytes(
                        server.sentAudioBytes(), now);
                if (videoSample >= 0L) measuredRtspVideoBitrate = videoSample;
                if (audioSample >= 0L) measuredRtspAudioBitrate = audioSample;
            }
            value.put("encoderTargetBitrateMbps", bitrate == null ? 0 : bitrate.bitrate() / 1000000d)
                    .put("socketSendBufferBytes", server == null ? 0 : server.socketSendBufferBytes())
                    .put("slowWriteDisconnects", server == null ? 0 : server.slowWriteDisconnects())
                    .put("rtspVideoBitrate", Math.max(0L, measuredRtspVideoBitrate))
                    .put("rtspAudioBitrate", Math.max(0L, measuredRtspAudioBitrate));
            CastConfig current = config;
            if (current != null) {
                value.put("width", current.width)
                        .put("height", current.height)
                        .put("fps", current.fps)
                        .put("codec", current.codec)
                        .put("bitrateMbps", current.bitrate / 1000000)
                        .put("audioRequested", current.audio);
            }
        } catch (JSONException ignored) {
        }
        return value;
    }

    String rtspUrl() {
        RtspCastServer server = rtspServer;
        String address = advertisedAddress;
        if (address.length() == 0) address = findLanAddress();
        return server == null || address == null ? ""
                : "rtsp://" + address + ":" + server.port() + "/cast";
    }

    void setAdvertisedAddress(String address) {
        advertisedAddress = address == null ? "" : address.trim();
    }

    long encodeDelayMs() {
        return encodeDelayMs < 0d ? -1L : Math.round(encodeDelayMs);
    }

    boolean isRunning() {
        return running;
    }

    boolean isReady() {
        return running && encodedVideoFrames > 0L && rtspUrl().length() > 0;
    }

    int outputWidth() {
        CastConfig current = config;
        return current == null ? 1280 : current.width;
    }

    int outputHeight() {
        CastConfig current = config;
        return current == null ? 720 : current.height;
    }

    int outputFps() {
        CastConfig current = config;
        return current == null ? CastConfig.DEFAULT_FPS : current.fps;
    }

    private void failAsync(String prefix, Throwable failure) {
        failAsync(prefix, failure, videoEncoder);
    }

    private void failAsync(final String prefix, final Throwable failure,
            final MediaCodec failedEncoder) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // A late failure from a retired session must not stop the new
                // encoder after a rapid channel reselection.
                if (!running || videoEncoder != failedEncoder) return;
                error = prefix + "：" + message(failure);
                status = "投送异常";
                Log.e(TAG, error, failure);
                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).handleWebViewCastFailure();
                } else {
                    stop();
                }
            }
        });
    }

    void stop() {
        synchronized (lifecycleLock) {
            running = false;
            networkLease.release();
            audioActive = false;
            // Unblock writers before waiting for producers or releasing their codecs.
            if (videoQueue != null) videoQueue.close();
            if (rtspServer != null) rtspServer.close();
            joinWorker(videoDrainThread);
            joinWorker(videoSendThread);
            videoDrainThread = videoSendThread = null;
            if (audioCapture != null) {
                audioCapture.close();
                audioCapture = null;
            }
            if (uiRenderTick != null) {
                uiHandler.removeCallbacks(uiRenderTick);
                uiRenderTick = null;
            }
            if (uiFrameCallback != null) {
                Choreographer.getInstance().removeFrameCallback(uiFrameCallback);
                uiFrameCallback = null;
            }
            if (compositor != null) {
                compositor.close();
                compositor = null;
            }
            MediaProjection oldProjection = mediaProjection;
            MediaProjection.Callback oldCallback = projectionCallback;
            mediaProjection = null;
            projectionCallback = null;
            if (oldProjection != null) {
                try {
                    if (oldCallback != null) {
                        oldProjection.unregisterCallback(oldCallback);
                    }
                    // MainActivity owns the consent token across channel changes.
                } catch (RuntimeException ignored) {
                }
            }
            if (videoEncoder != null) {
                try {
                    videoEncoder.stop();
                } catch (RuntimeException ignored) {
                }
                videoEncoder.release();
                videoEncoder = null;
            }
            if (encoderSurface != null) {
                encoderSurface.release();
                encoderSurface = null;
            }
            if (rtspServer != null) {
                rtspServer.close();
                rtspServer = null;
            }
            if (!"启动失败".equals(status) && !"投送异常".equals(status)) {
                status = "未投送";
            }
        }
    }

    @Override
    public void close() {
        stop();
    }

    private static void joinWorker(Thread worker) {
        if (worker == null || worker == Thread.currentThread()) return;
        try { worker.join(500L); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private static String findLanAddress() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) {
                        continue;
                    }
                    String text = address.getHostAddress();
                    if (text.startsWith("192.168.")) {
                        return text;
                    }
                    if (fallback == null && (text.startsWith("10.")
                            || text.startsWith("172."))) {
                        fallback = text;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static String message(Throwable failure) {
        String value = failure == null ? null : failure.getMessage();
        return TextUtils.isEmpty(value) ? "未知错误" : value;
    }

    private final class AudioCapture implements Closeable {
        private final RtspCastServer server = rtspServer;
        private final MediaCodec sessionVideoEncoder = videoEncoder;
        private final MediaCodec encoder;
        private final AudioRecord record;
        private volatile boolean active = true;
        private Thread recordThread;
        private Thread inputThread;
        private Thread drainThread;
        private Thread sendThread;
        private final CastPcmQueue pcmQueue = new CastPcmQueue(AUDIO_INPUT_BYTES);
        private final CastAudioQueue queue = new CastAudioQueue();
        private long capturedSamples;

        long droppedPcmFrames() {
            return pcmQueue.droppedFrames();
        }

        long droppedAacFrames() {
            return queue.droppedFrames();
        }

        @SuppressLint("NewApi")
        AudioCapture(MediaProjection projection) throws IOException {
            AudioPlaybackCaptureConfiguration capture =
                    new AudioPlaybackCaptureConfiguration.Builder(projection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .addMatchingUid(Process.myUid())
                            .build();
            int minimum = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) {
                throw new IOException("设备不支持 48kHz 双声道捕获");
            }
            AudioFormat audioFormat = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AUDIO_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            record = new AudioRecord.Builder()
                    .setAudioFormat(audioFormat)
                    // Keep only the platform-required buffer. Doubling it added a
                    // full extra capture window before AAC could see the samples.
                    .setBufferSizeInBytes(Math.max(minimum, AUDIO_INPUT_BYTES))
                    .setAudioPlaybackCaptureConfig(capture)
                    .build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                record.release();
                throw new IOException("系统声音捕获初始化失败");
            }
            MediaFormat format = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_INPUT_BYTES);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0);
                format.setFloat(MediaFormat.KEY_OPERATING_RATE, (float) AUDIO_SAMPLE_RATE);
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        }

        void start() {
            encoder.start();
            recordThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                    recordLoop();
                }
            }, "webview-cast-audio-record");
            inputThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                    inputLoop();
                }
            }, "webview-cast-audio-input");
            drainThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    // Capturing must meet AudioRecord's deadline. AAC draining only
                    // has to keep pace with 21 ms access units and must not starve
                    // the UI/compositor on small CPUs.
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                    drainLoop();
                }
            }, "webview-cast-audio-codec");
            sendThread = new Thread(new Runnable() {
                @Override public void run() {
                    // The queue is bounded to two AAC frames. Let the display/video
                    // sender win CPU and the shared RTSP socket during contention.
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                    try {
                        while (active && running) {
                            CastAudioQueue.Frame frame = queue.take();
                            if (frame == null) break;
                            server.sendAudio(frame.data, frame.ptsUs);
                        }
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, "webview-cast-audio-send");
            // Have both consumers waiting before capture begins, so the first AAC
            // frame is drained and sent immediately instead of sitting in a codec queue.
            sendThread.start();
            drainThread.start();
            inputThread.start();
            record.startRecording();
            recordThread.start();
        }

        @SuppressLint("NewApi")
        private void recordLoop() {
            while (active && running) {
                byte[] pcm = pcmQueue.obtain();
                try {
                    // One AAC-LC frame (~21 ms), not an entire large codec buffer.
                    int count = record.read(pcm, 0, AUDIO_INPUT_BYTES,
                            AudioRecord.READ_BLOCKING);
                    if (count <= 0) {
                        pcmQueue.recycle(pcm);
                        continue;
                    }
                    long pts = capturedSamples * 1000000L / AUDIO_SAMPLE_RATE;
                    capturedSamples += count / AUDIO_PCM_BYTES_PER_FRAME;
                    pcmQueue.offer(new CastPcmQueue.Frame(
                            pcm, count, pts, System.nanoTime()));
                } catch (RuntimeException failure) {
                    pcmQueue.recycle(pcm);
                    if (active && running) {
                        failAsync("声音捕获异常", failure, sessionVideoEncoder);
                    }
                    break;
                }
            }
        }

        private void inputLoop() {
            while (active && running) {
                CastPcmQueue.Frame frame = null;
                try {
                    frame = pcmQueue.take();
                    if (frame == null) break;
                    int index;
                    do {
                        index = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    } while (index < 0 && active && running
                            && !frame.isStale(System.nanoTime()));
                    if (index < 0) continue;
                    if (frame.isStale(System.nanoTime())) {
                        encoder.queueInputBuffer(index, 0, 0, frame.ptsUs, 0);
                        continue;
                    }
                    ByteBuffer input = encoder.getInputBuffer(index);
                    if (input == null) {
                        encoder.queueInputBuffer(index, 0, 0, frame.ptsUs, 0);
                        continue;
                    }
                    input.clear();
                    int count = Math.min(input.remaining(), frame.length);
                    input.put(frame.data, 0, count);
                    encoder.queueInputBuffer(index, 0, count, frame.ptsUs, 0);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (RuntimeException failure) {
                    if (active && running) {
                        failAsync("声音编码输入异常", failure, sessionVideoEncoder);
                    }
                    break;
                } finally {
                    if (frame != null) pcmQueue.recycle(frame.data);
                }
            }
        }

        private void drainLoop() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (active && running) {
                try {
                    int index = encoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        server.setAudioConfig(
                                encoder.getOutputFormat().getByteBuffer("csd-0"));
                    } else if (index >= 0) {
                        try {
                            ByteBuffer output = encoder.getOutputBuffer(index);
                            if (output != null && info.size > 0 && info.size <= 8191
                                    && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                ByteBuffer copy = output.duplicate();
                                copy.position(info.offset); copy.limit(info.offset + info.size);
                                byte[] data = new byte[info.size]; copy.get(data);
                                queue.offer(new CastAudioQueue.Frame(data,
                                        info.presentationTimeUs, System.nanoTime()));
                            }
                        } finally {
                            // Socket back-pressure must never hold AAC codec buffers.
                            encoder.releaseOutputBuffer(index, false);
                        }
                    }
                } catch (RuntimeException failure) {
                    if (active && running) {
                        failAsync("声音编码异常", failure, sessionVideoEncoder);
                    }
                    break;
                }
            }
        }

        @Override
        public void close() {
            active = false;
            pcmQueue.close();
            queue.close();
            try {
                record.stop();
            } catch (RuntimeException ignored) {
            }
            record.release();
            joinWorker(recordThread);
            joinWorker(inputThread);
            joinWorker(drainThread);
            joinWorker(sendThread);
            try {
                encoder.stop();
            } catch (RuntimeException ignored) {
            }
            encoder.release();
        }
    }

}
