package xiao.bu.tv.windows;

import xiao.bu.tv.WindowsCoreSession;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;

/** The production Windows session, held alive while its actual media is decoded. */
public final class RealChannelProbe {
  private static WindowsCoreSession active;
  private static Path evidence;
  private static volatile String delivered;
  private static Object field(Object owner,String name)throws Exception {java.lang.reflect.Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
  private static void record(String phase){
    try{
      Object engine=field(active,"engine");if(engine==null)return;
      JSONObject result=new JSONObject().put("phase",phase);
      try{
        Object selected=field(active,"selected");
        if(selected!=null){
          result.put("selected_source_private",field(active,"selectedSource"));
          synchronized(selected){result.put("selectedConsole",new JSONArray((Collection<?>)field(selected,"consoleEvents")));}
        }
      }catch(ReflectiveOperationException ignored){}
      try{result.put("dependencyRetries",field(active,"dependencyRetries"));}catch(ReflectiveOperationException ignored){}
      for(String key:new String[]{"selectionTarget","delivered"}){
        String raw=key.equals("delivered")?delivered:String.valueOf(field(engine,key));
        if(raw!=null&&!raw.isEmpty()){java.net.URI url=java.net.URI.create(raw);result.put(key,new JSONObject().put("host",url.getHost()).put("path",url.getPath()));}
      }
      for(String key:new String[]{"status","fatal","errors","hostErrors","resourceErrors","firstMediaMs","readyMs","selectionPending","mseAttached"})result.put(key,field(engine,key));
      result.put("unsupported",new JSONArray((Set<?>)field(engine,"unsupported")));
      JSONArray requests=(JSONArray)field(engine,"requests"),network=new JSONArray();
      synchronized(requests){for(int i=0;i<requests.length();i++){
        JSONObject row=requests.getJSONObject(i),brief=new JSONObject();java.net.URI url=java.net.URI.create(row.optString("url"));
        brief.put("host",url.getHost()).put("path",url.getPath());for(String key:new String[]{"status","error","kind","ms","bytes"})if(row.has(key))brief.put(key,row.get(key));network.put(brief);
      }}
      result.put("network",network);JSONArray events=(JSONArray)field(engine,"events"),last=new JSONArray();
      synchronized(events){for(int i=Math.max(0,events.length()-30);i<events.length();i++)last.put(events.get(i));}
      JSONArray initial=new JSONArray();synchronized(events){for(int i=0;i<events.length();i++){JSONObject e=events.getJSONObject(i);String v=String.valueOf(e.opt("value"));if(v.contains("LIFECYCLE")||v.contains("UserScript")||e.optString("kind").equals("error"))initial.put(e);}}try{synchronized(engine){for(Object entry:(Collection<?>)field(engine,"consoleEvents"))initial.put(entry);}}catch(ReflectiveOperationException ignored){}result.put("initialDiagnostics",initial);
      try{synchronized(engine){last=new JSONArray((Collection<?>)field(engine,"recentEvents"));}}catch(ReflectiveOperationException ignored){}
      result.put("recentEvents",last);Files.writeString(evidence.resolve("page-"+phase+".json"),result.toString(2));
    }catch(Exception ignored){} // Diagnostic capture never changes the playback result.
  }
  public static void main(String[] args) throws Exception {
    xiao.bu.tv.WindowsSiteConfiguration.install();
    String trace=System.getProperty("ntv.traceScript","");
    if(!trace.isEmpty()){
      String diagnostic=Files.readString(Path.of(trace));
      java.lang.reflect.Field scripts=Class.forName("xiao.bu.tv.LiteEngine").getDeclaredField("documentScripts");scripts.setAccessible(true);
      @SuppressWarnings("unchecked") java.util.function.Function<String,List<String>> original=(java.util.function.Function<String,List<String>>)scripts.get(null);
      WindowsCoreSession.installDocumentScripts(page->{List<String> joined=new ArrayList<>(original.apply(page));joined.add(diagnostic);return joined;});
    }
    evidence=Path.of(args[1]).getParent();
    CountDownLatch ready=new CountDownLatch(1);AtomicBoolean failed=new AtomicBoolean();
    AtomicInteger generation=new AtomicInteger();
    AtomicReference<Map.Entry<Integer,String>> mediaClock=new AtomicReference<>();
    WindowsCoreSession session=new WindowsCoreSession(args[0],Path.of(args[1]),new WindowsCoreSession.Listener() {
      public void media(String url,Map<String,String> headers) {
        media(url,headers,false);
      }
      public void media(String url,Map<String,String> headers,boolean virtualMse){media(url,headers,virtualMse,false);}
      public void media(String url,Map<String,String> headers,boolean virtualMse,boolean liveHls){
        delivered=url;
        record("handoff");
        int next=generation.incrementAndGet();mediaClock.set(new AbstractMap.SimpleImmutableEntry<>(next,url));
        System.out.println("MEDIA_JSON "+new JSONObject().put("url",url).put("headers",new JSONObject(headers)).put("generation",next).put("virtualMse",virtualMse).put("liveHls",liveHls));
        System.out.flush();ready.countDown();
      }
      public void failed(String message) {
        record("failure");
        failed.set(true);System.out.println("FAILED_JSON "+new JSONObject().put("message",message));
        System.out.flush();ready.countDown();
      }
    });
    active=session;
    try {
      session.start();
      if(!ready.await(75,TimeUnit.SECONDS))throw new IOException("No media handoff within 75 seconds");
      if(failed.get())throw new IOException("Web session reported failure");
      // Single-byte reads avoid Wine's unsupported available0 query on a
      // redirected Windows stdin pipe; the same bounded ASCII protocol works
      // on native Windows and does not change the production session.
      StringBuilder line=new StringBuilder();int value;
      while((value=System.in.read())>=0) {
        if(value=='\r')continue;
        if(value=='\n') {
          String command=line.toString();line.setLength(0);
          if(command.equals("STOP"))break;
          if(command.startsWith("SIZE ")){String[] parts=command.split(" ");Map.Entry<Integer,String> owned=mediaClock.get();if(owned!=null&&parts.length==4&&Integer.parseInt(parts[1])==owned.getKey())session.playbackMetadata(owned.getValue(),Integer.parseInt(parts[2]),Integer.parseInt(parts[3]));}
          if(command.startsWith("TIME ")){String[] parts=command.split(" ");Map.Entry<Integer,String> owned=mediaClock.get();if(owned!=null){if(parts.length==2)session.playbackTime(owned.getValue(),Double.parseDouble(parts[1]));else if(parts.length==3&&Integer.parseInt(parts[1])==owned.getKey())session.playbackTime(owned.getValue(),Double.parseDouble(parts[2]));}}
        } else {
          if(line.length()>=128)throw new IOException("Control line too long");
          line.append((char)value);
        }
      }
    } finally {
      record("stop");
      session.close();
      if(!session.awaitClosed(8000))throw new IOException("Native session did not close");
    }
  }
}
