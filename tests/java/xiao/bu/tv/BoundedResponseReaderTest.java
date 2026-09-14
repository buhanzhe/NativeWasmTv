package xiao.bu.tv;

import java.io.IOException;
import java.io.InputStream;

public final class BoundedResponseReaderTest {
    private static final int LIMIT = 2 * 1024 * 1024;
    private static void check(boolean value) { if (!value) throw new AssertionError(); }

    private static final class Generated extends InputStream {
        final long length;
        long position;
        boolean closed;
        Generated(long length) { this.length = length; }
        public int read() {
            return position < length ? (int) (position++ & 255) : -1;
        }
        public int read(byte[] b, int off, int len) {
            if (position == length) return -1;
            int count = (int) Math.min(len, length - position);
            for (int i = 0; i < count; i++) b[off + i] = (byte) (position++ & 255);
            return count;
        }
        public void close() { closed = true; }
    }

    private static void valid(int actual, long declared) throws Exception {
        Generated input = new Generated(actual);
        byte[] result = BoundedResponseReader.read(input, declared, LIMIT);
        check(input.closed && result.length == actual);
        for (int i = 0; i < result.length; i++) check(result[i] == (byte) i);
    }

    private static void oversized(long actual, long declared) throws Exception {
        Generated input = new Generated(actual);
        try {
            BoundedResponseReader.read(input, declared, LIMIT);
            throw new AssertionError("Oversized response accepted");
        } catch (IOException expected) {
            check(expected.getMessage().contains("上限"));
            check(input.closed);
            check(input.position <= LIMIT + 1);
            if (declared > LIMIT) check(input.position == 0);
        }
    }

    public static void main(String[] args) throws Exception {
        valid(0, -1);
        valid(1001, -1);
        valid(1001, 1001);
        valid(1001, 2000); // truncated response is left to the caller to classify
        valid(1001, 2); // understated Content-Length must not silently truncate data
        valid(LIMIT, -1);
        valid(LIMIT, LIMIT);
        oversized(LIMIT + 1, -1);
        oversized(256L * 1024 * 1024, -1);
        oversized(Long.MAX_VALUE, 0); // infinite/chunked stream stops at limit + 1
        oversized(Long.MAX_VALUE, 1);
        oversized(256L * 1024 * 1024, 256L * 1024 * 1024);
        oversized(Long.MAX_VALUE, 3L * 1024 * 1024 * 1024); // no int overflow
        System.out.println("PASS bounded reads: exact/false/missing lengths, 256 MB and infinite bodies, closure");
    }
}
