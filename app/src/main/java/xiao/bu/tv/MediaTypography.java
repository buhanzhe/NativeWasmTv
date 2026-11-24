package xiao.bu.tv;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.TypefaceSpan;

/** Song-style film titles, with episode labels on the same baseline in sans serif. */
final class MediaTypography {
    private static Typeface display;
    static synchronized Typeface display(Context context) {
        if (display == null) {
            try { display = Typeface.createFromAsset(context.getAssets(), "fonts/arphic-gbsn00lp.ttf"); }
            catch (RuntimeException unavailable) { display = Typeface.SERIF; }
        }
        return display;
    }
    static CharSequence title(String value) {
        SpannableString text = new SpannableString(value);
        int separator = value.lastIndexOf(" · ");
        if (separator > 0) {
            text.setSpan(new RelativeSizeSpan(.58f), separator, value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new TypefaceSpan("sans-serif"), separator, value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }
}
