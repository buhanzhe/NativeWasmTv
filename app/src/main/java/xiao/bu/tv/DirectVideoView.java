package xiao.bu.tv;

import android.content.Context;
import android.graphics.PixelFormat;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/** A zero-copy MediaCodec target, preferred on KitKat-class TV hardware. */
public final class DirectVideoView extends SurfaceView implements SurfaceHolder.Callback {
    private SurfaceCallback callback;
    private SurfaceHolder activeHolder;
    private int videoWidth;
    private int videoHeight;
    private int sarNum = 1;
    private int sarDen = 1;
    private boolean stretchVideo;
    private boolean legacySurfaceMode;

    public DirectVideoView(Context context) {
        this(context, null);
    }

    public DirectVideoView(Context context, AttributeSet attrs) {
        super(context, attrs);
        getHolder().setFormat(PixelFormat.OPAQUE);
        applySurfaceType();
        getHolder().addCallback(this);
        setKeepScreenOn(true);
    }

    void setLegacySurfaceMode(boolean legacySurfaceMode) {
        if (this.legacySurfaceMode == legacySurfaceMode) {
            return;
        }
        this.legacySurfaceMode = legacySurfaceMode;
        applySurfaceType();
    }

    private void applySurfaceType() {
        // Android 4.3's framework VideoView uses PUSH_BUFFERS. Keep NORMAL available for
        // ijkplayer-compatible devices and let the user A/B test the vendor overlay path.
        //noinspection deprecation
        getHolder().setType(legacySurfaceMode
                ? SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS
                : SurfaceHolder.SURFACE_TYPE_NORMAL);
    }

    void setSurfaceCallback(SurfaceCallback callback) {
        this.callback = callback;
        SurfaceHolder holder = activeHolder;
        if (callback != null && holder != null && holder.getSurface() != null
                && holder.getSurface().isValid()) {
            callback.onVideoSurfaceCreated(holder);
        }
    }

    boolean isSurfaceReady() {
        return activeHolder != null && activeHolder.getSurface() != null
                && activeHolder.getSurface().isValid();
    }

    SurfaceHolder getVideoSurfaceHolder() {
        return isSurfaceReady() ? activeHolder : null;
    }

    void setStretchVideo(boolean stretchVideo) {
        if (this.stretchVideo == stretchVideo) {
            return;
        }
        this.stretchVideo = stretchVideo;
        requestLayout();
    }

    void setVideoSize(int width, int height, int sarNum, int sarDen) {
        videoWidth = Math.max(0, width);
        videoHeight = Math.max(0, height);
        this.sarNum = sarNum > 0 ? sarNum : 1;
        this.sarDen = sarDen > 0 ? sarDen : 1;
        if (videoWidth > 0 && videoHeight > 0) {
            // Keep the producer buffer at the encoded size. Old MStar/MediaCodec hardware
            // overlays can render black when the Surface buffer remains at the window size.
            getHolder().setFixedSize(videoWidth, videoHeight);
        } else {
            getHolder().setSizeFromLayout();
        }
        requestLayout();
    }

    void onPause() {
        // SurfaceHolder owns the lifecycle; no GL context needs pausing.
    }

    void onResume() {
        // SurfaceHolder recreates the surface when required.
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int availableWidth = MeasureSpec.getSize(widthMeasureSpec);
        int availableHeight = MeasureSpec.getSize(heightMeasureSpec);
        int width = availableWidth;
        int height = availableHeight;
        if (!stretchVideo && videoWidth > 0 && videoHeight > 0
                && availableWidth > 0 && availableHeight > 0) {
            float videoAspect = (float) videoWidth * sarNum / ((float) videoHeight * sarDen);
            float viewAspect = (float) availableWidth / availableHeight;
            if (viewAspect > videoAspect) {
                width = Math.round(availableHeight * videoAspect);
            } else {
                height = Math.round(availableWidth / videoAspect);
            }
        }
        setMeasuredDimension(width, height);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        activeHolder = holder;
        if (callback != null) {
            callback.onVideoSurfaceCreated(holder);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (callback != null) {
            callback.onVideoSurfaceDestroyed(holder);
        }
        if (activeHolder == holder) {
            activeHolder = null;
        }
    }

    interface SurfaceCallback {
        void onVideoSurfaceCreated(SurfaceHolder holder);

        void onVideoSurfaceDestroyed(SurfaceHolder holder);
    }
}
