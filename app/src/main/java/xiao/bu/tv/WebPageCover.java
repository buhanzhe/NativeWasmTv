package xiao.bu.tv;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Opaque presentation only: the renderer remains attached and keeps loading underneath. */
final class WebPageCover extends FrameLayout {
    private final LinearLayout content;
    private final TextView title, address, reveal;
    private final LinearLayout status;
    private final LoadingSpinnerView spinner;
    private float scale = 1f;
    private boolean black;
    private final OnClickListener onReveal;

    // The visual pixels may be opaque, but never let the View hierarchy cull
    // Chromium's underlying draw/timer work as an occluded opaque sibling.
    @Override public boolean isOpaque() { return false; }

    void setBlack(boolean value) {
        black=value;
        if(value)setBackgroundColor(android.graphics.Color.BLACK);
        else setBackgroundDrawable(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff101d30,0xff080e18}));
        getChildAt(0).setVisibility(value?GONE:VISIBLE);
        setContentDescription(value?"纯黑遮罩，点击显示网页":null);
    }

    WebPageCover(Context context, OnClickListener onReveal) {
        super(context);
        this.onReveal=onReveal;
        setBackgroundDrawable(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff101d30, 0xff080e18}));
        setClickable(true);
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout center = new LinearLayout(context);
        center.setGravity(Gravity.CENTER);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        center.addView(content, new LinearLayout.LayoutParams(1, -2));
        scroll.addView(center, new ScrollView.LayoutParams(-1, -2));
        addView(scroll, new LayoutParams(-1, -1));
        status = new LinearLayout(context);
        status.setGravity(Gravity.CENTER);
        content.addView(status);
        spinner = new LoadingSpinnerView(context);
        status.addView(spinner);
        title = text("网页加载中…", 0xfff3f7ff);
        title.setTypeface(null, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        status.addView(title);
        address = text("", 0xffb9cbe2);
        address.setSingleLine(true);
        address.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        if (android.os.Build.VERSION.SDK_INT >= 17)
            address.setTextDirection(View.TEXT_DIRECTION_LTR);
        content.addView(address);
        reveal = text("显示网页", 0xffffffff);
        reveal.setClickable(true);
        reveal.setFocusable(true);
        reveal.setOnClickListener(onReveal);
        content.addView(reveal);
        setInterfaceScale(1f);
    }

    private TextView text(String value, int color) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    void update(String url, boolean loading, String error) {
        address.setText(url == null ? "" : url);
        title.setText(error != null ? "网页加载失败" : loading ? "网页加载中…" : "网页已加载");
        title.setContentDescription(error != null ? "网页加载失败：" + error : title.getText());
        spinner.setVisibility(loading && error == null ? VISIBLE : GONE);
    }

    void focusReveal() { if(!black)reveal.requestFocus(); }

    void setInterfaceScale(float value) {
        scale = value;
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20 * scale);
        address.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13 * scale);
        reveal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14 * scale);
        content.setPadding(dp(12), dp(20), dp(12), dp(20));
        status.setLayoutParams(row(-1, dp(40), 0));
        LinearLayout.LayoutParams spinnerBounds = new LinearLayout.LayoutParams(dp(22), dp(22));
        spinnerBounds.rightMargin = dp(12);
        spinner.setLayoutParams(spinnerBounds);
        LinearLayout.LayoutParams titleBounds = new LinearLayout.LayoutParams(-2, -2);
        title.setLayoutParams(titleBounds);
        address.setLayoutParams(row(-1, dp(32), 12));
        reveal.setLayoutParams(row(dp(148), dp(44), 24));
        StateListDrawable button = new StateListDrawable();
        button.addState(new int[]{android.R.attr.state_pressed}, round(0xff75b6ff));
        button.addState(new int[]{android.R.attr.state_focused}, round(0xff549dff));
        button.addState(new int[]{}, round(0xff2479e8));
        reveal.setBackgroundDrawable(button);
        requestLayout();
    }

    private GradientDrawable round(int color) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(12));
        return background;
    }

    private LinearLayout.LayoutParams row(int width, int height, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.topMargin = dp(top);
        return params;
    }

    private int dp(float value) {
        return Math.max(1, Math.round(value * scale * getResources().getDisplayMetrics().density));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        content.getLayoutParams().width = Math.max(1,
                Math.min(dp(540), MeasureSpec.getSize(widthSpec) - dp(32)));
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if(black && event.getActionMasked()==MotionEvent.ACTION_UP){performClick();onReveal.onClick(this);}
        else if(!black)super.dispatchTouchEvent(event);
        return true;
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        super.dispatchGenericMotionEvent(event);
        return true;
    }
}
