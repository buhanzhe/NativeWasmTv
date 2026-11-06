package xiao.bu.tv;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import xiao.bu.tv.AppLog;

/** Called once at native codec configuration, never in the frame loop. */
public final class IjkCodecPerformance {
    public static float configure(MediaFormat format, String name, String mime,
            int width, int height, float streamRate) {
        if (Build.VERSION.SDK_INT < 23 || streamRate < 45f || streamRate > 240f
                || width <= 0 || height <= 0) return 0f;
        try {
            for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                if (codec.isEncoder() || !codec.getName().equals(name)) continue;
                MediaCodecInfo.VideoCapabilities video = codec.getCapabilitiesForType(mime)
                        .getVideoCapabilities();
                if (video == null) return 0f;
                double maximum = video.getSupportedFrameRatesFor(width, height).getUpper();
                float rate = (float) Math.min(maximum, Math.max(60f, streamRate));
                if (rate < streamRate || !video.areSizeAndRateSupported(width, height, rate)) return 0f;
                format.setInteger(MediaFormat.KEY_FRAME_RATE, Math.round(streamRate));
                format.setFloat(MediaFormat.KEY_OPERATING_RATE, rate);
                format.setInteger(MediaFormat.KEY_PRIORITY, 0);
                AppLog.i("IjkCodecPerformance", name + " " + width + "x" + height
                        + " stream=" + streamRate + " operating=" + rate);
                return rate;
            }
        } catch (RuntimeException error) {
            AppLog.w("IjkCodecPerformance", "Codec performance hint unavailable", error);
        }
        return 0f;
    }

    private IjkCodecPerformance() {}
}
