#!/usr/bin/env python3
"""Offline production ProcessDns concurrency/deadline tests. No network or Android runtime."""
import pathlib, subprocess, tempfile, shutil, argparse
HERE=pathlib.Path(__file__).resolve().parent
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('source', nargs='?', type=pathlib.Path)
parser.add_argument('--android-jar', type=pathlib.Path)
args=parser.parse_args()
source=args.source or (HERE/'ProcessDns.java' if (HERE/'ProcessDns.java').exists() else HERE.parents[1]/'app/src/main/java/xiao/bu/tv/ProcessDns.java')
JAVA=r'''
package xiao.bu.tv;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.Dns;
public class ProcessDnsTest {
 static int checks;
 static synchronized void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static List<InetAddress> ip(int n)throws UnknownHostException{return Arrays.asList(InetAddress.getByAddress(new byte[]{127,0,0,(byte)n}));}
 static void blocked(CountDownLatch l){boolean done=false;while(!done)try{l.await();done=true;}catch(InterruptedException ignored){}}
 static void fail(ProcessDns d,String h,String why)throws Exception{try{d.lookup(h);throw new AssertionError("expected failure "+h);}catch(UnknownHostException e){check(e.getMessage().contains(why),e.toString());}}
 static void settled()throws Exception{Thread.sleep(60);}
 public static void main(String[] args)throws Exception{
  final AtomicInteger calls=new AtomicInteger();final List<InetAddress> original=new ArrayList<InetAddress>(ip(1));
  ProcessDns cache=new ProcessDns(new Dns(){public List<InetAddress> lookup(String h){calls.incrementAndGet();return original;}},200);
  List<InetAddress> result=cache.lookup("Example.COM");original.clear();check(result.size()==1,"defensive copy");
  try{result.clear();throw new AssertionError("mutable");}catch(UnsupportedOperationException expected){checks++;}
  check(cache.lookup("example.com")==result&&calls.get()==1,"case-insensitive success cache");
  cache.invalidate("EXAMPLE.com");original.addAll(ip(2));check(cache.lookup("example.com").equals(ip(2))&&calls.get()==2,"invalidate success");settled();
  final AtomicInteger retries=new AtomicInteger();ProcessDns retry=new ProcessDns(new Dns(){public List<InetAddress> lookup(String h)throws UnknownHostException{if(retries.incrementAndGet()==1)throw new UnknownHostException("fixture");return ip(1);}},200);
  fail(retry,"retry.example","retry.example");check(retry.lookup("retry.example").equals(ip(1))&&retries.get()==2,"failure not cached");settled();
  fail(cache,"","Empty hostname");fail(cache,null,"Empty hostname");
  final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);final AtomicInteger sharedCalls=new AtomicInteger();
  final ProcessDns shared=new ProcessDns(new Dns(){public List<InetAddress> lookup(String h)throws UnknownHostException{sharedCalls.incrementAndGet();entered.countDown();blocked(release);return ip(1);}},90);
  ExecutorService callers=Executors.newFixedThreadPool(12);
  Future<?> a=callers.submit(new Callable<Object>(){public Object call()throws Exception{fail(shared,"shared.example","timed out");return null;}});
  check(entered.await(1,TimeUnit.SECONDS),"worker entered");
  Future<?> b=callers.submit(new Callable<Object>(){public Object call()throws Exception{fail(shared,"SHARED.example","timed out");return null;}});
  a.get(2,TimeUnit.SECONDS);b.get(2,TimeUnit.SECONDS);fail(shared,"shared.example","timed out");check(sharedCalls.get()==1,"timeouts preserve shared inflight");
  final AtomicBoolean interruptPreserved=new AtomicBoolean();Thread interrupted=new Thread(new Runnable(){public void run(){Thread.currentThread().interrupt();try{shared.lookup("shared.example");}catch(UnknownHostException expected){interruptPreserved.set(Thread.currentThread().isInterrupted());}}});
  interrupted.start();interrupted.join(1000);check(interruptPreserved.get(),"caller interruption preserved");
  release.countDown();settled();check(shared.lookup("shared.example").equals(ip(1))&&sharedCalls.get()==1,"late successful result remains shared/cacheable");
  final AtomicInteger burstCalls=new AtomicInteger();final ProcessDns burst=new ProcessDns(new Dns(){public List<InetAddress> lookup(String h)throws UnknownHostException{int delay=100+Integer.parseInt(h.substring(1,h.indexOf('.')))%5*100;try{Thread.sleep(delay);}catch(InterruptedException e){Thread.currentThread().interrupt();}burstCalls.incrementAndGet();return ip(1);}});
  long burstStart=System.nanoTime();List<Future<List<InetAddress>>> burstResults=new ArrayList<Future<List<InetAddress>>>();
  for(int i=0;i<10;i++){final String host="h"+i+".example";burstResults.add(callers.submit(new Callable<List<InetAddress>>(){public List<InetAddress> call()throws Exception{return burst.lookup(host);}}));}
  for(Future<List<InetAddress>> f:burstResults)check(f.get(4,TimeUnit.SECONDS).equals(ip(1)),"normal ten-host burst succeeds");
  long burstMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-burstStart);check(burstCalls.get()==10&&burstMs<3500,"100-500ms burst is bounded: "+burstMs);System.out.println("BURST 10/10 distinct hosts at 100-500ms: "+burstMs+"ms");settled();
  java.lang.reflect.Field poolField=ProcessDns.class.getDeclaredField("LOOKUPS");poolField.setAccessible(true);final ThreadPoolExecutor pool=(ThreadPoolExecutor)poolField.get(null);
  final CountDownLatch two=new CountDownLatch(2),unblock=new CountDownLatch(1);final AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger(),actual=new AtomicInteger();
  final ProcessDns saturated=new ProcessDns(new Dns(){public List<InetAddress> lookup(String h)throws UnknownHostException{actual.incrementAndGet();int n=active.incrementAndGet();synchronized(peak){peak.set(Math.max(peak.get(),n));}two.countDown();blocked(unblock);active.decrementAndGet();return ip(1);}},600);
  List<Future<?>> blockedCallers=new ArrayList<Future<?>>();for(int i=0;i<10;i++){final String host="blocked"+i+".example";blockedCallers.add(callers.submit(new Callable<Object>(){public Object call()throws Exception{fail(saturated,host,"timed out");return null;}}));}
  check(two.await(1,TimeUnit.SECONDS),"both fixed workers running");long queueWait=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);while(pool.getQueue().size()<8&&System.nanoTime()<queueWait)Thread.sleep(1);check(pool.getQueue().size()==8,"bounded eight-task queue");
  long start=System.nanoTime();for(int i=0;i<30;i++)fail(saturated,"busy"+i+".example","busy");
  check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<300,"over-capacity fails fast");
  for(Future<?> f:blockedCallers)f.get(2,TimeUnit.SECONDS);check(pool.getQueue().isEmpty(),"expired queued work removed");check(actual.get()==2,"expired queued work never resolves");check(peak.get()==2&&active.get()==2,"maximum two uninterruptible workers");
  int workers=0;for(Thread t:Thread.getAllStackTraces().keySet())if(t.isAlive()&&t.getName().equals("ntv-dns-resolver")){workers++;check(t.isDaemon(),"daemon worker");}check(workers<=2,"fixed thread bound");
  unblock.countDown();settled();check(saturated.lookup("busy0.example").equals(ip(1)),"rejection does not poison host");settled();
  final CountDownLatch oldIn=new CountDownLatch(1),oldOut=new CountDownLatch(1);final AtomicInteger versions=new AtomicInteger();
  final ProcessDns race=new ProcessDns(new Dns(){public List<InetAddress> lookup(String h)throws UnknownHostException{if(versions.incrementAndGet()==1){oldIn.countDown();blocked(oldOut);throw new UnknownHostException("old failure");}return ip(2);}},100);
  Future<?> old=callers.submit(new Callable<Object>(){public Object call()throws Exception{fail(race,"race.example","timed out");return null;}});
  check(oldIn.await(1,TimeUnit.SECONDS),"old inflight started");old.get();race.invalidate("RACE.example");check(race.lookup("race.example").equals(ip(2)),"replacement succeeds");oldOut.countDown();settled();check(race.lookup("race.example").equals(ip(2))&&versions.get()==2,"old failure cannot erase replacement");
  final CountDownLatch slowRelease=new CountDownLatch(1);ProcessDns production=new ProcessDns(new Dns(){public List<InetAddress> lookup(String h)throws UnknownHostException{blocked(slowRelease);return ip(1);}});
  start=System.nanoTime();fail(production,"slow.example","timed out");long ms=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);slowRelease.countDown();check(ms>=4500&&ms<6500,"production five-second wall deadline: "+ms);
  callers.shutdownNow();System.out.println("PASS "+checks+" assertions: real ProcessDns 5s wall deadline, two-worker/eight-queue cap, normal ten-host bursts, expired queued-task cleanup, sharing, interruption, saturation recovery, immutable cache and invalidation race");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='ntv-dns-test-') as td:
 p=pathlib.Path(td);(p/'xiao/bu/tv').mkdir(parents=True);(p/'okhttp3').mkdir()
 shutil.copyfile(source,p/'xiao/bu/tv/ProcessDns.java');(p/'xiao/bu/tv/ProcessDnsTest.java').write_text(JAVA)
 (p/'okhttp3/Dns.java').write_text('package okhttp3; public interface Dns { java.util.List<java.net.InetAddress> lookup(String host) throws java.net.UnknownHostException; }')
 compiler=[shutil.which('javac')] if shutil.which('javac') else ['java','com.sun.tools.javac.Main']
 subprocess.run(compiler+['-source','8','-target','8','-d',str(p)]+[str(x) for x in p.rglob('*.java')],check=True)
 subprocess.run(['java','-cp',str(p),'xiao.bu.tv.ProcessDnsTest'],check=True,timeout=20)
 if args.android_jar:
  target=p/'api19';target.mkdir()
  subprocess.run(compiler+['-source','8','-target','8','-bootclasspath',str(args.android_jar),'-d',str(target),str(p/'okhttp3/Dns.java'),str(p/'xiao/bu/tv/ProcessDns.java')],check=True)
  print('PASS real API19 compile: production ProcessDns; only okhttp3.Dns declaration stub')
