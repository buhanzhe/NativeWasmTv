package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Compare stock HTTPS and the small GET bridge in the actual WebSourceView on API 19. */
public final class LegacyWebGetInstrumentation extends Instrumentation {
    private MainActivity activity;
    private WebSourceView source;
    private final StringBuilder report = new StringBuilder();
    private Object field(Object object, String name) throws Exception {
        Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    private void call(String name, Class<?>[] types, Object... args) throws Exception {
        Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(activity,args);
    }
    private void log(String value) { report.append(value).append('\n');android.util.Log.i("LegacyWebTest",value); }
    private String inspect(WebView web) throws Exception {
        CountDownLatch done=new CountDownLatch(1);String[] text={"timeout"};
        runOnMainSync(()->web.evaluateJavascript("JSON.stringify({url:location.href,title:document.title,html:document.documentElement.outerHTML.length,text:document.body?document.body.innerText.length:0,images:document.images.length,ready:document.readyState,error:location.protocol==='chrome-error:'})",v->{text[0]=v;done.countDown();}));
        done.await(6,TimeUnit.SECONDS);return text[0];
    }
    @Override public void onCreate(Bundle args) {super.onCreate(args);start();}
    @Override public void onStart() {
        int code=-1;int channel=0, line=0;
        try {
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(1500);
            channel=(Integer)field(activity,"currentChannelIndex");line=(Integer)field(activity,"currentSourceIndex");
            runOnMainSync(()->{try {
                call("releasePlayer",new Class<?>[0]);call("closeWebSource",new Class<?>[0]);
                source=new WebSourceView(activity);
                activity.addContentView(source,new FrameLayout.LayoutParams(-1,-1));
            }catch(Exception e){throw new RuntimeException(e);}});
            StringBuilder list=new StringBuilder();
            try(java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(getContext().getAssets().open("legacy-web-get-sites.txt"),"UTF-8"))){String s;while((s=reader.readLine())!=null)list.append(s).append('\n');}
            int index=0;
            for(String url:list.toString().trim().split("\n")) {
                for(int mode=0;mode<2;mode++) {
                    final boolean baseline=mode==0;final int request=++index;
                    runOnMainSync(()->{try {
                        source.open(request,url);
                        if(baseline) {
                            final WebViewClient delegate=(WebViewClient)field(source,"sourceClient");
                            ((WebView)field(source,"webView")).setWebViewClient(new WebViewClient(){
                                @Override public void onPageStarted(WebView v,String u,android.graphics.Bitmap b){delegate.onPageStarted(v,u,b);}
                                @Override public void onPageFinished(WebView v,String u){delegate.onPageFinished(v,u);}
                                @Override public void onReceivedError(WebView v,int c,String t,String u){delegate.onReceivedError(v,c,t,u);}
                            });
                        }
                    }catch(Exception e){throw new RuntimeException(e);}});
                    SystemClock.sleep(11000);
                    log((baseline?"native ":"GET ")+url+" "+inspect((WebView)field(source,"webView")));
                    android.graphics.Bitmap shot=getUiAutomation().takeScreenshot();
                    if(shot!=null){try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(activity.getExternalFilesDir(null),"web-get-"+request+".png"))){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{shot.recycle();}}
                }
            }
        }catch(Throwable error){code=0;log(android.util.Log.getStackTraceString(error));}
        finally {
            final int restoreChannel=channel,restoreLine=line;
            if(activity!=null)runOnMainSync(()->{try{if(source!=null){source.destroyPage();((ViewGroup)source.getParent()).removeView(source);}call("switchChannel",new Class<?>[]{int.class,int.class},restoreChannel,restoreLine);}catch(Exception ignored){}});
        }
        Bundle result=new Bundle();result.putString("stream",report.toString());finish(code,result);
    }
}
