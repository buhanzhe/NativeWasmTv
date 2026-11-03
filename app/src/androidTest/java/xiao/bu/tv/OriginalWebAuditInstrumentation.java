package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.*;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.io.*;
import java.lang.reflect.*;
import org.json.*;

/** Real API19 system-WebView fallback; observes browser-owned video, never decrypts it. */
public final class OriginalWebAuditInstrumentation extends Instrumentation {
    private Bundle args;
    private MainActivity activity;
    private WebSourceView page;
    private volatile String failure;
    private Throwable uiFailure;
    private int discovered;
    private final JSONArray events=new JSONArray();
    public void onCreate(Bundle input){super.onCreate(input);args=input;start();}
    private void event(String type){try{events.put(new JSONObject().put("type",type).put("elapsed",SystemClock.elapsedRealtime()));}catch(Exception ignored){}}
    private interface Action{void run()throws Exception;}
    private void main(Action action)throws Exception{
        uiFailure=null;runOnMainSync(()->{try{action.run();}catch(Throwable error){uiFailure=error;}});
        if(uiFailure!=null)throw new Exception(uiFailure);
    }
    public void onStart(){
        Bundle output=new Bundle();int status=-1;JSONObject record=new JSONObject();
        String id=args.getString("id","original-web");
        if(!id.matches("[A-Za-z0-9_-]{1,100}")){finish(0,output);return;}
        File folder=new File(getTargetContext().getExternalFilesDir(null),"original-web-audit");folder.mkdirs();
        try{
            if(Build.VERSION.SDK_INT!=19)throw new IllegalStateException("This audit is scoped to Android 4.4 only");
            ActivityMonitor monitor=addMonitor(MainActivity.class.getName(),null,false);
            try{getTargetContext().startActivity(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));activity=(MainActivity)monitor.waitForActivityWithTimeout(10000);if(activity==null)throw new IOException("Activity creation timeout");}finally{removeMonitor(monitor);}
            final String url=args.containsKey("urlBase64")?new String(android.util.Base64.decode(args.getString("urlBase64"),android.util.Base64.DEFAULT),"UTF-8"):args.getString("url");
            final String script=args.containsKey("ujsBase64")?new String(android.util.Base64.decode(args.getString("ujsBase64"),android.util.Base64.DEFAULT),"UTF-8"):args.getString("ujs","");
            main(()->{
                try{Field request=MainActivity.class.getDeclaredField("playRequestId");request.setAccessible(true);request.setInt(activity,request.getInt(activity)+1000);Method release=MainActivity.class.getDeclaredMethod("releasePlayer");release.setAccessible(true);release.invoke(activity);}catch(Exception error){throw new RuntimeException(error);}
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
                page=new WebSourceView(activity);page.setListener(new WebSourceView.Listener(){
                    public void onPageStarted(int id,String url){event("started");}
                    public void onPageReady(int id,String url,String title){event("ready");}
                    public void onPageError(int id,String reason){failure=reason;event("error");}
                    public void onStreamDiscovered(int id,String stream,String url,String ua,String cookies){discovered++;event("raw-resource-observed-without-handoff");}
                    public void onBrowserHome(){}public void onBrowserChannel(int g,int c){}
                    public void onBrowserDownloadImage(String url){}public void onBrowserClipboard(String value,String message){}
                    public void onBrowserUserScript(String url){}public void onBrowserPoliciesChanged(boolean images,boolean block,boolean rtc){}
                });
                ((FrameLayout)activity.findViewById(android.R.id.content)).addView(page,new FrameLayout.LayoutParams(-1,-1));
                page.applyConfiguration("720p",true,"windows",1f);page.applyMinimalMode(true);page.applyPageCover(false);
                page.open(999,url,script,false,"原网页加密流程验证","");
            });
            JSONArray samples=new JSONArray();long begin=SystemClock.elapsedRealtime(),limit=begin+Long.parseLong(args.getString("seconds","110"))*1000,observed=0,lastAt=begin,lastPosition=-1;
            String mediaToken="";JSONObject last=new JSONObject();
            while(SystemClock.elapsedRealtime()<limit){
                final JSONObject[] current={null};main(()->current[0]=page.currentWebMediaState());
                last=current[0]==null?new JSONObject():new JSONObject(current[0].toString());long now=SystemClock.elapsedRealtime(),position=last.optLong("mediaTimeMs",-1);String token=last.optString("token");
                boolean valid=last.optInt("mediaReadyState")>=2&&last.optInt("videoWidth")>0&&!last.optBoolean("mediaPaused",true)&&!last.optBoolean("mediaEnded")&&last.optBoolean("playing");
                if(!token.equals(mediaToken)){observed=0;lastPosition=-1;mediaToken=token;}
                if(valid&&lastPosition>=0&&position>lastPosition)observed+=Math.min(position-lastPosition,now-lastAt);
                else if(position<lastPosition)observed=0;
                JSONObject sample=new JSONObject().put("elapsedMs",now-begin).put("ready",last.optInt("mediaReadyState")).put("positionMs",position).put("width",last.optInt("videoWidth")).put("height",last.optInt("videoHeight")).put("playing",valid).put("observedMs",observed);samples.put(sample);
                lastAt=now;lastPosition=position;if(observed>=15000)break;Thread.sleep(500);
            }
            record.put("url",url).put("android",Build.VERSION.RELEASE).put("samples",samples).put("events",events).put("rawResources",discovered).put("observedPlaybackMs",observed).put("actualBrowserVideo",observed>=15000).put("failure",failure==null?JSONObject.NULL:failure).put("visualReview","pending").put("success",false).put("mode","original system WebView; no raw native handoff, signature or decryption hook");
            Bitmap shot=getUiAutomation().takeScreenshot();if(shot!=null)try(OutputStream out=new FileOutputStream(new File(folder,id+".png"))){shot.compress(Bitmap.CompressFormat.PNG,100,out);shot.recycle();}
            output.putString("stream","Original WebView "+id+" observed="+observed+" ms; visual review pending\n");
        }catch(Throwable error){status=0;try{record.put("testError",android.util.Log.getStackTraceString(error));}catch(Exception ignored){}output.putString("stream",android.util.Log.getStackTraceString(error));}
        finally{
            try(Writer writer=new OutputStreamWriter(new FileOutputStream(new File(folder,id+".json")),"UTF-8")){writer.write(record.toString(2));}catch(Exception ignored){}
            try{main(()->{if(page!=null)page.closePage();if(activity!=null)activity.finish();});}catch(Exception ignored){}
        }
        finish(status,output);
    }
}
