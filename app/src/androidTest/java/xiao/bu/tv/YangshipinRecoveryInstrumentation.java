package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.*;
import java.util.Map;
import tv.danmaku.ijk.media.player.IMediaPlayer;

/** Inject recovery events into real Yangshipin playback; no generated media or four-hour wait. */
public final class YangshipinRecoveryInstrumentation extends Instrumentation {
    private MainActivity activity;
    private Throwable mainError;
    private boolean custom;
    interface Action { void run() throws Exception; }
    private void ui(Action action) throws Exception {
        mainError=null;
        runOnMainSync(()->{try{action.run();}catch(Throwable error){mainError=error;}});
        if(mainError!=null)throw new Exception(mainError);
    }
    private Object get(String name)throws Exception {Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    private void set(String name,Object value)throws Exception {Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}
    private Object call(String name,Class<?>[] types,Object...args)throws Exception {Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args);}
    private void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    @Override public void onCreate(Bundle args){custom="true".equals(args.getString("custom"));start();}
    private JSONObject awaitPlayback()throws Exception {
        long deadline=SystemClock.elapsedRealtime()+90000;
        while(SystemClock.elapsedRealtime()<deadline){
            final JSONObject[] sample={null};
            ui(()->{
                IMediaPlayer p=(IMediaPlayer)get("player");
                if(p!=null&&(Boolean)get("prepared")&&p.isPlaying()&&p.getVideoWidth()>0)
                    sample[0]=new JSONObject().put("positionMs",p.getCurrentPosition()).put("width",p.getVideoWidth()).put("height",p.getVideoHeight()).put("request",get("playRequestId"));
            });
            if(sample[0]!=null){
                SystemClock.sleep(1800);
                final boolean[] advancing={false};
                ui(()->{IMediaPlayer p=(IMediaPlayer)get("player");advancing[0]=p!=null&&p.isPlaying()&&p.getCurrentPosition()>sample[0].getLong("positionMs")&&(Integer)get("playRequestId")==sample[0].getInt("request");});
                if(advancing[0])return sample[0];
            }
            SystemClock.sleep(250);
        }
        throw new AssertionError("Real Yangshipin playback did not advance");
    }
    @SuppressWarnings("unchecked") private String poisonCache(Channel channel)throws Exception {
        String quality=(String)call("yangshipinDefinition",new Class<?>[]{Channel.class},channel);
        String key=channel.yangshipinPid+"_"+quality;
        String poison="https://expired.invalid/expired.m3u8";
        Object resolver=get("yangshipinResolver");
        Field memory=YangshipinWebResolver.class.getDeclaredField("apiCache");memory.setAccessible(true);
        Class<?> cached=Class.forName("xiao.bu.tv.YangshipinWebResolver$CachedUrl");
        Constructor<?> constructor=cached.getDeclaredConstructor(String.class,long.class);constructor.setAccessible(true);
        ((Map<String,Object>)memory.get(resolver)).put(key,constructor.newInstance(poison,System.currentTimeMillis()));
        getTargetContext().getSharedPreferences("yangshipin_resolver",0).edit().putString("url_"+key,poison).putLong("url_"+key+"_at",System.currentTimeMillis()).commit();
        return key;
    }
    @Override public void onStart(){
        JSONObject report=new JSONObject();JSONArray cases=new JSONArray();Bundle result=new Bundle();
        ChannelCatalog.Group[] original=null;int group=0,channelIndex=0,source=0;boolean oldMaintenance=false;
        try{
            report.put("syntheticMedia",false).put("faultInjection",true).put("fourHourSoak",false).put("customWebviewSource",custom).put("cases",cases);
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(4000);
            original=ChannelCatalog.GROUPS;group=(Integer)get("currentGroupIndex");channelIndex=(Integer)get("currentChannelIndex");source=(Integer)get("currentSourceIndex");oldMaintenance=(Boolean)get("startupMaintenanceDestroyed");
            final Channel resolverChannel=ChannelCatalog.YANGSHIPIN_CCTV_CHANNELS[0];
            final Channel channel=custom?new Channel("1","CCTV-1 网页源","custom-ysp","webview://https://yangshipin.cn/tv/home?pid=600001859",null,null,null):resolverChannel;
            ui(()->{
                set("startupMaintenanceDestroyed",true);
                ((java.util.concurrent.atomic.AtomicInteger)get("catalogLoadGeneration")).incrementAndGet();
                ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("央视频恢复验证",custom?ChannelCatalog.SOURCE_CUSTOM:ChannelCatalog.SOURCE_YSP_CCTV,new Channel[]{channel})};
                set("currentGroupIndex",0);set("currentChannelIndex",0);set("currentSourceIndex",0);
                call("switchChannel",new Class<?>[]{int.class},0);
            });
            report.put("initial",awaitPlayback());
            for(String kind:new String[]{"stall","http403"}){
                final int before=(Integer)get("playRequestId");final String[] key={null};
                ui(()->{
                    key[0]=poisonCache(resolverChannel);
                    if("stall".equals(kind))call("recoverStalledPlayback",new Class<?>[]{int.class,IMediaPlayer.class,String.class},before,get("player"),"injected live clock stall");
                    else call("showPlaybackForbidden",new Class<?>[]{Channel.class},channel);
                });
                SystemClock.sleep(1800);
                check((Integer)get("playRequestId")>before,"Recovery reused old request");
                check(get("playRequestId").equals(get("freshYangshipinRequestId")),"Recovery did not request fresh resolution");
                JSONObject played=awaitPlayback();
                SharedPreferences prefs=getTargetContext().getSharedPreferences("yangshipin_resolver",0);
                check(!prefs.getString("url_"+key[0],"").contains("expired.invalid"),"Poisoned URL cache survived recovery");
                check((Integer)get("currentChannelIndex")==0&&(Integer)get("currentSourceIndex")==0,"Recovery changed channel/source");
                cases.put(new JSONObject().put("event",kind).put("result","playing").put("freshResolution",true).put("oldMemoryAndDiskCacheBypassed",true).put("media",played));
            }
            report.put("result","PASS");
        }catch(Throwable error){
            try{report.put("result","FAIL").put("error",android.util.Log.getStackTraceString(error));}catch(Exception ignored){}
        }finally{
            try{
                if(original!=null){final ChannelCatalog.Group[] saved=original;final int g=group,c=channelIndex,s=source;final boolean maintenance=oldMaintenance;
                    ui(()->{ChannelCatalog.GROUPS=saved;set("currentGroupIndex",g);set("currentChannelIndex",c);set("currentSourceIndex",s);set("startupMaintenanceDestroyed",maintenance);call("resetPlaybackRecoveryState",new Class<?>[0]);call("startChannel",new Class<?>[]{int.class},c);});
                    report.put("restored",true);
                }
                File out=new File(getTargetContext().getExternalFilesDir(null),"yangshipin-recovery-"+(custom?"custom":"builtin")+"-result.json");
                try(FileOutputStream stream=new FileOutputStream(out)){stream.write(report.toString(2).getBytes("UTF-8"));}
                result.putString("report",out.getAbsolutePath());result.putString("result",report.optString("result"));
            }catch(Throwable error){result.putString("restoreError",error.toString());}
            finish("PASS".equals(report.optString("result"))?0:1,result);
        }
    }
}
