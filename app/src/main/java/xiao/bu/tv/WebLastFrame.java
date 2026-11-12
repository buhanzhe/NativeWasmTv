package xiao.bu.tv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

/** One bounded outgoing frame, covering a reused WebView until its media is ready. */
final class WebLastFrame {
    interface Host {
        boolean ready(int request);
        void finished(boolean timeout);
    }
    private final Activity activity;
    private final ViewGroup root;
    private final View source;
    private final Host host;
    private final ImageView cover;
    private final View matte;
    private boolean swipePreview;
    private boolean nativeSwipe, nativeSwipeAnimating, nativeFrameReady;
    private int nativeSwipeGeneration;
    private int previewOrigin = -1;
    private float previewX, previewY;
    private final Handler ui = new Handler();
    private Bitmap frame;
    private int generation, request = -1, polls;
    private Runnable captureFallback;
    private boolean capturing, closed;
    private final Runnable check = new Runnable() {
        @Override public void run() {
            if (frame == null || closed) return;
            if (request >= 0 && host.ready(request)) { clear(); host.finished(false); return; }
            if (++polls >= 150) { clear(); host.finished(true); return; }
            ui.postDelayed(this, 200L);
        }
    };

    WebLastFrame(Activity activity, ViewGroup root, View source, Host host) {
        this.activity = activity; this.root = root; this.source = source; this.host = host;
        cover = new ImageView(activity);
        matte = new View(activity);
        matte.setBackgroundColor(android.graphics.Color.BLACK);
        matte.setVisibility(View.GONE);
        matte.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        cover.setScaleType(ImageView.ScaleType.FIT_XY);
        cover.setClickable(false); cover.setFocusable(false);
        cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        cover.setVisibility(View.GONE);
        root.addView(matte, new FrameLayout.LayoutParams(-1, -1));
        root.addView(cover, new FrameLayout.LayoutParams(-1, -1));
    }

    boolean active() { return frame != null || capturing; }
    boolean isSwipePreview() { return swipePreview; }
    boolean isNativeSwipe() { return nativeSwipe; }
    View animationView() { return frame == null ? null : cover; }

    void preview(float x, float y) {
        if (!swipePreview) { previewOrigin = request; request = -1; polls = 0; }
        swipePreview = true; previewX = x; previewY = y;
        if (frame != null) positionPreview();
        else if (!capturing) capture(this::positionPreview);
    }

    private void positionPreview() {
        if (!swipePreview || frame == null) return;
        cover.animate().cancel(); cover.setTranslationX(previewX); cover.setTranslationY(previewY);
    }

    void rebound(long duration, android.animation.TimeInterpolator interpolator) {
        if (!swipePreview) return;
        previewX = previewY = 0f;
        if (frame != null) cover.animate().translationX(0f).translationY(0f)
                .setDuration(duration).setInterpolator(interpolator).start();
    }

    void finishPreview() {
        if (!swipePreview) return;
        swipePreview = false; previewX = previewY = 0f;
        cover.animate().withEndAction(null).cancel(); cover.setTranslationX(0f); cover.setTranslationY(0f);
        if (previewOrigin >= 0) { request = previewOrigin; previewOrigin = -1; polls = 0; }
        else clear();
    }

    void capture(Runnable navigate) {
        capture(null, navigate);
    }

