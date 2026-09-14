package xiao.bu.tv;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.webkit.WebView;
import android.util.Log;
import java.lang.reflect.*;
import java.util.concurrent.*;
public final class WebAudioInstrumentation extends Instrumentation {
 private final StringBuilder report=new StringBuilder();
 private boolean channelFlow, returnOnly;
 private MainActivity activity; private WebSourceView source; private WebView web;
 @Override public void onCreate(Bundle b){super.onCreate(b);channelFlow=b!=null&&"true".equals(b.getString("channelFlow"));returnOnly=b!=null&&"true".equals(b.getString("returnOnly"));start();}
 private Object field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 private void call(String n)throws Exception{Method m=MainActivity.class.getDeclaredMethod(n);m.setAccessible(true);m.invoke(activity);}
 private interface Job{void run()throws Exception;}
 private void ui(Job j)throws Exception{final Exception[] e={null};runOnMainSync(()->{try{j.run();}catch(Exception x){e[0]=x;}});if(e[0]!=null)throw e[0];}
 private String js(String script)throws Exception{final String[] v={null};CountDownLatch l=new CountDownLatch(1);ui(()->web.evaluateJavascript(script,s->{v[0]=s;l.countDown();}));if(!l.await(8,TimeUnit.SECONDS))throw new Exception("JS timeout");return v[0];}
 private void note(String s){report.append(s).append('\n');Log.i("NtvWebAudio",s);}
 @Override public void onStart(){int code=-1;try{
 activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(1500);
 if(channelFlow){
  ui(()->{source=(WebSourceView)field(activity,"webSourceView");});
  Method control=MainActivity.class.getDeclaredMethod("handleWebControl",org.json.JSONObject.class);control.setAccessible(true);
  for(int index=1;index<=(returnOnly?1:8);index++){
   control.invoke(activity,new org.json.JSONObject().put("action","play").put("group",15).put("channel",index).put("source",0));
   SystemClock.sleep(12000);
   ui(()->web=(WebView)field(source,"webView"));
   note("CHANNEL "+index+" prepared="+field(activity,"prepared")+" focusMuted="+field(activity,"mutedByAudioFocus")+" callMuted="+field(activity,"mutedByCallMode")+" native="+(field(activity,"player")!=null));
   if(!Boolean.TRUE.equals(field(activity,"prepared")) || Boolean.TRUE.equals(field(activity,"mutedByAudioFocus")) || Boolean.TRUE.equals(field(activity,"mutedByCallMode")))
    throw new AssertionError("Channel "+index+" native audio unavailable or muted");
   note(js("JSON.stringify({url:location.href,guard:!!window.__ntvMediaPause,videos:[].map.call(document.querySelectorAll('video,audio'),function(v){return {paused:v.paused,muted:v.muted,volume:v.volume,time:v.currentTime,audioBytes:v.webkitAudioDecodedByteCount}})})"));
  }
  if(returnOnly){
   if(Boolean.TRUE.equals(field(activity,"mutedByAudioFocus")))throw new AssertionError("Native player lost audio focus");
   WebView retained=web;
   note("RETURN_RESULT "+control.invoke(activity,new org.json.JSONObject().put("action","returnToWeb")));
   note("IMMEDIATE "+js("JSON.stringify({visible:document.visibilityState,guard:!!window.__ntvMediaPause})"));
   SystemClock.sleep(6000);
   ui(()->web=(WebView)field(source,"webView"));
   if(web!=retained)throw new AssertionError("WebView was recreated");
   note("RETURN "+js("JSON.stringify({guard:!!window.__ntvMediaPause,videos:[].map.call(document.querySelectorAll('video'),function(v){return {paused:v.paused,muted:v.muted,volume:v.volume,time:v.currentTime,audio:v.webkitAudioDecodedByteCount}})})"));
   if(!"true".equals(js("(function(){var v=document.querySelector('video');return !window.__ntvMediaPause&&v&&!v.paused&&!v.muted&&v.volume>0&&v.currentTime>0})()")))throw new AssertionError("Returned page audio did not resume");
   note("PASS same WebView resumed with audible volume");
  }
  Bundle out=new Bundle();out.putString("stream",report.toString());finish(-1,out);return;
 }
 ui(()->{call("releasePlayer");call("hideLoading");source=(WebSourceView)field(activity,"webSourceView");source.setListener(null);});
 String[] urls={"https://live.jstv.com/?channelId=670","https://www.xjtvs.com.cn/column/tv/434?channelId=1"};
 for(String url:urls){
 ui(()->{source.open(990001,url);web=(WebView)field(source,"webView");});SystemClock.sleep(12000);
 note(url+" "+js("JSON.stringify({url:location.href,videos:[].map.call(document.querySelectorAll('video,audio'),function(v){return {muted:v.muted,defaultMuted:v.defaultMuted,volume:v.volume,paused:v.paused,time:v.currentTime,ready:v.readyState,audioBytes:v.webkitAudioDecodedByteCount,src:v.currentSrc,html:v.outerHTML.slice(0,600)}}),frames:[].map.call(document.querySelectorAll('iframe'),function(f){return f.src})})"));
 String check=js("(function(){var v=document.querySelector('video');return !!v&&!v.paused&&!v.muted&&v.volume>0&&v.webkitAudioDecodedByteCount>0})()");
 if(!"true".equals(check))throw new AssertionError("Audio playback state failed: "+url);
 if(url.contains("xjtvs.com.cn")&&!"true".equals(js("document.querySelector('video').currentSrc.indexOf('blob:')===0")))throw new AssertionError("DPlayer did not choose HLS.js");
 note("PASS audible volume and decoded audio: "+url);
 js("(function(){var v=document.querySelector('video');v.volume=0;v.muted=true;v.pause();v.play()})()");SystemClock.sleep(700);
 if(!"true".equals(js("(function(){var v=document.querySelector('video');return v.volume===0&&v.muted})()")))throw new AssertionError("User mute was overridden");
 note("PASS manual mute preserved");
 }
 ui(()->source.closePage());
 }catch(Throwable e){code=0;note(Log.getStackTraceString(e));}Bundle out=new Bundle();out.putString("stream",report.toString());finish(code,out);}
}
