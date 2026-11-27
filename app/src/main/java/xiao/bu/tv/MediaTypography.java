package xiao.bu.tv;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;

/** Keep the episode label smaller while using the same system font. */
final class MediaTypography {
    static CharSequence title(String value) {
        SpannableString text = new SpannableString(value);
        int separator = value.lastIndexOf(" · ");
        if (separator > 0) {
            text.setSpan(new RelativeSizeSpan(.58f), separator, value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }
}
