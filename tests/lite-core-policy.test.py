"""Exercise domain-free startup transitions and real HLS relay/clock boundaries."""
from pathlib import Path
import os, re, subprocess, tempfile

root = Path(__file__).resolve().parents[1]
core = root / 'browser-core/litebrowser/src/main/java/xiao/bu/tv'
# Keep app behavior out of the reusable Java/JS artifact. Package names and
# DOM namespace identifiers are not website policy.
allowed={'xiao.bu.tv','java.net','javax.net','android.net','www.w3.org'}
for directory,glob in [(core,'*.java'),(core.parents[3]/'assets','*.js')]:
    assert directory.is_dir(),directory
    for path in directory.rglob(glob):
        domains=set(re.findall(r'[a-zA-Z0-9_-]+(?:\.[a-zA-Z0-9_-]+)*\.(?:com|cn|net|tv|org)\b',path.read_text('utf8')))
        assert domains<=allowed,(path,domains-allowed)
assert not (core.parents[3]/'assets/sites.js').exists()
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
def jar(group, name):
    return next(p for p in (cache/group/name).rglob('*.jar') if not p.name.endswith(('-sources.jar', '-javadoc.jar')))
cp = os.pathsep.join(str(jar(g, n)) for g, n in [('com.squareup.okhttp3', 'okhttp'), ('com.squareup.okio', 'okio')])
java = r'''package xiao.bu.tv;
import java.io.*;import java.util.*;import java.lang.reflect.*;import okhttp3.*;
class Check {
 static void ok(boolean b){if(!b)throw new AssertionError();}
 static LiteStartupState state(long ready,long progress,int pending,boolean busy,boolean media,boolean interaction){return new LiteStartupState(ready,progress,200,pending,busy,media,interaction);}
 static void startup(){
  long start=1000;
  LiteStartupState fetching=state(2000,31000,1,false,false,false);
  LiteStartupState ordinary=state(2000,0,0,false,false,false);
  String loader="Promise rejection: Error: Loader timeout";
  ok(LiteWebStartupPolicy.retryDependency(loader,0,ordinary,false,false,false));
  ok(!LiteWebStartupPolicy.retryDependency(loader,1,ordinary,false,false,false));
  ok(!LiteWebStartupPolicy.retryDependency(loader,0,ordinary,true,false,false));
  ok(!LiteWebStartupPolicy.retryDependency(loader,0,ordinary,false,true,false));
  ok(!LiteWebStartupPolicy.retryDependency(loader,0,ordinary,false,false,true));
  ok(!LiteWebStartupPolicy.retryDependency(loader,0,state(2000,0,0,false,false,true),false,false,false));
  ok(!LiteWebStartupPolicy.retryDependency("HTTP 403",0,ordinary,false,false,false));
  ok(!LiteWebStartupPolicy.retryDependency(loader,0,new LiteStartupState(2000,0,403,0,false,false,false),false,false,false));

  ok(LiteWebStartupPolicy.deadline(start,31000,fetching,false)==LiteWebStartupPolicy.Decision.WAIT);
  ok(LiteWebStartupPolicy.render(start,31000,fetching,false)==LiteWebStartupPolicy.Decision.WAIT);
  ok(LiteWebStartupPolicy.deadline(start,46000,fetching,false)==LiteWebStartupPolicy.Decision.RENDER);
  ok(LiteWebStartupPolicy.render(start,46000,fetching,false)==LiteWebStartupPolicy.Decision.RENDER);
  ok(LiteWebStartupPolicy.deadline(start,13000,fetching,true)==LiteWebStartupPolicy.Decision.FAIL);
  ok(LiteWebStartupPolicy.render(start,11000,state(2000,0,0,false,false,false),false)==LiteWebStartupPolicy.Decision.WAIT);
  ok(LiteWebStartupPolicy.render(start,12000,state(2000,0,0,false,false,false),false)==LiteWebStartupPolicy.Decision.RENDER);
  for(LiteStartupState s:new LiteStartupState[]{state(2000,0,0,true,false,false),state(2000,0,0,false,true,false),state(2000,30000,0,false,false,false)})
   ok(LiteWebStartupPolicy.deadline(start,31000,s,false)==LiteWebStartupPolicy.Decision.WAIT);
  ok(LiteWebStartupPolicy.render(start,3000,state(2000,0,1,true,false,true),false)==LiteWebStartupPolicy.Decision.RENDER);
  ok(LiteWebStartupPolicy.render(start,3000,fetching,true)==LiteWebStartupPolicy.Decision.RENDER);
  ok(LiteWebStartupPolicy.deadline(start,46000,state(0,45000,1,true,false,false),false)==LiteWebStartupPolicy.Decision.FAIL);
  ok(LiteWebStartupPolicy.deadline(start,31000,new LiteStartupState(2000,31000,500,1,true,false,false),false)==LiteWebStartupPolicy.Decision.FAIL);
  // A redirect's newer ready time cannot reset the overall hard deadline.
  ok(LiteWebStartupPolicy.render(start,46000,state(45000,45000,1,true,false,false),false)==LiteWebStartupPolicy.Decision.RENDER);
 }
 static byte[] packet(long pts){
  byte[] p=new byte[188];Arrays.fill(p,(byte)0xff);p[0]=0x47;p[1]=0x40;p[2]=0x10;p[3]=0x10;
  int at=4;p[at]=0;p[at+1]=0;p[at+2]=1;p[at+3]=(byte)0xe0;p[at+4]=0;p[at+5]=0;p[at+6]=(byte)0x80;p[at+7]=(byte)0x80;p[at+8]=5;
  stamp(p,at+9,pts);return p;
 }
 static void stamp(byte[] p,int at,long v){p[at]=(byte)(0x21|((v>>29)&14));p[at+1]=(byte)(v>>22);p[at+2]=(byte)(((v>>14)&254)|1);p[at+3]=(byte)(v>>7);p[at+4]=(byte)(((v<<1)&254)|1);}
 static byte[] copy(LiteTsTimeline t,String key,String group,long seq,byte[] input)throws Exception{
  t.register(key,group,seq,90000);ByteArrayOutputStream out=new ByteArrayOutputStream();
  t.copy(new BufferedInputStream(new ByteArrayInputStream(input)),out,t.get(key));return out.toByteArray();
 }
 static long pts(byte[] p){return LiteTsTimeline.stamp(p,13);}
 static void clocks()throws Exception{
  LiteTsTimeline t=new LiteTsTimeline();
  byte[] first=packet(900000),normal=packet(990000),reset=packet(0);
  ok(Arrays.equals(first,copy(t,"a","muxed",1,first)));
  ok(Arrays.equals(normal,copy(t,"b","muxed",2,normal)));
  byte[] fixed=copy(t,"c","muxed",3,reset);ok(pts(fixed)==1080000);
  for(int i=18;i<188;i++)ok(fixed[i]==reset[i]);
  ok(pts(copy(t,"retry","muxed",4,packet(90000)))==1170000);
  ok(Arrays.equals(reset,copy(t,"variant","different-playlist",4,reset)));
  ok(Arrays.equals(reset,copy(t,"gap","muxed",100,reset)));
  t=new LiteTsTimeline();copy(t,"wrap-a","muxed",1,packet(LiteTsTimeline.MASK-90000));
  ok(Arrays.equals(reset,copy(t,"wrap-b","muxed",2,reset)));
  byte[] mp4=new byte[]{0,0,0,24,'f','t','y','p',1,2,3};
  ok(Arrays.equals(mp4,copy(new LiteTsTimeline(),"mp4","muxed",1,mp4)));
  byte[] partial=Arrays.copyOf(first,99);ok(Arrays.equals(partial,copy(new LiteTsTimeline(),"partial","muxed",1,partial)));
  t.disable();ok(t.get("wrap-a")==null);t.register("new","muxed",3,90000);ok(t.get("new")==null);
 }
 static void manifests()throws Exception{
  Method rewrite=LiteMediaRelay.class.getDeclaredMethod("rewrite",String.class,HttpUrl.class);rewrite.setAccessible(true);
  Field field=LiteMediaRelay.class.getDeclaredField("timeline");field.setAccessible(true);
  Method whole=LiteMediaRelay.class.getDeclaredMethod("wholeResource",String.class,long.class);whole.setAccessible(true);
  ok((Boolean)whole.invoke(null,"bytes 0-187/188",188L));ok(!(Boolean)whole.invoke(null,"bytes 1-187/188",187L));ok(!(Boolean)whole.invoke(null,"bytes 0-99/188",100L));
  OkHttpClient client=new OkHttpClient();
  try{
   for(String ext:new String[]{"flv","mp4","mp3","aac","ts","mkv","webm","mov","avi"}){
    LiteMediaRelay direct=new LiteMediaRelay(client,"https://arbitrary.example/live."+ext+"?token=exact",Collections.emptyMap(),false);
    try{ok(direct.url().endsWith("."+ext));ok(MainActivityClassifier.direct(direct.url()));}finally{direct.close();}
   }
   LiteMediaRelay identified=new LiteMediaRelay(client,"https://arbitrary.example/play?id=7",Collections.emptyMap(),false,true);
   try{ok(identified.url().endsWith(".flv"));ok(MainActivityClassifier.direct(identified.url()));}finally{identified.close();}
   for(String tag:new String[]{"", "#EXT-X-KEY:METHOD=NONE", "#EXT-X-DISCONTINUITY", "#EXT-X-MAP:URI=\"init.mp4\"", "#EXT-X-BYTERANGE:188@0", "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"", "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\""}){
    LiteMediaRelay relay=new LiteMediaRelay(client,"https://arbitrary.example/live.m3u8",Collections.emptyMap(),false);
    try{
     String m="#EXTM3U\n"+tag+"\n#EXT-X-MEDIA-SEQUENCE:1\n#EXTINF:1,\nmedia?id=1\n";
     String local=(String)rewrite.invoke(relay,m,HttpUrl.parse("https://arbitrary.example/live.m3u8"));
     ok(local.contains("http://127.0.0.1:")&&!local.contains("\nmedia?id="));
     LiteTsTimeline t=(LiteTsTimeline)field.get(relay);
     String key="https://arbitrary.example/media?id=1\nplaylist=https://arbitrary.example/live.m3u8\nsequence=1";
     ok((t.get(key)!=null)==(tag.isEmpty()||tag.equals("#EXT-X-KEY:METHOD=NONE")));
    }finally{relay.close();}
   }
  }finally{client.dispatcher().executorService().shutdownNow();client.connectionPool().evictAll();}
 }
 public static void main(String[] args)throws Exception{startup();clocks();manifests();System.out.println("PASS startup progress/interaction/hard cap, muxed TS reset/normal/wrap/retry/variant isolation and real relay clear/encrypted/discontinuity/fMP4/range guards");}
}'''
with tempfile.TemporaryDirectory() as folder:
    p=Path(folder)
    activity=(root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text('utf8')
    at=activity.index('    private static boolean isDirectHttpMediaSource(')
    classify=activity[at:activity.index('\n    }',at)+6]
    stubs={
        'android/os/SystemClock.java':'package android.os;public class SystemClock {public static long elapsedRealtime(){return System.nanoTime()/1000000;}}',
        'android/util/Log.java':'package android.util;public class Log {public static int i(String t,String m){return 0;}}',
        'android/net/Uri.java':'package android.net;public class Uri {java.net.URI uri;public static Uri parse(String s){Uri u=new Uri();u.uri=java.net.URI.create(s);return u;}public String getPath(){return uri.getPath();}}',
        'xiao/bu/tv/MainActivityClassifier.java':'package xiao.bu.tv;import android.net.Uri;import java.util.Locale;class MainActivityClassifier {'+classify+' static boolean direct(String s){return isDirectHttpMediaSource(s);}}',
        'xiao/bu/tv/LiteEngine.java':'package xiao.bu.tv;public class LiteEngine {public interface PlaybackTransport extends java.io.Closeable {String url();}}',
        'xiao/bu/tv/Check.java':java,
    }
    for name, source in stubs.items():
        f=p/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(source,encoding='utf8')
    sources=[core/(name+'.java') for name in ['LiteStartupState','LiteTsTimeline','LiteMediaRelay','LiteHlsSession','LiteLoopbackServer','LiteChunkedOutputStream']]
    sources.append(root/'app/src/main/java/xiao/bu/tv/LiteWebStartupPolicy.java')
    jdk=Path(os.environ['JAVA_HOME'])/'bin'
    subprocess.run([str(jdk/'javac'),'--release','8','-encoding','UTF-8','-cp',cp,'-d',folder,*map(str,sources),*map(str,p.rglob('*.java'))],check=True)
    subprocess.run([str(jdk/'java'),'-cp',folder+os.pathsep+cp,'xiao.bu.tv.Check'],check=True,timeout=20)
