"""Exercise the production resolver with fragmented/redirected HTTP responses."""
import os, subprocess
from pathlib import Path
root = Path(__file__).resolve().parent.parent
out = root / '.codex-tmp/http-content-routing'; out.mkdir(parents=True, exist_ok=True)
source = (root/'app/src/main/java/xiao/bu/tv/HttpStreamResolver.java').read_text(encoding='utf-8')
(out/'HttpStreamResolver.java').write_text(source, encoding='utf-8')
check = r'''package xiao.bu.tv;
import java.io.*;import java.net.*;import java.util.*;
class NetworkClient {
 static HttpURLConnection openBounded(URL url,int timeout)throws IOException {if(timeout<=0||timeout>12000)throw new AssertionError("Unbounded probe");return open(url);}
 static Map<String,Reply> replies=new HashMap<>();static int bytesRead,opens,closed;
 static class Reply {int status=200;String type,location;byte[] body;int chunk=65536,failures,length=-1;
  Reply(String t,byte[] b){type=t;body=b;}}
 static HttpURLConnection open(URL url)throws IOException {
  final Reply r=replies.get(url.toString());if(r==null)throw new IOException("Unexpected URL "+url);opens++;
  return new HttpURLConnection(url){
   public void connect(){}public void disconnect(){closed++;}public boolean usingProxy(){return false;}
   public int getContentLength(){return r.length;}
   public int getResponseCode()throws IOException{if(r.failures-->0)throw new EOFException("socket closed before headers");return r.status;}public String getContentType(){return r.type;}
   public String getHeaderField(String key){return "Location".equals(key)?r.location:null;}
   public InputStream getInputStream(){return new ByteArrayInputStream(r.body){
    public synchronized int read(byte[] b,int off,int len){int n=super.read(b,off,Math.min(len,r.chunk));if(n>0)bytesRead+=n;return n;}
   };}
  };
 }
}
public class HttpContentRoutingCheck {
 static byte[] text(String s)throws Exception{return s.getBytes("UTF-8");}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static HttpStreamResolver.Result test(String path,String mime,byte[] body,int chunk)throws Exception {
  String url="http://fixture.test/"+path;NetworkClient.Reply reply=new NetworkClient.Reply(mime,body);reply.chunk=chunk;
  NetworkClient.replies.put(url,reply);NetworkClient.bytesRead=0;NetworkClient.opens=NetworkClient.closed=0;
  HttpStreamResolver.Result result=HttpStreamResolver.resolve(url);
  check(NetworkClient.opens==NetworkClient.closed,"Connection leak");return result;
 }
 public static void main(String[] args)throws Exception {
  check(HttpStreamResolver.shouldResolve("https://test/live.m3u8"),"Known HLS must verify response");
  check(!HttpStreamResolver.shouldResolve("rtsp://test/live"),"Do not HTTP-probe RTSP");
  check(!HttpStreamResolver.shouldResolve("rtp://239.3.1.129:8008"),"Do not HTTP-probe RTP multicast");
  check(!HttpStreamResolver.shouldResolve("udp://239.3.1.129:8008"),"Do not HTTP-probe UDP multicast");
  byte[] page=text("\ufeff <!-- leading --> <!DoCtYpE HTML><html><a href='https://media.test/other.m3u8'>Example</a></html>");
  for(String mime:new String[]{"text/html; charset=utf-8","application/octet-stream","video/mp4","application/vnd.apple.mpegurl"}) {
   HttpStreamResolver.Result r=test("misleading.m3u8",mime,page,2);check(r.webPage&&!r.directMedia,"HTML must beat suffix and MIME: "+mime);
   check(r.htmlMimeOverride==!mime.startsWith("text/html"),"Only override an incorrect HTML MIME");
  }
  HttpStreamResolver.Result r=test("no-suffix","text/html",text("#EXTM3U\n#EXTINF:5,\npart.ts\n"),1);
  check(!r.webPage&&!r.directMedia,"HLS body must beat HTML MIME");

  byte[] manifest=text("#EXTM3U\n#EXTINF:2,\nopaque.php?token=1\n#EXT-X-ENDLIST\n");
  r=test("huya.php?id=room","application/vnd.apple.mpegurl",manifest,1);
  check(r.url.endsWith("huya.php?id=room")&&Arrays.equals(r.playlist,text(new String(manifest,"UTF-8").trim())),"Complete API manifest handed off under exact URL");
  check(!r.webPage&&!r.directMedia&&r.playlistReceivedNanos>0,"HLS snapshot kept classification and timestamp");
  byte[] oversized=new byte[140000];Arrays.fill(oversized,(byte)' ');System.arraycopy(manifest,0,oversized,0,manifest.length);
  r=test("large.php","application/vnd.apple.mpegurl",oversized,1024);
  check(r.playlist==null&&NetworkClient.bytesRead<=128*1024+1,"Oversized manifest handoff bounded without truncation");
  r=test("video.sdp","application/sdp",text("v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=RTP fixture\r\nm=video 40120 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n"),2);
  check(r.directMedia&&!r.webPage,"SDP must reach the native RTP demuxer");
  r=test("fake.sdp","application/sdp",page,2);check(r.webPage&&!r.directMedia,"HTML still beats SDP MIME");
  byte[] mp4={0,0,0,24,'f','t','y','p','i','s','o','m',0,0,0,0};
  r=test("movie.html","text/html",mp4,1);check(r.directMedia&&!r.webPage,"MP4 body must beat HTML MIME");
  byte[] ts=new byte[1024];ts[0]=ts[188]=ts[376]=0x47;
  r=test("stream","application/octet-stream",ts,2);check(r.directMedia&&!r.webPage,"Extensionless TS");
  r=test("stream","text/plain",new byte[]{'F','L','V',1,0,0},1);check(r.directMedia&&!r.webPage,"FLV magic");
  check(r.continuousMedia,"Unbounded FLV must retain live reconnect policy");
  NetworkClient.Reply liveRedirect=new NetworkClient.Reply(null,new byte[0]);liveRedirect.status=302;liveRedirect.location="http://fixture.test/stream";
  NetworkClient.replies.put("http://fixture.test/douyu.php?id=747764",liveRedirect);
  r=HttpStreamResolver.resolve("http://fixture.test/douyu.php?id=747764");
  check(r.continuousMedia&&r.url.equals("http://fixture.test/stream")&&r.playbackUrl.equals("http://fixture.test/douyu.php?id=747764"),"Live playback must reopen the original redirect endpoint, not a consumed CDN session");
  r=test("flv-api.php","application/json",text("{\"url\":\"http://fixture.test/douyu.php?id=747764\"}"),2);
  check(r.playbackUrl.equals("http://fixture.test/douyu.php?id=747764"),"JSON wrapper must reopen its media entry, not feed JSON to the player");
  NetworkClient.replies.get("http://fixture.test/stream").length=6;
  check(!HttpStreamResolver.resolve("http://fixture.test/stream").continuousMedia,"Finite FLV must keep file completion policy");
  r=test("movie.mp4","video/mp4",mp4,1);check(!r.continuousMedia,"MP4 must keep VOD policy even without Content-Length");
  r=test("page","application/xhtml+xml",text("<?xml version='1.0'?><html xmlns='http://www.w3.org/1999/xhtml'></html>"),1);
  check(r.webPage,"XHTML");
  r=test("utf16.m3u8","application/octet-stream","<!doctype html><html>test</html>".getBytes("UTF-16"),1);
  check(r.webPage,"UTF-16 HTML with a misleading suffix");
  String redirected="http://fixture.test/final.html";
  NetworkClient.Reply redirect=new NetworkClient.Reply(null,new byte[0]);redirect.status=302;redirect.location="/final.html";
  NetworkClient.replies.put("http://fixture.test/go.mp4",redirect);
  test("final.html","text/html",page,1024);
  r=HttpStreamResolver.resolve("http://fixture.test/go.mp4");check(r.webPage&&redirected.equals(r.url),"Redirected page target");
  test("video.m3u8","text/plain",text("#EXTM3U\n#EXTINF:5,\npart.ts"),1024);
  r=test("api.php","text/html",text("{\"url\":\"http://fixture.test/video.m3u8\"}"),3);
  check(!r.webPage&&r.url.endsWith("video.m3u8"),"Mislabeled JSON wrapper must still resolve");
  r=test("aikan.php?id=live","text/html; charset=UTF-8",text("url = 'http://fixture.test/video.m3u8'"),1);
  check(!r.webPage&&!r.directMedia&&r.url.endsWith("video.m3u8"),"PHP text wrapper must beat HTML MIME");
  r=test("fragment.php","text/html",text("<!-- fragment --><div class='player'>Live player</div>"),1);
  check(r.webPage,"Actual HTML fragments still open in browser");
  try{
   test("aikan.php?id=unavailable","text/html; charset=UTF-8",text("获取播放地址失败"),1);
   throw new AssertionError("PHP upstream error became an HTML page");
  }catch(IOException expected){check(expected.getMessage().contains("获取播放地址失败"),"Preserve upstream failure message");}
  check(NetworkClient.opens==NetworkClient.closed,"PHP retry connection leak");
  try{
   test("plain.php","text/html",text("Not a webpage or a stream"),2);
   throw new AssertionError("Plain text became HTML due to MIME");
  }catch(IOException expected){}
  r=test("api-bom.php","video/mp4",text("\ufeff{\"url\":\"http://fixture.test/video.m3u8\"}"),3);
  check(!r.webPage&&!r.directMedia&&r.url.endsWith("video.m3u8"),"BOM JSON must beat wrong media MIME");
  r=test("api-page","application/json",text("{\"url\":\"http://fixture.test/final.html\"}"),3);
  check(r.webPage&&redirected.equals(r.url),"Wrapper can resolve to a page");
  byte[] huge=new byte[1000000];Arrays.fill(huge,(byte)'x');System.arraycopy(page,0,huge,0,page.length);
  r=test("huge","application/octet-stream",huge,65536);check(r.webPage&&NetworkClient.bytesRead<=1024,"Page probe must stay bounded");
  NetworkClient.Reply bad=new NetworkClient.Reply("text/html",page);bad.status=404;
  NetworkClient.replies.put("http://fixture.test/missing.m3u8",bad);
  try{HttpStreamResolver.resolve("http://fixture.test/missing.m3u8");throw new AssertionError("404 must not look playable");}catch(IOException expected){}
  NetworkClient.Reply transientReply=new NetworkClient.Reply("audio/mpeg",text("ID3 media fixture"));transientReply.failures=1;
  NetworkClient.replies.put("http://fixture.test/transient.mp3",transientReply);NetworkClient.opens=NetworkClient.closed=0;
  r=HttpStreamResolver.resolve("http://fixture.test/transient.mp3");
  check(r.directMedia && NetworkClient.opens==2 && NetworkClient.closed==2,"EOF retry must recover and close both connections");
  Thread.currentThread().interrupt();
  try{HttpStreamResolver.resolve("http://fixture.test/final.html");throw new AssertionError("Cancelled request continued");}catch(InterruptedIOException expected){}finally{Thread.interrupted();}
  System.out.println("PASS content routing: HTML/HLS/binary priority, fragmented prefix, redirects, JSON wrappers, bounded page probe, HTTP error and connection cleanup");
 }
}'''
(out/'HttpContentRoutingCheck.java').write_text(check,encoding='utf-8')
b=Path(os.environ['JAVA_HOME'])/'bin'
subprocess.run([str(b/'javac.exe'),'-encoding','UTF-8','-d',str(out),str(out/'HttpStreamResolver.java'),str(out/'HttpContentRoutingCheck.java')],check=True)
subprocess.run([str(b/'java.exe'),'-cp',str(out),'xiao.bu.tv.HttpContentRoutingCheck'],check=True)
