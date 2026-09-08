package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.webkit.WebView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Layout assertions use native innerWidth, CSS vw/media queries and real input hit-testing. */
public final class DesktopZoomInstrumentation extends Instrumentation {
    private Bundle args;
    private MainActivity activity;
    private WebSourceView source;
    private WebView web;
    private final StringBuilder report=new StringBuilder();
    @Override public void onCreate(Bundle b){super.onCreate(b);args=b;start();}
    @Override public void onStart(){
        int code=-1;
        try {
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(3000);
            main(() -> {
                Field f=MainActivity.class.getDeclaredField("playRequestId");f.setAccessible(true);f.setInt(activity,f.getInt(activity)+1);
                Method m=MainActivity.class.getDeclaredMethod("releasePlayer");m.setAccessible(true);m.invoke(activity);
                source=(WebSourceView)field(activity,"webSourceView");source.setListener(null);
                // Emulator windows can be resized to non-16:9 (e.g. 2569 x 1440).
                // Use a 16:9 content area; do not change device resolution/density.
                android.view.View root=(android.view.View)field(activity,"root");
                int width=Math.min(root.getWidth(),root.getHeight()*16/9);
                source.setLayoutParams(new android.widget.FrameLayout.LayoutParams(width,width*9/16));
            });
            for(String mode:new String[]{"720p","1080p","2k"}) {
                int width=mode.equals("2k")?2560:mode.equals("1080p")?1920:1280;
                for(float zoom:new float[]{0.5f,0.75f,1f,1.25f,1.5f,2f,3f}) {
                    open("http://127.0.0.1:18890/zoom.html",mode,zoom);
                    JSONObject p=json("zoomSnapshot()");note(mode+" "+zoom+" "+p);
                    if(!args.containsKey("probe")) {verify(p,width,zoom);clickTarget(p);}
                }
            }
            open("http://127.0.0.1:18890/zoom.html","2k",2f);
            if(!args.containsKey("probe")) {
                json("(function(){window.keepZoomState='preserved';zoomClicks=7;return {ok:true}})()");
                main(() -> source.applyConfiguration("2k",true,"windows",1f));SystemClock.sleep(900);
                verify(json("zoomSnapshot()"),2560,1);
                main(() -> source.applyConfiguration("2k",true,"windows",2f));SystemClock.sleep(900);
                verify(json("zoomSnapshot()"),2560,2);
                check(json("({state:window.keepZoomState,clicks:zoomClicks})").getString("state").equals("preserved"),"Zoom reloaded the document");
                check(json("zoomSnapshot()").getInt("clicks")==7,"Zoom lost page state");
                note("live zoom 200% -> 100% -> 200% preserves document state");
            }
            main(() -> source.setCastCaptureActive(true, 30));SystemClock.sleep(1000);
            JSONObject cast=json("zoomSnapshot()");note("cast preserves browser 2k 200% "+cast);
            if(!args.containsKey("probe")) {verify(cast,2560,2);clickTarget(cast);}
            main(() -> source.setCastCaptureActive(false, 30));SystemClock.sleep(1000);
            JSONObject restored=json("zoomSnapshot()");note("restored "+restored);
            if(!args.containsKey("probe"))verify(restored,2560,2);
            json("(function(){document.querySelector('meta[name=viewport]').content='width=device-width,initial-scale=1';return {ok:true}})()");
            SystemClock.sleep(800);
            if(!args.containsKey("probe"))verify(json("zoomSnapshot()"),2560,2);
            json("(function(){var el=document.createElement('div');el.style.width='4000px';el.style.height='100px';document.body.appendChild(el);return {ok:true}})()");
            SystemClock.sleep(700);
            note("overflow "+json("zoomSnapshot()"));
            if(!args.containsKey("probe"))verify(json("zoomSnapshot()"),2560,2);
            if(args.containsKey("public")) {
                open("https://toolwa.com/monitor-info/","2k",2f);SystemClock.sleep(8000);
                JSONObject page=json("({title:document.title,text:document.body.innerText,screenWidth:screen.width,screenHeight:screen.height,dpr:devicePixelRatio,innerWidth:innerWidth,innerHeight:innerHeight})");
                note("public "+page);
                check(page.getString("title").contains("工具哇"),"public page unavailable");
                android.graphics.Bitmap b=getUiAutomation().takeScreenshot();
                java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null),"desktop-zoom-2k-200.png"));
                try{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{out.close();b.recycle();}
            }
            note("PASS");
        } catch(Throwable error){code=1;note("FAIL "+Log.getStackTraceString(error));}
        Bundle result=new Bundle();result.putString("stream",report.toString());finish(code,result);
    }
    private void open(String url,String mode,float zoom)throws Exception{
        main(()->{source.applyConfiguration(mode,true,"windows",zoom);source.open(900011,url);web=(WebView)field(source,"webView");});
        SystemClock.sleep(650);
        for(int i=0;i<100;i++){if(json("({ready:document.readyState})").optString("ready").equals("complete"))break;SystemClock.sleep(100);}
        SystemClock.sleep(500);
    }
    private void verify(JSONObject p,int width,double zoom)throws Exception{
        check(p.getInt("screenWidth")==width&&p.getInt("screenHeight")==width*9/16,"screen must not zoom");
        check(p.getInt("outerWidth")==width&&p.getInt("outerHeight")==width*9/16,"outer window must not zoom");
        check(Math.abs(p.getDouble("dpr")-zoom)<0.001,"DPR does not follow zoom");
        check(Math.abs(p.getDouble("innerWidth")-width/zoom)<=2,"layout viewport cropped instead of reflowed: "+p);
        check(Math.abs(p.getDouble("innerHeight")-width*9/16/zoom)<=2,"viewport height incorrect: "+p);
        check(Math.abs(p.getDouble("vw")-width/zoom)<=2,"CSS vw differs from viewport");
        check(p.getString("columns").trim().equals(width/zoom<=1500?"1":"2"),"CSS breakpoint differs from viewport");
    }
    private void clickTarget(JSONObject p)throws Exception{
        main(()->{
            float x=(float)p.getDouble("x")*web.getScale(),y=(float)p.getDouble("y")*web.getScale();
            long t=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,x,y,0);
            MotionEvent up=MotionEvent.obtain(t,t+30,MotionEvent.ACTION_UP,x,y,0);
            try{web.dispatchTouchEvent(down);web.dispatchTouchEvent(up);}finally{down.recycle();up.recycle();}
        });
        SystemClock.sleep(150);check(json("zoomSnapshot()").getInt("clicks")==p.getInt("clicks")+1,"zoomed hit target missed");
    }
    private JSONObject json(String expression)throws Exception{
        String[] result={null};CountDownLatch done=new CountDownLatch(1);
        main(()->web.evaluateJavascript("JSON.stringify("+expression+")",v->{result[0]=v;done.countDown();}));
        check(done.await(5,TimeUnit.SECONDS),"JS timeout");return new JSONObject((String)new JSONTokener(result[0]).nextValue());
    }
    private interface Work{void run()throws Exception;}
    private void main(Work work)throws Exception{Exception[] error={null};runOnMainSync(()->{try{work.run();}catch(Exception e){error[0]=e;}});if(error[0]!=null)throw error[0];}
    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private void note(String value){report.append(value).append('\n');Log.i("DesktopZoomTest",value);Bundle b=new Bundle();b.putString("stream",value+"\n");sendStatus(0,b);}
}
