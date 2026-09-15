package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.webkit.WebView;
import java.lang.reflect.*;
import java.util.concurrent.*;

/** Fixture: python -m http.server 19979 --bind 127.0.0.1 --directory tests/fixtures/trackpad
 *  Device: adb reverse tcp:19979 tcp:19979 (Android 5+).
 */
public final class TrackpadNavigationInstrumentation extends Instrumentation {
    private MainActivity activity;
    private WebSourceView source;
    private WebView web;
    private Object field(Object owner, String name) throws Exception {
        Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);
    }
    private String js(String script) throws Exception {
        CountDownLatch done=new CountDownLatch(1);String[] value={null};
        runOnMainSync(()->web.evaluateJavascript(script,result->{value[0]=result;done.countDown();}));
        if(!done.await(5,TimeUnit.SECONDS))throw new AssertionError("JavaScript timeout");
        return value[0];
    }
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle out=new Bundle();int code=-1;
        try{
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(1800);
            source=(WebSourceView)field(activity,"webSourceView");
            runOnMainSync(()->{source.setListener(null);source.open(90001,"http://127.0.0.1:19979/a.html");});
            web=(WebView)field(source,"webView");
            SystemClock.sleep(1000);
            js("history.pushState({},'', '/b.html');document.getElementById('entry').value='edited'");
            Method dispatch=MainActivity.class.getDeclaredMethod("dispatchWebPointer",org.json.JSONObject.class);dispatch.setAccessible(true);
            Field enabled=MainActivity.class.getDeclaredField("flyMouseEnabled");enabled.setAccessible(true);enabled.setBoolean(activity,true);
            dispatch.invoke(activity,new org.json.JSONObject().put("action","webBack"));SystemClock.sleep(350);
            String back=js("location.pathname");
            check(back.contains("a.html"),"Back button failed: "+back+" visible="+source.isPageVisible()+" retained="+source.hasRetainedPage());
            dispatch.invoke(activity,new org.json.JSONObject().put("action","webForward"));SystemClock.sleep(350);
            check(js("location.pathname").contains("b.html"),"Forward button failed");
            check(js("document.getElementById('entry').value").contains("edited"),"History lost form state");
            float before=(Float)field(source,"currentPageScale");
            for(int i=0;i<12;i++)dispatch.invoke(activity,new org.json.JSONObject().put("action","zoom").put("zoomFactor",1.003));
            SystemClock.sleep(150);
            float after=(Float)field(source,"currentPageScale");
            check(Math.abs(after-before*(float)Math.pow(1.003,12))<.0001,"Small zoom increments were lost");
            check(js("document.querySelector('meta[name=viewport]').content").contains("initial-scale="),"Viewport not updated");
            dispatch.invoke(activity,new org.json.JSONObject().put("action","webForward"));SystemClock.sleep(100);
            check(!activity.isFinishing(),"History boundary closed app");
            js("document.body.innerHTML='<div id=strip style=\"position:fixed;inset:0;top:0;left:0;width:100vw;height:100vh;overflow-x:auto\"><div style=\"width:400vw;height:1px\"></div></div>'");
            dispatch.invoke(activity,new org.json.JSONObject().put("action","webSwipe").put("gestureId","strip").put("scrollX",240));
            SystemClock.sleep(200);
            dispatch.invoke(activity,new org.json.JSONObject().put("action","webSwipe").put("gestureId","strip").put("end",true).put("direction",-1));
            SystemClock.sleep(200);
            check(js("location.pathname").contains("b.html"),"Scrollable strip triggered history");
            check(!"0".equals(js("document.getElementById('strip').scrollLeft")),"Native horizontal wheel did not scroll strip");
            js("document.getElementById('strip').scrollLeft=100000");
            dispatch.invoke(activity,new org.json.JSONObject().put("action","webSwipe").put("gestureId","edge").put("scrollX",240));
            dispatch.invoke(activity,new org.json.JSONObject().put("action","webSwipe").put("gestureId","edge").put("end",true).put("direction",-1));
            SystemClock.sleep(200);
            check(js("location.pathname").contains("b.html"),"Scroll boundary triggered history");
            js("document.body.innerHTML='No horizontal overflow'");
            dispatch.invoke(activity,new org.json.JSONObject().put("action","webSwipe").put("gestureId","plain").put("scrollX",240));
            dispatch.invoke(activity,new org.json.JSONObject().put("action","webSwipe").put("gestureId","plain").put("end",true).put("direction",-1));
            SystemClock.sleep(250);
            check(js("location.pathname").contains("b.html"),"Legacy swipe navigated a non-scrollable page");
            runOnMainSync(()->source.closePage());
            out.putString("stream","PASS Android WebView horizontal scrolling without swipe navigation, explicit history buttons, preserved form and fractional zoom: "+before+" -> "+after+"\n");
        }catch(Throwable error){code=0;out.putString("stream",android.util.Log.getStackTraceString(error));}
        finish(code,out);
    }
}
