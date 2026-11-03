"""Quality inspection must not make the player download the same fresh manifest twice."""
from pathlib import Path
import os,subprocess,tempfile
root=Path(__file__).resolve().parents[1]
source=(root/'app/src/main/java/xiao/bu/tv/HlsProxyServer.java').read_text(encoding='utf8')
def method(signature):
    a=source.index(signature);b=source.index('{',a)+1;depth=1
    while depth:depth+=(source[b]=='{')-(source[b]=='}');b+=1
    return source[a:b]
java=r'''package xiao.bu.tv;
import java.io.*;import java.net.*;import java.nio.charset.*;
class VariantHandoffCheck {
 static final Charset UTF_8=StandardCharsets.UTF_8;
 static final int MAX_PLAYLIST_RESPONSE_BYTES=262144;
 static class SystemClock {static long now=100;static long elapsedRealtime(){return now;}}
 static class Variant{}
 static class Resolution{}
 static class VariantCandidate {boolean available;Resolution actual;VariantCandidate(Variant v,boolean a,Resolution r){available=a;actual=r;}}
 static class ProxyResponse {String type;byte[] body;ProxyResponse(String t,byte[] b){type=t;body=b;}}
 HlsStartupPlaylistCache startupPlaylists=new HlsStartupPlaylistCache(),resolvedPlaylists=new HlsStartupPlaylistCache();
 boolean running=true,playbackSuspended,probeFails;int inspectionGets,networkGets,tsGets,rewrites,status=200;long probeDelay;
 String responseUrl="https://test/live/child.m3u8",segmentUrl;
 byte[] body="#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:10\n#EXTINF:4,\n10.ts\n".getBytes(UTF_8);
 HttpURLConnection openUpstreamConnection(String url)throws IOException {
  inspectionGets++;
  return new HttpURLConnection(new URL(responseUrl)){
   public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}
   public int getResponseCode(){return status;}
   public InputStream getInputStream(){return new ByteArrayInputStream(body);}
  };
 }
 void applyRequestHeaders(HttpURLConnection c,String url){}
 int contentLength(HttpURLConnection c){return body.length;}
 byte[] readUpstreamFully(InputStream in,int length,int max)throws IOException{return in.readAllBytes();}
 Resolution probeTransportStreamResolution(String url)throws IOException {
  tsGets++;segmentUrl=url;SystemClock.now+=probeDelay;if(probeFails)throw new IOException("TS probe failed");return new Resolution();
 }
 String rewritePlaylist(String policy,String base,String text){rewrites++;return "rewritten:"+text;}
 void startManifestAhead(String url){}
 ProxyResponse takeManifestAhead(String url){return null;}
 ProxyResponse fetchNetwork(String url){networkGets++;return new ProxyResponse("application/vnd.apple.mpegurl",body);}
 METHODS
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception {
  String url="https://test/live/child.m3u8";Variant v=new Variant();
  VariantHandoffCheck p=new VariantHandoffCheck();
  check(p.inspectVariant(url,v).available,"quality check");
  check(p.inspectionGets==1&&p.tsGets==1,"resolution verification retained");
  check(new String(p.fetch(url).body,UTF_8).startsWith("rewritten:#EXTM3U"),"cached body still rewritten through live/decrypt policy");
  check(p.networkGets==0&&p.rewrites==1,"duplicate child GET before playback");
  p.fetch(url);check(p.networkGets==1,"one use; reload must fetch live data");
  p=new VariantHandoffCheck();p.probeDelay=1000;p.inspectVariant(url,v);p.fetch(url);
  check(p.networkGets==1,"slow TS inspection must not refresh manifest age");
  p=new VariantHandoffCheck();p.inspectVariant(url,v);SystemClock.now+=999;p.fetch(url);
  check(p.networkGets==0,"fresh bound");
  p=new VariantHandoffCheck();p.responseUrl="https://edge/redirect/live.m3u8";p.inspectVariant(url,v);p.fetch(url);
  check(p.networkGets==1&&p.segmentUrl.equals("https://edge/redirect/10.ts"),"redirect keeps final base and normal fetch path");
  p=new VariantHandoffCheck();p.status=503;check(!p.inspectVariant(url,v).available,"unavailable rendition");p.fetch(url);check(p.networkGets==1,"failed response cannot be handed off");
  p=new VariantHandoffCheck();p.probeFails=true;check(!p.inspectVariant(url,v).available,"probe failure");p.fetch(url);check(p.networkGets==1,"failed quality probe not reused");
  p=new VariantHandoffCheck();p.body="<html>error</html>".getBytes(UTF_8);p.inspectVariant(url,v);p.fetch(url);check(p.networkGets==1,"non-playlist not reused");
  p=new VariantHandoffCheck();p.inspectVariant(url,v);p.fetch("https://test/other.m3u8");check(p.networkGets==1,"exact rendition URL identity");
  VariantHandoffCheck other=new VariantHandoffCheck();other.fetch(url);check(other.networkGets==1,"another channel proxy cannot consume this manifest");
  p=new VariantHandoffCheck();p.inspectVariant(url,v);p.running=false;
  try{p.fetch(url);throw new AssertionError("closed proxy");}catch(SocketException expected){}
  p=new VariantHandoffCheck();p.inspectVariant(url,v);p.playbackSuspended=true;
  try{p.fetch(url);throw new AssertionError("suspended proxy");}catch(InterruptedIOException expected){}
  System.out.println("PASS actual variant inspection/fetch: single GET, one-use live reload, TTL/slow probe, exact proxy identity, redirect, failed/invalid response and cancel guards");
 }
}'''
java=java.replace('METHODS','\n'.join(method(s)for s in [
 '    private VariantCandidate inspectVariant(', '    private static String firstMediaSegment(',
 '    private static boolean isPlaylistBody(', '    private ProxyResponse fetch(']))
with tempfile.TemporaryDirectory(prefix='ntv-variant-')as folder:
    target=Path(folder)/'VariantHandoffCheck.java';target.write_text(java,encoding='utf8')
    jdk=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
    subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',folder,str(target),
      str(root/'app/src/main/java/xiao/bu/tv/HlsStartupPlaylistCache.java'),str(root/'app/src/main/java/xiao/bu/tv/HlsUri.java')],check=True)
    subprocess.run([str(jdk/'java.exe'),'-cp',folder,'xiao.bu.tv.VariantHandoffCheck'],check=True)
