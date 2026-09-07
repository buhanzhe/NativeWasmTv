package xiao.bu.tv;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** URL-scoped picture/sound choices; captions deliberately have one global preference. */
final class MediaTrackSelection {

    static final String SUBTITLE_KEY = "media_subtitle_global";
    static final String SUBTITLE_ENABLED_KEY = "media_subtitle_enabled";
    static final String DISABLED = "__disabled__";

    static String urlKey(String type, String url) {
        // Preserve path case and query parameters: they can identify different programmes.
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                (url == null ? "" : url.trim()).getBytes(Charset.forName("UTF-8"))
            );
            StringBuilder key = new StringBuilder("media_" + type + "_url_");
            for (byte value : digest) {
                key.append(Character.forDigit((value & 255) >>> 4, 16));
                key.append(Character.forDigit(value & 15, 16));
            }
            return key.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String language(String value) {
        String language = value == null ? "" : value.trim().toLowerCase(Locale.US).replace('_', '-');
        if ("und".equals(language)) return "";
        if ("eng".equals(language)) return "en";
        if ("chi".equals(language) || "zho".equals(language)) return "zh";
        if ("jpn".equals(language)) return "ja";
        if ("kor".equals(language)) return "ko";
        if ("fra".equals(language) || "fre".equals(language)) return "fr";
        if ("deu".equals(language) || "ger".equals(language)) return "de";
        if ("spa".equals(language)) return "es";
        return language;
    }

    static String signature(String language, String description) {
        // Measured bitrate is not a stable track identity across reopens.
        String stable =
            description == null ? "" : description.trim().replaceAll("[0-9.]+\\s*(?:kb/s|bit/s)", "");
        return language(language) + "\u001f" + stable;
    }

    static String subtitleChoice(String language, String description) {
        String normalized = language(language);
        return normalized.isEmpty() ? "track:" + signature("", description) : "lang:" + normalized;
    }

    static int subtitleMatch(String wanted, String language, String description) {
        String choice = subtitleChoice(language, description);
        if (choice.equals(wanted)) return 2;
        if (
            wanted.startsWith("lang:") &&
            choice.startsWith("lang:") &&
            wanted.split("-")[0].equals(choice.split("-")[0])
        ) return 1;
        return 0;
    }
}
