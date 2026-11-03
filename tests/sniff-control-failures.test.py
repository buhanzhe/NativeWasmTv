"""Execute production sniff deadline, local source access and HTTP error handling."""
from pathlib import Path
import subprocess,tempfile
root=Path(__file__).resolve().parents[1];base=root/'app/src/main/java/xiao/bu/tv'
def read(name):return (base/name).read_text('utf-8')
def method(src,signature):
 a=src.index(signature);b=src.index('{',a);level=1;i=b+1
 while level:
  level+=(src[i]=='{')-(src[i]=='}');i+=1
 return src[a:i]
lite=read('LiteWebPlayback.java');pm=read('PlaylistManager.java');http=read('LocalControlServer.java');main=read('MainActivity.java')
fields=lite[lite.index('    private static final long SNIFF_TIMEOUT_MS'):lite.index('    private boolean virtualPlayback;')]
opening=lite[lite.index('    void open(String url, String script)'):lite.index('        try {',lite.index('    void open(String url, String script)'))]+ '    }'
guard=pm[pm.index('    byte[] readForMobile('):pm.index('        Source source = null;',pm.index('    byte[] readForMobile('))]+'        return new byte[0];\n    }'
handle=method(http,'    private void handle(Socket socket)')
java=r'''package xiao.bu.tv;
import java.io.*;import java.net.*;import java.util.*;
class Checks {
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 static class Sniff {
  interface Host {void failure(String v);} Host host; int failures;
  Sniff(){host=v->{check(closed);failures++;};}
  boolean closed, finalStream, streamActive, temporary;Object renderedPage;
  int navigations,generation;double lastPosition,lastDuration;Boolean controlPaused;
  boolean virtualPlayback,liveHlsPlayback;String controlledSource;
  int releases; Object renderFallback=new Object(),progress=new Object();
  static class Handler {long now;Map<Runnable,Long> jobs=new HashMap<>();
   void postDelayed(Runnable r,long delay){jobs.put(r,now+delay);}
   void removeCallbacks(Object r){jobs.remove(r);}
   void advance(long t){now=t;for(Runnable r:new ArrayList<>(jobs.keySet()))if(jobs.containsKey(r)&&jobs.get(r)<=now){jobs.remove(r);r.run();}}
  } Handler handler=new Handler();
  void releaseEngine(){releases++;}
  void close(){if(closed)return;closed=true;generation++;handler.jobs.clear();releaseEngine();}
  FIELDS
  OPEN
 }
 static class Guard {
  Object context=new Object();int reads;String lastLocation;
  byte[] readLocal(String value)throws IOException {
   reads++;lastLocation=value;
   if(value.equals("/missing.txt"))throw new FileNotFoundException("本地频道文件不存在");
   return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }
  GUARD
  LOCAL
  SUPPORTED
 }
 static class TvbusLiveConfig {
  TvbusLiveConfig(Object context){}
  byte[] expand(byte[] bytes,String location,boolean refresh){check(refresh);return bytes;}
 }
 static class Http {
  static final int SOCKET_TIMEOUT_MS=100,MAX_KU9_SCRIPT_BYTES=2000000;static final String TAG="test";
  static class Log {static void w(String a,String b,Exception e){}static void i(String a,String b){}}
  static class MediaFileDownload {}
  static class Listener {MediaFileDownload mediaDownload(String a,String b,String c){return null;}}
  Listener listener=new Listener(); int status;String message;
  void send(Socket s,int code,String type,byte[] body)throws IOException{status=code;message=new String(body,java.nio.charset.StandardCharsets.UTF_8);}
  byte[] jsonError(String v){return v.getBytes(java.nio.charset.StandardCharsets.UTF_8);}
  void handleTakeoverSession(Socket s,BufferedInputStream i){}
  void sendMediaDownload(Socket s,MediaFileDownload d){}
  void receiveBackgroundUpload(Socket s,InputStream i,boolean c,long n)throws Exception{throw new IOException("Unsupported in this fixture");}
  int requestBodyLimit(){return 1024;}
  byte[] readFixedBody(InputStream i,int n){return new byte[0];}
  byte[] readChunkedBody(InputStream i,int n){return new byte[0];}
  void route(Socket s,String method,String path,byte[] body)throws Exception{
   if(path.contains("channels"))throw new ControlRequestException(409,"频道分组已变化，请刷新后重试");
   if(path.contains("denied"))throw new ControlRequestException(403,"禁止读取");
   if(path.contains("json"))throw new org.json.JSONException();
   if(path.contains("internal"))throw new IOException("/system/etc/hosts sensitive");
   queryParameter(path,"x");status=200;
  }
  HANDLE
  LINE
  QUERY
  DECODE
  void request(String path){status=0;handle(new Socket(){
   public InputStream getInputStream(){return new ByteArrayInputStream(("GET "+path+" HTTP/1.1\r\n\r\n").getBytes());}
   public synchronized void setSoTimeout(int v){}public synchronized void close(){}
  });}
 }
 public static void main(String[] args)throws Exception{
  for(int scenario=0;scenario<5;scenario++){
   Sniff s=new Sniff();s.open("one","");s.handler.advance(30000);s.open("redirect","");
   if(scenario==1)s.renderedPage=new Object();
   if(scenario==2){s.temporary=true;s.streamActive=true;}
   if(scenario==3)s.finalStream=true;
   if(scenario==4)s.close();
   s.handler.advance(59999);check(s.failures==0);s.handler.advance(60000);
   check(s.failures==(scenario<3?1:0));s.handler.advance(120000);check(s.failures<=1);
  }
  Guard g=new Guard();for(String path:new String[]{"/sdcard/频道 列表.txt","file:///data/user/0/xiao.bu.tv/files/imported-playlists/local.playlist","content://provider/channels/123"," /storage/emulated/0/channels.m3u "}){
   check(new String(g.readForMobile(path),java.nio.charset.StandardCharsets.UTF_8).equals(path.trim()));
   check(g.lastLocation.equals(path.trim()));
  }
  check(g.reads==4);g.readForMobile("/sdcard/频道 列表.txt");check(g.reads==5);
  try{g.readForMobile("/missing.txt");throw new AssertionError();}catch(FileNotFoundException expected){}
  for(String invalid:new String[]{null," ","javascript:alert(1)"})try{g.readForMobile(invalid);throw new AssertionError();}catch(ControlRequestException e){check(e.status==400);}
  check(g.readForMobile("https://example.org/list.m3u").length==0);
  Http h=new Http();h.request("/api/playlist/channels");check(h.status==409);
  h.request("/denied");check(h.status==403);h.request("/json");check(h.status==400);
  h.request("/internal");check(h.status==500&&!h.message.contains("/system"));
  h.request("/api/state?x=%ZZ");check(h.status==400);
  h.request("/api/state?x="+String.join("",Collections.nCopies(9000,"x")));check(h.status==400);
  ChannelStartupTiming timing=new ChannelStartupTiming();timing.begin(1,100);timing.stage(1,"resolve-begin",110);timing.stage(1,"load-failed",60100);
  check(timing.display(1).contains("加载失败")&&!timing.display(1).contains("进行中"));
 }
}
'''
for key,value in {'FIELDS':fields,'OPEN':opening,'GUARD':guard,'LOCAL':method(pm,'    static boolean isLocalLocation('),'SUPPORTED':method(pm,'    private static boolean isSupportedLocation('),'HANDLE':handle,'LINE':method(http,'    private static String readLine('),'QUERY':method(http,'    private static String queryParameter('),'DECODE':method(http,'    private static String decodeQueryValue(')}.items():java=java.replace('  '+key,value)
with tempfile.TemporaryDirectory() as temp:
 d=Path(temp);(d/'Checks.java').write_text(java,encoding='utf-8');p=d/'org/json/JSONException.java';p.parent.mkdir(parents=True);p.write_text('package org.json; public class JSONException extends Exception {}')
 subprocess.run(['javac','-encoding','UTF-8','-d',temp,str(d/'Checks.java'),str(p),str(base/'ControlRequestException.java'),str(base/'ChannelStartupTiming.java')],check=True)
 subprocess.run(['java','-cp',temp,'xiao.bu.tv.Checks'],check=True,timeout=10)
print('PASS sniff deadline across redirects/render/preroll, local playlist access and reread, HTTP 400/403/409, error privacy, failed timing')
