package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;
import tv.danmaku.ijk.media.player.misc.ITrackInfo;

/** Diagnose actual production playback, comparing audio clocks without changing the source. */
public final class LocalStreamFrameInstrumentation extends Instrumentation {
    private MainActivity activity;
    private Bundle args;
    private Thread logStress;
    private android.view.Window.Callback originalWindowCallback;
    private Field field(String name) throws Exception {
        Field value = MainActivity.class.getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }
    private void invoke(String name) throws Exception {
        Method value = MainActivity.class.getDeclaredMethod(name);
        value.setAccessible(true);
        value.invoke(activity);
    }
    @Override public void onCreate(Bundle value) { super.onCreate(value); args=value; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        StringBuilder report = new StringBuilder();
        Map<String, ?> saved = null;
        ChannelCatalog.Group[] groups = null;
        int code = -1;
        try {
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(Long.parseLong(args.getString("startupWaitMs", "1500")));
            saved = activity.getSharedPreferences(MainActivity.PREFERENCES, 0).getAll();
            groups = ChannelCatalog.GROUPS;
            runOnMainSync(() -> {
                originalWindowCallback=activity.getWindow().getCallback();
                activity.getWindow().setCallback((android.view.Window.Callback)
                        java.lang.reflect.Proxy.newProxyInstance(android.view.Window.Callback.class.getClassLoader(),
                        new Class<?>[]{android.view.Window.Callback.class}, (proxy, method, values) -> {
                            // This fixture must not navigate on physical/ghost touches.
                            if("dispatchTouchEvent".equals(method.getName())) return true;
                            return method.invoke(originalWindowCallback,values);
                        }));
            });
            if ("codec_benchmark".equals(args.getString("mode"))) {
                runOnMainSync(() -> { try { invoke("closeWebSource"); invoke("releasePlayer"); }
                    catch(Exception e) { throw new RuntimeException(e); } });
                codecBenchmark(report);
            } else {
            String url = args.getString("url", "http://192.168.0.10:8767/cctv4k.m3u8");
            Channel channel = new Channel("1", "LOCAL_FRAME_DIAG", "diag", url, null, null);
            runOnMainSync(() -> { try {
                field("autoSwitchSource").setBoolean(activity, false);
                if (args.containsKey("decodeMode")) {
                    field("decodeMode").set(activity, args.getString("decodeMode"));
                    field("defaultDecodeMode").set(activity, args.getString("decodeMode"));
                }
                if(args.containsKey("delayMode")) field("liveDelayMode").set(activity,
                        "true".equals(args.getString("settingsApi"))
                                ? ("low".equals(args.getString("delayMode")) ? "stable" : "low")
                                : args.getString("delayMode"));
                ChannelCatalog.GROUPS = new ChannelCatalog.Group[]{new ChannelCatalog.Group(
                        "LOCAL_FRAME_DIAG", ChannelCatalog.SOURCE_CUSTOM, new Channel[]{channel})};
                field("currentGroupIndex").setInt(activity, 0);
                field("currentChannelIndex").setInt(activity, 0);
                field("currentSourceIndex").setInt(activity, 0);
                if (url.startsWith("/") || "true".equals(args.getString("direct"))) {
                    invoke("closeWebSource"); invoke("releasePlayer");
                    AppLog.loadIjkLibraries();
                    IjkMediaPlayer local = new IjkMediaPlayer();
                    field("player").set(activity, local);
                    field("videoRenderingStarted").setBoolean(activity, false);
                    local.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER,"mediacodec",1);
                    local.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER,"mediacodec-hevc",1);
                    if(args.containsKey("frameSync")) local.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER,
                            "ntv-frame-sync",Integer.parseInt(args.getString("frameSync")));
                    local.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER,"framedrop",
                            Integer.parseInt(args.getString("frameDrop","1")));
                    if(args.containsKey("maxFps")) local.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER,
                            "max-fps",Integer.parseInt(args.getString("maxFps")));
                    if(args.containsKey("pictureQueue")) local.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER,
                            "video-pictq-size",Integer.parseInt(args.getString("pictureQueue")));
                    if(args.containsKey("minFrames")) local.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER,
                            "min-frames",Integer.parseInt(args.getString("minFrames")));
                    if(args.containsKey("bufferBytes")) local.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER,
                            "max-buffer-size",Integer.parseInt(args.getString("bufferBytes")));
                    if(args.containsKey("audio")) local.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER,
                            "ntv-initial-audio",Integer.parseInt(args.getString("audio")));
                    if(args.containsKey("infbuf")) local.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER,
                            "infbuf",Integer.parseInt(args.getString("infbuf")));
                    local.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER,"start-on-prepared",0);
                    DirectVideoView view=(DirectVideoView)field("videoView").get(activity);
                    local.setDisplay(view.getVideoSurfaceHolder());
                    local.setOnPreparedListener(mp -> { try {
                        field("prepared").setBoolean(activity,true);
                        Method layout=MainActivity.class.getDeclaredMethod("updateVideoLayout",
                                tv.danmaku.ijk.media.player.IMediaPlayer.class);
                        layout.setAccessible(true); layout.invoke(activity,mp);
                        mp.start();
                    } catch(Exception e) { throw new RuntimeException(e); } });
                    local.setOnInfoListener((mp,what,extra) -> { try {
                        if(what==3) field("videoRenderingStarted").setBoolean(activity,true);
                        if(what==701||what==702) field("buffering").setBoolean(activity,what==701);
                    } catch(Exception e) { throw new RuntimeException(e); } return false; });
                    local.setDataSource(url); local.prepareAsync();
                } else {
                    Method change = MainActivity.class.getDeclaredMethod("switchChannel", int.class);
                    change.setAccessible(true); change.invoke(activity, 0);
                }
            } catch (Exception e) { throw new RuntimeException(e); } });
            long deadline = SystemClock.elapsedRealtime() + 60000;
            while (!field("videoRenderingStarted").getBoolean(activity)
                    || "software".equals(args.getString("decodeMode"))
                    && !(field("player").get(activity) instanceof IjkMediaPlayer)) {
                if (SystemClock.elapsedRealtime() > deadline) throw new AssertionError("First frame timeout");
                SystemClock.sleep(200);
            }
            if ("true".equals(args.getString("settingsApi"))) {
                Object previous = field("player").get(activity);
                java.net.HttpURLConnection connection = (java.net.HttpURLConnection)
                        new java.net.URL("http://127.0.0.1:9966/api/settings").openConnection();
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setConnectTimeout(5000); connection.setReadTimeout(15000);
                byte[] body = new JSONObject().put("liveDelayMode",args.getString("delayMode"))
                        .toString().getBytes("UTF-8");
                connection.getOutputStream().write(body);
                if(connection.getResponseCode()!=200) throw new AssertionError("Settings API failed");
                connection.getInputStream().close(); connection.disconnect();
                deadline=SystemClock.elapsedRealtime()+60000;
                while(field("player").get(activity)==previous
                        || !field("videoRenderingStarted").getBoolean(activity)) {
                    if(SystemClock.elapsedRealtime()>deadline) throw new AssertionError("Settings did not restart playback");
                    SystemClock.sleep(200);
                }
                String mode=args.getString("delayMode");
                if(!mode.equals(activity.getSharedPreferences(MainActivity.PREFERENCES,0)
                        .getString("live_delay_mode",""))) throw new AssertionError("Mode not saved");
                HlsProxyServer proxy=(HlsProxyServer)field("proxy").get(activity);
                JSONObject profile=new JSONObject().put("settingsApi",true).put("mode",mode);
                int expected="low".equals(mode)?0:"balanced".equals(mode)?1:2;
                for(String name:new String[]{"genericStartupPrefetchSegments",
                        "cctvStartupDownloadSegments","cctvStartupDecryptSegments"}) {
                    Field value=HlsProxyServer.class.getDeclaredField(name);value.setAccessible(true);
                    int count=value.getInt(proxy);profile.put(name,count);
                    if(count!=expected)throw new AssertionError(name+"="+count);
                }
                for(String name:new String[]{"liveIjkMinFrames","liveIjkFirstBufferMs",
                        "liveIjkNextBufferMs","liveIjkLastBufferMs"}) {
                    Method value=MainActivity.class.getDeclaredMethod(name);value.setAccessible(true);
                    profile.put(name,value.invoke(activity));
                }
                report.append(profile).append('\n');
            }
            if(args.containsKey("initialAudio") && !url.startsWith("/")) {
                runOnMainSync(() -> { try {
                    Method start=MainActivity.class.getDeclaredMethod("startIjkPlayer",Channel.class,
                            String.class,boolean.class,boolean.class,int[].class);
                    start.setAccessible(true);start.invoke(activity,channel,url,false,false,
                            new int[]{Integer.parseInt(args.getString("initialAudio")),0,-1});
                } catch(Exception e) {throw new RuntimeException(e);} });
                deadline=SystemClock.elapsedRealtime()+60000;
                while(!field("videoRenderingStarted").getBoolean(activity)) {
                    if(SystemClock.elapsedRealtime()>deadline) throw new AssertionError("Reopen timeout");
                    SystemClock.sleep(200);
                }
            }
            IjkMediaPlayer media = (IjkMediaPlayer) field("player").get(activity);
            JSONArray tracks = new JSONArray();
            int aac = -1;
            ITrackInfo[] info = media.getTrackInfo();
            for (int i=0; i<info.length; i++) {
                String description = info[i].getInfoInline();
                tracks.put(new JSONObject().put("index",i).put("type",info[i].getTrackType())
                        .put("description",description));
                if(info[i].getTrackType()==ITrackInfo.MEDIA_TRACK_TYPE_AUDIO
                        && description.toLowerCase(java.util.Locale.US).contains("aac")) aac=i;
            }
            report.append(new JSONObject().put("url",url).put("width",media.getVideoWidth())
                    .put("height",media.getVideoHeight()).put("tracks",tracks)
                    .put("metadata",media.getMediaMeta().toString())
                    .put("videoDecoder",media.getMediaInfo().mVideoDecoder)
                    .put("videoDecoderImpl",media.getMediaInfo().mVideoDecoderImpl)
                    .put("audioDecoderImpl",media.getMediaInfo().mAudioDecoderImpl)).append('\n');
            int original = media.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO);
            String[] phases = args.getString("phases", "default,aac,no_audio").split(",");
            for (String phase: phases) {
                final int selected = "aac".equals(phase) ? aac : "default".equals(phase) ? original : -1;
                runOnMainSync(() -> {
                    int active = media.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO);
                    if(selected>=0) media.selectTrack(selected);
                    else if(active>=0) media.deselectTrack(active);
                });
                SystemClock.sleep(Long.parseLong(args.getString("warmupMs", "8000")));
                long focusDeadline=SystemClock.elapsedRealtime()+60000;
                while(!activity.hasWindowFocus()) {
                    if(SystemClock.elapsedRealtime()>focusDeadline) throw new AssertionError("Activity is not foreground");
                    SystemClock.sleep(200);
                }
                long cpu = Process.getElapsedCpuTime(), start = SystemClock.elapsedRealtime();
                int bufferEvents=field("bufferingEventId").getInt(activity);
                long position = media.getCurrentPosition();
                JSONArray samples = new JSONArray();
                Method property = IjkMediaPlayer.class.getDeclaredMethod("_getPropertyFloat",int.class,float.class);
                property.setAccessible(true);
                Method longProperty = IjkMediaPlayer.class.getDeclaredMethod("_getPropertyLong",int.class,long.class);
                longProperty.setAccessible(true);
                int seconds = Integer.parseInt(args.getString("seconds", "20"));
                if ("true".equals(args.getString("logStress"))) {
                    logStress = new Thread(() -> {
                        try {
                            int count = 0;
                            while (!Thread.currentThread().isInterrupted()) {
                                AppLog.i("VideoLogStress", "playback log " + count++);
                                Thread.sleep(10);
                            }
                        } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                    }, "video-log-stress");
                    logStress.start();
                }
                for (int i=0; i<seconds; i++) {
                    SystemClock.sleep(1000);
                    if(field("player").get(activity)!=media) throw new AssertionError("Player replaced");
                    if(!activity.hasWindowFocus()) throw new AssertionError("Activity lost foreground at sample "+i);
                    samples.put(new JSONObject().put("second",i+1)
                            .put("decodeFps",property.invoke(media,
                                    IjkMediaPlayer.PROP_FLOAT_VIDEO_DECODE_FRAMES_PER_SECOND,0f))
                            .put("dropRate",property.invoke(media,IjkMediaPlayer.FFP_PROP_FLOAT_DROP_FRAME_RATE,0f))
                            .put("avDelaySeconds",property.invoke(media,10004,0f))
                            .put("avDiffSeconds",property.invoke(media,10005,0f))
                            .put("outputFps",media.getVideoOutputFramesPerSecond())
                            .put("sourceFps",IjkStreamMetrics.sourceFrameRate(media))
                            .put("outputFrames",longProperty.invoke(media,22001,0L))
                            .put("videoCacheMs",media.getVideoCachedDuration())
                            .put("audioCacheMs",media.getAudioCachedDuration())
                            .put("videoCacheBytes",longProperty.invoke(media,IjkMediaPlayer.FFP_PROP_INT64_VIDEO_CACHED_BYTES,0L))
                            .put("audioCacheBytes",longProperty.invoke(media,IjkMediaPlayer.FFP_PROP_INT64_AUDIO_CACHED_BYTES,0L))
                            .put("positionMs",media.getCurrentPosition())
                            .put("surfaceReady",((DirectVideoView)field("videoView").get(activity)).isSurfaceReady())
                            .put("windowFocused",activity.hasWindowFocus())
                            .put("buffering",field("buffering").getBoolean(activity)));
                }
                JSONObject row = new JSONObject().put("phase",phase)
                        .put("selectedAudio",media.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO))
                        .put("elapsedMs",SystemClock.elapsedRealtime()-start)
                        .put("rebufferEvents",field("bufferingEventId").getInt(activity)-bufferEvents)
                        .put("positionAdvancedMs",media.getCurrentPosition()-position)
                        .put("processCpuMs",Process.getElapsedCpuTime()-cpu).put("samples",samples);
                report.append(row).append('\n');
                if (logStress != null) { logStress.interrupt(); logStress.join(1000); logStress = null; }
                android.util.Log.i("LocalFrameDiag",row.toString());
            }
            }
        } catch (Throwable error) { code=1; report.append(android.util.Log.getStackTraceString(error)); }
        finally {
            if (logStress != null) logStress.interrupt();
            final Map<String, ?> prefs=saved; final ChannelCatalog.Group[] original=groups;
            if(activity!=null) runOnMainSync(() -> { try {
                invoke("closeWebSource"); invoke("releasePlayer");
                if(original!=null) ChannelCatalog.GROUPS=original;
                if(prefs!=null) {
                    SharedPreferences.Editor edit=activity.getSharedPreferences(MainActivity.PREFERENCES,0).edit().clear();
                    for(Map.Entry<String, ?> entry:prefs.entrySet()) {
                        String key=entry.getKey(); Object value=entry.getValue();
                        if(value instanceof String)edit.putString(key,(String)value);
                        else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);
                        else if(value instanceof Integer)edit.putInt(key,(Integer)value);
                        else if(value instanceof Long)edit.putLong(key,(Long)value);
                        else if(value instanceof Float)edit.putFloat(key,(Float)value);
                        else if(value instanceof java.util.Set)edit.putStringSet(key,(java.util.Set<String>)value);
                    }
                    edit.commit();
                }
                activity.finish();
                if(originalWindowCallback!=null) activity.getWindow().setCallback(originalWindowCallback);
            } catch(Exception e) { throw new RuntimeException(e); } });
        }
        result.putString("stream",report.toString()); finish(code,result);
    }

    private void codecBenchmark(StringBuilder report) throws Exception {
        String path=args.getString("file", "/sdcard/Android/data/xiao.bu.tv/files/local-cctv4k-aac.mp4");
        DirectVideoView view=(DirectVideoView)field("videoView").get(activity);
        runOnMainSync(() -> view.setVideoSize(3840,2160,1,1));
        SystemClock.sleep(500);
        for(boolean render:new boolean[]{false,true}) for(boolean operating:new boolean[]{false,true}) {
            android.media.MediaExtractor extractor=new android.media.MediaExtractor();
            android.media.MediaCodec codec=null;
            try {
                extractor.setDataSource(path);
                int video=-1;
                for(int i=0;i<extractor.getTrackCount();i++)
                    if(extractor.getTrackFormat(i).getString("mime").startsWith("video/")) {video=i;break;}
                extractor.selectTrack(video);
                android.media.MediaFormat format=extractor.getTrackFormat(video);
                format.setInteger("frame-rate",50);
                float operatingRate=Float.parseFloat(args.getString("operatingRate","100"));
                if(operating) { format.setFloat("operating-rate",operatingRate); format.setInteger("priority",0); }
                codec=android.media.MediaCodec.createByCodecName("OMX.qcom.video.decoder.hevc");
                boolean supported=codec.getCodecInfo().getCapabilitiesForType("video/hevc")
                        .getVideoCapabilities().areSizeAndRateSupported(3840,2160,50);
                codec.configure(format,view.getVideoSurface(),null,0); codec.start();
                android.media.MediaCodec.BufferInfo info=new android.media.MediaCodec.BufferInfo();
                long start=SystemClock.elapsedRealtime(),first=0,last=0; int frames=0,queued=0;
                while(SystemClock.elapsedRealtime()-start<6000) {
                    for(int batch=0;batch<8;batch++) {
                        int input=codec.dequeueInputBuffer(0);
                        if(input<0)break;
                        java.nio.ByteBuffer buffer=codec.getInputBuffer(input);buffer.clear();
                        int bytes=extractor.readSampleData(buffer,0);
                        if(bytes<0) break;
                        codec.queueInputBuffer(input,0,bytes,extractor.getSampleTime(),0);
                        extractor.advance();queued++;
                    }
                    int output=codec.dequeueOutputBuffer(info,1000);
                    if(output>=0) {
                        long now=SystemClock.elapsedRealtime(); if(first==0)first=now; last=now;frames++;
                        codec.releaseOutputBuffer(output,render);
                    }
                }
                report.append(new JSONObject().put("mode","codec_benchmark").put("render",render)
                        .put("operatingRate",operating?operatingRate:0).put("supports4k50",supported)
                        .put("elapsedMs",SystemClock.elapsedRealtime()-start).put("frames",frames)
                        .put("queued",queued).put("outputFps",frames>1?(frames-1)*1000d/(last-first):0)
                        .put("windowFocused",activity.hasWindowFocus()).put("surfaceReady",view.isSurfaceReady()))
                        .append('\n');
            } finally {
                if(codec!=null) {try{codec.stop();}catch(RuntimeException ignored){} finally{codec.release();}}
                extractor.release();
            }
        }
    }
}
