package xiao.bu.tv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.webkit.WebView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Real production WebSourceView on API 25; serial rooms avoid competing video decoders. */
public final class HuyaLongPlaybackInstrumentation extends Instrumentation {
    private Activity activity;
    private WebSourceView source;
    private Bundle args;
    private interface Work {void run()throws Exception;}
    private void main(Work work)throws Exception {
        Throwable[] error={null};runOnMainSync(()->{try{work.run();}catch(Throwable e){error[0]=e;}});
        if(error[0]!=null)throw new Exception(error[0]);
    }
    @Override public void onCreate(Bundle value){super.onCreate(value);args=value;start();}
    private JSONObject sample()throws Exception {
        String[] result={null};CountDownLatch done=new CountDownLatch(1);
        main(()->{
            Field field=WebSourceView.class.getDeclaredField("webView");field.setAccessible(true);WebView web=(WebView)field.get(source);
            web.evaluateJavascript("JSON.stringify((function(){var vs=document.querySelectorAll('video'),v=null,area=0;for(var i=0;i<vs.length;i++){var r=vs[i].getBoundingClientRect(),s=getComputedStyle(vs[i]);if(s.display==='none'||s.visibility==='hidden')continue;if(r.width*r.height>area){area=r.width*r.height;v=vs[i];}}var q=v&&v.getVideoPlaybackQuality?v.getVideoPlaybackQuality():null;return {host:location.hostname,path:location.pathname,ua:navigator.userAgent,paused:!v||v.paused,ready:v?v.readyState:0,width:v?v.videoWidth:0,height:v?v.videoHeight:0,time:v?v.currentTime:0,frames:q?q.totalVideoFrames:v?v.webkitDecodedFrameCount||0:0,error:v&&v.error?v.error.code:0};})())",value->{result[0]=value;done.countDown();});
        });
        if(!done.await(8,TimeUnit.SECONDS))throw new AssertionError("WebView sample timed out");
        Object decoded=new JSONTokener(result[0]).nextValue();
        return new JSONObject((String)decoded);
    }
    private File reportFile;
    private void write(JSONObject report)throws Exception {
        try(FileOutputStream out=new FileOutputStream(reportFile)){out.write(report.toString(2).getBytes("UTF-8"));}
    }
    @Override public void onStart(){
        Bundle result=new Bundle();JSONObject report=new JSONObject();JSONArray rooms=new JSONArray();int code=Activity.RESULT_CANCELED;
        try {
            if(android.os.Build.VERSION.SDK_INT<25)throw new AssertionError("This check requires Android 7.1+");
            File dir=new File(getTargetContext().getExternalFilesDir(null),"huya-long");dir.mkdirs();reportFile=new File(dir,"result.json");
            ActivityMonitor launch=addMonitor("xiao.bu.tv.WebPlaybackTestActivity",null,false);
            Bundle phase=new Bundle();phase.putString("phase","await_main");sendStatus(0,phase);
            activity=launch.waitForActivityWithTimeout(25000);removeMonitor(launch);
            if(activity==null)throw new AssertionError("Launch the isolated WebPlaybackTestActivity");
            main(()->{source=new WebSourceView(activity);activity.addContentView(source,new android.view.ViewGroup.LayoutParams(-1,-1));source.applyAutoPlay(true);source.applyMinimalMode(false);source.applyPageCover(false);source.applyConfiguration("720p",true,args.getString("ua","auto"),"native",1f,false,false,false,"[]");});
            report.put("sdk",android.os.Build.VERSION.SDK_INT).put("release",android.os.Build.VERSION.RELEASE).put("model",android.os.Build.MODEL).put("selectedUa",args.getString("ua","auto")).put("rooms",rooms);
            int id=0;long duration=Long.parseLong(args.getString("observeMs","420000"));
            for(String room:args.getString("rooms","11342386,30995188").split(",")){
                if(!room.matches("[0-9]+"))throw new AssertionError("Invalid test room");
                final String url="https://www.huya.com/"+room;final int request=++id;
                main(()->{source.closePage();source.open(request,url,"",false,"Huya "+room,"Long playback check");});
                JSONObject row=new JSONObject().put("room",room);JSONArray samples=new JSONArray();row.put("samples",samples);rooms.put(row);write(report);
                long began=SystemClock.elapsedRealtime(),first=0,lastProgress=0,maxStall=0;
                double previousTime=-1;long previousFrames=-1,advancedFrames=0;
                while(first==0?SystemClock.elapsedRealtime()-began<60000:SystemClock.elapsedRealtime()-first<duration){
                    JSONObject state=sample();long now=SystemClock.elapsedRealtime();state.put("elapsedMs",now-began);samples.put(state);
                    boolean roomDocument=state.optString("path").equals("/"+room);
                    boolean playing=roomDocument&&!state.optBoolean("paused")&&state.optInt("width")>0&&state.optInt("ready")>=2&&state.optDouble("time")>0;
                    if(first==0&&playing){first=lastProgress=now;row.put("firstFrameMs",now-began);}
                    if(first!=0){
                        long frames=state.optLong("frames");double time=state.optDouble("time");
                        if(playing&&(frames>previousFrames||time>previousTime))lastProgress=now;
                        if(frames>previousFrames&&previousFrames>=0)advancedFrames+=frames-previousFrames;
                        maxStall=Math.max(maxStall,now-lastProgress);previousTime=time;previousFrames=frames;
                    }
                    row.put("observedMs",first==0?0:now-first).put("maxStallMs",maxStall).put("advancedFrames",advancedFrames);write(report);
                    if(first==0&&now-began>=60000)break;
                    SystemClock.sleep(5000);
                }
                JSONObject last=samples.getJSONObject(samples.length()-1);
                boolean pass=first>0&&maxStall<15000&&advancedFrames>100&&!last.optBoolean("paused")&&last.optInt("ready")>=2&&last.optInt("width")>0;
                row.put("passed",pass);write(report);
            }
            boolean passed=true;for(int i=0;i<rooms.length();i++)passed&=rooms.getJSONObject(i).optBoolean("passed");
            report.put("passed",passed);write(report);if(!passed)throw new AssertionError("One or more rooms failed continuous playback");code=Activity.RESULT_OK;
        }catch(Throwable error){try{report.put("error",android.util.Log.getStackTraceString(error));if(reportFile!=null)write(report);}catch(Exception ignored){}}
        finally{try{main(()->{if(source!=null)source.destroyPage();if(activity!=null)activity.finish();});}catch(Exception ignored){}}
        result.putString("resultFile",reportFile==null?"":reportFile.toString());result.putString("result",report.toString());finish(code,result);
    }
}
