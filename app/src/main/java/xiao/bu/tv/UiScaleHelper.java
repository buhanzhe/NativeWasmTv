package xiao.bu.tv;

import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;

import java.util.WeakHashMap;

/** Applies an inexpensive, reversible scale to native overlay layouts. */
final class UiScaleHelper {
    private final WeakHashMap<View, BaseValues> values =
            new WeakHashMap<View, BaseValues>();

    void apply(View view, float scale) {
        if (view == null) {
            return;
        }
        BaseValues base = values.get(view);
        if (base == null) {
            base = new BaseValues(view);
            values.put(view, base);
        }
        base.apply(view, scale);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                apply(group.getChildAt(index), scale);
            }
        }
    }

    private static int scaled(int value, float scale) {
        return Math.round(value * scale);
    }

    private static final class BaseValues {
        final int width;
        final int height;
        final int leftMargin;
        final int topMargin;
        final int rightMargin;
        final int bottomMargin;
        final boolean hasMargins;
        final int paddingLeft;
        final int paddingTop;
        final int paddingRight;
        final int paddingBottom;
        final float textSizePx;
        final boolean textView;
        final int dividerHeight;
        final boolean listView;

        BaseValues(View view) {
            ViewGroup.LayoutParams params = view.getLayoutParams();
            width = params == null ? ViewGroup.LayoutParams.WRAP_CONTENT : params.width;
            height = params == null ? ViewGroup.LayoutParams.WRAP_CONTENT : params.height;
            if (params instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                leftMargin = margins.leftMargin;
                topMargin = margins.topMargin;
                rightMargin = margins.rightMargin;
                bottomMargin = margins.bottomMargin;
                hasMargins = true;
            } else {
                leftMargin = topMargin = rightMargin = bottomMargin = 0;
                hasMargins = false;
            }
            paddingLeft = view.getPaddingLeft();
            paddingTop = view.getPaddingTop();
            paddingRight = view.getPaddingRight();
            paddingBottom = view.getPaddingBottom();
            textView = view instanceof TextView;
            textSizePx = textView ? ((TextView) view).getTextSize() : 0f;
            listView = view instanceof ListView;
            dividerHeight = listView ? ((ListView) view).getDividerHeight() : 0;
        }

        void apply(View view, float scale) {
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params != null) {
                if (width >= 0) {
                    params.width = scaled(width, scale);
                }
                if (height >= 0) {
                    params.height = scaled(height, scale);
                }
                if (hasMargins && params instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams margins =
                            (ViewGroup.MarginLayoutParams) params;
                    margins.leftMargin = scaled(leftMargin, scale);
                    margins.topMargin = scaled(topMargin, scale);
                    margins.rightMargin = scaled(rightMargin, scale);
                    margins.bottomMargin = scaled(bottomMargin, scale);
                }
                view.setLayoutParams(params);
            }
            view.setPadding(scaled(paddingLeft, scale), scaled(paddingTop, scale),
                    scaled(paddingRight, scale), scaled(paddingBottom, scale));
            if (textView) {
                ((TextView) view).setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        textSizePx * scale);
            }
            if (listView) {
                ((ListView) view).setDividerHeight(scaled(dividerHeight, scale));
            }
        }
    }
}