    void captureVideo(DirectVideoView video, Runnable navigate) {
        if (!video.isTextureOutput()) { captureSurface(video.getSurfaceView(), navigate); return; }
        cancelCapture(); request = -1; polls = 0;
        View output = video.getRenderView();
        int width = output.getWidth(), height = output.getHeight();
        if (frame != null || closed || width <= 0 || height <= 0) { navigate.run(); return; }
        float scale = Math.min(1f, Math.min(1280f / width, 1280f / height));
        final Bitmap bitmap;
        try { bitmap = Bitmap.createBitmap(Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale)), Bitmap.Config.ARGB_8888); }
        catch (OutOfMemoryError unavailable) { navigate.run(); return; }
        video.copyVideoFrame(bitmap, ui, result -> {
            if (result == 0) {
                frame = bitmap;
                cover.setScaleType(width > root.getWidth() || height > root.getHeight()
                        ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
                cover.setBackgroundColor(android.graphics.Color.BLACK);
                cover.setImageBitmap(bitmap); cover.setVisibility(View.VISIBLE); matte.setVisibility(View.VISIBLE);
                raise(); ui.removeCallbacks(check); ui.postDelayed(check, 200L);
            } else bitmap.recycle();
            navigate.run();
        });
    }

    void captureSurface(SurfaceView video, Runnable navigate) {
        capture(video, navigate);
    }

    private void capture(SurfaceView video, Runnable navigate) {
        cancelCapture();
        request = -1; polls = 0;
        if (frame != null) { navigate.run(); return; }
        final int token = generation;
        int width = video == null ? root.getWidth() : video.getWidth();
        int height = video == null ? root.getHeight() : video.getHeight();
        if (closed || width <= 0 || height <= 0 || Build.VERSION.SDK_INT < 24) { navigate.run(); return; }
        float scale = Math.min(1f, Math.min(1280f / width, 1280f / height));
        final Bitmap bitmap;
        try { bitmap = Bitmap.createBitmap(Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale)), Bitmap.Config.ARGB_8888); }
        catch (OutOfMemoryError error) { navigate.run(); return; }
        cover.setScaleType(video == null ? ImageView.ScaleType.FIT_XY
                : width > root.getWidth() || height > root.getHeight()
                ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        cover.setBackgroundColor(android.graphics.Color.BLACK);
        capturing = true;
        // A failed/cancelled GPU copy must never hold up channel selection. Its
        // destination stays alive until PixelCopy completes, even after timeout.
        captureFallback = () -> {
            if (token != generation || !capturing) return;
            capturing = false; generation++; captureFallback = null; navigate.run();
        };
        ui.postDelayed(captureFallback, 500L);
        try {
            PixelCopy.OnPixelCopyFinishedListener done = result -> {
                if (token != generation || closed || !capturing) { bitmap.recycle(); return; }
                if (captureFallback != null) ui.removeCallbacks(captureFallback);
                captureFallback = null; capturing = false;
                if (result == PixelCopy.SUCCESS) {
                    frame = bitmap; cover.setImageBitmap(bitmap); cover.setVisibility(View.VISIBLE);
                    matte.setVisibility(View.VISIBLE);
                    raise(); ui.removeCallbacks(check); ui.postDelayed(check, 200L);
                } else bitmap.recycle();
                navigate.run();
            };
            if (video != null) PixelCopy.request(video, bitmap, done, ui);
            else if (Build.VERSION.SDK_INT >= 26) copyWindow(bitmap, done);
            else copyLegacyWindow(bitmap, done);
        } catch (Exception error) {
            if (captureFallback != null) ui.removeCallbacks(captureFallback);
            captureFallback = null; capturing = false; bitmap.recycle(); navigate.run();
        }
    }

    @android.annotation.TargetApi(26)
    private void copyWindow(Bitmap bitmap, PixelCopy.OnPixelCopyFinishedListener done) {
        int[] location = new int[2]; root.getLocationInWindow(location);
        PixelCopy.request(activity.getWindow(), new Rect(location[0], location[1],
                location[0] + root.getWidth(), location[1] + root.getHeight()), bitmap, done, ui);
    }

    @android.annotation.TargetApi(24)
    private void copyLegacyWindow(Bitmap bitmap, PixelCopy.OnPixelCopyFinishedListener done) throws Exception {
        // API 24/25 expose Surface PixelCopy but not its Window overload.
        // This fallback is confined to those releases (before hidden-API rules).
        java.lang.reflect.Method method = View.class.getDeclaredMethod("getViewRootImpl");
        method.setAccessible(true);
        Object viewRoot = method.invoke(activity.getWindow().getDecorView());
        java.lang.reflect.Field field = viewRoot.getClass().getDeclaredField("mSurface");
        field.setAccessible(true);
        PixelCopy.request((Surface) field.get(viewRoot), bitmap, done, ui);
    }

    void bind(int id) { if (frame != null) { if(request!=id)nativeFrameReady=false;request = id; if (swipePreview) previewOrigin = id; polls = 0; } }
    void nativeReady(int id) {
        if (frame == null || request != id) return;
        if (nativeSwipeAnimating) { nativeFrameReady=true; return; }
        clear(); host.finished(false);
    }

    /** Preserve the decoded outgoing pixels after the old player is released. */
    void animateNativeSwipe(float startX,float startY,float directionX,float directionY,
            android.animation.TimeInterpolator interpolator) {
        if(frame==null)return;
        nativeSwipe=nativeSwipeAnimating=true;nativeFrameReady=false;
        final int token=++nativeSwipeGeneration;
        cover.animate().withEndAction(null).cancel();
        cover.setTranslationX(startX);cover.setTranslationY(startY);
        cover.animate().translationX(directionX*root.getWidth()).translationY(directionY*root.getHeight())
                .setDuration(160L).setInterpolator(interpolator).withEndAction(()->{
                    if(token!=nativeSwipeGeneration||!nativeSwipe)return;
                    nativeSwipeAnimating=false;
                    if(nativeFrameReady){clear();host.finished(false);}
                }).start();
    }

    void raise() {
        if (frame == null || source.getParent() != root) return;
        if (root.indexOfChild(cover) <= root.indexOfChild(source)) {
            root.removeView(cover);
            root.addView(cover, root.indexOfChild(source) + 1, new FrameLayout.LayoutParams(-1, -1));
        }
        if (root.indexOfChild(matte) != root.indexOfChild(cover) - 1) {
            root.removeView(matte);
            root.addView(matte, root.indexOfChild(cover), new FrameLayout.LayoutParams(-1, -1));
        }
    }

    void cancelCapture() {
        generation++; capturing = false;
        if (captureFallback != null) ui.removeCallbacks(captureFallback);
        captureFallback = null;
    }

    void clear() {
        cancelCapture(); ui.removeCallbacks(check); request = -1;
        swipePreview = false; previewOrigin = -1; previewX = previewY = 0f;
        nativeSwipe=nativeSwipeAnimating=nativeFrameReady=false;
        nativeSwipeGeneration++;
        cover.animate().withEndAction(null).cancel();
        cover.setTranslationX(0f); cover.setTranslationY(0f); matte.setVisibility(View.GONE);
        cover.setVisibility(View.GONE); cover.setImageDrawable(null);
        // RenderThread may still own the last display list; let GC release the
        // displayed bitmap after the ImageView has relinquished it.
        frame = null;
    }

    void close() { closed = true; clear(); root.removeView(cover); root.removeView(matte); }
}
