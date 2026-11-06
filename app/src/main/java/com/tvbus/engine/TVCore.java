package com.tvbus.engine;
import android.content.Context;
/** JNI bridge only. Native engine is downloaded on demand, never bundled. */
public final class TVCore {
 private long handle;
 private Listener listener;
 private int playPort=8902;
 public TVCore(String path) { System.load(path); handle=initialise(); if(handle==0||handle==-1)throw new IllegalStateException("TVBus initialise failed"); }
 private void optional(String name,Runnable setter) {
  try { setter.run(); } catch (Throwable error) { xiao.bu.tv.AppLog.w("TvbusPlugin","Optional native API unavailable: "+name+" ("+error.getClass().getSimpleName()+")"); }
 }
 public void configure() {
  optional("setServPort",()->setServPort(handle,0));
  optional("setPlayPort",()->setPlayPort(handle,playPort));
  optional("setRunningMode",()->setRunningMode(handle,1));
 }
 public void settings(org.json.JSONObject core) throws org.json.JSONException {
  playPort=core.optInt("playPort",8902);
  if(playPort<1024||playPort>65535)throw new IllegalArgumentException("Invalid TVBus playback port");
  String value=core.optString("auth","");if(!value.isEmpty()){final String v=value;optional("setAuthUrl",()->setAuthUrl(handle,v));}
  value=core.optString("name","");if(!value.isEmpty()){final String v=value;optional("setUsername",()->setUsername(handle,v));}
  value=core.optString("pass","");if(!value.isEmpty()){final String v=value;optional("setPassword",()->setPassword(handle,v));}
  value=core.optString("domain","");if(!value.isEmpty()){final String v=value;optional("setDomainSuffix",()->setDomainSuffix(handle,v));}
  value=core.optString("broker","");if(!value.isEmpty()){final String v=value;optional("setMKBroker",()->setMKBroker(handle,v));}
  org.json.JSONArray options=core.optJSONArray("option");
  if(options!=null)for(int i=0;i<options.length();i++){
   org.json.JSONObject option=options.getJSONObject(i);String key=option.optString("key","");
   org.json.JSONArray values=option.optJSONArray("values");
   if(!key.isEmpty()&&values!=null)for(int j=0;j<values.length();j++){
    value=values.optString(j,"");if(!value.isEmpty()){final String v=value;optional("setOption",()->setOption(handle,key,v));}
   }
  }
 }
 public int initialize(Context context) { return init(handle,context); }
 public int loop() { return run(handle); }
 public void listen(Listener value) { listener=value;setListener(handle,value); }
 public void play(String url) { start(handle,url); }
 public void halt() { stop(handle); }
 public void close() { quit(handle); }
 private native void setAuthUrl(long handle,String value);
 private native void setUsername(long handle,String value);
 private native void setPassword(long handle,String value);
 private native void setDomainSuffix(long handle,String value);
 private native void setMKBroker(long handle,String value);
 private native void setOption(long handle,String key,String value);
 private native long initialise();
 private native int init(long handle,Context context);
 private native int run(long handle);
 private native void start(long handle,String url);
 private native void start2(long handle,String url,String code);
 private native void stop(long handle);
 private native void quit(long handle);
 private native void setRunningMode(long handle,int mode);
 private native void setServPort(long handle,int port);
 private native void setPlayPort(long handle,int port);
 private native void setListener(long handle,Listener listener);
}
