package xiao.bu.tv;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.hardware.SensorEvent;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import tv.danmaku.ijk.media.player.IMediaPlayer;

/** API 14-compatible audit with local fixtures and an optional online provider phase. */
public final class LegacyFullInstrumentation extends Instrumentation {
    private MainActivity activity;private ManagementActivity management;private Bundle args;
    private Fixture server;private String base;private ChannelCatalog.Group[] original,fixtures;
    private ChannelCatalogStore fixtureStore;private ChannelCatalog.Group[] storedCatalog;private String storedFingerprint;
    private Map<String,?> saved;private String savedTheme;private int failures;private final JSONArray results=new JSONArray();
    private volatile boolean watch;private volatile long longestUiGap;private Thread monitor;
    private interface Work {void run()throws Exception;}
    private interface Condition {boolean get()throws Exception;}
    public void onCreate(Bundle value){super.onCreate(value);args=value;start();}
    private static Field field(Object owner,String name)throws Exception{
        Class<?> type=owner.getClass();while(type!=null){try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException e){type=type.getSuperclass();}}
        throw new NoSuchFieldException(name);
    }
    private Object get(String name)throws Exception{return field(activity,name).get(activity);}
    private void set(String name,Object value)throws Exception{field(activity,name).set(activity,value);}
    private Object call(String name,Class<?>[] types,Object... values)throws Exception{
        Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,values);
    }
    private void main(Work work)throws Exception{
        CountDownLatch done=new CountDownLatch(1);Throwable[] failure={null};
        new Handler(Looper.getMainLooper()).post(()->{try{work.run();}catch(Throwable e){failure[0]=e;}finally{done.countDown();}});
        if(!done.await(15,TimeUnit.SECONDS))throw new AssertionError("UI blocked for 15 seconds\n"+stacks());
        if(failure[0]!=null)throw new AssertionError(failure[0]);
    }
    private static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    private static String stacks(){StringBuilder out=new StringBuilder();for(Map.Entry<Thread,StackTraceElement[]> entry:Thread.getAllStackTraces().entrySet()){
        String name=entry.getKey().getName();if(!name.equals("main")&&!name.toLowerCase(Locale.US).contains("release"))continue;
        out.append(name).append(' ').append(entry.getKey().getState()).append('\n');for(StackTraceElement frame:entry.getValue())out.append(frame).append('\n');}return out.toString();}
    private void until(Condition condition,long timeout,String reason)throws Exception{
        long end=SystemClock.elapsedRealtime()+timeout;while(!condition.get()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(50);
        check(condition.get(),reason);
    }
    private void test(String name,Work work)throws Exception{
        String only=args.getString("only","");if(!only.isEmpty()&&!name.contains(only))return;
        JSONObject row=new JSONObject().put("case",name);long begin=SystemClock.elapsedRealtime();
        try{work.run();row.put("pass",true);}catch(Throwable error){failures++;row.put("pass",false).put("error",android.util.Log.getStackTraceString(error));}
        row.put("ms",SystemClock.elapsedRealtime()-begin);results.put(row);
        Bundle result=new Bundle();result.putString("stream",(row.getBoolean("pass")?"PASS ":"FAIL ")+row.toString()+"\n");sendStatus(0,result);
    }
    private void fixtureCatalog()throws Exception{
        StringBuilder text=new StringBuilder("#EXTM3U\n");
        for(String[] entry:new String[][]{{"Video","video.mp4"},{"Video","video.mp4?line=2"},{"Live","live.m3u8"},{"Radio","audio.mp3"}})
            text.append("#EXTINF:-1 group-title=\"Legacy fixtures\",").append(entry[0]).append('\n').append(base).append(entry[1]).append('\n');
        for(int i=0;i<1327;i++)text.append("#EXTINF:-1 group-title=\"Large list\",频道 ").append(i+1).append('\n').append(base).append("video.mp4?id=").append(i).append('\n');
        Method parse=PlaylistManager.class.getDeclaredMethod("parse",byte[].class,String.class);parse.setAccessible(true);
        fixtures=(ChannelCatalog.Group[])parse.invoke(null,text.toString().getBytes("UTF-8"),base+"fixture.m3u");
        if(activity!=null)main(()->{ChannelCatalog.GROUPS=fixtures;set("currentGroupIndex",0);set("currentChannelIndex",0);set("currentSourceIndex",0);});
    }
    private void stop()throws Exception{
        main(()->{set("playRequestId",(Integer)get("playRequestId")+1);call("cancelPendingCatchup",new Class[]{});call("cancelPendingRelativeSwitch",new Class[]{});
            call("closeWebSource",new Class[]{});call("releasePlayer",new Class[]{});});
    }
    private void start(int index)throws Exception{
        main(()->{ChannelCatalog.GROUPS=fixtures;set("currentGroupIndex",0);set("currentSourceIndex",0);set("catchupProgram",null);
            call("switchChannel",new Class[]{int.class,int.class},index,0);});
        until(()->(Boolean)get("prepared")&&((Boolean)get("audioOnlyPlayback")||(Boolean)get("videoRenderingStarted")),30000,"No first frame/audio for "+index);
    }
    private IMediaPlayer player()throws Exception{return (IMediaPlayer)get("player");}
    private void media()throws Exception{
        test("mp4-first-frame-pause-seek-resume",()->{
            long begin=SystemClock.elapsedRealtime();start(0);long first=SystemClock.elapsedRealtime()-begin;
            IMediaPlayer player=player();long before=player.getCurrentPosition();SystemClock.sleep(2500);
            check(player.getCurrentPosition()>before+1200,"Video clock stalled");
            main(()->player.pause());before=player.getCurrentPosition();SystemClock.sleep(500);
            check(Math.abs(player.getCurrentPosition()-before)<400,"Pause ignored");
            main(()->{player.seekTo(8000);player.start();});until(()->player.getCurrentPosition()>6500,8000,"Seek ignored");
            check((Boolean)get("videoRenderingStarted"),"No rendered video");
            android.util.Log.i("LegacyFull","mp4 firstFrameMs="+first+" backend="+player.getClass().getSimpleName());
        });
        test("source-change-and-list-channel-switch",()->{
            start(0);main(()->call("switchCustomSource",new Class[]{int.class,boolean.class,String.class},1,false,""));
            until(()->(Integer)get("currentSourceIndex")==1&&(Boolean)get("videoRenderingStarted"),30000,"Second source failed");
            main(()->{call("openChannelList",new Class[]{boolean.class},false);call("switchBrowsingChannel",new Class[]{int.class},1);
                check(!((DirectVideoView)get("videoView")).isFrameCovered(),"List switch blacked out retained frame");});
            until(()->(Integer)get("currentChannelIndex")==1&&(Boolean)get("prepared")&&(Boolean)get("videoRenderingStarted")
                    &&(base+"live.m3u8").equals(get("activePlayerStreamUrl")),30000,"List-selected live channel failed");
            until(()->!((DirectVideoView)get("videoView")).isFrameCovered(),2000,"New live frame covered");
        });
        test("mp3-clock-and-radio-artwork",()->{
            start(2);check((Boolean)get("audioOnlyPlayback"),"MP3 not classified as audio");
            long initial=player().getCurrentPosition();until(()->player().getCurrentPosition()>initial+500,8000,"Radio never began output");
            long before=player().getCurrentPosition();SystemClock.sleep(2000);check(player().getCurrentPosition()>before+1000,"Radio clock stalled");
            check(((View)get("audioArtwork")).getVisibility()==View.VISIBLE,"Radio artwork absent");
        });
        test("hls-live-clock",()->{start(1);long before=player().getCurrentPosition();SystemClock.sleep(2000);check(player().getCurrentPosition()>before+1000,"Live HLS stalled");});
        test("failed-catchup-preserves-current-player",()->{
            start(1);IMediaPlayer old=player();long before=old.getCurrentPosition();
            EpgManager.Program programme=new EpgManager.Program(server.archiveStart,server.archiveStart+1800000,"History","");
            main(()->call("beginCatchupPlayback",new Class[]{int.class,int.class,int.class,EpgManager.Program.class,String.class},0,1,0,programme,base+"wrong.m3u8"));
            until(()->get("catchupPreloader")==null,12000,"Invalid catchup stuck");SystemClock.sleep(1200);
            check(player()==old&&old.isPlaying()&&old.getCurrentPosition()>before+500,"Current live programme stopped after failed catchup");
        });
        test("valid-catchup-preload-first-frame",()->{
            start(1);EpgManager.Program programme=new EpgManager.Program(server.archiveStart,server.archiveStart+1800000,"History","");
            main(()->call("beginCatchupPlayback",new Class[]{int.class,int.class,int.class,EpgManager.Program.class,String.class},0,1,0,programme,base+"archive.m3u8"));
            until(()->get("catchupProgram")!=null&&(Boolean)get("prepared")&&(Boolean)get("videoRenderingStarted")
                    &&(base+"archive.m3u8").equals(get("activePlayerStreamUrl")),35000,"Valid catchup never became ready");
            check((Boolean)get("catchupTimeVerified"),"Catchup time unverified");
        });
        test("repeated-player-lifecycle",()->{
            for(int i=0;i<6;i++){start(i%2);SystemClock.sleep(400);}
            stop();SystemClock.sleep(1500);check(get("player")==null,"Player not released");
        });
    }
    private void ui()throws Exception{
        stop();
        for(String theme:new String[]{"builtin","ui2","ui3","ui4","ui5","ui6"})test("theme-list-menu-scale-"+theme,()->{
            TvUiPlugin.select(activity,theme);until(()->(Integer)get("appliedTvUiRevision")==TvUiPlugin.revision(),10000,"Theme not attached");
            main(()->{ChannelCatalog.GROUPS=fixtures;call("openChannelList",new Class[]{boolean.class},false);call("showChannelMenu",new Class[]{int.class},1);});
            SystemClock.sleep(250);
            for(String scale:new String[]{"50","90","standard","150","200"}){
                main(()->{set("uiScaleMode",scale);View root=(View)get("root");call("refreshUiScaleForViewport",new Class[]{int.class,int.class,boolean.class},root.getWidth(),root.getHeight(),true);});
                SystemClock.sleep(75);
                main(()->{
                    View root=(View)get("root"),panel=(View)get("channelListPanel");ListView list=(ListView)get("channelList"),groups=(ListView)get("groupList");
                    check(list.getCount()==1327,"Large list lost entries");check(panel.getWidth()<=root.getWidth()&&panel.getHeight()<=root.getHeight(),"Panel exceeds screen");
                    check(list.getChildCount()>0&&list.getChildCount()<60,"List is empty or not virtualized");
                    if(groups.getChildCount()>0)check(groups.getChildAt(0).getHeight()==list.getChildAt(0).getHeight(),"Category/channel row pitch differs");
                    list.setSelection(1219);
                });
            }
            main(()->{call("closeChannelList",new Class[]{});if(!theme.equals("builtin")){
                call("openTvUiMenu",new Class[]{});check(((TvUiMenu)get("tvUiMenu")).visible(),"Settings menu absent");((TvUiMenu)get("tvUiMenu")).dismiss();}
            });
        });
        test("favorite-add-remove",()->{
            main(()->{ChannelCatalog.GROUPS=fixtures;set("currentGroupIndex",0);set("currentChannelIndex",0);
                int count=((Set<?>)get("favoriteChannelKeys")).size();call("toggleCurrentChannelFavorite",new Class[]{});call("toggleCurrentChannelFavorite",new Class[]{});
                check(((Set<?>)get("favoriteChannelKeys")).size()==count,"Favorite toggle not reversible");});
        });
        test("large-list-scroll-ui-latency",()->{
            main(()->{ChannelCatalog.GROUPS=fixtures;call("openChannelList",new Class[]{boolean.class},false);call("showChannelMenu",new Class[]{int.class},1);});
            long begin=SystemClock.elapsedRealtime();for(int i=0;i<60;i++){final int position=i*21;main(()->((ListView)get("channelList")).setSelection(position));SystemClock.sleep(15);}
            check(SystemClock.elapsedRealtime()-begin<15000,"Scrolling blocks UI");
        });
    }
    private void web()throws Exception{
        stop();WebSourceView source=(WebSourceView)get("webSourceView");
        for(int i=0;i<3;i++){final int cycle=i;test("isolated-browser-page-cycle-"+i,()->{
            main(()->source.open((Integer)get("playRequestId"),base+"page-a?cycle="+cycle,"",false,"",""));
            until(()->source.currentPageTitle().equals("Legacy A"),20000,"Browser page A failed: "+source.currentPageTitle());
            main(()->source.openBookmarkedPage(base+"page-b","Legacy B","Audit",false));
            until(()->source.currentPageTitle().equals("Legacy B"),15000,"Browser page B failed");
            CountDownLatch cache=new CountDownLatch(1);String[] error={null};main(()->source.clearBrowserCache(reason->{error[0]=reason;cache.countDown();}));
            check(cache.await(8,TimeUnit.SECONDS)&&error[0]==null,"Browser cache clear failed: "+error[0]);
            main(()->call("closeWebSource",new Class[]{}));
        });}
        test("browser-native-playback-return",()->{start(0);check((Boolean)get("videoRenderingStarted"),"Cannot return from browser to native video");});
    }
    public static final class Reply {
        volatile CountDownLatch done;volatile String value;
        @JavascriptInterface public void result(String text){value=text;CountDownLatch wait=done;if(wait!=null)wait.countDown();}
    }
    private String js(WebView web,Reply reply,String expression)throws Exception{
        reply.done=new CountDownLatch(1);reply.value=null;
        main(()->web.loadUrl("javascript:window.__LegacyAudit.result(String("+expression+"))"));
        check(reply.done.await(8,TimeUnit.SECONDS),"Legacy JS callback timed out");return reply.value;
    }
    private void management()throws Exception{
        stop();management=(ManagementActivity)startActivitySync(new Intent(getTargetContext(),ManagementActivity.class)
                .putExtra(ManagementActivity.EXTRA_URL,base+"management").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        WebView web=(WebView)field(management,"webView").get(management);Reply reply=new Reply();
        main(()->{web.addJavascriptInterface(reply,"__LegacyAudit");web.reload();});SystemClock.sleep(1500);
        test("management-page-js-and-device-bridge",()->{
            check(js(web,reply,"document.title").equals("Legacy management"),"Management document failed");
            check(js(web,reply,"typeof NtvDevice.hasGyroscope()+'|'+typeof NtvDevice.navigateBack").equals("boolean|function"),"Native device bridge absent");
        });
        test("management-gyro-api-compatibility",()->{
            Object bridge=field(management,"nativeDeviceBridge").get(management);Constructor<SensorEvent> constructor=SensorEvent.class.getDeclaredConstructor(int.class);constructor.setAccessible(true);
            SensorEvent event=constructor.newInstance(3);event.values[0]=.1f;event.values[1]=.2f;event.values[2]=.3f;
            main(()->{Field listening=field(bridge,"listening");listening.setBoolean(bridge,true);
                try{Method update=bridge.getClass().getDeclaredMethod("onSensorChanged",SensorEvent.class);update.setAccessible(true);update.invoke(bridge,event);}
                finally{listening.setBoolean(bridge,false);}});
        });
        test("management-picker-legacy-callbacks",()->{
            Class<?> type=Class.forName("xiao.bu.tv.ManagementActivity$LegacyFileChooserClient");int callbacks=0;
            for(Method m:type.getDeclaredMethods())if(m.getName().equals("openFileChooser"))callbacks++;
            check(callbacks==3,"Legacy upload callbacks removed");
            Intent intent=ManagementActivity.fileChooserIntent(new String[]{"application/vnd.android.package-archive"});
            check(intent.getType().equals("application/vnd.android.package-archive"),"APK upload type incorrect");
        });
        main(()->management.finish());management=null;
    }
    private void controlPages()throws Exception{
        stop();until(()->get("controlServer")!=null&&((LocalControlServer)get("controlServer")).getPort()>0,10000,"Control server absent");
        String host="http://127.0.0.1:"+((LocalControlServer)get("controlServer")).getPort()+"/";
        management=(ManagementActivity)startActivitySync(new Intent(getTargetContext(),ManagementActivity.class)
                .putExtra(ManagementActivity.EXTRA_URL,host).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        WebView web=(WebView)field(management,"webView").get(management);Reply reply=new Reply();
        main(()->web.addJavascriptInterface(reply,"__LegacyAudit"));
        for(String page:new String[]{"index.html","pages/channels.html","pages/groups.html","pages/playback.html","pages/browser.html",
                "pages/media.html","pages/advanced.html","pages/system.html","pages/script.html","pages/flymouse.html"})test("control-page-"+page,()->{
            main(()->web.loadUrl(host+page));SystemClock.sleep(700);
            String stateName=page.equals("pages/media.html")?"mediaState":"state";
            until(()->"ready".equals(js(web,reply,"typeof "+stateName+"!=='undefined'&&"+stateName+"!==null&&document.readyState==='complete'?'ready':'waiting'")),12000,"State/render failed for "+page);
            check(!js(web,reply,"document.querySelector('.online')?document.querySelector('.online').innerText:''").contains("连接失败"),"Control page API failed");
            check(js(web,reply,"document.querySelectorAll('main').length").equals("1"),"Page content absent");
        });
        test("control-api-custom-scale-validation",()->{
            for(String mode:new String[]{"50","90","standard","150","200"}){
                JSONObject response=new JSONObject((String)call("handleWebSettings",new Class[]{JSONObject.class},new JSONObject().put("uiScaleMode",mode)));
                check(response.optBoolean("ok",true)&&mode.equals(get("uiScaleMode")),"Scale setting rejected "+mode);
            }
            boolean rejected=false;try{call("handleWebSettings",new Class[]{JSONObject.class},new JSONObject().put("uiScaleMode","999"));}catch(InvocationTargetException expected){rejected=true;}
            check(rejected,"Invalid scale accepted");
        });
        main(()->management.finish());management=null;
    }
    private void providers()throws Exception{
        for(int provider=0;provider<2;provider++)for(int index:new int[]{0,3}){
            final int kind=provider,channel=index;test("online-provider-"+provider+"-channel-"+index,()->{
                stop();main(()->{ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("Online audit",kind,
                    kind==0?ChannelCatalog.CCTV_CHANNELS:ChannelCatalog.YANGSHIPIN_CCTV_CHANNELS)};set("currentGroupIndex",0);
                    call("switchChannel",new Class[]{int.class,int.class},channel,0);});
                until(()->(Boolean)get("prepared")&&(Boolean)get("videoRenderingStarted"),25000,"Online provider did not render");
                long position=player().getCurrentPosition();SystemClock.sleep(2000);check(player().getCurrentPosition()>position+500,"Online clock stalled");
            });
        }
    }
    private void watchUi(){watch=true;monitor=new Thread(()->{
        Handler ui=new Handler(Looper.getMainLooper());while(watch){long begin=SystemClock.elapsedRealtime();CountDownLatch beat=new CountDownLatch(1);ui.post(beat::countDown);
            try{beat.await(3,TimeUnit.SECONDS);longestUiGap=Math.max(longestUiGap,SystemClock.elapsedRealtime()-begin);Thread.sleep(100);}catch(InterruptedException e){return;}}
    },"legacy-audit-ui-watch");monitor.setDaemon(true);monitor.start();}
    private static void restore(SharedPreferences preferences,Map<String,?> values){
        SharedPreferences.Editor edit=preferences.edit().clear();for(Map.Entry<String,?> entry:values.entrySet()){
            String key=entry.getKey();Object value=entry.getValue();if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);
            else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Long)edit.putLong(key,(Long)value);else if(value instanceof Float)edit.putFloat(key,(Float)value);
            else if(value instanceof Set)edit.putStringSet(key,new HashSet<String>((Set<String>)value));
        }edit.commit();
    }
    public void onStart(){Bundle result=new Bundle();
        if(!getTargetContext().getPackageName().endsWith(".iptvdiag")){
            result.putString("stream","FAIL: legacy fixtures require the isolated .iptvdiag application ID\n");finish(0,result);return;
        }
        SharedPreferences preferences=getTargetContext().getSharedPreferences(MainActivity.PREFERENCES,0);
        saved=preferences.getAll();savedTheme=getTargetContext().getSharedPreferences("tv_ui",0).getString("selected","builtin");
        PowerManager.WakeLock wake=((PowerManager)getTargetContext().getSystemService(Context.POWER_SERVICE)).newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK|PowerManager.ACQUIRE_CAUSES_WAKEUP,"ntv:legacy-functional-test");
        try{
            wake.acquire(180000);
            server=new Fixture(new File(Environment.getExternalStorageDirectory(),"ntv-legacy-audit"));base=server.base();
            original=ChannelCatalog.GROUPS;fixtureCatalog();fixtureStore=new ChannelCatalogStore(getTargetContext());storedCatalog=fixtureStore.load();
            android.database.Cursor fingerprint=fixtureStore.getReadableDatabase().rawQuery("SELECT value FROM catalog_meta WHERE name='fingerprint'",null);
            try{storedFingerprint=fingerprint.moveToFirst()?fingerprint.getString(0):null;}finally{fingerprint.close();}
            check(fixtureStore.replace(fixtures,null),"Could not persist test catalog");
            preferences.edit().putString("decode_mode",args.getString("decoder","auto")).putBoolean("auto_update_channel_list",false).putString("last_channel_snapshot_v2",new JSONObject()
                    .put("groupTitle","Legacy fixtures").put("name","Video").put("number","1").put("catalogSource",3)
                    .put("urls",new JSONArray().put(base+"video.mp4")).toString()).commit();
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(1500);
            main(()->{activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                    |WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED|WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                ((java.util.concurrent.atomic.AtomicInteger)get("catalogLoadGeneration")).incrementAndGet();
                set("autoSwitchSource",false);set("autoSwitchSourceSeconds",0);});stop();fixtureCatalog();watchUi();
            String phase=args.getString("phase","media");
            if(phase.equals("media"))media();else if(phase.equals("ui"))ui();else if(phase.equals("web"))web();else if(phase.equals("management"))management();else if(phase.equals("control"))controlPages();else if(phase.equals("providers"))providers();else throw new IllegalArgumentException(phase);
            android.os.Debug.MemoryInfo memory=new android.os.Debug.MemoryInfo();android.os.Debug.getMemoryInfo(memory);
            result.putString("stream","COMPLETE Android "+Build.VERSION.RELEASE+" phase="+phase+" cases="+results.length()+" failed="+failures
                    +" pssKB="+memory.getTotalPss()+" nativeKB="+android.os.Debug.getNativeHeapAllocatedSize()/1024
                    +" fd="+fdCount()+" threads="+Thread.activeCount()+" longestUiGapMs="+longestUiGap+"\n"+results.toString());
        }catch(Throwable error){failures++;result.putString("stream","FAIL setup/cleanup "+android.util.Log.getStackTraceString(error));}
        finally{
            watch=false;if(monitor!=null)monitor.interrupt();
            try{main(()->{if(management!=null)management.finish();if(activity!=null){call("cancelPendingCatchup",new Class[]{});call("closeWebSource",new Class[]{});activity.finish();}});waitForIdleSync();}catch(Throwable ignored){}
            if(fixtureStore!=null){try{if(storedCatalog!=null)fixtureStore.replace(storedCatalog,storedFingerprint);else fixtureStore.getWritableDatabase().delete("catalog_meta",null,null);}finally{fixtureStore.close();}}
            if(original!=null)ChannelCatalog.GROUPS=original;try{TvUiPlugin.select(getTargetContext(),savedTheme);}catch(Exception ignored){}restore(preferences,saved);
            if(server!=null)server.close();if(wake.isHeld())wake.release();
        }
        finish(failures==0?-1:0,result);
    }
    private static int fdCount(){String[] files=new File("/proc/self/fd").list();return files==null?-1:files.length;}
    static final class Fixture implements java.io.Closeable {
        final ServerSocket socket;final File root;final long archiveStart=System.currentTimeMillis()-7200000;
        final ExecutorService clients=Executors.newFixedThreadPool(4);volatile boolean running=true;
        Fixture(File root)throws Exception{this.root=root;check(new File(root,"video.mp4").isFile(),"Media fixture not pushed to "+root);socket=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
            Thread accept=new Thread(()->{while(running)try{Socket client=socket.accept();clients.execute(()->serve(client));}catch(Exception ignored){}},"legacy-audit-http");accept.setDaemon(true);accept.start();}
        String base(){return "http://127.0.0.1:"+socket.getLocalPort()+"/";}
        private static byte[] read(File file)throws Exception{try(InputStream input=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] block=new byte[8192];int n;while((n=input.read(block))!=-1)out.write(block,0,n);return out.toByteArray();}}
        void serve(Socket client){try(Socket connection=client){
            connection.setSoTimeout(5000);BufferedReader reader=new BufferedReader(new InputStreamReader(connection.getInputStream(),"UTF-8"));String first=reader.readLine();if(first==null)return;
            String path=first.split(" ")[1].split("\\?",2)[0],range=null,line;while((line=reader.readLine())!=null&&!line.isEmpty())if(line.toLowerCase(Locale.US).startsWith("range:"))range=line.substring(6).trim();
            byte[] bytes=null;String type="text/html; charset=utf-8";File file=null;
            if(path.startsWith("/page-")||path.equals("/management")){
                String title=path.equals("/management")?"Legacy management":path.startsWith("/page-a")?"Legacy A":"Legacy B";
                bytes=("<!doctype html><html><head><title>"+title+"</title><meta name=viewport content='width=device-width'></head><body><h1>"+title+"</h1><p>Local legacy regression page</p><a href='/page-b'>Next page</a><input type=file><script>window.__ntvNativeGyroscope=function(x,y,z){window.lastGyro=x;};</script></body></html>").getBytes("UTF-8");
            }else if(path.endsWith(".m3u8")){
                String manifest=new String(read(new File(root,"vod.m3u8")),"UTF-8");
                if(path.equals("/wrong.m3u8")||path.equals("/archive.m3u8")){
                    SimpleDateFormat date=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);date.setTimeZone(TimeZone.getTimeZone("UTC"));
                    manifest=manifest.replaceFirst("#EXTINF:","#EXT-X-PROGRAM-DATE-TIME:"+date.format(new Date(path.startsWith("/wrong")?System.currentTimeMillis():archiveStart))+"\n#EXTINF:");
                }bytes=manifest.getBytes("UTF-8");type="application/vnd.apple.mpegurl";
            }else{file=new File(root,path.substring(1));if(!file.isFile()){connection.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));return;}
                type=path.endsWith(".mp3")?"audio/mpeg":path.endsWith(".ts")?"video/mp2t":"video/mp4";}
            long length=bytes==null?file.length():bytes.length,start=0,end=length-1;
            if(range!=null&&range.startsWith("bytes=")){String[] pair=range.substring(6).split("-",2);if(!pair[0].isEmpty())start=Long.parseLong(pair[0]);if(pair.length>1&&!pair[1].isEmpty())end=Math.min(end,Long.parseLong(pair[1]));}
            OutputStream output=connection.getOutputStream();String header="HTTP/1.1 "+(range==null?"200 OK":"206 Partial Content")+"\r\nContent-Type: "+type+"\r\nContent-Length: "+(end-start+1)+"\r\nAccept-Ranges: bytes\r\n"+(range==null?"":"Content-Range: bytes "+start+"-"+end+"/"+length+"\r\n")+"Connection: close\r\n\r\n";
            output.write(header.getBytes("UTF-8"));if(bytes!=null)output.write(bytes,(int)start,(int)(end-start+1));else try(RandomAccessFile input=new RandomAccessFile(file,"r")){input.seek(start);byte[] block=new byte[16384];long remain=end-start+1;while(remain>0){int n=input.read(block,0,(int)Math.min(remain,block.length));if(n<0)break;output.write(block,0,n);remain-=n;}}output.flush();
        }catch(Exception ignored){}}
        public void close(){running=false;try{socket.close();}catch(IOException ignored){}clients.shutdownNow();}
    }
}
