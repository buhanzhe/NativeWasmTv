package xiao.bu.tv;

import java.lang.reflect.Method;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/** Optional nTv properties, independent of the bundled upstream Java API. */
final class IjkStreamMetrics {
    private static final Method GET_FLOAT = findGetter("_getPropertyFloat", int.class, float.class);
    private static final Method GET_LONG = findGetter("_getPropertyLong", int.class, long.class);
    private static final Method SET_LONG = findGetter("_setPropertyLong", int.class, long.class);
    private static final Method GET_CODEC = findGetter("_getVideoCodecInfo");
    private static final int SOURCE_FPS = 11001;
    private static final int OUTPUT_FRAMES = 22001;
    private static final int RECEIVER_DELAY = 22002;
    private static final int VIDEO_DESCRIPTION = 22010;
    private static final int DEINTERLACE = 22012;
    private static final int VIDEO_PAYLOAD_BYTES = 22013;
    private static final int AUDIO_PAYLOAD_BYTES = 22014;
    private static final int LIVE_BUFFER_ADVANCE = 22015;
    private static final int LIVE_BUFFER_ADJUSTING = 22016;

    static boolean liveBufferControlAvailable(IjkMediaPlayer player) {
        return SET_LONG != null && longProperty(player, LIVE_BUFFER_ADVANCE, 0L) == 1L;
    }

    static boolean liveBufferAdjusting(IjkMediaPlayer player) {
        return longProperty(player, LIVE_BUFFER_ADJUSTING, 0L) == 1L;
    }

    static void advanceLiveBuffer(IjkMediaPlayer player, long advanceMs) throws java.io.IOException {
        if (!liveBufferControlAvailable(player)) throw new java.io.IOException("当前播放器不支持直播缓冲调节");
        try { SET_LONG.invoke(player, LIVE_BUFFER_ADVANCE, advanceMs); }
        catch (Exception error) { throw new java.io.IOException("直播缓冲调节失败", error); }
    }

    private static Method findGetter(String name, Class<?>... parameters) {
        try {
            Method method = IjkMediaPlayer.class.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (Exception ignored) {
            return null;
        }
    }

    static VideoStreamDescription videoDescription(IjkMediaPlayer player) {
        long value = longProperty(player, VIDEO_DESCRIPTION, 0L);
        return value == 0L ? VideoStreamDescription.UNKNOWN : new VideoStreamDescription(value);
    }

    static float decodeFrameRate(tv.danmaku.ijk.media.player.IMediaPlayer player) {
        if(player instanceof IjkMediaPlayer)try{
            float value=((IjkMediaPlayer)player).getVideoDecodeFramesPerSecond();
            if(!Float.isNaN(value)&&!Float.isInfinite(value)&&value>=0)return value;
        }catch(RuntimeException ignored){}
        return 0f;
    }

    static boolean sourceDescriptionAvailable(IjkMediaPlayer player) {
        // Zero means not parsed yet; -1 means this native build lacks the property.
        return longProperty(player, VIDEO_DESCRIPTION, -1L) >= 0L;
    }

    static String videoDecoderName(IjkMediaPlayer player) {
        if (player != null && GET_CODEC != null) try {
            String value = (String) GET_CODEC.invoke(player);
            if (value != null && value.trim().length() > 0) {
                int comma = value.indexOf(',');
                String name = (comma >= 0 ? value.substring(comma + 1) : value).trim();
                if (name.length() > 0) return name;
            }
        } catch (Exception ignored) {}
        return "未知";
    }

    static String deinterlaceStatus(IjkMediaPlayer player, VideoStreamDescription source) {
        if ("progressive".equals(source.scanType)) return "not_needed";
        if (!"interlaced".equals(source.scanType) || player == null) return "unknown";
        if (player.getVideoDecoder() == IjkMediaPlayer.FFP_PROPV_DECODER_MEDIACODEC)
            return "system_unverified";
        return longProperty(player, DEINTERLACE, 0L) == 1L ? "bwdif_field" : "unknown";
    }

    static String deinterlaceLabel(String status) {
        if ("bwdif_field".equals(status)) return "BWDIF逐场";
        if ("system_unverified".equals(status)) return "系统处理（待验证）";
        if ("not_needed".equals(status)) return "无需";
        return "未知";
    }

    static float sourceFrameRate(IjkMediaPlayer player) {
        return floatProperty(player, SOURCE_FPS, 0f);
    }

    static long payloadCachedBytes(IjkMediaPlayer player, boolean video) {
        // Upstream cached-byte properties include AVPacket/list-node memory.
        // That overhead is substantial for small audio packets, not media bitrate.
        return longProperty(player, video ? VIDEO_PAYLOAD_BYTES : AUDIO_PAYLOAD_BYTES, -1L);
    }

    static float outputFrameRate(IjkMediaPlayer player) {
        if (player == null || !player.isPlaying()) return 0f;
        if (android.os.Build.VERSION.SDK_INT >= 17) {
            float gpu=NativeHdrOutput.renderedFrameRate();
            if (!Float.isNaN(gpu)) return gpu;
        }
        return player.getVideoOutputFramesPerSecond();
    }

    /** Packet arrival to display submission, smoothed; -1 for absent/stale samples. */
    static long receiverDelayMs(IjkMediaPlayer player) {
        return longProperty(player, RECEIVER_DELAY, -1L);
    }

    static long outputFrameCount(IjkMediaPlayer player) {
        return longProperty(player, OUTPUT_FRAMES, -1L);
    }

    static boolean extendedMetricsAvailable(IjkMediaPlayer player) {
        return floatProperty(player, SOURCE_FPS, -1f) >= 0f;
    }

    private static long longProperty(IjkMediaPlayer player, int id, long fallback) {
        if (player != null && GET_LONG != null) try {
            return ((Number) GET_LONG.invoke(player, id, fallback)).longValue();
        } catch (Exception ignored) { }
        return fallback;
    }

    private static float floatProperty(IjkMediaPlayer player, int id, float fallback) {
        if (player == null || GET_FLOAT == null) return fallback;
        try {
            return ((Number) GET_FLOAT.invoke(player, id, fallback)).floatValue();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private IjkStreamMetrics() {}
}
