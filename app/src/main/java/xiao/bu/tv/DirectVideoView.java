package xiao.bu.tv;

import android.content.Context;
import android.graphics.PixelFormat;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;

/** Direct video surface, preserving native video resolution independently of the UI layer. */
public final class DirectVideoView extends FrameLayout implements SurfaceHolder.Callback {
    private final SurfaceView surfaceView;
    private android.view.View frameCover;
    private SurfaceCallback callback;
    private SurfaceHolder activeHolder;
    private float aspectRatio;
    private final VideoBlurBackdrop blur = new VideoBlurBackdrop(this);
    private boolean blurBackground;
    void setBlurFrameRateSource(VideoBlurBackdrop.FrameRateSource source) { blur.setFrameRateSource(source); }
    void setBlurBackground(boolean enabled) {
        if (blurBackground == enabled) return;
        blurBackground = enabled; blur.enable(enabled); requestLayout();
    }
    @Override protected void onDraw(android.graphics.Canvas canvas) {
        super.onDraw(canvas); blur.draw(canvas);
    }
    @Override protected void onDetachedFromWindow() { blur.clear(); super.onDetachedFromWindow(); }
    @Override protected void onVisibilityChanged(android.view.View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (blur != null) { if (visibility != VISIBLE) blur.clear(); else invalidate(); }
    }
    void setAspectRatio(float value){if(aspectRatio!=value){aspectRatio=value;requestLayout();}}
    private int videoWidth, videoHeight, sarNum = 1, sarDen = 1;
    private int gpuBufferWidth, gpuBufferHeight;
    private boolean stretchVideo, fillVideo, legacySurfaceMode, viewportBufferMode, appliedViewportBufferMode;

    public DirectVideoView(Context context) { this(context, null); }

