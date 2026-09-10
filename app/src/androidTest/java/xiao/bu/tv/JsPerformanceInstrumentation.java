package xiao.bu.tv;
import android.app.Instrumentation;
import android.os.Bundle;
import android.webkit.*;
import java.util.concurrent.*;
import org.json.*;

/** Offline identical ES5 workloads; timings exclude network and video. */
public final class JsPerformanceInstrumentation extends Instrumentation {
 private WebView web; private volatile CountDownLatch done; private volatile String answer; private final StringBuilder log=new StringBuilder();
 public void onCreate(Bundle b){super.onCreate(b);start();}
 private static double ms(long start){return (System.nanoTime()-start)/1e6;}
 private String body(String kind){
  if(kind.equals("compute"))return "var x=1;for(var i=0;i<300000;i++){x=((x*1664525+1013904223)|0);}return String(x);";
  if(kind.equals("json"))return "var a=[];for(var i=0;i<100;i++)a.push({id:i,name:'channel'+i,url:'https://example.test/live/'+i});var s=JSON.stringify(a),v=0;for(var j=0;j<300;j++){var b=JSON.parse(s);v+=b[50].id;JSON.stringify(b);}return String(v);";
  return "var s='https://example.test/live/channel123?id=456&quality=high',v=0;for(var i=0;i<10000;i++){var m=/channel([0-9]+)/.exec(s);v+=Number(m[1]);s.replace(/quality=[a-z]+/,'quality=low');}return String(v);";
 }
 private String script(String kind,boolean q){return "(function(){var t=Date.now();var v=(function(){"+body(kind)+"})();var r=JSON.stringify({innerMs:Date.now()-t,value:v});"+(q?"NtvCjsBridge.complete(r);":"prompt(r,'bench');")+"})();";}
 private void record(String engine,String kind,int i,double wall,String result)throws Exception{JSONObject r=new JSONObject(result);r.put("engine",engine).put("kind",kind).put("iteration",i).put("wallMs",wall);log.append(r).append('\n');}
 public void onStart(){Bundle out=new Bundle();try{
  log.append("SDK ").append(android.os.Build.VERSION.SDK_INT).append(" ABI ").append(BuildConfig.CJS_PLUGIN_ABI).append('\n');
  long start=System.nanoTime();Class.forName("xiao.bu.tv.NativeQuickJs");log.append("quickjsLibraryLoadMs ").append(ms(start)).append('\n');
  for(String kind:new String[]{"compute","json","regex"})for(int i=0;i<11;i++){
   final String[] r={null};start=System.nanoTime();NativeQuickJs.execute(script(kind,true),new NativeQuickJs.Host(){public boolean isCancelled(){return false;}public String invoke(int op,String[] a){if(op==5)r[0]=a[0];return null;}});record("quickjs",kind,i,ms(start),r[0]);
  }
  done=new CountDownLatch(1);start=System.nanoTime();runOnMainSync(new Runnable(){public void run(){web=new WebView(getTargetContext());web.getSettings().setJavaScriptEnabled(true);log.append("webviewUA ").append(web.getSettings().getUserAgentString()).append('\n');web.setWebChromeClient(new WebChromeClient(){public boolean onJsPrompt(WebView v,String u,String message,String def,JsPromptResult r){answer=message;r.confirm();done.countDown();return true;}});web.setWebViewClient(new WebViewClient(){public void onPageFinished(WebView v,String u){done.countDown();}});web.loadDataWithBaseURL("https://benchmark.invalid/","<html><body>benchmark</body></html>","text/html","UTF-8",null);}});if(!done.await(30,TimeUnit.SECONDS))throw new Exception("page init timeout");log.append("webviewReadyMs ").append(ms(start)).append('\n');
  for(final String kind:new String[]{"compute","json","regex"})for(int i=0;i<11;i++){
   answer=null;done=new CountDownLatch(1);start=System.nanoTime();runOnMainSync(new Runnable(){public void run(){web.loadUrl("javascript:"+script(kind,false));}});if(!done.await(30,TimeUnit.SECONDS))throw new Exception("JS timeout");record("webview",kind,i,ms(start),answer);
  }
  out.putString("stream","PASS\n"+log);finish(-1,out);
 }catch(Throwable e){out.putString("stream","FAIL "+e+"\n"+log);finish(1,out);}finally{runOnMainSync(new Runnable(){public void run(){if(web!=null){web.stopLoading();web.destroy();web=null;}}});}}
}
