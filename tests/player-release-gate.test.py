"""Exercise actual release/prepare methods under completion, replacement and timeout races."""
from pathlib import Path
import subprocess,tempfile
root=Path(__file__).resolve().parents[1]
s=(root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf-8')
a=s.index('    private void prepareIjkPlayerAfterRelease(');b=s.index('    private boolean isDirectThirdPartyRecordingSource',a)
methods=s[a:b]
java=r'''import java.util.*;import java.util.concurrent.*;
class ReleaseGateCheck {
 static class SystemClock {static long now;static long elapsedRealtime(){return now;}}
 static class Log {static void w(String t,String m){}static void w(String t,String m,Throwable e){}}
 static class PlaybackErrorReporting {static int calls;static void caught(int request,String component,String kind,Throwable error){calls++;}}
 static class AndroidMediaPlayer extends IjkMediaPlayer {int binds;void setDisplay(SurfaceHolder h){binds++;}void setSurface(Object o){binds++;}}
 static class SurfaceHolder {}
 static class VideoView {SurfaceHolder getVideoSurfaceHolder(){return null;}Object getVideoSurface(){return null;}}
 VideoView videoView=new VideoView();String lastSystemPlaybackError="";void releasePlayer(boolean keep){}void hideLoading(){}void showChannelBar(String n,String s){}static class Channel {String name="";}Channel currentChannel(){return new Channel();}
 static class IjkMediaPlayer {int prepares;boolean fail;public void prepareAsync(){prepares++;if(fail)throw new IllegalStateException("prepare failed");}}
 static class Ui {Map<Runnable,Long> timers=new LinkedHashMap<>();void postDelayed(Runnable r,long d){timers.put(r,SystemClock.now+d);}void removeCallbacks(Runnable r){timers.remove(r);}void advance(long delta){SystemClock.now+=delta;for(Runnable r:new ArrayList<>(timers.keySet()))if(timers.get(r)<=SystemClock.now){timers.remove(r);r.run();}}}
 static String TAG="test";Ui channelBar=new Ui();IjkMediaPlayer player;int playRequestId=1;boolean finishing;
 CountDownLatch playerReleaseComplete=new CountDownLatch(1);Runnable pendingIjkPrepareAfterRelease,pendingSystemPrepareAfterRelease;
 int recoveries;boolean isFinishing(){return finishing;}void recoverStalledPlayback(int id,IjkMediaPlayer p,String reason){recoveries++;}
 int watchdogs;void armPlayerStartupWatchdog(IjkMediaPlayer p,int id){watchdogs++;}
 METHODS
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  ReleaseGateCheck a=new ReleaseGateCheck();a.player=new IjkMediaPlayer();
  a.prepareIjkPlayerAfterRelease(a.player,1);check(a.player.prepares==0&&a.watchdogs==0&&a.channelBar.timers.size()==1);
  a.playerReleaseComplete.countDown();a.resumeIjkPreparationAfterRelease(a.playerReleaseComplete);
  check(a.player.prepares==1&&a.watchdogs==1&&a.channelBar.timers.isEmpty());a.channelBar.advance(3000);check(a.player.prepares==1&&a.watchdogs==1);
  a=new ReleaseGateCheck();a.player=new IjkMediaPlayer();IjkMediaPlayer old=a.player;CountDownLatch stale=a.playerReleaseComplete;
  a.prepareIjkPlayerAfterRelease(old,1);a.channelBar.removeCallbacks(a.pendingIjkPrepareAfterRelease);a.pendingIjkPrepareAfterRelease=null;
  a.playRequestId=2;a.player=new IjkMediaPlayer();a.playerReleaseComplete=new CountDownLatch(1);a.prepareIjkPlayerAfterRelease(a.player,2);
  stale.countDown();a.resumeIjkPreparationAfterRelease(stale);check(a.player.prepares==0&&old.prepares==0&&a.pendingIjkPrepareAfterRelease!=null);
  a.playerReleaseComplete.countDown();a.resumeIjkPreparationAfterRelease(a.playerReleaseComplete);check(a.player.prepares==1&&old.prepares==0);
  a=new ReleaseGateCheck();a.player=new IjkMediaPlayer();a.prepareIjkPlayerAfterRelease(a.player,1);a.finishing=true;a.playerReleaseComplete.countDown();a.resumeIjkPreparationAfterRelease(a.playerReleaseComplete);check(a.player.prepares==0);
  a=new ReleaseGateCheck();a.player=new IjkMediaPlayer();a.prepareIjkPlayerAfterRelease(a.player,1);a.channelBar.advance(2000);check(a.player.prepares==1&&a.pendingIjkPrepareAfterRelease==null);a.playerReleaseComplete.countDown();a.resumeIjkPreparationAfterRelease(a.playerReleaseComplete);check(a.player.prepares==1);
  a=new ReleaseGateCheck();a.player=new IjkMediaPlayer();a.playerReleaseComplete.countDown();a.prepareIjkPlayerAfterRelease(a.player,1);check(a.player.prepares==1&&a.channelBar.timers.isEmpty());
  a=new ReleaseGateCheck();AndroidMediaPlayer system=new AndroidMediaPlayer();a.player=system;
  a.prepareSystemPlayerAfterRelease(system,1);check(system.prepares==0&&system.binds==0);
  a.playerReleaseComplete.countDown();a.resumeIjkPreparationAfterRelease(a.playerReleaseComplete);
  check(system.prepares==1&&system.binds==1);a.channelBar.advance(3000);check(system.prepares==1);
  check(PlaybackErrorReporting.calls==0);
  a=new ReleaseGateCheck();a.player=new IjkMediaPlayer();a.player.fail=true;a.playerReleaseComplete.countDown();a.prepareIjkPlayerAfterRelease(a.player,1);check(PlaybackErrorReporting.calls==1&&a.recoveries==1);
  a=new ReleaseGateCheck();system=new AndroidMediaPlayer();system.fail=true;a.player=system;a.playerReleaseComplete.countDown();a.prepareSystemPlayerAfterRelease(system,1);check(PlaybackErrorReporting.calls==2&&!a.lastSystemPlaybackError.isEmpty());
  System.out.println("PASS release completion, immediate prepare, stale release/request, teardown, timeout and duplicate completion");
 }
}'''.replace('METHODS',methods)
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'ReleaseGateCheck.java';p.write_text(java,encoding='utf-8')
 subprocess.run(['javac','-encoding','UTF-8',str(p)],check=True)
 subprocess.run(['java','-cp',tmp,'ReleaseGateCheck'],check=True)
