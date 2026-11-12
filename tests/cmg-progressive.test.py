"""Exercise the production incremental TS transformer and shared reader buffer on a JVM."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
java = r'''
package xiao.bu.tv;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
public class ProgressiveCheck {
 static void check(boolean b){if(!b)throw new AssertionError();}
 static byte[] packet(int pid,boolean start,int cc){byte[] p=new byte[188];Arrays.fill(p,(byte)0x55);p[0]=0x47;p[1]=(byte)((pid>>8)|(start?64:0));p[2]=(byte)pid;p[3]=(byte)(0x10|cc);return p;}
 static byte[] join(byte[]...v)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();for(byte[] a:v)b.write(a);return b.toByteArray();}
 static byte[] read(InputStream in)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] p=new byte[71];int n;while((n=in.read(p))>=0)b.write(p,0,n);return b.toByteArray();}
 static CmgTsStream.Decoder decoder=new CmgTsStream.Decoder(){public int videoPid(byte[] p){return p.length>=376?256:-1;}public byte[] decode(byte[] p,int pid){for(int i=0;i<p.length;i+=188)if((((p[i+1]&31)<<8)|(p[i+2]&255))==pid)p[i+20]^=0x33;return p;}};
 public static void main(String[] args)throws Exception{
  byte[] source=join(packet(0,true,0),packet(100,true,0),packet(256,true,5),packet(257,true,1),packet(256,false,8),packet(256,true,10),packet(256,false,11),packet(256,true,0));
  ByteArrayOutputStream untouched=new ByteArrayOutputStream();
  CmgTsStream.Decoder identity=new CmgTsStream.Decoder(){public int videoPid(byte[] p){return p.length>=376?256:-1;}public byte[] decode(byte[] p,int pid){return p;}};
  new CmgTsStream(identity,8192,false).copy(new ByteArrayInputStream(source),untouched::write);
  check(Arrays.equals(source,untouched.toByteArray()));
  for(int chunk:new int[]{1,7,187,188,4096}){
   ProgressiveMediaBuffer b=new ProgressiveMediaBuffer(10000);List<Integer> sizes=new ArrayList<>();
   InputStream fragmented=new ByteArrayInputStream(source){public synchronized int read(byte[] p,int o,int l){return super.read(p,o,Math.min(chunk,l));}};
   new CmgTsStream(decoder,8192).copy(fragmented,p->{sizes.add(p.length);b.append(p);});byte[] cached=b.finish();
   check(sizes.size()==4);check(Arrays.equals(read(b.open()),cached));check(Arrays.equals(read(b.open()),cached));check(cached.length==source.length);
   int cc=4;for(int i=0;i<cached.length;i+=188){int pid=((cached[i+1]&31)<<8)|(cached[i+2]&255);if(pid==256){cc=(cc+1)&15;check((cached[i+3]&15)==cc);check(cached[i+20]==(byte)(source[i+20]^0x33));}else check(Arrays.equals(Arrays.copyOfRange(source,i,i+188),Arrays.copyOfRange(cached,i,i+188)));}
  }
  int[] emitted={0};
  try{new CmgTsStream(decoder,8192).copy(new ByteArrayInputStream(source),p->{emitted[0]++;Thread.currentThread().interrupt();});throw new AssertionError("Cancelled transform completed");}
  catch(InterruptedIOException expected){check(emitted[0]==1);}finally{Thread.interrupted();}
  ExecutorService pool=Executors.newCachedThreadPool();
  try {
   for(int consumed:new int[]{0,1,187,188,189,375,376}){
    ProgressiveMediaBuffer compact=new ProgressiveMediaBuffer(1000);
    byte[] one=packet(100,true,0),two=packet(256,true,1),three=packet(257,true,2);
    compact.append(one);compact.append(two);InputStream partial=compact.open(),independent=compact.open();
    byte[] prefix=new byte[consumed];int pos=0;while(pos<consumed)pos+=partial.read(prefix,pos,consumed-pos);
    compact.append(three);byte[] body=compact.finish();check(compact.finish()==body);
    check(Arrays.equals(join(prefix,read(partial)),body));check(Arrays.equals(read(independent),body));
   }
   ProgressiveMediaBuffer single=new ProgressiveMediaBuffer(1000);byte[] owned=packet(256,true,0);single.append(owned);check(single.finish()==owned);
   ProgressiveMediaBuffer empty=new ProgressiveMediaBuffer(1);check(empty.finish().length==0&&empty.open().read()==-1);
   ProgressiveMediaBuffer known=new ProgressiveMediaBuffer(1000);known.expectLength(188);check(known.expectedLength()==188);known.append(owned);check(Arrays.equals(known.finish(),owned));
   for(int consumed:new int[]{0,1,187,188,189,375,376}){
    ProgressiveMediaBuffer bounded=new ProgressiveMediaBuffer(1000);bounded.expectLength(564);
    byte[] one=packet(100,true,0),two=packet(256,true,1),three=packet(257,true,2);
    bounded.append(one);bounded.append(two);InputStream partial=bounded.open(),independent=bounded.open();
    byte[] prefix=new byte[consumed];int pos=0;while(pos<consumed)pos+=partial.read(prefix,pos,consumed-pos);
    bounded.append(three);byte[] body=bounded.finish();check(bounded.finish()==body);
    check(Arrays.equals(join(prefix,read(partial)),body));check(Arrays.equals(read(independent),body));
   }
   ProgressiveMediaBuffer shortBody=new ProgressiveMediaBuffer(1000);shortBody.expectLength(376);shortBody.append(owned);try{shortBody.finish();throw new AssertionError();}catch(IOException expected){}
   ProgressiveMediaBuffer longBody=new ProgressiveMediaBuffer(1000);longBody.expectLength(188);try{longBody.append(new byte[376]);throw new AssertionError();}catch(IOException expected){}
   ProgressiveMediaBuffer b=new ProgressiveMediaBuffer(10000);b.expectLength(source.length);CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);
   InputStream slow=new ByteArrayInputStream(source){boolean paused;public synchronized int read(byte[] p,int o,int l){if(pos>=188*6&&!paused){paused=true;blocked.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}}return super.read(p,o,pos<188*6?Math.min(l,188*6-pos):l);}};
   Future<?> producer=pool.submit(()->{try{new CmgTsStream(decoder,8192).copy(slow,b::append);b.finish();}catch(Exception e){throw new RuntimeException(e);}});
   check(blocked.await(2,TimeUnit.SECONDS));check(!producer.isDone());InputStream reader=b.open();byte[] early=new byte[188*5];int n=0;while(n<early.length){int k=reader.read(early,n,early.length-n);check(k>0);n+=k;}check(n==188*5);release.countDown();producer.get(2,TimeUnit.SECONDS);read(reader);
   ProgressiveMediaBuffer failed=new ProgressiveMediaBuffer(1000);Future<?> waiting=pool.submit(()->{try{failed.open().read();throw new AssertionError();}catch(IOException expected){}});failed.fail(new IOException("truncated"));waiting.get(2,TimeUnit.SECONDS);try{failed.finish();throw new AssertionError();}catch(IOException expected){}
   ProgressiveMediaBuffer limit=new ProgressiveMediaBuffer(1);try{limit.append(new byte[2]);throw new AssertionError();}catch(IOException expected){}
   ProgressiveMediaBuffer cancel=new ProgressiveMediaBuffer(100);InputStream closed=cancel.open();closed.close();try{closed.read();throw new AssertionError();}catch(IOException expected){}
  }finally{pool.shutdownNow();}
  for(byte[] bad:new byte[][]{Arrays.copyOf(source,source.length-1),new byte[188],new byte[0]})try{new CmgTsStream(decoder,8192).copy(new ByteArrayInputStream(bad),p->{});throw new AssertionError();}catch(IOException expected){}
  try{new CmgTsStream(decoder,188).copy(new ByteArrayInputStream(source),p->{});throw new AssertionError();}catch(IOException expected){}
  System.out.println("PASS: partial network reads, early clear data before EOF, PES ordering, audio/PSI preservation, continuity, independent readers, failure/cancel/size/truncation");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='ntv-cmg-progressive-') as tmp:
    out = Path(tmp)
    sources = []
    for name in ['CmgTsStream', 'ProgressiveMediaBuffer']:
        path = out / (name + '.java')
        path.write_bytes((root / 'app/src/main/java/xiao/bu/tv' / path.name).read_bytes())
        sources.append(str(path))
    test = out / 'ProgressiveCheck.java'
    test.write_text(java, encoding='utf-8')
    subprocess.run(['javac', '-encoding', 'UTF-8', '-d', tmp, *sources, str(test)], check=True)
    subprocess.run(['java', '-cp', tmp, 'xiao.bu.tv.ProgressiveCheck'], check=True, timeout=15)
