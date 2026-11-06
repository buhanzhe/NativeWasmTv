package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.CRC32;
import org.json.*;
import tv.danmaku.ijk.media.player.*;

/** Actual MainActivity -> LiteWebPlayback -> native decoder, with output frames. */
public final class WebContinuousPlaybackInstrumentation extends Instrumentation {
    MainActivity activity; Bundle args;
    final Handler main=new Handler(Looper.getMainLooper());
    interface Action {void run()throws Exception;}
    void ui(Action action)throws Exception{CountDownLatch done=new CountDownLatch(1);Throwable[] error={null};main.post(()->{try{action.run();}catch(Throwable t){error[0]=t;}finally{done.countDown();}});if(!done.await(3,TimeUnit.SECONDS))throw new AssertionError("Main thread stalled");if(error[0]!=null)throw new Exception(error[0]);}
    Object get(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    void set(String name,Object value)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}
    Object call(String name,Class<?>[] types,Object...values)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,values);}
    void call(String name)throws Exception{call(name,new Class<?>[0]);}
    void pageControl(boolean paused)throws Exception{ui(()->{LiteWebPlayback web=(LiteWebPlayback)get("liteWebPlayback");Field f=LiteWebPlayback.class.getDeclaredField("engine");f.setAccessible(true);((LiteEngine)f.get(web)).evaluate("document.querySelector('video')."+(paused?"pause()":"play().catch(function(){})"));});}
    void controlChecks(JSONObject result)throws Exception{
        pageControl(true);SystemClock.sleep(1200);JSONObject paused=sample();if(paused.optBoolean("playing"))throw new AssertionError("Page pause did not reach decoder");
        pageControl(false);SystemClock.sleep(2200);JSONObject resumed=sample();if(!resumed.optBoolean("playing")||resumed.optLong("outputFrames")<=paused.optLong("outputFrames"))throw new AssertionError("Page resume did not restore output");
        ui(()->((IMediaPlayer)get("player")).pause());SystemClock.sleep(1500);JSONObject remote=sample();if(remote.optBoolean("playing"))throw new AssertionError("Old page play intent undid native pause");
        ui(()->((IMediaPlayer)get("player")).start());SystemClock.sleep(2200);JSONObject finalState=sample();if(!finalState.optBoolean("playing")||finalState.optLong("outputFrames")<=remote.optLong("outputFrames"))throw new AssertionError("Native resume did not restore output");
        result.put("controls",new JSONObject().put("pagePause",true).put("pageResume",true).put("nativePauseRetained",true).put("nativeResume",true));
    }
    JSONObject sample()throws Exception{JSONObject row=new JSONObject();ui(()->{
        IMediaPlayer p=(IMediaPlayer)get("player");row.put("atMs",SystemClock.elapsedRealtime()).put("prepared",get("prepared")).put("firstFrame",get("videoRenderingStarted")).put("request",get("playRequestId"));
        if("system".equals(args.getString("engine"))){JSONObject web=((WebSourceView)get("webSourceView")).currentWebMediaState();row.put("backend","WebView").put("playing",web.optBoolean("playing")&&!web.optBoolean("mediaPaused",true)).put("positionMs",web.optLong("mediaTimeMs")).put("width",web.optInt("videoWidth")).put("height",web.optInt("videoHeight")).put("firstFrame",web.optInt("videoWidth")>0&&web.optLong("mediaTimeMs")>0).put("outputFrames",web.optLong("videoFrames",-1));}
        else if(p!=null){row.put("backend",p.getClass().getSimpleName()).put("playing",p.isPlaying()).put("positionMs",p.getCurrentPosition()).put("width",p.getVideoWidth()).put("height",p.getVideoHeight());if(p instanceof IjkMediaPlayer)row.put("outputFrames",IjkStreamMetrics.outputFrameCount((IjkMediaPlayer)p)).put("outputFps",((IjkMediaPlayer)p).getVideoOutputFramesPerSecond());}
        LiteWebPlayback web=(LiteWebPlayback)get("liteWebPlayback");if(web!=null){web.enableAudit();row.put("lite",web.auditSnapshot());}
    });return row;}
    void restore(SharedPreferences prefs,Map<String,?> values){SharedPreferences.Editor e=prefs.edit().clear();for(Map.Entry<String,?> v:values.entrySet()){Object x=v.getValue();String k=v.getKey();if(x instanceof String)e.putString(k,(String)x);else if(x instanceof Boolean)e.putBoolean(k,(Boolean)x);else if(x instanceof Integer)e.putInt(k,(Integer)x);else if(x instanceof Long)e.putLong(k,(Long)x);else if(x instanceof Float)e.putFloat(k,(Float)x);else if(x instanceof Set)e.putStringSet(k,new HashSet<String>((Set<String>)x));}e.commit();}
    @Override public void onCreate(Bundle value){args=value;start();}
    @Override public void onStart(){JSONObject result=new JSONObject();JSONArray samples=new JSONArray(),images=new JSONArray();Bundle status=new Bundle();int code=0;File dir=new File(getTargetContext().getExternalFilesDir(null),"web-continuous");dir.mkdirs();String id=args.getString("id","case");SharedPreferences prefs=getTargetContext().getSharedPreferences("tv_player",0);Map<String,?> saved=new HashMap<>(prefs.getAll());
        try{
            if(!id.matches("[A-Za-z0-9_-]{1,64}"))throw new AssertionError("Invalid id");
            String input=new String(android.util.Base64.decode(args.getString("source"),0),"UTF-8"),url=input.startsWith("webview://")?input:"webview://"+input;
            int observe=Math.max(15000,Math.min(180000,Integer.parseInt(args.getString("observeMs","45000"))));
            prefs.edit().putBoolean("auto_update_channel_list",false).commit();
            if("true".equals(args.getString("foregroundLaunch"))){ActivityMonitor launch=addMonitor(MainActivity.class.getName(),null,false);Bundle phase=new Bundle();phase.putString("phase","await_main");sendStatus(0,phase);activity=(MainActivity)launch.waitForActivityWithTimeout(15000);removeMonitor(launch);if(activity==null)throw new AssertionError("Foreground launch timed out");}
            else activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(1500);
            long began=SystemClock.elapsedRealtime();
            ui(()->{call("stopCurrentChannelLoad");((java.util.concurrent.atomic.AtomicInteger)get("catalogLoadGeneration")).incrementAndGet();((TvUiMenu)get("tvUiMenu")).dismiss();call("closeChannelList");set("autoSwitchSource",false);set("webSourceEngine","lite");set("playerEngine","ijk");set("decodeMode",args.getString("decode","software"));set("defaultDecodeMode",args.getString("decode","software"));Channel channel=new Channel("1",args.getString("name","web"),"",url,null,null);set("quickChannel",channel);set("remoteCatalogUrl","");int request=(Integer)get("playRequestId");set("webViewPageCover",false);set("webViewAutoPlay",true);set("webViewAutoPlaySniffed",true);set("defaultWebSourceEngine",args.getString("engine","lite"));((ChannelPlaybackMemory)get("playbackMemory")).rememberEngine(WebDomainSettings.key(url),args.getString("engine","lite"));
                if("system".equals(args.getString("engine")))call("openWebSource",new Class<?>[]{Channel.class,String.class,int.class},channel,url,request);
                else call("openLiteWebSource",new Class<?>[]{Channel.class,String.class,int.class,String.class},channel,url,request,"");LiteWebPlayback web=(LiteWebPlayback)get("liteWebPlayback");if(web!=null){web.enableAudit();if("true".equals(args.getString("mseTrace"))){Field field=LiteWebPlayback.class.getDeclaredField("engine");field.setAccessible(true);LiteEngine engine=(LiteEngine)field.get(web);try(InputStream auditInput=getContext().getAssets().open("mse-input-audit.js");ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] block=new byte[8192];int n;while((n=auditInput.read(block))>0)bytes.write(block,0,n);engine.setAuditBootstrap(new String(bytes.toByteArray(),"UTF-8"));}}}});
            long first=-1,lastPosition=-1,advanced=0,lastProgress=began,maxStall=0,initialFrames=-1,finalFrames=-1,lastOutput=-1,lastOutputAt=began,maxOutputStall=0,nextImage=0;int imageIndex=0;
            while(SystemClock.elapsedRealtime()-began<30000||first>=0&&SystemClock.elapsedRealtime()-first<observe){
                JSONObject row=sample();long now=SystemClock.elapsedRealtime();
                if(first<0&&row.optBoolean("firstFrame")){first=now;lastProgress=now;result.put("firstFrameMs",now-began);}
                long position=row.optLong("positionMs",-1);if(first>=0){if(lastPosition>=0&&position>lastPosition){advanced+=Math.min(1000,position-lastPosition);lastProgress=now;}maxStall=Math.max(maxStall,now-lastProgress);lastPosition=position;long frames=row.optLong("outputFrames",-1);if(frames>=0){if(initialFrames<0){initialFrames=frames;lastOutputAt=now;}if(frames>lastOutput)lastOutputAt=now;maxOutputStall=Math.max(maxOutputStall,now-lastOutputAt);lastOutput=frames;finalFrames=frames;}
                    if(now>=nextImage&&imageIndex<4){Bitmap bitmap=getUiAutomation().takeScreenshot();if(bitmap!=null){CRC32 crc=new CRC32();for(int y=bitmap.getHeight()/4;y<bitmap.getHeight()*3/4;y+=3)for(int x=bitmap.getWidth()/4;x<bitmap.getWidth()*3/4;x+=3){int pixel=bitmap.getPixel(x,y);crc.update(pixel);crc.update(pixel>>>8);crc.update(pixel>>>16);}String file=id+"-"+(imageIndex++)+".png";try(FileOutputStream out=new FileOutputStream(new File(dir,file))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();images.put(new JSONObject().put("file",file).put("crc32",crc.getValue()).put("afterFirstFrameMs",now-first));}nextImage=now+10000;}
                }
                samples.put(row);if(first<0&&now-began>=30000)break;SystemClock.sleep(500);
            }
            result.put("observedMs",first<0?0:SystemClock.elapsedRealtime()-first).put("advancedMs",advanced).put("maxStallMs",maxStall).put("maxOutputStallMs",maxOutputStall).put("outputFramesAdvanced",finalFrames<0?-1:finalFrames-initialFrames);
            java.util.Set<Long> pixels=new java.util.HashSet<>();for(int i=0;i<images.length();i++)pixels.add(images.getJSONObject(i).getLong("crc32"));
            JSONObject last=samples.getJSONObject(samples.length()-1);boolean output=finalFrames>=0?finalFrames-initialFrames>observe/2000&&maxOutputStall<8000:pixels.size()>=2;
            boolean passed=first>=0&&advanced>=observe*3/4&&maxStall<8000&&last.optBoolean("playing")&&output;
            if(passed&&"true".equals(args.getString("controlsCheck","false")))controlChecks(result);
            result.put("passed",passed);if(!passed)throw new AssertionError("Continuous output failed: advanced="+advanced+" stall="+maxStall+" output="+(finalFrames-initialFrames));
        }catch(Throwable error){code=-1;try{result.put("passed",false).put("failure",android.util.Log.getStackTraceString(error));}catch(Exception ignored){}}
        finally{try{if(activity!=null)ui(()->{call("stopCurrentChannelLoad");activity.finish();});long end=SystemClock.elapsedRealtime()+3000;while(!activity.isDestroyed()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(50);}catch(Exception ignored){}restore(prefs,saved);try{result.put("samples",samples).put("images",images).put("sdk",Build.VERSION.SDK_INT).put("abi",Build.CPU_ABI);try(FileOutputStream out=new FileOutputStream(new File(dir,id+".json"))){out.write(result.toString(2).getBytes("UTF-8"));}status.putString("resultFile",new File(dir,id+".json").toString());status.putString("summary",new JSONObject().put("passed",result.optBoolean("passed")).put("advancedMs",result.optLong("advancedMs")).put("maxStallMs",result.optLong("maxStallMs")).toString());}catch(Exception ignored){}finish(code,status);}
    }
}
