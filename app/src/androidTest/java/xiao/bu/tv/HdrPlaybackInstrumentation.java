package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import org.json.JSONObject;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/** Real HDR10 fixture through the production player, hardware and software output. */
public final class HdrPlaybackInstrumentation extends Instrumentation {
    private Bundle args;
    private MainActivity activity;
    private android.view.Window.Callback originalWindowCallback;
    private java.net.ServerSocket localFileServer;
    private final java.util.List<java.net.Socket> localClients=
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Production playback expects HTTP; serve the local fixture without USB/Wi-Fi. */
    private String localFixture(File file) throws Exception {
        if(!file.isFile() && !file.isDirectory())throw new java.io.FileNotFoundException(file.toString());
        final boolean hls=file.isDirectory();
        final java.util.concurrent.Semaphore mediaLock=new java.util.concurrent.Semaphore(1);
        localFileServer=new java.net.ServerSocket(0,4,java.net.InetAddress.getByName("127.0.0.1"));
        final java.net.ServerSocket server=localFileServer;
        new Thread(() -> {
            while(!server.isClosed())try {
                final java.net.Socket client=server.accept();localClients.add(client);
                new Thread(() -> {
                    boolean locked=false;
                    try(java.net.Socket socket=client) {
                        socket.setSoTimeout(5000);
                        java.io.BufferedReader reader=new java.io.BufferedReader(
                                new java.io.InputStreamReader(socket.getInputStream(),"US-ASCII"));
                        String request=reader.readLine(),line,range=null;
                        if(request==null)return;
                        String name=request.split(" ")[1].split("\\?",2)[0].substring(1);
                        if(hls && (!name.matches("[A-Za-z0-9._-]+") || name.equals("..")))return;
                        File actual=hls?new File(file,name):file;
                        if(hls && !actual.getCanonicalFile().getParentFile().equals(file.getCanonicalFile()))return;
                        while((line=reader.readLine())!=null && !line.isEmpty())
                            if(line.toLowerCase(java.util.Locale.US).startsWith("range:"))
                                range=line.substring(6).trim();
                        boolean slow=hls && name.endsWith(".ts");
                        if(slow){mediaLock.acquire();locked=true;}
                        try(java.io.RandomAccessFile source=new java.io.RandomAccessFile(actual,"r")) {
                        long total=source.length(),begin=0,end=total-1;
                        if(range!=null) {
                            java.util.regex.Matcher match=java.util.regex.Pattern
                                    .compile("bytes=(\\d+)-(\\d*)").matcher(range);
                            if(!match.matches())throw new java.io.IOException("Invalid fixture range");
                            begin=Long.parseLong(match.group(1));
                            if(!match.group(2).isEmpty())end=Math.min(end,Long.parseLong(match.group(2)));
                        }
                        if(begin>end)throw new java.io.IOException("Unsatisfiable fixture range");
                        java.io.OutputStream output=new java.io.BufferedOutputStream(socket.getOutputStream());
                        String type=hls?(name.endsWith(".m3u8")?"application/vnd.apple.mpegurl":"video/mp2t"):"video/mp4";
                        String header="HTTP/1.1 "+(range==null?"200 OK":"206 Partial Content")
                                +"\r\nContent-Type: "+type+"\r\nAccept-Ranges: bytes\r\nContent-Length: "
                                +(end-begin+1)+(range==null?"":"\r\nContent-Range: bytes "+begin+"-"+end+"/"+total)
                                +"\r\nConnection: close\r\n\r\n";
                        output.write(header.getBytes("US-ASCII"));output.flush();source.seek(begin);
                        byte[] chunk=new byte[16384];long remaining=end-begin+1,length=remaining;
                        long started=SystemClock.elapsedRealtime();
                        while(remaining>0 && !server.isClosed()) {
                            int count=source.read(chunk,0,(int)Math.min(chunk.length,remaining));
                            if(count<0)break;
                            remaining-=count;
                            if(slow)SystemClock.sleep(Math.max(0,started+(length-remaining)*6000/length-SystemClock.elapsedRealtime()));
                            output.write(chunk,0,count);if(slow)output.flush();
                        }
                        output.flush();
                        }
                    }catch(java.io.IOException | InterruptedException ignored) {
                        // FFmpeg closes ranges early when seeking; this is expected.
                    }finally {if(locked)mediaLock.release();localClients.remove(client);}
                },"HDR local fixture range").start();
            }catch(java.io.IOException ignored) {if(server.isClosed())break;}
        },"HDR local fixture server").start();
        return "http://127.0.0.1:"+server.getLocalPort()+(hls?"/index.m3u8":"/fixture.mp4");
    }
    private JSONObject checkVideoInfo(IjkMediaPlayer media) throws Exception {
        JSONObject stats = new JSONObject(api("/api/media", null, 200)).getJSONObject("currentSourceStats");
        String expectedRange = args.getString("expectedRange");
        if (expectedRange != null && !expectedRange.equals(stats.getString("dynamicRange")))
            throw new AssertionError("Source range: " + stats);
        String expectedDepth = args.getString("expectedDepth");
        if (expectedDepth != null && Integer.parseInt(expectedDepth) != stats.getInt("bitDepth"))
            throw new AssertionError("Source depth: " + stats);
        for (String key : new String[]{"colorPrimaries", "colorSpace", "colorTransfer"}) {
            String expected = args.getString(key);
            if (expected != null && !expected.equals(stats.getString(key)))
                throw new AssertionError(key + ": " + stats);
        }
        if ("true".equals(args.getString("requireHardware"))
                && media.getVideoDecoder()!=IjkMediaPlayer.FFP_PROPV_DECODER_MEDIACODEC)
            throw new AssertionError("Hardware decoder fell back; GPU performance result is invalid");
        String actualDecoder = media.getMediaInfo().mVideoDecoderImpl.trim();
        String expectedDecoder = args.getString("expectedDecoder");
        if (expectedDecoder != null && !expectedDecoder.equals(actualDecoder))
            throw new AssertionError("Decoder fallback: " + actualDecoder);
        if (!actualDecoder.equals(stats.getString("videoDecoderName")))
            throw new AssertionError("Actual decoder name: " + stats);
        if ("true".equals(args.getString("skipUiChecks"))) return stats;
        runOnMainSync(() -> {try {
            field("showDebugInfo").setBoolean(activity,true);
            ((android.view.View)field("channelListPanel").get(activity)).setVisibility(android.view.View.GONE);
            Method visibility=MainActivity.class.getDeclaredMethod("applyDebugInfoVisibility");
            visibility.setAccessible(true);visibility.invoke(activity);
            Method show=MainActivity.class.getDeclaredMethod("showChannelBar",String.class,String.class);
            show.setAccessible(true);show.invoke(activity,"HDR_FIXTURE","");
            Method refresh=MainActivity.class.getDeclaredMethod("refreshVideoInfo");
            refresh.setAccessible(true);refresh.invoke(activity);
            String card=((android.widget.TextView)field("videoInfo").get(activity)).getText().toString();
            String overlay=((android.widget.TextView)field("debugInfoOverlay").get(activity)).getText().toString();
            String range=stats.getString("dynamicRange");
            String label="未知".equals(range)?"HDR/SDR未知":range;
            if (!card.startsWith(label+" · "))throw new AssertionError("Card range: "+card);
            if (!overlay.contains(label+" · "+stats.getInt("bitDepth")+"bit")
                    || !overlay.contains(stats.getString("colorTransfer"))
                    || !overlay.contains(actualDecoder))throw new AssertionError("Debug info: "+overlay);
            stats.put("card",card).put("overlay",overlay);
        }catch(Exception error){throw new RuntimeException(error);}});
        return stats;
    }
    private Field field(String name) throws Exception {
        Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f;
    }
    private String api(String path, JSONObject body, int expected) throws Exception {
        LocalControlServer server=(LocalControlServer)field("controlServer").get(activity);
        HttpURLConnection connection=(HttpURLConnection)new URL(
                "http://127.0.0.1:"+server.getPort()+path).openConnection();
        connection.setConnectTimeout(5000);connection.setReadTimeout(10000);
        try {
            if(body!=null) {
                connection.setRequestMethod("POST");connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type","application/json");
                try(java.io.OutputStream output=connection.getOutputStream()) {
                    output.write(body.toString().getBytes("UTF-8"));
                }
            }
            int status=connection.getResponseCode();
            if(status!=expected)throw new AssertionError(path+" HTTP "+status);
            InputStream input=status>=400?connection.getErrorStream():connection.getInputStream();
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] block=new byte[4096];int n;
            try {while((n=input.read(block))!=-1)bytes.write(block,0,n);}finally{input.close();}
            return bytes.toString("UTF-8");
        }finally{connection.disconnect();}
    }
    @Override public void onCreate(Bundle values) {super.onCreate(values);args=values;start();}
    @Override public void onStart() {
        Bundle result=new Bundle();StringBuilder report=new StringBuilder();int code=1;
        Map<String,?> saved=getTargetContext().getSharedPreferences(MainActivity.PREFERENCES,0).getAll();
        ChannelCatalog.Group[] savedGroups=null;
        try {
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(() -> {
                originalWindowCallback=activity.getWindow().getCallback();
                activity.getWindow().setCallback((android.view.Window.Callback)
                        java.lang.reflect.Proxy.newProxyInstance(android.view.Window.Callback.class.getClassLoader(),
                        new Class<?>[]{android.view.Window.Callback.class}, (proxy, method, values) -> {
                            if("dispatchTouchEvent".equals(method.getName())
                                    || "dispatchKeyEvent".equals(method.getName())
                                    || "dispatchGenericMotionEvent".equals(method.getName())) return true;
                            return method.invoke(originalWindowCallback,values);
                        }));
            });
            SystemClock.sleep(2000);
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                android.view.Display display=activity.getWindowManager().getDefaultDisplay();
                JSONObject capabilities=new JSONObject().put("display",display.getName())
                        .put("hdrTypes",new org.json.JSONArray(display.getHdrCapabilities().getSupportedHdrTypes()))
                        .put("maxLuminance",display.getHdrCapabilities().getDesiredMaxLuminance());
                report.append("display=").append(capabilities).append('\n');
                android.util.Log.i("nTvHdrTest",capabilities.toString());
            }
            savedGroups=ChannelCatalog.GROUPS;
            DirectVideoView view=(DirectVideoView)field("videoView").get(activity);
            long deadline=SystemClock.elapsedRealtime()+10000;
            while(!view.isSurfaceReady()) {
                if(SystemClock.elapsedRealtime()>deadline)throw new AssertionError("No Surface");
                SystemClock.sleep(100);
            }
            String requestedUrl=args.getString("url");
            if(requestedUrl==null || !(requestedUrl.startsWith("http") || requestedUrl.startsWith("/")))
                throw new IllegalArgumentException("Use -e url with an HTTP or absolute local HDR fixture");
            final String url=requestedUrl.startsWith("/") ? localFixture(new File(requestedUrl)) : requestedUrl;
            String[] hdrModes=args.getString("hdrModes","mapping,hardware,bt709,mapping").split(",");
            final String seedMode="bt709".equals(hdrModes[0]) ? "mapping" : "bt709";
            String advancedPage=api("/pages/advanced.html",null,200);
            if(!advancedPage.contains("强制 BT.709") || !advancedPage.contains("转 SDR")
                    || advancedPage.contains("<option value=\"hardware_sdr\">"))
                throw new AssertionError("Advanced page missing HDR settings");
            String originalMode=(String)field("hdrMode").get(activity);
            api("/api/settings",new JSONObject().put("hdrMode","invalid"),500);
            if(!originalMode.equals(field("hdrMode").get(activity)))
                throw new AssertionError("Invalid mode was applied");
            for(String mode:args.getString("modes","hardware,software").split(",")) {
                final boolean software="software".equals(mode);
                Channel channel=new Channel("1","HDR_FIXTURE","diag",url,null,null);
                Method play=MainActivity.class.getDeclaredMethod("startIjkPlayer",Channel.class,
                        String.class,boolean.class,boolean.class,int[].class,long.class);
                play.setAccessible(true);
                runOnMainSync(() -> {try {
                    ((java.util.concurrent.atomic.AtomicInteger)field("catalogLoadGeneration").get(activity)).incrementAndGet();
                    field("autoSwitchSource").setBoolean(activity,false);
                    field("hdrMode").set(activity,seedMode);
                    field("decodeMode").set(activity,mode);
                    if(args.containsKey("delayMode"))field("liveDelayMode").set(activity,args.getString("delayMode"));
                    ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group(
                            "HDR_FIXTURE",ChannelCatalog.SOURCE_CUSTOM,new Channel[]{channel})};
                    field("currentGroupIndex").setInt(activity,0);
                    field("currentChannelIndex").setInt(activity,0);
                    field("currentSourceIndex").setInt(activity,0);
                    play.invoke(activity,channel,url,software,true,null,0L);
                } catch(Exception error) {throw new RuntimeException(error);}});
                for(String hdrMode:hdrModes) {
                Object previous=field("player").get(activity);
                long requestedAt=SystemClock.elapsedRealtime();
                api("/api/settings",new JSONObject().put("hdrMode",hdrMode).put("decodeMode",mode),200);
                org.json.JSONArray warmupStatuses=new org.json.JSONArray();
                long lastStatusAt=0;
                deadline=SystemClock.elapsedRealtime()+Long.parseLong(args.getString("firstFrameTimeoutMs","30000"));
                while(field("player").get(activity)==previous
                        || !field("videoRenderingStarted").getBoolean(activity)) {
                    if(SystemClock.elapsedRealtime()>deadline)throw new AssertionError(mode+" first frame timeout");
                    if(SystemClock.elapsedRealtime()-lastStatusAt>=1000) {
                        String[] text=new String[1];
                        runOnMainSync(() -> {try {
                            text[0]=((android.widget.TextView)field("statusText").get(activity)).getText().toString();
                        }catch(Exception error){throw new RuntimeException(error);}});
                        warmupStatuses.put(new JSONObject().put("elapsedMs",SystemClock.elapsedRealtime()-requestedAt)
                                .put("text",text[0]));
                        android.util.Log.i("nTvHdrTest","WARMUP_STATUS "+warmupStatuses.getJSONObject(warmupStatuses.length()-1));
                        lastStatusAt=SystemClock.elapsedRealtime();
                    }
                    SystemClock.sleep(100);
                }
                if(!hdrMode.equals(activity.getSharedPreferences(MainActivity.PREFERENCES,0)
                        .getString("hdr_mode","")))throw new AssertionError("HDR preference not saved");
                JSONObject state=new JSONObject(api("/api/state?view=advanced",null,200));
                if(!hdrMode.equals(state.getJSONObject("settings").getString("hdrMode")))
                    throw new AssertionError("HDR status not updated");
                IjkMediaPlayer media=(IjkMediaPlayer)field("player").get(activity);
                long firstFrameMs=SystemClock.elapsedRealtime()-requestedAt;
                if("true".equals(args.getString("requireWaitMessage"))
                        && !warmupStatuses.toString().contains("网速太慢，稳定播放预计还需"))
                    throw new AssertionError("Missing estimated wait message: "+warmupStatuses);
                long startupCacheMs=media.getVideoCachedDuration();
                HlsProxyServer observedProxy=(HlsProxyServer)field("proxy").get(activity);
                long start=SystemClock.elapsedRealtime();
                while(media.getCurrentPosition()<Long.parseLong(args.getString("positionCheckMs","6500"))) {
                    if(!activity.hasWindowFocus())throw new AssertionError("Fixture lost foreground");
                    if(field("player").get(activity)!=media)throw new AssertionError("Fixture player replaced");
                    if(SystemClock.elapsedRealtime()-start>20000)throw new AssertionError(mode+" playback stalled");
                    SystemClock.sleep(100);
                }
                int monitorSeconds=Integer.parseInt(args.getString("monitorSeconds","0"));
                if(monitorSeconds>0) {
                    int events=field("bufferingEventId").getInt(activity);
                    long position=media.getCurrentPosition(),begin=SystemClock.elapsedRealtime();
                    long gpuStart=NativeHdrOutput.renderedFrameCount();
                    org.json.JSONArray samples=new org.json.JSONArray();
                    long cpuStart=android.os.Process.getElapsedCpuTime();
                    for(int second=0;second<Math.min(180,monitorSeconds);second++) {
                        SystemClock.sleep(1000);
                        if(!activity.hasWindowFocus() || field("player").get(activity)!=media)
                            throw new AssertionError("Buffer fixture interrupted");
                        samples.put(new JSONObject().put("second",second+1)
                                .put("positionMs",media.getCurrentPosition())
                                .put("outputFps",IjkStreamMetrics.outputFrameRate(media))
                                .put("gpuTiming",NativeHdrOutput.performance())
                                .put("decodeFps",media.getVideoDecodeFramesPerSecond())
                                .put("tcpBytesPerSecond",media.getTcpSpeed())
                                .put("videoCacheBytes",media.getVideoCachedBytes())
                                .put("processCpuMs",android.os.Process.getElapsedCpuTime()-cpuStart)
                                .put("videoCacheMs",media.getVideoCachedDuration())
                                .put("audioCacheMs",media.getAudioCachedDuration())
                                .put("buffering",field("buffering").getBoolean(activity)));
                        if(second%15==14)android.util.Log.i("nTvHdrTest","BUFFER_SAMPLE "+samples.getJSONObject(second));
                    }
                    report.append(new JSONObject().put("monitorUrl",url).put("hdrMode",hdrMode)
                            .put("delayMode",field("liveDelayMode").get(activity))
                            .put("decodeMode",mode)
                            .put("decoder",media.getMediaInfo().mVideoDecoderImpl)
                            .put("firstFrameMs",firstFrameMs)
                            .put("warmupStatuses",warmupStatuses)
                            .put("startupVideoCacheMs",startupCacheMs)
                            .put("elapsedMs",SystemClock.elapsedRealtime()-begin)
                            .put("positionAdvancedMs",media.getCurrentPosition()-position)
                            .put("bufferStateTransitions",field("bufferingEventId").getInt(activity)-events)
                            .put("gpuFramesAdvanced",NativeHdrOutput.renderedFrameCount()-gpuStart)
                            .put("samples",samples)).append('\n');
                    if("true".equals(args.getString("requireContinuous"))) {
                        if(field("bufferingEventId").getInt(activity)!=events
                                || Math.abs(SystemClock.elapsedRealtime()-begin
                                        -(media.getCurrentPosition()-position))>2000)
                            throw new AssertionError("Continuous playback interrupted: "+report);
                        for(int sample=0;sample<samples.length();sample++)
                            if(samples.getJSONObject(sample).getBoolean("buffering"))
                                throw new AssertionError("Buffering during continuous playback: "+report);
                    }
                }
                float fps=IjkStreamMetrics.outputFrameRate(media);
                long frames=NativeHdrOutput.renderedFrameCount();
                JSONObject videoInfo=checkVideoInfo(media);
                long hold=Long.parseLong(args.getString("holdMs","0"));
                if(hold>0) {
                    android.util.Log.i("nTvHdrTest","DIAG_READY mode="+mode+" hdrMode="+hdrMode);
                    SystemClock.sleep(Math.min(hold,60000));
                }
                if(media.getVideoDecoder()==IjkMediaPlayer.FFP_PROPV_DECODER_MEDIACODEC) {
                    String expectedGpu=args.getString("expectedGpu");
                    if("true".equals(expectedGpu) && frames<30)
                        throw new AssertionError("Required hardware/soft display is inactive");
                    if("false".equals(expectedGpu) && frames!=0)
                        throw new AssertionError("Unexpected GPU output");
                    if(expectedGpu==null && "true".equals(args.getString("hdrFixture","true"))
                            && !"hardware".equals(hdrMode) && frames<30)
                        throw new AssertionError("GPU colour output is inactive");
                }
                runOnMainSync(media::pause);
                SystemClock.sleep(250);
                Bitmap image=getUiAutomation().takeScreenshot();
                if(image==null)throw new AssertionError("No screenshot");
                int colored=0;
                // The channel card/debug overlay must never turn a black video
                // into a passing capture. Use a video-only upper-centre region.
                for(int y=image.getHeight()/5;y<image.getHeight()*2/5;y+=8)
                    for(int x=image.getWidth()/4;x<image.getWidth()*3/4;x+=8)
                        if((image.getPixel(x,y)&0xffffff)!=0)colored++;
                File file=new File(activity.getExternalFilesDir(null),"hdr-"+mode+"-"+hdrMode+".png");
                try(FileOutputStream stream=new FileOutputStream(file)) {image.compress(Bitmap.CompressFormat.PNG,100,stream);}
                image.recycle();
                // Some old vendor composers cannot capture native Main10 overlays.
                // Keep the evidence and report this limitation; never waive GPU/software output.
                if(colored<100 && (software || !"hardware".equals(hdrMode)
                        || !"true".equals(args.getString("allowNativeHdrCaptureBlack"))))
                    throw new AssertionError(mode+"/"+hdrMode+" screenshot is black: "+file);
                JSONObject row=new JSONObject().put("mode",mode).put("hdrMode",hdrMode)
                        .put("decoder",media.getMediaInfo().mVideoDecoderImpl)
                        .put("actualDecoderType",media.getVideoDecoder()==IjkMediaPlayer.FFP_PROPV_DECODER_MEDIACODEC
                                ? "hardware" : "software")
                        .put("width",media.getVideoWidth()).put("height",media.getVideoHeight())
                        .put("outputFps",fps).put("gpuFrames",frames)
                        .put("gpuTiming",NativeHdrOutput.performance())
                        .put("captureVisible",colored>=100)
                        .put("videoInfo",videoInfo)
                        .put("positionMs",media.getCurrentPosition()).put("screenshot",file.getAbsolutePath());
                report.append(row).append('\n');android.util.Log.i("nTvHdrTest",row.toString());
                runOnMainSync(media::start);
                }
                runOnMainSync(() -> {try {
                    Method release=MainActivity.class.getDeclaredMethod("releasePlayer");release.setAccessible(true);release.invoke(activity);
                }catch(Exception error){throw new RuntimeException(error);}});
                SystemClock.sleep(1200);
            }
            code=-1;
        }catch(Throwable error){report.append(android.util.Log.getStackTraceString(error));}
        finally {
            if(localFileServer!=null)try {localFileServer.close();}catch(java.io.IOException ignored) {}
            for(java.net.Socket socket:localClients)try {socket.close();}catch(java.io.IOException ignored) {}
            if(activity!=null)runOnMainSync(() -> {
                if(originalWindowCallback!=null)activity.getWindow().setCallback(originalWindowCallback);
                activity.finish();
            });
            if(savedGroups!=null)ChannelCatalog.GROUPS=savedGroups;
            SystemClock.sleep(500);
            SharedPreferences.Editor edit=getTargetContext().getSharedPreferences(MainActivity.PREFERENCES,0).edit().clear();
            for(Map.Entry<String,?> entry:saved.entrySet()) {
                String key=entry.getKey();Object value=entry.getValue();
                if(value instanceof String)edit.putString(key,(String)value);
                else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);
                else if(value instanceof Integer)edit.putInt(key,(Integer)value);
                else if(value instanceof Long)edit.putLong(key,(Long)value);
                else if(value instanceof Float)edit.putFloat(key,(Float)value);
                else if(value instanceof java.util.Set)edit.putStringSet(key,(java.util.Set<String>)value);
            }
            edit.commit();
        }
        result.putString("hdr",report.toString());finish(code,result);
    }
}
