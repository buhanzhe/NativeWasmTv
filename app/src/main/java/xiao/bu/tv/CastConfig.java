package xiao.bu.tv;

import android.text.TextUtils;

import org.json.JSONObject;

/** Immutable settings for one WebView cast session. */
final class CastConfig {
    static final String CODEC_H264 = "h264";
    static final String CODEC_H265 = "h265";
    static final int DEFAULT_WIDTH = 1920;
    static final int DEFAULT_HEIGHT = 1080;
    static final int DEFAULT_FPS = 30;
    static final int DEFAULT_BITRATE = 8_000_000;

    final String url;
    final int width;
    final int height;
    final int fps;
    final int bitrate;
    final boolean audio;
    final String codec;

    CastConfig(String url, int width, int height, int fps, int bitrate, boolean audio) {
        this(url, width, height, fps, bitrate, audio, CODEC_H264);
    }

    CastConfig(String url, int width, int height, int fps, int bitrate, boolean audio,
            String codec) {
        this.url = url;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.bitrate = bitrate;
        this.audio = audio;
        this.codec = CODEC_H265.equals(codec) ? CODEC_H265 : CODEC_H264;
    }

    CastConfig withVideo(String codec, int fps) {
        return new CastConfig(url, width, height, fps, bitrate, audio, codec);
    }

    String videoMimeType() {
        return CODEC_H265.equals(codec) ? "video/hevc" : "video/avc";
    }

    static CastConfig fromJson(JSONObject value) {
        String url = value == null ? "" : value.optString("url", "").trim();
        if (!TextUtils.isEmpty(url)
                && !url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("网页地址必须使用 HTTP 或 HTTPS");
        }
        int width = clamp(value.optInt("width", DEFAULT_WIDTH), 640, 3840);
        int height = clamp(value.optInt("height", DEFAULT_HEIGHT), 360, 2160);
        // Surface video encoders generally require even dimensions.
        width &= ~1;
        height &= ~1;
        int requestedFps = value.optInt("fps", DEFAULT_FPS);
        int fps = requestedFps >= 100 ? 120 : requestedFps >= 50 ? 60
                : requestedFps >= 28 ? 30 : 25;
        int bitrateMbps = clamp(value.optInt("bitrateMbps", DEFAULT_BITRATE / 1_000_000),
                2, 40);
        return new CastConfig(url, width, height, fps, bitrateMbps * 1_000_000,
                value.optBoolean("audio", true), value.optString("codec", CODEC_H264));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
