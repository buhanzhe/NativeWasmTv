package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.WebView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Cast lifecycle regression: page replacement, HOME, input, return and lease expiry. */
public final class BackgroundCastInstrumentation extends Instrumentation {
    private MainActivity activity;
    private Bundle args;
    private String receiver;
    private WebView web;
    private ChannelCatalog.Group[] originalGroups;
    private int originalGroup, originalChannel;
    private Map<String, ?> originalPreferences;
    private final StringBuilder report = new StringBuilder();

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); args=arguments; start(); }
    @Override public void onStart() {
        int code=-1;
        try {
            receiver=args.getString("receiver", "http://192.168.0.113:9966");
            if ("none".equals(receiver)) receiver=""; // adb/Windows drops empty arguments
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(5000);
            originalPreferences=activity.getSharedPreferences(MainActivity.PREFERENCES,0).getAll();
            onMain(() -> {
                activity.stopBackgroundCast("开始后台投送回归测试");
                originalGroups=ChannelCatalog.GROUPS;
                originalGroup=(Integer)field(activity,"currentGroupIndex");
                originalChannel=(Integer)field(activity,"currentChannelIndex");
                ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group(
                        "后台投送测试", ChannelCatalog.SOURCE_CUSTOM, new Channel[]{new Channel(
                        "1","动态网页",null,"webview://"+args.getString("fixture","http://192.168.0.10:18891/"),null,null)})};
                set(activity,"currentGroupIndex",0);set(activity,"currentChannelIndex",0);
                set(activity,"webCastResolution","1280x720");set(activity,"webCastFps",30);
                set(activity,"webCastAudio",false);
                set(activity,"flyMouseEnabled",true);
            });
            if (receiver.length()>0) {
                note("claim="+http("http://127.0.0.1:9966/api/takeover",new JSONObject().put("receiverUrl",receiver)));
            } else {
                // Start without a page: later web channels must inherit casting,
                // even when MainActivity has already been covered by management.
                note("standalone="+http("http://127.0.0.1:9966/api/cast/start",new JSONObject()
                        .put("width",1280).put("height",720).put("fps",30).put("audio",false)));
                onMain(() -> activity.startActivity(new Intent(activity,ManagementActivity.class)
                        .putExtra(ManagementActivity.EXTRA_URL,"http://127.0.0.1:9966/")));
                SystemClock.sleep(1000);
                http("http://127.0.0.1:9966/api/control",new JSONObject()
                        .put("action","play").put("group",0).put("channel",0).put("source",0));
            }
            long limit=SystemClock.elapsedRealtime()+45000;
            while(SystemClock.elapsedRealtime()<limit) {
                if (state().getJSONObject("cast").optLong("encodedVideoFrames")>30) break;
                SystemClock.sleep(500);
            }
            onMain(() -> web=(WebView)field(field(activity,"webSourceView"),"webView"));
            check(web!=null,"WebView not started");
            note("foreground="+sample());
            check(state().getJSONObject("castBackground").getBoolean("active"),"foreground service missing");
            check(!state().getJSONObject("castBackground").getBoolean("background"),"management incorrectly counted as background");
            checkPageRunning(true);

            shell("input keyevent 3");
            SystemClock.sleep(1500);
            JSONObject first=sample();note("HOME="+first);
            check(state().getJSONObject("castBackground").getBoolean("background"),"HOME not detected");
            SystemClock.sleep(20000);
            JSONObject second=sample();note("HOME +20s="+second);
            progressing(first,second);
            checkPointer();
            checkReceiver();
            onMain(() -> activity.startActivity(new Intent(activity,ManagementActivity.class)
                    .putExtra(ManagementActivity.EXTRA_URL,"http://127.0.0.1:9966/")
                    .putExtra(ManagementActivity.EXTRA_TAKEOVER,true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
            SystemClock.sleep(2000);
            check(state().getJSONObject("castBackground").getLong("remainingMs")==-1,"return did not cancel expiry");
            note("RETURN="+sample());
            checkPointer();

            if (receiver.length()==0) {
                http("http://127.0.0.1:9966/api/cast/stop",new JSONObject());
                checkPageRunning(false); // MainActivity is still behind management.
                http("http://127.0.0.1:9966/api/cast/start",new JSONObject()
                        .put("width",1280).put("height",720).put("fps",30).put("audio",false));
                checkPageRunning(true);
                onMain(() -> source().hideForStreamPlayback());
                checkPageRunning(false); // Hidden retained pages must not run.
                onMain(() -> check(source().restoreAfterStreamPlayback(),"restore page failed"));
                checkPageRunning(true);
                note("PASS: stop/restart behind management and retained page pause/restore");
            }

            shell("input keyevent 3");SystemClock.sleep(1000);
            if(receiver.length()==0) {
                long deadline=state().getJSONObject("castBackground").getLong("remainingMs");
                onMain(() -> {
                    source().closePage();
                    checkPageRunning(false);
                    source().open(900002,args.getString("fixture","http://192.168.0.10:18891/")+"?replaced=1");
                    web=(WebView)field(source(),"webView");
                });
                checkPageRunning(true);
                SystemClock.sleep(1500);
                check(state().getJSONObject("castBackground").getLong("remainingMs")<deadline,
                        "background page replacement renewed lease");
                note("PASS: replacement page while HOME inherits cast lifecycle");
            }
            long began=SystemClock.elapsedRealtime();
            JSONObject previous=sample();
            for(int i=1;i<=11;i++) {
                SystemClock.sleep(10000);
                JSONObject next=sample(); note("background +"+(SystemClock.elapsedRealtime()-began)+"ms="+next);
                progressing(previous,next);checkReceiver();previous=next;
                checkPointer();
                if(i==2) shell("input keyevent 223");
                if(i==4) shell("input keyevent 224");
            }
            SystemClock.sleep(14000);
            JSONObject ended=state();note("EXPIRED="+ended.getJSONObject("castBackground")+" cast="+ended.getJSONObject("cast"));
            check(!ended.getJSONObject("cast").optBoolean("running"),"encoder not stopped");
            check(!ended.getJSONObject("castBackground").optBoolean("active"),"service not stopped");
            checkPageRunning(false);
            check(ended.optString("takeoverReceiverUrl").isEmpty(),"host still owns receiver");
            if(receiver.length()>0) {
                JSONObject tv=http(receiver+"/api/state",null);
                check(!tv.optBoolean("takeoverSessionConnected"),"receiver did not exit takeover");
            }
            note("PASS");
        } catch(Throwable error) { code=1;note("FAIL "+Log.getStackTraceString(error)); }
        finally {
            try {
                if(activity!=null) onMain(() -> {
                    activity.stopBackgroundCast("测试结束");
                    if(originalGroups!=null) {
                        ChannelCatalog.GROUPS=originalGroups;
                        set(activity,"currentGroupIndex",originalGroup);
                        set(activity,"currentChannelIndex",originalChannel);
                    }
                    if(originalPreferences!=null) {
                        SharedPreferences.Editor editor=activity.getSharedPreferences(MainActivity.PREFERENCES,0).edit();
                        for(String key:new String[]{"last_group_index_v2","last_channel_index_v2","last_channel_snapshot_v2"}) {
                            Object value=originalPreferences.get(key);
                            if(value instanceof Integer) editor.putInt(key,(Integer)value);
                            else if(value instanceof String) editor.putString(key,(String)value);
                            else editor.remove(key);
                        }
                        editor.commit();
                    }
                });
                shell("input keyevent 224");
            } catch(Throwable error) {note("cleanup="+error);}
        }
        Bundle result=new Bundle();result.putString("stream",report.toString());finish(code,result);
    }
    private void checkReceiver() throws Exception {
        if(receiver.length()==0) return;
        JSONObject tv=http(receiver+"/api/state",null);
        check(tv.optBoolean("takeoverSessionConnected"),"receiver disconnected");
        check(tv.optLong("takeoverSessionSilenceMs")<10000,"heartbeat stale");
    }
    private void progressing(JSONObject a,JSONObject b) {
        check(b.optLong("webFrames")>a.optLong("webFrames")+15,"webpage animation frozen");
        check(b.optLong("encoded")>a.optLong("encoded")+15,"encoder frozen");
        check(b.optInt("viewWidth")==1280 && b.optInt("viewHeight")==720,
                "background page was not laid out: "+b);
    }
    private WebSourceView source() throws Exception {
        return (WebSourceView)field(activity,"webSourceView");
    }
    private void checkPageRunning(boolean expected) throws Exception {
        // Callers already on UI (replacement) cannot nest runOnMainSync.
        Action checkState=() -> check(Boolean.valueOf(expected).equals(
                field(field(source(),"webView"),"pageResumed")),"wrong WebView resumed state: "+expected);
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()) checkState.run();
        else onMain(checkState);
    }
    private void checkPointer() throws Exception {
        int before=js().optInt("clicks");
        http("http://127.0.0.1:9966/api/pointer",new JSONObject().put("action","reset"));
        http("http://127.0.0.1:9966/api/pointer",new JSONObject().put("action","click"));
        SystemClock.sleep(400);
        int after=js().optInt("clicks");
        check(after==before+1,"background pointer click was not delivered: "+sample());
        note("pointer clicks="+after);
    }
    private JSONObject sample() throws Exception {
        JSONObject state=state();JSONObject page=js();
        JSONObject result=new JSONObject().put("webFrames",page.optLong("frames"))
                .put("visibility",page.optString("visibility"))
                .put("encoded",state.getJSONObject("cast").optLong("encodedVideoFrames"))
                .put("lease",state.getJSONObject("castBackground"));
        onMain(() -> result.put("viewWidth",web.getWidth()).put("viewHeight",web.getHeight())
                .put("layoutPending",((android.view.View)field(activity,"root")).isLayoutRequested()));
        return result;
    }
    private JSONObject js() throws Exception {
        CountDownLatch done=new CountDownLatch(1);String[] value={null};
        runOnMainSync(() -> web.evaluateJavascript("JSON.stringify({frames:window.castTest&&castTest.frames,clicks:window.castTest&&castTest.clicks,visibility:document.visibilityState})",s -> {value[0]=s;done.countDown();}));
        check(done.await(5,TimeUnit.SECONDS),"JS timed out");
        return new JSONObject((String)new JSONTokener(value[0]).nextValue());
    }
    private JSONObject state() throws Exception {return http("http://127.0.0.1:9966/api/state",null);}
    private JSONObject http(String url,JSONObject body) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(5000);c.setReadTimeout(40000);
        try {
            if(body!=null) {c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");byte[] bytes=body.toString().getBytes("UTF-8");c.setFixedLengthStreamingMode(bytes.length);c.getOutputStream().write(bytes);c.getOutputStream().close();}
            InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
            while((n=in.read(b))!=-1) out.write(b,0,n);in.close();
            return new JSONObject(out.toString("UTF-8"));
        } finally {c.disconnect();}
    }
    private void shell(String command) throws Exception {getUiAutomation().executeShellCommand(command).close();}
    private interface Action {void run() throws Exception;}
    private void onMain(Action action) throws Exception {
        Exception[] error={null};runOnMainSync(() -> {try{action.run();}catch(Exception e){error[0]=e;}});
        if(error[0]!=null) throw error[0];
    }
    private static Object field(Object object,String name) throws Exception {Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static void set(Object object,String name,Object value) throws Exception {Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    private static void check(boolean ok,String message) {if(!ok) throw new AssertionError(message);}
    private void note(String text) {report.append(text).append('\n');Log.i("BackgroundCastTest",text);Bundle b=new Bundle();b.putString("stream",text+"\n");sendStatus(0,b);}
}
