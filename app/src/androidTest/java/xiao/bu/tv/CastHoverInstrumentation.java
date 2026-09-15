package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Compare native hover delivery before/after cast, including a background Activity. */
public final class CastHoverInstrumentation extends Instrumentation {
    private MainActivity activity;
    private WebSourceView source;
    private WebView web;
    private String receiver;
    private boolean localOnly;
    private ChannelCatalog.Group[] savedGroups;
    private int savedGroup, savedChannel, savedSource;
    private Object savedAudio, savedResolution, savedFps, savedCodec, savedAutoPlay, savedFlyMouse;
    private final StringBuilder report = new StringBuilder();
    private Object field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = MainActivity.class.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(activity, args);
    }
    private void set(String name, Object value) throws Exception {
        Field f = MainActivity.class.getDeclaredField(name); f.setAccessible(true); f.set(activity, value);
    }
    private void log(String message) { report.append(message).append('\n'); android.util.Log.i("CastHover",message); }
    private void main(Runnable runnable) { runOnMainSync(runnable); }
    private String js(String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1); String[] result = {null};
        main(() -> web.evaluateJavascript(script, value -> { result[0] = value; done.countDown(); }));
        if (!done.await(8, TimeUnit.SECONDS)) throw new AssertionError("JS timeout");
        return result[0];
    }
    private void move(float x, float y, int action) {
        main(() -> {
            try {
                FlyMouseCursorView cursor = (FlyMouseCursorView) field(activity, "flyMouseCursor");
                cursor.setVisibility(View.VISIBLE);
                cursor.moveBy(cursor.getWidth() * x - cursor.cursorX(), cursor.getHeight() * y - cursor.cursorY());
                call("dispatchFlyMouseMotionEvent", new Class<?>[]{int.class,long.class}, action, SystemClock.uptimeMillis());
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }
    private void probe(String stage) throws Exception {
        js("window.hoverProbe={moves:0,over:0,x:0,y:0};if(!window.hoverProbeInstalled){window.hoverProbeInstalled=true;document.addEventListener('mousemove',function(e){hoverProbe.moves++;hoverProbe.x=e.clientX;hoverProbe.y=e.clientY;},true);document.addEventListener('mouseover',function(){hoverProbe.over++;},true)}");
        for (int i = 0; i < 12; i++) {
            move(.15f + i * .04f, .4f, MotionEvent.ACTION_HOVER_MOVE);
            SystemClock.sleep(60);
        }
        SystemClock.sleep(400);
        log(stage + " " + js("JSON.stringify({probe:hoverProbe,hover:Array.from(document.querySelectorAll(':hover')).slice(-4).map(e=>e.tagName+'.'+e.className),videos:document.querySelectorAll('video').length})"));
        int moves = Integer.parseInt(js("hoverProbe.moves"));
        // Chromium may coalesce mouse moves while a live page is busy.
        if (moves == 0) throw new AssertionError(stage + " swallowed all mouse moves");
        if (!"true".equals(js("document.elementFromPoint(hoverProbe.x,hoverProbe.y).matches(':hover')"))) throw new AssertionError(stage + " hover target differs from pointer position");
        if (!"true".equals(js("document.querySelectorAll(':hover').length>0"))) throw new AssertionError(stage + " missing CSS hover");
        move(.59f, .4f, MotionEvent.ACTION_HOVER_EXIT);
        SystemClock.sleep(400);
        if (!"0".equals(js("document.querySelectorAll(':hover').length"))) throw new AssertionError(stage + " stale hover after exit");
        move(.3f, .4f, MotionEvent.ACTION_HOVER_MOVE);
        SystemClock.sleep(400);
        if (!"true".equals(js("document.querySelectorAll(':hover').length>0"))) throw new AssertionError(stage + " hover did not recover");
        log(stage + " PASS move / CSS hover / exit / re-enter");
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); receiver=args.getString("receiver", ""); localOnly="true".equals(args.getString("local")); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int status = -1;
        try {
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(5500);
            source = (WebSourceView) field(activity, "webSourceView");
            savedGroups=ChannelCatalog.GROUPS;savedGroup=(Integer)field(activity,"currentGroupIndex");savedChannel=(Integer)field(activity,"currentChannelIndex");savedSource=(Integer)field(activity,"currentSourceIndex");
            savedAudio=field(activity,"webCastAudio");savedResolution=field(activity,"webCastResolution");savedFps=field(activity,"webCastFps");savedCodec=field(activity,"webCastCodec");savedAutoPlay=field(activity,"webViewAutoPlaySniffed");savedFlyMouse=field(activity,"flyMouseEnabled");
            main(() -> {
                try {
                    set("webCastAudio", false);
                    set("webCastResolution", "1920x1080");
                    set("webCastFps", 30);
                    set("webCastCodec", "h264");
                    set("webViewAutoPlaySniffed", false);
                    set("flyMouseEnabled", true);
                    ChannelCatalog.setCustomGroups(new ChannelCatalog.Group[]{new ChannelCatalog.Group("Hover test",0,new Channel[]{
                        new Channel("1","Bili","","webview://https://www.bilibili.com/video/BV1GJ411x7h7/",null,null),
                        new Channel("2","YSP","","webview://https://www.yangshipin.cn/",null,null)})});
                    set("currentGroupIndex", 1);
                    set("currentChannelIndex", 0);
                    call("switchChannel",new Class<?>[]{int.class,int.class},0,0);
                    call("closeChannelList",new Class<?>[0]);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            SystemClock.sleep(11000);
            web = (WebView) field(source, "webView");
            probe("Bili-local");
            if (!localOnly) {
                WebView originalWeb = web;
                String originalPage = js("location.href");
                if (receiver.length()>0) {
                    log("takeover="+call("handleWebTakeover",new Class<?>[]{org.json.JSONObject.class},new org.json.JSONObject().put("receiverUrl",receiver)));
                } else {
                    call("startWebViewCast", new Class<?>[]{CastConfig.class,android.media.projection.MediaProjection.class},
                        new CastConfig("",1920,1080,30,4000000,false), null);
                    main(() -> { try {activity.startActivity(new Intent(activity,ManagementActivity.class).putExtra(ManagementActivity.EXTRA_URL,((LocalControlServer)field(activity,"controlServer")).getLoopbackUrl()).putExtra(ManagementActivity.EXTRA_TAKEOVER,true));}catch(Exception e){throw new RuntimeException(e);} });
                }
                SystemClock.sleep(10000);
                web = (WebView) field(source, "webView");
                if(web != originalWeb || !originalPage.equals(js("location.href"))) throw new AssertionError("Casting replaced the webpage");
                probe("Bili-cast-direct");
                log("READY touch Bili");
                for(int sample=0;sample<6;sample++){SystemClock.sleep(5000);log("TOUCH sample="+js("JSON.stringify({probe:hoverProbe,hover:Array.from(document.querySelectorAll(':hover')).slice(-3).map(e=>e.tagName+'.'+e.className)})"));}
                log("afterTouch Bili="+js("JSON.stringify({probe:hoverProbe,hover:Array.from(document.querySelectorAll(':hover')).map(e=>e.tagName+'.'+e.className),video:Array.from(document.querySelectorAll('video')).map(e=>({rect:e.getBoundingClientRect().toJSON(),paused:e.paused,time:e.currentTime}))})"));
                main(() -> web.loadUrl("https://www.yangshipin.cn/tv/home?pid=600001859"));
                SystemClock.sleep(12000);
                web = (WebView) field(source, "webView");
                probe("YSP-cast-direct");
                log("READY touch YSP");
                for(int sample=0;sample<6;sample++){SystemClock.sleep(5000);log("TOUCH sample="+js("JSON.stringify({probe:hoverProbe,hover:Array.from(document.querySelectorAll(':hover')).slice(-3).map(e=>e.tagName+'.'+e.className)})"));}
                log("afterTouch YSP="+js("JSON.stringify({probe:hoverProbe,hover:Array.from(document.querySelectorAll(':hover')).map(e=>e.tagName+'.'+e.className),video:Array.from(document.querySelectorAll('video')).map(e=>({rect:e.getBoundingClientRect().toJSON(),paused:e.paused,time:e.currentTime}))})"));
                log("PASS retained webpage / local hover / cast hover on both sites");
            }
        } catch (Throwable error) { status = 0; report.append(android.util.Log.getStackTraceString(error)); }
        finally {
            if (activity != null) main(() -> { try {
                call("exitReceiverTakeover",new Class<?>[]{boolean.class},false);
                ((WebViewCastManager) field(activity,"webViewCastManager")).stop();
                call("endCastUiDrawing",new Class<?>[0]);
                if(savedGroups!=null){ChannelCatalog.GROUPS=savedGroups;set("currentGroupIndex",savedGroup);set("webCastAudio",savedAudio);set("webCastResolution",savedResolution);set("webCastFps",savedFps);set("webCastCodec",savedCodec);set("webViewAutoPlaySniffed",savedAutoPlay);set("flyMouseEnabled",savedFlyMouse);call("switchChannel",new Class<?>[]{int.class,int.class},savedChannel,savedSource);}
                ManagementActivity.closeAll();
            } catch (Exception ignored) {} });
        }
        result.putString("stream",report.toString()); finish(status,result);
    }
}
