"""Execute the production Android MSE control-dispatch block across queued renewals."""
from pathlib import Path
import os,subprocess,tempfile
root=Path(__file__).resolve().parents[1]
s=(root/'browser-core/litebrowser/src/main/java/xiao/bu/tv/LiteEngine.java').read_text('utf8');a=s.index('      if(op.equals("mediaControl"))');b=s.index('      if(op.equals("intlDate"))',a);block=s[a:b]
jar=next(p for p in (Path.home()/'.gradle/caches/modules-2/files-2.1/org.json/json').rglob('*.jar') if not p.name.endswith(('-sources.jar','-javadoc.jar')))
java='''package xiao.bu.tv;
import org.json.*;import java.util.*;import java.util.concurrent.atomic.*;
class LiteMseRelay {final String key;LiteMseRelay(String k){key=k;}String url(){return "http://127.0.0.1/"+key;}}
class Check{
 final AtomicBoolean stopped=new AtomicBoolean(),mediaReady=new AtomicBoolean();int mseOwner=1,publishedMseOwner=1,epoch=1;LiteMseRelay mse=new LiteMseRelay("A");
 final List<Runnable> queued=new ArrayList<>();final List<String> controls=new ArrayList<>();final UI ui=new UI();final Listener listener=new Listener();
 class UI{void post(Runnable r){queued.add(r);}}class Listener{void mediaControl(String source,boolean virtualMse,boolean paused){controls.add(source+":"+paused);}}
 int selectionEpoch(){return epoch;}
 String control(boolean useMse,boolean pauseIntent)throws Exception{JSONObject o=new JSONObject().put("source",useMse?"":"https://fixture.test/live.m3u8").put("virtualMse",useMse).put("paused",pauseIntent);String op="mediaControl";
'''+block+'''return "null";}
 void drain(){for(Runnable r:queued)r.run();queued.clear();}
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[]args)throws Exception{
  Check c=new Check();c.control(true,true);c.drain();check(c.controls.isEmpty());
  c.mediaReady.set(true);c.control(true,true);c.mseOwner=2;c.publishedMseOwner=2;c.mse=new LiteMseRelay("B");c.drain();check(c.controls.isEmpty());
  c.control(true,true);c.drain();check(c.controls.equals(Arrays.asList("http://127.0.0.1/B:true")));
  c.controls.clear();c.control(true,false);c.mse=new LiteMseRelay("C");c.drain();check(c.controls.isEmpty());
  c.control(true,false);c.mediaReady.set(false);c.drain();check(c.controls.isEmpty());
  c.mediaReady.set(true);c.control(true,false);c.epoch++;c.drain();check(c.controls.isEmpty());
  c.control(false,false);c.mseOwner++;c.drain();check(c.controls.equals(Arrays.asList("https://fixture.test/live.m3u8:false")));
  System.out.println("PASS production control queue: MSE owner/relay/readiness/epoch guards, published source identity, current pause/resume and direct-stream preservation");
 }
}'''
with tempfile.TemporaryDirectory() as folder:
 p=Path(folder);(p/'Check.java').write_text(java,'utf8');bin=Path(os.environ['JAVA_HOME'])/'bin'
 subprocess.run([str(bin/'javac'),'--release','8','-encoding','UTF-8','-cp',str(jar),'-d',folder,str(p/'Check.java')],check=True)
 subprocess.run([str(bin/'java'),'-cp',folder+os.pathsep+str(jar),'xiao.bu.tv.Check'],check=True)
