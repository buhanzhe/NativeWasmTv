package xiao.bu.tv;

/** Start-code search without a Java call for every encrypted payload byte. */
final class H264AnnexB {
    private H264AnnexB() {}

    static int find(byte[] data, int from, int end) {
        int i = from;
        // Match the transport transformer's existing four-byte lookahead.
        while (i < end - 4) {
            int third = data[i + 2] & 255;
            if (third > 1) { i += 3; continue; }
            if (data[i + 1] != 0) { i += 2; continue; }
            if (data[i] == 0 && (third == 1 || data[i + 3] == 1)) return i;
            i++;
        }
        return end;
    }
}
