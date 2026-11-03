package xiao.bu.tv;

/** One bounded startup attempt includes prepare and the first decoded video frame. */
final class PlaybackStartupPolicy {
    static final long TIMEOUT_MS=25000L;
    static String failure(long elapsed,boolean prepared,boolean expectedVideo,boolean videoReady,String mime){
        if(prepared&&(!expectedVideo||videoReady)||elapsed<TIMEOUT_MS)return "";
        if(!prepared)return "播放器准备超时，请重试或切换线路";
        if("video/hevc".equals(mime))return "无法解码 H.265/HEVC 视频，请改用软件解码、降低清晰度或切换线路";
        return "视频无法解码或未收到有效画面，请切换解码方式或线路";
    }
}
