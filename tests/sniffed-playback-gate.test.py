"""Run the production opening guard and asynchronous callback against fake UI objects."""
from pathlib import Path
import os, subprocess, tempfile

root = Path(__file__).resolve().parents[1]
source = (root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf8')

def method(name):
    begin = source.index('    private ' + name)
    opening = source.index('{', begin)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[begin:end]

guard = method('void startSniffedResource(SniffedResource resource, boolean automatic)')
guard = guard[:guard.index('        Channel channel = currentChannel();')] + '        opened++;\n    }'
guard = method('void startSniffedResource(SniffedResource resource)')+'\n'+guard
callback = method('void probeSniffedResource(')
hls = method('static boolean isHttpHlsSource(')
recovery = method('boolean returnFromFailedSniffedPlayback(')

harness = r'''import java.util.*;
class SniffedGateCheck {
static final String TAG="test";
static class Result {String status="unavailable",error="";}
static class SniffedResource {
 int requestId=1;String url="https://cdn.test/live.m3u8",pageUrl="",userAgent="",cookies="";Result metadata;
}
static class Probe {
 interface Callback {void complete(Result result);}
 Callback callback;
 void submit(String u,String p,String a,String c,Callback cb){callback=cb;}
}
static class Web {int fullscreen;void showPlaybackFullscreen(){fullscreen++;}String key="1:1";boolean visible=true;boolean hasRetainedPage(){return true;}
 boolean isPageVisible(){return visible;}String currentResourcePageKey(){return key;}}
static class Toast {static final int LENGTH_SHORT=0,LENGTH_LONG=1;static String text;
 static Toast makeText(Object c,String t,int duration){text=t;return new Toast();}void show(){}}
static class Log {static void i(String t,String message){}static void w(String t,String message){}}
static class AppLog extends Log {}
Map<String,SniffedResource> sniffedResources=new HashMap<>();Object sniffedResourcesSnapshot;
SniffedResource pendingSniffedResource;String pendingSniffedPageKey="";boolean pendingSniffedAutomatic;
Probe sniffedMediaProbe=new Probe();Web webSourceView=new Web();int playRequestId=1,opened;
int webOriginalFallbackRequestId=-1;boolean finishing;boolean isFinishing(){return finishing;}
Runnable liteWebFallback;boolean playingDiscoveredWebStream,playingSniffedAutomatic,restore=true;
boolean retryLegacyGdtvPlayback(String reason){return false;}
void stopFailedLitePlayback(String reason){}
SniffedResource playingSniffedResource;String playingSniffedTitle="test";
void cancelSniffedOpenTimeout(){}void cancelCustomSourceTimeout(){}void abortChannelSwitchAnimation(){}
boolean canReturnToSniffedPage(){return true;}void closeChannelList(){}void closeManagementPanel(){}
boolean restoreSniffedWebPage(){playingSniffedAutomatic=false;return restore;}
void clearPendingPlayer(){}void releasePlayer(){}void hideLoading(){}void showChannelBar(String a,String b){}
void runOnUiThread(Runnable task){task.run();}
SniffedResource findSniffedResource(String url){return sniffedResources.get(url);}
GUARD
CALLBACK
HLS
RECOVERY
SniffedResource prepare(){SniffedResource r=new SniffedResource();sniffedResources.put(r.url,r);probeSniffedResource(r);return r;}
static Result result(String status,String error){Result r=new Result();r.status=status;r.error=error;return r;}
static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
public static void main(String[] args){
 Toast.text="";
 SniffedGateCheck auto=new SniffedGateCheck();SniffedResource failed=auto.prepare();auto.startSniffedResource(failed,true);
 auto.sniffedMediaProbe.callback.complete(result("unavailable","资源连接或格式解析失败"));
 check(Toast.text.equals("")&&auto.opened==0&&auto.webOriginalFallbackRequestId==auto.playRequestId,"automatic failure interrupted web playback");
 failed.metadata=result("ready","");auto.startSniffedResource(failed,true);check(auto.opened==0,"late automatic retry ignored fallback latch");
 auto.startSniffedResource(failed);check(auto.opened==1,"explicit manual selection blocked by automatic fallback");
 auto.playingDiscoveredWebStream=true;auto.playingSniffedResource=failed;auto.playingSniffedAutomatic=true;Toast.text="";
 check(auto.returnFromFailedSniffedPlayback("failed")&&Toast.text.equals(""),"automatic native fallback showed toast over web playback");
 auto.playingSniffedAutomatic=false;auto.returnFromFailedSniffedPlayback("manual failure");check(Toast.text.contains("manual failure"),"manual native failure lost reason");
 auto.playingSniffedAutomatic=true;auto.restore=false;auto.returnFromFailedSniffedPlayback("page gone");check(Toast.text.contains("page gone"),"total failure was silently hidden");
 SniffedGateCheck s=new SniffedGateCheck();SniffedResource r=s.prepare();s.startSniffedResource(r);
 check(s.opened==0&&s.pendingSniffedResource==r,"pending probe opened player");
 s.sniffedMediaProbe.callback.complete(result("unavailable","HTTP 404：资源不存在或链接已失效"));
 check(s.opened==0&&s.pendingSniffedResource==null&&Toast.text.contains("HTTP 404"),"404 did not stay on page with reason");
 s.startSniffedResource(r);check(s.opened==0,"explicit retry opened known failed resource");
 s=new SniffedGateCheck();r=s.prepare();s.startSniffedResource(r);s.sniffedMediaProbe.callback.complete(result("ready",""));
 check(s.opened==1&&s.pendingSniffedResource==null,"playable resource did not open");
 s=new SniffedGateCheck();r=s.prepare();s.startSniffedResource(r);s.playRequestId++;
 s.sniffedMediaProbe.callback.complete(result("ready",""));check(s.opened==0,"old channel callback opened player");
 s=new SniffedGateCheck();r=s.prepare();s.startSniffedResource(r);s.webSourceView.key="2:1";
 s.sniffedMediaProbe.callback.complete(result("ready",""));check(s.opened==0,"old tab callback opened player");
 s=new SniffedGateCheck();r=s.prepare();s.startSniffedResource(r);s.webSourceView.visible=false;
 s.sniffedMediaProbe.callback.complete(result("ready",""));check(s.opened==0,"hidden page callback opened player");
 s=new SniffedGateCheck();r=s.prepare();s.startSniffedResource(r);s.finishing=true;
 s.sniffedMediaProbe.callback.complete(result("ready",""));check(s.opened==0,"destroyed activity callback opened player");
 s=new SniffedGateCheck();r=s.prepare();s.startSniffedResource(r);SniffedResource next=new SniffedResource();
 next.url="https://cdn.test/next.m3u8";s.sniffedResources.put(next.url,next);s.startSniffedResource(next);
 s.sniffedMediaProbe.callback.complete(result("ready",""));check(s.opened==0&&s.pendingSniffedResource==next,"replaced choice opened player");
 s=new SniffedGateCheck();r=s.prepare();r.metadata=result("unavailable","HTTP 403：服务器拒绝访问");s.startSniffedResource(r);
 check(s.opened==0&&s.webSourceView.fullscreen==1&&s.webOriginalFallbackRequestId==s.playRequestId&&Toast.text.contains("HTTP 403"),"403 lost reason or opened player");
 System.out.println("PASS failed/pending/ready resources, HTTP reasons, stale channel/tab, hidden page, teardown and replaced selection");
}
}'''.replace('GUARD', guard).replace('CALLBACK', callback).replace('HLS', hls).replace('RECOVERY',recovery)
jdk = Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/corretto-1.8.0_492'))/'bin'
with tempfile.TemporaryDirectory(prefix='ntv-sniffed-gate-') as directory:
    file = Path(directory)/'SniffedGateCheck.java'
    file.write_text(harness,encoding='utf8')
    subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8',str(file)],check=True)
    subprocess.run([str(jdk/'java.exe'),'-cp',directory,'SniffedGateCheck'],check=True)