    public DirectVideoView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        surfaceView = new SurfaceView(context);
        surfaceView.getHolder().setFormat(PixelFormat.OPAQUE);
        applySurfaceType();
        surfaceView.getHolder().addCallback(this);
        addView(surfaceView, new LayoutParams(-1, -1));
        setKeepScreenOn(true);
    }

    SurfaceView getSurfaceView() { return surfaceView; }
    boolean canCaptureMenuBackdrop() {
        // PixelCopy can allocate/read the full decoder buffer inside the driver,
        // even when its destination is a thumbnail. Keep 4K playback off that path.
        return videoWidth > 0 && videoHeight > 0
                && (long) videoWidth * videoHeight <= 1920L * 1080
                && isSurfaceReady() && !isFrameCovered();
    }
    boolean isFrameCovered() { return frameCover != null && frameCover.getVisibility() == VISIBLE; }
    void setFrameCovered(boolean covered) {
        if (covered) blur.clear(); else invalidate();
        if (frameCover == null) {
            if (!covered) return;
            frameCover = new android.view.View(getContext());
            frameCover.setBackgroundColor(android.graphics.Color.BLACK);
            addView(frameCover, new LayoutParams(-1, -1));
        }
        // Keep the Surface attached: INVISIBLE/GONE can destroy its native output.
        frameCover.setVisibility(covered ? VISIBLE : GONE);
    }
    void setLegacySurfaceMode(boolean enabled) {
        if (legacySurfaceMode == enabled) return;
        legacySurfaceMode = enabled;
        applySurfaceType();
    }

    private void applySurfaceType() {
        if (surfaceView == null) return;
        //noinspection deprecation
        surfaceView.getHolder().setType(legacySurfaceMode
                ? SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS : SurfaceHolder.SURFACE_TYPE_NORMAL);
    }

    void setSurfaceCallback(SurfaceCallback listener) {
        callback = listener;
        if (listener != null && isSurfaceReady())
            listener.onVideoSurfaceCreated(getVideoSurfaceHolder(), getVideoSurface());
    }

    boolean isSurfaceReady() {
        Surface surface = getVideoSurface();
        return surface != null && surface.isValid();
    }

    SurfaceHolder getVideoSurfaceHolder() { return activeHolder; }
    Surface getVideoSurface() {
        return activeHolder == null ? null : activeHolder.getSurface();
    }

    void setStretchVideo(boolean enabled) {
        if (stretchVideo == enabled) return;
        stretchVideo = enabled;
        updateBufferSize();
        requestLayout();
    }

    void setFillVideo(boolean enabled) {
        if (fillVideo == enabled) return;
        fillVideo = enabled;
        updateBufferSize();
        requestLayout();
    }

    void setVideoSize(int width, int height, int sarNum, int sarDen) {
        width = Math.max(0, width);
        height = Math.max(0, height);
        sarNum = sarNum > 0 ? sarNum : 1;
        sarDen = sarDen > 0 ? sarDen : 1;
        // Size and SAR often arrive separately, followed by onPrepared. Avoid
        // repeating the same native-window resize and layout before first frame.
        if (videoWidth == width && videoHeight == height
                && this.sarNum == sarNum && this.sarDen == sarDen
                && appliedViewportBufferMode == viewportBufferMode) return;
        videoWidth = width;
        blur.clear();
        videoHeight = height;
        this.sarNum = sarNum;
        this.sarDen = sarDen;
        appliedViewportBufferMode = viewportBufferMode;
        updateBufferSize();
        requestLayout();
    }

    void setViewportBufferMode(boolean enabled) { viewportBufferMode = enabled; }

    void setGpuBufferSize(int width,int height) {
        if(gpuBufferWidth==width&&gpuBufferHeight==height)return;
        gpuBufferWidth=width;gpuBufferHeight=height;
        updateBufferSize();
    }

    private void updateBufferSize() {
        if(gpuBufferWidth>0&&gpuBufferHeight>0&&!viewportBufferMode) {
            surfaceView.getHolder().setFixedSize(gpuBufferWidth,gpuBufferHeight);
            return;
        }
        if (viewportBufferMode && fillVideo && !stretchVideo && videoWidth > 0 && videoHeight > 0
                && getParent() instanceof android.view.View) {
            android.view.View parent = (android.view.View) getParent();
            if (parent.getWidth() > 0 && parent.getHeight() > 0) {
                setFillBufferSize(parent.getWidth(), parent.getHeight());
                return;
            }
        }
        if (!viewportBufferMode && videoWidth > 0 && videoHeight > 0)
            surfaceView.getHolder().setFixedSize(videoWidth, videoHeight);
        else surfaceView.getHolder().setSizeFromLayout();
    }

    private void setFillBufferSize(int width, int height) {
        // Cropped portrait video can extend far beyond the screen. Limit low-RAM
        // software output independently of the displayed Surface rectangle.
        float ratio = (float) videoWidth * sarNum / ((float) videoHeight * sarDen);
        surfaceView.getHolder().setFixedSize(
                Math.min(width, Math.max(1, Math.round(height * ratio))),
                Math.min(height, Math.max(1, Math.round(width / ratio))));
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int availableWidth = MeasureSpec.getSize(widthMeasureSpec);
        int availableHeight = MeasureSpec.getSize(heightMeasureSpec);
        int width = availableWidth, height = availableHeight;
        int fillWidth = 0, fillHeight = 0;
        if (!stretchVideo && videoWidth > 0 && videoHeight > 0
                && availableWidth > 0 && availableHeight > 0) {
            float videoAspect = aspectRatio>0?aspectRatio:(float) videoWidth * sarNum / ((float) videoHeight * sarDen);
            if (fillVideo) {
                // Resize the actual Surface rectangle: View transforms do not move
                // SurfaceView on older Android. Keep its parent at viewport size
                // so swipe animations and the frame cover still use screen bounds.
                if ((float) availableWidth / availableHeight > videoAspect)
                    fillHeight = (int) Math.ceil(availableWidth / (double) videoAspect);
                else fillWidth = (int) Math.ceil(availableHeight * (double) videoAspect);
                fillWidth = Math.max(availableWidth, fillWidth);
                fillHeight = Math.max(availableHeight, fillHeight);
                // Also handle a changed viewport (rotation / window resize).
                if (viewportBufferMode) setFillBufferSize(availableWidth, availableHeight);
            } else {
                if ((float) availableWidth / availableHeight > videoAspect)
                    width = Math.round(availableHeight * videoAspect);
                else height = Math.round(availableWidth / videoAspect);
            }
        }
        super.onMeasure(MeasureSpec.makeMeasureSpec(blurBackground ? availableWidth : width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(blurBackground ? availableHeight : height, MeasureSpec.EXACTLY));
        if (blurBackground)
            surfaceView.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        if (fillWidth > 0 && fillHeight > 0)
            surfaceView.measure(MeasureSpec.makeMeasureSpec(fillWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(fillHeight, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int x = (getMeasuredWidth() - surfaceView.getMeasuredWidth()) / 2;
        int y = (getMeasuredHeight() - surfaceView.getMeasuredHeight()) / 2;
        surfaceView.layout(x, y, x + surfaceView.getMeasuredWidth(), y + surfaceView.getMeasuredHeight());
        if (frameCover != null && frameCover.getVisibility() != GONE)
            frameCover.layout(0, 0, getMeasuredWidth(), getMeasuredHeight());
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        activeHolder = holder;
        // A recreated holder starts at layout size even if the source dimensions
        // have not changed. Restore its buffer before the new decoder attaches.
        if (videoWidth > 0 && videoHeight > 0) updateBufferSize();
        invalidate();
        if (callback != null) callback.onVideoSurfaceCreated(holder, holder.getSurface());
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if(android.os.Build.VERSION.SDK_INT>=17)NativeHdrOutput.surfaceSizeChanged(holder.getSurface());
    }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        blur.clear();
        if (callback != null) callback.onVideoSurfaceDestroyed(holder, holder.getSurface());
        if (activeHolder == holder) {
            activeHolder = null;
            gpuBufferWidth = gpuBufferHeight = 0;
        }
    }

    void releaseOutput() {
        blur.clear();
        callback = null;
        activeHolder = null;
        // SurfaceView owns its Surface. The caller removes this view on retirement
        // or Activity teardown; do not release a codec's Surface from the UI thread.
        surfaceView.getHolder().removeCallback(this);
    }

    interface SurfaceCallback {
        void onVideoSurfaceCreated(SurfaceHolder holder, Surface surface);
        void onVideoSurfaceDestroyed(SurfaceHolder holder, Surface surface);
    }
}
