package xiao.bu.tv;

import android.app.*;import android.content.*;import android.os.*;import android.view.*;
import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.*;import org.json.*;

/** Real Lite -> HLS -> IJK playback through the committed finger-swipe path. */
public final class LiteMinimalSwipeInstrumentation extends WebTaskNavigationInstrumentation {
    interface Condition{boolean ok()throws Exception;}
    void await(Condition c,long ms,String message)throws Exception{long end=SystemClock.elapsedRealtime()+ms;while(SystemClock.elapsedRealtime()<end){if(c.ok())return;SystemClock.sleep(80);}throw new AssertionError(message);}
    boolean playing(int index)throws Exception{boolean[] ok={false};main(()->{tv.danmaku.ijk.media.player.IMediaPlayer p=(tv.danmaku.ijk.media.player.IMediaPlayer)field(activity,"player");ok[0]=(Integer)field(activity,"currentChannelIndex")==index&&(Boolean)field(activity,"videoRenderingStarted")&&p!=null&&p.isPlaying()&&p.getCurrentPosition()>300&&field(activity,"liteWebPlayback")!=null;});return ok[0];}
    @Override public void onStart(){
        Bundle result=new Bundle();JSONObject report=new JSONObject();int code=-1;ServerSocket server=null;
        SharedPreferences prefs=getTargetContext().getSharedPreferences("tv_player",0);Map<String,?> saved=new HashMap<>(prefs.getAll());ChannelCatalog.Group[] savedGroups=ChannelCatalog.GROUPS;
        ArrayList<String> keys=new ArrayList<>();ChannelHealth health=null;
        try{
            byte[][] segments=new byte[6][];for(int i=0;i<6;i++)try(InputStream input=getContext().getAssets().open("mse/seg"+i+".ts");ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                byte[] b=new byte[8192];int n;while((n=input.read(b))>0)bytes.write(b,0,n);segments[i]=bytes.toByteArray();
            }
            server=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));final ServerSocket fixture=server;
            new Thread(()->{while(!fixture.isClosed())try(Socket socket=fixture.accept()){
                socket.setSoTimeout(3000);BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),"UTF-8"));String first=reader.readLine(),line;if(first==null)continue;
                while((line=reader.readLine())!=null&&!line.isEmpty()){}String path=first.split(" ")[1],type;byte[] data;
                if(path.endsWith(".ts")){type="video/mp2t";data=segments[Integer.parseInt(path.substring(path.lastIndexOf('/')+2,path.length()-3))%6];}
                else if(path.endsWith(".m3u8")){
                    type="application/vnd.apple.mpegurl";StringBuilder list=new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n");
                    for(int i=0;i<60;i++){if(i>0&&i%6==0)list.append("#EXT-X-DISCONTINUITY\n");list.append("#EXTINF:2,\n/s").append(i%6).append(".ts\n");}data=list.toString().getBytes("UTF-8");
                }else{type="text/html; charset=utf-8";data="<!doctype html><html><body><video autoplay controls src='/live.m3u8'></video></body></html>".getBytes("UTF-8");}
                OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: "+type+"\r\nContent-Length: "+data.length+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));out.write(data);out.flush();
            }catch(Exception ignored){}},"lite-swipe-fixture").start();
            String base="http://127.0.0.1:"+server.getLocalPort();Channel[] channels={new Channel("1","地方台A","","webview://"+base+"/a.html",null,null),new Channel("2","地方台B","","webview://"+base+"/b.html",null,null)};for(Channel c:channels)keys.add(ChannelHealth.key(c));
            prefs.edit().putBoolean("auto_update_channel_list",false).putBoolean("neighbor_probe_enabled",false).commit();
            ActivityMonitor launch=addMonitor(MainActivity.class.getName(),null,false);Bundle phase=new Bundle();phase.putString("phase","await_main");sendStatus(0,phase);activity=(MainActivity)launch.waitForActivityWithTimeout(15000);removeMonitor(launch);if(activity==null)throw new AssertionError("No foreground activity");
            await(()->{boolean[] focused={false};main(()->focused[0]=activity.hasWindowFocus());return focused[0];},8000,"Device must be awake and unlocked for Surface swipe verification");
            main(()->{
                call("stopCurrentChannelLoad");((java.util.concurrent.atomic.AtomicInteger)field(activity,"catalogLoadGeneration")).incrementAndGet();((TvUiMenu)field(activity,"tvUiMenu")).dismiss();call("closeChannelList");
                ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("轻量翻页测试",ChannelCatalog.SOURCE_CUSTOM,channels)};
                set("quickChannel",null);set("remoteCatalogUrl","");set("currentGroupIndex",0);set("playerEngine","ijk");set("defaultDecodeMode","software");set("decodeMode","software");set("rememberChannelDecode",false);set("rememberChannelSource",false);
                set("webViewMinimalMode",true);set("webViewAutoPlay",true);set("webViewAutoPlaySniffed",true);set("webViewPageCover",false);set("defaultWebSourceEngine","lite");set("neighborProbeEnabled",false);set("lowResourceDevice",false);set("autoSwitchSource",false);
                ((ChannelPlaybackMemory)field(activity,"playbackMemory")).rememberEngine(WebDomainSettings.key(channels[0].url),"lite");call("switchChannel",new Class<?>[]{int.class},0);
            });
            health=(ChannelHealth)field(activity,"channelHealth");await(()->playing(0),20000,"Initial lightweight HLS did not render");
            report.put("initialLiteNativePlayback",true).put("sdk",Build.VERSION.SDK_INT).put("model",Build.MODEL);
            for(int target:new int[]{1,0}){
                int offset=target==1?1:-1;float direction=target==1?-1f:1f;
                main(()->{call("setPlaybackLayerTranslation",new Class<?>[]{float.class,float.class},0f,direction*80f);call("animateRelativeChannelSwitch",new Class<?>[]{int.class,float.class,float.class},offset,0f,direction);call("resetPlaybackGesture");});
                SystemClock.sleep(60);
                main(()->{
                    WebLastFrame last=(WebLastFrame)field(activity,"webLastFrame");boolean staticCover=last!=null&&last.active()&&!last.isSwipePreview()&&!last.isNativeSwipe();
                    if(staticCover)throw new AssertionError("Fixed system-WebView snapshot obscures native Lite swipe");
                    View outgoing=(View)field(activity,"retiringVideoView");if(outgoing==null)outgoing=(View)field(activity,"videoView");
                    if(outgoing.getTranslationY()*direction<=0)throw new AssertionError("Outgoing video did not move with swipe");
                    if(last!=null&&last.isNativeSwipe()&&last.animationView().getTranslationY()*direction<=0)throw new AssertionError("Retained decoded frame did not move");
                    if(last!=null&&last.isNativeSwipe()){
                        android.graphics.Bitmap bitmap=(android.graphics.Bitmap)field(last,"frame");Set<Integer> colors=new HashSet<>();
                        for(int row=1;row<=4;row++)for(int col=1;col<=4;col++)colors.add(bitmap.getPixel(bitmap.getWidth()*col/5,bitmap.getHeight()*row/5));
                        if(colors.size()<4)throw new AssertionError("Retained Surface snapshot has no decoded test-picture content");
                        report.put("retainedVideoPixelsVerified",true);
                    }
                });
                await(()->playing(target),20000,"Incoming lightweight channel did not render");
                await(()->!(Boolean)field(activity,"channelSwitchAnimating"),3000,"Swipe ownership stuck");
                main(()->{View video=(View)field(activity,"videoView");if(Math.abs(video.getTranslationY())>1||Math.abs(video.getTranslationX())>1)throw new AssertionError("Incoming video not reset");WebLastFrame last=(WebLastFrame)field(activity,"webLastFrame");if(last!=null&&last.active())throw new AssertionError("Outgoing retained frame was not released");if(!((Boolean)call("canStartPlaybackGesture",new Class<?>[0])))throw new AssertionError("Next gesture blocked");});
            }
            report.put("forwardAndReverseSwipe",true).put("noFixedWebSnapshot",true).put("incomingFramesAndGestureRecovered",true);
            int request=(Integer)field(activity,"playRequestId");main(()->{call("setPlaybackLayerTranslation",new Class<?>[]{float.class,float.class},0f,-40f);call("animateGestureRebound");});SystemClock.sleep(400);
            main(()->{View video=(View)field(activity,"videoView");if(Math.abs(video.getTranslationY())>1||(Integer)field(activity,"playRequestId")!=request)throw new AssertionError("Short swipe failed to rebound without tuning");});
            report.put("shortSwipeRebound",true).put("passed",true);
        }catch(Throwable error){code=0;try{report.put("passed",false).put("failure",android.util.Log.getStackTraceString(error));}catch(Exception ignored){}}
        finally{
            try{if(activity!=null){main(()->{call("stopCurrentChannelLoad");activity.finish();});long end=SystemClock.elapsedRealtime()+3000;while(!activity.isDestroyed()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(50);}}catch(Exception ignored){}
            if(health!=null)try{((ExecutorService)field(health,"worker")).awaitTermination(10,TimeUnit.SECONDS);}catch(Exception ignored){}
            try{android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(getTargetContext().getDatabasePath("channel-health.db").getPath(),null,0);for(String key:keys)db.delete("health","id=?",new String[]{key});db.close();}catch(Exception ignored){}
            if(server!=null)try{server.close();}catch(Exception ignored){}ChannelCatalog.GROUPS=savedGroups;restore(prefs,saved);
            try{report.put("preferencesRestored",saved.equals(prefs.getAll()));File dir=new File(getTargetContext().getExternalFilesDir(null),"web-task");dir.mkdirs();try(FileOutputStream file=new FileOutputStream(new File(dir,"result.json"))){file.write(report.toString(2).getBytes("UTF-8"));}result.putString("stream",report.toString());}catch(Exception ignored){}finish(code,result);
        }
    }
}
