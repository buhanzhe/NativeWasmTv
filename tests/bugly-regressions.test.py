"""Reproduce Bugly 1262 / ANR 5281 / ANR 3229 with production code and blocked services."""
from pathlib import Path
import subprocess,tempfile
root=Path(__file__).resolve().parents[1]
def block(text,signature):
 start=text.index(signature);end=text.index('{',start)+1;depth=1
 while depth:
  depth+=(text[end]=='{')-(text[end]=='}');end+=1
 return text[start:end]
main=(root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf-8')
skin=(root/'app/src/main/java/xiao/bu/tv/skin/SharedTvUi.java').read_text(encoding='utf-8')
files={
'android/media/AudioManager.java':'package android.media;public class AudioManager{public static final int MODE_IN_CALL=2,MODE_IN_COMMUNICATION=3;public int getMode(){return 0;}}',
'android/os/Looper.java':'package android.os;public class Looper{public static Looper getMainLooper(){return new Looper();}}',
'android/os/Handler.java':'package android.os;public class Handler{public Handler(Looper l){}public boolean post(Runnable r){r.run();return true;}}',
'android/util/Log.java':'package android.util;public class Log{public static int w(String t,String s,Throwable e){return 0;}}',
'xiao/bu/tv/AudioMonitorCheck.java':r'''package xiao.bu.tv;
import java.util.concurrent.*;import java.util.concurrent.atomic.*;
public class AudioMonitorCheck {
 static void check(boolean b){if(!b)throw new AssertionError();}
 static final class Reader implements AudioModeMonitor.Reader {
  CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();boolean fail;int mode;
  public int mode(){calls.incrementAndGet();entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}if(fail)throw new IllegalStateException("service dead");return mode;}
 }
 public static void main(String[] args)throws Exception {
  for(int scenario=0;scenario<5;scenario++){
   Reader reader=new Reader();BlockingQueue<Runnable> ui=new LinkedBlockingQueue<>();AtomicInteger callbacks=new AtomicInteger();AtomicBoolean active=new AtomicBoolean();
   ExecutorService worker=Executors.newSingleThreadExecutor();
   AudioModeMonitor monitor=new AudioModeMonitor(reader,worker,ui::add,value->{callbacks.incrementAndGet();active.set(value);});
   try{
    monitor.refresh();check(reader.entered.await(2,TimeUnit.SECONDS));
    for(int i=0;i<100;i++)monitor.refresh();check(reader.calls.get()==1&&ui.isEmpty()); // UI stays responsive while Binder is blocked.
    reader.mode=scenario==1?3:0;reader.fail=scenario==2;
    if(scenario==3)monitor.close();reader.release.countDown();
    Runnable delivered=ui.poll(2,TimeUnit.SECONDS);check(delivered!=null);
    if(scenario==4)monitor.close();delivered.run();
    check(callbacks.get()==(scenario>=3?0:1));
    if(scenario<3)check(active.get()==(scenario==1||scenario==2)); // Service errors retain call muting.
    if(scenario==0){monitor.refresh();ui.poll(2,TimeUnit.SECONDS).run();check(reader.calls.get()==2&&callbacks.get()==2);}
   }finally{reader.release.countDown();monitor.close();worker.awaitTermination(2,TimeUnit.SECONDS);}
  }
  System.out.println("PASS audio Binder blockage off UI, coalescing, normal/call/error states and closed/queued callback cancellation");
 }
}''',
'xiao/bu/tv/MainActivity.java':r'''package xiao.bu.tv;
import java.util.concurrent.*;
public class MainActivity {
 volatile WifiDirectCoordinator wifiDirectCoordinator;boolean startupMaintenanceDestroyed,finishing;int permissions;
 BlockingQueue<Runnable> ui=new LinkedBlockingQueue<>();boolean isFinishing(){return finishing;}
 void runOnUiThread(Runnable r){ui.add(r);}void requestWifiDirectPermission(){permissions++;}
 METHOD
 static class WifiDirectCoordinator {
  interface PermissionDelegate{void requestWifiDirectPermission();}
  static CountDownLatch entered,release;static WifiDirectCoordinator last;boolean closed;PermissionDelegate permissions;
  WifiDirectCoordinator(MainActivity a,PermissionDelegate p){permissions=p;last=this;entered.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}}
  void close(){closed=true;}
 }
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args)throws Exception{
  for(int scenario=0;scenario<3;scenario++){
   WifiDirectCoordinator.entered=new CountDownLatch(1);WifiDirectCoordinator.release=new CountDownLatch(1);MainActivity a=new MainActivity();
   a.initializeWifiDirect();check(WifiDirectCoordinator.entered.await(2,TimeUnit.SECONDS));check(a.wifiDirectCoordinator==null);
   if(scenario==1)a.finishing=true;if(scenario==2)a.startupMaintenanceDestroyed=true;
   WifiDirectCoordinator.release.countDown();Runnable result=a.ui.poll(2,TimeUnit.SECONDS);check(result!=null);result.run();
   if(scenario==0){check(a.wifiDirectCoordinator!=null);a.wifiDirectCoordinator.permissions.requestWifiDirectPermission();check(a.permissions==1);}
   else check(a.wifiDirectCoordinator==null&&WifiDirectCoordinator.last.closed);
  }
  System.out.println("PASS slow P2P/package service does not block startup; initialization, permission callback and late-result cleanup");
 }
}'''.replace('METHOD',block(main,'    private void initializeWifiDirect()')),
'MenuBindingCheck.java':r'''public class MenuBindingCheck {
 static class Context{}static class BaseAdapter{}static class View{LayoutParams p=new LayoutParams();LayoutParams getLayoutParams(){return p;}}
 static class LayoutParams{int height;}static class ViewGroup extends View{}
 static class MenuRow extends View{String label,description;boolean main,radio,checked;int index;MenuRow(Context c){}void setContentDescription(String s){description=s;}void invalidate(){}}
 static class Bundle{int getInt(String k,int d){return d;}}
 Bundle choices=new Bundle();int categoryPitch=30;int naturalPitch(){return 20;}
 ADAPTER
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  MenuBindingCheck owner=new MenuBindingCheck();String[] items={"A",null,"B ●","C ○"};
  MenuAdapter adapter=owner.new MenuAdapter(new Context(),items,false,2);items[0]=null;
  check(adapter.getCount()==4);View reused=null;
  for(int i=0;i<4;i++){reused=adapter.getView(i,reused,new ViewGroup());check(((MenuRow)reused).label!=null);}
  check("A".equals(adapter.getItem(0))&&"".equals(adapter.getItem(1)));
  check(owner.new MenuAdapter(new Context(),null,false,-1).getCount()==0);
  System.out.println("PASS real menu binding with null entries, absent array, recycled rows and caller array mutation");
 }
}'''.replace('ADAPTER',block(skin,'        private final class MenuAdapter extends BaseAdapter')),
}
with tempfile.TemporaryDirectory(prefix='ntv-bugly-') as folder:
 temp=Path(folder)
 for name,source in files.items():
  path=temp/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(source,encoding='utf-8')
 subprocess.run(['javac','-encoding','UTF-8','-d',folder,*map(str,temp.rglob('*.java')),str(root/'app/src/main/java/xiao/bu/tv/AudioModeMonitor.java')],check=True)
 for cls in ['xiao.bu.tv.AudioMonitorCheck','xiao.bu.tv.MainActivity','MenuBindingCheck']:
  subprocess.run(['java','-cp',folder,cls],check=True,timeout=15)
