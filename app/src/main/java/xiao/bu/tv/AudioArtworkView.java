package xiao.bu.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.util.AttributeSet;
import android.view.View;

/** Compose the record once; animation rotates a texture instead of clipping a shader every frame. */
public final class AudioArtworkView extends FrameLayout {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint background = new Paint();
    private final RectF disc = new RectF();
    private Bitmap vinyl, cover, record;
    private int coverRevision;
    private String title = "", kind = "音乐";
    private float textSize;
    private final ImageView recordView;
    private ObjectAnimator rotation;
    private boolean playing, active = true, attached;
    public AudioArtworkView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        recordView = new ImageView(context);
        recordView.setScaleType(ImageView.ScaleType.FIT_XY);
        if (android.os.Build.VERSION.SDK_INT >= 16)
            recordView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(recordView, new FrameLayout.LayoutParams(1, 1));
    }

    void show(String name, boolean live) {
        if (vinyl == null) vinyl = BitmapFactory.decodeResource(getResources(), R.drawable.vinyl_record);
        title = name == null ? "" : name;
        kind = live ? "音乐电台 · 直播" : "音乐";
        recordView.setRotation(0f);
        setCover(null);
        setVisibility(VISIBLE);
        invalidate();
        updateAnimation();
    }

    void clear() {
        playing = false;
        updateAnimation();
        setVisibility(GONE);
        cover = null;
        coverRevision++;
        vinyl = null;
        record = null;
        recordView.setImageDrawable(null);
    }

    void setCover(Bitmap value) {
        cover = value;
        coverRevision++;
        composeRecord();
        invalidate();
    }

    void setPlaying(boolean value) {
        if (playing == value) return;
        playing = value;
        updateAnimation();
    }

    void setActive(boolean value) { active = value; updateAnimation(); }

    Bitmap cover() { return cover; }
    int coverRevision() { return coverRevision; }

    private boolean shouldRotate() { return playing && active && attached && isShown() && getWindowVisibility() == VISIBLE; }
    private void updateAnimation() {
        if (shouldRotate()) {
            if (rotation != null) return;
            float start = recordView.getRotation() % 360f;
            recordView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            rotation = ObjectAnimator.ofFloat(recordView, "rotation", start, start + 360f);
            rotation.setDuration(30000L);
            rotation.setRepeatCount(ValueAnimator.INFINITE);
            rotation.setInterpolator(new LinearInterpolator());
            rotation.start();
        } else if (rotation != null) {
            rotation.cancel();
            rotation = null;
            recordView.setLayerType(View.LAYER_TYPE_NONE, null);
        }
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); attached = true; updateAnimation(); }
    @Override protected void onDetachedFromWindow() { attached = false; updateAnimation(); super.onDetachedFromWindow(); }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); updateAnimation(); }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float size = Math.min(w * 0.68f, h * 0.68f);
        float top = (h - size) * 0.31f;
        disc.set((w - size) / 2, top, (w + size) / 2, top + size);
        textSize = Math.min(w * 0.045f, h * 0.04f);
        background.setShader(new LinearGradient(0, 0, w, h,
                new int[] {0xff203b40, 0xff102328, 0xff070b10}, null, Shader.TileMode.CLAMP));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        int size = Math.round(Math.min(getMeasuredWidth(), getMeasuredHeight()) * 0.68f);
        int exact = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
        recordView.measure(exact, exact);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int x = Math.round(disc.left), y = Math.round(disc.top);
        recordView.layout(x, y, x + recordView.getMeasuredWidth(), y + recordView.getMeasuredHeight());
    }

    private void composeRecord() {
        if (vinyl == null) { record = null; return; }
        // Android 4.4's hardware Canvas can leave polygonal/ragged edges on a rotating
        // bitmap-shader circle. Rasterize its antialiased edge with a software Canvas
        // only when the cover changes; keep the window and animation hardware accelerated.
        Bitmap composed = Bitmap.createBitmap(vinyl.getWidth(), vinyl.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(composed);
        Paint label = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        float cx = composed.getWidth() / 2f, cy = composed.getHeight() / 2f;
        float radius = composed.getWidth() * 0.245f;
        canvas.drawBitmap(vinyl, 0, 0, label);
        if (cover != null) {
            float scale = radius * 2 / Math.min(cover.getWidth(), cover.getHeight());
            BitmapShader shader = new BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix matrix = new Matrix();
            matrix.setScale(scale, scale);
            matrix.postTranslate(cx - cover.getWidth() * scale / 2, cy - cover.getHeight() * scale / 2);
            shader.setLocalMatrix(matrix);
            label.setShader(shader);
        } else label.setColor(0xffb96d4c);
        canvas.drawCircle(cx, cy, radius, label);
        label.setShader(null);
        label.setColor(0x55999999);
        label.setStyle(Paint.Style.STROKE);
        label.setStrokeWidth(1);
        canvas.drawCircle(cx, cy, radius, label);
        label.setStyle(Paint.Style.FILL);
        label.setColor(0xff0a1519);
        canvas.drawCircle(cx, cy, composed.getWidth() * 0.011f, label);
        // Never recycle a bitmap still referenced by the render thread's display list.
        record = composed;
        recordView.setImageBitmap(composed);
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), background);
        if (record == null) return;
        float cx = disc.centerX();
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(textSize);
        paint.setColor(0xfff2f5f4);
        float width = paint.measureText(title);
        if (width > getWidth() * 0.84f) paint.setTextSize(textSize * getWidth() * 0.84f / width);
        canvas.drawText(title, cx, disc.bottom + textSize * 1.35f, paint);
        paint.setTextSize(textSize * 0.53f);
        paint.setColor(0xff9cafb0);
        canvas.drawText(kind, cx, disc.bottom + textSize * 2.4f, paint);
    }
}
