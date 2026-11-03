"""Run the legacy Guangdong recovery and its admission before WebView fallback."""
from pathlib import Path
import os,subprocess,tempfile
root=Path(__file__).resolve().parents[1]
s=(root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf8')
def method(name):
 start=s.index('    private '+name);end=s.index('{',start)+1;depth=1
 while depth:depth+=(s[end]=='{')-(s[end]=='}');end+=1
 return s[start:end]
entry=method('boolean returnFromFailedSniffedPlayback(')
entry=entry[:entry.index('        if (!playingDiscoveredWebStream')]+ '\n return false;\n}'
java=r'''import java.net.*;
class Check {
 static final String TAG="test";
 static class Build {static class VERSION{static int SDK_INT=19;}static class VERSION_CODES{static final int KITKAT=19;}}
 static class Uri {URI value;String getHost(){return value.getHost();}String getPath(){return value.getPath();}}
 static class SystemClock{static long now=100000;static long elapsedRealtime(){return now;}}
 static class Log{static void w(String a,String b){}}
 static class Channel{String name="广东珠江",url="webview://https://www.gdtv.cn/tvChannelDetail/44";}
 static class Queue{Runnable task;long delay;void postDelayed(Runnable r,long d){task=r;delay=d;}void run(){Runnable r=task;task=null;r.run();}}
 Queue channelBar=new Queue();Channel channel=new Channel();
 int playRequestId=10,playbackReadyRequestId=10,playbackRecoveryAttempts,starts,closed,released,fallbacks;
 int currentGroupIndex=3,currentChannelIndex=1,currentSourceIndex,stallRecoveryRequestId=-1,yangshipinRefreshScheduledRequest=-1,continuousHttpSourceRequestId=-1,continuousHttpRefreshScheduledRequest=-1;
 long lastPlaybackRecoveryAt;String playbackRecoveryTarget="";boolean finishing,offline;int networkErrors;
 Object liteWebPlayback=new Object();Runnable liteWebFallback=()->fallbacks++;
 Channel currentChannel(){return channel;}String configuredPlaybackUrl(Channel c){return c.url;}
 Uri parseWebViewPageUri(String u){if(u==null||!u.startsWith("webview://https://")&&!u.startsWith("webview://http://"))return null;try{Uri v=new Uri();v.value=URI.create(u.substring(10));return v;}catch(Exception e){return null;}}
 boolean hostMatches(String host,String domain){return host!=null&&(host.equals(domain)||host.endsWith("."+domain));}
 void cancelSniffedOpenTimeout(){}void cancelCustomSourceTimeout(){}void clearPendingPlayer(){}
 void closeWebSource(){closed++;liteWebPlayback=null;liteWebFallback=null;}
 void releasePlayer(boolean b){released++;}void showLoading(String a,String b){}
 boolean isDeviceNetworkDisconnected(){return offline;}void hideLoading(){}void recordLoadError(String s){networkErrors++;}void showChannelBar(String a,String b){}
 boolean isFinishing(){return finishing;}
 void startChannel(int i){check(i==1,"Changed requested channel");starts++;playRequestId++;playbackReadyRequestId=-1;liteWebPlayback=new Object();liteWebFallback=()->fallbacks++;}
 METHODS
 static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
 public static void main(String[]args){
  Check a=new Check();check(a.returnFromFailedSniffedPlayback("network EOF"),"Recovery not admitted");
  check(a.fallbacks==0&&a.closed==1&&a.released==1&&a.channelBar.delay==500&&a.playbackRecoveryAttempts==1,"First failure fell into unsupported legacy WebView");
  a.channelBar.run();a.playbackReadyRequestId=a.playRequestId;
  check(a.returnFromFailedSniffedPlayback("another interruption"),"Second refresh missing");
  check(a.channelBar.delay==1000&&a.playbackRecoveryAttempts==2,"Retry budget/backoff lost across requests");
  a.channelBar.run();a.playbackReadyRequestId=a.playRequestId;
  check(a.returnFromFailedSniffedPlayback("still broken")&&a.fallbacks==1&&a.channelBar.task==null&&a.starts==2,"Exhaustion lost final fallback or loops forever");
  for(int sdk:new int[]{14,15,16,19,21,25}){Build.VERSION.SDK_INT=sdk;a=new Check();check(a.retryLegacyGdtvPlayback("failure")== (sdk<=19),"SDK gate "+sdk);}
  Build.VERSION.SDK_INT=19;
  for(String url:new String[]{"webview://https://www.gdtv.cn/tvChannelDetail/44","webview://https://m.gdtv.cn/tvChannelDetail/43/?ujs=fixture"}){a=new Check();a.channel.url=url;check(a.retryLegacyGdtvPlayback("failure"),"Official selected channel rejected");}
  for(String url:new String[]{"webview://https://www.gdtv.cn/","webview://https://www.gdtv.cn/tvChannelDetail/44/replay","webview://https://gdtv.cn.evil.test/tvChannelDetail/44","webview://https://other.test/tvChannelDetail/44","https://www.gdtv.cn/tvChannelDetail/44"}){a=new Check();a.channel.url=url;check(!a.retryLegacyGdtvPlayback("failure"),"Unrelated page retried: "+url);}
  a=new Check();a.playbackReadyRequestId=-1;check(!a.retryLegacyGdtvPlayback("never rendered"),"Unsupported media retried without ever rendering");
  a=new Check();a.liteWebPlayback=null;check(!a.retryLegacyGdtvPlayback("system"),"System engine changed");
  a=new Check();a.retryLegacyGdtvPlayback("failure");a.playRequestId++;a.channelBar.run();check(a.starts==0,"Stale delayed retry changed selection");
  a=new Check();a.retryLegacyGdtvPlayback("failure");a.finishing=true;a.channelBar.run();check(a.starts==0,"Retry reopened finishing activity");
  a=new Check();a.offline=true;a.retryLegacyGdtvPlayback("offline");a.channelBar.run();check(a.starts==0&&a.channelBar.task!=null,"Page reopened before network was available");a.offline=false;a.channelBar.run();check(a.starts==1,"Restored network did not resume retry");
  a=new Check();a.offline=true;a.retryLegacyGdtvPlayback("offline");SystemClock.now+=30001;a.channelBar.run();check(a.starts==0&&a.channelBar.task==null&&a.networkErrors==1&&a.fallbacks==0,"Permanent offline wait was unbounded or opened blank old WebView");
  System.out.println("PASS legacy GDTV post-frame recovery before WebView fallback, exact live route, two-attempt budget, backoff and stale/finish guards");
 }
}'''.replace('METHODS',method('boolean retryLegacyGdtvPlayback(')+method('void syncPlaybackRecoveryTarget(')+entry)
jdk=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
with tempfile.TemporaryDirectory() as folder:
 p=Path(folder)/'Check.java';p.write_text(java,encoding='utf8')
 subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8',str(p)],check=True)
 subprocess.run([str(jdk/'java.exe'),'-cp',folder,'Check'],check=True)
