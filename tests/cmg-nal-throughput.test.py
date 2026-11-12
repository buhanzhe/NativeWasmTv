"""Compare the production NAL transform with its previous implementation byte for byte."""
from pathlib import Path
import subprocess
root=Path(__file__).resolve().parents[1]
out=root/'.codex-tmp/cmg-nal-throughput';out.mkdir(parents=True,exist_ok=True)
path='app/src/main/java/xiao/bu/tv/HlsProxyServer.java'
old=(root/'tests/fixtures/cmg-nal-reference.java').read_text(encoding='utf-8')
new=(root/path).read_text(encoding='utf-8')
def method(source,start,end):return source[source.index(start):source.index(end,source.index(start))]
common=r"""
 static final Object TAG=new Object();static boolean cmgLiveVideoDecodeEnabled=true;static int cmgVclSinceRuntimeRestart;
 static final java.util.List<Integer> calls=new java.util.ArrayList<Integer>();
 static void restartCmgRuntimeAtAccessUnitBoundary(int t){}static void advanceCmgSessionForNal(int t){calls.add(t);}
 static boolean needsCmgNalDecode(int t){return t==1||t==5;}static boolean needsCmgStateDecode(int t){return t==7;}
 static void updateCmgLiveVideoFlag(int t,byte[] b){}static void sanitizeH264SpsMarker(byte[] d,int a,int z,boolean v){if(v&&z-a>=3)d[a+2]&=0xfc;}
 static boolean bytesDiffer(byte[] a,byte[] b){return !java.util.Arrays.equals(a,b);}
 static final class SystemClock{static long elapsedRealtime(){return 0;}}
 static final class AppLog{static void w(Object t,String s){}static void i(Object t,String s){}}
 static final class NativeCmgDecryptor{
  static byte[] decodeNalForProbe(byte[] b,boolean a,boolean c){return b.clone();}
  static int decodeNalRangeInPlace(byte[] b,int o,int n,boolean a,boolean c){
   int mode=n>1?(b[o+1]&255)%6:0;if(mode==4)return -1;if(mode==5)return -2;
   int len=Math.max(0,n-mode);for(int i=1;i<len;i++)b[o+i]^=0x55;return len;
  }
 }
 static final class PesBuffer{byte[] b,result;int length;PesBuffer(byte[] in){length=in.length;b=java.util.Arrays.copyOf(in,in.length+1000);}byte[] data(){return b;}int payloadSize(){return length;}void copyPayloadToTransportStream(byte[] ts,byte[] b,int n){result=java.util.Arrays.copyOf(b,n);}}
"""
(out/'Old.java').write_text(old,encoding='utf-8')
for name,source in [('New',new)]:
 body=method(source,'    private static void decryptPesNals(','    private static boolean bytesDiffer')
 body=body.replace('private static void decryptPesNals','static void decryptPesNals')
 scan=method(source,'    private static int startCodeLength(','    private static Resolution parseSps')
 (out/(name+'.java')).write_text('package xiao.bu.tv;import java.io.*;class '+name+'{'+common+scan+body+'}',encoding='utf-8')
check=r"""
package xiao.bu.tv;import java.util.*;import java.io.*;
public class NalCheck{
 static void check(boolean b){if(!b)throw new AssertionError();}
 static int slow(byte[] b,int a,int z){for(int i=a;i<z-4;i++)if(Old.startCodeLength(b,i)>0)return i;return z;}
 public static void main(String[] args)throws Exception{
  Random r=new Random(715);long count=0;
  for(int round=0;round<10000;round++){
   ByteArrayOutputStream out=new ByteArrayOutputStream();
   for(int n=r.nextInt(25);n>0;n--){if(r.nextBoolean())out.write(0);out.write(0);out.write(0);out.write(1);out.write(new int[]{1,5,6,7,9}[r.nextInt(5)]);for(int j=r.nextInt(200);j>0;j--)out.write(2+r.nextInt(254));}
   for(int n=r.nextInt(6);n>0;n--)out.write(0);
   byte[] b=out.toByteArray();Old.PesBuffer a=new Old.PesBuffer(b);New.PesBuffer c=new New.PesBuffer(b);
   Old.calls.clear();New.calls.clear();Old.decryptPesNals(null,a,true);New.decryptPesNals(null,c,true);
   check(Arrays.equals(a.result,c.result)&&Old.calls.equals(New.calls));count+=Old.calls.size();
   byte[] fuzz=new byte[r.nextInt(300)];r.nextBytes(fuzz);for(int i=0;i<fuzz.length;i++)if(r.nextBoolean())fuzz[i]&=1;
   for(int i=0;i<=fuzz.length;i++)check(slow(fuzz,i,fuzz.length)==H264AnnexB.find(fuzz,i,fuzz.length));
  }
  System.out.println("PASS 10000 PES cases, "+count+" ordered NAL calls, shrinking/rejected/unchanged NALs, mixed prefixes, tails and randomized scanner boundaries");
  byte[] b=new byte[2*1024*1024];r.nextBytes(b);b[b.length-8]=0;b[b.length-7]=0;b[b.length-6]=1;
  for(int i=0;i<10;i++){slow(b,0,b.length);H264AnnexB.find(b,0,b.length);}
  long start=System.nanoTime(),sum=0;for(int i=0;i<50;i++)sum+=slow(b,0,b.length);long oldNs=System.nanoTime()-start;
  start=System.nanoTime();for(int i=0;i<50;i++)sum-=H264AnnexB.find(b,0,b.length);long newNs=System.nanoTime()-start;check(sum==0);
  System.out.println("Scanner benchmark oldMs="+oldNs/1000000+" newMs="+newNs/1000000);
 }
}
"""
(out/'NalCheck.java').write_text(check)
subprocess.run(['javac','--release','8','-encoding','UTF-8','-d',str(out),str(root/'app/src/main/java/xiao/bu/tv/H264AnnexB.java'),str(out/'Old.java'),str(out/'New.java'),str(out/'NalCheck.java')],check=True)
subprocess.run(['java','-cp',str(out),'xiao.bu.tv.NalCheck'],check=True,timeout=40)
