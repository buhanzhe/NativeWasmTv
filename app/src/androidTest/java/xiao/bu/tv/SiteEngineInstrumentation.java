package xiao.bu.tv;
import android.app.Instrumentation;import android.os.Bundle;import android.webkit.*;import java.util.concurrent.*;import java.io.*;import java.security.*;import org.json.*;
public final class SiteEngineInstrumentation extends Instrumentation {
 WebView web;volatile CountDownLatch done;volatile String answer;double net;int requests;StringBuilder rows=new StringBuilder();
 public void onCreate(Bundle b){super.onCreate(b);start();}
 double ms(long n){return (System.nanoTime()-n)/1e6;}
 String asset(String s)throws Exception{InputStream in=getContext().getAssets().open(s+".js");ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))>0)o.write(b,0,n);in.close();return o.toString("UTF-8");}
 String call(int op,String[] a)throws Exception{
  if(op==3){byte[] d=MessageDigest.getInstance("MD5").digest(a[0].getBytes("UTF-8"));StringBuilder s=new StringBuilder();for(byte v:d)s.append(String.format(java.util.Locale.US,"%02x",v&255));return s.toString();}
  if(op==2){long t=System.nanoTime();String r=Ku9HttpClient.requestJson(a[0],a[1],a[2],a[3],true,8*1024*1024);net+=ms(t);requests++;return r;}
  if(op==5||op==6){answer=a[0];if(done!=null)done.countDown();return "";}throw new Exception("op "+op);
 }
 String script(String site,boolean quick)throws Exception{
  String bridge=quick?"function invoke(o,a){if(o===3)return NtvCjsBridge.md5(a[0]);return NtvCjsBridge.request(a[0],a[1],a[2],a[3],true);}":"function invoke(o,a){return prompt(JSON.stringify({op:o,args:a}),'host');}";
  String url=site.equals("cctv")?"https://tv.cctv.com/live/cctv2/":"https://tv.gxtv.cn/channel/channelivePlay_f3335975f9fe11e88bcfe41f13b60c62.html";
  return "(function(){"+bridge+"var cjs={md5:function(s){return invoke(3,[s]);},request:function(u,m,h,b){return JSON.parse(invoke(2,[u,m,JSON.stringify(h),b]));}};"+asset(site)+"var result;try{result=JSON.stringify(main({url:"+JSONObject.quote(url)+",quality:'high'}));}catch(e){result=JSON.stringify({error:String(e)});}"+(quick?"NtvCjsBridge.complete(result);":"prompt(result,'result');")+"})();";
 }
 public void onStart(){Bundle out=new Bundle();try{
  NetworkClient.initialize(getTargetContext());CjsPluginRuntime.initialize(getTargetContext());done=new CountDownLatch(1);long t=System.nanoTime();runOnMainSync(()->{web=new WebView(getTargetContext());web.getSettings().setJavaScriptEnabled(true);web.setWebChromeClient(new WebChromeClient(){public boolean onJsPrompt(WebView v,String u,String m,String def,JsPromptResult r){try{if("result".equals(def)){answer=m;r.confirm();done.countDown();}else{JSONObject j=new JSONObject(m);JSONArray x=j.getJSONArray("args");String[] a=new String[x.length()];for(int i=0;i<a.length;i++)a[i]=x.getString(i);final int op=j.getInt("op");final String[] args=a;FutureTask<String> task=new FutureTask<String>(() -> call(op,args));new Thread(task,"site-http").start();r.confirm(task.get(40,TimeUnit.SECONDS));}}catch(Exception e){r.confirm("{}");}return true;}});web.setWebViewClient(new WebViewClient(){public void onPageFinished(WebView v,String u){done.countDown();}});web.loadDataWithBaseURL("https://benchmark.invalid/","<html></html>","text/html","UTF-8",null);});if(!done.await(30,TimeUnit.SECONDS))throw new Exception("web init");rows.append("webInitMs ").append(ms(t)).append('\n');
  for(String site:new String[]{"cctv","gxtv"})for(int round=0;round<4;round++)for(int order=0;order<2;order++){
   boolean q=(round%2==0)==(order==0);final String js=script(site,q);answer=null;net=0;requests=0;done=new CountDownLatch(1);t=System.nanoTime();
   if(q)NativeQuickJs.execute(js,new NativeQuickJs.Host(){public boolean isCancelled(){return false;}public String invoke(int op,String[] a)throws Exception{return call(op,a);}});
   else{runOnMainSync(()->web.loadUrl("javascript:"+js));if(!done.await(60,TimeUnit.SECONDS))throw new Exception("JS timeout");}
   double wall=ms(t);JSONObject v=new JSONObject(answer);JSONObject row=new JSONObject().put("site",site).put("engine",q?"quickjs":"webview").put("round",round).put("wallMs",wall).put("netMs",net).put("overheadMs",wall-net).put("requests",requests).put("ok",v.has("url"));if(v.has("error"))row.put("error",v.getString("error"));rows.append(row).append('\n');
  }
  out.putString("stream","PASS\n"+rows);finish(-1,out);
 }catch(Throwable e){out.putString("stream","FAIL "+e+"\n"+rows);finish(1,out);}finally{runOnMainSync(()->{if(web!=null)web.destroy();});}}
}
