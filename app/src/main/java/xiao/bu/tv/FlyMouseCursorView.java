package xiao.bu.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

public final class FlyMouseCursorView extends View {
    interface CursorVisibilityListener {
        void onCursorVisibilityChanged(boolean visible);
    }

    private static final long CURSOR_IDLE_TIMEOUT_MS = 5000L;
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path cursorPath = new Path();
    private float cursorX = -1f;
    private float cursorY = -1f;
    private boolean cursorVisible = true;
    private CursorVisibilityListener cursorVisibilityListener;
    private float castVisualScale = 1f;
    private long clickPulseUntil;
    private final Runnable hideIdleCursor = new Runnable() {
        @Override
        public void run() {
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
        strokePaint.setStrokeWidth(dp(1.5f));
        strokePaint.setColor(0xff111111);
    }

    void resetPosition() {
        if (getWidth() > 0 && getHeight() > 0) {
            cursorX = getWidth() / 2f;
            cursorY = getHeight() / 2f;
        }
        revealCursor();
    }

    void moveBy(float dx, float dy) {
        ensurePosition();
        revealCursor();
        // MainActivity batches movement on VSYNC. Draw exactly the coordinates
        // dispatched to the browser, not a second independently animated cursor.
        // The tip must also reach controls at the very edge of the screen.
        cursorX = clamp(cursorX + dx, 0f, Math.max(0, getWidth() - 1));
        cursorY = clamp(cursorY + dy, 0f, Math.max(0, getHeight() - 1));
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
        clickPulseUntil = SystemClock.uptimeMillis() + 180L;
        invalidate();
        postInvalidateDelayed(190L);
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
        if (!cursorVisible) {
            return;
        }
        // A lower-resolution stream is enlarged by the receiver. Scale against
        // the 4K canvas so the pointer keeps the same final on-screen size.
        float scale = getResources().getDisplayMetrics().density * castVisualScale;
        strokePaint.setStrokeWidth(1.5f * scale);
        float x = cursorX;
        float y = cursorY;
        cursorPath.reset();
        cursorPath.moveTo(x, y);
        cursorPath.lineTo(x + 5f * scale, y + 18f * scale);
        cursorPath.lineTo(x + 10f * scale, y + 13f * scale);
        cursorPath.lineTo(x + 16f * scale, y + 21f * scale);
        cursorPath.lineTo(x + 20f * scale, y + 18f * scale);
        cursorPath.lineTo(x + 14f * scale, y + 10f * scale);
        cursorPath.lineTo(x + 21f * scale, y + 8f * scale);
        cursorPath.close();
        canvas.drawPath(cursorPath, fillPaint);
        canvas.drawPath(cursorPath, strokePaint);
        if (SystemClock.uptimeMillis() < clickPulseUntil) {
            Paint pulse = strokePaint;
            pulse.setColor(0xff34c759);
            pulse.setStrokeWidth(2f * scale);
            canvas.drawCircle(x, y, 15f * scale, pulse);
            pulse.setColor(0xff111111);
            pulse.setStrokeWidth(1.5f * scale);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(hideIdleCursor);
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
        if (visibility == View.VISIBLE) {
            revealCursor();
        } else {
            setCursorVisible(false);
        }
    }

    private void revealCursor() {
        setCursorVisible(true);
        removeCallbacks(hideIdleCursor);
        postDelayed(hideIdleCursor, CURSOR_IDLE_TIMEOUT_MS);
    }

    private void setCursorVisible(boolean visible) {
        if (cursorVisible == visible) {
            invalidate();
            return;
        }
        cursorVisible = visible;
        invalidate();
        if (cursorVisibilityListener != null) {
            cursorVisibilityListener.onCursorVisibilityChanged(visible);
        }
    }

    private void ensurePosition() {
        if (cursorX < 0f || cursorY < 0f) {
            cursorX = getWidth() / 2f;
            cursorY = getHeight() / 2f;
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
