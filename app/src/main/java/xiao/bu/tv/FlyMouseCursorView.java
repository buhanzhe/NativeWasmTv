package xiao.bu.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;

public final class FlyMouseCursorView extends View {
    interface CursorVisibilityListener {
        void onCursorVisibilityChanged(boolean visible);
    }

    interface StateListener { void changed(); }
    private StateListener stateListener;
    private boolean drawSuppressed;
    private long clickSerial;
    void setStateListener(StateListener listener) { stateListener = listener; }
    private void stateChanged() { if (stateListener != null) stateListener.changed(); }
    boolean isCursorVisible() { return cursorVisible && getVisibility() == View.VISIBLE; }
    long clickSerial() { return clickSerial; }
    float cursorUnit() { return getResources().getDisplayMetrics().density * castVisualScale; }
    void setDrawSuppressed(boolean suppressed) {
        if (drawSuppressed == suppressed) return;
        // Clear the previously drawn pointer once, then skip hidden-frame work.
        invalidateCursorArea();
        drawSuppressed = suppressed;
        cancelFeedbackFrame();
        invalidateCursorArea();
    }
    void showRemotePosition(float x, float y, float unit, boolean visible) {
        float nextScale = unit / Math.max(.1f, getResources().getDisplayMetrics().density);
        if (Math.abs(castVisualScale - nextScale) > .001f) {
            invalidateCursorArea();
            castVisualScale = Math.max(.05f, Math.min(8f, nextScale));
        }
        if (visible) moveBy(x - cursorX(), y - cursorY());
        else setCursorVisible(false);
    }

    private static final long CURSOR_IDLE_TIMEOUT_MS = 5000L;
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path cursorPath = new Path();
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Bitmap cursorBitmap;
    private float bitmapUnit;
    private float bitmapPadding;
    private float cursorX = -1f;
    private float cursorY = -1f;
    private boolean cursorVisible = true;
    private CursorVisibilityListener cursorVisibilityListener;
    private float castVisualScale = 1f;
    private static final long CLICK_DURATION_MS = 240L;
    private long clickStartedAt;
    private long lastMoveAt;
    private long fastMotionMs;
    private long enlargedUntil;
    private float visualScale = 1f;
    private float scaleFrom = 1f;
    private float scaleTarget = 1f;
    private long scaleStartedAt;
    private long scaleDurationMs;
    private final Paint pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long feedbackDueAt;
    private long lastInteractionAt;
    private boolean idleCheckPosted;
    private final Runnable feedbackFrame = new Runnable() {
        @Override public void run() {
            feedbackDueAt = 0L;
            invalidateCursorArea();
        }
    };
    private final Runnable hideIdleCursor = new Runnable() {
        @Override
        public void run() {
            idleCheckPosted = false;
            long remaining = CURSOR_IDLE_TIMEOUT_MS - (SystemClock.uptimeMillis() - lastInteractionAt);
            if (cursorVisible && getVisibility() == VISIBLE && remaining > 0L) {
                idleCheckPosted = true;
                postDelayed(this, remaining);
                return;
            }
            setCursorVisible(false);
        }
    };

    public FlyMouseCursorView(Context context) {
        this(context, null);
    }

