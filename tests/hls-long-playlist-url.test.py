"""Exercise production player-URL creation and exact upstream resolution on the JVM."""
from pathlib import Path
import os, subprocess, tempfile

root = Path(__file__).resolve().parents[1]
source = (root / 'app/src/main/java/xiao/bu/tv/HlsProxyServer.java').read_text('utf8')
methods = source[source.index('    String proxyUrl('):source.index('    /** Keep the MP3 type')]
harness = r'''package xiao.bu.tv;
import java.util.*;import java.net.*;import java.nio.charset.*;
class LongPlaylistCheck {
 static final Charset UTF_8=Charset.forName("UTF-8");
 final String loopbackHost="127.1.1.1";
 final ServerSocket serverSocket;
 final HlsPlaylistAliases playlistAliases=new HlsPlaylistAliases();
 final Map<String,Boolean> recordingTokens=new HashMap<>();
 boolean carrierIptvSession;
 final Route carrierNetworkRoute=new Route();
 static class Route {void prepare(){}}
 static class CarrierNetworkRoute {static boolean isCarrierIptvUrl(String u){return false;}}
 static class Base64 {
  static final int NO_WRAP=1,URL_SAFE=2;
  static String encodeToString(byte[] b,int f){return java.util.Base64.getUrlEncoder().encodeToString(b);}
  static byte[] decode(String s,int f){return java.util.Base64.getUrlDecoder().decode(s);}
 }
 LongPlaylistCheck()throws Exception{serverSocket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));}
 METHODS
 static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
 public static void main(String[] a)throws Exception {
  LongPlaylistCheck p=new LongPlaylistCheck();
  try {
   String shortOrigin="http://cdn.test/live.m3u8?token=a%2Fb%2Bc&x=1";
   String old=p.proxyUrl(shortOrigin)+".m3u8";
   check(old.equals(p.systemPlayerHlsUrl(shortOrigin)),"short URL changed");
   for(int n:new int[]{700,800,1024,2048,8192}) {
    StringBuilder raw=new StringBuilder("http://cdn.test/channel.m3u8?vkey=");
    for(int i=0;i<n;i++)raw.append("aA9_-".charAt(i%5));
    raw.append("&signed=a%2Fb%2Bc%3D&time=1792674522");String origin=raw.toString();
    String url=p.systemPlayerHlsUrl(origin);
    check(url.length()<1024&&url.endsWith(".m3u8"),"IJK longurl still triggered");
    check(url.equals(p.systemPlayerHlsUrl(origin)),"reload URL not stable");
    String token=new URI(url).getPath().substring("/proxy/".length());
    for(int reload=0;reload<100;reload++)check(origin.equals(p.decodeProxyToken(token)),"signed URL changed on reload");
    check(origin.equals(p.probeOriginUrl(url)),"probe lost original URL");
    check(p.recordingTokens.containsKey(stripProxyTokenSuffix(token)),"recording alias not registered");
   }
   HlsPlaylistAliases bounded=new HlsPlaylistAliases();String active=bounded.token("active");
   for(int i=0;i<10000;i++){bounded.token("other-"+i);check("active".equals(bounded.resolve(active)),"active alias evicted");}
   try{bounded.resolve("~1");throw new AssertionError("old entry not evicted");}catch(IllegalArgumentException expected){}
   String stale=bounded.token("stale");bounded.clear();
   check(!stale.equals(bounded.token("new")),"expired token reused");
   try{bounded.resolve(stale);throw new AssertionError("expired alias accepted");}catch(IllegalArgumentException expected){}
   Thread[] threads=new Thread[8];Throwable[] errors=new Throwable[8];
   for(int i=0;i<threads.length;i++){final int index=i;threads[i]=new Thread(()->{try{
    for(int j=0;j<2000;j++){String t=p.playlistAliases.token("same");check("same".equals(p.playlistAliases.resolve(t)),"concurrent lookup");}
   }catch(Throwable e){errors[index]=e;}});threads[i].start();}
   for(Thread t:threads)t.join();for(Throwable e:errors)if(e!=null)throw new AssertionError(e);
   System.out.println("PASS long signed playlist URLs, repeated reloads, exact signatures, short URLs, probe/recording tokens, bounded concurrent cache and cleanup");
  }finally{p.serverSocket.close();}
 }
}'''.replace('METHODS', methods)
jdk = Path(os.environ.get('JAVA_HOME', 'C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
with tempfile.TemporaryDirectory() as temp:
    target = Path(temp)/'LongPlaylistCheck.java';target.write_text(harness, encoding='utf8')
    subprocess.run([str(jdk/'javac'), '-encoding', 'UTF-8', '-d', temp,
                    str(root/'app/src/main/java/xiao/bu/tv/HlsPlaylistAliases.java'),str(target)],check=True)
    subprocess.run([str(jdk/'java'), '-cp', temp, 'xiao.bu.tv.LongPlaylistCheck'],check=True)
