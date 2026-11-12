package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.JSONObject;
import tv.danmaku.ijk.media.player.IMediaPlayer;

/** Actual provider, production Activity, compatibility transport, and fresh preference check. */
final class Legacy404MainChecks {
    private static Object get(Object o,String name)throws Exception {Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String name,Object value)throws Exception {Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
    private interface Work{void run()throws Exception;}
    private static void main(Instrumentation self,Work work)throws Exception {
        Throwable[] error={null};self.runOnMainSync(()->{try{work.run();}catch(Throwable e){error[0]=e;}});
        if(error[0]!=null)throw new Exception(error[0]);
    }
    static void run(Instrumentation self,Bundle args)throws Exception {
        android.content.SharedPreferences preferences=self.getTargetContext().getSharedPreferences(MainActivity.PREFERENCES,0);
        java.util.Map<String,?> original=new java.util.HashMap<>(preferences.getAll());
        try {
        preferences.edit()
                .remove("neighbor_probe_enabled").putString("resolution_mode","low")
                .putBoolean("continuous_ts_enabled",!"hls".equals(args.getString("transport","ts")))
                .putBoolean("show_debug_info",true).commit();
        MainActivity activity=(MainActivity)self.startActivitySync(new Intent(self.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            check(Boolean.FALSE.equals(get(activity,"neighborProbeEnabled")),"Neighbor probing must default off");
            long deadline=SystemClock.elapsedRealtime()+90000;
            while((get(activity,"activePlayerChannel")==null||get(activity,"activePlayerStreamUrl")==null||get(activity,"proxy")==null)
                    &&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(250);
            Channel channel=(Channel)get(activity,"activePlayerChannel");String url=(String)get(activity,"activePlayerStreamUrl");
            check(channel!=null&&url!=null&&url.contains(".m3u8"),"Provider did not resolve HLS");
            boolean linear=!"hls".equals(args.getString("transport","ts"));
            Object old=linear?get(activity,"player"):null;
            if(linear)main(self,()->{
                set(activity,"LEGACY_TS_COMPATIBILITY_ENABLED",true);
                set(activity,"legacyHlsCompatibilityChannel",channel);set(activity,"systemFirstFrameRequestId",-1);
                Method method=MainActivity.class.getDeclaredMethod("startAndroidMediaPlayer",Channel.class,String.class,boolean.class,boolean.class);
                method.setAccessible(true);method.invoke(activity,channel,url,false,true);
            });
            deadline=SystemClock.elapsedRealtime()+45000;
            while((get(activity,"player")==old||get(activity,"player")==null||!Boolean.TRUE.equals(get(activity,"prepared")))
                    &&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(250);
            IMediaPlayer player=(IMediaPlayer)get(activity,"player");
            check(player!=null&&player!=old&&Boolean.TRUE.equals(get(activity,"prepared")),"Main TS playback did not prepare");
            if(linear)check(player instanceof LegacyStreamMediaPlayer,"Compatibility test must use native TS push adapter");
            main(self,()->player.setVolume(0,0));
            int pauseSeconds=Integer.parseInt(args.getString("pauseSeconds","0"));
            if(pauseSeconds>0){
                main(self,player::pause);SystemClock.sleep(pauseSeconds*1000L);
                check(get(activity,"player")==player&&!player.isPlaying(),"Paused TS source failed or reopened");
                main(self,player::start);
            }
            long begin=player.getCurrentPosition();int request=(Integer)get(activity,"playRequestId");
            Bundle progress=new Bundle();progress.putString("stream","Main "+(linear?"TS":"HLS")+" prepared channel="+channel.name+" size="+player.getVideoWidth()+"x"+player.getVideoHeight()+"\n");self.sendStatus(1,progress);
            long started=SystemClock.elapsedRealtime();int seconds=Integer.parseInt(args.getString("seconds","90"));
            while(SystemClock.elapsedRealtime()-started<seconds*1000L){
                check(get(activity,"player")==player&&(Integer)get(activity,"playRequestId")==request,"Main playback unexpectedly reopened/stopped");
                SystemClock.sleep(500);
            }
            JSONObject row=new JSONObject().put("transport",linear?"ts":"hls").put("sdk",android.os.Build.VERSION.SDK_INT).put("model",android.os.Build.MODEL)
                    .put("channel",channel.name).put("playerClass",player.getClass().getSimpleName()).put("neighborProbeEnabled",get(activity,"neighborProbeEnabled"))
                    .put("positionMs",player.getCurrentPosition()).put("advancedMs",player.getCurrentPosition()-begin)
                    .put("durationMs",player.getDuration()).put("width",player.getVideoWidth()).put("height",player.getVideoHeight())
                    .put("confirmedFrame",(Integer)get(activity,"systemFirstFrameRequestId")==request)
                    .put("samePlayer",get(activity,"player")==player).put("observedSeconds",seconds);
            try(FileOutputStream out=new FileOutputStream(new File(self.getTargetContext().getExternalFilesDir(null),"legacy-main-404.json"))){out.write(row.toString(2).getBytes("UTF-8"));}
            check(player.getCurrentPosition()-begin>seconds*800L&&player.getDuration()<=0,"Main live playback did not advance: "+row);
        }finally{
            main(self,activity::finish);
            long finished=SystemClock.elapsedRealtime()+3000;
            while(!activity.isDestroyed()&&SystemClock.elapsedRealtime()<finished)SystemClock.sleep(50);
        }
        } finally {
            android.content.SharedPreferences.Editor editor=preferences.edit().clear();
            for(java.util.Map.Entry<String,?> entry:original.entrySet()){
                String key=entry.getKey();Object value=entry.getValue();
                if(value instanceof String)editor.putString(key,(String)value);
                else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);
                else if(value instanceof Integer)editor.putInt(key,(Integer)value);
                else if(value instanceof Long)editor.putLong(key,(Long)value);
                else if(value instanceof Float)editor.putFloat(key,(Float)value);
                else if(value instanceof java.util.Set)editor.putStringSet(key,new java.util.HashSet<String>((java.util.Set<String>)value));
            }
            if(!editor.commit())throw new java.io.IOException("Could not restore test preferences");
        }
    }
}
