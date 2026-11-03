package xiao.bu.tv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.*;
import android.view.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Local deterministic media and current app flows; same package, no catalog/database replacement. */
public final class CandidateRegressionInstrumentation extends Instrumentation {
    private volatile MainActivity activity;
    private Bundle args;
    private Fixture fixture;
    private String base,theme;
    private SharedPreferences preferences;
    private Map<String,?> saved;
    private final JSONArray results=new JSONArray();
    private int failures;
    interface Work {void run() throws Exception;}
    interface Condition {boolean get() throws Exception;}
    public void onCreate(Bundle value){super.onCreate(value);args=value;start();}
    public void callActivityOnResume(Activity value){super.callActivityOnResume(value);if(value instanceof MainActivity)activity=(MainActivity)value;}
    private Object get(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    private Object call(String name,Class<?>[] types,Object... values)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);try{return m.invoke(activity,values);}catch(InvocationTargetException e){Throwable cause=e.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new AssertionError(cause);}}
    private void ui(Work work)throws Exception{CountDownLatch latch=new CountDownLatch(1);Throwable[] error={null};new Handler(Looper.getMainLooper()).post(()->{try{work.run();}catch(Throwable e){error[0]=e;}finally{latch.countDown();}});if(!latch.await(8,TimeUnit.SECONDS))throw new AssertionError("UI timeout 8 seconds");if(error[0]!=null)throw new AssertionError(error[0]);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private void until(Condition condition,long timeout,String message)throws Exception{long deadline=SystemClock.elapsedRealtime()+timeout;while(!condition.get()&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(50);check(condition.get(),message);}
    private JSONObject media()throws Exception{final JSONObject[] value={null};ui(()->value[0]=(JSONObject)call("buildLocalMediaState",new Class[]{boolean.class},true));return value[0];}
    private void ready()throws Exception{until(()->{JSONObject m=media();return m.optBoolean("prepared")&&(Boolean)get("videoRenderingStarted")&&((Integer)get("playbackReadyRequestId")).intValue()==((Integer)get("playRequestId")).intValue();},20000,"No actual new first frame: "+media());}
    private void open(String path,String title)throws Exception{ui(()->call("playQuickMedia",new Class[]{String.class,String.class,File.class},base+path,title,null));ready();}
    private void settings(JSONObject value)throws Exception{call("handleWebSettings",new Class[]{JSONObject.class},value);}
    private void restartSettings(JSONObject value)throws Exception{int previous=(Integer)get("playRequestId");settings(value);until(()->((Integer)get("playRequestId")).intValue()!=previous,8000,"Settings did not restart current item");ready();}
    private void control(JSONObject value)throws Exception{call("handleMediaControl",new Class[]{JSONObject.class},value);}
    private void test(String name,Work work)throws Exception{
        String only=args==null?"":args.getString("only","");if(!only.isEmpty()&&!name.contains(only))return;
        long start=SystemClock.elapsedRealtime();JSONObject row=new JSONObject().put("case",name).put("api",Build.VERSION.SDK_INT);
        try{work.run();row.put("pass",true);}catch(Throwable error){failures++;row.put("pass",false).put("error",android.util.Log.getStackTraceString(error));}
        row.put("ms",SystemClock.elapsedRealtime()-start);results.put(row);Bundle b=new Bundle();b.putString("stream","CASE "+row+"\n");sendStatus(1,b);
    }
    private void mediaTests()throws Exception{
        test("ijk-mp4-first-frame-pause-seek-resume",()->{
            settings(new JSONObject().put("playerEngine","ijk").put("decodeMode","auto"));
            open("video.mp4?first","Fixture MP4");JSONObject m=media();long position=m.getLong("positionMs");
            SystemClock.sleep(1300);check(media().getLong("positionMs")>position+600,"Playback position stalled");
            control(new JSONObject().put("action","pause"));position=media().getLong("positionMs");SystemClock.sleep(350);
            check(Math.abs(media().getLong("positionMs")-position)<250,"Pause ignored");
            control(new JSONObject().put("action","seek").put("positionMs",12000).put("sourceKey",m.getString("sourceKey")));
            control(new JSONObject().put("action","play"));until(()->media().getLong("positionMs")>11000,6000,"Seek did not advance");
        });
        test("stale-vod-seek-cannot-seek-new-item",()->{
            open("video.mp4?old","Old VOD");String old=media().getString("sourceKey");
            open("video.mp4?new","New VOD");check(!old.equals(media().getString("sourceKey")),"Playback key did not change");
            boolean rejected=false;try{control(new JSONObject().put("action","seek").put("positionMs",45000).put("sourceKey",old));}catch(IOException|JSONException expected){rejected=true;}
            check(rejected,"Old VOD sourceKey was accepted by current player");check(media().getLong("positionMs")<12000,"Stale command moved new item");
        });
        test("quick-item-survives-decoder-and-engine-changes",()->{
            open("video.mp4?decoder","Decoder fixture");
            for(String mode:new String[]{"software","hardware","auto"}){
                restartSettings(new JSONObject().put("decodeMode",mode));check("Decoder fixture".equals(media().getString("name")),"Decoder change lost temporary item");
                check(mode.equals(get("decodeMode")),"Decoder selection not retained");
            }
            restartSettings(new JSONObject().put("playerEngine","system"));
            check(get("player") instanceof tv.danmaku.ijk.media.player.AndroidMediaPlayer,"System engine not selected");
            check("Decoder fixture".equals(media().getString("name")),"System engine lost temporary item");
            restartSettings(new JSONObject().put("playerEngine","ijk"));
        });
        test("direct-mp4-download-range-and-stale-key",()->{
            open("video.mp4?download","Download fixture");JSONObject m=media();check(m.optBoolean("fileDownloadAvailable"),"MP4 download missing");
            String url="http://127.0.0.1:"+((LocalControlServer)get("controlServer")).getPort()+"/api/media/download?sourceKey=";
            HttpURLConnection c=(HttpURLConnection)new URL(url+URLEncoder.encode(m.getString("sourceKey"),"UTF-8")).openConnection();
            c.setRequestProperty("Range","bytes=0-1023");c.setConnectTimeout(5000);c.setReadTimeout(5000);
            try{check(c.getResponseCode()==206,"Range download status "+c.getResponseCode());byte[] bytes=read(c.getInputStream(),2048);check(bytes.length==1024,"Incorrect download range length");}finally{c.disconnect();}
            c=(HttpURLConnection)new URL(url+"stale").openConnection();c.setReadTimeout(5000);try{check(c.getResponseCode()>=400,"Old download key accepted");}finally{c.disconnect();}
        });
        test("hls-vod-first-frame-and-live-buffer-identity",()->{
            open("finite.m3u8","HLS VOD fixture");check(media().getLong("durationMs")>10000,"HLS VOD has no duration");
            open("live.m3u8","HLS live fixture");JSONObject m=media();check(m.getLong("durationMs")<=0,"Live marked as VOD");
            boolean rejected=false;try{control(new JSONObject().put("action","liveSeek").put("sourceKey","old").put("advanceMs",1000));}catch(IOException expected){rejected=true;}
            check(rejected,"Stale live-buffer command accepted");
            until(()->media().optLong("liveBufferSeekMaxMs")>=1000,6000,"No live buffer available");
            control(new JSONObject().put("action","liveSeek").put("sourceKey",media().getString("sourceKey")).put("advanceMs",500));
            until(()->!media().optBoolean("liveBufferAdjusting"),7000,"Live buffer adjustment did not complete");
        });
        test("failed-temporary-address-stays-on-requested-item",()->{
            ui(()->call("playQuickMedia",new Class[]{String.class,String.class,File.class},base+"missing.m3u8","Missing fixture",null));
            SystemClock.sleep(4000);check("Missing fixture".equals(media().getString("name")),"Failed temporary URL jumped into catalog");
            open("video.mp4?recovered","Recovered fixture");
        });
    }
    private void settingsAndUi()throws Exception{
        test("timeout-all-values-persist",()->{for(int seconds:new int[]{0,5,10,15,20,25,30}){
            settings(new JSONObject().put("autoSwitchSourceSeconds",seconds));check(((Integer)get("autoSwitchSourceSeconds")).intValue()==seconds,"Timeout selection reset");}
            settings(new JSONObject().put("autoSwitchSourceSeconds",0));});
        test("web-channel-memory-capability-and-settings",()->{
            settings(new JSONObject().put("rememberChannelUserAgent",true).put("rememberChannelWebEngine",true));
            check((Boolean)get("rememberChannelUserAgent"),"UA memory not enabled");
            check(((Boolean)get("rememberChannelWebEngine")).booleanValue()==LiteWebPlayback.supported(),"Engine memory capability mismatch");
            for(String ua:new String[]{"auto","windows","ipad","macos","native"}){settings(new JSONObject().put("webViewUserAgent",ua));check(ua.equals(get("webViewUserAgent")),"UA setting lost");}
        });
        for(String selected:new String[]{"builtin","ui2","ui3","ui4","ui5","ui6"})test("theme-menu-password-8-digit-confirm-"+selected,()->{
            TvUiPlugin.select(getTargetContext(),selected);SystemClock.sleep(120);
            if(!"builtin".equals(selected)){
                ui(()->{call("openTvUiMenu",new Class[]{});check(((TvUiMenu)get("tvUiMenu")).visible(),"Theme menu did not open");});SystemClock.sleep(100);
                ui(()->((TvUiMenu)get("tvUiMenu")).dismiss());
            }
            AtomicBoolean accepted=new AtomicBoolean();GroupPasswordDialog[] dialog={null};
            try{ui(()->{dialog[0]=new GroupPasswordDialog(activity,"Test",8,input->{accepted.set("07091234".equals(input));return accepted.get();});dialog[0].show();
                    for(char digit:"07091234".toCharArray())key(dialog[0],KeyEvent.KEYCODE_0+digit-'0');
                    check(dialog[0].isShowing()&&!accepted.get(),"Password auto-submitted before confirm");key(dialog[0],KeyEvent.KEYCODE_ENTER);
                    check(accepted.get()&&!dialog[0].isShowing(),"Remote Enter did not unlock 8-digit password");});
            }finally{ui(()->{if(dialog[0]!=null)dialog[0].dismiss();});}
        });
        test("native-minimal-cursor-movement-only",()->{
            FlyMouseCursorView cursor=(FlyMouseCursorView)get("flyMouseCursor");ui(()->{
                cursor.setVisibility(View.VISIBLE);cursor.setMovementOnly(true);cursor.resetPosition();cursor.revealCursor();cursor.pulseClick();cursor.moveBy(0,0);
                check(!cursor.isCursorVisible(),"Nonmovement revealed minimal cursor");cursor.moveBy(10,0);check(cursor.isCursorVisible(),"Movement did not reveal cursor");cursor.hideCursor();cursor.setMovementOnly(false);
            });
        });
    }
    private void webTests()throws Exception{
        for(String engine:new String[]{"system","lite"}){
            if("lite".equals(engine)&&!LiteWebPlayback.supported())continue;
            test("web-"+engine+"-ujs-click-native-handoff",()->{
                settings(new JSONObject().put("webSourceEngine",engine).put("webViewAutoPlay",true)
                        .put("webViewAutoPlaySniffed",true).put("webViewUserScriptEnabled",true)
                        .put("webViewMinimalMode",true).put("webViewPageCover",false));
                String script="document.getElementById('play').click();";
                String encoded=android.util.Base64.encodeToString(script.getBytes("UTF-8"),android.util.Base64.NO_WRAP);
                ui(()->call("playQuickMedia",new Class[]{String.class,String.class,File.class},
                        "webview://"+base+"page.html?ujs="+URLEncoder.encode(encoded,"UTF-8"),"Web "+engine,null));
                ready();check(media().optBoolean("playing"),"Sniffed local page is not playing");
            });
        }
    }
    private static void key(GroupPasswordDialog dialog,int key){long now=SystemClock.uptimeMillis();dialog.dispatchKeyEvent(new KeyEvent(now,now,0,key,0));dialog.dispatchKeyEvent(new KeyEvent(now,now+1,1,key,0));}
    private static byte[] read(InputStream input,int limit)throws IOException{try(InputStream in=input;ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] block=new byte[1024];int n;while((n=in.read(block))!=-1){if(bytes.size()+n>limit)throw new IOException("Response over limit");bytes.write(block,0,n);}return bytes.toByteArray();}}
    private void restorePreferences(){if(saved==null)return;SharedPreferences.Editor edit=preferences.edit();for(String key:preferences.getAll().keySet())if(!saved.containsKey(key))edit.remove(key);
        for(Map.Entry<String,?> e:saved.entrySet()){Object v=e.getValue();String k=e.getKey();if(v instanceof String)edit.putString(k,(String)v);else if(v instanceof Integer)edit.putInt(k,(Integer)v);else if(v instanceof Boolean)edit.putBoolean(k,(Boolean)v);else if(v instanceof Long)edit.putLong(k,(Long)v);else if(v instanceof Float)edit.putFloat(k,(Float)v);else if(v instanceof Set)edit.putStringSet(k,new HashSet<String>((Set<String>)v));}edit.commit();}
    public void onStart(){int code=-1;Bundle result=new Bundle();try{
        preferences=getTargetContext().getSharedPreferences(MainActivity.PREFERENCES,0);saved=preferences.getAll();theme=getTargetContext().getSharedPreferences("tv_ui",0).getString("selected","builtin");
        fixture=new Fixture(new File(getTargetContext().getExternalFilesDir(null),"candidate-fixtures"));base=fixture.base();
        getTargetContext().startActivity(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));until(()->activity!=null,30000,"Activity did not resume");
        mediaTests();settingsAndUi();webTests();if(failures>0)code=1;
    }catch(Throwable error){code=1;result.putString("setupError",android.util.Log.getStackTraceString(error));}
    finally{try{if(activity!=null)ui(()->{((TvUiMenu)get("tvUiMenu")).dismiss();call("closeWebSource",new Class[]{});call("releasePlayer",new Class[]{});call("clearQuickMedia",new Class[]{});activity.finish();});}catch(Exception ignored){}
        if(theme!=null)try{TvUiPlugin.select(getTargetContext(),theme);}catch(Exception ignored){}restorePreferences();if(fixture!=null)fixture.close();
        result.putString("stream","COMPLETE cases="+results.length()+" failures="+failures+"\n"+results);finish(code,result);}
    }
    private static final class Fixture implements AutoCloseable {
        final File root;final ServerSocket server;final ExecutorService clients=Executors.newFixedThreadPool(3);volatile boolean running=true;
        Fixture(File root)throws IOException{this.root=root;server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));Thread accept=new Thread(()->{while(running)try{Socket socket=server.accept();clients.execute(()->serve(socket));}catch(IOException ignored){}},"candidate-fixture-http");accept.setDaemon(true);accept.start();}
        String base(){return "http://127.0.0.1:"+server.getLocalPort()+"/";}
        void serve(Socket socket){try(Socket peer=socket){peer.setSoTimeout(5000);BufferedReader reader=new BufferedReader(new InputStreamReader(peer.getInputStream(),"UTF-8"));String first=reader.readLine();if(first==null)return;
            String path=first.split(" ")[1].split("\\?",2)[0];String line,range=null;while((line=reader.readLine())!=null&&!line.isEmpty())if(line.toLowerCase(Locale.US).startsWith("range:"))range=line.substring(6).trim();
            if(path.equals("/page.html")){
                byte[] html=("<!doctype html><meta charset=utf-8><title>UJS fixture</title><button id=play onclick=\"document.getElementById('video').src='finite.m3u8';document.getElementById('video').play();\">Play</button><video id=video controls></video>").getBytes("UTF-8");
                OutputStream output=peer.getOutputStream();output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "+html.length+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));output.write(html);return;
            }
            File file=new File(root,path.substring(1));OutputStream output=peer.getOutputStream();if(!file.isFile()){output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));return;}
            long length=file.length(),start=0,end=length-1;if(range!=null&&range.startsWith("bytes=")){String[] parts=range.substring(6).split("-",2);if(!parts[0].isEmpty())start=Long.parseLong(parts[0]);if(parts.length>1&&!parts[1].isEmpty())end=Math.min(end,Long.parseLong(parts[1]));}
            String type=path.endsWith(".m3u8")?"application/vnd.apple.mpegurl":path.endsWith(".ts")?"video/mp2t":"video/mp4";
            String headers="HTTP/1.1 "+(range==null?"200 OK":"206 Partial Content")+"\r\nContent-Type: "+type+"\r\nContent-Length: "+(end-start+1)+"\r\nAccept-Ranges: bytes\r\n"+(range==null?"":"Content-Range: bytes "+start+"-"+end+"/"+length+"\r\n")+"Connection: close\r\n\r\n";
            output.write(headers.getBytes("UTF-8"));try(RandomAccessFile in=new RandomAccessFile(file,"r")){in.seek(start);byte[] block=new byte[32768];long left=end-start+1;while(left>0){int n=in.read(block,0,(int)Math.min(left,block.length));if(n<0)break;output.write(block,0,n);left-=n;}}output.flush();
        }catch(Exception ignored){}}
        public void close(){running=false;try{server.close();}catch(IOException ignored){}clients.shutdownNow();}
    }
}
