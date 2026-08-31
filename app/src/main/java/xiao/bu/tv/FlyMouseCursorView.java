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
    private static final long CURSOR_IDLE_TIMEOUT_MS = 5000L;
    private static final float MIN_VISIBLE_MOVEMENT_PX = 0.25f;
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path cursorPath = new Path();
    private float cursorX = -1f;
    private float cursorY = -1f;
    private float targetX = -1f;
    private float targetY = -1f;
    private boolean moveAnimationRunning;
    private boolean cursorVisible = true;
    private long clickPulseUntil;
    private final Runnable hideIdleCursor = new Runnable() {
        @Override
        public void run() {
            cursorVisible = false;
            invalidate();
        }
    };
    private final Runnable moveAnimationFrame = new Runnable() {
        @Override
        public void run() {
            if (!moveAnimationRunning) {
                return;
            }
            float deltaX = targetX - cursorX;
            float deltaY = targetY - cursorY;
            if (Math.abs(deltaX) < 0.35f && Math.abs(deltaY) < 0.35f) {
                cursorX = targetX;
                cursorY = targetY;
                moveAnimationRunning = false;
                invalidate();
                return;
            }
            // Follow the accumulated target instead of restarting an animator for
            // every network packet. Large movements catch up a little faster while
            // small movements remain precise and visually smooth.
            float distance = Math.max(Math.abs(deltaX), Math.abs(deltaY));
            float interpolation = 0.55f + Math.min(0.25f, distance / 600f);
            cursorX += deltaX * interpolation;
            cursorY += deltaY * interpolation;
            invalidate();
            postDelayed(this, 16L);
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
        removeCallbacks(moveAnimationFrame);
        moveAnimationRunning = false;
        if (getWidth() > 0 && getHeight() > 0) {
            cursorX = getWidth() / 2f;
            cursorY = getHeight() / 2f;
            targetX = cursorX;
            targetY = cursorY;
        }
        revealCursor();
    }

    void moveBy(float dx, float dy) {
        ensurePosition();
        if (Math.abs(dx) + Math.abs(dy) < MIN_VISIBLE_MOVEMENT_PX) {
            return;
        }
        revealCursor();
        float inset = dp(8f);
        targetX = clamp(targetX + dx, inset, Math.max(inset, getWidth() - inset));
        targetY = clamp(targetY + dy, inset, Math.max(inset, getHeight() - inset));
        if (!moveAnimationRunning) {
            moveAnimationRunning = true;
            removeCallbacks(moveAnimationFrame);
            post(moveAnimationFrame);
        }
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

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        if (cursorX < 0f || cursorY < 0f || oldWidth <= 0 || oldHeight <= 0) {
            cursorX = width / 2f;
            cursorY = height / 2f;
            targetX = cursorX;
            targetY = cursorY;
        } else {
            cursorX = width * cursorX / oldWidth;
            cursorY = height * cursorY / oldHeight;
            targetX = width * targetX / oldWidth;
            targetY = height * targetY / oldHeight;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        ensurePosition();
        if (!cursorVisible) {
            return;
        }
        float scale = getResources().getDisplayMetrics().density;
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
            pulse.setStrokeWidth(dp(2f));
            canvas.drawCircle(x, y, dp(15f), pulse);
            pulse.setColor(0xff111111);
            pulse.setStrokeWidth(dp(1.5f));
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        moveAnimationRunning = false;
        removeCallbacks(moveAnimationFrame);
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
            cursorVisible = false;
        }
    }

    private void revealCursor() {
        cursorVisible = true;
        removeCallbacks(hideIdleCursor);
        postDelayed(hideIdleCursor, CURSOR_IDLE_TIMEOUT_MS);
        invalidate();
    }

    private void ensurePosition() {
        if (cursorX < 0f || cursorY < 0f) {
            cursorX = getWidth() / 2f;
            cursorY = getHeight() / 2f;
        }
        if (targetX < 0f || targetY < 0f) {
            targetX = cursorX;
            targetY = cursorY;
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
