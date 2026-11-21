package xiao.bu.channel.bl;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Response;

/** Inclusive HTTP byte range. A response can leave its total length unspecified. */
final class BlMediaRange {
    private static final Pattern RESPONSE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+|\\*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REQUEST = Pattern.compile("bytes=(\\d+)-(\\d*)", Pattern.CASE_INSENSITIVE);
    final long start, end, total;

    private BlMediaRange(long start, long end, long total) {
        this.start = start;
        this.end = end;
        this.total = total;
    }

    static BlMediaRange received(Response response) throws IOException {
        Matcher matcher = RESPONSE.matcher(response.header("Content-Range", ""));
        if (response.code() != 206 || !matcher.matches()) throw new IOException("媒体 Content-Range 无效");
        try {
            long start = Long.parseLong(matcher.group(1)), end = Long.parseLong(matcher.group(2));
            long total = matcher.group(3).equals("*") ? -1 : Long.parseLong(matcher.group(3));
            if (end < start || end == Long.MAX_VALUE || total >= 0 && end >= total)
                throw new IOException("媒体分片范围无效");
            return new BlMediaRange(start, end, total);
        } catch (NumberFormatException invalid) {
            throw new IOException("媒体 Content-Range 无效", invalid);
        }
    }

    static BlMediaRange requested(String header) {
        if (header == null) return null;
        Matcher matcher = REQUEST.matcher(header);
        if (!matcher.matches()) return null;
        try {
            long start = Long.parseLong(matcher.group(1));
            long end = matcher.group(2).isEmpty() ? Long.MAX_VALUE : Long.parseLong(matcher.group(2));
            return end < start ? null : new BlMediaRange(start, end, -1);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    boolean cacheable(Response response) {
        long length = response.body().contentLength();
        return total > 0 && (length < 0 || length == end - start + 1)
                && "identity".equalsIgnoreCase(response.header("Content-Encoding", "identity"));
    }
}
