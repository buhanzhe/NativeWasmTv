"""Run the production refresh scheduler and optional-probe policy with detached UI state."""
import os,subprocess,tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
source=(root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf-8')
a=source.index('    private final Runnable updateVideoInfo =');b=source.index('    private static final class BrowserImageDownload',a)
ticker=source[a:b]
a=source.index('    private void scheduleVideoInfoRefresh()');b=source.index('    @SuppressLint',a)
schedule=source[a:b]
a=source.index('    private boolean sourceMetadataProbeNeeded()');b=source.index('    private float debugInfoHeightFactor()',a)
policy=source[a:b]
java="""import java.util.*;
public class DebugRefreshCheck {
 static class View {boolean attached;ArrayList<Runnable> queue=new ArrayList<>();long delay;View(boolean a){attached=a;}
  void removeCallbacks(Runnable r){queue.removeIf(x->x==r);}void post(Runnable r){if(attached)queue.add(r);}
  void postDelayed(Runnable r,long ms){delay=ms;post(r);}void step(){queue.remove(0).run();}}
 View root=new View(true),videoInfo=new View(false);Object liteWebPlayback;boolean active=true;int refreshes,reads;float cpu=20;
 boolean backgroundPlaybackSuspended,prepared,videoRenderingStarted,audioOnlyPlayback,showDebugInfo=true,showColorDebugInfo;
 long lastPlaybackProgressAt,lastVideoOutputAt,lastPlaybackPosition,playbackHealthySince;boolean playbackProgressObserved;
 static class SystemClock {static long elapsedRealtime(){return 100;}}
 boolean hasActivePlayer(){return active;}void refreshVideoInfo(){refreshes++;}float sampleSystemCpuUsage(){reads++;return cpu;}
 TICKER
 SCHEDULE
 POLICY
 static void check(boolean v,String message){if(!v)throw new AssertionError(message);}
 public static void main(String[] args){DebugRefreshCheck a=new DebugRefreshCheck();
  a.scheduleVideoInfoRefresh();a.scheduleVideoInfoRefresh();check(a.root.queue.size()==1,"Duplicate refresh task");
  for(int n=0;n<4;n++){a.root.step();check(a.refreshes==n+1,"Detached label stopped refresh");check(a.root.queue.size()==1&&a.root.delay==1000,"Refresh period differs");}
  a.active=false;a.root.step();check(a.root.queue.isEmpty(),"Stopped player retained ticker");
  a.liteWebPlayback=new Object();a.scheduleVideoInfoRefresh();a.root.step();check(a.root.queue.size()==1,"Light browser playback must keep ticker");a.liteWebPlayback=null;a.root.step();check(a.root.queue.isEmpty(),"Closed light browser retained ticker");
  check(!a.sourceMetadataProbeNeeded()&&a.reads==0,"Probe before prepare/first frame");a.prepared=true;
  check(!a.sourceMetadataProbeNeeded()&&a.reads==0,"Probe before first frame");a.videoRenderingStarted=true;
  for(float cpu:new float[]{-1,0,49.9f,50,90}){a.cpu=cpu;check(a.sourceMetadataProbeNeeded()==(cpu>=0&&cpu<50),"CPU probe threshold differs: "+cpu);}
  a.cpu=10;a.backgroundPlaybackSuspended=true;check(!a.sourceMetadataProbeNeeded(),"Background probe enabled");a.backgroundPlaybackSuspended=false;
  a.audioOnlyPlayback=true;check(!a.sourceMetadataProbeNeeded(),"Audio probe enabled");a.audioOnlyPlayback=false;
  a.showDebugInfo=false;check(!a.sourceMetadataProbeNeeded(),"Hidden metadata probe enabled");a.showColorDebugInfo=true;check(a.sourceMetadataProbeNeeded(),"Color debug cannot fill missing headers");
  System.out.println("PASS detached-label ticker, single 1000ms task, stopped-player cleanup and post-frame CPU<50 probe policy");
 }
}
""".replace('TICKER',ticker).replace('SCHEDULE',schedule).replace('POLICY',policy)
with tempfile.TemporaryDirectory(prefix='ntv-debug-refresh-') as temp:
 file=Path(temp)/'DebugRefreshCheck.java';file.write_text(java,encoding='utf-8');jdk=Path(os.environ['JAVA_HOME'])/'bin'
 subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',temp,str(file)],check=True)
 subprocess.run([str(jdk/'java.exe'),'-cp',temp,'DebugRefreshCheck'],check=True)
