package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.webkit.WebView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** A live Chromium document, including unsaved DOM state, must survive cast shutdown. */
public final class CastResumeInstrumentation extends Instrumentation {
    private MainActivity activity;
    private Throwable failure;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private Object get(String name) throws Exception {
        Field f=MainActivity.class.getDeclaredField(name); f.setAccessible(true);return f.get(activity);
    }
    private void set(String name,Object value) throws Exception {
        Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);
    }
    private Object call(String name,Class<?>[] types,Object... args) throws Exception {
        Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args);
    }
    private interface Action {void run()throws Exception;}
    private void main(Action action)throws Exception {
        failure=null;runOnMainSync(()->{try{action.run();}catch(Throwable e){failure=e;}});
        if(failure!=null)throw new Exception(failure);
    }
    private WebView browser(WebSourceView source) throws Exception {
        Field f=WebSourceView.class.getDeclaredField("webView");f.setAccessible(true);return (WebView)f.get(source);
    }
    private String js(WebView view,String script)throws Exception {
        CountDownLatch done=new CountDownLatch(1);String[] result={null};
        main(()->view.evaluateJavascript(script,value->{result[0]=value;done.countDown();}));
        if(!done.await(5,TimeUnit.SECONDS))throw new AssertionError("JS callback timed out");
        return result[0];
    }
    @Override public void onStart(){Bundle result=new Bundle();int status=-1;
        try{
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(2000);
            WebSourceView source=(WebSourceView)get("webSourceView");
            for(int mode=0;mode<3;mode++){
                final int path=mode;
                final String url="http://127.0.0.1/retained-cast-"+mode;
                main(()->{
                    call("releasePlayer",new Class<?>[0]);
                    source.open((Integer)get("playRequestId"),url);
                    browser(source).loadDataWithBaseURL(url,"<html><body style='height:10000px'><input id='draft'><script>window.probe=Math.random();</script></body></html>","text/html","UTF-8",null);
                    set("remoteReceiverControlUrl","http://127.0.0.1:9");
                });
                SystemClock.sleep(1200);
                WebView original=browser(source);
                String before=js(original,"document.getElementById('draft').value='unsaved';window.scrollTo(0,800);String(window.probe)");
                if("null".equals(before))throw new AssertionError("Test document not ready");
                int requestBefore=(Integer)get("playRequestId");
                JSONObject command=new JSONObject().put("action","play").put("receiver",true)
                        .put("receiverUrl","http://127.0.0.1:9")
                        .put("group",get("currentGroupIndex")).put("channel",get("currentChannelIndex"))
                        .put("source",get("currentSourceIndex"));
                // Exercise the real receiver command, including a duplicate request.
                for(int attempt=0;attempt<2;attempt++) {
                    call("handleWebControl",new Class<?>[]{JSONObject.class},command);
                    SystemClock.sleep(500);
                    if(browser(source)!=original || (Integer)get("playRequestId")!=requestBefore)
                        throw new AssertionError("Starting cast replaced the page request");
                    if(!before.equals(js(original,"String(window.probe)")))
                        throw new AssertionError("Starting cast reloaded JS context");
                    if(!"\"unsaved\"".equals(js(original,"document.getElementById('draft').value")))
                        throw new AssertionError("Starting cast lost form state");
                }
                main(()->{
                    if(!((WebViewCastManager)get("webViewCastManager")).isRunning())throw new AssertionError("Encoder never started");
                    if(path==0)call("finishReceiverDisconnection",new Class<?>[0]);
                    else if(path==1)call("exitReceiverTakeover",new Class<?>[]{boolean.class},false);
                });
                if(path==2)call("handleWebTakeover",new Class<?>[]{JSONObject.class},new JSONObject().put("receiverUrl",""));
                SystemClock.sleep(700);
                main(()->{
                    if(browser(source)!=original)throw new AssertionError("WebView was replaced");
                    if(!url.equals(source.currentPageUrl())||!source.isPageVisible())throw new AssertionError("Current page lost");
                    if(((CastRootLayout)get("root")).hasCastViewport())throw new AssertionError("Cast viewport retained");
                    if(((WebViewCastManager)get("webViewCastManager")).isRunning())throw new AssertionError("Encoder leaked");
                    if(!"".equals(get("remoteReceiverControlUrl")))throw new AssertionError("Takeover not cleared");
                });
                if(!before.equals(js(original,"String(window.probe)")))throw new AssertionError("JS context reloaded");
                if(!"\"unsaved\"".equals(js(original,"document.getElementById('draft').value")))throw new AssertionError("Form state lost");
                if(Double.parseDouble(js(original,"window.scrollY"))<=0)throw new AssertionError("Scroll position reset");
            }
            // Cleanup-only paths must still release the document rather than retaining a hidden renderer.
            main(()->{set("remoteReceiverControlUrl","http://127.0.0.1:9");call("releaseReceiverPlayback",new Class<?>[0]);
                if(source.hasRetainedPage())throw new AssertionError("Cleanup retained page");});
            result.putString("stream","PASS start/duplicate start/disconnect/native stop/web stop: same WebView, JS context, form and scroll; encoder and viewport released; cleanup closes page\n");
        }catch(Throwable e){status=0;result.putString("stream",android.util.Log.getStackTraceString(e));}
        finally{if(activity!=null)runOnMainSync(()->activity.finish());}
        finish(status,result);
    }
}
