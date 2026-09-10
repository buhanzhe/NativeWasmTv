package xiao.bu.tv;

public final class MediaBitrateEstimatorTest {
    private static void equal(long expected, long actual) {
        if (expected != actual) throw new AssertionError(expected + " != " + actual);
    }
    public static void main(String[] args) {
        MediaBitrateEstimator meter = new MediaBitrateEstimator();
        equal(-1, meter.sampleCumulativeBytes(0, 1000));
        // Management polling and heartbeat can query in the same millisecond.
        equal(-1, meter.sampleCumulativeBytes(0, 1000));
        for (int i = 1; i <= 100; i++) meter.sampleCumulativeBytes(i * 5000, 1000 + i * 10);
        equal(4000000, meter.sampleCumulativeBytes(500000, 2000));
        meter.reset();
        equal(-1, meter.sampleCumulativeBytes(0, 1000));
        // Sparse callers must not produce zero forever with a four-second window.
        equal(4000000, meter.sampleCumulativeBytes(2500000, 6000));
        equal(0, meter.sampleCumulativeBytes(2500000, 11000));
        equal(-1, meter.sampleCumulativeBytes(10, 12000));
        equal(8000, meter.sampleCumulativeBytes(1010, 13000));
        equal(-1, meter.sampleCumulativeBytes(2000, 30000));
        System.out.println("MediaBitrateEstimatorTest passed");
    }
}
