package xiao.bu.tv;

import java.util.Arrays;

/** Bounded, monotonic, PTS-matched Surface-to-output observations; not ASIC timing. */
final class CastFrameTiming {
    private static final int CAPACITY = 256;
    private final long[] pts = new long[CAPACITY], begin = new long[CAPACITY];
    private final long[] swapped = new long[CAPACITY], output = new long[CAPACITY];
    private final long[] at = new long[CAPACITY], total = new long[CAPACITY];
    private final long[] submit = new long[CAPACITY], after = new long[CAPACITY];
    private int cursor, sampleCursor;
    private long unmatched, overwritten;

    synchronized void reset() {
        Arrays.fill(begin, 0L);
        Arrays.fill(at, 0L);
        cursor = sampleCursor = 0;
        unmatched = overwritten = 0L;
    }

    synchronized void submitted(long ptsUs, long nowNs) {
        int i = cursor;
        cursor = (cursor + 1) % CAPACITY;
        if (begin[i] != 0) overwritten++;
        pts[i] = ptsUs; begin[i] = nowNs; swapped[i] = output[i] = 0;
    }

    synchronized void swapCompleted(long ptsUs, long nowNs) {
        int i = find(ptsUs);
        if (i < 0) return;
        swapped[i] = nowNs;
        finish(i);
    }

    synchronized void encoded(long ptsUs, long nowNs) {
        int i = find(ptsUs);
        if (i < 0) { unmatched++; return; }
        output[i] = nowNs;
        finish(i);
    }

    private int find(long value) {
        for (int i = 0; i < CAPACITY; i++) if (begin[i] != 0 && pts[i] == value) return i;
        return -1;
    }

    private void finish(int i) {
        // Output can be dequeued before eglSwapBuffers returns on another thread.
        if (swapped[i] == 0 || output[i] == 0) return;
        int s = sampleCursor;
        sampleCursor = (sampleCursor + 1) % CAPACITY;
        at[s] = output[i];
        total[s] = Math.max(0, output[i] - begin[i]);
        submit[s] = Math.max(0, swapped[i] - begin[i]);
        after[s] = Math.max(0, output[i] - swapped[i]);
        begin[i] = 0;
    }

    synchronized Snapshot snapshot(long nowNs) {
        long[] sorted = new long[CAPACITY];
        int n = 0, pending = 0;
        long sum = 0, swapSum = 0, afterSum = 0, oldest = 0;
        for (int i = 0; i < CAPACITY; i++) {
            if (at[i] != 0 && nowNs - at[i] <= 2_000_000_000L) {
                sorted[n++] = total[i]; sum += total[i];
                swapSum += submit[i]; afterSum += after[i];
            }
            if (begin[i] != 0) {
                pending++;
                oldest = Math.max(oldest, nowNs - begin[i]);
            }
        }
        Arrays.sort(sorted, 0, n);
        return new Snapshot(n, pending, n == 0 ? -1 : sum / n,
                n == 0 ? -1 : sorted[(n * 95 + 99) / 100 - 1],
                n == 0 ? -1 : swapSum / n, n == 0 ? -1 : afterSum / n,
                oldest, unmatched, overwritten);
    }

    static final class Snapshot {
        final int samples, pending;
        final long meanNs, p95Ns, submitNs, afterSubmitNs, oldestNs, unmatched, overwritten;
        Snapshot(int samples, int pending, long mean, long p95, long submit, long after,
                long oldest, long unmatched, long overwritten) {
            this.samples = samples; this.pending = pending;
            meanNs = mean; p95Ns = p95; submitNs = submit; afterSubmitNs = after;
            oldestNs = oldest; this.unmatched = unmatched; this.overwritten = overwritten;
        }
    }
}
