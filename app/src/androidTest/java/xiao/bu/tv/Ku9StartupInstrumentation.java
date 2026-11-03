package xiao.bu.tv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.WindowManager;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import android.widget.TextView;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.misc.ITrackInfo;

/** Actual script -> manifest -> advancing first video output, with settings restored. */
public final class Ku9StartupInstrumentation extends Instrumentation {
    private MainActivity activity;
    private Bundle args;
    private Throwable uiError;
    private java.net.ServerSocket catalogBootstrap;
    private final java.util.concurrent.CountDownLatch catalogHold=new java.util.concurrent.CountDownLatch(1);
    private final JSONArray rows=new JSONArray();
    @Override public void onCreate(Bundle value){super.onCreate(value);args=value;start();}
    @Override public void callActivityOnCreate(Activity target,Bundle state) {
        target.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                |WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON|WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                |WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        super.callActivityOnCreate(target,state);
        if(target instanceof MainActivity)try {
            Field generation=MainActivity.class.getDeclaredField("catalogLoadGeneration");generation.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicInteger)generation.get(target)).incrementAndGet();
            if(catalogBootstrap!=null) {
                Field remote=MainActivity.class.getDeclaredField("remoteCatalogUrl");remote.setAccessible(true);
                remote.set(target,"http://127.0.0.1:"+catalogBootstrap.getLocalPort());
            }
        }catch(Exception error){throw new RuntimeException(error);}
    }
    private Field field(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f;}
    private Object get(String name)throws Exception{return field(name).get(activity);}
    private void set(String name,Object value)throws Exception{field(name).set(activity,value);}
    private interface Action{void run()throws Exception;}
    private void main(Action work)throws Exception {
        uiError=null;runOnMainSync(()->{try{work.run();}catch(Throwable error){uiError=error;}});
        if(uiError!=null)throw new Exception(uiError);
    }
    private void start(int index)throws Exception {
        main(()->{Method m=MainActivity.class.getDeclaredMethod("startChannel",int.class);m.setAccessible(true);m.invoke(activity,index);});
    }
    private static long renderedFrames(IMediaPlayer player)throws Exception {
        if(!(player instanceof tv.danmaku.ijk.media.player.IjkMediaPlayer))return -1L;
        // The small host wrapper can be inlined out of a minified release APK.
        Method property=tv.danmaku.ijk.media.player.IjkMediaPlayer.class.getDeclaredMethod("_getPropertyLong",int.class,long.class);
        property.setAccessible(true);return ((Number)property.invoke(player,22001,-1L)).longValue();
    }
    private static double sourceRate(IMediaPlayer player)throws Exception {
        if(!(player instanceof tv.danmaku.ijk.media.player.IjkMediaPlayer))return 0;
        Method property=tv.danmaku.ijk.media.player.IjkMediaPlayer.class.getDeclaredMethod("_getPropertyFloat",int.class,float.class);
        property.setAccessible(true);double value=((Number)property.invoke(player,11001,0f)).doubleValue();return value>=1&&value<=240?value:0;
    }
    private long loadMs() throws Exception {
        ChannelStartupTiming timing = (ChannelStartupTiming) get("channelStartupTiming");
        synchronized (timing) {
            if (!timing.finished((Integer) get("playRequestId"))) return -1L;
            Field started = ChannelStartupTiming.class.getDeclaredField("started");
            Field finished = ChannelStartupTiming.class.getDeclaredField("finished");
            started.setAccessible(true); finished.setAccessible(true);
            return finished.getLong(timing) - started.getLong(timing);
        }
    }
    private void play(int index,String name)throws Exception {
        long begin=SystemClock.elapsedRealtime();start(index);
        JSONObject row=new JSONObject().put("index",index).put("channel",name).put("requestId",get("playRequestId")).put("networkDns",NetworkClient.getDnsMode());rows.put(row);
        IMediaPlayer seen=null;long position=0;
        long limit=Long.parseLong(args.getString("caseTimeoutMs","45000"));
        while(SystemClock.elapsedRealtime()-begin<limit) {
            if(Boolean.parseBoolean(args.getString("coldLite","false"))&&get("liteWebPlayback")!=null){LiteWebPlayback audit=(LiteWebPlayback)get("liteWebPlayback");audit.enableAudit();row.put("usingLiteRuntime",true).put("liteDiagnostics",audit.auditSnapshot());}
            if(activity.isFinishing())throw new InterruptedException("Playback audit cancelled by closing the activity");
            IMediaPlayer current=(IMediaPlayer)get("player");
            boolean radio=args.getString("radioPlaylist")!=null;
            if(current!=null&&(Boolean)get("prepared")&&(radio?(Boolean)get("audioOnlyPlayback"):(Boolean)get("videoRenderingStarted"))
                    &&(Integer)get("currentChannelIndex")==index
                    &&get("activePlayerChannel")==ChannelCatalog.GROUPS[0].channels[index]
                    &&((Integer)get("playbackReadyRequestId")).intValue()==((Integer)get("playRequestId")).intValue()
                    &&loadMs()>=0) {
                if(seen!=current){seen=current;position=current.getCurrentPosition();}
                if(current.isPlaying()&&(radio||current.getVideoWidth()>0)&&current.getCurrentPosition()-position>=600) {
                    if(!activity.hasWindowFocus())throw new AssertionError("Playback window lost focus");
                    row.put("firstFrameMs",loadMs()).put("confirmedMs",SystemClock.elapsedRealtime()-begin)
                            .put("width",current.getVideoWidth()).put("player",current.getClass().getSimpleName()).put("status","rendering");
                    if(Boolean.parseBoolean(args.getString("coldLite","false"))){LiteWebPlayback lite=(LiteWebPlayback)get("liteWebPlayback");if(lite!=null)row.put("startupTimings",lite.startupTimings()).put("handoffs",lite.finalHandoffs());}
                    if(args.getString("expectedMediaPath")!=null) {
                        String stream=(String)get("activePlayerStreamUrl");
                        row.put("mediaPath",stream==null?"":java.net.URI.create(stream).getPath());
                        if(stream==null||!stream.contains(args.getString("expectedMediaPath")))
                            throw new AssertionError("Unexpected channel selected by page script: "+row.optString("mediaPath"));
                    }
                    if(radio)row.put("audioConfirmedMs",SystemClock.elapsedRealtime()-begin);
                    long watch=Long.parseLong(args.getString("watchMs","0"));
                    if(watch>0) {
                        long framesBefore=renderedFrames(current);
                        boolean frameWatch=Boolean.parseBoolean(args.getString("nativeFrameWatch","false"));
                        double rate=frameWatch?sourceRate(current):0,observedVideoMs=0;
                        long previousFrames=framesBefore,previousWall=SystemClock.elapsedRealtime();
                        long from=current.getCurrentPosition(), lastPosition=from;
                        long watchStarted=SystemClock.elapsedRealtime(), lastAdvance=watchStarted, maxStall=0, maxRollback=0, advances=0;
                        long lastTrace=0;
                        while(SystemClock.elapsedRealtime()-watchStarted<watch) {
                            long remaining=watch-(SystemClock.elapsedRealtime()-watchStarted);
                            if(remaining<=0)break;
                            SystemClock.sleep(Math.min(500,remaining));
                            long tick=SystemClock.elapsedRealtime(),next=current.getCurrentPosition();
                            if(frameWatch&&rate>0){long frames=renderedFrames(current);if(frames<previousFrames)observedVideoMs=0;else if(get("player")==current&&current.isPlaying()&&frames>previousFrames)observedVideoMs+=Math.min(tick-previousWall,(frames-previousFrames)*1000.0/rate);previousFrames=frames;previousWall=tick;}
                            if(next-lastPosition>=30){
                                if(tick-lastAdvance>1000)android.util.Log.i("Ku9PlaybackAudit","clock resumed elapsed="+(tick-watchStarted)+" gap="+(tick-lastAdvance)+" position="+next);
                                maxStall=Math.max(maxStall,tick-lastAdvance);lastAdvance=tick;advances+=next-lastPosition;lastPosition=next;
                            } else if(next<lastPosition-100){
                                maxRollback=Math.max(maxRollback,lastPosition-next);
                                android.util.Log.i("Ku9PlaybackAudit","clock rollback elapsed="+(tick-watchStarted)+" from="+lastPosition+" to="+next);
                                lastPosition=next;lastAdvance=tick;
                            }
                            if(Boolean.parseBoolean(args.getString("tracePlaylist","false"))&&tick-lastTrace>=2000) {
                                lastTrace=tick;
                                Ku9ScriptResolver resolver=(Ku9ScriptResolver)get("ku9ScriptResolver");
                                Field serverField=Ku9ScriptResolver.class.getDeclaredField("playlistServer");serverField.setAccessible(true);
                                Ku9PlaylistServer server=(Ku9PlaylistServer)serverField.get(resolver);
                                String text=Ku9HttpClient.readUtf8(new java.net.URL(server.url()).openStream(),128*1024);
                                String seq="",tail="";
                                for(String line:text.split("\n")) {
                                    if(line.startsWith("#EXT-X-MEDIA-SEQUENCE:"))seq=line.substring(22);
                                    if(line.startsWith("http")){String path=java.net.URI.create(line).getPath();tail=path.substring(path.lastIndexOf('/')+1);}
                                }
                                android.util.Log.i("Ku9PlaybackAudit","window elapsed="+(tick-watchStarted)+" position="+next+" stalled="+(tick-lastAdvance)+" sequence="+seq+" tail="+tail
                                    +" outputFps="+(current instanceof tv.danmaku.ijk.media.player.IjkMediaPlayer?((tv.danmaku.ijk.media.player.IjkMediaPlayer)current).getVideoOutputFramesPerSecond():-1));
                            }
                        }
                        maxStall=Math.max(maxStall,SystemClock.elapsedRealtime()-lastAdvance);
                        row.put("watchMs",watch).put("watchClockAdvanceMs",current.getCurrentPosition()-from).put("maxClockStallMs",maxStall).put("maxClockRollbackMs",maxRollback).put("clockAdvancesMs",advances);
                        row.put("samePlayer",get("player")==current).put("stillPlaying",current.isPlaying());
                        if(Boolean.parseBoolean(args.getString("coldLite","false"))){LiteWebPlayback observed=(LiteWebPlayback)get("liteWebPlayback");if(observed!=null)row.put("finalLiteDiagnostics",observed.auditSnapshot());}
                        if(frameWatch)row.put("observedVideoMs",Math.round(observedVideoMs)).put("nativeSourceFps",rate).put("videoMeasurement","positive native output-frame counter deltas / native source fps, capped by monotonic wall time; excludes pauses, changed players and counter resets; independent of source PTS rebasing");
                        if(current instanceof tv.danmaku.ijk.media.player.IjkMediaPlayer){
                            long framesAfter=renderedFrames(current);
                            row.put("nativeOutputFps",IjkStreamMetrics.outputFrameRate((tv.danmaku.ijk.media.player.IjkMediaPlayer)current));
                            if(framesBefore>=0&&framesAfter>=0){
                                row.put("outputFramesAdvanced",framesAfter-framesBefore);
                                if(framesAfter<=framesBefore){row.put("status","unavailable");throw new AssertionError("Video frames stopped during stability watch: "+name);}
                            }
                        }
                        boolean enough=frameWatch&&rate>0?observedVideoMs>=Math.max(10000,watch/2):advances>=watch/2;
                        if(get("player")!=current||!current.isPlaying()||!enough) {
                            row.put("status","unavailable");
                            throw new AssertionError("Playback stopped during stability watch: "+name);
                        }
                        String maxAllowed=args.getString("maxClockStallMs");
                        if(maxAllowed!=null&&maxStall>Long.parseLong(maxAllowed)) {
                            row.put("status","unavailable");
                            throw new AssertionError("Playback clock stalled "+maxStall+"ms: "+name);
                        }
                    }
                    if (current instanceof tv.danmaku.ijk.media.player.IjkMediaPlayer && !Boolean.parseBoolean(args.getString("coldLite","false"))) {
                        float fps = IjkStreamMetrics.sourceFrameRate(
                                (tv.danmaku.ijk.media.player.IjkMediaPlayer) current);
                        row.put("sourceFps", fps);
                        String expected = args.getString("expectedSourceFps");
                        if (expected != null && Math.abs(fps - Float.parseFloat(expected)) > 1f)
                            throw new AssertionError("Source FPS mismatch: " + fps + " expected " + expected);
                    }
                    ITrackInfo[] tracks=current.getTrackInfo();JSONArray trackRows=new JSONArray();int audioTracks=0;
                    if(tracks!=null)for(ITrackInfo track:tracks){if(track==null)continue;int type=track.getTrackType();if(type==ITrackInfo.MEDIA_TRACK_TYPE_AUDIO)audioTracks++;trackRows.put(new JSONObject().put("type",type).put("info",track.getInfoInline()));}
                    row.put("tracks",trackRows).put("audioTrackCount",audioTracks);
                    if(current instanceof tv.danmaku.ijk.media.player.IjkMediaPlayer)row.put("selectedAudioTrack",((tv.danmaku.ijk.media.player.IjkMediaPlayer)current).getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO));
                    if(Boolean.parseBoolean(args.getString("coldLite","false"))&&android.os.Build.VERSION.SDK_INT>=18){
                        android.graphics.Bitmap capture=getUiAutomation().takeScreenshot();
                        if(capture!=null){String filename="cold-main-"+args.getString("label","test")+"-"+index+".png";try(FileOutputStream image=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),filename))){capture.compress(android.graphics.Bitmap.CompressFormat.PNG,100,image);}finally{capture.recycle();}row.put("screenshot",filename);}
                    }
                    row.put("status","passed");
                    Bundle update=new Bundle();update.putString("stream",row.toString()+"\n");sendStatus(0,update);return;
                }
            }
            String status=((TextView)get("statusText")).getText().toString();
            if(SystemClock.elapsedRealtime()-begin>2000
                    &&((Integer)get("currentChannelIndex")!=index||status.contains("暂不可用")
                    ||status.contains("脚本执行失败")||status.contains("IPv6")||status.contains("分片准备失败")
                    ||status.contains("播放错误")||status.contains("频道缓冲超时"))) {
                row.put("status","unavailable").put("message",status).put("elapsedMs",SystemClock.elapsedRealtime()-begin);
                throw new AssertionError("Source unavailable: "+name+" "+status);
            }
            SystemClock.sleep(100);
        }
        row.put("status","unavailable").put("elapsedMs",SystemClock.elapsedRealtime()-begin);
        throw new AssertionError("No advancing video: "+name);
    }
    @Override public void onStart() {
        Bundle result=new Bundle();int code=-1;
        SharedPreferences prefs=getTargetContext().getSharedPreferences(MainActivity.PREFERENCES,0);
        if(Boolean.parseBoolean(args.getString("dnsConfigOnly","false"))){
            boolean explicit=prefs.contains("network_dns");String stored=prefs.getString("network_dns","unset");
            try{
                NetworkClient.initialize(getTargetContext());
                for(String mode:new String[]{"ali","tencent","114","baidu","system"}){prefs.edit().putString("network_dns",mode).commit();if(!mode.equals(NetworkClient.getDnsMode()))throw new AssertionError("Explicit DNS preference lost: "+mode);}
                JSONObject output=new JSONObject().put("explicitDns",explicit).put("storedDns",stored).put("buildDefaultDns",NetworkClient.DEFAULT_DNS).put("allExplicitDnsChoicesPreserved",true);
                if(args.getString("dnsProbeHost")!=null){
                    java.util.concurrent.ExecutorService diagnostic=java.util.concurrent.Executors.newSingleThreadExecutor();
                    java.util.concurrent.Future<JSONObject> probe=diagnostic.submit(()->{
                        JSONObject info=new JSONObject();long began=SystemClock.elapsedRealtime();okhttp3.Dns dns=NetworkClient.sharedClient().dns();JSONArray families=new JSONArray();
                        for(java.net.InetAddress address:dns.lookup(args.getString("dnsProbeHost")))families.put(address instanceof java.net.Inet6Address?"IPv6":"IPv4");info.put("dnsMs",SystemClock.elapsedRealtime()-began).put("families",families);
                        if(args.getString("dnsProbeUrl")!=null){LiteEngine page=new LiteEngine(getTargetContext(),new LiteEngine.Listener(){public void frame(JSONObject p){}public void message(String s){}public void navigate(String u){}public void media(String u,java.util.Map<String,String> h){}},dns);
                          try{began=SystemClock.elapsedRealtime();JSONObject probeRequest=new JSONObject().put("op","requestSync").put("kind","script").put("url",args.getString("dnsProbeUrl"));String probeUa=args.getString("dnsProbeUa");if(probeUa!=null)probeRequest.put("headers",new JSONObject().put("User-Agent",probeUa));JSONObject response=new JSONObject(page.host(probeRequest.toString()));String body=response.optString("text");info.put("httpMs",SystemClock.elapsedRealtime()-began).put("httpStatus",response.optInt("status")).put("httpError",response.has("error")).put("bodyBytes",body.getBytes("UTF-8").length).put("hasScripts",body.toLowerCase(java.util.Locale.US).contains("<script"));if(Boolean.parseBoolean(args.getString("saveProbe","false")))try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),"network-probe-response-private.json"))){out.write(response.toString().getBytes("UTF-8"));}}finally{page.close();}
                        }return info;
                    });
                    try{output.put("probe",probe.get(18,java.util.concurrent.TimeUnit.SECONDS));}catch(Exception error){probe.cancel(true);output.put("probeError",error.getClass().getSimpleName());Throwable cause=error.getCause();if(cause!=null)output.put("probeCause",cause.getClass().getSimpleName()).put("probeMessage",String.valueOf(cause.getMessage()).replaceAll("https?://[^ ]+","[url]"));}finally{diagnostic.shutdownNow();}
                }
                result.putString("stream",output.toString()+"\n");
            }catch(Exception error){throw new RuntimeException(error);}finally{if(explicit)prefs.edit().putString("network_dns",stored).commit();else prefs.edit().remove("network_dns").commit();}
            finish(-1,result);return;
        }
        Map<String,?> saved=prefs.getAll();ChannelCatalog.Group[] savedGroups=ChannelCatalog.GROUPS;
        SharedPreferences coldPrefs=null;Map<String,?> savedColdPrefs=null;
        try {
            if(Boolean.parseBoolean(args.getString("coldLite","false"))){
                prefs.edit().putString("web_source_engine","lite").commit();
                LiteEngine.httpCache(getTargetContext()).evictAll();
            }
            if(Boolean.parseBoolean(args.getString("checkMediaHeaders","false"))) {
                Ku9SignedHeadersInstrumentation.checkMediaHeaders();
                Bundle checked=new Bundle();checked.putString("stream","PASS Ku9 media header propagation, filtering, origin scope and reset\n");sendStatus(0,checked);
            }
            if (args.getString("networkDns") != null) prefs.edit()
                    .putString("network_dns", args.getString("networkDns")).commit();
            if (Boolean.parseBoolean(args.getString("http1", "false"))) {
                okhttp3.OkHttpClient http = NetworkClient.sharedClient().newBuilder()
                        .protocols(java.util.Collections.singletonList(okhttp3.Protocol.HTTP_1_1)).build();
                Field client = NetworkClient.class.getDeclaredField("client"); client.setAccessible(true); client.set(null, http);
                Field factory = NetworkClient.class.getDeclaredField("factory"); factory.setAccessible(true);
                factory.set(null, new okhttp3.OkUrlFactory(http));
            }
            catalogBootstrap=new java.net.ServerSocket(0,2,java.net.InetAddress.getByName("127.0.0.1"));
            new Thread(()->{try(java.net.Socket socket=catalogBootstrap.accept()) {
                catalogHold.await(15,java.util.concurrent.TimeUnit.SECONDS);
            }catch(Exception ignored){}},"ku9-audit-bootstrap").start();
            // Prevent an unrelated saved 4K channel from allocating a decoder during
            // this audit. The temporary missing local script never opens a player.
            prefs.edit().putString("last_channel_snapshot_v2",new JSONObject()
                    .put("groupTitle","KU9_AUDIT_BOOTSTRAP").put("catalogSource",ChannelCatalog.SOURCE_CUSTOM)
                    .put("number","1").put("name","KU9_AUDIT_BOOTSTRAP")
                    .put("urls",new JSONArray().put("http://A/ku9/js/__ku9_missing_bootstrap_fixture__.js"))
                    .put("sourceIndex",0).toString()).commit();
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(1500);
            main(()->{
                ((java.util.concurrent.atomic.AtomicInteger)get("catalogLoadGeneration")).incrementAndGet();
                set("remoteCatalogUrl","");
            });
            catalogHold.countDown();catalogBootstrap.close();
            if(args.getString("coldSite")!=null){
                if(!getTargetContext().getPackageName().endsWith(".iptvdiag"))throw new AssertionError("Cold install probe requires diagnostic package");
                String site=args.getString("coldSite");CjsPluginRuntime.ensureCatalog();
                Field states=CjsPluginRuntime.class.getDeclaredField("states");states.setAccessible(true);
                Object state=((Map)states.get(null)).get(site);if(state==null)throw new AssertionError("Unknown cold site");
                Field loaded=state.getClass().getDeclaredField("loaded");loaded.setAccessible(true);
                if(loaded.getBoolean(state))throw new AssertionError("Cold site's native library already loaded");
                coldPrefs=getTargetContext().getSharedPreferences("cjs_sites_v5",0);savedColdPrefs=coldPrefs.getAll();
                String key=site+":"+CjsPluginRuntime.currentProfile()+":";
                coldPrefs.edit().remove(key+"active").remove(key+"pending").commit();
                for(String name:new String[]{"runtime","directory"}){Field f=state.getClass().getDeclaredField(name);f.setAccessible(true);f.set(state,null);}
                Field used=state.getClass().getDeclaredField("used");used.setAccessible(true);used.setBoolean(state,false);
                if(CjsPluginRuntime.isInstalled(site))throw new AssertionError("Cold site still installed");
            }
            if(args.getString("cmgNativeTrial")!=null) {
                CjsPluginRuntime.ensureCatalog();
                if(!CjsPluginRuntime.isInstalled("yangshipin.cn"))CjsPluginRuntime.installOrUpdate("yangshipin.cn");
                Field states=CjsPluginRuntime.class.getDeclaredField("states");states.setAccessible(true);
                Object state=((Map)states.get(null)).get("yangshipin.cn");
                Field loaded=state.getClass().getDeclaredField("loaded");loaded.setAccessible(true);
                if(loaded.getBoolean(state))throw new AssertionError("CMG library already loaded before trial");
                File trial=new File(getTargetContext().getFilesDir(),"cmg-native-trial");trial.mkdirs();
                try(java.io.InputStream input=new java.io.FileInputStream(args.getString("cmgNativeTrial"));
                        FileOutputStream output=new FileOutputStream(new File(trial,"yangshipin.so"))) {
                    byte[] buffer=new byte[32768];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);
                }
                Field directory=state.getClass().getDeclaredField("directory");directory.setAccessible(true);directory.set(state,trial);
                CjsPluginRuntime.loadNativeLibrary("yangshipin.cn");
                File trace=new File(getTargetContext().getExternalFilesDir(null),"cmg-crypto-"+args.getString("label","test")+".bin");
                if(!CmgTraceBridge.enable(trace.getAbsolutePath()))throw new AssertionError("Unable to open CMG native trace");
            }
            if(Boolean.parseBoolean(args.getString("checkAudioForwarding","false")))
                result.putString("stream",HlsStartupChecks.radioForwarding(activity));
            if(args.getString("fixtureDir")!=null) {
                result.putString("stream",Ku9StartupChecks.run(activity));
            }
            String base=args.getString("base","https://raw.githubusercontent.com/buhanzhe/webSourceM3U8/main/k-web/ku9/js/");
            JSONArray fullManifest=null;
            String[] cases=args.getString("cases","jlntv.js?id=ds,jlntv.js?id=sh,jlntv.js?id=ds").split(",");
            if(args.getString("manifest")!=null) {
                JSONArray manifest=new JSONArray(Ku9HttpClient.readUtf8(new java.io.FileInputStream(args.getString("manifest")),256*1024));
                if(Boolean.parseBoolean(args.getString("fullManifest","false")))fullManifest=manifest;
                cases=new String[manifest.length()];for(int n=0;n<cases.length;n++)cases[n]=fullManifest==null?manifest.getJSONObject(n).getString("url").split("/ku9/js/",2)[1]:manifest.getJSONObject(n).getString("name");
            }
            if(args.getString("fixtureDir")!=null)cases=new String[0];
            Channel[] radios=null;
            if(args.getString("radioPlaylist")!=null) {
                java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
                try(java.io.InputStream input=getContext().getAssets().open(args.getString("radioPlaylist"))) {
                    byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))>=0)bytes.write(buffer,0,count);
                }
                Method parse=PlaylistManager.class.getDeclaredMethod("parse",byte[].class);parse.setAccessible(true);
                Channel[] all=((ChannelCatalog.Group[])parse.invoke(null,(Object)bytes.toByteArray()))[0].channels;
                String[] indices=args.getString("indices","0,3,0").split(",");
                radios=new Channel[indices.length];cases=new String[indices.length];
                for(int n=0;n<indices.length;n++){radios[n]=all[Integer.parseInt(indices[n])];cases[n]=radios[n].name;}
            }
            final String[] entries=cases;final Channel[] radioEntries=radios;final JSONArray channelManifest=fullManifest;
            main(()->{
                ((java.util.concurrent.atomic.AtomicInteger)get("catalogLoadGeneration")).incrementAndGet();
                set("autoSwitchSource",false);set("remoteCatalogUrl","");
                Channel[] channels=radioEntries==null?new Channel[entries.length]:radioEntries;
                if(radioEntries==null)for(int n=0;n<entries.length;n++)channels[n]=new Channel(String.valueOf(n+1),entries[n],"",channelManifest==null?base+entries[n]:channelManifest.getJSONObject(n).getString("url"),null,null);
                ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("KU9_STARTUP_TEST",ChannelCatalog.SOURCE_CUSTOM,channels)};
                set("currentGroupIndex",0);set("currentSourceIndex",0);
            });
            boolean backgroundChecked=false;
            for(int n=0;n<entries.length;n++) {
                if(activity.isFinishing())throw new InterruptedException("Playback audit cancelled");
                try{
                    if(args.getString("cmgNativeTrial")!=null)CmgTraceBridge.mark(entries[n]);play(n,entries[n]);
                    if(!backgroundChecked&&Boolean.parseBoolean(args.getString("checkBackground","false"))){
                        JSONObject row=rows.getJSONObject(rows.length()-1);
                        TvUiChecks.backgroundSmoke(this,activity,row,false,false);backgroundChecked=true;
                        Bundle update=new Bundle();update.putString("stream",row.toString()+"\n");sendStatus(0,update);
                    }
                }
                catch(Exception|AssertionError error) {
                    if(!Boolean.parseBoolean(args.getString("continueFailures","false")))throw error;
                    JSONObject row=rows.getJSONObject(rows.length()-1);row.put("error",error.toString());
                    if("passed".equals(row.optString("status")))row.put("status","failed");
                    Bundle update=new Bundle();update.putString("stream",row.toString()+"\n");sendStatus(0,update);
                }
                saveReport();
                if(n+1<entries.length)SystemClock.sleep(Long.parseLong(args.getString("settleMs","0")));
            }
            if(args.getString("fixtureDir")==null)result.putString("stream","COMPLETE Ku9 actual playback audit: "+entries.length+" entries; see per-channel report\n");
        }catch(Throwable error){code=0;result.putString("stream",android.util.Log.getStackTraceString(error));}
        finally {
            catalogHold.countDown();try{if(catalogBootstrap!=null)catalogBootstrap.close();}catch(Exception ignored){}
            saveReport();
            if(activity!=null)try{main(()->activity.finish());}catch(Exception ignored){}
            if(args.getString("cmgNativeTrial")!=null) {
                try{CmgTraceBridge.stop();}catch(Throwable ignored){}
                File trial=new File(getTargetContext().getFilesDir(),"cmg-native-trial");
                new File(trial,"yangshipin.so").delete();trial.delete();
            }
            ChannelCatalog.GROUPS=savedGroups;
            restorePreferences(prefs,saved);
            if(coldPrefs!=null&&savedColdPrefs!=null)restorePreferences(coldPrefs,savedColdPrefs);
        }
        finish(code,result);
    }
    private static void restorePreferences(SharedPreferences prefs,Map<String,?> saved){
            SharedPreferences.Editor edit=prefs.edit().clear();
            for(Map.Entry<String,?> item:saved.entrySet()) {
                String key=item.getKey();Object value=item.getValue();
                if(value instanceof String)edit.putString(key,(String)value);
                else if(value instanceof Integer)edit.putInt(key,(Integer)value);
                else if(value instanceof Long)edit.putLong(key,(Long)value);
                else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);
                else if(value instanceof Float)edit.putFloat(key,(Float)value);
                else if(value instanceof Set)edit.putStringSet(key,(Set<String>)value);
            }
            edit.commit();
    }
    private void saveReport() {
        try(FileOutputStream output=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),
                "ku9-startup-"+args.getString("label","test")+".json"))){output.write(rows.toString(2).getBytes("UTF-8"));}catch(Exception ignored){}
    }
}
