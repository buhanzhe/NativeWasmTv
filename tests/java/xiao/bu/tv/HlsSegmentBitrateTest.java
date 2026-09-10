package xiao.bu.tv;

import java.nio.file.Files;
import java.nio.file.Paths;

public final class HlsSegmentBitrateTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        byte[] bytes = Files.readAllBytes(Paths.get(args[0]));
        HlsSegmentBitrate meter = new HlsSegmentBitrate();
        String base = "https://example.com/live/list.m3u8", url = "https://example.com/live/one.ts";
        meter.register(base, new String[] {"#EXTM3U", "#EXTINF:4,", "one.ts"});
        HlsSegmentBitrate.Sample sample = meter.begin(url);
        // Real HTTP reads split TS packets at arbitrary offsets.
        for (int i = 0; i < bytes.length; i += 137) sample.add(bytes, i, Math.min(137, bytes.length - i));
        meter.complete(sample, 1000);
        long video = meter.bitrate(true, 1000), audio = meter.bitrate(false, 1000);
        check(video > 0 && audio > 0);
        long expectedVideo = Long.parseLong(args[1]), expectedAudio = Long.parseLong(args[2]);
        check(video == expectedVideo * 2 && audio == expectedAudio * 2); // bytes * 8 / 4 seconds
        // Download completion time cannot alter encoded bitrate; duplicate fetches do not count twice.
        meter.complete(sample, 5000);
        check(video == meter.bitrate(true, 5000));
        check(meter.bitrate(true, 17000) == -1);
        meter.register("https://example.com/other.m3u8", new String[] {"#EXTINF:4,", "other.ts"});
        check(meter.bitrate(true, 5000) == -1);
        check(meter.begin(url) == null);
        meter.register(base, new String[] {"#EXTINF:4,", "one.ts"});
        HlsSegmentBitrate.Sample truncated = meter.begin(url);
        truncated.add(bytes, 0, bytes.length - 1);
        meter.complete(truncated, 1000);
        check(meter.bitrate(true, 1000) == -1);
        meter.register(base, new String[] {"#EXTINF:4,", "#EXT-X-BYTERANGE:188@0", "range.ts"});
        check(meter.begin("https://example.com/live/range.ts") == null);
        System.out.println("HlsSegmentBitrateTest passed: video=" + video + " audio=" + audio + " bits/s (matches ffprobe packet sizes)");
    }
}
