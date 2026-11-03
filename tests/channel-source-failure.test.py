"""Run actual source-failure decisions against target-channel and backup state."""
import os, subprocess
from pathlib import Path
root=Path(__file__).resolve().parent.parent
src=(root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf-8')
def method(start,end): return src[src.index(start):src.index(end,src.index(start))]
methods=method('    private void handleUnavailableSource(', '    private boolean isDeviceNetworkDisconnected(')
methods+=method('    private void showPlaybackForbidden(', '    private void dispatchFlyMouseButtonDown(')
methods+=method('    private void resolveCjsSite(final Channel channel, final String pageUrl,\n            final int requestId, final ChannelPreparationGate gate)', '    private void startResolvedPlayer(Channel channel, String streamUrl,')

java=r'''import java.util.concurrent.Executor;import java.io.IOException;
class ChannelSourceFailureCheck {
 static class Channel {String name="target";int count;Channel(int n){count=n;}int sourceCount(){return count;}String sourceUrl(int i){return "";}IptvCatchup catchupFor(int i){return null;}}
 static class PlaybackHttpError {static final String FORBIDDEN_MESSAGE="forbidden";static boolean isForbidden(String s){return "403".equals(s);}}
 static class Toast {static final int LENGTH_LONG=1;static Toast makeText(Object a,String b,int c){return new Toast();}void show(){}}
 final Executor PROXY_SWITCH_WORKER=Runnable::run;
 static class EpgManager {static class Program {long startMillis,stopMillis;String catchupId;}}
 static class IptvCatchup {String url(String u,long s,long e,long n,String id)throws IOException{return u;}}
 EpgManager.Program catchupProgram;
 int catchupSource(Channel c,EpgManager.Program p,int preferred,int direction){throw new AssertionError("Live regression entered catchup path");}
 void beginCatchupPlayback(int group,int index,int source,EpgManager.Program p,String url){throw new AssertionError("Live regression entered catchup path");}
 static final String TAG="test";
 static class Log {static void w(String tag,String message){}}
 static class ChannelPreparationGate {Runnable pending;boolean opened;boolean defer(Runnable r){if(opened)return false;pending=r;return true;}void open(){opened=true;if(pending!=null)pending.run();}}
 static class CjsSource {String url;}CjsSource activeCjsSource;
 static class Proxy {void setWebRequestHeaders(String r,String a,Object c){}void configureCjsTransformer(String t,String[]a,String[]h){}}
 Proxy proxy=new Proxy();
 static class CjsSiteResolver {
  interface Callback {void onResolved(int id,Result r);void onFailed(int id,String reason);}
  static class Result {String referer="",transformer="",url="";String[]transformerArgs={},mediaHosts={};boolean directDataSource;}
  Callback callback;void cancel(){}void resolve(int id,String n,String p,String q,String s,boolean fresh,Callback c){callback=c;}
 }
 CjsSiteResolver cjsSiteResolver=new CjsSiteResolver();
 static class ChannelNetworkWarmup {static class CjsLease {CjsSiteResolver resolver;CjsSiteResolver.Result result;}CjsLease takeCjs(String p,String q,String s,int i,CjsSiteResolver.Callback c){return null;}}
 ChannelNetworkWarmup channelNetworkWarmup;
 int freshYangshipinRequestId=-1,warmResultRequest;
 void updateLoadingStatus(String s){}String customSourceStatus(String s){return s;}
 void logSelectedChannelUrl(int i){}void startResolvedPlayer(Channel c,String u,boolean d){}
 String playbackResolutionMode(){return "high";}boolean retryWarmedSource(int i){return false;}
 int playRequestId=1;boolean rememberChannelSource;Channel quickChannel;
 static class Memory {void rememberSource(String key,String url){throw new AssertionError("Disabled memory must not write");}}
 Memory playbackMemory=new Memory();Object currentGroup(){return null;}String playbackMemoryKey(Object group,Channel c){return "key";}
 void recordLoadError(String s){}boolean refreshContinuousHttpPlayback(int id,String s){return false;}boolean refreshYangshipinPlayback(int id,String s){return false;}
 String sourceLineDescription(Channel c,int index){return "";}
 boolean autoSwitchSource,offline,released;
 Channel channel;int currentGroupIndex,currentChannelIndex=7,currentSourceIndex,triedCustomSources=1,starts;String status="";
 ChannelSourceFailureCheck(int count){channel=new Channel(count);}
 Channel currentChannel(){return channel;}
 boolean isDeviceNetworkDisconnected(){return offline;}
 boolean returnFromFailedSniffedPlayback(String s){return false;}
 void cancelChannelManifest(){}void clearPendingPlayer(){}void cancelCustomSourceTimeout(){}
 void releasePlayer(){releasePlayer(false);}void releasePlayer(boolean keep){released=true;}
 boolean showStoppedRadioArtwork(Channel c){return false;}
 void abortChannelSwitchAnimation(){}void hideLoading(){}void showChannelBar(String n,String s){status=s;}
 void cancelPendingRelativeSwitch(){}void clearNumericChannelInput(){}void resetPlaybackRecoveryState(){}
 void startChannel(int index){if(index!=7)throw new AssertionError("Selection changed");starts++;}
 METHODS
 static void check(boolean value){if(!value)throw new AssertionError();}
 public static void main(String[] args){
  ChannelSourceFailureCheck s=new ChannelSourceFailureCheck(1);
  s.handleUnavailableSource("failed","bad playlist");check(s.currentChannelIndex==7);check(s.starts==0&&s.currentSourceIndex==0);
  s=new ChannelSourceFailureCheck(2);
  s.handleUnavailableSource("failed","connection timeout");check(s.currentChannelIndex==7);check(s.starts==1&&s.currentSourceIndex==1&&s.triedCustomSources==2);
  s=new ChannelSourceFailureCheck(2);
  s.handleUnavailableSource("failed","403");check(s.currentChannelIndex==7);check(s.starts==1&&s.currentSourceIndex==1);
  s=new ChannelSourceFailureCheck(2);s.triedCustomSources=2;
  s.handleUnavailableSource("failed","bad playlist");check(s.currentChannelIndex==7);check(s.starts==0&&s.status.contains("全部"));
  s=new ChannelSourceFailureCheck(2);s.offline=true;
  s.handleUnavailableSource("failed","network");check(s.currentChannelIndex==7);check(s.starts==0);
  s=new ChannelSourceFailureCheck(1);
  s.showPlaybackForbidden(s.channel);check(s.currentChannelIndex==7);check(s.starts==0&&s.status.equals("forbidden"));
  s=new ChannelSourceFailureCheck(2);s.autoSwitchSource=true;
  s.resolveCjsSite(s.channel,"https://tv.cctv.com/live/cctv1/",1,null);
  s.cjsSiteResolver.callback.onFailed(1,"Injected CCTV plugin failure");
  check(s.currentChannelIndex==7&&s.currentSourceIndex==1&&s.starts==1);
  s=new ChannelSourceFailureCheck(1);s.autoSwitchSource=true;
  s.resolveCjsSite(s.channel,"https://tv.cctv.com/live/cctv1/",1,null);
  s.cjsSiteResolver.callback.onFailed(1,"Injected CCTV plugin failure");
  check(s.currentSourceIndex==0&&s.starts==0&&s.status.contains("站点插件解析失败"));
  s=new ChannelSourceFailureCheck(2);s.autoSwitchSource=true;s.triedCustomSources=2;
  s.resolveCjsSite(s.channel,"https://tv.cctv.com/live/cctv1/",1,null);
  s.cjsSiteResolver.callback.onFailed(1,"Injected CCTV plugin failure");
  check(s.starts==0&&s.status.contains("全部"));
  s=new ChannelSourceFailureCheck(2);s.autoSwitchSource=true;
  s.resolveCjsSite(s.channel,"https://tv.cctv.com/live/cctv1/",1,null);
  s.cjsSiteResolver.callback.onFailed(0,"Obsolete failure");check(s.starts==0&&s.currentSourceIndex==0);
  s=new ChannelSourceFailureCheck(2);s.autoSwitchSource=true;ChannelPreparationGate gate=new ChannelPreparationGate();
  s.resolveCjsSite(s.channel,"https://tv.cctv.com/live/cctv1/",1,gate);
  s.cjsSiteResolver.callback.onFailed(1,"Injected CCTV plugin failure");check(s.starts==0);gate.open();
  check(s.currentChannelIndex==7&&s.currentSourceIndex==1&&s.starts==1);
  System.out.println("PASS: source failure policy and actual CCTV plugin callback select the next configured source; single/exhausted/stale/deferred failures retained");
 }
}'''.replace('METHODS',methods)
out=root/'.codex-tmp/channel-source-failure';out.mkdir(parents=True,exist_ok=True)
test=out/'ChannelSourceFailureCheck.java';test.write_text(java,encoding='utf-8')
bin=Path(os.environ.get('JAVA_HOME',r'C:/Users/Administrator/.jdks/corretto-1.8.0_492'))/'bin'
subprocess.run([str(bin/'javac.exe'),'-encoding','UTF-8','-d',str(out),str(test)],check=True)
subprocess.run([str(bin/'java.exe'),'-cp',str(out),'ChannelSourceFailureCheck'],check=True)
