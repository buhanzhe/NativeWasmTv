package xiao.bu.tv;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded nonfatal reports. No player, Activity, networking or SDK calls on the caller thread. */
final class PlayerErrorReports {
    interface Clock { long now(); }
    interface Sink { void post(Throwable error,Thread origin); }
    private static final Pattern URL=Pattern.compile("(?i)(?:https?|rtsp|rtmp|file|content|webview)://[^\\s\\\"'<>]+");
    private static final Pattern HEADER=Pattern.compile("(?im)(authorization|(?:set-)?cookie)(\\s*[:=]\\s*)[^\\r\\n]+");
    private static final Pattern SECRET=Pattern.compile("(?i)(token|signature|password|passwd|secret|access_key)(\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)");
    private static final Pattern LINE=Pattern.compile("^\\S+\\s+\\S+\\s+(\\d+)\\s+(\\d+)\\s+([EFWI])\\s+(\\S+)\\s*:\\s?(.*)$");
    private static final Pattern HEX=Pattern.compile("0x[0-9a-fA-F]+"),NUMBER=Pattern.compile("\\d+"),SPACE=Pattern.compile("\\s+");
    static final String MARKER_TAG="NtvPlayerReport";
    private static final AtomicLong reportIds=new AtomicLong();
    private final Clock clock;
    private final Executor executor;
    private final Sink sink;
    private final LinkedHashMap<String,Long> last=new LinkedHashMap<>();
    private final LinkedHashMap<Long,Scene> scenes=new LinkedHashMap<>();
    private long sequence,lastCallback=Long.MIN_VALUE,lastNative=Long.MIN_VALUE,windowStart;
    private int windowCount;
    private volatile Scene current;

    static final class Scene {
        final long id; final int request; final String context;
        private final ArrayDeque<String> logs=new ArrayDeque<>();
        private int size;
        Scene(long id,int request,String context){this.id=id;this.request=request;this.context=context;}
        synchronized void log(String value){
            value=clean(value,600);logs.addLast(value);size+=value.length();
            while(logs.size()>24||size>6000)size-=logs.removeFirst().length();
        }
        synchronized String logs(){StringBuilder text=new StringBuilder();for(String line:logs)text.append(line).append('\n');return text.toString();}
    }