    public FlyMouseCursorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClickable(false);
        setFocusable(false);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(Color.WHITE);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeWidth(1.5f);
        strokePaint.setColor(0xff111111);
        // Keep geometry unchanged while moving; only the canvas transform changes.
        cursorPath.moveTo(0f, 0f);
        cursorPath.lineTo(5f, 18f);
        cursorPath.lineTo(10f, 13f);
        cursorPath.lineTo(16f, 21f);
        cursorPath.lineTo(20f, 18f);
        cursorPath.lineTo(14f, 10f);
        cursorPath.lineTo(21f, 8f);
        cursorPath.close();
    }

    void resetPosition() {
        invalidateCursorArea();
        if (getWidth() > 0 && getHeight() > 0) {
            cursorX = getWidth() / 2f;
            cursorY = getHeight() / 2f;
        }
        resetFeedback();
        revealCursor();
        invalidateCursorArea();
        stateChanged();
    }

    void moveBy(float dx, float dy) {
        ensurePosition();
        invalidateCursorArea();
        long now = SystemClock.uptimeMillis();
        long elapsed = now - lastMoveAt;
        float unit = getResources().getDisplayMetrics().density * castVisualScale;
        float minimumDistance = 650f * Math.max(.1f, unit) * elapsed / 1000f;
        // Require sustained fast movement, not a single coalesced network packet.
        if (!drawSuppressed && lastMoveAt > 0 && elapsed > 0 && elapsed <= 160L
                && dx * dx + dy * dy >= minimumDistance * minimumDistance) {
            fastMotionMs += Math.min(elapsed, 60L);
            if (fastMotionMs >= 120L) {
                updateFeedback(now);
                enlargedUntil = now + 240L;
                animateScaleTo(2f, now, 140L);
            }
        } else {
            fastMotionMs = 0L;
        }
        lastMoveAt = now;
        revealCursor();
        // MainActivity batches movement on VSYNC. Draw exactly the coordinates
        // dispatched to the browser, not a second independently animated cursor.
        // The tip must also reach controls at the very edge of the screen.
        cursorX = clamp(cursorX + dx, 0f, Math.max(0, getWidth() - 1));
        cursorY = clamp(cursorY + dy, 0f, Math.max(0, getHeight() - 1));
        invalidateCursorArea();
        stateChanged();
    }

    float cursorX() {
        ensurePosition();
        return cursorX;
    }

    float cursorY() {
        ensurePosition();
        return cursorY;
    }

    void pulseClick() {
        revealCursor();
        clickStartedAt = SystemClock.uptimeMillis();
        clickSerial++;
        invalidateCursorArea();
        stateChanged();
    }

    void setCastVisualScale(float scale) {
        float next = Math.max(0.25f, Math.min(1f, scale));
        if (Math.abs(next - castVisualScale) < 0.001f) return;
        castVisualScale = next;
        invalidate();
    }

    void setCursorVisibilityListener(CursorVisibilityListener listener) {
        cursorVisibilityListener = listener;
        if (listener != null) {
            listener.onCursorVisibilityChanged(
                    getVisibility() == View.VISIBLE && cursorVisible);
        }
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        if (cursorX < 0f || cursorY < 0f || oldWidth <= 0 || oldHeight <= 0) {
            cursorX = width / 2f;
            cursorY = height / 2f;
        } else {
            cursorX = width * cursorX / oldWidth;
            cursorY = height * cursorY / oldHeight;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        ensurePosition();
        if (!cursorVisible || drawSuppressed) {
            return;
        }
        // A lower-resolution stream is enlarged by the receiver. Scale against
        // the 4K canvas so the pointer keeps the same final on-screen size.
        long now = SystemClock.uptimeMillis();
        updateFeedback(now);
        float unit = getResources().getDisplayMetrics().density * castVisualScale;
        float clickProgress = clickStartedAt == 0L ? 1f
                : clamp((now - clickStartedAt) / (float) CLICK_DURATION_MS, 0f, 1f);
        float pressScale = 1f - .14f * (float) Math.sin(Math.PI * clickProgress);
        ensureCursorBitmap(unit);
        float scale = visualScale * pressScale / 2f;
        float x = cursorX;
        float y = cursorY;
        canvas.save();
        canvas.translate(x, y);
        canvas.scale(scale, scale);
        // Upload a small, stable texture once. Changing a path's scale every
        // frame otherwise churns the legacy HWUI path-mask cache during circles.
        canvas.drawBitmap(cursorBitmap, -bitmapPadding, -bitmapPadding, bitmapPaint);
        canvas.restore();
        if (clickProgress < 1f) {
            // A brief expanding ripple and soft press/release, anchored at the actual hit point.
            pulsePaint.setStyle(Paint.Style.STROKE);
            pulsePaint.setStrokeWidth(1.5f * unit);
            pulsePaint.setColor(0xffb9e5ff);
            pulsePaint.setAlpha(Math.round(190f * (1f - clickProgress)));
            canvas.drawCircle(x, y, (7f + 19f * clickProgress) * unit, pulsePaint);
        }
        boolean animating = visualScale != scaleTarget || clickProgress < 1f;
        if (animating || enlargedUntil > now) {
            scheduleFeedbackFrame(animating ? 0L : Math.max(1L, enlargedUntil - now));
        } else {
            cancelFeedbackFrame();
        }
    }

    private void ensureCursorBitmap(float unit) {
        if (cursorBitmap != null && Math.abs(bitmapUnit - unit) < .001f) return;
        bitmapUnit = unit;
        // Rasterize at the maximum 2x size; movement and animation only composite
        // this image. Density / cast resolution changes are the only rebuilds.
        float rasterScale = unit * 2f;
        bitmapPadding = (float) Math.ceil(2f * rasterScale);
        int size = Math.max(1, (int) Math.ceil(25f * rasterScale));
        cursorBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas raster = new Canvas(cursorBitmap);
        raster.translate(bitmapPadding, bitmapPadding);
        raster.scale(rasterScale, rasterScale);
        raster.drawPath(cursorPath, fillPaint);
        raster.drawPath(cursorPath, strokePaint);
    }

    private void scheduleFeedbackFrame(long delay) {
        long due = SystemClock.uptimeMillis() + delay;
        if (feedbackDueAt != 0L && feedbackDueAt <= due) return;
        cancelFeedbackFrame();
        feedbackDueAt = due;
        if (Build.VERSION.SDK_INT >= 16) postOnAnimationDelayed(feedbackFrame, delay);
        else postDelayed(feedbackFrame, Math.max(16L, delay));
    }

    private void cancelFeedbackFrame() {
        if (feedbackDueAt == 0L) return;
        removeCallbacks(feedbackFrame);
        feedbackDueAt = 0L;
    }

    private void invalidateCursorArea() {
        if (drawSuppressed) return;
        int radius = (int) Math.ceil(52f * getResources().getDisplayMetrics().density
                * castVisualScale + 4f);
        invalidate((int) cursorX - radius, (int) cursorY - radius,
                (int) cursorX + radius, (int) cursorY + radius);
    }

    private void animateScaleTo(float target, long now, long duration) {
        if (scaleTarget == target) return;
        scaleFrom = visualScale;
        scaleTarget = target;
        scaleStartedAt = now;
        scaleDurationMs = duration;
    }

    private void updateFeedback(long now) {
        if (visualScale != scaleTarget) {
            float progress = clamp((now - scaleStartedAt) / (float) scaleDurationMs, 0f, 1f);
            float remaining = 1f - progress;
            visualScale = progress >= 1f ? scaleTarget
                    : scaleFrom + (scaleTarget - scaleFrom) * (1f - remaining * remaining * remaining);
        }
        if (enlargedUntil > 0L && now >= enlargedUntil) {
            enlargedUntil = 0L;
            animateScaleTo(1f, now, 280L);
        }
    }

    private void resetFeedback() {
        cancelFeedbackFrame();
        clickStartedAt = lastMoveAt = fastMotionMs = enlargedUntil = 0L;
        visualScale = scaleFrom = scaleTarget = 1f;
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(hideIdleCursor);
        idleCheckPosted = false;
        cursorBitmap = null;
        resetFeedback();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        // View's constructor may dispatch visibility before subclass fields exist.
        if (changedView != this || hideIdleCursor == null) {
            return;
        }
        removeCallbacks(hideIdleCursor);
        idleCheckPosted = false;
        if (visibility == View.VISIBLE) {
            revealCursor();
        } else {
            setCursorVisible(false);
        }
    }

    private void revealCursor() {
        setCursorVisible(true);
        lastInteractionAt = SystemClock.uptimeMillis();
        if (!idleCheckPosted) {
            idleCheckPosted = true;
            postDelayed(hideIdleCursor, CURSOR_IDLE_TIMEOUT_MS);
        }
    }

    private void setCursorVisible(boolean visible) {
        if (cursorVisible == visible) {
            return;
        }
        cursorVisible = visible;
        if (!visible) resetFeedback();
        invalidateCursorArea();
        if (cursorVisibilityListener != null) {
            cursorVisibilityListener.onCursorVisibilityChanged(visible);
        }
        stateChanged();
    }

    private void ensurePosition() {
        if (cursorX < 0f || cursorY < 0f) {
            cursorX = getWidth() / 2f;
            cursorY = getHeight() / 2f;
        }
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
