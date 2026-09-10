package xiao.bu.tv;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** Measures elementary TS payload over media time, never download wall time. */
final class HlsSegmentBitrate {
    private final Map<String, Long> durations = new LinkedHashMap<String, Long>();
    private final Map<String, Sample> samples = new LinkedHashMap<String, Sample>();
    private String playlist = "";
    private long updatedAt;

    synchronized void register(String url, String[] lines) {
        if (!url.equals(playlist)) {
            playlist = url;
            durations.clear();
            samples.clear();
        }
        URI base = URI.create(url);
        long duration = 0;
        boolean range = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#EXTINF:")) {
                try {
                    double seconds = Double.parseDouble(line.substring(8).split(",", 2)[0]);
                    duration = seconds > 0 && seconds <= 120 ? Math.round(seconds * 1000) : 0;
                } catch (NumberFormatException ignored) { duration = 0; }
            } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                range = true;
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                URI segment = base.resolve(line);
                if (!range && duration > 0 && segment.getPath().endsWith(".ts")) {
                    durations.put(segment.toString(), duration);
                    while (durations.size() > 128) durations.remove(durations.keySet().iterator().next());
                }
                duration = 0;
                range = false;
            }
        }
    }

    synchronized Sample begin(String url) {
        Long duration = durations.get(url);
        return duration == null ? null : new Sample(url, duration);
    }

    synchronized void complete(Sample sample, long now) {
        if (sample == null || sample.invalid || sample.used != 0
                || !durations.containsKey(sample.url) || samples.containsKey(sample.url)
                || sample.video.bytes == 0 && sample.audio.bytes == 0) return;
        samples.put(sample.url, sample);
        while (samples.size() > 3) samples.remove(samples.keySet().iterator().next());
        updatedAt = now;
    }

    synchronized long bitrate(boolean video, long now) {
        if (now - updatedAt > 15000) return -1;
        long bytes = 0, duration = 0;
        for (Sample sample : samples.values()) {
            long count = video ? sample.video.bytes : sample.audio.bytes;
            if (count > 0) { bytes += count; duration += sample.duration; }
        }
        return MediaBitrateEstimator.fromPayload(bytes, duration, 250, 1, 200000000);
    }

    private static final class Track {
        int pid = -1, remaining = -1, continuity = -1;
        long bytes;
    }

    static final class Sample {
        final String url;
        final long duration;
        final Track video = new Track(), audio = new Track();
        final byte[] packet = new byte[188];
        int used;
        boolean invalid;

        Sample(String url, long duration) { this.url = url; this.duration = duration; }

        void add(byte[] data, int offset, int length) {
            while (length > 0 && !invalid) {
                int count = Math.min(length, 188 - used);
                System.arraycopy(data, offset, packet, used, count);
                used += count; offset += count; length -= count;
                if (used == 188) { parse(); used = 0; }
            }
        }

        private void parse() {
            if ((packet[0] & 255) != 0x47 || (packet[1] & 0x80) != 0) { invalid = true; return; }
            int control = (packet[3] >> 4) & 3;
            if ((control & 1) == 0) return;
            if ((packet[3] & 0xc0) != 0) { invalid = true; return; }
            int pos = 4;
            if ((control & 2) != 0) pos += 1 + (packet[4] & 255);
            if (pos >= 188) return;
            int pid = ((packet[1] & 31) << 8) | (packet[2] & 255);
            boolean start = (packet[1] & 64) != 0;
            Track track = pid == video.pid ? video : pid == audio.pid ? audio : null;
            if (track != null && track.continuity == (packet[3] & 15)) return;
            if (start && pos + 9 <= 188 && packet[pos] == 0 && packet[pos + 1] == 0 && packet[pos + 2] == 1) {
                int id = packet[pos + 3] & 255;
                track = id >= 0xe0 && id <= 0xef ? video : id >= 0xc0 && id <= 0xdf ? audio : null;
                if (track == null) return; // Private streams require PMT codec identification.
                if (track.pid != -1 && track.pid != pid) return; // Never sum multiple tracks.
                track.pid = pid;
                int header = 9 + (packet[pos + 8] & 255);
                int pesLength = ((packet[pos + 4] & 255) << 8) | (packet[pos + 5] & 255);
                if (pos + header > 188 || pesLength > 0 && pesLength < header - 6) { invalid = true; return; }
                track.remaining = pesLength == 0 ? -1 : pesLength - header + 6;
                pos += header;
            }
            if (track == null) return;
            int continuity = packet[3] & 15;
            if (track.continuity == continuity) return;
            if (track.continuity >= 0 && continuity != ((track.continuity + 1) & 15)) { invalid = true; return; }
            track.continuity = continuity;
            int count = 188 - pos;
            if (track.remaining >= 0) {
                count = Math.min(count, track.remaining);
                track.remaining -= count;
            }
            track.bytes += count;
        }
    }
}
