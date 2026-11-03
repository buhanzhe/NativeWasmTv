"""Exercise production live HTTP recovery scheduling without a decoder/network dependency."""
from pathlib import Path
import os, subprocess, tempfile

root = Path(__file__).resolve().parents[1]
source = (root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf-8')
def method(signature):
    start = source.index(signature)
    brace = source.index('{', start); end = brace + 1; depth = 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}'); end += 1
    return source[start:end]

java = r'''
class HttpLiveRecoveryCheck {
 static final String TAG="test";static final int PLAYBACK_RECOVERY_MAX_ATTEMPTS=5;
 static class SystemClock {static long elapsedRealtime(){return 1234;}}
 static class Log {static void w(String a,String b){}}
 static class Channel {String name="刀刀";}
 static class Queue {Runnable next;long delay;void postDelayed(Runnable r,long d){next=r;delay=d;}void run(){Runnable r=next;next=null;r.run();}}
 Queue channelBar=new Queue();int playRequestId=7,continuousHttpSourceRequestId=7,continuousHttpRefreshScheduledRequest=-1,stallRecoveryRequestId=-1;
 int playbackRecoveryAttempts,playbackRecoverySourcesTried,currentGroupIndex,currentChannelIndex,currentSourceIndex,starts,stops,timeoutCancels;
 long lastPlaybackRecoveryAt,duration;boolean finishing;Object catchupProgram,quickChannel;
 String playbackRecoveryTarget="0:0:0";
 Channel currentChannel(){return new Channel();}long seekableDuration(){return duration;}
 boolean isFinishing(){return finishing;}void cancelCustomSourceTimeout(){timeoutCancels++;}
 void clearPendingPlayer(){}void releasePlayer(boolean b){stops++;}void abortChannelSwitchAnimation(){}void hideLoading(){}
 void showLoading(String a,String b){}void showChannelBar(String a,String b){}
 void startChannel(int channel){
  check(continuousHttpRefreshScheduledRequest==playRequestId,"Refresh lost identity");
  starts++;continuousHttpSourceRequestId=++playRequestId;continuousHttpRefreshScheduledRequest=-1;
 }
 REFRESH
 SYNC
 static void check(boolean b,String reason){if(!b)throw new AssertionError(reason);}
 public static void main(String[] args){
  HttpLiveRecoveryCheck c=new HttpLiveRecoveryCheck();
  for(int i=1;i<=5;i++){
   int request=c.playRequestId;check(c.refreshContinuousHttpPlayback(request,"EOF / timeout / 403"),"Live source ignored");
   check(c.playbackRecoveryAttempts==i&&c.channelBar.delay==i*500,"Bounded backoff missing");
   check(c.refreshContinuousHttpPlayback(request,"duplicate")&&c.playbackRecoveryAttempts==i,"Duplicate retry");
   c.channelBar.run();check(!c.refreshContinuousHttpPlayback(request,"stale"),"Stale retry accepted");
  }
  check(c.refreshContinuousHttpPlayback(c.playRequestId,"still unavailable"),"Exhaustion ignored");
  check(c.starts==5&&c.stops==1&&c.channelBar.next==null&&c.currentChannelIndex==0&&c.currentSourceIndex==0,"Unbounded retries / channel changed");
  c=new HttpLiveRecoveryCheck();c.duration=60000;check(!c.refreshContinuousHttpPlayback(7,"finite FLV"),"VOD restarted as live");
  c=new HttpLiveRecoveryCheck();c.quickChannel=new Object();check(!c.refreshContinuousHttpPlayback(7,"local media"),"Temporary media restarted");
  c=new HttpLiveRecoveryCheck();c.catchupProgram=new Object();check(!c.refreshContinuousHttpPlayback(7,"catchup"),"Catchup restarted");
  c=new HttpLiveRecoveryCheck();c.continuousHttpSourceRequestId=-1;check(!c.refreshContinuousHttpPlayback(7,"unclassified source"),"Non-live source affected");
  c=new HttpLiveRecoveryCheck();c.refreshContinuousHttpPlayback(7,"EOF");c.playRequestId++;c.channelBar.run();check(c.starts==0,"Switch did not cancel retry");
  c=new HttpLiveRecoveryCheck();c.refreshContinuousHttpPlayback(7,"EOF");c.finishing=true;c.channelBar.run();check(c.starts==0,"Exit did not cancel retry");
  c=new HttpLiveRecoveryCheck();c.currentChannelIndex=1;c.syncPlaybackRecoveryTarget();check(c.continuousHttpSourceRequestId==-1&&c.continuousHttpRefreshScheduledRequest==-1,"Live identity leaked across channel switch");
  System.out.println("PASS live EOF/error/stall refresh, retry bound/backoff, duplicate/stale/cancel guards, finite/local/catchup isolation");
 }
}
'''.replace(' REFRESH',method('    private boolean refreshContinuousHttpPlayback(')).replace(' SYNC',method('    private void syncPlaybackRecoveryTarget('))
with tempfile.TemporaryDirectory(prefix='ntv-http-live-recovery-') as folder:
    path=Path(folder)/'HttpLiveRecoveryCheck.java';path.write_text(java,encoding='utf-8')
    java_bin=Path(os.environ['JAVA_HOME'])/'bin'
    subprocess.run([str(java_bin/'javac.exe'),'-encoding','UTF-8',str(path)],check=True)
    subprocess.run([str(java_bin/'java.exe'),'-cp',folder,'HttpLiveRecoveryCheck'],check=True)
