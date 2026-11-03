"""Run production recording dispatch, stream ownership and HTTP writer against faults."""
from pathlib import Path
import subprocess, tempfile, sys, zipfile

root=Path(__file__).resolve().parents[1]
src=(root/'app/src/main/java/xiao/bu/tv/HlsProxyServer.java').read_text(encoding='utf8')
local=(root/'app/src/main/java/xiao/bu/tv/LocalControlServer.java').read_text(encoding='utf8')
def between(text,start,end):
    begin=text.index(start);return text[begin:text.index(end,begin)]
def method(text,start):
    begin=text.index(start);depth=0
    for index in range(text.index('{',begin),len(text)):
        if text[index]=='{':depth+=1
        elif text[index]=='}':
            depth-=1
            if depth==0:return text[begin:index+1]
imports='package xiao.bu.tv; import java.io.*; import java.net.*; import java.util.*; import java.nio.charset.*;\n'
local_java=imports+'class LocalControlServer {\n'+between(local,'    static final class Resource','    private static final String TAG')+'''
 static final String TAG="test";
 static final ThreadLocal<byte[]> transferBuffer=new ThreadLocal<byte[]>() {
  protected byte[] initialValue(){return new byte[65536];}
 };
 static class Log {static void w(String t,String m,Exception e){}}
 static void send(Socket s,int status,String type,byte[] body)throws IOException{send(s,status,type,body,"no-store","");}
'''+between(local,'    private static void sendMediaDownload(', '    private static String findLanAddress(')+'''
 static void transfer(Socket socket, Resource resource)throws IOException{sendResource(socket,resource);}
 static void transferDownload(Socket socket,MediaFileDownload download){sendMediaDownload(socket,download);}
}
'''
hls_java=imports+'''class HlsProxyServer {
 static final Charset UTF_8=StandardCharsets.UTF_8;
 static final int MAX_PLAYLIST_RESPONSE_BYTES=2*1024*1024,UPSTREAM_MAX_ATTEMPTS=3,
 UPSTREAM_CONNECT_TIMEOUT_MS=100,UPSTREAM_READ_TIMEOUT_MS=100,UPSTREAM_RETRY_DELAY_MS=0;
 boolean running=true,playbackSuspended,encrypted,prefetched,known,special,cjs,dolby;
 final Set<String> dolbyInitUrls=new HashSet<>();final Map<String,Double> segmentDurations=new HashMap<>();
 final Map<String,Boolean> recordingTokens=new HashMap<>();String loopbackHost="127.0.0.1";
 final Map<String,String> tokenUrls=new HashMap<>();
 final ServerSocket serverSocket;String redirectedBase;int opens,fetches,failOpen;
 FakeConnection last;byte[] payload="binary0123456789".getBytes(UTF_8);String type="video/mp2t";
 long declared=payload.length;int failAfter=-1;boolean inputClosed;long readBytes;
 HlsProxyServer()throws IOException{serverSocket=new ServerSocket(0);}
 String proxyUrl(String url){String token="token"+tokenUrls.size();tokenUrls.put(token,url);recordingTokens.put(token,true);return "http://127.0.0.1:"+serverSocket.getLocalPort()+"/proxy/"+token;}
 String token(String url){String proxy=proxyUrl(url);return proxy.substring(proxy.lastIndexOf('/')+1);}
 String stripProxyTokenSuffix(String s){return s;}
 String decodeProxyToken(String s){String url=tokenUrls.get(s);if(url==null)throw new IllegalArgumentException();return url;}
 boolean knowsHlsPlaylist(String s){return known;}
 boolean needsSpecialDecrypt(String s){return special;}
 boolean needsCjsTransform(String s){return cjs;}
 boolean hasAesSegmentKey(String s){return encrypted;}
 boolean hasGenericSegmentTask(String s){return prefetched;}
 void noteHttp2Failure(String s,IOException e){}
 boolean isRetryableUpstreamError(IOException e,String s){return e.getMessage().equals("retry-open");}
 static class SystemClock {static void sleep(long n){}}
 void applyRequestHeaders(HttpURLConnection c,String u){c.setRequestProperty("Referer","https://official.test/");c.setRequestProperty("Cookie","auth=latest");}
 String sanitizeHeaderValue(String s){return s;}
 long contentLength(HttpURLConnection c){return declared;}
 boolean isPlaylist(String s,String t){return s.contains(".m3u8")||t!=null&&t.contains("mpegurl");}
 String rewritePlaylist(String policy,String responseUrl,String text){redirectedBase=responseUrl;String prefix="http://127.0.0.1:"+serverSocket.getLocalPort()+"/proxy/";return HlsRecordingPlaylist.rewrite(text,responseUrl,prefix,prefix,this::proxyUrl);}
 ProxyResponse fetch(String s){fetches++;return new ProxyResponse(type,payload);}
 static class ProxyResponse {final String contentType;final byte[] body;ProxyResponse(String t,byte[] b){contentType=t;body=b;}}
 class UpstreamInputStream extends FilterInputStream {
  UpstreamInputStream(InputStream i,boolean media){super(i);}
 }
 class FakeConnection extends HttpURLConnection {
  boolean disconnected;final Map<String,String> headers=new HashMap<>();
  FakeConnection(String u)throws IOException{super(new URL("https://redirect.test/edge/media.ts"));}
  public void connect(){} public void disconnect(){disconnected=true;}public boolean usingProxy(){return false;}
  public int getResponseCode(){return 200;}public String getContentType(){return type;}
  public void setRequestProperty(String k,String v){headers.put(k,v);}
  public InputStream getInputStream(){return new InputStream(){int pos;
   public int read()throws IOException{byte[] b=new byte[1];int n=read(b,0,1);return n<0?-1:b[0]&255;}
   public int read(byte[] b,int o,int n)throws IOException{
    if(failAfter>=0&&pos>=failAfter)throw new IOException("body failure");
    if(pos==payload.length)return -1;int size=Math.min(n,payload.length-pos);
    if(failAfter>=0)size=Math.min(size,failAfter-pos);
    System.arraycopy(payload,pos,b,o,size);pos+=size;readBytes+=size;return size;
   }
   public void close(){inputClosed=true;}
  };}
 }
 HttpURLConnection openUpstreamConnection(String u)throws IOException{opens++;if(failOpen-->0)throw new IOException("retry-open");return last=new FakeConnection(u);}
'''+between(src,'    private String recordingUrl(', '    static void configureCmgUpdateTags(')+between(src,'    private boolean canStreamWithoutRewrite(', '    private void streamUpstream(')+method(src,'    private static boolean isPlaylistBody(')+'\n}\n'
check_java=imports+r'''class TransferCheck {
 static int checks;static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static class Peer extends Socket {
  static class Bytes extends ByteArrayOutputStream {byte[] raw(){return buf;}}
  final Bytes out=new Bytes();boolean fail;
  public OutputStream getOutputStream(){return new FilterOutputStream(out){
   public void write(byte[] b,int o,int n)throws IOException{if(fail&&Peer.this.out.size()>0)throw new IOException("client aborted");Peer.this.out.write(b,o,n);}
  };}
  String text(){int size=out.size();String head=new String(out.raw(),0,Math.min(size,4096),StandardCharsets.ISO_8859_1);return size<=4096?head:head+new String(out.raw(),size-64,64,StandardCharsets.ISO_8859_1);}
 }
 static byte[] body(Peer p){int start=p.text().indexOf("\r\n\r\n")+4;return Arrays.copyOfRange(p.out.raw(),start,p.out.size());}
 static void fileDownloads()throws Exception{
  NetworkClient.Fake c=NetworkClient.next=new NetworkClient.Fake();
  c.payload=new byte[150000];new Random(9).nextBytes(c.payload);c.length=c.payload.length;
  MediaFileDownload file=MediaFileDownload.open("https://file.test/movie.mp4","Cookie: session=current\nReferer: https://official.test/","标题",null,null);
  Peer peer=new Peer();LocalControlServer.transferDownload(peer,file);
  check(Arrays.equals(body(peer),c.payload),"Direct file download byte exact");
  check(c.closed&&!c.disconnected,"Completed file retains pooled connection");
  check(c.request.get("Cookie").equals("session=current")&&c.request.get("Accept-Encoding").equals("identity"),"Direct file auth and representation unchanged");
  c=NetworkClient.next=new NetworkClient.Fake();c.status=206;c.range="bytes 3-7/20";
  file=MediaFileDownload.open("https://file.test/movie.mp4","","file","bytes=3-7","\"v1\"");peer=new Peer();LocalControlServer.transferDownload(peer,file);
  check(peer.text().startsWith("HTTP/1.1 206")&&peer.text().contains("Content-Range: bytes 3-7/20"),"Range status and response headers retained");
  check(c.request.get("Range").equals("bytes=3-7")&&c.request.get("If-Range").equals("\"v1\""),"Range request remains conditional");
  c=NetworkClient.next=new NetworkClient.Fake();c.length=-1;
  file=MediaFileDownload.open("https://file.test/movie.mp4","","file",null,null);peer=new Peer();LocalControlServer.transferDownload(peer,file);
  check(peer.text().contains("Transfer-Encoding: chunked")&&peer.text().endsWith("0\r\n\r\n")&&!c.disconnected,"Unknown length file completes explicitly and retains connection");
  c=NetworkClient.next=new NetworkClient.Fake();c.length=100;
  file=MediaFileDownload.open("https://file.test/movie.mp4","","file",null,null);peer=new Peer();LocalControlServer.transferDownload(peer,file);
  check(c.disconnected&&!peer.text().contains("500 Internal"),"Truncated file closes without appending HTTP/JSON");
  c=NetworkClient.next=new NetworkClient.Fake();c.payload=new byte[150000];c.length=-1;
  file=MediaFileDownload.open("https://file.test/movie.mp4","","file",null,null);peer=new Peer();peer.fail=true;LocalControlServer.transferDownload(peer,file);
  check(c.disconnected&&c.closed,"File client cancellation disconnects before closing body");
 }
 static void allocations()throws Exception{
  HlsProxyServer p=new HlsProxyServer();p.payload=new byte[2*1024*1024];p.declared=p.payload.length;
  Socket sink=new Socket(){public OutputStream getOutputStream(){return new OutputStream(){public void write(int b){}public void write(byte[] b,int o,int n){}};}};
  LocalControlServer.transfer(sink,open(p));
  AllocationCounter counter=new AllocationCounter();long before=counter.read();
  for(int n=0;n<8;n++)LocalControlServer.transfer(sink,open(p));
  long streaming=(counter.read()-before)/8;
  before=counter.read();
  for(int n=0;n<8;n++)BoundedResponseReader.read(new ByteArrayInputStream(p.payload),p.payload.length,16*1024*1024);
  long buffered=(counter.read()-before)/8;
  if(buffered>0)check(streaming<buffered/10,"Steady-state streaming allocates less than 10% of a full-segment read");
  System.out.println(counter.platform+"_ALLOCATION bytes/request 2MiB: buffered="+buffered+" streaming="+streaming+" (allocation counter, not heap/PSS)");p.serverSocket.close();
 }
 static LocalControlServer.Resource open(HlsProxyServer p)throws IOException{return p.openForRecording("https://site.test/index.m3u8",p.token("https://cdn.test/media.ts?sign=a%2Bb"),"/api/recording/resource/");}
 public static void main(String[] args)throws Exception{
  HlsProxyServer p=new HlsProxyServer();
  try{
   p.payload=new byte[8*1024*1024];new Random(42).nextBytes(p.payload);p.declared=p.payload.length;
   LocalControlServer.Resource r=open(p);check(r.body==null&&r.stream!=null,"Large media is streamed");
   check(p.readBytes<=8192,"Only prefix inspected before forwarding");
   check(p.last.headers.get("Cookie").equals("auth=latest")&&p.last.headers.get("Referer").contains("official"),"Keep current upstream authentication");
   Peer peer=new Peer();LocalControlServer.transfer(peer,r);
   check(Arrays.equals(body(peer),p.payload),"8MB transfer byte exact");
   check(p.inputClosed&&!p.last.disconnected,"Completed body closed while retaining pool");
   check(peer.text().contains("Access-Control-Allow-Private-Network: true")&&peer.text().contains("Access-Control-Allow-Origin: *"),"Keep browser/PNA headers");
   r=open(p);p.inputClosed=false;r.close();r.close();check(p.inputClosed&&p.last.disconnected,"Early cancellation disconnects once");
   Peer broken=new Peer();broken.fail=true;r=open(p);LocalControlServer.transfer(broken,r);
   check(p.last.disconnected&&p.inputClosed,"Client abort cancels upstream");
   check(!broken.text().contains("500 Internal"),"Never append a second HTTP response");
   p.failAfter=32768;r=open(p);peer=new Peer();LocalControlServer.transfer(peer,r);
   check(p.last.disconnected&&!peer.text().contains("服务器错误"),"Upstream read failure cancels without JSON tail");p.failAfter=-1;
   p.payload="short".getBytes(StandardCharsets.UTF_8);p.declared=100;r=open(p);peer=new Peer();LocalControlServer.transfer(peer,r);
   check(p.last.disconnected&&peer.text().contains("Content-Length: 100"),"Truncation stays detectable by the client");
   p.declared=-1;r=open(p);peer=new Peer();LocalControlServer.transfer(peer,r);
   check(peer.text().contains("Transfer-Encoding: chunked")&&peer.text().endsWith("0\r\n\r\n"),"Unknown length has explicit complete EOF");
   check(!p.last.disconnected,"Unknown-length complete body retains pool");
   p.payload=new byte[100000];p.failAfter=32768;r=open(p);peer=new Peer();LocalControlServer.transfer(peer,r);
   check(!peer.text().endsWith("0\r\n\r\n")&&p.last.disconnected,"Failed unknown-length body has no successful EOF");p.failAfter=-1;
   int previous=p.opens;p.failOpen=1;r=open(p);r.close();check(p.opens==previous+2,"Opening failure retries before response");
   p.payload="#EXTM3U\n#EXTINF:3,\nseg.ts?sign=x%2By\n".getBytes(StandardCharsets.UTF_8);p.declared=p.payload.length;
   r=open(p);check(r.stream==null&&new String(r.body,StandardCharsets.UTF_8).contains("/api/recording/resource/"),"Disguised playlist is rewritten, not streamed raw");
   check(p.redirectedBase.contains("redirect.test/edge"),"Redirect target is relative URI base");
   boolean redirectToken=false;for(String token:p.recordingTokens.keySet())if(p.decodeProxyToken(token).equals("https://redirect.test/edge/seg.ts?sign=x%2By"))redirectToken=true;
   check(redirectToken,"Signed redirect-relative segment remains byte exact");
   int priorFetches=p.fetches;previous=p.opens;
   for(int mode=0;mode<6;mode++){
    p.encrypted=mode==0;p.special=mode==1;p.cjs=mode==2;p.prefetched=mode==3;p.known=mode==4;
    String url="https://cdn.test/media.ts?sign=a%2Bb";if(mode==5)p.dolbyInitUrls.add(url);
    r=open(p);check(r.stream==null,"Transform/known-manifest guard "+mode);
   }
   check(p.opens==previous&&p.fetches==priorFetches+6,"Encrypted/prefetched/Dolby routes retain original processing");
   boolean rejected=false;try{p.openForRecording("https://site.test/","not-registered","/");}catch(IOException e){rejected=true;}check(rejected,"No arbitrary proxy URLs");
   p.running=false;rejected=false;try{open(p);}catch(IOException e){rejected=true;}check(rejected,"Stopped session cannot open new stream");
   fileDownloads();allocations();
   System.out.println("PASS "+checks+" recording/file stream checks: integrity, transforms, auth, redirects, retries, memory bound, cancellation and HTTP framing");
  }finally{p.serverSocket.close();}
 }
}
class NetworkClient {
 static Fake next;
 static HttpURLConnection open(URL u){return next;}
 static class Fake extends HttpURLConnection {
  byte[] payload={1,2,3,4,5};long length=5;int status=200;String range;boolean disconnected,closed;
  final Map<String,String> request=new HashMap<>();
  Fake()throws IOException{super(new URL("https://file.test/movie.mp4"));}
  public void connect(){} public void disconnect(){disconnected=true;}public boolean usingProxy(){return false;}
  public int getResponseCode(){return status;}public String getContentType(){return "video/mp4";}
  public String getHeaderField(String k){return k.equals("Content-Length")?(length<0?null:Long.toString(length)):k.equals("Content-Range")?range:null;}
  public void setRequestProperty(String k,String v){request.put(k,v);}
  public InputStream getInputStream(){return new ByteArrayInputStream(payload){public void close(){closed=true;}};}
 }
}
class BuildConfig {static final String VERSION_NAME="test";}
class AllocationCounter {
 final String platform;final java.lang.reflect.Method method;final Object bean;final Object[] args;
 AllocationCounter()throws Exception{
  Class<?> android=null;try{android=Class.forName("android.os.Debug");}catch(ClassNotFoundException ignored){}
  if(android!=null){platform="ANDROID";android.getMethod("startAllocCounting").invoke(null);method=android.getMethod("getThreadAllocSize");bean=null;args=new Object[0];}
  else{platform="HOST_JVM";bean=Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null);method=Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes",long.class);args=new Object[]{Thread.currentThread().getId()};}
 }
 long read()throws Exception{return ((Number)method.invoke(bean,args)).longValue();}
}
'''
jdk=Path('C:/Users/Administrator/.jdks/jbr-17.0.14/bin')
with tempfile.TemporaryDirectory(prefix='ntv-transfer-') as directory:
    tmp=Path(directory)
    for name,text in [('LocalControlServer.java',local_java),('HlsProxyServer.java',hls_java),('TransferCheck.java',check_java)]:
        (tmp/name).write_text(text,encoding='utf8')
    sources=[root/'app/src/main/java/xiao/bu/tv'/name for name in ['HlsUri.java','HlsRecordingPlaylist.java','BoundedResponseReader.java','MediaFileDownload.java']]
    sources+=[root/'browser-core/litebrowser/src/main/java/xiao/bu/tv/LiteChunkedOutputStream.java',*tmp.glob('*.java')]
    subprocess.run([str(jdk/'javac.exe'),'--release','8','-encoding','UTF-8','-d',directory,*map(str,sources)],check=True)
    subprocess.run([str(jdk/'java.exe'),'-Xmx64m','-cp',directory,'xiao.bu.tv.TransferCheck'],check=True)
    if len(sys.argv)>1:
        destination=Path(sys.argv[1]);destination.mkdir(parents=True,exist_ok=True)
        classes=tmp/'checks.jar'
        with zipfile.ZipFile(classes,'w') as archive:
            for item in tmp.rglob('*.class'):archive.write(item,item.relative_to(tmp).as_posix())
        d8=max(Path('D:/android/sdk/build-tools').glob('*/lib/d8.jar'),key=lambda p:tuple(map(int,p.parent.parent.name.split('.'))))
        subprocess.run([str(jdk/'java.exe'),'-cp',str(d8),'com.android.tools.r8.D8','--min-api','19','--output',str(destination),'--lib','D:/android/sdk/platforms/android-35/android.jar',str(classes)],check=True)
        with zipfile.ZipFile(destination/'transfer-check.jar','w') as archive:archive.write(destination/'classes.dex','classes.dex')
