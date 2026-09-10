package xiao.bu.tv;

public final class CastAdaptationTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        CastFrameTiming timing = new CastFrameTiming();
        timing.submitted(1000, 1_000_000);
        timing.encoded(1000, 7_000_000);
        timing.swapCompleted(1000, 9_000_000);
        CastFrameTiming.Snapshot s = timing.snapshot(10_000_000);
        check(s.samples == 1 && s.meanNs == 6_000_000 && s.submitNs == 8_000_000
                && s.afterSubmitNs == 0 && s.pending == 0, "output-before-swap race");
        timing.submitted(2000, 20_000_000);
        timing.submitted(3000, 30_000_000);
        timing.swapCompleted(3000, 32_000_000);
        timing.encoded(3000, 45_000_000);
        timing.swapCompleted(2000, 23_000_000);
        timing.encoded(2000, 50_000_000);
        timing.encoded(9999, 51_000_000);
        s = timing.snapshot(52_000_000);
        check(s.samples == 3 && s.p95Ns == 30_000_000 && s.unmatched == 1, "PTS pairing/order");
        check(timing.snapshot(3_000_000_000L).samples == 0, "expired samples");
        for (int i = 1; i <= 1000; i++) timing.submitted(i, i * 1000L);
        check(timing.snapshot(6_000_000_000L).pending == 256, "bounded pending history");

        CastBitrateController controller = new CastBitrateController(8_000_000, 30);
        long now = 1_000_000_000L;
        controller.update(now, 0, 0, true, 0, false);
        controller.update(now += 500_000_000L, 0, 0, true, 0, true);
        check(controller.bitrate() == 8_000_000 && !controller.needsLowerResolution(), "single spike");
        for (int i = 0; i < 36; i++) {
            controller.recordSend(1_000_000);
            controller.update(now += 500_000_000L, 0, 0, true, 0, true);
        }
        check(controller.bitrate() == 2_000_000 && controller.needsLowerResolution(), "sustained overload");
        controller.update(now += 500_000_000L, 0, 0, true, 0, false);
        check(!controller.needsLowerResolution(), "recovered pressure cancels pending resize");
        controller.resolutionHandled();
        check(!controller.needsLowerResolution(), "one resolution request per decision");
        CastBitrateController network = new CastBitrateController(8_000_000, 30);
        for (int i = 0; i < 40; i++) network.update(now += 500_000_000L, i, 0, true, 140_000_000, false);
        check(network.bitrate() < 8_000_000 && !network.needsLowerResolution(), "network alone must not resize");
        int before = network.bitrate();
        for (int i = 0; i < 80; i++) network.update(now += 500_000_000L, 40, 0, false, 0, true);
        check(network.bitrate() == before && !network.needsLowerResolution(), "no viewer");
        CastBitrateController unsupported = new CastBitrateController(8_000_000, 30);
        unsupported.reject(8_000_000);
        for (int i = 0; i < 20; i++) unsupported.update(now += 500_000_000L, 0, 0, true, 0, true);
        check(unsupported.needsLowerResolution(), "vendor rate-control rejection still permits resize");
        CastConfig c = new CastConfig("https://example.test", 1280, 720, 30, 4_000_000, false);
        check("tcp".equals(c.transport), "default transport is TCP");
        CastConfig udp = new CastConfig("", 1280, 720, 30, 4_000_000, false, "h265", "udp");
        check("udp".equals(udp.withVideo("h264", 25).lowerResolution(2_000_000).transport),
                "codec fallback and adaptive resize preserve transport");
        check("tcp".equals(new CastConfig("", 1280, 720, 30, 4_000_000, false, "h265").transport),
                "H265 does not imply UDP");
        CastConfig lower = c.lowerResolution(2_000_000);
        check(lower.width == 960 && lower.height == 540 && lower.fps == 30, "720 to 540");
        lower = lower.lowerResolution(lower.bitrate);
        check(lower.width == 640 && lower.height == 360 && lower.lowerResolution(lower.bitrate) == null,
                "360 floor");
        check(c.width == 1280 && c.bitrate == 4_000_000, "user settings unchanged");
        System.out.println("PASS: frame timing races, bounded history, sustained pressure, network separation, vendor fallback, resolution ladder");
    }
}
