package xiao.bu.tv;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import java.util.concurrent.ConcurrentHashMap;

/** null is unknown; only an enumerated, absent MIME is a confirmed missing decoder. */
final class VideoDecoderSupport {
    private static final ConcurrentHashMap<String,Boolean> cache=new ConcurrentHashMap<>();
    static Boolean hasDecoder(String mime){
        if(Build.VERSION.SDK_INT<16)return null;
        Boolean saved=cache.get(mime);if(saved!=null)return saved;
        try{
            for(int i=0;i<MediaCodecList.getCodecCount();i++){
                MediaCodecInfo codec=MediaCodecList.getCodecInfoAt(i);
                if(codec.isEncoder())continue;
                for(String type:codec.getSupportedTypes())if(mime.equalsIgnoreCase(type)){
                    cache.put(mime,true);return true;
                }
            }
            cache.put(mime,false);return false;
        }catch(RuntimeException|LinkageError unavailable){return null;}
    }
    static String mime(String codecs){
        String value=codecs==null?"":codecs.toLowerCase(java.util.Locale.US);
        if(value.contains("hvc1")||value.contains("hev1")||value.contains("hevc")||value.contains("h265"))return "video/hevc";
        if(value.contains("avc1")||value.contains("avc3")||value.contains("h264"))return "video/avc";
        return "";
    }
}
