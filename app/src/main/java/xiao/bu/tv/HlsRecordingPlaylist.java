package xiao.bu.tv;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recording uses device URLs even when the player downloads segments directly. */
final class HlsRecordingPlaylist {
    interface ResourceUrl { String register(String origin); }
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("URI=\"([^\"]+)\"");

    static String rewrite(String body, String origin, String internalPrefix,
            String publicPrefix, ResourceUrl resources) {
        URI base = URI.create(origin);
        StringBuilder result = new StringBuilder(body.length());
        String[] lines = body.replace("\r", "").split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i > 0) result.append('\n');
            if (line.startsWith("#")) {
                Matcher matcher = URI_ATTRIBUTE.matcher(line);
                StringBuffer updated = new StringBuffer();
                while (matcher.find()) {
                    matcher.appendReplacement(updated, Matcher.quoteReplacement("URI=\""
                            + local(base, matcher.group(1), internalPrefix, publicPrefix, resources)
                            + "\""));
                }
                matcher.appendTail(updated);
                result.append(updated);
            } else if (!line.trim().isEmpty()) {
                result.append(local(base, line.trim(), internalPrefix, publicPrefix, resources));
            }
        }
        return result.toString();
    }

    private static String local(URI base, String reference, String internalPrefix,
            String publicPrefix, ResourceUrl resources) {
        // Cached playlists may already contain proxy URLs. Never wrap their tokens again.
        if (reference.startsWith(internalPrefix))
            return publicPrefix + reference.substring(internalPrefix.length());
        if (reference.startsWith(publicPrefix)) return reference;
        String absolute = HlsUri.resolve(base, reference).toString();
        String registered = resources.register(absolute);
        return publicPrefix + registered.substring(internalPrefix.length());
    }
}
