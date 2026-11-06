package xiao.bu.tv;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import xiao.bu.tv.AppLog;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

final class DolbyVisionSupport {
    private static final ConcurrentHashMap<String, Boolean> playlistSupport =
            new ConcurrentHashMap<String, Boolean>();
    private DolbyVisionSupport() {}

    static boolean supportsPlaylistCodec(String codecs) {
        if (!DolbyFormats.isDolbyVision(codecs)) return true;
        for (String raw : codecs.toLowerCase(Locale.US).split(",")) {
            String codec = raw.trim();
            if (!DolbyFormats.isDolbyVision(codec)) continue;
            Boolean cached = playlistSupport.get(codec);
            if (cached != null) return cached;
            boolean supported = false;
            String[] fields = codec.split("\\.");
            try {
                int profile = Integer.parseInt(fields[1]);
                int level = Integer.parseInt(fields[2]);
                supported = profile >= 0 && profile <= 9 && level >= 1 && level <= 13
                        && selectDecoder(1 << profile, 1 << (level - 1)) != null;
            } catch (RuntimeException ignored) { }
            playlistSupport.put(codec, supported);
            return supported;
        }
        return false;
    }

    private static volatile Boolean atmosDecoderSupported;
    static boolean supportsAtmosDecoder() {
        if (Build.VERSION.SDK_INT < 23) return false;
        if (atmosDecoderSupported != null) return atmosDecoderSupported.booleanValue();
        try {
            for (int i = 0; i < MediaCodecList.getCodecCount(); i++) {
                MediaCodecInfo codec = MediaCodecList.getCodecInfoAt(i);
                if (codec.isEncoder()) continue;
                for (String type : codec.getSupportedTypes())
                    if ("audio/eac3-joc".equalsIgnoreCase(type) || "audio/eac3".equalsIgnoreCase(type)) {
                        atmosDecoderSupported = Boolean.TRUE; return true;
                    }
            }
        } catch (RuntimeException error) { AppLog.w("nTvDolby", "Cannot query Atmos decoder", error); }
        atmosDecoderSupported = Boolean.FALSE;
        return false;
    }

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
                                AppLog.i("nTvDolby", "Dolby Vision decoder=" + codec.getName()
                                        + " profile=" + profile + " level=" + level);
                                return codec.getName();
                            }
                        }
                    } catch (RuntimeException ignored) {}
                }
            }
        } catch (RuntimeException error) {
            AppLog.w("nTvDolby", "Cannot query Dolby Vision decoder", error);
        }
        AppLog.w("nTvDolby", "No Dolby Vision decoder for profile=" + profile + " level=" + level);
        return null;
    }
}
