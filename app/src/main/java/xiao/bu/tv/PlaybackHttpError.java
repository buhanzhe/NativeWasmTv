package xiao.bu.tv;

import java.util.regex.Pattern;

/** Preserve a confirmed HTTP denial without guessing from generic decoder errors. */
final class PlaybackHttpError {
    static final String FORBIDDEN_MESSAGE = "HTTP 403：服务器拒绝访问，可能需要授权或链接已过期";
    // FFmpeg libavutil/error.h: AVERROR_HTTP_FORBIDDEN = FFERRTAG(0xF8,'4','0','3').
    private static final int AVERROR_HTTP_FORBIDDEN = -(0xf8 | ('4' << 8) | ('0' << 16) | ('3' << 24));
    private static final Pattern FORBIDDEN = Pattern.compile("(?i)\\bHTTP(?:/\\d+(?:\\.\\d+)?)?\\s+403\\b");
    private static final Pattern MISSING = Pattern.compile("(?i)\\bHTTP(?:/\\d+(?:\\.\\d+)?)?\\s+(?:404|410)\\b");
    static boolean isMissing(Throwable error){
        for(int depth=0;error!=null&&depth<8;depth++,error=error.getCause())
            if(error.getMessage()!=null&&MISSING.matcher(error.getMessage()).find())return true;
        return false;
    }

    static boolean isForbidden(String detail) {
        return detail != null && FORBIDDEN.matcher(detail).find();
    }

    static boolean isForbidden(Throwable error) {
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
            if (isForbidden(error.getMessage())) return true;
        }
        return false;
    }

    static boolean isForbidden(int what, int extra) {
        return what == AVERROR_HTTP_FORBIDDEN || extra == AVERROR_HTTP_FORBIDDEN;
    }

    /** Only failed, player-requested responses count; stale work from a prior attempt cannot win. */
    static final class Attempt {
        private long generation;
        private boolean forbidden;
        synchronized void reset() { generation++; forbidden = false; }
        synchronized long token() { return generation; }
        synchronized void record(long token, Throwable error) {
            if (token == generation && PlaybackHttpError.isForbidden(error)) forbidden = true;
        }
        synchronized boolean isForbidden() { return forbidden; }
    }
}
