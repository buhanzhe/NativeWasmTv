package xiao.bu.tv;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** Whole-response reads for manifests/decryption only; progressive media must stream. */
final class BoundedResponseReader {
    private BoundedResponseReader() {}

    static byte[] read(InputStream input, long expectedLength, int limit) throws IOException {
        try {
            if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
            if (expectedLength > limit) throw tooLarge(limit);
            int capacity = expectedLength > 0 ? (int) expectedLength : Math.min(64 * 1024, limit);
            byte[] body = new byte[capacity];
            int size = 0;
            while (true) {
                if (size == body.length) {
                    // Verify EOF even when Content-Length claimed an exact size.
                    // A missing or false length must never bypass the hard limit.
                    int next = input.read();
                    if (next == -1) return body;
                    if (size == limit) throw tooLarge(limit);
                    int grown = (int) Math.min((long) limit, Math.max((long) size + 1, (long) size * 2));
                    body = Arrays.copyOf(body, grown);
                    body[size++] = (byte) next;
                    continue;
                }
                int count = input.read(body, size, body.length - size);
                if (count == -1) return size == body.length ? body : Arrays.copyOf(body, size);
                if (count == 0) {
                    int next = input.read();
                    if (next == -1) return Arrays.copyOf(body, size);
                    body[size++] = (byte) next;
                } else {
                    size += count;
                }
            }
        } finally {
            input.close();
        }
    }

    private static IOException tooLarge(int limit) {
        return new IOException("上游响应超过整包读取上限（" + limit / 1024
                + " KB），已停止读取，避免内存溢出");
    }
}
