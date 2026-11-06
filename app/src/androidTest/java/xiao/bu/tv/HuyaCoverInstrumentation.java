package xiao.bu.tv;

import android.app.*;
import android.content.*;
import android.content.res.Resources;
import android.os.*;
import android.view.*;
import java.io.*;
import java.net.*;
import java.lang.reflect.Method;
import java.util.*;
import org.json.*;

/** Real WebView video and opaque cover, with no controller/HUD polling or touch. */
public final class HuyaCoverInstrumentation extends WebTaskNavigationInstrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args){arguments=args;super.onCreate(args);}
    Object sourceCall(String name,Class<?>[] types,Object...args)throws Exception{
        Method m=WebSourceView.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(source,args);
    }
    void awaitJs(String expression,long timeout)throws Exception{
        long end=SystemClock.elapsedRealtime()+timeout;
        while(SystemClock.elapsedRealtime()<end){if("true".equals(js(expression)))return;SystemClock.sleep(150);}
        throw new AssertionError("Video fixture not ready: "+expression);
    }
    void setSource(String name,Object value)throws Exception{
        java.lang.reflect.Field f=WebSourceView.class.getDeclaredField(name);f.setAccessible(true);f.set(source,value);
    }
    @Override public void onStart(){
        Bundle result=new Bundle();JSONObject report=new JSONObject();int code=-1;ServerSocket server=null;
        SharedPreferences prefs=getTargetContext().getSharedPreferences("tv_player",0);
        Map<String,?> saved=new HashMap<>(prefs.getAll());
        try{
            byte[] clip;try(InputStream input=getContext().getAssets().open("huya-cover.mp4");ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                byte[] chunk=new byte[8192];int n;while((n=input.read(chunk))>0)bytes.write(chunk,0,n);clip=bytes.toByteArray();
            }
            server=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));final ServerSocket fixture=server;
            new Thread(()->{while(!fixture.isClosed())try(Socket socket=fixture.accept()){
                socket.setSoTimeout(3000);BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),"UTF-8"));
                String first=reader.readLine(),line,range=null;if(first==null)continue;
                while((line=reader.readLine())!=null&&!line.isEmpty())if(line.toLowerCase(Locale.US).startsWith("range:"))range=line.substring(6).trim();
                boolean video=first.contains("/clip.mp4");
                byte[] data=video?clip:("<!doctype html><html><head><meta name='viewport' content='width=device-width'></head><body style='margin:0;background:black'>"
                        +"<video id='video' autoplay loop playsinline src='/clip.mp4' style='width:100vw;height:100vh'></video>"
                        +"<script>window.fixtureReady=true;window.touches=0;document.addEventListener('touchstart',function(){touches++});document.addEventListener('click',function(){touches++});</script></body></html>").getBytes("UTF-8");
                int start=0,end=data.length-1;boolean partial=video&&range!=null&&range.startsWith("bytes=");
                if(partial){String[] bounds=range.substring(6).split("-",-1);start=Integer.parseInt(bounds[0]);if(bounds.length>1&&!bounds[1].isEmpty())end=Math.min(end,Integer.parseInt(bounds[1]));}
                String headers="HTTP/1.1 "+(partial?"206 Partial Content":"200 OK")+"\r\nContent-Type: "+(video?"video/mp4":"text/html; charset=utf-8")
                        +"\r\nAccept-Ranges: bytes\r\nContent-Length: "+(end-start+1)+"\r\nConnection: close\r\n";
                if(partial)headers+="Content-Range: bytes "+start+"-"+end+"/"+data.length+"\r\n";
                OutputStream out=socket.getOutputStream();out.write((headers+"\r\n").getBytes("UTF-8"));out.write(data,start,end-start+1);out.flush();
            }catch(Exception ignored){}},"huya-cover-fixture").start();
            String url="http://127.0.0.1:"+server.getLocalPort()+"/fixture";
            prefs.edit().putBoolean("auto_update_channel_list",false).putBoolean("neighbor_probe_enabled",false).commit();
            ActivityMonitor launch=addMonitor(MainActivity.class.getName(),null,false);Bundle phase=new Bundle();phase.putString("phase","await_main");sendStatus(0,phase);
            if(arguments!=null&&"true".equals(arguments.getString("launch")))
                activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            else activity=(MainActivity)launch.waitForActivityWithTimeout(15000);
            removeMonitor(launch);if(activity==null)throw new AssertionError("No foreground MainActivity");
            main(()->{
                call("stopCurrentChannelLoad");((java.util.concurrent.atomic.AtomicInteger)field(activity,"catalogLoadGeneration")).incrementAndGet();
                ((TvUiMenu)field(activity,"tvUiMenu")).dismiss();call("closeChannelList");
                // Exercise the exact widget on API19 too, without creating a
                // second Activity/task or altering production legacy routing.
                Resources base=activity.getResources();Resources local=new Resources(base.getAssets(),base.getDisplayMetrics(),base.getConfiguration()){
                    @Override public boolean getBoolean(int id)throws NotFoundException{return id==R.bool.legacy_web_activity_enabled?false:super.getBoolean(id);}
                };
                Context context=new ContextThemeWrapper(activity,android.R.style.Theme_Holo_NoActionBar){@Override public Resources getResources(){return local;}};
                source=new WebSourceView(context);activity.addContentView(source,new ViewGroup.LayoutParams(-1,-1));
                source.applyConfiguration("720p",true,"native","native",1f,false,false,false,"[]");
                source.applyAutoPlay(true);source.applyMinimalMode(false);source.applyPageCoverStyle("black");source.applyPageCover(true);
                source.open(100,url,"",false,"虎牙遮罩夹具","测试");
            });
            awaitJs("window.fixtureReady===true && !video.paused && video.readyState>=2 && video.currentTime>0.2 && video.videoWidth>0",18000);
            ready("/fixture");
            main(()->{if(!source.isPageCovered())throw new AssertionError("Baseline loading cover absent");});
            report.put("videoPlayedUnderOpaqueCover",true).put("before",new JSONObject(js("JSON.stringify({width:video.videoWidth,height:video.videoHeight,frames:video.webkitDecodedFrameCount||0,frameType:typeof video.webkitDecodedFrameCount,qualityType:typeof video.getVideoPlaybackQuality,mediaApi:typeof window.__ntvWebMedia,time:video.currentTime})").replaceAll("^\"|\"$","").replace("\\\"","\"")));
            long began=SystemClock.elapsedRealtime();
            main(()->{setSource("pageUrl","https://www.huya.com/11342412");sourceCall("startCoveredOriginalPlayerCheck",new Class<?>[0]);});
            long until=SystemClock.elapsedRealtime()+5000;boolean[] covered={true};
            while(SystemClock.elapsedRealtime()<until){main(()->covered[0]=source.isPageCovered());if(!covered[0])break;SystemClock.sleep(100);}
            if(covered[0]){report.put("snapshot",js("JSON.stringify(window.__ntvWebMedia?window.__ntvWebMedia.snapshot():{})"));throw new AssertionError("Huya video stayed covered without touch/HUD polling");}
            if(!"0".equals(js("window.touches")))throw new AssertionError("Synthetic touch was used");
            report.put("autoRevealedWithoutTouchOrHud",true).put("revealMs",SystemClock.elapsedRealtime()-began);
            main(()->{source.applyMinimalMode(true);source.applyPageCover(true);source.currentWebMediaState();});SystemClock.sleep(1200);
            main(()->{if(!source.isPageCovered())throw new AssertionError("Manual cover was auto-dismissed");});
            report.put("manualCoverPreserved",true).put("passed",true).put("sdk",Build.VERSION.SDK_INT).put("model",Build.MODEL);
            String live=arguments==null?null:arguments.getString("site");
            if(live!=null){
                String liveUa=arguments.getString("ua","auto");
                main(()->{source.closePage();source.applyConfiguration("720p",true,liveUa,"native",1f,false,false,false,"[]");source.applyMinimalMode(false);source.applyPageCover(true);source.open(101,live,"",false,"虎牙直播验证","测试");});
                long end=SystemClock.elapsedRealtime()+40000;boolean[] liveCovered={true};
                while(SystemClock.elapsedRealtime()<end){main(()->liveCovered[0]=source.isPageCovered());if(!liveCovered[0])break;SystemClock.sleep(250);}
                report.put("liveUrl",live).put("liveAutoRevealed",!liveCovered[0]);
                String details=js("JSON.stringify((function(){var v=document.querySelector('video');return {host:location.hostname,ready:document.readyState,video:!!v,paused:!v||v.paused,width:v?v.videoWidth:0,height:v?v.videoHeight:0,time:v?v.currentTime:0,frames:v?v.webkitDecodedFrameCount||0:0}})())");
                report.put("liveMedia",new JSONObject(details.replaceAll("^\"|\"$","").replace("\\\"","\"")));
                long observeMs=Long.parseLong(arguments.getString("observeMs","0"));
                JSONArray continuous=new JSONArray();long observeStart=SystemClock.elapsedRealtime();
                while(SystemClock.elapsedRealtime()-observeStart<observeMs){
                    String sample=js("JSON.stringify((function(){var v=document.querySelector('video');return {paused:!v||v.paused,ready:v?v.readyState:0,width:v?v.videoWidth:0,time:v?v.currentTime:0,frames:v?v.webkitDecodedFrameCount||0:0,error:v&&v.error?v.error.code:0}})())");
                    continuous.put(new JSONObject().put("elapsedMs",SystemClock.elapsedRealtime()-observeStart).put("sample",sample));
                    SystemClock.sleep(10000);
                }
                report.put("continuous",continuous).put("liveUa",liveUa);
                if(!liveCovered[0])try{
                    android.graphics.Bitmap screenshot=getUiAutomation().takeScreenshot();
                    File dir=new File(getTargetContext().getExternalFilesDir(null),"web-task");dir.mkdirs();
                    try(FileOutputStream image=new FileOutputStream(new File(dir,"huya-live.png"))){screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,image);}screenshot.recycle();
                    report.put("liveScreenshot",true);
                }catch(Exception unavailable){report.put("liveScreenshot",false);}
            }
        }catch(Throwable error){code=0;try{report.put("passed",false).put("failure",android.util.Log.getStackTraceString(error));}catch(Exception ignored){}}
        finally{
            try{if(source!=null)main(()->{source.destroyPage();((ViewGroup)source.getParent()).removeView(source);});if(activity!=null){main(()->activity.finish());long end=SystemClock.elapsedRealtime()+3000;while(!activity.isDestroyed()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(50);}}catch(Exception ignored){}
            if(server!=null)try{server.close();}catch(Exception ignored){}restore(prefs,saved);
            try{report.put("preferencesRestored",saved.equals(prefs.getAll()));File dir=new File(getTargetContext().getExternalFilesDir(null),"web-task");dir.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(dir,"result.json"))){out.write(report.toString(2).getBytes("UTF-8"));}result.putString("stream",report.toString());}catch(Exception ignored){}
            finish(code,result);
        }
    }
}
