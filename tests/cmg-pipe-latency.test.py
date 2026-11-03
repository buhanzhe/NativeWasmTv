"""Bounded CMG handoff: wakeups, backpressure, byte order, EOF and cancellation."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
out = root / '.codex-tmp/cmg-pipe-latency'
out.mkdir(parents=True, exist_ok=True)
java = r'''
package xiao.bu.tv;
import java.io.*;
import java.util.concurrent.*;
public final class CmgPipeLatencyCheck {
 public static void main(String[] args) throws Exception {
  final CmgBytePipe input = new CmgBytePipe(64 * 1024);
  final OutputStream pipe = input.output();
  final int rounds = 4, count = 3760;
  final CountDownLatch[] received = new CountDownLatch[rounds];
  for(int i=0;i<rounds;i++)received[i]=new CountDownLatch(1);
  final Throwable[] failure = new Throwable[1];
  Thread reader = new Thread(() -> {
   try {
    byte[] data = new byte[count];
    for(int i=0;i<rounds;i++) {
     int n=0,k;while(n<count&&(k=input.read(data,n,count-n))>=0)n+=k;
     if(n!=count)throw new AssertionError("Unexpected EOF");
     for(byte b:data)if(b!=42)throw new AssertionError("Corrupt transfer");
     received[i].countDown();
    }
   } catch(Throwable t){failure[0]=t;}
  },"cmg-pipe-reader");
  reader.setDaemon(true);reader.start();
  byte[] block = new byte[count];java.util.Arrays.fill(block,(byte)42);
  long elapsed=0;
  try {
   for(int i=0;i<rounds;i++) {
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
    while(reader.getState()!=Thread.State.WAITING) {
     if(System.nanoTime()>deadline)throw new AssertionError("Reader did not wait");
     Thread.sleep(1);
    }
    long start=System.nanoTime();
    pipe.write(block, 0, count);
    if(!received[i].await(3,TimeUnit.SECONDS))throw new AssertionError("Reader stuck");
    elapsed+=System.nanoTime()-start;
   }
   reader.join(1000);
   if(failure[0]!=null)throw new AssertionError(failure[0]);
   long ms=TimeUnit.NANOSECONDS.toMillis(elapsed);
   System.out.println("CMG pipe delivery: "+rounds+" blocks in "+ms+"ms");
   if(ms>=1000)throw new AssertionError("Producer left decoder waiting for the pipe polling timeout");
  } finally {pipe.close();input.close();}
  stress(); cancellation();
 }
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 static void stress() throws Exception {
  final CmgBytePipe input=new CmgBytePipe(4096);
  final OutputStream output=input.output();
  final Throwable[] failure={null};
  Thread producer=new Thread(()->{
   try {
    byte[] block=new byte[16384];
    for(int n=0;n<128;n++){
     for(int i=0;i<block.length;i++)block[i]=(byte)(n*block.length+i);
     output.write(block);
    }
    output.close();
   }catch(Throwable t){failure[0]=t;}
  });producer.setDaemon(true);producer.start();
  long started=System.nanoTime();int total=0,n;
  byte[] block=new byte[188];
  while((n=input.read(block))>=0){for(int i=0;i<n;i++)check(block[i]==(byte)(total+i));total+=n;}
  producer.join(1000);check(!producer.isAlive()&&failure[0]==null&&total==2*1024*1024);
  check(input.read()==-1&&input.read(block,0,0)==0);
  check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<5000);
  System.out.println("PASS bounded 2 MiB transfer, wraparound, backpressure, byte order and EOF");
 }
 static void cancellation() throws Exception {
  final CmgBytePipe input=new CmgBytePipe(16);final boolean[] interrupted={false};
  Thread reader=new Thread(()->{try{input.read();}catch(InterruptedIOException e){interrupted[0]=Thread.currentThread().isInterrupted();}catch(IOException e){throw new RuntimeException(e);}});
  reader.setDaemon(true);reader.start();Thread.sleep(20);reader.interrupt();reader.join(1000);check(!reader.isAlive()&&interrupted[0]);input.close();
  final CmgBytePipe full=new CmgBytePipe(16);final boolean[] closed={false};
  full.output().write(new byte[16]);
  Thread writer=new Thread(()->{try{full.output().write(42);}catch(IOException e){closed[0]=true;}});
  writer.setDaemon(true);writer.start();Thread.sleep(20);full.close();writer.join(1000);check(!writer.isAlive()&&closed[0]);
  System.out.println("PASS interrupt wakes blocked reader; close wakes blocked writer");
 }
}
'''
test = out / 'CmgPipeLatencyCheck.java'
test.write_text(java, encoding='utf-8')
production=root/'app/src/main/java/xiao/bu/tv/CmgBytePipe.java'
subprocess.run(['javac', '--release', '8', '-encoding', 'UTF-8', '-d', str(out), str(production), str(test)], check=True)
subprocess.run(['java', '-cp', str(out), 'xiao.bu.tv.CmgPipeLatencyCheck'], check=True, timeout=20)
