"""Exercise the real cold-start scheduler with stable, busy and churning playback."""
from pathlib import Path
import os, subprocess, tempfile

root = Path(__file__).resolve().parents[1]
source = (root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf8')
begin = source.index('    private void deferStartupMaintenance(')
end = source.index('    private boolean startupMaintenanceDestroyed;', begin)
method = source[begin:end]
# Cache loading must remain outside the delayed network-refresh task.
startup = source[source.index('// Management remains available even when a source'):begin]
assert startup.index('loadCompleteCatalogInBackground(false)') < startup.index('"catalog-refresh"')
assert 'if (autoUpdateChannelList) loadCompleteCatalogInBackground(true);' in startup
assert '}, 15000L);' in startup
test = '''import java.util.*;
class StartupMaintenanceCheck {
static class SystemClock {static long now;static long elapsedRealtime(){return now;}}
static class AppLog {static void i(String tag,String text){}}
static final String TAG="test";
boolean prepared,buffering,videoRenderingStarted,audioOnlyPlayback,playbackProgressObserved;
boolean startupMaintenanceDestroyed,finishing;int playRequestId=1,calls;long lastStartupMaintenanceAt;
float cpu;String systemCpuMetricSource="proc";
boolean isFinishing(){return finishing;}float sampleSystemCpuUsage(){return cpu;}
class Root {
TreeMap<Long,List<Runnable>> queue=new TreeMap<>();
void postDelayed(Runnable r,long delay){queue.computeIfAbsent(SystemClock.now+delay,k->new ArrayList<>()).add(r);}
void advance(long until,boolean churn){
while(!queue.isEmpty()&&queue.firstKey()<=until){
SystemClock.now=queue.firstKey();List<Runnable> tasks=queue.pollFirstEntry().getValue();
if(churn)playRequestId++;
for(Runnable r:tasks)r.run();
}SystemClock.now=until;
}
}Root root=new Root();
METHOD
static void check(boolean ok){if(!ok)throw new AssertionError();}
static StartupMaintenanceCheck create(){SystemClock.now=0;return new StartupMaintenanceCheck();}
void catalog(){deferStartupMaintenance("catalog-refresh",()->calls++,15000L);}
public static void main(String[] args){
StartupMaintenanceCheck s=create();s.prepared=s.videoRenderingStarted=true;s.catalog();
s.root.advance(4000,false);check(s.calls==1);s.root.advance(90000,false);check(s.calls==1);
s=create();s.prepared=s.videoRenderingStarted=true;s.catalog();
s.root.advance(14000,true);check(s.calls==0);s.root.advance(15000,true);check(s.calls==1);
s=create();s.prepared=s.videoRenderingStarted=true;s.cpu=99;s.catalog();
s.root.advance(14000,false);check(s.calls==0);s.root.advance(15000,false);check(s.calls==1);
s=create();s.buffering=true;s.catalog();s.root.advance(15000,false);check(s.calls==1);
s=create();s.catalog();s.root.advance(15000,false);check(s.calls==1);
s=create();s.catalog();s.lastStartupMaintenanceAt=14999;s.root.advance(15000,true);check(s.calls==1);
s=create();s.catalog();s.startupMaintenanceDestroyed=true;s.root.advance(90000,false);check(s.calls==0);
s=create();s.catalog();s.finishing=true;s.root.advance(90000,false);check(s.calls==0);
s=create();s.cpu=99;s.deferStartupMaintenance("update-check",()->sUnused(),60000L);
s.root.advance(59000,true);check(!s.root.queue.isEmpty());s.root.advance(60000,true);check(s.root.queue.isEmpty());
}static void sUnused(){}
}
'''.replace('METHOD', method)
jdk = Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/corretto-1.8.0_492'))/'bin'
with tempfile.TemporaryDirectory(prefix='ntv-startup-refresh-') as folder:
    file = Path(folder)/'StartupMaintenanceCheck.java'
    file.write_text(test,encoding='utf8')
    subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8',str(file)],check=True)
    subprocess.run([str(jdk/'java.exe'),'-cp',folder,'StartupMaintenanceCheck'],check=True)
print('PASS cache-first start, stable playback, request churn, CPU pressure, failed source and teardown')

# Execute the actual delayed management task after its Activity has been replaced.
begin=source.index('    private final Runnable startManagementServerTask=')
end=source.index('    private void startManagementServer()',begin)
task=source[begin:end]
assert 'root.removeCallbacks(startManagementServerTask)' in source
code='''class ManagementStartupCheck {
boolean startupMaintenanceDestroyed,finishing;int starts;
boolean isFinishing(){return finishing;}void startManagementServer(){starts++;}
TASK
public static void main(String[] args){
 ManagementStartupCheck live=new ManagementStartupCheck();live.startManagementServerTask.run();if(live.starts!=1)throw new AssertionError();
 ManagementStartupCheck old=new ManagementStartupCheck();old.startupMaintenanceDestroyed=true;old.startManagementServerTask.run();if(old.starts!=0)throw new AssertionError("Old activity rebound port");
 old.startupMaintenanceDestroyed=false;old.finishing=true;old.startManagementServerTask.run();if(old.starts!=0)throw new AssertionError();
}}
'''.replace('TASK',task)
with tempfile.TemporaryDirectory(prefix='ntv-management-start-') as folder:
    file=Path(folder)/'ManagementStartupCheck.java';file.write_text(code,encoding='utf8')
    subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8',str(file)],check=True)
    subprocess.run([str(jdk/'java.exe'),'-cp',folder,'ManagementStartupCheck'],check=True)
print('PASS destroyed and finishing Activities cannot start a delayed management server')
