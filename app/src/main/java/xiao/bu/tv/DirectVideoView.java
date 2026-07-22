package xiao.bu.tv;

import android.content.Context;
import android.graphics.PixelFormat;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/** A zero-copy MediaCodec target, preferred on KitKat-class TV hardware. */
public final class DirectVideoView extends SurfaceView implements SurfaceHolder.Callback {
    private SurfaceCallback callback;
    private int videoWidth;
    private int videoHeight;
    private int sarNum = 1;
    private int sarDen = 1;

    public DirectVideoView(Context context) {
        this(context, null);
    }

    public DirectVideoView(Context context, AttributeSet attrs) {
        super(context, attrs);
        getHolder().setFormat(PixelFormat.OPAQUE);
        getHolder().addCallback(this);
        setKeepScreenOn(true);
    }

    void setSurfaceCallback(SurfaceCallback callback) {
        this.callback = callback;
        Surface surface = getHolder().getSurface();
        if (callback != null && surface != null && surface.isValid()) {
            callback.onVideoSurfaceCreated(surface);
        }
    }

    void setVideoSize(int width, int height, int sarNum, int sarDen) {
        videoWidth = Math.max(0, width);
        videoHeight = Math.max(0, height);
        this.sarNum = sarNum > 0 ? sarNum : 1;
        this.sarDen = sarDen > 0 ? sarDen : 1;
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
        if (videoWidth > 0 && videoHeight > 0 && availableWidth > 0 && availableHeight > 0) {
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
        if (callback != null) {
            callback.onVideoSurfaceCreated(holder.getSurface());
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (callback != null) {
            callback.onVideoSurfaceDestroyed(holder.getSurface());
        }
    }

    interface SurfaceCallback {
        void onVideoSurfaceCreated(Surface surface);

        void onVideoSurfaceDestroyed(Surface surface);
    }
}
