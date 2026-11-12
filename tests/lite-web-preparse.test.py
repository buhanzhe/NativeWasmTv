"""Compile the real lease owner: eligibility, profile identity, expiry and ownership."""
from pathlib import Path
import os, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
sources={
'android/content/Context.java':'package android.content; public class Context {}',
'android/os/Looper.java':'package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}',
'android/os/SystemClock.java':'package android.os; public class SystemClock {public static long now;public static long elapsedRealtime(){return now;}}',
'android/os/Handler.java':'''package android.os;import java.util.*;public class Handler {
 public static final List<Runnable> tasks=new ArrayList<>();public Handler(Looper l){}
 public void postDelayed(Runnable r,long delay){tasks.add(r);}public void removeCallbacksAndMessages(Object o){tasks.clear();}
 public static void fire(){List<Runnable> copy=new ArrayList<>(tasks);tasks.clear();for(Runnable r:copy)r.run();}}''',
'xiao/bu/tv/LiteWebPlayback.java':'''package xiao.bu.tv;import android.content.Context;
class LiteWebPlayback {
 interface Host {void stream(String u,boolean t);void prepared();void status(String t);void failure(String t);String userAgent(String p,String f);int networkThreadMultiplier();boolean playing();long position();long duration();int width();int height();void setPaused(boolean p);}
 static LiteWebPlayback last;final Host host;boolean ready,closed;LiteWebPlayback(Context c,Host h){last=this;host=h;}
 void prepare(String p,String s){}void markReady(){ready=true;host.prepared();}boolean prepared(){return ready;}boolean closed(){return closed;}void close(){closed=true;}}
''',
'xiao/bu/tv/AppLog.java':'package xiao.bu.tv;class AppLog{static void i(String a,String b){}static void d(String a,String b){}}',
'xiao/bu/tv/Check.java':'''package xiao.bu.tv;import android.os.*;
class Check {
 static void ok(boolean b){if(!b)throw new AssertionError();}
 static LiteWebPreloader.Request request(String page,String ua,int threads,String settings){return new LiteWebPreloader.Request(page,"script",ua,threads,settings);}
 public static void main(String[] args){
  for(int sdk:new int[]{14,15,18})ok(!LiteWebPreloader.eligible(sdk,4,1L<<30,false));
  ok(!LiteWebPreloader.eligible(19,1,1L<<30,false));ok(!LiteWebPreloader.eligible(19,4,100L<<20,false));ok(!LiteWebPreloader.eligible(25,4,1L<<30,true));ok(LiteWebPreloader.eligible(19,4,200L<<20,false));
  LiteWebPreloader owner=new LiteWebPreloader(new android.content.Context());LiteWebPreloader.Request a=request("https://site/one","windows",0,"profile");
  owner.prepare(a);LiteWebPlayback first=LiteWebPlayback.last;owner.cancelPending();ok(first.closed&&!owner.occupied());
  owner.prepare(a);first=LiteWebPlayback.last;first.markReady();owner.cancelPending();ok(owner.ready());
  owner.prepare(a);ok(owner.ready()&&LiteWebPlayback.last==first&&!first.closed); // a repeat must not discard ready work or renew its deadline
  ok(owner.take(request(a.page,a.userAgent,0,"profile"))==first);ok(!owner.occupied());Handler.fire();ok(!first.closed); // expiry cannot close an adopted foreground session
  for(LiteWebPreloader.Request mismatch:new LiteWebPreloader.Request[]{request("https://site/two","windows",0,"profile"),request(a.page,"ipad",0,"profile"),request(a.page,"windows",2,"profile"),request(a.page,"windows",0,"changed")}){
   owner.prepare(a);LiteWebPlayback.last.markReady();first=LiteWebPlayback.last;ok(owner.take(mismatch)==null&&first.closed);
  }
  owner.prepare(a);first=LiteWebPlayback.last;SystemClock.now+=10000;first.markReady();
  SystemClock.now+=60000;ok(owner.ready()); // keeps the actual page/transport beyond the old 30s limit
  SystemClock.now+=239999;ok(owner.ready()); // 4m59.999s since successful preparation, not page-load start
  SystemClock.now++;ok(!owner.ready()&&owner.take(a)==null&&first.closed);
  owner.prepare(a);first=LiteWebPlayback.last;owner.prepare(a);ok(first.closed);owner.close();ok(!owner.occupied());
  owner.prepare(a);first=LiteWebPlayback.last;first.host.failure("failed");ok(first.closed&&!owner.occupied());
  owner.prepare(a);LiteWebPlayback obsolete=LiteWebPlayback.last;
  LiteWebPreloader.Request b=request("https://site/two","windows",0,"profile");owner.prepare(b);first=LiteWebPlayback.last;
  obsolete.host.prepared();ok(!owner.ready());first.markReady();obsolete.host.failure("late failure");ok(owner.ready()&&!first.closed);
  ok(owner.take(b)==first);first.host.failure("late prepared callback");ok(!first.closed&&!owner.occupied());
  System.out.println("PASS API/core/memory eligibility, cancellation, profile identity, five-minute retention from readiness, expiration and exclusive foreground ownership");
 }
}'''}
with tempfile.TemporaryDirectory() as tmp:
 paths=[]
 for name,value in sources.items():
  p=Path(tmp)/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(value,encoding='utf-8');paths.append(str(p))
 jdk=Path(os.environ['JAVA_HOME'])/'bin'
 subprocess.run([str(jdk/'javac'),'-encoding','UTF-8','-d',tmp,*paths,str(root/'app/src/main/java/xiao/bu/tv/LiteWebPreloader.java')],check=True)
 subprocess.run([str(jdk/'java'),'-cp',tmp,'xiao.bu.tv.Check'],check=True)