    PlayerErrorReports(Clock clock,Executor executor,Sink sink){this.clock=clock;this.executor=executor;this.sink=sink;}
    synchronized Scene begin(int request,String backend,String channel,String source,String decode,int line){
        String host="",scheme="";
        try{URI uri=new URI(source);host=uri.getHost();scheme=uri.getScheme();}catch(Exception ignored){}
        // Context stores only the host, never credentials, path or signed query.
        Scene scene=new Scene(++sequence,request,"backend="+clean(backend,40)+"; channel="+clean(channel,160)
                +"; sourceScheme="+clean(scheme,20)+"; sourceHost="+clean(host,160)
                +"; line="+(line+1)+"; decode="+clean(decode,40));
        scenes.put(scene.id,scene);while(scenes.size()>8)scenes.remove(scenes.keySet().iterator().next());
        current=scene;return scene;
    }
    synchronized Scene scene(long id){return scenes.get(id);}
    void end(){current=null;}
    void error(int request,String component,String kind,String message,Throwable error){
        Scene scene=current;if(scene==null||scene.request!=request)return;
        submit(scene,component,kind,message,error,Thread.currentThread(),true);
    }
    void unscopedException(String component,String operation,Throwable error){
        // Teardown may finish after another channel starts. Report the exception
        // without falsely assigning that old player's failure to the new channel.
        Scene scene=new Scene(0,-1,"backend="+clean(component,40)+"; phase=old-player-release; channel=unavailable");
        submit(scene,component,operation,error.getClass().getName()+": "+error.getMessage(),error,Thread.currentThread(),true);
    }
    private synchronized boolean reserve(String key,boolean callback){
        long now=clock.now();Long previous=last.get(key);
        if(previous!=null&&now>=previous&&now-previous<600000)return false;
        long lastUpload=callback?lastCallback:lastNative;
        if(lastUpload!=Long.MIN_VALUE&&now>=lastUpload&&now-lastUpload<(callback?1000:10000))return false;
        if(now<windowStart||now-windowStart>=600000){windowStart=now;windowCount=0;}
        if(windowCount>=10)return false;
        windowCount++;if(callback)lastCallback=now;else lastNative=now;last.remove(key);last.put(key,now);
        while(last.size()>64)last.remove(last.keySet().iterator().next());
        return true;
    }
    private void submit(final Scene scene,final String component,final String kind,
            final String message,final Throwable error,final Thread origin,boolean callback){
        final String safe=clean(message,1000);
        final String key=scene.context+":"+component+":"+kind+":"+signature(safe)+(error==null?"":":"+error.getClass().getName());
        if(!reserve(key,callback))return;
        try{executor.execute(()->{
            try{
                String detail="component="+clean(component,40)+"; event="+clean(kind,80)
                        +"\n"+scene.context+"\n"+safe;
                if(error!=null)detail+="\nCaught exception:\n"+caughtDetail(error);
                String nativeLogs=scene.logs();if(!nativeLogs.isEmpty())detail+="\nRecent player errors:\n"+nativeLogs;
                PlaybackFailure report=new PlaybackFailure(reportIds.incrementAndGet(),
                        clean(component+"/"+kind+": "+signature(safe),900),clean(detail,14000));
                report.setStackTrace(error==null?new StackTraceElement[]{new StackTraceElement(
                        "nTv.playback."+component,kind+"_"+Integer.toHexString(signature(safe).hashCode()),"Playback",-1)}
                        :boundedStack(error.getStackTrace()));
                sink.post(report,origin);
            }catch(RuntimeException|LinkageError ignored){/* Reporting cannot interrupt playback. */}
        });}catch(RuntimeException ignored){/* A full/closed queue drops telemetry only. */}
    }
    private static StackTraceElement[] boundedStack(StackTraceElement[] frames){
        int count=Math.min(frames.length,48);StackTraceElement[] copy=new StackTraceElement[count];
        for(int i=0;i<count;i++){StackTraceElement frame=frames[i];copy[i]=new StackTraceElement(
                clean(frame.getClassName(),200),clean(frame.getMethodName(),200),
                frame.getFileName()==null?null:clean(frame.getFileName(),200),frame.getLineNumber());}
        return copy;
    }
    private static String caughtDetail(Throwable error){
        StringBuilder text=new StringBuilder();
        for(int cause=0;error!=null&&cause<4&&text.length()<5500;cause++){
            text.append(error.getClass().getName()).append(": ").append(clean(error.getMessage(),700)).append('\n');
            StackTraceElement[] frames=error.getStackTrace();
            for(int i=0;i<frames.length&&i<32&&text.length()<5500;i++)text.append(" at ").append(clean(frames[i].toString(),300)).append('\n');
            Throwable next=error.getCause();if(next==error)break;error=next;
        }
        return clean(text.toString(),6000);
    }
    private static String signature(String value){return SPACE.matcher(NUMBER.matcher(HEX.matcher(value).replaceAll("#")).replaceAll("#")).replaceAll(" ");}
    static String clean(String value,int maximum){
        if(value==null)return "";
        // Bound before regex work as exception messages may be supplied by a server.
        value=value.substring(0,Math.min(value.length(),Math.max(maximum*2,4096)));
        value=URL.matcher(value).replaceAll("[url]");
        value=HEADER.matcher(value).replaceAll("$1$2[redacted]");
        value=SECRET.matcher(value).replaceAll("$1$2[redacted]");
        return value.substring(0,Math.min(value.length(),maximum));
    }
    static final class PlaybackFailure extends RuntimeException {
        private static final long serialVersionUID=1L;
        final String id,details;
        // Keep the issue signature stable across sessions/channels. Per-report
        // IDs and channel details live in the correlated BuglyLog attachment.
        PlaybackFailure(long id,String message,String details){super(message);this.id=String.valueOf(id);this.details=details;}
    }

