package xiao.bu.tv;
import java.util.Locale;
/** Filename classification for temporary uploads; decoded content is validated separately. */
final class QuickMediaFile {
    private static String lower(String name){return name==null?"":name.toLowerCase(Locale.US);}
    static boolean isImageName(String name){return lower(name).matches(".*\\.(jpe?g|png|webp|bmp)$");}
    static boolean isAudioName(String name){return lower(name).matches(".*\\.(mp3|m4a|aac|flac|wav|ogg|opus)$");}
    static boolean isSupportedName(String name){return isImageName(name)||isAudioName(name)
        ||lower(name).matches(".*\\.(mp4|mkv|webm|avi|mov|ts|m4v|flv)$");}
    private QuickMediaFile(){}
}
