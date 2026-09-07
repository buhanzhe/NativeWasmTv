package xiao.bu.tv;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** Root view that can retain an encoder-sized layout while its Activity is stopped. */
public final class CastRootLayout extends FrameLayout {
    private int castWidth;
    private int castHeight;

    public CastRootLayout(Context context) {
        this(context, null);
    }

    public CastRootLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setCastViewport(int width, int height) {
        int nextWidth = Math.max(0, width);
        int nextHeight = Math.max(0, height);
        if (castWidth == nextWidth && castHeight == nextHeight) {
            return;
        }
        castWidth = nextWidth;
        castHeight = nextHeight;
        requestLayout();
    }

    boolean hasCastViewport() {
        return castWidth > 0 && castHeight > 0;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (hasCastViewport()) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(castWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(castHeight, MeasureSpec.EXACTLY));
            setMeasuredDimension(castWidth, castHeight);
            return;
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
