package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/** Real Douyu FLV playback with injected EOF, stall, forbidden and player-error events. */
public final class HttpLiveRecoveryInstrumentation extends Instrumentation {
    private MainActivity activity;
    private Throwable uiError;
    private Bundle args;
    private static final String SOURCE="http://43.142.56.63:3366/lunbo/douyu.php?id=747764";
    interface Action { void run() throws Exception; }
    private void ui(Action action) throws Exception {
        uiError=null;runOnMainSync(()->{try{action.run();}catch(Throwable e){uiError=e;}});
        if(uiError!=null)throw new Exception(uiError);
    }
    private Object get(String name)throws Exception {Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    private void set(String name,Object value)throws Exception {Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}
    private Object call(String name,Class<?>[] types,Object...args)throws Exception {Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args);}
    private void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private void notifyPlayer(IMediaPlayer p,String field,Object...values)throws Exception {
        Class<?> type=p.getClass();Field f=null;
        while(type!=null){try{f=type.getDeclaredField(field);break;}catch(NoSuchFieldException e){type=type.getSuperclass();}}
        check(f!=null,"Player listener missing: "+field);f.setAccessible(true);Object listener=f.get(p);
        check(listener!=null,"Player listener not set");
        if("mOnCompletionListener".equals(field))((IMediaPlayer.OnCompletionListener)listener).onCompletion(p);
        else ((IMediaPlayer.OnErrorListener)listener).onError(p,(Integer)values[0],(Integer)values[1]);
    }
    private JSONObject awaitPlayback(int previousRequest,int observeMs)throws Exception {
        long until=SystemClock.elapsedRealtime()+60000;
        while(SystemClock.elapsedRealtime()<until){
            final JSONObject[] row={null};
            ui(()->{
                IMediaPlayer p=(IMediaPlayer)get("player");int request=(Integer)get("playRequestId");
                if(request>previousRequest&&p instanceof IjkMediaPlayer&&(Boolean)get("prepared")
                        &&(Boolean)get("videoRenderingStarted")&&p.isPlaying()&&p.getVideoWidth()>0)
                    row[0]=new JSONObject().put("request",request).put("positionMs",p.getCurrentPosition())
                        .put("width",p.getVideoWidth()).put("height",p.getVideoHeight()).put("durationMs",p.getDuration())
                        .put("resolvedUrl",get("activePlayerStreamUrl"));
            });
            if(row[0]!=null){
                SystemClock.sleep(observeMs);final boolean[] ready={false};
                ui(()->{
                    IMediaPlayer p=(IMediaPlayer)get("player");
                    if(p instanceof IjkMediaPlayer&&(Integer)get("playRequestId")==row[0].getInt("request")&&p.isPlaying()){
                        float fps=((IjkMediaPlayer)p).getVideoOutputFramesPerSecond();long advanced=p.getCurrentPosition()-row[0].getLong("positionMs");
                        ready[0]=fps>0&&advanced>observeMs/2;
                        row[0].put("advancedMs",advanced).put("outputFps",fps);
                        check(get("continuousHttpSourceRequestId").equals(get("playRequestId")),"Live marker missing");
                        check((Integer)get("currentChannelIndex")==0&&(Integer)get("currentSourceIndex")==0,"Channel/source changed");
                    }
                });
                if(ready[0])return row[0];
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("Douyu FLV did not render and advance");
    }
    @Override public void onCreate(Bundle value){args=value;start();}
    @Override public void onStart(){
        JSONObject report=new JSONObject();JSONArray cases=new JSONArray();Bundle result=new Bundle();
        Map<String,Map<String,?>> savedPrefs=new HashMap<>();
        boolean original="xiao.bu.tv".equals(getTargetContext().getPackageName());
        try{
            check(getTargetContext().getPackageName().endsWith(".douyurecovery")
                    || original&&"true".equals(args.getString("allowOriginal")),"Use isolated test app or explicitly preserve original preferences");
            if(original){
                File dir=new File(getTargetContext().getApplicationInfo().dataDir,"shared_prefs");
                File[] files=dir.listFiles();
                if(files!=null)for(File file:files)if(file.getName().endsWith(".xml")){
                    String name=file.getName().substring(0,file.getName().length()-4);
                    savedPrefs.put(name,new HashMap<>(getTargetContext().getSharedPreferences(name,0).getAll()));
                }
            }
            report.put("source",SOURCE).put("syntheticMedia",false).put("faultInjection",true).put("cases",cases);
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(3000);
            String source=args.getString("source",SOURCE);
            Channel channel=new Channel("1","刀刀","douyu-recovery",source,null,null,null);
            ui(()->{
                set("startupMaintenanceDestroyed",true);((java.util.concurrent.atomic.AtomicInteger)get("catalogLoadGeneration")).incrementAndGet();
                ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("断流验证",ChannelCatalog.SOURCE_CUSTOM,new Channel[]{channel})};
                set("currentGroupIndex",0);set("currentChannelIndex",0);set("currentSourceIndex",0);
                call("resetPlaybackRecoveryState",new Class<?>[0]);call("startChannel",new Class<?>[]{int.class},0);
            });
            JSONObject initial=awaitPlayback(-1,10000);report.put("initial",initial);
            if(args.getString("source")!=null){
                // A local relay closes a real FLV socket. No completion callback is injected.
                JSONObject recovered=awaitPlayback(initial.getInt("request"),4000);
                cases.put(new JSONObject().put("event","real upstream EOF").put("result","playing").put("media",recovered));
            }else for(String kind:new String[]{"EOF","stall","HTTP403","playerError"}){
                final int[] before={0};final String[] old={null};long started=SystemClock.elapsedRealtime();
                ui(()->{
                    before[0]=(Integer)get("playRequestId");old[0]=(String)get("activePlayerStreamUrl");IMediaPlayer p=(IMediaPlayer)get("player");
                    if("EOF".equals(kind))notifyPlayer(p,"mOnCompletionListener");
                    else if("playerError".equals(kind))notifyPlayer(p,"mOnErrorListener",-10000,-5);
                    else if("HTTP403".equals(kind))call("showPlaybackForbidden",new Class<?>[]{Channel.class},channel);
                    else call("recoverStalledPlayback",new Class<?>[]{int.class,IMediaPlayer.class,String.class},before[0],p,"injected live clock stall");
                });
                JSONObject media=awaitPlayback(before[0],3000);
                check(SOURCE.equals(media.getString("resolvedUrl")),"Player reopened a classified CDN session instead of the original gateway");
                check(channel.sourceUrl(0).equals(SOURCE),"Original source modified");
                cases.put(new JSONObject().put("event",kind).put("result","playing").put("reopenedOriginalEndpoint",true)
                        .put("recoveryAndObservationMs",SystemClock.elapsedRealtime()-started).put("media",media));
            }
            report.put("result","PASS");
        }catch(Throwable e){try{report.put("result","FAIL").put("error",android.util.Log.getStackTraceString(e));}catch(Exception ignored){}}
        finally{
            if(original&&!savedPrefs.isEmpty())try{
                if(activity!=null){ui(()->{call("releasePlayer",new Class<?>[]{boolean.class},true);activity.finish();});SystemClock.sleep(750);}
                for(Map.Entry<String,Map<String,?>> entry:savedPrefs.entrySet()){
                    SharedPreferences.Editor edit=getTargetContext().getSharedPreferences(entry.getKey(),0).edit().clear();
                    for(Map.Entry<String,?> item:entry.getValue().entrySet()){
                        String key=item.getKey();Object value=item.getValue();
                        if(value instanceof String)edit.putString(key,(String)value);
                        else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);
                        else if(value instanceof Integer)edit.putInt(key,(Integer)value);
                        else if(value instanceof Long)edit.putLong(key,(Long)value);
                        else if(value instanceof Float)edit.putFloat(key,(Float)value);
                        else if(value instanceof Set)edit.putStringSet(key,(Set<String>)value);
                    }
                    check(edit.commit(),"Unable to restore preferences: "+entry.getKey());
                }
                report.put("originalPreferencesRestored",true);
            }catch(Throwable error){try{report.put("result","FAIL").put("restoreError",error.toString());}catch(Exception ignored){}}
        }
        try{
            File file=new File(getTargetContext().getExternalFilesDir(null),"http-live-recovery.json");
            try(FileOutputStream out=new FileOutputStream(file)){out.write(report.toString(2).getBytes("UTF-8"));}
            result.putString("report",file.getAbsolutePath());result.putString("result",report.optString("result"));
        }catch(Exception e){result.putString("error",e.toString());}
        finish("PASS".equals(report.optString("result"))?0:1,result);
    }
}
