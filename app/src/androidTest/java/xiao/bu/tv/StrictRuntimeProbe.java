package xiao.bu.tv;
import android.app.Instrumentation;
import android.os.Bundle;
import android.webkit.*;
import java.util.concurrent.*;
import org.json.*;
/** Test-only end-to-end check through production preflight/compiler service and real WebView. */
final class StrictRuntimeProbe {
 static void run(final Instrumentation owner){
  Bundle result=new Bundle();final WebView[] view={null};final String[] observed={null};final CountDownLatch done=new CountDownLatch(1);
  try{
   String source="'use strict';if(true){function strictOnly(){return 42;}window.__strictProof=strictOnly();}";
   if(!LegacyJsSyntax.needsCompilation(source))throw new AssertionError("Production Java preflight missed strict block function");
   JSONObject transformed=LegacyJsTranspiler.get(owner.getTargetContext()).transform(source,"https://fixture.invalid/strict-only.js",false);
   final String code=transformed.getString("code");
   if(!transformed.optBoolean("changed"))throw new AssertionError("Production compiler did not transform strict block function");
   owner.runOnMainSync(new Runnable(){public void run(){
    view[0]=new WebView(owner.getTargetContext());view[0].getSettings().setJavaScriptEnabled(true);view[0].getSettings().setBlockNetworkLoads(true);
    view[0].setWebChromeClient(new WebChromeClient(){public boolean onConsoleMessage(ConsoleMessage m){android.util.Log.i("NtvStrictProduction",m.message());return true;}});
    view[0].setWebViewClient(new WebViewClient(){public void onPageFinished(WebView w,String url){w.evaluateJavascript("(function(){var d=new DOMException('test','AbortError');return {proof:window.__strictProof,errors:window.__strictErrors,domName:d.name,domCode:d.code,ua:navigator.userAgent};})()",new ValueCallback<String>(){public void onReceiveValue(String v){observed[0]=v;done.countDown();}});}});
    WebPageScriptManager manager=new WebPageScriptManager(view[0]);manager.update(false,false,false,"");
    String html="<!doctype html><html><head>"+manager.legacyDocumentPrelude()+"<script>window.__strictErrors=[];window.onerror=function(m){window.__strictErrors.push(String(m));};</script><script>"+code.replace("</script","<\\/script")+"</script></head><body>production strict fixture</body></html>";
    view[0].loadDataWithBaseURL("https://live.jstv.com/",html,"text/html","UTF-8",null);
   }});
   if(!done.await(20,TimeUnit.SECONDS))throw new AssertionError("Production WebView fixture timeout");
   JSONObject row=new JSONObject(observed[0]);if(row.optInt("proof")!=42||row.getJSONArray("errors").length()!=0||!"AbortError".equals(row.optString("domName"))||row.optInt("domCode")!=20)throw new AssertionError("Production fixture failed: "+row);
   transformed.remove("code");row.put("compiler",transformed);result.putString("stream","PASS production Java preflight + compiler service + WebView + DOMException: "+row+"\n");owner.finish(-1,result);
  }catch(Throwable e){result.putString("stream",android.util.Log.getStackTraceString(e));owner.finish(0,result);}
  finally{owner.runOnMainSync(new Runnable(){public void run(){if(view[0]!=null)view[0].destroy();}});}
 }
}
