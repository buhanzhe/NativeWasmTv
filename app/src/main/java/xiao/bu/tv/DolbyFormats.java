package xiao.bu.tv;

import java.util.Locale;

/** Container/playlist codec declarations, not guesses based on channel names. */
final class DolbyFormats {
    private DolbyFormats() {}

    static boolean isDolbyVision(String codecs) {
        if (codecs == null) return false;
        for (String value : codecs.toLowerCase(Locale.US).split(",")) {
            String codec = value.trim();
            if (codec.startsWith("dvhe.") || codec.startsWith("dvh1.")
                    || codec.startsWith("dvav.") || codec.startsWith("dva1.")) return true;
        }
        return false;
    }

    static int audioEncoding(String codecs) {
        if (codecs == null) return 0;
        for (String value : codecs.toLowerCase(Locale.US).split(",")) {
            String codec = value.trim();
            if (codec.equals("ec-3") || codec.equals("eac3")) return 6;
            if (codec.equals("ac-3") || codec.equals("ac3")) return 5;
            if (codec.equals("mlpa") || codec.equals("truehd")) return 14;
            if (codec.startsWith("mp4a.40.") || codec.equals("aac")) return 2;
        }
        return 0;
    }

    static int audioRank(String codecs, boolean ac3Direct, boolean eac3Direct) {
        int encoding = audioEncoding(codecs);
        if (encoding == 6 && eac3Direct) return 4;
        if (encoding == 5 && ac3Direct) return 3;
        if (encoding == 2) return 2;
        return 1;
    }
}
