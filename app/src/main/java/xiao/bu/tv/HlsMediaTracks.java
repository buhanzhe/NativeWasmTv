package xiao.bu.tv;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HLS rendition metadata and WebVTT timing, independent of FFmpeg/Android. */
final class HlsMediaTracks {

    static final int SUBTITLE_BASE = 1000000;
    static final int VIDEO_BASE = 2000000;
    private static final Pattern ATTRIBUTE = Pattern.compile("([A-Z0-9-]+)=(?:\"([^\"]*)\"|([^,]*))");
    private static final Pattern CUE = Pattern.compile(
        "((?:\\d+:)?\\d{2}:\\d{2}[.,]\\d{3})\\s+-->\\s+((?:\\d+:)?\\d{2}:\\d{2}[.,]\\d{3})"
    );

    static final class Track {

        final String url, name, language, info, group;
        final boolean defaultTrack;

        Track(String url, String name, String language, String info, String group, boolean defaultTrack) {
            this.url = url;
            this.name = name;
            this.language = language;
            this.info = info;
            this.group = group;
            this.defaultTrack = defaultTrack;
        }

        String signature() {
            return "hls:" + group + "\u001f" + language + "\u001f" + name;
        }
    }

    static final class Manifest {

        final String url;
        final List<Track> videos = new ArrayList<Track>();
        final List<Track> subtitles = new ArrayList<Track>();
        String selectedVideoUrl = "";

        Manifest(String url) {
            this.url = url;
        }
    }

    static Map<String, String> attributes(String line) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        Matcher matcher = ATTRIBUTE.matcher(line);
        while (matcher.find())
            values.put(matcher.group(1), matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
        return values;
    }

    static boolean referencesRendition(String variant, String rendition) {
        Map<String, String> media = attributes(rendition);
        String group = media.get("GROUP-ID");
        return group != null && group.equals(attributes(variant).get(media.get("TYPE")));
    }

    private static String value(Map<String, String> map, String key) {
        String value = map.get(key);
        return value == null ? "" : value;
    }

    static String resolve(String base, String reference) {
        try {
            URI uri = URI.create(base).resolve(reference.trim());
            return "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())
                ? uri.toString()
                : "";
        } catch (IllegalArgumentException error) {
            return "";
        }
    }

