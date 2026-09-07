package xiao.bu.tv;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Google Material "cast" geometry, redrawn as paths for pre-Lollipop devices. */
final class CastIconDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int intrinsicSize;
    private int alpha = 255;

    CastIconDrawable(int intrinsicSize) {
        this.intrinsicSize = intrinsicSize;
        paint.setColor(0xffffffff);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override public void draw(Canvas canvas) {
        float width = getBounds().width();
        float height = getBounds().height();
        if (width <= 0f || height <= 0f) return;
        int save = canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(width / 24f, height / 24f);
        paint.setAlpha(alpha);
        paint.setStrokeWidth(2f);

        Path screen = new Path();
        screen.moveTo(3f, 8f);
        screen.lineTo(3f, 5.5f);
        screen.quadTo(3f, 3.5f, 5f, 3.5f);
        screen.lineTo(19f, 3.5f);
        screen.quadTo(21f, 3.5f, 21f, 5.5f);
        screen.lineTo(21f, 17.5f);
        screen.quadTo(21f, 19.5f, 19f, 19.5f);
        screen.lineTo(15.5f, 19.5f);
        canvas.drawPath(screen, paint);

        Path waves = new Path();
        waves.moveTo(2.5f, 15.5f);
        waves.cubicTo(6.1f, 15.5f, 9f, 18.4f, 9f, 22f);
        waves.moveTo(2.5f, 11f);
        waves.cubicTo(8.6f, 11f, 13.5f, 15.9f, 13.5f, 22f);
        canvas.drawPath(waves, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(3.2f, 20.8f, 1.55f, paint);
        paint.setStyle(Paint.Style.STROKE);
        canvas.restoreToCount(save);
    }

    @Override public void setAlpha(int value) {
        alpha = value;
        invalidateSelf();
    }

    @Override public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
        invalidateSelf();
    }

    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override public int getIntrinsicWidth() {
        return intrinsicSize;
    }

    @Override public int getIntrinsicHeight() {
        return intrinsicSize;
    }
}
