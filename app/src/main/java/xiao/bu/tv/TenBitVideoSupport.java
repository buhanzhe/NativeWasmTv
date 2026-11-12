package xiao.bu.tv;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Advisory only: never changes decoding, playback or source selection. */
final class TenBitVideoSupport {
    private TenBitVideoSupport() {}
    interface Callback { void complete(Boolean supported); }
    private static final class Deferred {
        // A broken codec service must not create a thread per channel switch.
        static final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,15,TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(1),task->{
                    Thread thread=new Thread(task,"ten-bit-codec-support");
                    thread.setDaemon(true);thread.setPriority(Thread.MIN_PRIORITY);return thread;
                },new ThreadPoolExecutor.DiscardOldestPolicy());
        static {worker.allowCoreThreadTimeOut(true);}
    }
    static void checkAsync(String mime,String profile,Callback callback){
        if(mime.isEmpty()||Build.VERSION.SDK_INT<16)return;
        try{Deferred.worker.execute(()->{
            Boolean supported;
            try{supported=hasHardwareDecoder(mime,profile);}
            catch(LinkageError unavailable){supported=null;}
            callback.complete(supported);
        });}catch(RuntimeException unavailable){/* Advisory only; playback continues. */}
    }

    static boolean isTenBit(String pixelFormat, String profile) {
        String pixels = normalize(pixelFormat);
        // A known pixel format takes precedence over a profile's maximum depth.
        if (!pixels.isEmpty()) {
            return pixels.matches(".*(?:p10|p010|p210|p410|y210|xv30|xv36)(?:le|be)?$");
        }
        String normalized = normalize(profile).replace(" ", "");
        return normalized.equals("main10") || normalized.equals("high10")
                || normalized.equals("high10intra");
    }

    static String mime(String codec) {
        String name = normalize(codec);
        if (name.equals("hevc") || name.equals("h265")) return "video/hevc";
        if (name.equals("h264") || name.equals("avc")) return "video/avc";
        if (name.equals("vp9")) return "video/x-vnd.on2.vp9";
        if (name.equals("av1")) return "video/av01";
        return "";
    }

    static boolean matchesProfile(String mime, String streamProfile, int profile) {
        if (mime.equals("video/hevc")) return profile == 2 || profile == 4096 || profile == 8192;
        if (mime.equals("video/avc")) return profile == 16;
        if (mime.equals("video/av01")) return profile == 2 || profile == 4096 || profile == 8192;
        if (mime.equals("video/x-vnd.on2.vp9")) {
            boolean profile3 = normalize(streamProfile).replace(" ", "").equals("profile3");
            return profile3 ? profile == 8 || profile == 8192 || profile == 32768
                    : profile == 4 || profile == 4096 || profile == 16384;
        }
        return false;
    }

    // null means unavailable metadata/capabilities, not proof of missing support.
    static Boolean hasHardwareDecoder(String mime, String streamProfile) {
        if (mime.isEmpty()) return null;
        if (Build.VERSION.SDK_INT < 16) return null;
        boolean unknown = false;
        try {
            for (int i = 0; i < MediaCodecList.getCodecCount(); i++) {
                MediaCodecInfo codec = MediaCodecList.getCodecInfoAt(i);
                if (codec.isEncoder()) continue;
                String name = normalize(codec.getName());
                if (name.startsWith("omx.google.") || name.startsWith("c2.android.")
                        || name.startsWith("omx.pv.") || name.startsWith("omx.ffmpeg.")
                        || name.startsWith("omx.avcodec.") || name.contains(".software.")
                        || name.contains(".sw.")) continue;
                if (Build.VERSION.SDK_INT >= 29 && !codec.isHardwareAccelerated()) continue;
                for (String type : codec.getSupportedTypes()) {
                    if (!mime.equalsIgnoreCase(type)) continue;
                    try {
                        MediaCodecInfo.CodecProfileLevel[] profiles = codec.getCapabilitiesForType(type).profileLevels;
                        if (profiles == null || profiles.length == 0) { unknown = true; continue; }
                        for (MediaCodecInfo.CodecProfileLevel profile : profiles) {
                            if (matchesProfile(mime, streamProfile, profile.profile)) return true;
                        }
                    } catch (RuntimeException error) { unknown = true; }
                }
            }
        } catch (RuntimeException error) { return null; }
        return unknown ? null : Boolean.FALSE;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }
}
