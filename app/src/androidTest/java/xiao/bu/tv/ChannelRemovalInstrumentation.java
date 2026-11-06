package xiao.bu.tv;

import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.*;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.*;
import android.view.KeyEvent;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** Real API-19 Activity/remote and kernel regression, with an isolated catalog. */
public final class ChannelRemovalInstrumentation extends Instrumentation {
    interface Work { void run() throws Exception; }
    final Handler main = new Handler(Looper.getMainLooper());
    MainActivity activity;
    JSONObject report = new JSONObject();
    ServerSocket server;
    SharedPreferences savedPlayerPreferences;
    Map<String,?> savedPlayerValues;
    volatile boolean closed;
    final AtomicInteger requests = new AtomicInteger();
    String base;
    void check(boolean condition, String text) { if (!condition) throw new AssertionError(text); }
    void ui(Work action) throws Exception {
        CountDownLatch done = new CountDownLatch(1); Throwable[] error = {null};
        main.post(() -> {try {action.run();} catch(Throwable e) {error[0]=e;} finally {done.countDown();}});
        if(!done.await(2500,TimeUnit.MILLISECONDS)){
            StringBuilder stacks=new StringBuilder();for(Map.Entry<Thread,StackTraceElement[]> entry:Thread.getAllStackTraces().entrySet()){stacks.append(entry.getKey().getName()).append('\n');for(StackTraceElement frame:entry.getValue())stacks.append("  ").append(frame).append('\n');}
            report.put("stalledThreads",stacks.toString());throw new AssertionError("Main thread/remote stalled >2500 ms");
        }
        if(error[0]!=null) throw new Exception(error[0]);
    }
    Object get(String name) throws Exception {Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    void set(String name,Object value) throws Exception {Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}
    Object call(String name,Class<?>[] types,Object...args) throws Exception {Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args);}
    void call(String name) throws Exception {call(name,new Class<?>[0]);}
    void key(int code) {activity.dispatchKeyEvent(new KeyEvent(0,code));activity.dispatchKeyEvent(new KeyEvent(1,code));}
    void await(Callable<Boolean> condition,long ms,String reason) throws Exception {
        long until=SystemClock.elapsedRealtime()+ms;
        while(SystemClock.elapsedRealtime()<until){if(condition.call())return;SystemClock.sleep(40);}
        throw new AssertionError(reason);
    }
    @Override public void onCreate(Bundle args) {super.onCreate(args);start();}
    @Override public void onStart() {
        Bundle result=new Bundle();int code=0;
        try {
            startServer(); catalog(); activity(); report.put("passed",true);
        } catch(Throwable e) {code=-1;try {report.put("passed",false).put("failure",android.util.Log.getStackTraceString(e));}catch(Exception ignored){}}
        finally {
            closed=true;try{if(server!=null)server.close();}catch(Exception ignored){}
            try {if(activity!=null)ui(()->{call("stopCurrentChannelLoad");activity.finish();});}catch(Throwable ignored){}
            if(savedPlayerPreferences!=null&&savedPlayerValues!=null){
                SharedPreferences.Editor edit=savedPlayerPreferences.edit().clear();
                for(Map.Entry<String,?> entry:savedPlayerValues.entrySet()){
                    String key=entry.getKey();Object value=entry.getValue();
                    if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);
                    else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof Long)edit.putLong(key,(Long)value);
                    else if(value instanceof Float)edit.putFloat(key,(Float)value);else if(value instanceof java.util.Set)edit.putStringSet(key,new java.util.HashSet<String>((java.util.Set<String>)value));
                }
                edit.commit();
            }
            try {File out=new File(getTargetContext().getExternalFilesDir(null),"channel-removal-result.json");try(FileOutputStream f=new FileOutputStream(out)){f.write(report.toString(2).getBytes("UTF-8"));}result.putString("resultFile",out.toString());}catch(Exception ignored){}
            result.putString("result",report.toString());finish(code,result);
        }
    }
    /** Every database, preference and imported file belongs only to this test run. */
    final class Isolated extends ContextWrapper {
        final String prefix="removal_"+System.nanoTime()+"_";
        Isolated(){super(getTargetContext());}
        @Override public android.content.res.AssetManager getAssets(){return ChannelRemovalInstrumentation.this.getContext().getAssets();}
        @Override public Context getApplicationContext(){return this;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences(prefix+name,mode);}
        @Override public File getFilesDir(){return getDir(prefix,0);}
        @Override public File getDatabasePath(String name){return super.getDatabasePath(prefix+name);}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return super.openOrCreateDatabase(prefix+name,mode,factory);}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler){return super.openOrCreateDatabase(prefix+name,mode,factory,handler);}
    }
    Isolated isolated; PlaylistManager manager; JSONArray sources;
    String playlist(String bad) {return "#EXTM3U\n#EXTINF:-1 group-title=\"移除测试\",失效频道\nwebview://"+bad+"\n#EXTINF:-1 group-title=\"移除测试\",正常频道\nwebview://"+base+"/good\n#EXTINF:-1 group-title=\"移除测试\",手动隐藏\nhttp://127.0.0.1:9/hidden.m3u8\n";}
    boolean contains(ChannelCatalog.Group[] groups,String name){for(ChannelCatalog.Group g:groups)for(Channel c:g.channels)if(c.name.equals(name))return true;return false;}
    void catalog() throws Exception {
        isolated=new Isolated();manager=new PlaylistManager(isolated);
        PlaylistManager.ImportedFile imported=manager.importLocalPlaylist("removal_fixture","fixture.m3u",playlist(base+"/404").getBytes("UTF-8"));
        sources=new JSONArray().put(new JSONObject().put("id","removal_fixture").put("name","移除回归").put("location",imported.location));
        manager.updateSources(sources);
        String id=PlaylistManager.channelGroupId("移除测试");
        JSONObject state=manager.getChannelSettingsJson(id);JSONArray order=new JSONArray(),hidden=new JSONArray();
        JSONArray channels=state.getJSONArray("channels");
        for(int i=channels.length()-1;i>=0;i--){JSONObject c=channels.getJSONObject(i);order.put(c.getString("id"));if(c.getString("name").equals("手动隐藏"))hidden.put(c.getString("id"));}
        manager.updateChannelSettings(new JSONObject().put("groupId",id).put("revision",state.getString("revision")).put("order",order).put("hidden",hidden));
        ChannelCatalog.Group[] removed=manager.removeChannel(id,"失效频道");check(!contains(removed,"失效频道")&&contains(removed,"正常频道"),"Remove did not filter catalog");
        PlaylistManager fresh=new PlaylistManager(isolated);check(!contains(fresh.loadCached(),"失效频道"),"Removal lost across manager restart");
        manager.importLocalPlaylist("removal_fixture","fixture.m3u",playlist(base+"/changed").getBytes("UTF-8"));
        check(!contains(manager.updateSources(sources).groups,"失效频道"),"URL refresh restored removed channel");
        ChannelCatalog.Group[] restored=manager.restoreRemovedChannels();check(contains(restored,"失效频道")&&!contains(restored,"手动隐藏"),"Restore changed independent manual hiding");
        JSONObject after=manager.getChannelSettingsJson(id);check(after.getJSONArray("channels").getJSONObject(0).getString("id").equals(order.getString(0)),"Restore changed custom order");
        manager.removeChannel(id,"失效频道");boolean rejected=false;try{manager.removeChannel(id,"正常频道");}catch(IOException expected){rejected=true;}
        check(rejected&&contains(new PlaylistManager(isolated).loadCached(),"正常频道"),"Last-visible-channel guard corrupted persistence");
        rejected=false;try{manager.removeChannel(id,"已不存在");}catch(IOException expected){rejected=true;}check(rejected,"Stale removal accepted");
        manager.restoreRemovedChannels();
        report.put("catalog",new JSONObject().put("persistentRemoval",true).put("urlRefreshKeepsRemoval",true).put("restorePreservesManualHidingAndOrder",true).put("lastVisibleGuard",true).put("staleSelectionGuard",true));
    }
    void startServer() throws Exception {
        server=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));base="http://127.0.0.1:"+server.getLocalPort();
        new Thread(()->{while(!closed)try{Socket socket=server.accept();new Thread(()->serve(socket),"removal-http").start();}catch(IOException e){break;}},"removal-http-listener").start();
    }
    void serve(Socket socket) {
        try(Socket s=socket){s.setSoTimeout(3000);BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream(),"US-ASCII"));String line=in.readLine();if(line==null)return;String path=line.split(" ")[1];while((line=in.readLine())!=null&&!line.isEmpty()){}requests.incrementAndGet();
            if(path.startsWith("/slow")){while(!closed&&!s.isClosed())SystemClock.sleep(100);return;}
            if(path.startsWith("/mse/")&&!path.contains("..")){
                ByteArrayOutputStream body=new ByteArrayOutputStream();try(InputStream asset=getContext().getAssets().open(path.substring(1))){byte[] buffer=new byte[8192];for(int count;(count=asset.read(buffer))>=0;)body.write(buffer,0,count);}
                byte[] data=body.toByteArray();OutputStream out=s.getOutputStream();String type=path.endsWith("html")?"text/html":"application/octet-stream";out.write(("HTTP/1.1 200 OK\r\nContent-Type: "+type+"\r\nContent-Length: "+data.length+"\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));out.write(data);out.flush();return;
            }
            String html="<!doctype html><html><body><h1>正常网页</h1><button onclick=\"this.textContent='已点击'\">登录按钮</button></body></html>";
            if(path.startsWith("/flood"))html="<!doctype html><html><body>繁忙脚本<script>for(var i=0;i<12000;i++)console.log('flood '+i);var until=Date.now()+8000;while(Date.now()<until){}</script></body></html>";
            boolean missing=path.startsWith("/404"),htmlError=path.startsWith("/404-html");
            byte[] bytes=(missing&&!htmlError?"{\"error\":\"not found\"}":html).getBytes("UTF-8");OutputStream out=s.getOutputStream();out.write(("HTTP/1.1 "+(missing?"404 Not Found":"200 OK")+"\r\nContent-Type: "+(missing&&!htmlError?"application/json":"text/html; charset=utf-8")+"\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));out.write(bytes);out.flush();
        }catch(Exception ignored){}
    }
    void open(String path) throws Exception {
        ui(()->{call("stopCurrentChannelLoad");((TvUiMenu)get("tvUiMenu")).dismiss();call("closeChannelList");int request=(Integer)get("playRequestId");call("openLiteWebSource",new Class<?>[]{Channel.class,String.class,int.class,String.class},ChannelCatalog.GROUPS[(Integer)get("currentGroupIndex")].channels[(Integer)get("currentChannelIndex")],"webview://"+base+path,request,"");});
    }
    void remote(JSONObject row) throws Exception {
        ui(()->call("closeChannelList"));
        long begin=SystemClock.elapsedRealtime();ui(()->{key(KeyEvent.KEYCODE_MENU);AlertDialog nativeMenu=(AlertDialog)get("channelActionsDialog");check(((TvUiMenu)get("tvUiMenu")).visible()||nativeMenu!=null&&nativeMenu.isShowing(),"MENU did not open real popup");});
        row.put("menuLatencyMs",SystemClock.elapsedRealtime()-begin);
        ui(()->{TvUiMenu menu=(TvUiMenu)get("tvUiMenu");AlertDialog nativeMenu=(AlertDialog)get("channelActionsDialog");if(nativeMenu!=null){nativeMenu.dispatchKeyEvent(new KeyEvent(0,KeyEvent.KEYCODE_BACK));nativeMenu.dispatchKeyEvent(new KeyEvent(1,KeyEvent.KEYCODE_BACK));check(!nativeMenu.isShowing(),"BACK did not dismiss native menu");}else{android.view.View content=menu.contentView();check(content!=null,"Themed menu disappeared");content.dispatchKeyEvent(new KeyEvent(0,KeyEvent.KEYCODE_BACK));content.dispatchKeyEvent(new KeyEvent(1,KeyEvent.KEYCODE_BACK));}});
        // Explicitly dismiss if the selected theme uses BACK to return to categories.
        ui(()->((TvUiMenu)get("tvUiMenu")).dismiss());
        row.put("backResponsive",true);
    }
    void activity() throws Exception {
        SharedPreferences prefs=getTargetContext().getSharedPreferences(MainActivity.PREFERENCES,0);
        savedPlayerPreferences=prefs;savedPlayerValues=new java.util.HashMap<String,Object>(prefs.getAll());
        boolean had=prefs.contains("auto_update_channel_list"),old=prefs.getBoolean("auto_update_channel_list",false);
        String oldTheme=getTargetContext().getSharedPreferences("tv_ui",0).getString("selected","builtin");
        prefs.edit().putBoolean("auto_update_channel_list",false).commit();
        try {
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(2000);
            ui(()->{call("stopCurrentChannelLoad");((AtomicInteger)get("catalogLoadGeneration")).incrementAndGet();set("playlistManager",manager);set("webSourceEngine","lite");set("autoSwitchSource",false);});
            call("applyPlaylistGroupVisibility",new Class<?>[]{ChannelCatalog.Group[].class},(Object)manager.loadCached());
            ui(()->{call("stopCurrentChannelLoad");set("quickChannel",null);set("remoteCatalogUrl","");for(int i=0;i<ChannelCatalog.GROUPS.length;i++)if(ChannelCatalog.GROUPS[i].title.equals("移除测试")){set("currentGroupIndex",i);for(int j=0;j<ChannelCatalog.GROUPS[i].channels.length;j++)if(ChannelCatalog.GROUPS[i].channels[j].name.equals("失效频道"))set("currentChannelIndex",j);}set("currentSourceIndex",0);});
            open("/404");await(()->{final boolean[] done={false};ui(()->done[0]=get("liteWebPlayback")==null&&!(Boolean)get("loadingActive"));return done[0];},12000,"HTTP 404 did not stop loading");
            ui(()->check(((Integer)get("webOriginalFallbackRequestId")).intValue()!=((Integer)get("playRequestId")).intValue(),"HTTP error launched legacy WebView"));
            JSONObject error=new JSONObject();remote(error);report.put("http404",error.put("stopped",true).put("legacyFallback",false));
            open("/slow");SystemClock.sleep(500);JSONObject slow=new JSONObject();remote(slow);
            // BACK cancels loading immediately, without waiting for the network.
            ui(()->{call("stopCurrentChannelLoad");check(get("liteWebPlayback")==null&&!(Boolean)get("loadingActive"),"Stop left pending page");});
            report.put("slowRequestCancellation",slow.put("stopped",true));
            long timeoutAt=SystemClock.elapsedRealtime();open("/slow-timeout");
            await(()->{boolean[] done={false};ui(()->done[0]=get("liteWebPlayback")==null&&!(Boolean)get("loadingActive"));return done[0];},34000,"Slow page exceeded 30-second sniff deadline");
            long timeoutMs=SystemClock.elapsedRealtime()-timeoutAt;check(timeoutMs<=32500,"Timeout exceeded allowance: "+timeoutMs);
            JSONObject deadline=new JSONObject().put("elapsedMs",timeoutMs);remote(deadline);report.put("pageTimeout",deadline);
            timeoutAt=SystemClock.elapsedRealtime();open("/404-html");
            SystemClock.sleep(11000);JSONObject htmlError=new JSONObject();remote(htmlError);
            await(()->{boolean[] done={false};ui(()->done[0]=get("liteWebPlayback")==null&&!(Boolean)get("loadingActive"));return done[0];},22500,"Rendered HTML error escaped 30-second deadline");
            timeoutMs=SystemClock.elapsedRealtime()-timeoutAt;check(timeoutMs>=29500&&timeoutMs<=32500,"HTML error deadline not exercised: "+timeoutMs);
            report.put("html404Timeout",htmlError.put("elapsedMs",timeoutMs));
            flood();
            JSONArray themes=new JSONArray();
            for(String theme:TvUiPlugin.IDS){
                TvUiPlugin.select(activity,theme);SystemClock.sleep(150);JSONObject row=new JSONObject().put("theme",theme);remote(row);themes.put(row);
            }
            report.put("themeRemoteMenus",themes);TvUiPlugin.select(activity,"builtin");SystemClock.sleep(100);
            ui(()->{call("stopCurrentChannelLoad");((TvUiMenu)get("tvUiMenu")).dismiss();call("confirmRemoveCurrentChannel");AlertDialog dialog=(AlertDialog)get("channelRemovalDialog");check(dialog!=null&&dialog.isShowing(),"Removal confirmation missing");check(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).hasFocus(),"Destructive action had default focus");dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();});
            check(contains(manager.loadCached(),"失效频道"),"Cancel removed channel");
            ui(()->{call("closeChannelList");key(KeyEvent.KEYCODE_MENU);AlertDialog menu=(AlertDialog)get("channelActionsDialog");check(menu!=null&&menu.isShowing(),"Native menu missing");menu.getListView().performItemClick(menu.getListView().getChildAt(1),1,1);((AlertDialog)get("channelRemovalDialog")).getButton(AlertDialog.BUTTON_POSITIVE).performClick();});
            await(()->{boolean[] ready={false};ui(()->ready[0]=!(Boolean)get("channelRemovalBusy"));return ready[0];},10000,"Removal background task stuck");
            check(!contains(ChannelCatalog.GROUPS,"失效频道"),"Removed channel remained in visible Activity catalog");
            ui(()->call("changeRemovedChannels",new Class<?>[]{String.class,String.class},null,null));
            await(()->{boolean[] ready={false};ui(()->ready[0]=!(Boolean)get("channelRemovalBusy"));return ready[0];},10000,"Restore background task stuck");
            check(contains(ChannelCatalog.GROUPS,"失效频道"),"Restore did not update visible Activity catalog");
            report.put("activityRemoval",new JSONObject().put("confirmationCancelFocus",true).put("cancelPreservesChannel",true).put("removeUpdatesVisibleCatalog",true).put("restoreUpdatesVisibleCatalog",true));
            open("/mse/mp4-remux-probe.html");
            await(()->{boolean[] ready={false};ui(()->ready[0]=(Boolean)get("prepared")&&(Boolean)get("videoRenderingStarted"));return ready[0];},30000,"Valid MSE did not render after failure/removal");
            SystemClock.sleep(1200);ui(()->{tv.danmaku.ijk.media.player.IMediaPlayer player=(tv.danmaku.ijk.media.player.IMediaPlayer)get("player");check(player!=null&&player.getCurrentPosition()>0,"MSE video clock did not advance");report.put("mseRecovery",new JSONObject().put("renderingStarted",true).put("positionMs",player.getCurrentPosition()).put("backend",player.getClass().getSimpleName()));});
            ui(()->{call("stopCurrentChannelLoad");call("closeChannelList");call("openManagement");AlertDialog menu=(AlertDialog)get("channelActionsDialog");menu.getListView().performItemClick(menu.getListView().getChildAt(1),1,1);});
            SystemClock.sleep(250);
            android.graphics.Bitmap image=getUiAutomation().takeScreenshot();if(image!=null){try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),"channel-removal.png"))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}image.recycle();}
            ui(()->{AlertDialog dialog=(AlertDialog)get("channelRemovalDialog");if(dialog!=null)dialog.dismiss();});
            report.put("sdk",Build.VERSION.SDK_INT).put("abi",Build.CPU_ABI).put("httpRequests",requests.get());
        } finally {TvUiPlugin.select(getTargetContext(),oldTheme);SharedPreferences.Editor edit=prefs.edit();if(had)edit.putBoolean("auto_update_channel_list",old);else edit.remove("auto_update_channel_list");edit.commit();}
    }
    void flood() throws Exception {
        final LiteEngine[] engine={null};AtomicInteger delivered=new AtomicInteger();
        ui(()->engine[0]=new LiteEngine(getTargetContext(),new LiteEngine.Listener(){public void frame(JSONObject frame){}public void message(String text){delivered.incrementAndGet();}public void navigate(String url){}public void media(String url,Map<String,String> headers){}}));
        try {
            engine[0].load(base+"/flood");
            await(()->engine[0].uiQueueStatistics().optLong("droppedMessages")>0,15000,"Flood did not exercise bounded message queue");
            JSONObject stats=engine[0].uiQueueStatistics();check(stats.getInt("pendingMessages")<=32&&stats.getInt("pendingMessageChars")<=65536&&stats.getInt("pendingFrames")<=1,"UI queue exceeded bound");
            JSONObject row=new JSONObject();remote(row);row.put("queue",stats).put("deliveredMessages",delivered.get());report.put("busyScript",row);
        } finally {ui(()->engine[0].close());}
    }
}
