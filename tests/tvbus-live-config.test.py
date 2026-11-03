"""Run the production live JSON adapter and local auth HTTP server against real fixtures."""
from pathlib import Path
import os, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
cache=Path.home()/'.gradle/caches/modules-2/files-2.1/org.json/json'
jars=[p for p in cache.rglob('json-*.jar') if not p.name.endswith(('-sources.jar','-javadoc.jar'))]
if not jars: raise RuntimeError('Run Gradle once to populate the org.json build dependency')
files={
'android/content/SharedPreferences.java':'''package android.content;public interface SharedPreferences {
 String getString(String k,String d);Editor edit();interface Editor {Editor putString(String k,String v);Editor remove(String k);boolean commit();void apply();}}
''',
'android/content/Context.java':'''package android.content;import java.util.*;public class Context {
 final Map<String,P> all=new HashMap<>();public Context getApplicationContext(){return this;}
 public SharedPreferences getSharedPreferences(String n,int mode){if(!all.containsKey(n))all.put(n,new P());return all.get(n);}
 static class P implements SharedPreferences,SharedPreferences.Editor {
 Map<String,String> values=new HashMap<>();public String getString(String k,String d){return values.containsKey(k)?values.get(k):d;}
 public SharedPreferences.Editor edit(){return this;}public SharedPreferences.Editor putString(String k,String v){values.put(k,v);return this;}
 public SharedPreferences.Editor remove(String k){values.remove(k);return this;}public boolean commit(){return true;}public void apply(){}
 }}''',
'xiao/bu/tv/PlaylistManager.java':'''package xiao.bu.tv;class PlaylistManager {static class Source {
 final String id,location;final boolean enabled;Source(String id,String url,boolean enabled){this.id=id;location=url;this.enabled=enabled;}}}''',
'xiao/bu/tv/TvbusPlugin.java':'''package xiao.bu.tv;class TvbusPlugin {static boolean matches(String value){return value!=null&&(value.toLowerCase(java.util.Locale.US).startsWith("tvbus://")||value.toLowerCase(java.util.Locale.US).startsWith("sop://"));}}''',
'xiao/bu/tv/NetworkClient.java':'''package xiao.bu.tv;class NetworkClient {static java.net.HttpURLConnection openComponent(java.net.URL u,boolean h,int timeout)throws java.io.IOException{return (java.net.HttpURLConnection)u.openConnection();}}''',
'xiao/bu/tv/LiveConfigCheck.java':r'''package xiao.bu.tv;
import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.atomic.*;import org.json.*;
import com.sun.net.httpserver.HttpServer;
public class LiveConfigCheck {
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 interface Action{void run()throws Exception;}
 static void rejected(Action action)throws Exception{try{action.run();throw new AssertionError("accepted invalid descriptor");}catch(IOException expected){}}
 public static void main(String[] args)throws Exception {
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  AtomicInteger reads=new AtomicInteger();AtomicReference<String> text=new AtomicReference<>("Group,#genre#\nOne,tvbus://CaseSensitive123\nRelative,../stream.ts\n");
  AtomicReference<String> authBody=new AtomicReference<>("{\"version\":1}");
  server.createContext("/resp.json", e->{byte[] b=authBody.get().getBytes("UTF-8");e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});
  server.createContext("/lists/child.txt", e->{reads.incrementAndGet();byte[] b=text.get().getBytes("UTF-8");e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});
  server.createContext("/name.txt", e->{byte[] b="provider-name".getBytes("UTF-8");e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});
  server.start();
  try {
   String base="http://127.0.0.1:"+server.getAddress().getPort()+"/root.json";
   JSONObject core=new JSONObject().put("so","./core/lib.so").put("name","./name.txt").put("pass","provider-value");
   JSONObject root=new JSONObject().put("lives",new JSONArray().put(new JSONObject().put("name","Provider").put("core",core).put("url","./lists/child.txt")));
   check(TvbusLiveConfig.canonical(new JSONObject("{\"so\":\"a\",\"name\":\"b\"}")).equals(TvbusLiveConfig.canonical(new JSONObject("{\"name\":\"b\",\"so\":\"a\"}"))));
   TvbusLiveConfig config=new TvbusLiveConfig(new android.content.Context());
   byte[] bytes=root.toString().getBytes("UTF-8");String expanded=new String(config.expand(bytes,base,true),"UTF-8");
   check(expanded.contains("tvbus://CaseSensitive123"));check(expanded.contains(base.replace("root.json","stream.ts")));
   List<PlaylistManager.Source> sources=Arrays.asList(new PlaylistManager.Source("one",base,true));
   JSONObject selected=config.find("one","tvbus://CaseSensitive123",sources);
   check(selected.getString("so").equals(base.replace("root.json","core/lib.so")));
   check(TvbusLiveConfig.runtimeCore(selected).getString("name").equals("provider-name"));
   check(selected.getString("pass").equals("provider-value"));
   JSONObject sop=new JSONObject().put("core",new JSONObject().put("broker_url","./broker")).put("groups",new JSONArray().put(new JSONObject().put("name","SOP").put("channels",new JSONArray().put(new JSONObject().put("name","SOP").put("url","sop://127.0.0.1:53812/6000")))));
   String sopLocation=base.replace("root.json","sop.json");config.expand(sop.toString().getBytes("UTF-8"),sopLocation,true);
   check(config.find("sop","sop://127.0.0.1:53812/6000",Arrays.asList(new PlaylistManager.Source("sop",sopLocation,true))).getString("broker_url").equals(base.replace("root.json","broker")));
   config.expand(bytes,base,false);check(reads.get()==1);
   text.set("Group,#genre#\nUpdated,tvbus://CaseSensitive123\n");
   check(new String(config.expand(bytes,base,true),"UTF-8").contains("Updated"));check(reads.get()==2);
   JSONObject inline=new JSONObject().put("core",new JSONObject().put("so","./other.so")).put("groups",new JSONArray().put(new JSONObject().put("name","Group").put("channels",new JSONArray().put(new JSONObject().put("name","Two").put("urls",new JSONArray().put("tvbus://CaseSensitive123"))))));
   String other=base.replace("root.json","other.json");config.expand(inline.toString().getBytes("UTF-8"),other,true);
   List<PlaylistManager.Source> both=Arrays.asList(sources.get(0),new PlaylistManager.Source("two",other,true));
   rejected(()->config.find("","tvbus://CaseSensitive123",both));check(config.find("one","tvbus://CaseSensitive123",both).getString("so").endsWith("/core/lib.so"));
   rejected(()->TvbusLiveConfig.normalizeCore(new JSONObject().put("so","file:///private/lib.so"),base));
   rejected(()->config.expand("{broken}".getBytes("UTF-8"),base,true));
   check(!config.find("one","tvbus://CaseSensitive123",sources).toString().equals("{}"));
   config.expand("One,tvbus://CaseSensitive123".getBytes("UTF-8"),base,true);
   check(config.find("one","tvbus://CaseSensitive123",sources).length()==0);
   try(TvbusAuthReply auth=new TvbusAuthReply("{\"fixture\":true}")) {
    HttpURLConnection connection=(HttpURLConnection)new URL(auth.url()+"?request=1").openConnection();
    connection.setDoOutput(true);connection.getOutputStream().write("request".getBytes("UTF-8"));
    check(connection.getResponseCode()==200);byte[] b=new byte[100];int count=connection.getInputStream().read(b);
    check(new String(b,0,count,"UTF-8").equals("{\"fixture\":true}"));connection.disconnect();
    connection=(HttpURLConnection)new URL(auth.url()+"-invalid").openConnection();check(connection.getResponseCode()==404);connection.disconnect();
   }
   try(TvbusAuthReply auth=new TvbusAuthReply(base.replace("root.json","resp.json"))) {
    for(int version=1;version<=2;version++){
     authBody.set("{\"version\":"+version+"}");
     HttpURLConnection c=(HttpURLConnection)new URL(auth.url()).openConnection();
     byte[] b=new byte[100];int count=c.getInputStream().read(b);
     check(new String(b,0,count,"UTF-8").equals(authBody.get()));c.disconnect();
    }
   }
   System.out.println("PASS live JSON: nested lists, relative paths, provider core, URL-valued fields, offline cache, refresh, source isolation, invalid input, stale core removal, loopback auth POST and rotating provider response");
  } finally {server.stop(0);}
 }
}'''
}
for name in ['TvbusLiveConfig.java','TvbusAuthReply.java']:
    files['xiao/bu/tv/'+name]=(root/'app/src/main/java/xiao/bu/tv'/name).read_text(encoding='utf-8')
with tempfile.TemporaryDirectory() as temp:
    for name,text in files.items():
        p=Path(temp)/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8')
    jdk=Path(os.environ.get('JAVA_HOME',root/'.codex-tmp/jdk8/runtime/jdk8u504-b01'))/'bin'
    subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-cp',str(jars[0]),'-d',temp]+[str(Path(temp)/n) for n in files],check=True)
    subprocess.run([str(jdk/'java.exe'),'-cp',temp+os.pathsep+str(jars[0]),'xiao.bu.tv.LiveConfigCheck'],check=True)
