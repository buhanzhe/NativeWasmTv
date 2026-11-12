"""Run production replacement/retirement methods with queued owner changes."""
import os,pathlib,subprocess,tempfile
root=pathlib.Path(__file__).resolve().parents[1]
s=(root/'browser-core/litebrowser/src/main/java/xiao/bu/tv/LiteEngine.java').read_text('utf8')
a=s.index('  private void closeRetiredMse(');b=s.index('  private int mseOwner',a);helpers=s[a:b]
a=s.index('      if(op.equals("mseReplace"))');b=s.index('      if(op.equals("mseDetach"))',a);replace=s[a:b]
jar=next(p for p in (pathlib.Path.home()/'.gradle/caches/modules-2/files-2.1/org.json/json').rglob('*.jar') if not p.name.endswith(('-sources.jar','-javadoc.jar')))
code='''package xiao.bu.tv;import org.json.*;import java.util.*;import java.util.concurrent.atomic.*;
class LiteMseRelay{int closed;void close(){closed++;}}
class Check{
 final Object handoffLock=new Object();final AtomicBoolean stopped=new AtomicBoolean(),mediaReady=new AtomicBoolean(true);
 LiteMseRelay mse=new LiteMseRelay(),retiredMse;int mseOwner=1,publishedMseOwner=1;boolean mseAttached=true;
 final List<Runnable> pending=new ArrayList<>();final List<Integer> delays=new ArrayList<>();final Worker worker=new Worker();
 class Worker{void postDelayed(Runnable r,int delay){pending.add(r);delays.add(delay);}}
'''+helpers+'''
 String replace(int previous,int owner)throws Exception{String op="mseReplace";JSONObject o=new JSONObject().put("previousOwner",previous).put("owner",owner);
'''+replace+'''return "unknown";}
 static void ok(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[]args)throws Exception{
  Check c=new Check();LiteMseRelay first=c.mse;ok(c.replace(1,2).equals("true"));ok(first.closed==0&&c.retiredMse==first&&c.mse==null&&!c.mediaReady.get()&&c.mseOwner==2&&c.delays.equals(Arrays.asList(10000)));
  Runnable stale=c.pending.get(0);LiteMseRelay unready=new LiteMseRelay();c.mse=unready;c.replace(2,3);ok(unready.closed==1&&c.retiredMse==first);
  LiteMseRelay next=new LiteMseRelay();c.mse=next;c.mediaReady.set(true);c.publishedMseOwner=3;c.replace(3,4);ok(first.closed==1&&next.closed==0&&c.retiredMse==next);
  stale.run();ok(next.closed==0&&c.retiredMse==next);
  c.closeRetiredMse(next);ok(next.closed==1&&c.retiredMse==null);c.pending.get(1).run();ok(next.closed==1);
  c.mse=new LiteMseRelay();ok(c.replace(3,5).equals("false")&&c.mse.closed==0);
  System.out.println("PASS actual replacement/retirement code: published old data retained, one retired transport, ten-second cap, stale timer isolation and early release");
 }
}'''
with tempfile.TemporaryDirectory() as folder:
 p=pathlib.Path(folder)/'Check.java';p.write_text(code,'utf8');bin=pathlib.Path(os.environ['JAVA_HOME'])/'bin'
 subprocess.run([str(bin/'javac'),'--release','8','-encoding','UTF-8','-cp',str(jar),'-d',folder,str(p)],check=True)
 subprocess.run([str(bin/'java'),'-cp',folder+os.pathsep+str(jar),'xiao.bu.tv.Check'],check=True)
