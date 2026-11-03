package xiao.bu.tv;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import xiao.bu.tv.skin.SharedTvUi;

/** Remote digits or a DPAD keypad; no IME, saved password, or playback ownership. */
final class GroupPasswordDialog extends Dialog {
    interface Unlock { boolean submit(String value); }
    private final LinearLayout body;
    private final TextView title, description, input, status, hint;
    private final List<TextView> keys = new ArrayList<>();
    private final StringBuilder digits = new StringBuilder();
    private final Unlock unlock;
    private final Activity activity;
    private final int length;
    private final float unit;
    private TextView confirm;
    private Bundle palette;

    GroupPasswordDialog(Activity activity, String group, int length, Unlock unlock) {
        super(activity);
        this.activity=activity;
        this.length = length;
        this.unlock = unlock;
        float scale = activity instanceof SharedTvUi.Viewport
                ? ((SharedTvUi.Viewport) activity).themeScale() : activity.getResources().getDisplayMetrics().density;
        unit = Math.max(.1f, Math.min(scale, Math.min(
                activity.getResources().getDisplayMetrics().widthPixels / 400f,
                activity.getResources().getDisplayMetrics().heightPixels / 390f)));
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setCanceledOnTouchOutside(false);
        body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(px(20), px(16), px(20), px(16));
        title = text("分组密码", 22, 28);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        description = text("请输入「" + group + "」的密码", 15, 25);
        description.setSingleLine(true);
        description.setEllipsize(android.text.TextUtils.TruncateAt.END);
        input = text("", 23, 43);
        input.setTypeface(Typeface.MONOSPACE);
        updateInput();
        status = text("", 14, 23);
        String[] labels = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "删除", "0", "确认"};
        for (int row = 0; row < 4; row++) {
            LinearLayout line = new LinearLayout(activity);
            for (int column = 0; column < 3; column++) {
                final int index = row * 3 + column;
                TextView key = new TextView(activity);
                key.setText(labels[index]);
                key.setTextSize(TypedValue.COMPLEX_UNIT_PX, 18 * unit);
                key.setGravity(Gravity.CENTER);
                key.setFocusable(true);
                key.setClickable(true);
                key.setSingleLine(true);
                key.setContentDescription(index < 9 || index == 10 ? "数字 " + labels[index] : labels[index]);
                key.setOnClickListener(view -> {
                    if (index == 9) erase();
                    else if (index == 11) submit();
                    else append(labels[index].charAt(0));
                });
                LinearLayout.LayoutParams slot = new LinearLayout.LayoutParams(0, px(40), 1);
                slot.setMargins(px(3), px(3), px(3), px(3));
                line.addView(key, slot);
                keys.add(key);
                if (index == 11) confirm = key;
            }
            body.addView(line, new LinearLayout.LayoutParams(-1, -2));
        }
        hint = text("数字键输入 · 确认验证 · 返回取消", 12, 24);
        setContentView(body);
        refreshTheme();
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(.35f);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            window.getDecorView().setSystemUiVisibility(activity.getWindow().getDecorView().getSystemUiVisibility());
        }
    }

    private int px(float value) { return Math.max(1, Math.round(value * unit)); }

    private TextView text(String value, int size, int height) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * unit);
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);
        body.addView(view, new LinearLayout.LayoutParams(-1, px(height)));
        return view;
    }

    private GradientDrawable shape(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(px(9));
        return shape;
    }

    void refreshTheme() {
        TvUiStyle theme = TvUiPlugin.current();
        palette = SharedTvUi.dialogPalette(theme == null ? "builtin" : theme.id);
        GradientDrawable panel = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{palette.getInt("panelStart"), palette.getInt("panelEnd")});
        panel.setCornerRadius(px(18));
        panel.setStroke(px(1), 0x45ffffff);
        body.setBackgroundDrawable(panel);
        title.setTextColor(palette.getInt("text"));
        input.setTextColor(palette.getInt("text"));
        description.setTextColor(palette.getInt("secondary"));
        hint.setTextColor(palette.getInt("secondary"));
        status.setTextColor(palette.getInt("accent"));
        input.setBackgroundDrawable(shape(0x25000000));
        for (TextView key : keys) {
            StateListDrawable background = new StateListDrawable();
            for (int state : new int[]{android.R.attr.state_pressed, android.R.attr.state_focused}) {
                GradientDrawable focus = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        new int[]{palette.getInt("focusStart"), palette.getInt("focusEnd")});
                focus.setCornerRadius(px(9));
                background.addState(new int[]{state}, focus);
            }
            background.addState(new int[]{}, shape(0x25ffffff));
            key.setBackgroundDrawable(background);
            key.setTextColor(new ColorStateList(new int[][]{
                    {android.R.attr.state_pressed}, {android.R.attr.state_focused}, {}},
                    new int[]{palette.getInt("focusText"), palette.getInt("focusText"), palette.getInt("text")}));
        }
    }

    @Override public void show() {
        super.show();
        if (getWindow() != null) getWindow().setLayout(px(360), -2);
        confirm.requestFocus();
    }

    private void append(char digit) {
        if (digits.length() >= length) return;
        digits.append(digit);
        status.setText("");
        updateInput();
        if (digits.length() == length) confirm.requestFocus();
    }

    private void erase() {
        if (digits.length() > 0) digits.deleteCharAt(digits.length() - 1);
        status.setText("");
        updateInput();
    }

    private void updateInput() {
        StringBuilder masked = new StringBuilder();
        for (int i = 0; i < Math.min(digits.length(), 16); i++) masked.append('●');
        if (digits.length() > 16) masked.append('…');
        input.setText(digits.length() == 0 ? "请输入 " + length + " 位数字密码" : masked.toString());
        input.setContentDescription("已输入 " + digits.length() + " 位，共 " + length + " 位密码");
    }

    private void submit() {
        if (digits.length() == 0) { status.setText("请先输入密码"); return; }
        if (unlock.submit(digits.toString())) dismiss();
        else {
            digits.setLength(0);
            updateInput();
            status.setText("密码错误，请重试");
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        int digit = key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9 ? key - KeyEvent.KEYCODE_0
                : key >= KeyEvent.KEYCODE_NUMPAD_0 && key <= KeyEvent.KEYCODE_NUMPAD_9 ? key - KeyEvent.KEYCODE_NUMPAD_0 : -1;
        if (digit >= 0 || key == KeyEvent.KEYCODE_DEL || key == KeyEvent.KEYCODE_FORWARD_DEL
                || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (digit >= 0) {
                    confirm.requestFocus(); append((char) ('0' + digit));
                }
                else if (key == KeyEvent.KEYCODE_DEL || key == KeyEvent.KEYCODE_FORWARD_DEL) erase();
                else {
                    submit();
                    if(!isShowing()&&activity instanceof MainActivity)((MainActivity)activity).retainPasswordKeyRelease(key);
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public void dismiss() { digits.setLength(0); super.dismiss(); }
}
