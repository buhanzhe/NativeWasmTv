package xiao.bu.tv;

import android.graphics.Paint;

/** Catalog-wide title width in font-size units, independent of UI scale. */
public final class ChannelNameWidth {
    public static final int MAX_CHARS = 9;
    static final String META = "channel_name_width_v5";
    private static volatile float cached = -1;

    static float measure(ChannelCatalog.Group[] groups) {
        Paint paint = new Paint();
        paint.setTextSize(20);
        float width = 0;
        if (groups != null) for (ChannelCatalog.Group group : groups) {
            for (Channel channel : group.channels) {
                width = Math.max(width, paint.measureText(channel.name) / 20);
                if (width >= MAX_CHARS) return MAX_CHARS;
            }
        }
        return width;
    }

    static void restore(float width) { cached = Math.max(0, Math.min(MAX_CHARS, width)); }

    static float pixels(Paint paint) {
        float width = cached;
        if (width < 0) {
            // Built-in catalogs have no database snapshot; initialize once.
            restore(measure(ChannelCatalog.GROUPS));
            width = cached;
        }
        return width * paint.getTextSize();
    }
}
