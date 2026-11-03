"""Run production prefetch/fetch dispatch and reject encrypted decoder input."""
from pathlib import Path
import os,subprocess
root=Path(__file__).resolve().parents[1]
proxy=(root/'app/src/main/java/xiao/bu/tv/HlsProxyServer.java').read_text('utf-8')
methods=proxy[proxy.index('    private byte[] genericPrefetchedSegment('):proxy.index('    private boolean genericStartupReady(')]
fetch=proxy[proxy.index('    private ProxyResponse fetchOnce('):proxy.index('        HttpURLConnection connection = openUpstreamConnection(originUrl);',proxy.index('    private ProxyResponse fetchOnce('))]
fetch+='        return new ProxyResponse("video/MP2T",downloadRaw(originUrl));\n    }\n'
harness=r'''package xiao.bu.tv;
import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.*;
public class HlsPrefetchTransformCheck {
 boolean running=true,playbackSuspended;int genericStartupPrefetchSegments;
 final Map<String,FutureTask<byte[]>> genericSegmentTasks=new LinkedHashMap<>();
 final Set<String> consumedGenericSegments=new LinkedHashSet<>();
 final ExecutorService genericPrefetchWorkers=Executors.newFixedThreadPool(3);
 int transformed,decrypted,raw;boolean invalid;
 static class AesSegmentKey {}
 static class SystemClock {static long elapsedRealtime(){return 0;}}
 static class ProxyResponse {final byte[] body;ProxyResponse(String type,byte[] body){this.body=body;}}
 boolean hasAesSegmentKey(String u){return u.contains("aes");}
 AesSegmentKey aesSegmentKey(String u){return hasAesSegmentKey(u)?new AesSegmentKey():null;}
 boolean needsCjsTransform(String u){return u.contains("cjs");}
 boolean canStreamWithoutRewrite(String u){return false;}
 boolean isTransportStream(String u,Object type){return u.endsWith(".ts");}
 boolean needsH5eDecrypt(String u){return false;}
 boolean needsCmgDecrypt(String u){return false;}
 byte[] getCctvSegment(String u){throw new AssertionError();}
 byte[] getCmgSegment(String u){throw new AssertionError();}
 byte[] downloadRaw(String u){raw++;return new byte[]{99};}
 byte[] transformCjsSegment(String u)throws IOException{transformed++;if(invalid)throw new IOException("rejected transform");return new byte[]{42};}
 byte[] decryptAes128Segment(String u,AesSegmentKey k){decrypted++;return new byte[]{7};}
 METHODS
 FETCH
 static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
 static void checkCase(String name,int expected)throws Exception {
  HlsPrefetchTransformCheck p=new HlsPrefetchTransformCheck();
  try {
   String url="http://fixture.test/"+name+".ts";
   p.prefetchGenericSegments(Collections.singletonList(url),2);
   check(p.fetchOnce(url).body[0]==expected,name+" prefetch leaked raw bytes");
   check(p.fetchOnce(url).body[0]==expected,name+" normal fetch changed representation");
   check(name.contains("aes")?p.decrypted==2:name.contains("cjs")?p.transformed==2:p.raw==2,"wrong transform count");
  }finally{p.genericPrefetchWorkers.shutdownNow();}
 }
 public static void main(String[] a)throws Exception {
  checkCase("cjs",42);checkCase("aes",7);checkCase("clear",99);checkCase("aes-cjs",7);
  HlsPrefetchTransformCheck p=new HlsPrefetchTransformCheck();p.invalid=true;
  try {
   String url="http://fixture.test/cjs-invalid.ts";
   p.prefetchGenericSegments(Collections.singletonList(url),2);
   try{p.fetchOnce(url);throw new AssertionError("Rejected encrypted segment reached decoder");}
   catch(IOException expected){check(expected.getMessage().contains("rejected transform"),"failure lost");}
   check(p.raw==0,"failure fell back to ciphertext");
  }finally{p.genericPrefetchWorkers.shutdownNow();}
  System.out.println("PASS CJS/AES/plain prefetch and direct-fetch parity; invalid transform never reaches decoder");
 }
}'''.replace('METHODS',methods).replace('FETCH',fetch)
out=root/'.codex-tmp/hls-prefetch-transform';out.mkdir(parents=True,exist_ok=True)
p=out/'HlsPrefetchTransformCheck.java';p.write_text(harness,'utf-8')
java=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
subprocess.run([str(java/'javac.exe'),'-encoding','UTF-8','-d',str(out),str(p)],check=True)
subprocess.run([str(java/'java.exe'),'-cp',str(out),'xiao.bu.tv.HlsPrefetchTransformCheck'],check=True)
