"""Fault-inject the real H5E task, downloader, capability probe and HTTP streaming path."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root/'app/src/main/java/xiao/bu/tv/HlsProxyServer.java').read_text(encoding='utf-8')
def method(signature):
    start = source.index(signature)
    end = source.index('{', start) + 1
    depth = 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]

java = r'''
package xiao.bu.tv;
import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.*;
public class H5eProgressiveCheck {
 static final String TAG="test";
 static final int MAX_BUFFERED_RESPONSE_BYTES=1024*1024,TS_PACKET_SIZE=188,
   UPSTREAM_MAX_ATTEMPTS=3,CCTV_SEGMENT_MAX_ATTEMPTS=4,UPSTREAM_CONNECT_TIMEOUT_MS=100,
   CCTV_SEGMENT_READ_TIMEOUT_MS=5000;
 boolean running=true,ownsH5eWork,sourceVideoProbeEnabled;
 Boolean h5eStreamingSupported;
 boolean failBody,failHeaders,wrongLength,unknownLength,hold=true;
 int opens;long lastMediaSegmentServedAt;String lastCctvRequestedUrl;byte[] screenshotSegment;
 final Map<String,ProgressiveMediaBuffer> h5eProgressive=new HashMap<>();
 final Map<String,FutureTask<byte[]>> cctvSegmentTasks=new HashMap<>();
 final Map<String,byte[]> cctvSegmentCache=new HashMap<>();
 final Map<String,String> cctvNextSegments=new HashMap<>();
 final Map<String,Long> segmentDownloadTimes=new HashMap<>();
 final ExecutorService genericPrefetchWorkers=Executors.newCachedThreadPool(),
    cctvPrefetchWorkers=Executors.newSingleThreadExecutor();
 final CountDownLatch paused=new CountDownLatch(1),release=new CountDownLatch(1);
 final ThreadLocal<byte[]> streamCopyBuffer=ThreadLocal.withInitial(()->new byte[8192]);
 boolean spsCompatibilityMode;
 static class NativeH5eDecryptor {
  static boolean missing;static int probes,firsts,calls;
  static boolean supportsStreamingTransportStream(){probes++;if(missing)throw new UnsatisfiedLinkError("old plugin");return true;}
  static void setSpsCompatibilityMode(boolean b){}
  static byte[] decryptTransportStreamPart(byte[] b,boolean first){calls++;if(first)firsts++;
   for(int i=0;i<b.length;i+=188)if((((b[i+1]&31)<<8)|(b[i+2]&255))==256)b[i+20]^=0x33;return b;}
 }
 static class Process {static final int THREAD_PRIORITY_FOREGROUND=0;static void setThreadPriority(int p){}}
 static class SystemClock {static long elapsedRealtime(){return System.nanoTime()/1000000;}}
 static class Log {static void i(String t,String m){}static void w(String t,String m,Throwable e){}}
 static class NetworkClient {static void invalidateDns(String h){}}
 static class VideoScreenshot {static boolean needsSegmentCache(){return false;}}
 static class StreamingResponseException extends IOException {StreamingResponseException(IOException e){super(e);}}
 void startupCost(String s,long v){}void logDecryptedSegmentCost(String s,long v){}
 final Map<String,Integer> milestones=new ConcurrentHashMap<>();
 void startupMilestone(String s,String u){milestones.putIfAbsent(s,1);}
 void noteClearVideo(byte[] b,int pid,String u){startupMilestone("first-clear-video-pes",u);}
 void sampleSourceVideo(byte[] b){}void inspectStartupVideoCodec(byte[] b){}void recordUpstreamBytes(int n,boolean b){}
 boolean needsH5eDecrypt(String u){return true;}
 boolean isRetryableUpstreamError(IOException e,String u){return true;}
 boolean isPlayerDisconnect(IOException e){return false;}
 boolean isCompleteTransportStream(byte[] b){return b.length>0&&b.length%188==0;}
 static int findAvcVideoPid(byte[] b){return b.length>=376?256:-1;}
 String segmentName(String u){return u;}
 void applyRequestHeaders(HttpURLConnection c,String u){}
 void prefetchCctvSegment(String u){throw new AssertionError("Unexpected next URL");}
 FutureTask<byte[]> newCctvSegmentTask(String u){return newH5eSegmentTask(u);}
 static byte[] fixture(){ByteArrayOutputStream out=new ByteArrayOutputStream();
  for(int i=0;i<200;i++){byte[] p=new byte[188];Arrays.fill(p,(byte)0x55);int pid=i==0?0:i==1?100:256;
   p[0]=0x47;p[1]=(byte)((pid>>8)|64);p[2]=(byte)pid;p[3]=(byte)(0x10|(i&15));out.write(p,0,p.length);}return out.toByteArray();}
 HttpURLConnection openUpstreamConnection(String u)throws IOException{opens++;int attempt=opens;byte[] bytes=fixture();return new HttpURLConnection(new URL(u)){
  public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}
  public int getResponseCode()throws IOException{if(failHeaders&&attempt==1)throw new SocketTimeoutException("headers");return 200;}
  public int getContentLength(){return unknownLength?-1:bytes.length+(wrongLength?188:0);}
  public InputStream getInputStream(){return new FilterInputStream(new ByteArrayInputStream(bytes)){boolean waited;int received;
   public synchronized int read(byte[] b,int o,int n)throws IOException{
    if(hold&&received>=188*10&&!waited){waited=true;paused.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("cancelled");}}
    if(failBody&&received>=188*20)throw new SocketTimeoutException("body");
    int count=in.read(b,o,Math.min(188,n));if(count>0)received+=count;return count;}
  };}
 };}
 void writeOk(OutputStream o,String t,byte[] b)throws IOException{o.write(b);}
 void writeStreamingHeaders(OutputStream o,int code,String t,long l,String range,boolean chunked)throws IOException{o.write(("HTTP/1.1 200 OK\r\n"+(chunked?"Transfer-Encoding: chunked":"Content-Length: "+l)+"\r\n\r\n").getBytes("UTF-8"));}
 METHODS
 static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
 static byte[] body(byte[] response)throws Exception{
  ByteArrayInputStream in=new ByteArrayInputStream(response);String line;
  boolean chunked=false;while(!(line=line(in)).isEmpty())if(line.equals("Transfer-Encoding: chunked"))chunked=true;
  if(!chunked){byte[] all=new byte[in.available()];check(in.read(all)==all.length,"Short fixed body");return all;}
  ByteArrayOutputStream out=new ByteArrayOutputStream();
  for(;;){int size=Integer.parseInt(line(in),16);if(size==0)break;byte[] block=new byte[size];
   check(in.read(block)==size,"Short HTTP chunk");out.write(block);check(line(in).isEmpty(),"Chunk delimiter");}
  return out.toByteArray();
 }
 static String line(InputStream in)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();int c;
  while((c=in.read())!=-1&&c!='\n')if(c!='\r')out.write(c);return out.toString("UTF-8");}
 void stop(){running=false;release.countDown();synchronized(cctvSegmentTasks){for(FutureTask<byte[]> t:new ArrayList<>(cctvSegmentTasks.values()))t.cancel(true);}genericPrefetchWorkers.shutdownNow();cctvPrefetchWorkers.shutdownNow();}
 static Future<?> serve(H5eProgressiveCheck p,ByteArrayOutputStream out,ExecutorService clients){return clients.submit(()->{try{p.streamH5eSegment("https://example.test/a.ts",out);}catch(IOException e){throw new CompletionException(e);}});}
 public static void main(String[] args)throws Exception{
  NativeH5eDecryptor.missing=true;H5eProgressiveCheck old=new H5eProgressiveCheck();
  check(!old.supportsH5eStreaming()&&!old.supportsH5eStreaming(),"Old plugin fallback");check(NativeH5eDecryptor.probes==1,"Probe should be cached");old.stop();NativeH5eDecryptor.missing=false;
  ExecutorService clients=Executors.newCachedThreadPool();
  try{
   H5eProgressiveCheck p=new H5eProgressiveCheck();ByteArrayOutputStream a=new ByteArrayOutputStream(),b=new ByteArrayOutputStream();
   try{
    Future<?> first=serve(p,a,clients);check(p.paused.await(2,TimeUnit.SECONDS),"Downloader not paused");
    long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(a.size()<188*5&&System.nanoTime()<until)Thread.sleep(5);
    check(a.size()>188*5&&!first.isDone(),"Video withheld until whole download");
    Future<?> second=serve(p,b,clients);
    until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(b.size()<188*5&&System.nanoTime()<until)Thread.sleep(5);
    check(b.size()>188*5,"Second reader did not join in-flight segment");
    p.release.countDown();first.get(3,TimeUnit.SECONDS);second.get(3,TimeUnit.SECONDS);
    check(Arrays.equals(body(a.toByteArray()),body(b.toByteArray())),"Readers differ");
    byte[] expected=fixture();for(int i=188*2;i<expected.length;i+=188)expected[i+20]^=0x33;
    check(Arrays.equals(expected,body(a.toByteArray())),"Changed/reordered HTTP media bytes");
    check(p.opens==1&&NativeH5eDecryptor.firsts==1,"Duplicate download/session");
    check(p.cctvSegmentCache.size()==1,"Complete output not cached");
    check(p.milestones.keySet().containsAll(Arrays.asList("encrypted-first-byte","player-ts-request","first-clear-video-pes","first-clear-segment","first-player-byte")),"Missing pipeline milestones");
    check("https://example.test/a.ts".equals(p.lastCctvRequestedUrl),"Requested segment lost its scheduling anchor");
   }finally{p.stop();}
   for(boolean length:new boolean[]{false,true}){
    H5eProgressiveCheck bad=new H5eProgressiveCheck();bad.failBody=!length;bad.wrongLength=length;
    try{ByteArrayOutputStream out=new ByteArrayOutputStream();Future<?> f=serve(bad,out,clients);
     check(bad.paused.await(2,TimeUnit.SECONDS),"Not paused");
     long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(out.size()<188*5&&System.nanoTime()<until)Thread.sleep(5);
     bad.release.countDown();try{f.get(3,TimeUnit.SECONDS);throw new AssertionError("Failed media accepted");}catch(ExecutionException expected){}
     check(bad.cctvSegmentCache.isEmpty()&&bad.opens==1,"Partial media cached or retried");
     check(!new String(out.toByteArray(),"ISO-8859-1").endsWith("0\r\n\r\n"),"Broken response marked complete");
    }finally{bad.stop();}
   }
   H5eProgressiveCheck retry=new H5eProgressiveCheck();retry.hold=false;retry.failHeaders=true;
   try{serve(retry,new ByteArrayOutputStream(),clients).get(3,TimeUnit.SECONDS);check(retry.opens==2,"Safe pre-body retry missing");}finally{retry.stop();}
   H5eProgressiveCheck unknown=new H5eProgressiveCheck();unknown.hold=false;unknown.unknownLength=true;
   try{ByteArrayOutputStream out=new ByteArrayOutputStream();serve(unknown,out,clients).get(3,TimeUnit.SECONDS);check(body(out.toByteArray()).length==fixture().length,"Unknown-length fallback lost bytes");check(new String(out.toByteArray(),"ISO-8859-1").contains("Transfer-Encoding: chunked"),"Unknown length was invented");}finally{unknown.stop();}
   H5eProgressiveCheck cancel=new H5eProgressiveCheck();
   try{Future<?> f=serve(cancel,new ByteArrayOutputStream(),clients);check(cancel.paused.await(2,TimeUnit.SECONDS),"Not paused");cancel.stop();try{f.get(3,TimeUnit.SECONDS);throw new AssertionError("Cancelled task succeeded");}catch(ExecutionException expected){}check(cancel.cctvSegmentCache.isEmpty(),"Cancelled media cached");}finally{cancel.stop();}
  }finally{clients.shutdownNow();}
  System.out.println("PASS H5E early video, shared readers, exact body, cache validity, cancellation, bounded retries, old-plugin fallback");
 }
}
'''
signatures = ['    private synchronized boolean supportsH5eStreaming()',
              '    private FutureTask<byte[]> newH5eSegmentTask(',
              '    private static void awaitEncryptedTsDownload(',
              '    private void downloadEncryptedTs(',
              '    private void streamH5eSegment(',
              '    private void writeProgressiveTs(']
java = java.replace('METHODS', '\n'.join(method(s) for s in signatures))
with tempfile.TemporaryDirectory(prefix='ntv-h5e-progressive-') as temp:
    out = Path(temp)
    test = out/'H5eProgressiveCheck.java'
    test.write_text(java, encoding='utf-8')
    sources = [root/'app/src/main/java/xiao/bu/tv'/f'{name}.java'
               for name in ['CmgTsStream', 'CmgBytePipe', 'ProgressiveMediaBuffer']]
    sources.append(root/'browser-core/litebrowser/src/main/java/xiao/bu/tv/LiteChunkedOutputStream.java')
    subprocess.run(['javac', '-encoding', 'UTF-8', '-d', temp, test, *sources], check=True)
    subprocess.run(['java', '-cp', temp, 'xiao.bu.tv.H5eProgressiveCheck'], check=True, timeout=20)
