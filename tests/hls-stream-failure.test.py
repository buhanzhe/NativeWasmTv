"""Fault-inject real proxy handlers: no second HTTP response inside a broken video."""
import os,subprocess
from pathlib import Path
root=Path(__file__).resolve().parent.parent
src=(root/'app/src/main/java/xiao/bu/tv/HlsProxyServer.java').read_text(encoding='utf-8')
def method(start,end):return src[src.index(start):src.index(end,src.index(start))]
handle=method('    private void handle(Socket socket)', '    private static boolean isPlayerDisconnect(')
stream=method('    private void streamUpstream(', '    private static long contentLength(')
progress=method('    private volatile long lastMediaProgressAt;', '    private final AtomicLong upstreamDownloadedBytes')
progress+=method('    private final class UpstreamInputStream', '    private static byte[] readAtMost(')
prefetch=method('    private List<String> prefetchGenericSegments(', '    private boolean genericStartupReady(')
stream_predicate=method('    private boolean canStreamWithoutRewrite(', '    private void streamUpstream(')
stream_headers=method('    private static void writeStreamingHeaders(', '    private static void writeError(').replace('writeStreamingHeaders(', 'realStreamingHeaders(')
player_output=method('    private static boolean isPlayerDisconnect(', '    private ProxyResponse fetch(')
java=r'''import java.io.*;import java.net.*;import java.util.*;import java.util.regex.*;import java.nio.charset.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
public class HlsStreamFailureCheck {
 static class Build {static class VERSION {static final int SDK_INT=19;}}
 static class VideoScreenshot {static boolean needsSegmentCache(){return true;}}
 static class android {static class os {static class Build {static class VERSION {static final int SDK_INT=19;}}}}
 boolean sourceVideoProbeEnabled,byteRangeMediaPlaylist,playbackSuspended,finiteMedia,metadataProbeMode;byte[] screenshotSegment;long lastMediaSegmentServedAt;
 String keyToken(String token){return null;}
 String decodeProxyToken(String token){return new String(Base64.decode(token,0),UTF_8);}
 boolean needsCmgDecrypt(String url){return false;}
 boolean needsH5eDecrypt(String url){return false;}
 boolean supportsH5eStreaming(){return false;}
 void streamH5eSegment(String url,OutputStream output){throw new AssertionError("Unexpected H5E route in clear media fixture");}
 void streamCmgSegment(String url,OutputStream output){throw new AssertionError("Unexpected CMG route in clear media fixture");}
 final PlaybackReplayCache replayCache=new PlaybackReplayCache(null);
 boolean isTransportStream(String u,String t){return false;}void sampleSourceVideo(byte[] b){}void inspectStartupVideoCodec(byte[] b){}
 String sourceVideoMime="";String sourceVideoMime(){return sourceVideoMime;}
 static class HlsVideoCodecProbe {static class Stream {void accept(byte[] b,int o,int n){}String mime(){return "";}}}
 final Set<Socket> clientSockets=new HashSet<>();
 final Set<String> dolbyInitUrls=new HashSet<>();
 final Map<String,Double> segmentDurations=new HashMap<>();
 void startupCost(String stage,long duration){}
 void logSegmentCost(String url,long download,long decrypt,boolean encrypted){}
 void noteHttp2Failure(String url,IOException error){}
 PROGRESS
 static final Charset UTF_8=StandardCharsets.UTF_8;
 static final int UPSTREAM_MAX_ATTEMPTS=3,UPSTREAM_CONNECT_TIMEOUT_MS=3500,UPSTREAM_READ_TIMEOUT_MS=5500,UPSTREAM_RETRY_DELAY_MS=250;
 static final String TAG="test";boolean running=true,failHeaders,forbidden,failWritingHeaders,resumable,fullBody,badRange,badEntity,ignoredRange,resumeFails,noEntity;int opens;String resumeHeader,ifRange;
 String connectionHeader;boolean pacedFixture;String fixtureType="video/x-flv";long fixtureLength=-1;ByteArrayOutputStream pacedSink;
 PlaybackHttpError.Attempt playbackHttpError=new PlaybackHttpError.Attempt();
 Throwable lastPlaybackFailure;
 AtomicLong upstreamDownloadedBytes=new AtomicLong(),streamedResponseBytes=new AtomicLong(),streamedResponseCount=new AtomicLong();
 ThreadLocal<byte[]> streamCopyBuffer=new ThreadLocal<byte[]>(){protected byte[] initialValue(){return new byte[65536];}};
 static class Log{static void e(String t,String m,Exception e){}static void i(String t,String m){}}
 static class SystemClock{static long now;static long elapsedRealtime(){return now;}static void sleep(long t){}}
 static class Base64{static final int URL_SAFE=0;static byte[] decode(String s,int flags){return "https://example.test/segment.mp4".getBytes(UTF_8);}}
 static class ProxyResponse{String contentType;byte[] body;}
 static class HlsSegmentBitrate{static class Sample{void add(byte[] b,int o,int c){}}Sample begin(String u){return null;}void complete(Sample s,long t){}}
 HlsSegmentBitrate segmentBitrate=new HlsSegmentBitrate();
 File largeFile;ByteArrayOutputStream largeSink;
 int genericStartupPrefetchSegments=2;
 final Map<String,java.util.concurrent.FutureTask<byte[]>> genericSegmentTasks=new LinkedHashMap<>();
 final Set<String> consumedGenericSegments=new HashSet<>(),aesUrls=new HashSet<>();
 final java.util.concurrent.ExecutorService genericPrefetchWorkers=java.util.concurrent.Executors.newFixedThreadPool(2);
 static class AesSegmentKey {}
 AesSegmentKey aesSegmentKey(String u){return aesUrls.contains(u)?new AesSegmentKey():null;}
 byte[] downloadRaw(String u){throw new AssertionError("Clear TS must not use whole-response prefetch");}
 byte[] decryptAes128Segment(String u,AesSegmentKey key){return new byte[188];}
 byte[] transformCjsSegment(String u){return new byte[188];}
 boolean needsCjsTransform(String u){return false;}boolean needsSpecialDecrypt(String u){return false;}
 boolean isPlaylist(String u,String type){return URI.create(u).getPath().endsWith(".m3u8");}
 boolean hasAesSegmentKey(String u){return aesUrls.contains(u);}boolean hasGenericSegmentTask(String u){return genericSegmentTasks.containsKey(u);}
 boolean isRetryableUpstreamError(IOException e,String u){return false;}
 String readAsciiLine(InputStream i){return "GET /proxy/token HTTP/1.1";}String readRangeHeader(InputStream i){return null;}
 boolean dolbyInitFixture;
 ProxyResponse fetch(String u){if(!dolbyInitFixture)throw new AssertionError("unexpected buffered fetch");
  ProxyResponse r=new ProxyResponse();r.contentType="video/mp4";r.body="normalized-dolby-init".getBytes(UTF_8);return r;}
 void writeOk(OutputStream o,String t,byte[] b)throws IOException{o.write(b);o.flush();}
 void writeError(OutputStream o,int code,String m)throws IOException{o.write(("HTTP/1.1 "+code+"\r\n\r\n"+m).getBytes(UTF_8));o.flush();}
 String sanitizeHeaderValue(String s){return s;}void applyRequestHeaders(HttpURLConnection c,String u){}
 long contentLength(HttpURLConnection c){String length=c.getHeaderField("Content-Length");return length==null?131072:Long.parseLong(length);}
 void writeStreamingHeaders(OutputStream o,int s,String t,long l,String r)throws IOException{realStreamingHeaders(o,s,t,l,r);if(failWritingHeaders)throw new IOException("Injected header write failure");}
 void writeStreamingHeaders(OutputStream o,int s,String t,long l,String r,boolean chunked)throws IOException{realStreamingHeaders(o,s,t,l,r,chunked);if(failWritingHeaders)throw new IOException("Injected header write failure");}
 HttpURLConnection openUpstreamConnection(String u)throws IOException{final int number=++opens;return new HttpURLConnection(new URL(u)){
  public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}
  public int getResponseCode(){return forbidden?403:failHeaders?500:resumable&&!(fullBody&&number==1)&&!(ignoredRange&&number>1)?206:200;}
  public String getContentType(){return largeFile!=null?"video/MP2T":pacedFixture?fixtureType:"video/mp4";}public void setRequestProperty(String key,String value){if(key.equals("Connection"))connectionHeader=value;if(number>1){if(key.equals("Range"))resumeHeader=value;if(key.equals("If-Range"))ifRange=value;}}
  public String getHeaderField(String s){
   if(largeFile!=null&&s.equals("Content-Length"))return Long.toString(largeFile.length());
   if(pacedFixture&&s.equals("Content-Length"))return Long.toString(fixtureLength);
   if(!resumable)return null;
   if(s.equals("Content-Length"))return number==1?"131072":"65536";
   if(s.equals("ETag"))return noEntity?null:badEntity&&number>1?"\"v2\"":"\"v1\"";
   if(s.equals("Content-Range"))return fullBody?(number==1?null:badRange?"bytes 65537-131072/131072":"bytes 65536-131071/131072"):
     number==1?"bytes 1000-132071/200000":badRange?"bytes 66537-132072/200000":"bytes 66536-132071/200000";
   return null;
  }
  public InputStream getInputStream()throws IOException{
   if(largeFile!=null)return new FileInputStream(largeFile){int received,headers=-1;
    public int read(byte[] b)throws IOException{
     if(headers<0)headers=largeSink.size();
     if(received>=128*1024)check(largeSink.size()>headers,"Large segment waited for complete download before forwarding");
     int n=super.read(b);if(n>0)received+=n;return n;
    }
   };
   if(pacedFixture)return new InputStream(){int packets,headerBytes=-1;
   public int read(){return -1;}
   public int read(byte[] b){
    if(headerBytes<0)headerBytes=pacedSink.size();
    check(pacedSink.size()==headerBytes+packets*(fixtureLength<0?106:100),"Paced packets retained until next upstream read: "+fixtureType);
    if(packets==3)return -1;
    Arrays.fill(b,0,100,(byte)('A'+packets++));return 100;
   }
  };return new InputStream(){boolean emitted;
   public int read(){return -1;}
   public int read(byte[] b)throws IOException{if(resumeFails&&number>1)throw new SocketTimeoutException("repeated failure");if(emitted){if(resumable&&number>1)return -1;throw new SocketTimeoutException("Injected segment read timeout");}emitted=true;java.util.Arrays.fill(b,number==1?(byte)'V':(byte)'W');return b.length;}
  };}
 };}
 static class MemorySocket extends Socket {
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();boolean closed;
  public void setSoTimeout(int x){}public void setTcpNoDelay(boolean b){}public void setSendBufferSize(int x){}
  public InputStream getInputStream(){return new ByteArrayInputStream(new byte[0]);}
  public OutputStream getOutputStream(){return bytes;}public void close(){closed=true;}
 }
 HANDLE
 STREAM
 PREFETCH
 CANSTREAM
 HEADERS
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void main(String[] args)throws Exception {
  ByteArrayOutputStream liveHeaders=new ByteArrayOutputStream();
  realStreamingHeaders(liveHeaders,200,"video/x-flv",-1,null);
  check(!liveHeaders.toString("UTF-8").contains("Accept-Ranges:"),"Unbounded live response advertised byte seeking");
  ByteArrayOutputStream finiteHeaders=new ByteArrayOutputStream();
  realStreamingHeaders(finiteHeaders,206,"video/mp4",300,"bytes 0-299/1000");
  check(finiteHeaders.toString("UTF-8").contains("Accept-Ranges: bytes"),"Finite range response lost seeking");
  for(boolean hls:new boolean[]{false,true}) {
   HlsStreamFailureCheck transport=new HlsStreamFailureCheck();transport.pacedFixture=true;transport.fixtureLength=300;
   transport.pacedSink=new ByteArrayOutputStream();transport.byteRangeMediaPlaylist=hls;
   if(hls)transport.segmentDurations.put("https://fixture.test/all.ts",2.0);
   transport.streamUpstream("https://fixture.test/all.ts","bytes=0-299",new BufferedOutputStream(transport.pacedSink),false);
   check(hls?transport.connectionHeader==null:"close".equals(transport.connectionHeader),"HLS range pooling or legacy non-HLS guard changed");
  }
  HlsStreamFailureCheck prefetch=new HlsStreamFailureCheck();
  List<String> clips=Arrays.asList("http://fixture/a.ts","http://fixture/b.ts","http://fixture/c.ts");
  check(prefetch.prefetchGenericSegments(clips,2).isEmpty()&&prefetch.genericSegmentTasks.isEmpty(),
    "Clear UHD media entered whole-body prefetch before the first player request");
  String opaque="http://fixture/resource?n=1";prefetch.segmentDurations.put(opaque,10.0);
  check(prefetch.canStreamWithoutRewrite(opaque)&&prefetch.prefetchGenericSegments(Arrays.asList(opaque),1).isEmpty(),
    "Manifest-declared opaque media URL did not use the progressive path");
  check(!prefetch.canStreamWithoutRewrite("http://fixture/unknown"),"Arbitrary unknown resources were treated as media");
  prefetch.aesUrls.addAll(clips);
  check(prefetch.prefetchGenericSegments(clips,2).size()==2,"Encrypted startup reserve was lost");
  for(java.util.concurrent.FutureTask<byte[]> task:prefetch.genericSegmentTasks.values())check(task.get().length==188,"Encrypted reserve failed");
  prefetch.genericPrefetchWorkers.shutdownNow();
  for(int index=1;index<args.length;index++) {
   HlsStreamFailureCheck large=new HlsStreamFailureCheck();large.largeFile=new File(args[index]);large.largeSink=new ByteArrayOutputStream();
   check(large.largeFile.length()>16*1024*1024,"Large-segment fixture must exceed the whole-response cap");
   try {
    BoundedResponseReader.read(new ByteArrayInputStream(new byte[0]),large.largeFile.length(),16*1024*1024);
    throw new AssertionError("Whole-response path unexpectedly accepted the oversized TS");
   }catch(IOException expected){check(expected.getMessage().contains("上游响应超过"),"Unexpected whole-response failure");}
   large.streamUpstream("http://fixture/large.ts?servicetype=1",null,new BufferedOutputStream(large.largeSink),false);
   byte[] wire=large.largeSink.toByteArray();int header=-1;
   for(int n=0;n+3<wire.length;n++)if(wire[n]==13&&wire[n+1]==10&&wire[n+2]==13&&wire[n+3]==10){header=n+4;break;}
   check(header>0&&wire.length-header==large.largeFile.length()&&large.streamedResponseCount.get()==1,"Large TS was truncated or rejected");
   java.security.MessageDigest expected=java.security.MessageDigest.getInstance("SHA-256"),actual=java.security.MessageDigest.getInstance("SHA-256");
   actual.update(wire,header,wire.length-header);
   try(InputStream input=new FileInputStream(large.largeFile)){byte[] b=new byte[65536];int n;while((n=input.read(b))!=-1)expected.update(b,0,n);}
   check(Arrays.equals(expected.digest(),actual.digest()),"Large TS payload changed");
   System.out.println("PASS progressive forwarding before 128 KiB; exact payload bytes="+large.largeFile.length()+" file="+large.largeFile.getName());
  }
  HlsStreamFailureCheck progress=new HlsStreamFailureCheck();SystemClock.now=100;
  progress.new UpstreamInputStream(new ByteArrayInputStream(new byte[3]),false).read(new byte[3]);
  check(progress.upstreamDownloadedBytes.get()==3&&!progress.mediaMakingProgress(8000),"Manifest bytes must not defer stalled-media recovery");
  SystemClock.now=200;
  InputStream media=progress.new UpstreamInputStream(new ByteArrayInputStream(new byte[10]),true);
  media.read(new byte[4]);
  check(progress.mediaMakingProgress(8000),"In-flight media bytes must defer premature reconnects");
  SystemClock.now=8200;
  check(!progress.mediaMakingProgress(8000),"Media progress did not expire");
  SystemClock.now=8300;
  progress.new UpstreamInputStream(new ByteArrayInputStream(new byte[1]),false).read();
  check(!progress.mediaMakingProgress(8000),"A key/manifest refresh revived a stalled media body");
  media.read();check(progress.mediaMakingProgress(8000),"Single-byte media read not tracked");
  check(progress.upstreamDownloadedBytes.get()==9,"Network statistics must still include non-media requests");
  progress.running=false;check(!progress.mediaMakingProgress(8000),"Closed proxy reported media progress");
  SystemClock.now=0;
  for(int kind=0;kind<8;kind++){
   HlsStreamFailureCheck live=new HlsStreamFailureCheck();live.pacedFixture=true;
   live.fixtureType=kind==0?"video/x-flv":kind==1?"application/octet-stream":kind==2?"audio/aac":kind==4?"video/mp2t":"video/x-flv";
   live.fixtureLength=kind==3||kind>=5?300:-1;live.pacedSink=new ByteArrayOutputStream();
   live.streamUpstream(kind==4?"https://fixture.test/opaque":"http://fixture.test/live.flv?token=test",null,new BufferedOutputStream(live.pacedSink),kind==4);
   String delivered=new String(live.pacedSink.toByteArray(),UTF_8);
   check((live.fixtureLength<0?delivered.endsWith("CCCC\r\n0\r\n\r\n"):delivered.endsWith("CCCC"))&&live.streamedResponseBytes.get()==300,"Paced stream payload changed");
  }
  System.out.println("PASS unknown-length TS/octet-stream/FLV/audio flush before next upstream read; finite HLS/media flush every short read before the next read");
  HlsStreamFailureCheck p=new HlsStreamFailureCheck();MemorySocket s=new MemorySocket();p.handle(s);
  String body=new String(s.bytes.toByteArray(),UTF_8);
  check(body.startsWith("HTTP/1.1 200"),"Expected initial media response");
  check(body.indexOf("HTTP/1.1",1)<0&&!body.contains("Upstream failed"),"Error response corrupted partial media");
  check(body.length()>65536&&s.closed,"Close truncated media for player reconnect");
  p.failWritingHeaders=true;s=new MemorySocket();p.handle(s);body=new String(s.bytes.toByteArray(),UTF_8);
  check(body.indexOf("HTTP/1.1",1)<0&&!body.contains("Upstream failed"),"Partial headers must not get a second response");
  p.failWritingHeaders=false;p.failHeaders=true;s=new MemorySocket();p.handle(s);body=new String(s.bytes.toByteArray(),UTF_8);
  check(body.startsWith("HTTP/1.1 502")&&s.closed,"Pre-header failures must still return HTTP error");
  p=new HlsStreamFailureCheck();p.forbidden=true;s=new MemorySocket();p.handle(s);body=new String(s.bytes.toByteArray(),UTF_8);
  check(body.startsWith("HTTP/1.1 403")&&p.playbackHttpError.isForbidden(),"Upstream 403 must reach the player and diagnosis");
  p=new HlsStreamFailureCheck();p.resumable=true;s=new MemorySocket();p.handle(s);body=new String(s.bytes.toByteArray(),UTF_8);
  check(p.opens==2&&"bytes=66536-132071".equals(p.resumeHeader)&&"\"v1\"".equals(p.ifRange),"Resume wrong byte offset or missing If-Range");
  check(body.indexOf('W')-body.indexOf('V')==65536&&body.endsWith("WWWW"),"Resumed media duplicated or lost bytes");
  check(p.streamedResponseCount.get()==1&&p.streamedResponseBytes.get()==131072,"Completed range should count exact payload");
  p=new HlsStreamFailureCheck();p.resumable=true;p.resumeFails=true;s=new MemorySocket();p.handle(s);
  check(p.opens==3&&p.streamedResponseCount.get()==0&&s.closed,"Body retry count must be bounded at two");
  for(int bad=0;bad<3;bad++){
   p=new HlsStreamFailureCheck();p.resumable=true;p.badRange=bad==0;p.badEntity=bad==1;p.ignoredRange=bad==2;s=new MemorySocket();p.handle(s);body=new String(s.bytes.toByteArray(),UTF_8);
   check(!body.contains("WWWW")&&body.indexOf("HTTP/1.1",1)<0,"Unsafe range/entity change appended bytes");
   check(p.streamedResponseCount.get()==0,"Unsafe range reported complete");
  }
  p=new HlsStreamFailureCheck();p.resumable=true;p.fullBody=true;s=new MemorySocket();p.handle(s);body=new String(s.bytes.toByteArray(),UTF_8);
  check(body.startsWith("HTTP/1.1 200")&&p.opens==2&&"bytes=65536-131071".equals(p.resumeHeader),"Full fragment must resume at received offset");
  check(body.indexOf('W')-body.indexOf('V')==65536&&body.endsWith("WWWW")&&p.streamedResponseBytes.get()==131072,"Full fragment resume changed media bytes");
  for(int bad=0;bad<4;bad++){
   p=new HlsStreamFailureCheck();p.resumable=true;p.fullBody=true;p.badRange=bad==0;p.badEntity=bad==1;p.ignoredRange=bad==2;p.noEntity=bad==3;
   s=new MemorySocket();p.handle(s);body=new String(s.bytes.toByteArray(),UTF_8);
   check(!body.contains("WWWW")&&body.indexOf("HTTP/1.1",1)<0&&p.streamedResponseCount.get()==0,"Unsafe full fragment resume appended bytes");
   if(p.noEntity)check(p.opens==1,"Missing entity tag must not retry by range");
  }
  p=new HlsStreamFailureCheck();
  p.dolbyInitUrls.add("https://example.test/segment.mp4");p.dolbyInitFixture=true;
  s=new MemorySocket();p.handle(s);
  check(new String(s.bytes.toByteArray(),UTF_8).equals("normalized-dolby-init"),
        "Cached Dolby init must pass through the normalizing fetch path");
  p.dolbyInitUrls.clear();p.dolbyInitFixture=false;
  System.out.println("PASS byte-exact HTTP 200 fragment and 206 range resume; changed entity/range/ignored range/missing ETag rejected; retries bounded; no HTTP errors injected into media");
  System.out.println("PASS manifest/key traffic does not defer stalled-media recovery; media reads extend recovery; network byte statistics unchanged");
 }
}'''.replace('\n HANDLE\n',handle).replace('\n STREAM\n',stream).replace('\n PROGRESS\n',progress).replace('\n PREFETCH\n',prefetch).replace('\n CANSTREAM\n',stream_predicate).replace('\n HEADERS\n',stream_headers)
java=java.replace('public class HlsStreamFailureCheck {','public class HlsStreamFailureCheck {\n'+player_output)
java += (root/'app/src/main/java/xiao/bu/tv/PlaybackHttpError.java').read_text(encoding='utf-8').replace('package xiao.bu.tv;', '').replace('import java.util.regex.Pattern;', '')
out=root/'.codex-tmp/hls-stream-failure-test';out.mkdir(parents=True,exist_ok=True)
f=out/'HlsStreamFailureCheck.java';f.write_text(java,encoding='utf-8');b=Path(os.environ['JAVA_HOME'])/'bin'
bounded=out/'BoundedResponseReader.java';bounded.write_text((root/'app/src/main/java/xiao/bu/tv/BoundedResponseReader.java').read_text(encoding='utf-8').replace('package xiao.bu.tv;',''),encoding='utf-8')
replay=out/'PlaybackReplayCache.java';replay.write_text((root/'app/src/main/java/xiao/bu/tv/PlaybackReplayCache.java').read_text(encoding='utf-8').replace('package xiao.bu.tv;',''),encoding='utf-8')
chunked=out/'LiteChunkedOutputStream.java';chunked.write_text((root/'browser-core/litebrowser/src/main/java/xiao/bu/tv/LiteChunkedOutputStream.java').read_text(encoding='utf-8').replace('package xiao.bu.tv;',''),encoding='utf-8')
subprocess.run([str(b/'javac.exe'),'-encoding','UTF-8',str(f),str(bounded),str(replay),str(chunked)],check=True)
large=out/'large-clear.ts'
with large.open('wb') as stream:
    for _ in range(17):stream.write(bytes(range(256))*4096)
samples=[]
if os.environ.get('NTV_HLS_SAMPLE_DIR'):
    for sample in Path(os.environ['NTV_HLS_SAMPLE_DIR']).rglob('*.ts'):
        try:
            if sample.stat().st_size>16*1024*1024:samples.append(sample)
        except FileNotFoundError:pass  # A concurrently generated live fixture can expire.
subprocess.run([str(b/'java.exe'),'-cp',str(out),'HlsStreamFailureCheck',str(out/'cache'),str(large),*map(str,samples)],check=True,timeout=45)
