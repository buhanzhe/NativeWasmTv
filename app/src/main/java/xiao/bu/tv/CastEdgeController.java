package xiao.bu.tv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small native overlay drawn with the WebView and therefore included in casts. */
final class CastEdgeController extends FrameLayout {
    interface Callback {
        void onStartCast();
        void onPreviousChannel();
        void onNextChannel();
        void onStopCast();
    }

    private final ImageButton bubble;
    private final LinearLayout panel;
    private final TextView title;
    private final TextView status;
    private final TextView previous;
    private final TextView next;
    private final TextView stop;
    private Callback callback;
    private boolean available;
    private boolean casting;
    private boolean busy;
    private float castVisualScale = 1f;

    CastEdgeController(Context context) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);
        // Consume empty space around/between child actions as part of the overlay.
        setClickable(true);

        bubble = new ImageButton(context);
        bubble.setImageDrawable(new CastIconDrawable(dp(24)));
        bubble.setScaleType(ImageButton.ScaleType.FIT_CENTER);
        bubble.setPadding(dp(13), dp(13), dp(13), dp(13));
        bubble.setBackgroundDrawable(round(0xe6232328, 26));
        bubble.setContentDescription("投屏");
        if (Build.VERSION.SDK_INT >= 21) bubble.setElevation(dp(10));
        LayoutParams bubbleParams = new LayoutParams(dp(52), dp(52),
                Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        addView(bubble, bubbleParams);

        panel = new LinearLayout(context);
        panel.setClickable(true);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(12), dp(12), dp(12));
        panel.setBackgroundDrawable(round(0xf5f7f7f9, 22));
        if (Build.VERSION.SDK_INT >= 21) panel.setElevation(dp(12));
        LayoutParams panelParams = new LayoutParams(dp(282), LayoutParams.WRAP_CONTENT,
                Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        addView(panel, panelParams);

        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(heading, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout copy = new LinearLayout(context);
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f);
        heading.addView(copy, copyParams);

        status = label("正在投屏", 12, 0xff007aff, true);
        copy.addView(status);
        title = label("当前频道", 16, 0xff17171a, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(2);
        copy.addView(title, titleParams);

        TextView close = action("×", "收起投屏控制", 0xfff7f7f9, 0xff55555c);
        heading.addView(close, new LinearLayout.LayoutParams(dp(38), dp(38)));
        close.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { setExpanded(false); }
        });

        LinearLayout actions = new LinearLayout(context);
        actions.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams actionRow = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(48));
        actionRow.topMargin = dp(10);
        panel.addView(actions, actionRow);

        previous = action("‹", "上一个频道", 0xffe7e7eb, 0xff151518);
        next = action("›", "下一个频道", 0xffe7e7eb, 0xff151518);
        stop = action("结束", "结束投屏", 0xffffe8e7, 0xffff3b30);
        LinearLayout.LayoutParams control = new LinearLayout.LayoutParams(0,
                LayoutParams.MATCH_PARENT, 1f);
        control.rightMargin = dp(7);
        actions.addView(previous, control);
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(0,
                LayoutParams.MATCH_PARENT, 1f);
        nextParams.rightMargin = dp(7);
        actions.addView(next, nextParams);
        actions.addView(stop, new LinearLayout.LayoutParams(0,
                LayoutParams.MATCH_PARENT, 1.25f));

        bubble.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (busy) return;
                if (casting) setExpanded(true);
                else if (callback != null) callback.onStartCast();
            }
        });
        previous.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (!busy && callback != null) callback.onPreviousChannel();
            }
        });
        next.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (!busy && callback != null) callback.onNextChannel();
            }
        });
        stop.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (!busy && callback != null) callback.onStopCast();
            }
        });
        setState(false, false, false, "");
    }

    void setCallback(Callback value) {
        callback = value;
    }

    /** Keep the final TV size equal to the 4K baseline at every cast resolution. */
    void setCastVisualScale(float scale) {
        float next = Math.max(0.25f, Math.min(1f, scale));
        if (Math.abs(next - castVisualScale) < 0.001f) return;
        castVisualScale = next;
        applyVisualScale();
    }

    @Override protected void onSizeChanged(int width, int height,
            int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        applyVisualScale();
    }

    private void applyVisualScale() {
        setPivotX(getWidth());
        setPivotY(getHeight() / 2f);
        setScaleX(castVisualScale);
        setScaleY(castVisualScale);
    }

    void setState(boolean nextAvailable, boolean nextCasting,
            boolean nextBusy, String contentName) {
        available = nextAvailable;
        casting = nextCasting;
        busy = nextBusy;
        title.setText(TextUtils.isEmpty(contentName) ? "当前内容" : contentName);
        status.setText(busy ? (casting ? "正在同步…" : "正在连接电视…") : "正在投屏");
        bubble.setAlpha(busy ? 0.58f : 1f);
        bubble.setEnabled(!busy);
        previous.setEnabled(!busy);
        next.setEnabled(!busy);
        stop.setEnabled(!busy);
        bubble.setContentDescription(casting ? "打开投屏控制" : "投屏到电视");
        if (!available) {
            setVisibility(GONE);
            setExpanded(false);
        } else {
            setVisibility(VISIBLE);
            if (busy && casting) setExpanded(true);
            else if (!casting) setExpanded(false);
        }
    }

    private void setExpanded(boolean expanded) {
        panel.setVisibility(expanded && casting ? VISIBLE : GONE);
        bubble.setVisibility(expanded && casting ? GONE : VISIBLE);
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView value = new TextView(getContext());
        value.setText(text);
        value.setTextSize(sp);
        value.setTextColor(color);
        if (bold) value.setTypeface(value.getTypeface(), android.graphics.Typeface.BOLD);
        return value;
    }

    private TextView action(String text, String description, int background, int color) {
        TextView value = label(text, "结束".equals(text) ? 13 : 28, color, true);
        value.setGravity(Gravity.CENTER);
        value.setBackgroundDrawable(round(background, 15));
        value.setContentDescription(description);
        return value;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable value = new GradientDrawable();
        value.setColor(color);
        value.setCornerRadius(dp(radiusDp));
        return value;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
