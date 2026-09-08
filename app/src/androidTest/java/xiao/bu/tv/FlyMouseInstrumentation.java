package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.webkit.WebView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Real Activity + HTTP pointer endpoint + system WebView; does not synthesize DOM input. */
public final class FlyMouseInstrumentation extends Instrumentation {
    private MainActivity activity;
    private WebSourceView source;
    private WebView web;
    private Bundle args;
    private final StringBuilder report = new StringBuilder();
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); args=arguments; start(); }
    @Override public void onStart() {
        int code=-1;
        try {
            Intent launch=new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity=(MainActivity)startActivitySync(launch);
            SystemClock.sleep(3000);
            onMain(() -> {
                invoke("releasePlayer"); invoke("hideLoading");
                Field f=MainActivity.class.getDeclaredField("flyMouseEnabled");f.setAccessible(true);f.set(activity,true);
                invoke("applyFlyMouseVisibility");
                source=(WebSourceView)field(activity,"webSourceView");
                source.setListener(null); // Do not change the channel catalog or trigger auto-sniff playback.
            });
            if(args.containsKey("zoom")) {
                open("http://127.0.0.1:18889/mouse.html","2k",2f);
                checkFixture("2K desktop zoom 200%");
                screenshot("mouse-2k-200.png");
                note("PASS");
            } else if(args.containsKey("url")) {
                open(args.getString("url"),"720p",1f);
                SystemClock.sleep(12000);
                note("public page="+json("({url:location.href,title:document.title,text:document.body.innerText.slice(0,350),videos:document.querySelectorAll('video').length})"));
                if (args.getString("url").contains("bilibili.com")) checkBilibili();
                else if (args.getString("url").contains("slider-captcha-js")) checkPublicSlider();
                screenshot("mouse-public.png");
            } else {
                for(String resolution:new String[]{"720p","1080p","2k"}) {
                    open("http://127.0.0.1:18889/mouse.html",resolution,1f);
                    checkFixture(resolution);
                }
                // Page zoom and cast viewport use different transforms from output pixels.
                open("http://127.0.0.1:18889/mouse.html","1080p",1.25f);
                checkFixture("1080p zoom 125%");
                onMain(() -> source.setCastCaptureActive(true, 30));
                SystemClock.sleep(800);
                check(!json("({muted:video.muted,volume:video.volume})").getBoolean("muted"),"Cast forcibly muted video");
                check(json("({volume:video.volume})").getDouble("volume")>0,"Cast zeroed volume");
                onMain(() -> source.setCastCaptureActive(false, 30));
                note("cast transition preserves media mute/volume settings");
                checkContinuousInput();
                screenshot("mouse-fixture.png");
                note("PASS");
            }
        } catch(Throwable error) { code=1;note("FAIL "+Log.getStackTraceString(error)); }
        Bundle result=new Bundle(); result.putString("stream",report.toString());finish(code,result);
    }

    private void open(String url,String resolution,float scale) throws Exception {
        onMain(() -> {
            source.applyConfiguration(resolution,true,"windows",scale);
            source.open(900001,url);
            web=(WebView)field(source,"webView");
            invoke("ensureFlyMouseOnTop");
        });
        for(int i=0;i<100;i++) {
            if(json("({ready:document.readyState})").optString("ready").equals("complete")) break;
            SystemClock.sleep(200);
        }
        SystemClock.sleep(1500);
        note("open "+resolution+" scale="+scale+" nativeButtons="+MouseButtonCompat.supported());
    }

    private void checkFixture(String label) throws Exception {
        check(json("({ready:!!window.mouseTest})").getBoolean("ready"),"fixture not loaded");
        moveTo("hover",0.4,0.5);
        check(json("({hover:document.querySelector('#hover:hover')!==null})").getBoolean("hover"),"hover not delivered");
        moveTo("hoverButton",0.5,0.5); action("click"); SystemClock.sleep(200);
        JSONObject state=json("({clicks:mouseTest.hoverClicks,down:mouseTest.events.filter(function(e){return e.type==='pointerdown'}),up:mouseTest.events.filter(function(e){return e.type==='pointerup'})})");
        note(label+" hover/click="+state);
        check(state.getInt("clicks")==1,"click missing/duplicated");
        check(state.getJSONArray("down").length()==1,"duplicate pointerdown");
        check(state.getJSONArray("down").getJSONObject(0).getString("pointerType").equals("mouse"),"not mouse pointer");
        check(state.getJSONArray("up").getJSONObject(0).getInt("buttons")==0,"mouse button stuck");
        moveTo("range",0.02,0.5); action("down");
        dragTo("range",0.8,0.5,24); action("up"); SystemClock.sleep(200);
        check(json("({v:+document.getElementById('range').value})").getInt("v")>70,"range drag failed");
        moveTo("handle",0.5,0.5); action("down");
        dragTo("slider",0.8,0.5,24); action("up"); SystemClock.sleep(200);
        JSONObject dragged=json("({drag:mouseTest.drag,ups:mouseTest.dragUps})");
        check(dragged.getDouble("drag")>350&&dragged.getInt("ups")==1,"mouse-only slider drag failed "+dragged);
        moveTo("pane",0.5,0.5);
        send(new JSONObject().put("action","scroll").put("scrollY",180)); SystemClock.sleep(600);
        check(json("({top:document.getElementById('pane').scrollTop})").getInt("top")>0,"nested wheel failed");
        moveTo("player",0.5,0.6); moveTo("play",0.5,0.5); action("click"); SystemClock.sleep(1200);
        JSONObject media=json("({time:video.currentTime,paused:video.paused,muted:video.muted,volume:video.volume})");
        note(label+" media="+media);
        check(!media.getBoolean("paused")&&media.getDouble("time")>0,"video play click failed");
        check(!media.getBoolean("muted")&&media.getDouble("volume")>0,"video started muted");
        moveTo("seek",0.1,0.5);action("down");dragTo("seek",0.55,0.5,12);action("up");SystemClock.sleep(700);
        check(json("({time:video.currentTime})").getDouble("time")>40,"video seek failed");
        moveTo("full",0.5,0.5);action("click");SystemClock.sleep(700);
        check(field(source,"fullscreenView")!=null,"fullscreen callback not installed/click failed");
        action("back");SystemClock.sleep(400);
        check(field(source,"fullscreenView")==null,"back did not close fullscreen");
        check(source.isPageVisible(),"back closed webpage instead of fullscreen");
        moveTo("handle",0.5,0.5);action("down");dragTo("slider",0.6,0.5,6);action("cancel");SystemClock.sleep(120);
        double before=json("({v:mouseTest.drag})").getDouble("v");
        moveTo("slider",0.3,0.5);
        check(Math.abs(json("({v:mouseTest.drag})").getDouble("v")-before)<2,"cancel left DOM mouse button held");
        moveTo("hover",0.4,0.5);moveTo("hoverButton",0.5,0.5);action("down");action("cancel");SystemClock.sleep(120);
        check(json("({clicks:mouseTest.hoverClicks})").getInt("clicks")==1,"cancel incorrectly clicked control");
        note(label+" PASS range="+json("({value:document.getElementById('range').value})")+" drag="+dragged+" wheel/seek/fullscreen/back OK");
    }

    private void checkBilibili() throws Exception {
        JSONObject link=json("(function(){var a=document.querySelector('a[href*=\"/video/BV\"]');return {url:a?a.href:''}})()");
        if(!args.getString("url").contains("/video/") && !link.optString("url").isEmpty()) {
            note("selected public video="+link.getString("url"));open(link.getString("url"),"720p",1f);SystemClock.sleep(12000);
        }
        for(int i=0;i<60;i++) {
            if(json("({ready:!!document.querySelector('.bpx-player-ctrl-play')&&!!document.querySelector('video')&&document.querySelector('video').readyState>=2})").getBoolean("ready")) break;
            SystemClock.sleep(500);
        }
        note("bili initial="+json("({url:location.href,title:document.title,text:document.body.innerText.slice(0,600),media:[].map.call(document.querySelectorAll('video'),function(v){return {paused:v.paused,muted:v.muted,volume:v.volume,time:v.currentTime,duration:v.duration}}),controls:[].map.call(document.querySelectorAll('[class*=bpx-player-ctrl]'),function(e){return e.className}).slice(0,40)})"));
        tag("video","biliVideo"); tag(".bpx-player-ctrl-play","biliPlay");
        moveTo("biliVideo",0.5,0.5);moveTo("biliPlay",0.5,0.5);
        if(!json("({paused:document.querySelector('video').paused})").getBoolean("paused"))action("click");
        SystemClock.sleep(300);
        check(json("({paused:document.querySelector('video').paused})").getBoolean("paused"),"Bili pause button failed");
        tag(".bpx-player-ctrl-full","biliFull");
        moveTo("biliFull",0.5,0.5);action("click");SystemClock.sleep(600);
        check(field(source,"fullscreenView")!=null,"Bili fullscreen click failed");
        screenshot("mouse-bili-fullscreen.png");
        action("back");SystemClock.sleep(500);
        note("Bili hover/pause/fullscreen/back PASS");
        note("Bili progress DOM="+json("({controls:[].map.call(document.querySelectorAll('[class*=bpx-player-progress]'),function(e){var r=e.getBoundingClientRect();return {cls:e.className,width:r.width,height:r.height}}),muted:document.querySelector('video').muted})"));
        tag(".bpx-player-progress-schedule-wrap","biliSeek");moveTo("biliSeek",0.01,0.5);
        action("down");dragTo("biliSeek",0.025,0.5,8);action("up");SystemClock.sleep(500);
        double time=json("({time:document.querySelector('video').currentTime})").getDouble("time");
        check(time>15&&time<30,"Bili seek failed (test stays within site's free preview): "+time);
        note("Bili progress drag PASS position="+time);
        check(!json("({muted:document.querySelector('video').muted})").getBoolean("muted"),"Bili starts muted");
    }

    private void checkContinuousInput() throws Exception {
        moveTo("range",0.4,0.5);action("down");
        long begin=SystemClock.uptimeMillis();
        for(int i=0;i<1100;i++) {
            send(new JSONObject().put("action","move").put("dx",i%80<40?0.5:-0.5).put("dy",0));
            SystemClock.sleep(16);
        }
        check(Boolean.TRUE.equals(field(activity,"flyMouseButtonDown")),"active drag cancelled by absolute 15-second timeout");
        action("up");SystemClock.sleep(100);
        note("continuous drag: 1100 HTTP moves in "+(SystemClock.uptimeMillis()-begin)+"ms; held state and release OK");
    }

    private void checkPublicSlider() throws Exception {
        note("slider DOM="+json("({buttons:[].map.call(document.querySelectorAll('button,[class*=slider],[class*=captcha]'),function(e){return {id:e.id,cls:e.className,text:e.textContent.slice(0,80)}}).slice(0,30)})"));
        tag(".slider-captcha-thumb","publicThumb");tag(".slider-captcha-track","publicTrack");
        double before=json("({x:document.getElementById('publicThumb').getBoundingClientRect().left})").getDouble("x");
        moveTo("publicThumb",0.5,0.5);action("down");dragTo("publicTrack",0.35,0.5,20);
        double after=json("({x:document.getElementById('publicThumb').getBoundingClientRect().left})").getDouble("x");
        screenshot("mouse-slider-drag.png");action("up");
        check(after-before>40,"Public slider did not follow mouse");
        note("Public slider input PASS displacement="+(after-before)+"px; only tested dragging, not challenge solving or bot detection");
    }

    private void tag(String selector,String id) throws Exception {
        check(json("(function(){var e=document.querySelector("+JSONObject.quote(selector)+");if(e)e.id="+JSONObject.quote(id)+";return {found:!!e}})()").getBoolean("found"),"Missing public control "+selector);
    }

    private float[] target(String id,double x,double y) throws Exception {
        JSONObject rect=json("(function(){var r=document.getElementById("+JSONObject.quote(id)+").getBoundingClientRect();return {x:r.left+r.width*"+x+",y:r.top+r.height*"+y+"}})()");
        final float[] result=new float[2];
        onMain(() -> {
            int[] origin=new int[2],base=new int[2];web.getLocationOnScreen(origin);((View)field(activity,"root")).getLocationOnScreen(base);
            result[0]=origin[0]-base[0]+(float)rect.getDouble("x")*web.getScale()*web.getScaleX();
            result[1]=origin[1]-base[1]+(float)rect.getDouble("y")*web.getScale()*web.getScaleY();
        }); return result;
    }
    private void moveTo(String id,double x,double y) throws Exception { move(target(id,x,y),0);SystemClock.sleep(120); }
    private void dragTo(String id,double x,double y,int steps) throws Exception { move(target(id,x,y),steps); }
    private void move(float[] dest,int steps) throws Exception {
        final float[] from=new float[2];
        onMain(() -> { FlyMouseCursorView c=(FlyMouseCursorView)field(activity,"flyMouseCursor");from[0]=c.cursorX();from[1]=c.cursorY(); });
        float dx=dest[0]-from[0],dy=dest[1]-from[1];
        int count=Math.max(steps,Math.max(1,(int)Math.ceil(Math.max(Math.abs(dx),Math.abs(dy))/180f)));
        for(int i=0;i<count;i++){send(new JSONObject().put("action","move").put("dx",dx/count).put("dy",dy/count));SystemClock.sleep(18);}
        SystemClock.sleep(50);
    }
    private void action(String name) throws Exception { send(new JSONObject().put("action",name)); }
    private void send(JSONObject body) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:9966/api/pointer").openConnection();
        c.setConnectTimeout(2500);c.setReadTimeout(2500);c.setRequestMethod("POST");c.setDoOutput(true);
        c.setRequestProperty("Content-Type","application/json");byte[] b=body.toString().getBytes("UTF-8");c.setFixedLengthStreamingMode(b.length);
        try {c.getOutputStream().write(b);c.getOutputStream().close();check(c.getResponseCode()==200,"HTTP pointer failed "+body);c.getInputStream().close();}
        finally{c.disconnect();}
    }
    private JSONObject json(String expression) throws Exception {
        final String[] result={null};CountDownLatch latch=new CountDownLatch(1);
        onMain(() -> web.evaluateJavascript("JSON.stringify("+expression+")",value->{result[0]=value;latch.countDown();}));
        check(latch.await(5,TimeUnit.SECONDS),"WebView JS evaluation timeout");
        Object value=new JSONTokener(result[0]).nextValue();return new JSONObject(String.valueOf(value));
    }
    private void screenshot(String name) throws Exception {
        android.graphics.Bitmap b=getUiAutomation().takeScreenshot();
        if(b!=null){java.io.File f=new java.io.File(getTargetContext().getExternalFilesDir(null),name);java.io.FileOutputStream out=new java.io.FileOutputStream(f);try{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{out.close();b.recycle();}note("screenshot="+f);}
    }
    private static Object field(Object object,String name) throws Exception {Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private void invoke(String name) throws Exception {Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(activity);}
    private interface Work{void run() throws Exception;}
    private void onMain(Work work) throws Exception {final Exception[] error={null};runOnMainSync(()->{try{work.run();}catch(Exception e){error[0]=e;}});if(error[0]!=null)throw error[0];}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private void note(String value){report.append(value).append('\n');Log.i("nTvMouseTest",value);}
}
