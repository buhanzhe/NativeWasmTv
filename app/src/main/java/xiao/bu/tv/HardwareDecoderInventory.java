package xiao.bu.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Codec-service Binder calls never run on the UI thread or gate player preparation. */
final class HardwareDecoderInventory {
    static final class Snapshot {
        final Set<String> avc;
        final boolean hasVideo;
        Snapshot(Set<String> avc,boolean hasVideo){
            this.avc=Collections.unmodifiableSet(new LinkedHashSet<String>(avc));this.hasVideo=hasVideo;
        }
    }
    private static volatile Snapshot snapshot;
    private static boolean started;
    static Snapshot snapshot(){return snapshot;}
    static synchronized void warm(Context context){
        if(started)return;
        if(Build.VERSION.SDK_INT<16){snapshot=new Snapshot(Collections.<String>emptySet(),false);started=true;return;}
        final Context app=context.getApplicationContext();started=true;
        try{
            Thread thread=new Thread(()->{
                SharedPreferences cache=app.getSharedPreferences("hardware_decoder_inventory",0);
                try{
                    if(Build.FINGERPRINT.equals(cache.getString("build",""))&&cache.contains("has_video")){
                        Set<String> saved=cache.getStringSet("avc",Collections.<String>emptySet());
                        snapshot=new Snapshot(saved,cache.getBoolean("has_video",true));
                    }
                }catch(RuntimeException invalid){AppLog.w("CodecInventory","Ignoring invalid decoder cache",invalid);}
                try{
                    Snapshot fresh=Platform.read();snapshot=fresh;
                    cache.edit().putString("build",Build.FINGERPRINT).putStringSet("avc",fresh.avc).putBoolean("has_video",fresh.hasVideo).apply();
                }catch(RuntimeException|LinkageError unavailable){AppLog.w("CodecInventory","Codec enumeration unavailable; retaining automatic selection",unavailable);}
            },"hardware-codec-inventory");
            thread.setDaemon(true);thread.setPriority(Thread.MIN_PRIORITY);thread.start();
        }catch(RuntimeException|OutOfMemoryError unavailable){AppLog.w("CodecInventory","Unable to start codec inventory",unavailable);}
    }
    private static final class Platform {
        static Snapshot read(){
            Set<String> avc=new LinkedHashSet<String>();boolean video=false;
            int count=MediaCodecList.getCodecCount();
            for(int index=0;index<count;index++){
                MediaCodecInfo info=MediaCodecList.getCodecInfoAt(index);
                if(info==null||info.isEncoder())continue;
                String name=info.getName();if(name==null)continue;
                String lower=name.toLowerCase(Locale.US);
                if(lower.startsWith("omx.google.")||lower.startsWith("omx.pv.")||lower.startsWith("omx.avcodec.")||lower.startsWith("omx.ffmpeg.")||lower.startsWith("c2.android.")||lower.contains(".software.")||lower.contains(".sw."))continue;
                for(String type:info.getSupportedTypes()){
                    if(type!=null&&type.toLowerCase(Locale.US).startsWith("video/"))video=true;
                    if("video/avc".equalsIgnoreCase(type))avc.add(name);
                }
            }
            return new Snapshot(avc,video);
        }
    }
    private HardwareDecoderInventory(){}
}
