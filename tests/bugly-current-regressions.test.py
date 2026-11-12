"""Production numeric migration, blocked codec service and stale Activity windows."""
from pathlib import Path
import os, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
def method(source,name):
 start=source.index(name);end=source.index('{',start)+1;depth=1
 while depth:
  depth+=(source[end]=='{')-(source[end]=='}');end+=1
 return source[start:end]
updater=(root/'app/src/main/java/xiao/bu/tv/AutoUpdater.java').read_text(encoding='utf8')
files={
'android/content/SharedPreferences.java':'''package android.content;import java.util.*;
public class SharedPreferences{
 public final Map<String,Object> data=new HashMap<>();
 public float getFloat(String k,float d){Object v=data.get(k);return v==null?d:(Float)v;}
 public Map<String,?> getAll(){return new HashMap<>(data);}
 public String getString(String k,String d){Object v=data.get(k);return v==null?d:(String)v;}
 public boolean getBoolean(String k,boolean d){Object v=data.get(k);return v==null?d:(Boolean)v;}
 public Set<String> getStringSet(String k,Set<String>d){Object v=data.get(k);return v==null?d:(Set<String>)v;}
 public boolean contains(String k){return data.containsKey(k);}public Editor edit(){return new Editor();}
 public class Editor{public Editor putString(String k,String v){data.put(k,v);return this;}public Editor putStringSet(String k,Set<String> v){data.put(k,v);return this;}public Editor putBoolean(String k,boolean v){data.put(k,v);return this;}public void apply(){}}
}''',
'android/content/Context.java':'''package android.content;public class Context{public final SharedPreferences prefs=new SharedPreferences();public Context getApplicationContext(){return this;}public SharedPreferences getSharedPreferences(String n,int m){return prefs;}}''',
'android/os/Build.java':'package android.os;public class Build{public static String FINGERPRINT="fixture";public static class VERSION{public static int SDK_INT=19;}}',
'android/media/MediaCodecInfo.java':'''package android.media;public class MediaCodecInfo{String name;boolean encoder;String[] types;public MediaCodecInfo(String n,boolean e,String...t){name=n;encoder=e;types=t;}public boolean isEncoder(){return encoder;}public String getName(){return name;}public String[] getSupportedTypes(){return types;}public boolean isHardwareAccelerated(){return !name.startsWith("OMX.google.");}public CodecCapabilities getCapabilitiesForType(String t){return new CodecCapabilities();}public static class CodecProfileLevel{public int profile=2;}public static class CodecCapabilities{public CodecProfileLevel[] profileLevels={new CodecProfileLevel()};}}''',
'android/media/MediaCodecList.java':'''package android.media;import java.util.concurrent.*;import java.util.concurrent.atomic.*;public class MediaCodecList{
 public static final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);public static final AtomicInteger calls=new AtomicInteger();public static boolean fail;
 public static MediaCodecInfo[] codecs={};public static String thread;
 public static int getCodecCount(){calls.incrementAndGet();thread=Thread.currentThread().getName();entered.countDown();try{release.await();}catch(Exception e){throw new RuntimeException(e);}if(fail)throw new IllegalStateException("service unavailable");return codecs.length;}
 public static MediaCodecInfo getCodecInfoAt(int i){return codecs[i];}
}''',
'xiao/bu/tv/AppLog.java':'package xiao.bu.tv;public class AppLog{public static void w(String t,String s,Throwable e){}}',
'android/app/Dialog.java':'package android.app;public class Dialog{public int shown;public RuntimeException failure;public void show(){if(failure!=null)throw failure;shown++;}}',
'android/view/WindowManager.java':'package android.view;public class WindowManager{public static class BadTokenException extends RuntimeException{}}',
'xiao/bu/tv/CurrentCheck.java':r'''package xiao.bu.tv;
import android.content.*;import android.os.Build;import android.media.*;import java.util.*;import java.util.concurrent.*;
public class CurrentCheck{
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static HardwareDecoderInventory.Snapshot await()throws Exception{long end=System.currentTimeMillis()+2000;while(HardwareDecoderInventory.snapshot()==null&&System.currentTimeMillis()<end)Thread.sleep(5);return HardwareDecoderInventory.snapshot();}
 public static void main(String[] args)throws Exception{
  SharedPreferences p=new SharedPreferences();Object[] inputs={1,2L,1.25f," 0.75 ","broken",true,Float.NaN,Float.POSITIVE_INFINITY,Double.MAX_VALUE};float[] expected={1,2,1.25f,.75f,9,9,9,9,9};
  for(int i=0;i<inputs.length;i++){p.data.put("scale",inputs[i]);check(PreferenceNumbers.readFloat(p,"scale",9)==expected[i],"numeric migration "+i);}
  check(PreferenceNumbers.readFloat(p,"absent",9)==9,"missing preference");
  String scenario=args[0];Context c=new Context();
  if(scenario.equals("legacy")){Build.VERSION.SDK_INT=15;HardwareDecoderInventory.warm(c);check(!await().hasVideo&&MediaCodecList.calls.get()==0,"API15 never touches MediaCodecList");return;}
  if(scenario.equals("cache")){c.prefs.data.put("build","fixture");c.prefs.data.put("has_video",true);c.prefs.data.put("avc",new HashSet<>(Arrays.asList("OMX.saved")));}
  if(scenario.equals("hevc"))MediaCodecList.codecs=new MediaCodecInfo[]{new MediaCodecInfo("OMX.vendor.hevc",false,"video/hevc")};
  else if(scenario.equals("avc")||scenario.equals("cache"))MediaCodecList.codecs=new MediaCodecInfo[]{new MediaCodecInfo("OMX.vendor.avc",false,"video/avc")};
  else MediaCodecList.codecs=new MediaCodecInfo[]{new MediaCodecInfo("OMX.google.avc",false,"video/avc"),new MediaCodecInfo("OMX.pv.avc",false,"video/avc"),new MediaCodecInfo("OMX.avcodec.avc",false,"video/avc"),new MediaCodecInfo("OMX.vendor.encoder",true,"video/avc"),new MediaCodecInfo("OMX.vendor.audio",false,"audio/aac")};
  MediaCodecList.fail=scenario.equals("fail");
  long start=System.nanoTime();HardwareDecoderInventory.warm(c);check(MediaCodecList.entered.await(2,TimeUnit.SECONDS),"reader started");
  for(int i=0;i<10000;i++)HardwareDecoderInventory.warm(c);
  check((System.nanoTime()-start)/1000000<1000,"blocked service held caller");check(MediaCodecList.calls.get()==1,"warm calls queued more Binder queries");
  check(!"main".equals(MediaCodecList.thread),"codec Binder called on main");
  if(scenario.equals("cache"))check(await().avc.contains("OMX.saved"),"known cache unavailable during hung query");else check(HardwareDecoderInventory.snapshot()==null,"unknown service became absent hardware");
  MediaCodecList.release.countDown();
  if(scenario.equals("fail")){Thread.sleep(80);check(HardwareDecoderInventory.snapshot()==null,"failed enumeration must remain unknown");}
  else{
   HardwareDecoderInventory.Snapshot result=await();if(scenario.equals("cache")){long end=System.currentTimeMillis()+2000;while(result.avc.contains("OMX.saved")&&System.currentTimeMillis()<end){Thread.sleep(5);result=HardwareDecoderInventory.snapshot();}}
   check(result!=null,"no inventory result");check(result.hasVideo==!scenario.equals("empty"),"hardware status wrong");check(result.avc.isEmpty()==!(scenario.equals("avc")||scenario.equals("cache")),"AVC names wrong");
   try{result.avc.add("mutated");throw new AssertionError("mutable snapshot");}catch(UnsupportedOperationException expectedMutation){}
  }
  System.out.println("PASS numeric legacy values and async codec "+scenario+": blocked service, coalescing, immutable cache, hardware classification");
 }
}''',
'xiao/bu/tv/DialogCheck.java':r'''package xiao.bu.tv;import android.os.Build;
public class DialogCheck{
 boolean destroyed;FakeActivity activity=new FakeActivity();static class FakeActivity{boolean finishing,dead;Object token=new Object();boolean isFinishing(){return finishing;}boolean isDestroyed(){return dead;}FakeActivity getWindow(){return this;}FakeActivity getDecorView(){return this;}Object getWindowToken(){return token;}}
 METHODS
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){DialogCheck c=new DialogCheck();android.app.Dialog dialog=new android.app.Dialog();check(c.showDialog(dialog)&&dialog.shown==1);c.activity.token=null;check(!c.showDialog(dialog)&&dialog.shown==1);c.activity.token=new Object();c.activity.dead=true;check(!c.showDialog(dialog));c.activity.dead=false;c.destroyed=true;check(!c.showDialog(dialog));c.destroyed=false;dialog.failure=new android.view.WindowManager.BadTokenException();check(!c.showDialog(dialog));dialog.failure=new IllegalStateException();check(!c.showDialog(dialog));System.out.println("PASS valid, unattached, destroyed and raced window-token update dialogs");}
}'''.replace('METHODS',method(updater,'    private boolean canShowDialog()')+'\n'+method(updater,'    private boolean showDialog(')),
'xiao/bu/tv/TenBitCheck.java':r'''package xiao.bu.tv;
import android.os.Build;import android.media.*;import java.util.*;import java.util.concurrent.*;
public class TenBitCheck{
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] args)throws Exception{
  check(TenBitVideoSupport.isTenBit("yuv420p10le","")&&!TenBitVideoSupport.isTenBit("yuv420p","main10"),"pixel format precedence");
  check(TenBitVideoSupport.matchesProfile("video/hevc","main10",2)&&!TenBitVideoSupport.matchesProfile("video/avc","high10",2),"profile matching");
  if(args[0].equals("legacy")){Build.VERSION.SDK_INT=15;TenBitVideoSupport.checkAsync("video/hevc","main10",r->{throw new AssertionError("legacy callback");});check(MediaCodecList.calls.get()==0,"API15 codec service queried");return;}
  boolean fail=args[0].equals("fail");MediaCodecList.fail=fail;
  MediaCodecList.codecs=new MediaCodecInfo[]{new MediaCodecInfo("OMX.vendor.hevc",false,"video/hevc")};
  List<Boolean> results=Collections.synchronizedList(new ArrayList<>());List<Integer> ids=Collections.synchronizedList(new ArrayList<>());
  TenBitVideoSupport.checkAsync("video/hevc","main10",r->{results.add(r);ids.add(-1);});
  check(MediaCodecList.entered.await(2,TimeUnit.SECONDS),"no background query");
  long start=System.nanoTime();for(int i=0;i<10000;i++){final int id=i;TenBitVideoSupport.checkAsync("video/hevc","main10",r->{results.add(r);ids.add(id);});}
  check((System.nanoTime()-start)/1000000<1000,"query blocked caller");check(MediaCodecList.calls.get()==1&&!MediaCodecList.thread.equals("main"),"query flood or UI query");
  MediaCodecList.release.countDown();long end=System.currentTimeMillis()+2000;while(ids.size()<2&&System.currentTimeMillis()<end)Thread.sleep(5);
  check(ids.equals(Arrays.asList(-1,9999)),"pending requests were not bounded to latest");check(fail?results.get(0)==null:Boolean.TRUE.equals(results.get(0)),"unknown capabilities treated as missing");
  System.out.println("PASS async 10bit "+args[0]+": blocked service, bounded queue and latest advisory");
 }
}''',
}
with tempfile.TemporaryDirectory(prefix='ntv-bugly-current-') as temp:
 out=Path(temp)
 for name,text in files.items():
  path=out/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,encoding='utf8')
 jdk=Path(os.environ['JAVA_HOME'])/'bin'
 sources=[*map(str,out.rglob('*.java')),*[str(root/'app/src/main/java/xiao/bu/tv'/name) for name in ['PreferenceNumbers.java','HardwareDecoderInventory.java','TenBitVideoSupport.java']]]
 subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',temp,*sources],check=True)
 for scenario in ['empty','hevc','avc','cache','fail','legacy']:
  subprocess.run([str(jdk/'java.exe'),'-cp',temp,'xiao.bu.tv.CurrentCheck',scenario],check=True,timeout=6)
 subprocess.run([str(jdk/'java.exe'),'-cp',temp,'xiao.bu.tv.DialogCheck'],check=True,timeout=6)
 for scenario in ['normal','fail','legacy']:
  subprocess.run([str(jdk/'java.exe'),'-cp',temp,'xiao.bu.tv.TenBitCheck',scenario],check=True,timeout=6)
