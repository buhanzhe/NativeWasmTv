package xiao.bu.tv;

import java.util.Locale;

/** Describe confirmed playlist problems without guessing from an IJK error code. */
final class HlsProbeFailure {
    static String manifest(String body) {
        String text = body == null ? "" : body.replace("\uFEFF", "").trim();
        if (text.length() == 0) return "清单请求返回空内容（HTTP 200）";
        String lower = text.toLowerCase(Locale.US);
        if (!text.startsWith("#EXTM3U")) {
            if (lower.startsWith("<!doctype html") || lower.startsWith("<html"))
                return "资源返回网页而非 HLS 清单，可能需要登录或验证";
            return "HLS 清单格式错误：缺少 #EXTM3U 标记";
        }
        boolean master = false, pendingVariant = false, pendingSegment = false;
        int variants = 0, segments = 0;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            String tag = line.toLowerCase(Locale.US);
            if (line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:")) {
                // DRM/key-format labels are hints, not a playback verdict.
                // Preserve the manifest and let the actual decoder attempt it.
                java.util.regex.Matcher method = java.util.regex.Pattern.compile("METHOD=([^,]+)").matcher(line);
                if (method.find()) {
                    String value = method.group(1).trim();
                    if ("AES-128".equalsIgnoreCase(value) && !tag.contains("uri=")) return "AES-128 加密清单缺少密钥地址";
                }
            }
            if (line.startsWith("#EXT-X-STREAM-INF:")) { master = true; pendingVariant = true; }
            else if (line.startsWith("#EXTINF:")) pendingSegment = true;
            else if (line.length() > 0 && !line.startsWith("#")) {
                if (pendingVariant) { variants++; pendingVariant = false; }
                if (pendingSegment) { segments++; pendingSegment = false; }
            }
        }
        if (master && variants == 0) return "HLS 主清单为空：没有可用的画质地址";
        if (!master && segments == 0) return "HLS 媒体清单为空：没有可播放的视频或音频分片";
        return "";
    }

    static String nativeError(int what, int extra) {
        return "播放器无法识别或解码资源（" + what + "/" + extra + "），尚未确认是格式、编码还是传输问题";
    }
}
