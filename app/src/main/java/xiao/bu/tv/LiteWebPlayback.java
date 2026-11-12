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
        default void stream(String url,boolean temporary,boolean localMse){stream(url,temporary);}
        default void prepared() { }
        void status(String value);
        void failure(String value);
        default void render(android.view.View page) { }
        default boolean loadImages(){return true;}
        default int networkThreadMultiplier() { return 0; }
        default String userAgent(String page,String fallback) { return ""; }
        boolean playing();
        default Object playerIdentity(){return null;}
        long position();
        long duration();
        int width();
        int height();
        void setPaused(boolean paused);
    }
    private final Context context;
    private Host host;
    private boolean preparing;
    private String preparedStream,profilePage="";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LiteEngine engine;
    private LiteEngine.PlaybackTransport transport;
    private int generation, navigations;
    private boolean closed, temporary, streamActive, finalStream;
    private int finalHandoffs,dependencyRetries;
    private static final long SNIFF_TIMEOUT_MS = 30000L;
    private boolean sniffDeadlineStarted;
    private long sniffStartedAt;
    private final Runnable sniffDeadline = () -> {
        if (!closed && !finalStream&&!continueStartup()) fail("网页嗅探超时，未找到可播放资源");
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
    private final org.json.JSONArray auditControls=new org.json.JSONArray();
    private double lastPosition, lastDuration;
    private Object staleNativePlayer;
    private android.widget.FrameLayout renderedPage;
    private LiteCanvas canvas;
    private WebPageCover cover;
    private boolean pageCovered,active=true;
    private JSONObject pendingInput;
    void setActive(boolean value){active=value;if(engine!=null&&renderedPage!=null)engine.setRendering(value);}
    private String coverStyle="status";
    boolean isRendering(){return renderedPage!=null;}
    boolean isVirtualMseSource(String source){return !closed&&streamActive&&virtualPlayback&&controlledSource.equals(source);}
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
        LiteWebStartupPolicy.Decision decision=LiteWebStartupPolicy.render(sniffStartedAt,clock(),engine.startupState(),pendingInput!=null);
        if(decision==LiteWebStartupPolicy.Decision.WAIT){handler.postDelayed(this,500);return;}
        renderOriginalPage();
    }};
    private void renderOriginalPage(){
        if(closed||finalStream||engine==null||renderedPage!=null)return;
        renderedPage=new android.widget.FrameLayout(context);
        canvas=new LiteCanvas(context);canvas.engine=engine;
        canvas.fixedViewportWidth=engine.viewportWidth();canvas.fixedViewportHeight=engine.viewportHeight();
        renderedPage.addView(canvas,new android.widget.FrameLayout.LayoutParams(-1,-1));
        applyPageCover(pageCovered,coverStyle);
        // An interactive login page can legitimately take longer than a media
        // sniff deadline. Keep the original page alive while the user signs in.
        // Give an original HTML error/challenge its normal rendering and scripts,
        // but do not turn an unrecovered 4xx/5xx page into an endless loading slot.
        if(engine.startupTimings().optInt("documentStatus",0)<400)
            handler.removeCallbacks(sniffDeadline);
        engine.renderPage();host.render(renderedPage);
        if(pendingInput!=null)canvas.textInput(pendingInput);
    }
    private long clock(){return android.os.SystemClock.elapsedRealtime();}
    private boolean continueStartup(){
        LiteWebStartupPolicy.Decision decision=LiteWebStartupPolicy.deadline(sniffStartedAt,clock(),engine==null?null:engine.startupState(),preparing);
        if(decision==LiteWebStartupPolicy.Decision.WAIT){handler.postDelayed(sniffDeadline,Math.max(1,Math.min(1000,LiteWebStartupPolicy.HARD-(clock()-sniffStartedAt))));return true;}
        if(decision==LiteWebStartupPolicy.Decision.RENDER){renderOriginalPage();return renderedPage!=null;}
        return false;
    }
    static long foregroundSniffTimeout(int sdk,String url){
        return SNIFF_TIMEOUT_MS;
    }
    private long foregroundSniffTimeout(String url){return foregroundSniffTimeout(android.os.Build.VERSION.SDK_INT,url);}
    LiteWebPlayback(Context context, Host host) { this.context=context; this.host=host; }
    void prepare(String url,String script){preparing=true;open(url,script);}
    boolean prepared(){return preparing&&!closed&&finalStream&&preparedStream!=null;}
    boolean closed(){return closed;}
    boolean adopt(Host foreground){
        if(!prepared())return false;
        preparing=false;host=foreground;SharedWebProfile.beginLite(context,profilePage);
        if(engine!=null){host.userAgent(profilePage,engine.userAgent());engine.promotePreparse();}
        String stream=preparedStream;preparedStream=null;
        host.stream(stream,false);
        if(liveHlsPlayback)handler.post(progress);
        return true;
    }
    static boolean supported() {
        return BuildConfig.BUNDLED_LITE_BROWSER;
    }
    JSONObject startupTimings(){return engine==null?new JSONObject():engine.startupTimings();}
    int finalHandoffs(){return finalHandoffs;}
    long upstreamDownloadedBytes(){return closed||engine==null?-1L:engine.upstreamDownloadedBytes();}
    void enableAudit(){auditEnabled=true;if(engine!=null){engine.traceRequests(true);long now=android.os.SystemClock.elapsedRealtime();if(now-auditPoll>=10000){auditPoll=now;engine.evaluate("(function(){var d=__diagnostics();console.log('NTV_AUDIT '+JSON.stringify({userscripts:Object.keys(window.__ntvUserScripts||{}).length,mgtv:typeof __mgtvAudit==='function'?__mgtvAudit():null,ready:d.readyState,runtime:d.runtime,wasm:d.wasm,parser:d.parser,timers:d.timers,pending:d.pendingHTTP,controls:d.playControls,media:document.querySelectorAll('video,audio').slice(0,4).map(function(v){return {ready:v.readyState,paused:v.paused,time:v.currentTime,hasSource:!!v.src}})}))})()");}}}
    JSONObject auditSnapshot(){
        JSONObject value=startupTimings();try{synchronized(auditMessages){value.put("messages",new org.json.JSONArray(auditMessages)).put("page",auditPage);}
            if(engine!=null)value.put("busy",engine.runtimeBusySnapshot());
            value.put("controls",auditControls).put("controlPaused",controlPaused==null?JSONObject.NULL:controlPaused).put("virtualPlayback",virtualPlayback).put("liveHlsPlayback",liveHlsPlayback);
        }catch(Exception ignored){}return value;
    }
    void open(String url, String script) {
        if (closed || finalStream) return;
        profilePage=url;
        if (++navigations > 12) { fail("网页跳转次数过多"); return; }
        if (!sniffDeadlineStarted) {
            sniffDeadlineStarted = true;
            sniffStartedAt=clock();
            handler.postDelayed(sniffDeadline, preparing?12000L:foregroundSniffTimeout(url));
        }
        final int epoch=++generation;
        handler.removeCallbacks(renderFallback);
        handler.removeCallbacks(progress);
        streamActive=temporary=false;lastPosition=lastDuration=0;
        controlPaused=null;virtualPlayback=liveHlsPlayback=false;controlledSource="";
        releaseEngine();
        if(!preparing)SharedWebProfile.beginLite(context,url);
        try {
            LiteEngine.Listener listener=new LiteEngine.Listener() {
                private boolean current() { return !closed && epoch==generation; }
                public void frame(JSONObject packet) { if(current()&&canvas!=null)canvas.frame(packet); }
                public void textInput(JSONObject state){if(current()){pendingInput=state;if(canvas!=null)canvas.textInput(state);}}
                public void message(String value) {
                    if(auditEnabled&&value.startsWith("NTV_AUDIT "))try{synchronized(auditMessages){auditPage=new JSONObject(value.substring(10));}}catch(Exception ignored){}
                    if(auditEnabled&&(value.startsWith("JS：")||value.startsWith("Promise rejection: "))){
                        java.util.regex.Matcher kind=java.util.regex.Pattern.compile("(?:Type|Reference|Range|Syntax|Internal|Eval|URI)?Error").matcher(value);
                        synchronized(auditMessages){if(auditMessages.size()>=96)auditMessages.removeFirst();String detail=value.split("\\r?\\n",2)[0].replaceAll("https?://[^\\s]+","[URL]").replaceAll("[A-Za-z0-9_=-]{64,}","[opaque]");auditMessages.addLast("JS exception: "+(kind.find()?kind.group():"unclassified")+" "+detail.substring(0,Math.min(detail.length(),240)));}
                    }
                    if(auditEnabled&&(value.startsWith("站点直播回退：")||value.startsWith("WebSocket metadata ")||value.startsWith("Document clock metadata ")||value.startsWith("HTTP ")||value.startsWith("API metadata ")||value.startsWith("CORS rejected metadata ")||value.startsWith("SCRIPT ")||value.startsWith("MSE ")||value.startsWith("候选校验")||value.startsWith("捕获候选")||value.startsWith("观察播放器")||value.startsWith("播放器实例")||value.startsWith("Original player API status ")||value.startsWith("虚拟 MSE")||value.startsWith("运行错误：")||value.startsWith("加载失败："))){
                        String safe=value.replaceAll("https?://[^\\s]+","[URL]");
                        synchronized(auditMessages){if(auditMessages.size()>=96)auditMessages.removeFirst();auditMessages.addLast(safe.substring(0,Math.min(safe.length(),800)));}
                    }
                    if(current()&&value.startsWith("UserScript ")){
                        String safe=value.replaceAll("https?://[^\\s]+","[URL]");
                        synchronized(auditMessages){if(auditMessages.size()>=96)auditMessages.removeFirst();auditMessages.addLast(safe.substring(0,Math.min(safe.length(),800)));}
                    }
                    if (!current()) return;
                    if(LiteWebStartupPolicy.retryDependency(value,dependencyRetries,engine==null?null:engine.startupState(),preparing,finalStream||streamActive,pendingInput!=null)){
                        dependencyRetries++;
                        AppLog.i("LiteWebPlayback","Retrying initial page after dependency loader timeout");
                        handler.postDelayed(()->{if(current()&&!closed&&!finalStream&&!streamActive&&pendingInput==null){clearRenderedPage();open(url,script);}},100);
                        return;
                    }
                    if(preparing&&value.contains("预解析不支持：")){fail(value);return;}
                    if(finalStream)return;
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
                    if(!current()||!streamActive||virtualMse!=virtualPlayback||virtualMse&&!source.equals(controlledSource))return;
                    if(!virtualMse&&!source.equals(controlledSource)){
                        if(!liveHlsPlayback||!LiteEngine.sameHlsSessionSource(source,controlledSource))return;
                        controlledSource=source;
                    }
                    if(auditControls.length()<32)try{auditControls.put(new JSONObject().put("atMs",android.os.SystemClock.elapsedRealtime()).put("paused",paused).put("virtualMse",virtualMse).put("nativePlaying",host.playing()));}catch(Exception ignored){}
                    controlPaused=paused;host.setPaused(paused);
                }
                private void deliver(String source,Map<String,String> headers,boolean preroll) {
                    boolean nextMse=headers!=null&&"1".equals(headers.get("X-NTV-Virtual-MSE"));
                    boolean replacement=finalStream&&virtualPlayback&&nextMse&&!source.equals(controlledSource);
                    if(!current()||finalStream&&!replacement)return;
                    if(preparing&&(preroll||headers!=null&&"1".equals(headers.get("X-NTV-Virtual-MSE")))){fail("此媒体需前台网页会话");return;}
                    try {
                        clearRenderedPage();handler.removeCallbacks(renderFallback);
                        releaseTransport();
                        String target=source;
                        // Preserve all validated upstream headers, cookies and the
                        // engine's TLS policy inside its own bounded HTTP relay.
                        boolean virtualMse=nextMse;
                        if(replacement)staleNativePlayer=host.playerIdentity();
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
                        if(preparing){engine.parkPreparse();preparedStream=target;host.prepared();}else host.stream(target,preroll,virtualMse);
                        handler.removeCallbacks(progress);if(!preparing&&(preroll||virtualMse||liveHlsPlayback))handler.post(progress);
                    } catch(Exception error) { fail("轻量媒体通道创建失败"); }
                }
            };
            engine=preparing?LiteEngine.forPreparse(context,listener,NetworkClient.sharedClient().dns(),host.networkThreadMultiplier())
                :LiteEngine.withNetworkThreadMultiplier(context,listener,NetworkClient.sharedClient().dns(),host.networkThreadMultiplier());
            if(preparing)engine.setAuditMemoryMiB(64);
            if(auditEnabled)engine.traceRequests(true);
            okhttp3.HttpUrl desktopPage=okhttp3.HttpUrl.parse(url);
            if(desktopPage!=null&&desktopPage.host().equals("www.btime.com"))engine.viewport(1280,720,0);
            if(android.os.Build.VERSION.SDK_INT==19&&(LiteEngine.keepsOriginalMediaPipeline(url)
                    ||desktopPage!=null&&desktopPage.host().equals("www.mgtv.com")))engine.viewport(1280,720,0);
            engine.setHeadless(true);engine.loadImages(host.loadImages());
            engine.userAgentOverride(host.userAgent(url,LiteEngine.defaultUserAgent(url)));
            // Keep catalogue selection code separate from diagnostic bootstrap.
            // The engine's existing ujs path executes it through the normal page flow.
            WebSourceOptions options=WebSourceOptions.parse(url,script);
            host.status("轻量内核正在加载网页");engine.load(options.url,options.script);
            if(!preparing)handler.postDelayed(renderFallback,500);
        } catch(Exception | LinkageError error) {
            AppLog.w("LiteWebPlayback","Unable to start selected web engine",error);
            fail("轻量内核启动失败");
        }
    }
    private final Runnable progress=new Runnable() {
        public void run() {
            if(closed||engine==null)return;
            try {
                if(streamActive&&host.playing()&&(staleNativePlayer==null||host.playerIdentity()!=staleNativePlayer)) {
                    staleNativePlayer=null;
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
