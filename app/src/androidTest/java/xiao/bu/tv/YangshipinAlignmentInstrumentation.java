package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/** Isolated real-channel regression for the native website exchange and progressive CMG path. */
public final class YangshipinAlignmentInstrumentation extends Instrumentation {
    private MainActivity activity;
    private Throwable uiError;
    interface Action { void run() throws Exception; }
    private void ui(Action a) throws Exception {
        uiError = null; runOnMainSync(() -> { try { a.run(); } catch (Throwable e) { uiError = e; } });
        if (uiError != null) throw new Exception(uiError);
    }
    private Object get(Object o, String name) throws Exception { Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o); }
    private void set(String name,Object value) throws Exception {Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}
    private Object call(String name,Class<?>[] types,Object...args) throws Exception {Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args);}
    private void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    @Override public void onCreate(Bundle args){start();}

    private void exchangeChecks() throws Exception {
        for (boolean skew : new boolean[]{false,true}) {
            final int[] requests={0},auths={0};
            YangshipinNativeResolver r=new YangshipinNativeResolver((url,method,headers,body,timeout)->{
                requests[0]++; check(timeout>0&&timeout<=8000,"bounded native HTTP");
                check(headers.getString("Cookie").contains("guid="),"identity cookies");
                JSONObject reply=new JSONObject();
                if(url.endsWith("/auth")){auths[0]++;reply.put("code",0).put("data",new JSONObject().put("token","player").put("ts",System.currentTimeMillis()/1000));}
                else if(url.contains("/web/open/token?")){check(url.contains("rnd=random"),"SDK random");reply.put("ret",0).put("data",new JSONObject().put("token","sdk"));}
                else {
                    JSONObject live=new JSONObject(body);check(live.getString("defn").equals("hd"),"quality preserved");
                    check(live.getString("livepid").equals("600001859"),"PID preserved");
                    check(headers.getString("yspplayertoken").equals("player")&&headers.getString("yspsdksign").startsWith("signed-"),"live signature");
                    reply.put("code",0).put("data",new JSONObject().put("playurl","https://test.ysp.cctv.cn/live.m3u8").put("extended_param","?proof=1"));
                }
                return new JSONObject().put("code",200).put("body",reply.toString());
            },new YangshipinNativeResolver.Signer(){public String random(String g,String t){return "random";}public String sign(String g,String t,String i){check(t.equals("sdk"),"SDK token");return "signed";}});
            YangshipinNativeResolver.Result result=r.resolve(ChannelCatalog.YANGSHIPIN_CCTV_CHANNELS[0],"hd",skew?86400000L:0,SystemClock.elapsedRealtime()+20000,()->false);
            check(result.url.endsWith("?proof=1")&&auths[0]==(skew?2:1),"clock correction/extended URL");
            int before=requests[0];try{r.resolve(ChannelCatalog.YANGSHIPIN_CCTV_CHANNELS[0],"hd",0,SystemClock.elapsedRealtime()+20000,()->true);throw new AssertionError("cancel ignored");}catch(java.io.InterruptedIOException expected){}
            check(requests[0]==before,"cancelled API performed I/O");
        }
        check(!YangshipinNativeResolver.validUrl("https://ysp.cctv.cn.evil/live.m3u8"),"URL host");
    }

    private JSONObject play(Channel channel, boolean fresh, int observeMs) throws Exception {
        return play(channel, fresh, observeMs, false);
    }
    private JSONObject play(Channel channel, boolean fresh, int observeMs, boolean direct) throws Exception {
        final long[] start={0}; final int[] request={0};
        ui(()->{
            ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("YSP alignment",direct?ChannelCatalog.SOURCE_CUSTOM:ChannelCatalog.SOURCE_YSP_CCTV,new Channel[]{channel})};
            set("currentGroupIndex",0);set("currentChannelIndex",0);set("currentSourceIndex",0);
            start[0]=SystemClock.elapsedRealtime();
            call("startChannel",new Class<?>[]{int.class,EpgManager.Program.class,String.class,boolean.class},0,null,null,fresh);
            request[0]=(Integer)get(activity,"playRequestId");
        });
        long deadline=SystemClock.elapsedRealtime()+45000; final boolean[] ready={false};
        while(SystemClock.elapsedRealtime()<deadline){
            ui(()->ready[0]=(Boolean)get(activity,"videoRenderingStarted")&&(Integer)get(activity,"playbackReadyRequestId")==request[0]);
            if(ready[0])break;SystemClock.sleep(25);
        }
        check(ready[0],"No real first frame: "+channel.name);
        JSONObject row=new JSONObject().put("channel",channel.name).put("fresh",fresh).put("directSignedUrl",direct).put("firstFrameMs",SystemClock.elapsedRealtime()-start[0]);
        final long[] position={0};
        ui(()->{
            Object resolver=get(activity,"yangshipinResolver");check(resolver!=null&&get(resolver,"webView")==null,"Native path fell back to WebView");
            IMediaPlayer p=(IMediaPlayer)get(activity,"player");position[0]=p.getCurrentPosition();
            row.put("width",p.getVideoWidth()).put("height",p.getVideoHeight());
            check(p instanceof IjkMediaPlayer,"Expected native player");
            check(p.getTrackInfo()!=null&&p.getTrackInfo().length>=2,"Audio/video tracks missing");
        });
        SystemClock.sleep(observeMs);
        ui(()->{
            IMediaPlayer p=(IMediaPlayer)get(activity,"player");
            check((Integer)get(activity,"playRequestId")==request[0],"Unexpected retry/channel change");
            check(p.isPlaying()&&p.getCurrentPosition()>position[0]+observeMs/2,"Playback did not advance");
            float outputFps=((IjkMediaPlayer)p).getVideoOutputFramesPerSecond();
            check(outputFps>0,"Video stopped rendering while audio advanced");
            row.put("outputFps",outputFps);
            row.put("advancedMs",p.getCurrentPosition()-position[0]).put("nativeWithoutWebView",true);
        });
        return row;
    }
    @Override public void onStart(){
        JSONObject report=new JSONObject();JSONArray samples=new JSONArray();Bundle result=new Bundle();
        try {
            exchangeChecks();
            getTargetContext().getSharedPreferences("tv_player",0).edit().putBoolean("auto_switch_source",false)
                    .putString("resolution_mode","high").putString("decode_mode","auto").putString("live_delay_mode","stable").commit();
            CjsPluginRuntime.initialize(getTargetContext());
            if(!CjsPluginRuntime.isInstalled("yangshipin.cn"))CjsPluginRuntime.installOrUpdate("yangshipin.cn");
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(3000);
            ui(()->{set("startupMaintenanceDestroyed",true);((java.util.concurrent.atomic.AtomicInteger)get(activity,"catalogLoadGeneration")).incrementAndGet();});
            for(int index:new int[]{0,4,12})samples.put(play(ChannelCatalog.YANGSHIPIN_CCTV_CHANNELS[index],true,5000));
            samples.put(play(ChannelCatalog.YANGSHIPIN_CCTV_CHANNELS[0],false,30000));
            final String[] signedUrl={null};
            ui(()->{
                java.util.Map<?,?> cache=(java.util.Map<?,?>)get(get(activity,"yangshipinResolver"),"apiCache");
                signedUrl[0]=(String)get(cache.get("600001859_fhd"),"url");
            });
            samples.put(play(new Channel("1","CCTV1 signed URL","direct-ysp",signedUrl[0],null,null,null),false,10000,true));
            report.put("result","PASS").put("mockExchangeAndCancellation","PASS").put("syntheticMedia",false).put("samples",samples);
        } catch(Throwable e){try{report.put("result","FAIL").put("samples",samples).put("error",android.util.Log.getStackTraceString(e));}catch(Exception ignored){}}
        try {
            File file=new File(getTargetContext().getExternalFilesDir(null),"yangshipin-alignment.json");
            try(FileOutputStream output=new FileOutputStream(file)){output.write(report.toString(2).getBytes("UTF-8"));}
            result.putString("report",file.getAbsolutePath());result.putString("result",report.optString("result"));
        }catch(Exception e){result.putString("error",e.toString());}
        finish("PASS".equals(report.optString("result"))?0:1,result);
    }
}
