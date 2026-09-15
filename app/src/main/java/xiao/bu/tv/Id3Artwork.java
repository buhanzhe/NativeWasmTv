package xiao.bu.tv;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** Read only a bounded ID3 header, never buffer an entire song or live stream. */
final class Id3Artwork {
    static final int MAX_TAG_BYTES = 2 * 1024 * 1024;

    static byte[] read(InputStream input) throws IOException {
        long deadline = System.nanoTime() + 6000000000L;
        byte[] header = new byte[10];
        if (!readFully(input, header, deadline) || header[0] != 'I'
                || header[1] != 'D' || header[2] != '3') return null;
        int version = header[3] & 255;
        int size = size(header, 6, true);
        if (version < 2 || version > 4 || size <= 0 || size > MAX_TAG_BYTES
                || (version == 2 && (header[5] & 64) != 0)) return null;
        byte[] tag = new byte[size];
        if (!readFully(input, tag, deadline)) return null;
        boolean unsync = (header[5] & 128) != 0;
        if (unsync && version < 4) tag = unsync(tag);
        int p = 0;
        if (version >= 3 && (header[5] & 64) != 0) {
            if (tag.length < 4) return null;
            int extended = size(tag, 0, version == 4);
            if (extended < 0 || extended > tag.length - (version == 3 ? 4 : 0)) return null;
            p = extended + (version == 3 ? 4 : 0);
        }
        byte[] fallback = null;
        int headerSize = version == 2 ? 6 : 10;
        while (p <= tag.length - headerSize && tag[p] != 0) {
            int length = version == 2 ? ((tag[p + 3] & 255) << 16)
                    | ((tag[p + 4] & 255) << 8) | (tag[p + 5] & 255)
                    : size(tag, p + 4, version == 4);
            if (length <= 0 || length > tag.length - p - headerSize) break;
            boolean picture = tag[p] == 'A' && tag[p + 1] == 'P'
                    && tag[p + 2] == 'I' && version != 2 && tag[p + 3] == 'C';
            picture |= version == 2 && tag[p] == 'P' && tag[p + 1] == 'I' && tag[p + 2] == 'C';
            int flags = version == 2 ? 0 : tag[p + 9] & 255;
            // Compressed/encrypted/grouped frames require additional interpretation.
            if (picture && (flags & (version == 4 ? 0x4d : 0xe0)) == 0) {
                byte[] frame = Arrays.copyOfRange(tag, p + headerSize, p + headerSize + length);
                if (version == 4 && (unsync || (flags & 2) != 0)) frame = unsync(frame);
                int at = version == 2 ? 4 : terminator(frame, 1, false);
                if (frame.length > 0 && at >= 0 && at < frame.length) {
                    int type = frame[at++] & 255;
                    at = terminator(frame, at, frame[0] == 1 || frame[0] == 2);
                    if (at >= 0 && at < frame.length) {
                        byte[] art = Arrays.copyOfRange(frame, at, frame.length);
                        if (type == 3) return art; // Front cover wins over other pictures.
                        if (fallback == null) fallback = art;
                    }
                }
            }
            p += headerSize + length;
        }
        return fallback;
    }

    private static int terminator(byte[] data, int start, boolean wide) {
        for (int p = start; p < data.length - (wide ? 1 : 0); p += wide ? 2 : 1) {
            if (data[p] == 0 && (!wide || data[p + 1] == 0)) return p + (wide ? 2 : 1);
        }
        return -1;
    }

    private static int size(byte[] data, int p, boolean syncSafe) {
        long value = 0;
        for (int i = 0; i < 4; i++) {
            int b = data[p + i] & 255;
            if (syncSafe && b > 127) return -1;
            value = (value << (syncSafe ? 7 : 8)) | b;
        }
        return value > Integer.MAX_VALUE ? -1 : (int) value;
    }

    private static byte[] unsync(byte[] data) {
        byte[] result = new byte[data.length];
        int count = 0;
        for (int i = 0; i < data.length; i++) {
            result[count++] = data[i];
            if ((data[i] & 255) == 255 && i + 1 < data.length && data[i + 1] == 0) i++;
        }
        return Arrays.copyOf(result, count);
    }

    private static boolean readFully(InputStream input, byte[] data, long deadline) throws IOException {
        int p = 0;
        while (p < data.length) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) return false;
            int count = input.read(data, p, data.length - p);
            if (count < 0) return false;
            p += count;
        }
        return true;
    }
}
