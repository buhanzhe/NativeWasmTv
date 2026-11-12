"""Run production scan cancellation/drain methods through blocked-probe races."""
import os,subprocess,tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
source=(root/'app/src/main/java/xiao/bu/tv/ChannelHealth.java').read_text('utf8')
def method(marker):
 start=source.index(marker);end=source.index('{',start)+1;depth=1
 while depth:
  depth+=(source[end]=='{')-(source[end]=='}');end+=1
 return source[start:end]
java=r'''import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
class CancelCheck {
 static class SystemClock {static long elapsedRealtime(){return 100;}}
 static class AppLog {static void i(String a,String b){}}
 static class JSONObject {Map<String,Object> values=new HashMap<>();JSONObject put(String k,Object v){values.put(k,v);return this;}}
 static class Channel {String name;String[] urls;Channel(String n){name=n;urls=new String[]{n};}}
 static class State {int status,source;State(int s){status=s;}}
 static class EntryCheck {int cancelled;void cancel(){cancelled++;}}
 static class Call {int cancelled;void cancel(){cancelled++;}}
 static class SniffedMediaProbe {int cleared;void clear(){cleared++;}}
 static class LiteEngine {int closed;void close(){closed++;}}
 static class Handler {int posts;void post(Runnable r){posts++;r.run();}}
 final Map<String,Channel> queue=new LinkedHashMap<>();final Map<String,State> cache=new HashMap<>();
 final Map<String,EntryCheck> entries=new LinkedHashMap<>();final AtomicInteger epoch=new AtomicInteger();
 Handler main=new Handler();Runnable changed=()->{};boolean closed,allowed,scanning,draining,scanItem,cancelled;
 long sessionId=1,scanId=7,revision=2,scanStarted=1,scanFinished;int completed=3,total=10,reused,scanGood=2,scanBad=1,scanUnknown,saved;
 String currentName="blocked";Call webCall=new Call();SniffedMediaProbe media=new SniffedMediaProbe();LiteEngine page=new LiteEngine();
 final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
 State probe(Channel c,int generation){entered.countDown();try{release.await();}catch(Exception e){throw new RuntimeException(e);}return new State(-1);}
 void save(String id,State state){saved++;}void start(){}static String entryUrl(String s){return s;}
 METHODS
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception{
  CancelCheck c=new CancelCheck();c.allowed=!args[0].equals("waiting");c.scanning=true;c.queue.put("a",new Channel("a"));State known=new State(1);c.cache.put("kept",known);EntryCheck entry=new EntryCheck();c.entries.put("a",entry);
  c.cancelAll(6);check(c.scanning&&c.epoch.get()==0,"stale cancellation stopped newer scan");
  Thread drain=null;if(c.allowed){c.draining=true;drain=new Thread(c::drain);drain.start();check(c.entered.await(2,TimeUnit.SECONDS),"probe did not start");}
  boolean allowed=c.allowed;c.cancelAll(7);check(!c.scanning&&c.cancelled&&c.queue.isEmpty(),"scan did not cancel");
  check(c.allowed==allowed&&c.completed==3&&c.total==10&&c.scanGood==2&&c.scanBad==1,"settings/counters changed");
  check(c.cache.get("kept")==known&&entry.cancelled==1&&c.webCall.cancelled==1&&c.media.cleared==1&&c.page.closed==1,"cache or resource cancellation");
  check("cancelled".equals(c.progress().values.get("phase")),"cancelled progress missing");
  int generation=c.epoch.get();c.cancelAll(7);check(c.epoch.get()==generation,"cancel is not idempotent");
  if(drain!=null){c.scanId=8;c.cancelled=false;c.scanning=true;c.queue.put("a",new Channel("a"));c.release.countDown();drain.join(2000);check(!drain.isAlive(),"old probe did not exit");check(c.queue.size()==1&&!c.cache.containsKey("a")&&c.completed==3&&c.saved==0,"old result changed restarted scan");}
  System.out.println("PASS "+args[0]+": cancelled resources, preserved results/settings, stale scan ID and old callback isolation");
 }
}'''
methods='\n'.join(method(m) for m in ['    void pause()','    void cancelAll(','    private void complete(','    private void finishScan(','    private void drain()','    JSONObject progress()'])
java=java.replace('METHODS',methods).replace('okhttp3.Call call=webCall','Call call=webCall')
with tempfile.TemporaryDirectory() as folder:
 path=Path(folder)/'CancelCheck.java';path.write_text(java,'utf8')
 jdk=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
 subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',folder,str(path)],check=True)
 for mode in ['running','waiting']:subprocess.run([str(jdk/'java.exe'),'-cp',folder,'CancelCheck',mode],check=True,timeout=5)
