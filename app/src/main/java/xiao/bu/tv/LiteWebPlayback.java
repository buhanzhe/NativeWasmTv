package xiao.bu.tv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.Closeable;
import java.util.Map;
import org.json.JSONObject;

/** Owns one lightweight page and its live media transport until channel close. */
final class LiteWebPlayback implements Closeable {
    interface Host {
        void stream(String url, boolean temporary);
        void status(String value);
        void failure(String value);
        default void render(android.view.View page) { }
        default int networkThreadMultiplier() { return 0; }
        boolean playing();
        long position();
        long duration();
        int width();
        int height();
        void setPaused(boolean paused);
    }
    private final Context context;
    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LiteEngine engine;
    private LiteEngine.PlaybackTransport transport;
    private int generation, navigations;
    private boolean closed, temporary, streamActive, finalStream;
    private int finalHandoffs;
    private static final long SNIFF_TIMEOUT_MS = 60000L;
    private boolean sniffDeadlineStarted;
    private final Runnable sniffDeadline = () -> {
        if (!closed && !finalStream) fail("网页嗅探超时，未找到可播放资源");
    };
    private void fail(String message) {
        if (closed) return;
        close();
        host.failure(message);
    }
    private boolean virtualPlayback;
    private boolean liveHlsPlayback;
    private String controlledSource="";
    private Boolean controlPaused;
    private boolean auditEnabled;
    private long auditPoll;
    private JSONObject auditPage=new JSONObject();
    private final java.util.ArrayDeque<String> auditMessages=new java.util.ArrayDeque<>();
    private double lastPosition, lastDuration;
    private android.widget.FrameLayout renderedPage;
    private LiteCanvas canvas;
    private WebPageCover cover;
    private boolean pageCovered,active=true;
    private JSONObject pendingInput;
    void setActive(boolean value){active=value;if(engine!=null&&renderedPage!=null)engine.setRendering(value);}
    private String coverStyle="status";
    boolean isRendering(){return renderedPage!=null;}
    void applyPageCover(boolean enabled,String style){
        pageCovered=enabled;coverStyle=style;
        if(renderedPage==null)return;
        if(cover==null){cover=new WebPageCover(context,v->{pageCovered=false;cover.setVisibility(android.view.View.GONE);});renderedPage.addView(cover,new android.widget.FrameLayout.LayoutParams(-1,-1));}
        cover.setBlack("black".equals(style));cover.update(null,false,null);
        cover.setVisibility(enabled?android.view.View.VISIBLE:android.view.View.GONE);
    }
    boolean handleKey(android.view.KeyEvent event){
        if(canvas!=null&&canvas.editingText())return canvas.textInputKey(event);
        int key=event.getKeyCode();
        if(key!=android.view.KeyEvent.KEYCODE_DPAD_UP&&key!=android.view.KeyEvent.KEYCODE_DPAD_DOWN
                &&key!=android.view.KeyEvent.KEYCODE_DPAD_LEFT&&key!=android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                &&key!=android.view.KeyEvent.KEYCODE_DPAD_CENTER&&key!=android.view.KeyEvent.KEYCODE_ENTER)return false;
        if(pageCovered){if(event.getAction()==android.view.KeyEvent.ACTION_UP&&(key==23||key==66))revealPage();return true;}
        return canvas!=null&&canvas.dispatchKeyEvent(event);
    }
    boolean revealPage(){if(cover==null||!pageCovered)return false;pageCovered=false;cover.setVisibility(android.view.View.GONE);return true;}
    private void clearRenderedPage(){
        if(renderedPage!=null&&renderedPage.getParent() instanceof android.view.ViewGroup)
            ((android.view.ViewGroup)renderedPage.getParent()).removeView(renderedPage);
        if(canvas!=null){canvas.endTextInput();canvas.engine=null;}
        pendingInput=null;
        renderedPage=null;canvas=null;cover=null;
    }
    private final Runnable renderFallback=new Runnable(){public void run(){
        if(closed||finalStream||engine==null||renderedPage!=null)return;
        if(streamActive||!active){handler.postDelayed(this,500);return;}
        long ready=engine.documentReadyAt(),now=android.os.SystemClock.elapsedRealtime();
        if(ready==0||now-ready<10000){handler.postDelayed(this,ready==0?500:Math.min(500,10000-(now-ready)));return;}
        renderedPage=new android.widget.FrameLayout(context);
        canvas=new LiteCanvas(context);canvas.engine=engine;
        canvas.fixedViewportWidth=engine.viewportWidth();canvas.fixedViewportHeight=engine.viewportHeight();
        renderedPage.addView(canvas,new android.widget.FrameLayout.LayoutParams(-1,-1));
        applyPageCover(pageCovered,coverStyle);
        // An interactive login page can legitimately take longer than a media
        // sniff deadline. Keep the original page alive while the user signs in.
        handler.removeCallbacks(sniffDeadline);
        engine.renderPage();host.render(renderedPage);
        if(pendingInput!=null)canvas.textInput(pendingInput);
    }};
    LiteWebPlayback(Context context, Host host) { this.context=context; this.host=host; }
    static boolean supported() {
        return BuildConfig.BUNDLED_LITE_BROWSER;
    }
    JSONObject startupTimings(){return engine==null?new JSONObject():engine.startupTimings();}
    int finalHandoffs(){return finalHandoffs;}
    long upstreamDownloadedBytes(){return closed||engine==null?-1L:engine.upstreamDownloadedBytes();}
    void enableAudit(){auditEnabled=true;if(engine!=null){engine.traceRequests(true);long now=android.os.SystemClock.elapsedRealtime();if(now-auditPoll>=10000){auditPoll=now;engine.evaluate("(function(){var d=__diagnostics();console.log('NTV_AUDIT '+JSON.stringify({mgtv:typeof __mgtvAudit==='function'?__mgtvAudit():null,ready:d.readyState,runtime:d.runtime,wasm:d.wasm,parser:d.parser,timers:d.timers,pending:d.pendingHTTP,controls:d.playControls,media:document.querySelectorAll('video,audio').slice(0,4).map(function(v){return {ready:v.readyState,paused:v.paused,time:v.currentTime,hasSource:!!v.src}})}))})()");}}}
    JSONObject auditSnapshot(){
        JSONObject value=startupTimings();try{synchronized(auditMessages){value.put("messages",new org.json.JSONArray(auditMessages)).put("page",auditPage);}
            if(engine!=null)value.put("busy",engine.runtimeBusySnapshot());
        }catch(Exception ignored){}return value;
    }
    void open(String url, String script) {
        if (closed || finalStream) return;
        if (++navigations > 12) { fail("网页跳转次数过多"); return; }
        if (!sniffDeadlineStarted) {
            sniffDeadlineStarted = true;
            handler.postDelayed(sniffDeadline, SNIFF_TIMEOUT_MS);
        }
        final int epoch=++generation;
        handler.removeCallbacks(renderFallback);
        handler.removeCallbacks(progress);
        streamActive=temporary=false;lastPosition=lastDuration=0;
        controlPaused=null;virtualPlayback=liveHlsPlayback=false;controlledSource="";
        releaseEngine();
        try {
            engine=LiteEngine.withNetworkThreadMultiplier(context,new LiteEngine.Listener() {
                private boolean current() { return !closed && epoch==generation; }
                public void frame(JSONObject packet) { if(current()&&canvas!=null)canvas.frame(packet); }
                public void textInput(JSONObject state){if(current()){pendingInput=state;if(canvas!=null)canvas.textInput(state);}}
                public void message(String value) {
                    if(auditEnabled&&value.startsWith("NTV_AUDIT "))try{synchronized(auditMessages){auditPage=new JSONObject(value.substring(10));}}catch(Exception ignored){}
                    if(auditEnabled&&(value.startsWith("JS：")||value.startsWith("Promise rejection: "))){
                        java.util.regex.Matcher kind=java.util.regex.Pattern.compile("(?:Type|Reference|Range|Syntax|Internal|Eval|URI)?Error").matcher(value);
                        synchronized(auditMessages){if(auditMessages.size()>=96)auditMessages.removeFirst();String detail=value.split("\\r?\\n",2)[0].replaceAll("https?://[^\\s]+","[URL]").replaceAll("[A-Za-z0-9_=-]{64,}","[opaque]");auditMessages.addLast("JS exception: "+(kind.find()?kind.group():"unclassified")+" "+detail.substring(0,Math.min(detail.length(),240)));}
                    }
                    if(auditEnabled&&(value.startsWith("Document clock metadata ")||value.startsWith("HTTP ")||value.startsWith("API metadata ")||value.startsWith("CORS rejected metadata ")||value.startsWith("SCRIPT ")||value.startsWith("MSE ")||value.startsWith("Original player API status ")||value.startsWith("虚拟 MSE")||value.startsWith("运行错误：")||value.startsWith("加载失败："))){
                        String safe=value.replaceAll("https?://[^\\s]+","[URL]");
                        synchronized(auditMessages){if(auditMessages.size()>=96)auditMessages.removeFirst();auditMessages.addLast(safe.substring(0,Math.min(safe.length(),800)));}
                    }
                    if (!current()||finalStream) return;
                    if (value.startsWith("加载失败：") || value.startsWith("频道选择脚本无效："))
                        fail(value);
                }
                public void navigate(String next) {
                    if(!current()||finalStream)return;
                    okhttp3.HttpUrl previous=okhttp3.HttpUrl.parse(url),target=okhttp3.HttpUrl.parse(next);
                    boolean reload=previous!=null&&target!=null&&previous.newBuilder().removeAllQueryParameters("ujs").fragment(null).build().equals(target.newBuilder().removeAllQueryParameters("ujs").fragment(null).build());
                    // A provider's cookie-validation reload must retain the
                    // host's own channel-selection script. It is still removed
                    // before the HTTP request; real page navigation drops it.
                    String forwarded=reload&&target.queryParameter("ujs")==null?script:"";
                    if(reload&&target.queryParameter("ujs")==null&&previous.queryParameter("ujs")!=null)next=target.newBuilder().addQueryParameter("ujs",previous.queryParameter("ujs")).build().toString();
                    open(next,forwarded);
                }
                public void media(String source,Map<String,String> headers) { deliver(source,headers,false); }
                public void temporaryMedia(String source,Map<String,String> headers) { deliver(source,headers,true); }
                public void mediaControl(String source,boolean virtualMse,boolean paused){
                    if(!current()||!streamActive||virtualMse!=virtualPlayback)return;
                    if(!virtualMse&&!source.equals(controlledSource)){
                        if(!liveHlsPlayback||!LiteEngine.sameHlsSessionSource(source,controlledSource))return;
                        controlledSource=source;
                    }
                    controlPaused=paused;host.setPaused(paused);
                }
                private void deliver(String source,Map<String,String> headers,boolean preroll) {
                    if(!current()||finalStream)return;
                    try {
                        clearRenderedPage();handler.removeCallbacks(renderFallback);
                        releaseTransport();
                        String target=source;
                        // Preserve all validated upstream headers, cookies and the
                        // engine's TLS policy inside its own bounded HTTP relay.
                        boolean virtualMse="1".equals(headers.get("X-NTV-Virtual-MSE"));
                        virtualPlayback=virtualMse;controlledSource=source;controlPaused=null;
                        liveHlsPlayback=!virtualMse&&engine.keepsMediaSession(source);
                        if(!virtualMse) {
                            transport=engine.playbackTransport(source,headers);target=transport.url();
                        }
                        if(canvas!=null)canvas.endTextInput();pendingInput=null;
                        temporary=preroll;streamActive=true;lastPosition=lastDuration=0;
                        if(!preroll){finalStream=true;finalHandoffs++;handler.removeCallbacks(sniffDeadline);}
                        // HLS pages may renew authentication throughout playback.
                        // Keep their DOM/JS and shared cookie jar until channel close,
                        // with rendering disabled while native video is visible.
                        if(!preroll&&!virtualMse&&!liveHlsPlayback)engine.close();
                        else engine.setRendering(false);
                        host.stream(target,preroll);
                        handler.removeCallbacks(progress);if(preroll||virtualMse||liveHlsPlayback)handler.post(progress);
                    } catch(Exception error) { fail("轻量媒体通道创建失败"); }
                }
            },NetworkClient.sharedClient().dns(),host.networkThreadMultiplier());
            if(auditEnabled)engine.traceRequests(true);
            okhttp3.HttpUrl desktopPage=okhttp3.HttpUrl.parse(url);
            if(desktopPage!=null&&desktopPage.host().equals("www.btime.com"))engine.viewport(1280,720,0);
            if(android.os.Build.VERSION.SDK_INT==19&&(LiteEngine.keepsOriginalMediaPipeline(url)
                    ||desktopPage!=null&&desktopPage.host().equals("www.mgtv.com")))engine.viewport(1280,720,0);
            engine.setHeadless(true);
            // Keep catalogue selection code separate from diagnostic bootstrap.
            // The engine's existing ujs path executes it through the normal page flow.
            WebSourceOptions options=WebSourceOptions.parse(url,script);
            host.status("轻量内核正在嗅探网页");engine.load(options.url,options.script);
            handler.postDelayed(renderFallback,500);
        } catch(Exception | LinkageError error) { fail("轻量内核启动失败"); }
    }
    private final Runnable progress=new Runnable() {
        public void run() {
            if(closed||engine==null)return;
            try {
                if(streamActive&&controlPaused!=null&&host.playing()==controlPaused)host.setPaused(controlPaused);
                if(streamActive&&host.playing()) {
                    lastPosition=Math.max(0,host.position())/1000.0;
                    lastDuration=Math.max(0,host.duration())/1000.0;
                    if(temporary)engine.temporaryPlayback(lastPosition,lastDuration,false,false);
                    else if(liveHlsPlayback)engine.directPlaybackTime(controlledSource,lastPosition,host.width(),host.height());
                    else engine.playbackTime(lastPosition,host.width(),host.height());
                }
            } catch(RuntimeException ignored) { }
            handler.postDelayed(this,500);
        }
    };
    boolean finishTemporary(boolean error) {
        if(closed||!temporary||engine==null)return false;
        temporary=false;streamActive=false;engine.temporaryPlayback(lastPosition,lastDuration,!error,error);handler.postDelayed(renderFallback,500);return true;
    }
    private void releaseTransport() {
        final LiteEngine.PlaybackTransport old=transport;transport=null;
        if(old!=null)new Thread(()->{try{old.close();}catch(Exception ignored){ }},"lite-playback-close").start();
    }
    private void releaseEngine() { clearRenderedPage();releaseTransport();if(engine!=null){engine.close();engine=null;} }
    public void close() { if(closed)return;closed=true;++generation;handler.removeCallbacksAndMessages(null);releaseEngine(); }
}
