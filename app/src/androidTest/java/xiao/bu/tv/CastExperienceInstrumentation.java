package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Bundle;
import android.webkit.WebView;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.Map;
import java.util.concurrent.*;
import org.json.*;

/** Local-only test probe. Uses production takeover/pointer APIs; no packaged app changes. */
public final class CastExperienceInstrumentation extends Instrumentation {
    MainActivity activity;
    ChannelCatalog.Group[] original;
    Map<String, ?> preferences;
    boolean done;
    ActivityMonitor managementMonitor;
    android.os.HandlerThread pixelThread;
    volatile boolean sampling;
    final JSONArray pixelSamples = new JSONArray();
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        try {
            managementMonitor=addMonitor(ManagementActivity.class.getName(),null,false);
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Thread.sleep(3000);
            preferences=activity.getSharedPreferences(MainActivity.PREFERENCES,0).getAll();
            original=ChannelCatalog.GROUPS;
            try(ServerSocket server=new ServerSocket(18892,8,InetAddress.getByName("127.0.0.1"))) {
                Bundle ready=new Bundle();ready.putString("stream","probe ready 18892\n");sendStatus(0,ready);
                while(!done) try(Socket socket=server.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),"UTF-8"));
                    String first=reader.readLine(),line;int length=0;
                    while((line=reader.readLine())!=null&&!line.isEmpty()) if(line.toLowerCase().startsWith("content-length:")) length=Integer.parseInt(line.substring(15).trim());
                    char[] body=new char[length];int offset=0,n;
                    while(offset<length&&(n=reader.read(body,offset,length-offset))>0)offset+=n;
                    JSONObject result;
                    try {result=handle(new JSONObject(new String(body)));}
                    catch(Throwable e){result=new JSONObject().put("error",android.util.Log.getStackTraceString(e));}
                    byte[] bytes=result.toString().getBytes("UTF-8");
                    OutputStream out=socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));out.write(bytes);out.flush();
                }
            }
        } catch(Throwable e) {android.util.Log.e("CastExperience", "probe",e);}
        finally {
            sampling=false;
            if(pixelThread!=null)pixelThread.quitSafely();
            try {main(()-> {
                activity.stopBackgroundCast("投屏测试结束");
                if(original!=null) ChannelCatalog.GROUPS=original;
                if(preferences!=null) {
                    SharedPreferences.Editor edit=activity.getSharedPreferences(MainActivity.PREFERENCES,0).edit();
                    for(String key:new String[]{"last_group_index_v2","last_channel_index_v2","last_channel_snapshot_v2"}) {
                        Object v=preferences.get(key);if(v instanceof Integer)edit.putInt(key,(Integer)v);
                        else if(v instanceof String)edit.putString(key,(String)v);else edit.remove(key);
                    }edit.commit();
                }
            });}catch(Exception ignored){}
        }
        finish(-1,new Bundle());
    }
    JSONObject handle(JSONObject request) throws Exception {
        String action=request.optString("action");
        if(action.equals("clock"))return new JSONObject().put("now",System.currentTimeMillis());
        if(action.equals("transport"))return CastTransportProbe.run(request.optInt("mbps",8)==30?30:8);
        if(action.equals("pixels")) {
            if(request.optBoolean("start")) {
                sampling=false;
                if(pixelThread!=null)pixelThread.quitSafely();
                synchronized(pixelSamples){while(pixelSamples.length()>0)pixelSamples.remove(0);}
                android.view.SurfaceView[] view={null};main(()->view[0]=(android.view.SurfaceView)field(activity,"videoView"));
                final android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(64,36,android.graphics.Bitmap.Config.ARGB_8888);
                pixelThread=new android.os.HandlerThread("cast-test-pixel");pixelThread.start();
                final android.os.Handler handler=new android.os.Handler(pixelThread.getLooper());sampling=true;
                handler.post(new Runnable(){public void run(){
                    if(!sampling||handler.getLooper().getThread()!=pixelThread){image.recycle();return;}
                    final long begin=System.currentTimeMillis();
                    android.view.PixelCopy.request(view[0],image,result->{
                        long end=System.currentTimeMillis();
                        synchronized(pixelSamples){if(pixelSamples.length()<12000)try{pixelSamples.put(new JSONObject().put("begin",begin).put("end",end).put("code",result).put("color",result==0?image.getPixel(61,2):0));}catch(Exception ignored){}}
                        handler.postDelayed(this,8);
                    },handler);
                }});
            }
            if(request.optBoolean("stop"))sampling=false;
            synchronized(pixelSamples){return new JSONObject().put("samples",new JSONArray(pixelSamples.toString()));}
        }
        if(action.equals("receiver")) {
            tv.danmaku.ijk.media.player.IjkMediaPlayer p=(tv.danmaku.ijk.media.player.IjkMediaPlayer)field(activity,"player");
            return new JSONObject().put("playing",p!=null&&p.isPlaying()).put("cachedMs",p==null?-1:p.getVideoCachedDuration()).put("packets",p==null?-1:p.getVideoCachedPackets()).put("decodeFps",p==null?-1:p.getVideoDecodeFramesPerSecond()).put("outputFps",p==null?-1:p.getVideoOutputFramesPerSecond());
        }
        if(action.equals("stop")){done=true;return new JSONObject().put("ok",true);}
        if(action.equals("setup")) {
            main(()-> {
                activity.stopBackgroundCast("开始投屏实测");
                JSONArray pages=request.getJSONArray("pages");Channel[] channels=new Channel[pages.length()];
                for(int i=0;i<pages.length();i++) channels[i]=new Channel(""+(i+1),pages.getJSONObject(i).getString("name"),null,"webview://"+pages.getJSONObject(i).getString("url"),null,null);
                ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("临时投屏测试",ChannelCatalog.SOURCE_CUSTOM,channels)};
                set(activity,"currentGroupIndex",0);set(activity,"currentChannelIndex",0);
                set(activity,"webCastResolution",request.optString("resolution","1920x1080"));set(activity,"webCastFps",request.optInt("fps",60));
                set(activity,"webCastBitrateMbps",20);
                set(activity,"webCastAudio",false);set(activity,"webViewAutoPlaySniffed",false);set(activity,"flyMouseEnabled",true);
            });return new JSONObject().put("ok",true);
        }
        if(action.equals("management")) {
            main(()-> {
                android.app.Activity old=managementMonitor.getLastActivity();
                if(old!=null&&!old.isFinishing())old.finish();
                if(request.has("url")) activity.startActivity(new Intent(activity,ManagementActivity.class)
                        .putExtra(ManagementActivity.EXTRA_URL,request.getString("url"))
                        .putExtra(ManagementActivity.EXTRA_TAKEOVER,true));
            });
            return new JSONObject().put("ok",true);
        }
        if(action.equals("views")) {
            JSONObject result=new JSONObject();main(()-> {
                WebView page=(WebView)field(field(activity,"webSourceView"),"webView");
                android.app.Activity manager=managementMonitor.getLastActivity();
                WebView control=manager==null||manager.isFinishing()?null:(WebView)field(manager,"webView");
                result.put("pageLayer",page.getLayerType()).put("controlLayer",control==null?-1:control.getLayerType())
                        .put("pageSize",page.getWidth()+"x"+page.getHeight());
                Object cast=field(activity,"webViewCastManager");
                result.put("pageIdentity",System.identityHashCode(page))
                        .put("encoderIdentity",System.identityHashCode(field(cast,"videoEncoder")))
                        .put("rtspIdentity",System.identityHashCode(field(cast,"rtspServer")));
                if(android.os.Build.VERSION.SDK_INT>=29) {
                    Object a=page.getWebViewRenderProcess(),b=control==null?null:control.getWebViewRenderProcess();
                    result.put("sameRenderer",a!=null&&a==b).put("pageRenderer",System.identityHashCode(a))
                            .put("controlRenderer",System.identityHashCode(b));
                }
            });return result;
        }
        if(action.equals("pipeline")) {
            Object cast=field(activity,"webViewCastManager");
            android.media.MediaCodec encoder=(android.media.MediaCodec)field(cast,"videoEncoder");
            JSONObject result=new JSONObject().put("encoder",encoder==null?"":encoder.getName())
                    .put("format",encoder==null?"":encoder.getOutputFormat().toString());
            JSONArray ages=new JSONArray(),stacks=new JSONArray();
            for(int i=0;i<40;i++) {
                long pts=(Long)field(cast,"lastVideoPresentationTimeUs");
                ages.put((System.nanoTime()/1000L-pts)/1000d);
                stacks.put(java.util.Arrays.toString(android.os.Looper.getMainLooper().getThread().getStackTrace()));
                Thread.sleep(50);
            }
            return result.put("encodedFrameAgeMs",ages).put("mainStacks",stacks);
        }
        if(action.equals("eval")) {
            String[] value={null};CountDownLatch latch=new CountDownLatch(1);
            main(()-> {
                Object owner=request.optBoolean("management")?managementMonitor.getLastActivity():field(activity,"webSourceView");
                WebView web=(WebView)field(owner,"webView");
                web.evaluateJavascript("JSON.stringify("+request.getString("expression")+")",v->{value[0]=v;latch.countDown();});
            });
            if(!latch.await(8,TimeUnit.SECONDS))throw new IOException("JS timeout");
            return new JSONObject().put("value",new JSONTokener(String.valueOf(new JSONTokener(value[0]).nextValue())).nextValue());
        }
        if(action.equals("cursor")) {
            JSONObject result=new JSONObject();main(()-> {
                FlyMouseCursorView c=(FlyMouseCursorView)field(activity,"flyMouseCursor");
                WebView w=(WebView)field(field(activity,"webSourceView"),"webView");
                int[] origin=new int[2],base=new int[2];w.getLocationOnScreen(origin);((android.view.View)field(activity,"root")).getLocationOnScreen(base);
                result.put("x",c.cursorX()).put("y",c.cursorY()).put("scale",w.getScale()*w.getScaleX())
                        .put("originX",origin[0]-base[0]).put("originY",origin[1]-base[1]);
            });return result;
        }
        if(action.equals("open")) {
            main(()-> ((WebSourceView)field(activity,"webSourceView")).open(999999,request.getString("url")));
            return new JSONObject().put("ok",true);
        }
        if(action.equals("receive")) {
            main(()-> {
                int g=(Integer)field(activity,"currentGroupIndex"),c=(Integer)field(activity,"currentChannelIndex");
                Method method=MainActivity.class.getDeclaredMethod("startResolvedPlayer",Channel.class,String.class,boolean.class);
                method.setAccessible(true);method.invoke(activity,ChannelCatalog.GROUPS[g].channels[c],request.getString("url"),true);
            });return new JSONObject().put("ok",true);
        }
        if(action.equals("codecs")) {
            JSONArray codecs=new JSONArray();
            for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) for(String type:info.getSupportedTypes()) if(type.equals("video/avc")) {
                MediaCodecInfo.VideoCapabilities caps=info.getCapabilitiesForType(type).getVideoCapabilities();
                codecs.put(new JSONObject().put("name",info.getName()).put("encoder",info.isEncoder()).put("4k60",caps.areSizeAndRateSupported(3840,2160,60)).put("4k30",caps.areSizeAndRateSupported(3840,2160,30)));
            }return new JSONObject().put("codecs",codecs);
        }
        throw new IOException("Unknown test action");
    }
    interface Work{void run()throws Exception;}
    void main(Work task)throws Exception {Exception[] error={null};runOnMainSync(()->{try{task.run();}catch(Exception e){error[0]=e;}});if(error[0]!=null)throw error[0];}
    static Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    static void set(Object object,String name,Object value)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
}
