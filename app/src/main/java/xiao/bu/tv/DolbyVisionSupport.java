package xiao.bu.tv;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.util.Log;
import java.util.Locale;

final class DolbyVisionSupport {
    private DolbyVisionSupport() {}

    static String selectDecoder(int profile, int level) {
        if (Build.VERSION.SDK_INT < 21 || profile <= 0 || level <= 0) return null;
        try {
            for (int index = 0; index < MediaCodecList.getCodecCount(); index++) {
                MediaCodecInfo codec = MediaCodecList.getCodecInfoAt(index);
                String name = codec.getName().toLowerCase(Locale.US);
                if (codec.isEncoder() || name.startsWith("omx.google.") || name.startsWith("c2.android.")
                        || name.endsWith(".secure") || name.endsWith(".tunneled")) continue;
                for (String type : codec.getSupportedTypes()) {
                    if (!"video/dolby-vision".equalsIgnoreCase(type)) continue;
                    try {
                        for (MediaCodecInfo.CodecProfileLevel support : codec.getCapabilitiesForType(type).profileLevels) {
                            if (support.profile == profile && support.level >= level) {
                                Log.i("nTvDolby", "Dolby Vision decoder=" + codec.getName()
                                        + " profile=" + profile + " level=" + level);
                                return codec.getName();
                            }
                        }
                    } catch (RuntimeException ignored) {}
                }
            }
        } catch (RuntimeException error) {
            Log.w("nTvDolby", "Cannot query Dolby Vision decoder", error);
        }
        Log.w("nTvDolby", "No Dolby Vision decoder for profile=" + profile + " level=" + level);
        return null;
    }
}
