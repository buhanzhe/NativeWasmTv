package xiao.bu.tv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Exercises the installed management page in the device's actual system WebView. */
public final class BrowserKernelInstrumentation extends Instrumentation {
    private final CountDownLatch completed = new CountDownLatch(1);
    private volatile String result;
    public final class Capture {
        @JavascriptInterface public void result(String value) { result=value; completed.countDown(); }
    }
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    private static WebView find(View view) {
        if(view instanceof WebView)return (WebView)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){
            WebView found=find(((ViewGroup)view).getChildAt(i));if(found!=null)return found;
        }
        return null;
    }
    private Activity open(String name) {
        return startActivitySync(new Intent(Intent.ACTION_MAIN)
                .setComponent(new ComponentName(getTargetContext().getPackageName(),name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("management_url","http://127.0.0.1:9966/pages/browser.html"));
    }
    @Override public void onStart() {
        Bundle report=new Bundle();Activity management=null;int status=Activity.RESULT_CANCELED;
        SharedPreferences preferences=getTargetContext().getSharedPreferences("tv_player",0);
        boolean hadDecode=preferences.contains("decode_mode"),hadEngine=preferences.contains("player_engine");
        String decode=preferences.getString("decode_mode","auto"),engine=preferences.getString("player_engine","ijk");
        try {
            // UI compatibility must not depend on a legacy live codec's release lock.
            preferences.edit().putString("decode_mode","software").putString("player_engine","ijk").commit();
            open("xiao.bu.tv.MainActivity");
            long deadline=System.currentTimeMillis()+45000;boolean online=false;String lastServerError="";
            while(System.currentTimeMillis()<deadline){
                HttpURLConnection connection=null;
                try{
                    connection=(HttpURLConnection)new URL("http://127.0.0.1:9966/pages/browser.html").openConnection();
                    connection.setConnectTimeout(1000);connection.setReadTimeout(1000);
                    int code=connection.getResponseCode();
                    if(code==200){online=true;break;}
                    lastServerError="HTTP "+code;
                }catch(Exception error){lastServerError=error.toString();}finally{if(connection!=null)connection.disconnect();}
                Thread.sleep(500);
            }
            if(!online)throw new AssertionError("Management server not reachable: "+lastServerError);
            management=open("xiao.bu.tv.ManagementActivity");
            final Activity page=management;
            runOnMainSync(()->{
                WebView web=find(page.getWindow().getDecorView());
                if(web==null)throw new AssertionError("Management WebView missing");
                web.addJavascriptInterface(new Capture(),"NtvKernelProbe");
                // Re-load so API 14 WebKit exposes the new bridge to this document.
                web.loadUrl("http://127.0.0.1:9966/pages/browser.html");
            });
            Thread.sleep(2500);
            runOnMainSync(()->{
                WebView web=find(page.getWindow().getDecorView());
                web.loadUrl("javascript:"+SCRIPT);
            });
            if(!completed.await(90,TimeUnit.SECONDS))throw new AssertionError("WebView probe timed out");
            report.putString("result",result);
            if(!new JSONObject(result).optBoolean("pass"))throw new AssertionError(result);
            report.putString("stream","PASS actual system WebView kernel selector: "+result+"\n");
            status=Activity.RESULT_OK;
        }catch(Throwable error){
            report.putString("stream","FAIL actual system WebView kernel selector: "+error+"\n");
            if(result!=null)report.putString("result",result);
        }finally{
            SharedPreferences.Editor editor=preferences.edit();
            if(hadDecode)editor.putString("decode_mode",decode);else editor.remove("decode_mode");
            if(hadEngine)editor.putString("player_engine",engine);else editor.remove("player_engine");
            editor.commit();
            if(management!=null){final Activity page=management;runOnMainSync(page::finish);}
        }
        finish(status,report);
    }
    private static final String SCRIPT="(function(){"
        +"var rows=[],original=null,end=Date.now()+60000;"
        +"function done(pass,error){NtvKernelProbe.result(JSON.stringify({pass:pass,error:error||'',rows:rows,ua:navigator.userAgent}));}"
        +"function click(el){var e=document.createEvent('MouseEvents');e.initEvent('click',true,true);el.dispatchEvent(e);}"
        +"function restore(error){if(original===null){done(false,error);return;}api('/api/settings',{webSourceEngine:original},function(e){done(false,error+(e?'; restore failed: '+e.message:''));});}"
        +"function choose(engine,next){try{var select=document.getElementById('webSourceEngine');click(select);"
        +"var options=document.querySelectorAll('.micro-select-option'),label=engine==='lite'?'轻量':'系统',button=null;"
        +"for(var i=0;i<options.length;i++)if(options[i].textContent.indexOf(label)>=0)button=options[i];"
        +"rows.push({engine:engine,optionCount:options.length,disabled:button?button.disabled:null});"
        +"if(!button||button.disabled)throw Error('Option missing or disabled: '+engine);click(button);"
        +"var deadline=Date.now()+10000;function verify(){api('/api/state?view=browser',null,function(e,d){"
        +"if(!e&&d.settings.webSourceEngine===engine){rows.push({saved:engine});next();}"
        +"else if(Date.now()<deadline)setTimeout(verify,300);else restore('Not saved: '+engine);});}verify();"
        +"}catch(e){restore(String(e));}}"
        +"function wait(){if(typeof state==='undefined'||!state||!state.settings||document.getElementById('webSourceEngine').disabled){"
        +"if(Date.now()<end)setTimeout(wait,300);else done(false,'Settings not loaded');return;}"
        +"if(state.settings.liteBrowserSupported===false){var row=document.getElementById('webSourceEngineRow');"
        +"var hidden=!!row&&(row.hidden||row.style.display==='none');rows.push({supported:false,hidden:hidden});done(hidden,hidden?'':'Standard package exposes kernel entry');return;}"
        +"original=state.settings.webSourceEngine;rows.push({supported:state.settings.liteBrowserSupported,original:original});"
        +"choose(original==='lite'?'system':'lite',function(){choose(original,function(){done(true);});});}wait();"
        +"})()";
}