    /** Each tail follows ordered scene markers, so buffered lines retain their original channel. */
    static boolean diagnosticOnly(String tag,String lower){
        // Some vendor libraries emit routine lifecycle/debug messages at ERROR.
        // Match only known messages, never whole components or decoder failures.
        if("AudioTrack".equals(tag))return lower.matches("(?:start|pause|stop)\\(\\): notifytracksessionid \\d+")
                ||lower.matches("start\\(\\d+\\): prior state:state_paused");
        if("IJKMEDIA".equals(tag))return lower.startsWith("sdl_jni_threaddestroyed:")
                    &&lower.contains("didn't call sdl_jni_detachthreadenv() explicity")
                ||lower.equals("sdl_amediacodecjava_stop: stop")
                ||lower.matches("sdl_overlay\\(ffmpeg\\): overlay_free_l\\(\\d+\\)")
                ||lower.matches("ijk_gles\\d+_renderer_texcoords_cropright");
        if("MainActivity".equals(tag))return lower.contains("java.io.interruptedioexception:")
                &&lower.contains("源地址解析已取消");
        if("MediaCodec".equals(tag))return lower.equals("media quality service not found.");
        if("ACodec".equals(tag))return lower.startsWith("configurecodec_amlogic_processvideohwcomposed,videodecoder ");
        return false;
    }
    final class Tail {
        private final int pid;private final String token;private Scene selected;private long nextAttempt;
        Tail(int pid,String token){this.pid=pid;this.token=token;}
        void line(String value){
            Matcher match=LINE.matcher(value);if(!match.matches()||!match.group(1).equals(String.valueOf(pid)))return;
            String tag=match.group(4),message=match.group(5);
            if(MARKER_TAG.equals(tag)){
                if(message.startsWith(token+":"))try{selected=scene(Long.parseLong(message.substring(token.length()+1).trim()));}catch(NumberFormatException ignored){}
                return;
            }
            Scene scene=selected;if(scene==null)return;
            String lower=message.toLowerCase(Locale.US);
            if(lower.startsWith("csd-")||lower.startsWith("configure acodec:")
                    ||lower.contains("sdl_amediaformatjava_getint32: callintmethod: failed")
                    ||lower.contains("setportmode on output to dynamicanwbuffer failed w/ err -1010")
                    ||lower.contains("broken pipe")||lower.contains("epipe")
                    ||lower.contains("option ijkiomanager not found")||lower.contains("option live_start_index not found"))return;
            if(lower.contains("option scan_all_pmts not found"))return;
            boolean app="MainActivity".equals(tag);
            scene.log(tag+" tid="+match.group(2)+": "+message);
            if(diagnosticOnly(tag,lower))return;
            if(app&&!(lower.contains("exception")||lower.contains("unable")||lower.contains("failed")))return;
            if(app&&!(lower.contains("player")||lower.contains("decoder")||lower.contains("codec")
                    ||lower.contains("playback")||lower.contains("surface")||lower.contains("audio")
                    ||lower.contains("seek")||lower.contains("track")||lower.contains("stream")||lower.contains("subtitle")))return;
            if(app&&lower.contains("release old player"))return;
            if(!app&&!"E".equals(match.group(3))&&!"F".equals(match.group(3)))return;
            long now=clock.now();if(now<nextAttempt)return;nextAttempt=now+1000;
            String kind=lower.contains("nal")||lower.contains("pps")||lower.contains("sps")?"InvalidBitstream"
                    :lower.contains("timestamp")?"TimestampError"
                    :lower.contains("codec")||lower.contains("decoder")?"DecoderError":"NativeLog";
            submit(scene,tag,kind,message,null,Thread.currentThread(),false);
        }
    }
}