    static Manifest parseMaster(String url, String text) {
        Manifest result = new Manifest(url);
        Map<String, Boolean> seenVideo = new LinkedHashMap<String, Boolean>();
        String pending = null;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-MEDIA:")) {
                Map<String, String> a = attributes(line);
                String target = resolve(url, value(a, "URI"));
                if ("SUBTITLES".equals(a.get("TYPE")) && a.containsKey("URI") && !target.isEmpty()) {
                    result.subtitles.add(
                        new Track(
                            target,
                            value(a, "NAME"),
                            value(a, "LANGUAGE"),
                            "WebVTT",
                            value(a, "GROUP-ID"),
                            "YES".equals(a.get("DEFAULT"))
                        )
                    );
                }
            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                pending = line;
            } else if (!line.isEmpty() && !line.startsWith("#") && pending != null) {
                Map<String, String> a = attributes(pending);
                String target = resolve(url, line);
                if (!target.isEmpty() && !seenVideo.containsKey(target)) {
                    String info = value(a, "RESOLUTION") + " · " + value(a, "CODECS");
                    if (a.containsKey("FRAME-RATE")) info += " · " + a.get("FRAME-RATE") + " fps";
                    if (a.containsKey("BANDWIDTH")) info += " · " + a.get("BANDWIDTH") + " bps";
                    result.videos.add(
                        new Track(target, videoLabel(a), "", info, value(a, "SUBTITLES"), false)
                    );
                    seenVideo.put(target, true);
                }
                pending = null;
            }
        }
        return result;
    }

    static String videoLabel(Map<String, String> attributes) {
        List<String> parts = new ArrayList<String>();
        String resolution = value(attributes, "RESOLUTION");
        if (!resolution.isEmpty()) parts.add(resolution.replace('x', '×'));
        String codec = videoCodec(value(attributes, "CODECS"));
        if (!codec.isEmpty()) parts.add(codec);
        try {
            double fps = Double.parseDouble(value(attributes, "FRAME-RATE"));
            if (fps > 0 && !Double.isInfinite(fps)) parts.add(String.format(Locale.US, "%.1f fps", fps));
        } catch (NumberFormatException ignored) {}
        try {
            double bitrate = Double.parseDouble(value(attributes, "BANDWIDTH"));
            if (bitrate > 0 && !Double.isInfinite(bitrate)) parts.add(
                bitrate >= 1000000
                    ? String.format(Locale.US, "%.1f Mbps", bitrate / 1000000)
                    : String.format(Locale.US, "%.0f kbps", bitrate / 1000)
            );
        } catch (NumberFormatException ignored) {}
        StringBuilder label = new StringBuilder();
        for (String part : parts) {
            if (label.length() > 0) label.append(' ');
            label.append(part);
        }
        return label.length() > 0 ? label.toString() : "视频轨道";
    }

    private static String videoCodec(String codecs) {
        // DV codec strings may follow the HEVC compatibility codec in CODECS.
        if (DolbyFormats.isDolbyVision(codecs)) return "Dolby Vision";
        for (String raw : codecs.toLowerCase(Locale.US).split(",")) {
            String codec = raw.trim();
            if (codec.startsWith("avc") || codec.equals("h264")) return "h264";
            if (codec.startsWith("hvc") || codec.startsWith("hev") || codec.equals("h265")) return "h265";
            if (codec.startsWith("av01")) return "av1";
            if (codec.startsWith("vp09") || codec.equals("vp9")) return "vp9";
            if (codec.startsWith("vp08") || codec.equals("vp8")) return "vp8";
            if (codec.startsWith("mp4v")) return "mpeg4";
        }
        return "";
    }

    static final class Segment {

        final String url;
        final long sequence, startMs, durationMs;

        Segment(String url, long sequence, long startMs, long durationMs) {
            this.url = url;
            this.sequence = sequence;
            this.startMs = startMs;
            this.durationMs = durationMs;
        }
    }

    static final class Playlist {

        final List<Segment> segments = new ArrayList<Segment>();
        boolean finite;
        long targetMs = 6000;

        Segment at(long position) {
            for (Segment s : segments)
                if (position >= s.startMs && position < s.startMs + s.durationMs) return s;
            return null;
        }
    }

    static Playlist parsePlaylist(String url, String text, Playlist previous) {
        Playlist result = new Playlist();
        long sequence = 0,
            start = 0,
            duration = 0;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            try {
                if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) sequence = Long.parseLong(line.substring(22));
                else if (line.startsWith("#EXT-X-TARGETDURATION:")) result.targetMs =
                    (long) (Double.parseDouble(line.substring(22)) * 1000);
                else if (line.startsWith("#EXTINF:")) duration = (long) (Double.parseDouble(
                    line.substring(8).split(",")[0]
                ) * 1000);
                else if (line.equals("#EXT-X-ENDLIST")) result.finite = true;
                else if (!line.isEmpty() && !line.startsWith("#") && duration > 0) {
                    String target = resolve(url, line);
                    if (!target.isEmpty()) result.segments.add(
                        new Segment(target, sequence++, start, duration)
                    );
                    start += duration;
                    duration = 0;
                }
            } catch (NumberFormatException ignored) {}
        }
        if (previous != null && !previous.segments.isEmpty() && !result.segments.isEmpty()) {
            long offset = 0;
            Segment first = result.segments.get(0);
            for (Segment old : previous.segments)
                if (old.sequence == first.sequence) {
                    offset = old.startMs;
                    break;
                }
            if (
                offset == 0 && first.sequence > previous.segments.get(previous.segments.size() - 1).sequence
            ) {
                Segment last = previous.segments.get(previous.segments.size() - 1);
                offset =
                    last.startMs + last.durationMs + (first.sequence - last.sequence - 1) * result.targetMs;
            }
            for (int i = 0; i < result.segments.size(); i++) {
                Segment s = result.segments.get(i);
                result.segments.set(i, new Segment(s.url, s.sequence, s.startMs + offset, s.durationMs));
            }
        }
        return result;
    }

    static final class Cue {

        final long startMs, endMs;
        final String text;

        Cue(long startMs, long endMs, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
        }
    }

    static final class Vtt {

        final List<Cue> cues = new ArrayList<Cue>();
        Long timestampOffsetMs;
    }

    static long timeMs(String timestamp) {
        double seconds = 0;
        for (String part : timestamp.split(":")) seconds = seconds * 60 + Double.parseDouble(part.replace(',', '.'));
        return Math.round(seconds * 1000);
    }

    static Vtt parseVtt(String text) {
        Vtt result = new Vtt();
        String[] lines = text.replace("\uFEFF", "").split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("X-TIMESTAMP-MAP=")) {
                Matcher local = Pattern.compile("LOCAL:([^,]+)").matcher(line);
                Matcher pts = Pattern.compile("MPEGTS:(\\d+)").matcher(line);
                if (local.find() && pts.find()) try {
                    result.timestampOffsetMs = Long.parseLong(pts.group(1)) / 90 - timeMs(local.group(1));
                } catch (NumberFormatException ignored) {}
            }
            if (
                line.equals("NOTE") ||
                line.startsWith("NOTE ") ||
                line.equals("STYLE") ||
                line.equals("REGION")
            ) {
                while (i + 1 < lines.length && !lines[i + 1].trim().isEmpty()) i++;
                continue;
            }
            Matcher cue = CUE.matcher(line);
            if (!cue.find()) continue;
            long start = timeMs(cue.group(1)),
                end = timeMs(cue.group(2));
            StringBuilder body = new StringBuilder();
            while (i + 1 < lines.length && !lines[i + 1].trim().isEmpty()) {
                if (body.length() > 0) body.append('\n');
                body.append(lines[++i]);
            }
            String plain = body
                .toString()
                .replaceAll("<[^>]*>", "")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
                .replace("&quot;", "\"")
                .replace("&amp;", "&");
            if (end > start) result.cues.add(new Cue(start, end, plain));
        }
        return result;
    }
}
