"""Exercise production background gates without network, decoder or Activity stubs."""
from pathlib import Path
import os, subprocess, tempfile

root = Path(__file__).resolve().parents[1]
source = (root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf8')
def method(signature):
    start = source.index(signature)
    at = source.index('{', start)+1
    depth = 1
    while depth:
        depth += (source[at]=='{')-(source[at]=='}')
        at += 1
    return source[start:at]

java = r'''import java.io.IOException;
class BackgroundCheck {
 static class Channel {String name="fixture";}
 interface IMediaPlayer {long getCurrentPosition();boolean isPlaying();}
 static class Player implements IMediaPlayer {public long getCurrentPosition(){return 42000;}public boolean isPlaying(){return true;}}
 static class AndroidMediaPlayer extends Player {}
 static class NtvApplication {boolean visible;boolean hasVisibleActivities(){return visible;}}
 static class LegacyWebSession {static LegacyWebSession current;boolean active,foreground;}
 static class Proxy {boolean paused;void setPlaybackSuspended(boolean value){paused=value;}}
 static class Log {static void i(String tag,String text){}static void w(String tag,String text,Exception e){}}
 static class TextUtils {static boolean equals(String a,String b){return java.util.Objects.equals(a,b);}}
 static final String TAG="test";
 NtvApplication app=new NtvApplication();Object root=new Object();boolean startupMaintenanceDestroyed;
 boolean backgroundPlaybackEnabled=true,backgroundPlaybackSuspended,playbackCompleted,activeSoftwareDecode,audioOnlyPlayback,prepared=true;
 Channel activePlayerChannel=new Channel(),pendingPlayerChannel,backgroundChannel;
 String activePlayerStreamUrl="http://fixture/video.m3u8",pendingPlayerStreamUrl,backgroundStreamUrl,directHttpMediaUrl;
 boolean backgroundDirect,backgroundSoftware,backgroundNeedsResolve,backgroundWasPaused,backgroundCompleted,backgroundSystem,backgroundSystemVideo;
 long backgroundPosition,foregroundResumePosition;boolean pendingForceSoftwareDecode;
 boolean foregroundResumePaused,foregroundResumeCompleted,foregroundResumeSystem,foregroundResumeSystemVideo;
 int foregroundResumeRequest=-1,playRequestId=9,releases,starts,focusAbandons;IMediaPlayer player=new Player();Proxy proxy=new Proxy();
 boolean isFinishing(){return false;}Object getApplication(){return app;}
 long seekableDuration(){return 90000;}void abortChannelSwitchAnimation(){}void cancelCustomSourceTimeout(){}
 void clearPendingPlayer(){pendingPlayerChannel=null;}void releasePlayer(boolean keep){releases++;player=null;}
 void abandonPlaybackAudioFocus(){focusAbandons++;}void updateLoadingStatus(String value){}
 void hideLoading(){}void showChannelBar(String name,String text){}
 void startPlayer(Channel c,String url,boolean software,boolean direct)throws IOException {starts++;player=new Player();}
 void startAndroidMediaPlayer(Channel c,String url,boolean software,boolean video){starts++;player=new AndroidMediaPlayer();}
 void startResolvedPlayer(Channel c,String url,boolean direct){starts++;player=new Player();}
 METHODS
 static void check(boolean b,String label){if(!b)throw new AssertionError(label);}
 public static void main(String[] args){
  BackgroundCheck c=new BackgroundCheck();IMediaPlayer original=c.player;c.reconcilePlaybackVisibility();
  check(c.player==original&&c.releases==0&&!c.proxy.paused&&!c.backgroundPlaybackSuspended,"default background must keep player/proxy/focus");
  c.backgroundPlaybackEnabled=false;c.reconcilePlaybackVisibility();
  check(c.releases==1&&c.player==null&&c.proxy.paused&&c.focusAbandons==1&&c.backgroundPosition==42000,"disabled background suspends and retains VOD position");
  c.reconcilePlaybackVisibility();check(c.releases==1,"duplicate stop must not release twice");
  c.app.visible=true;c.reconcilePlaybackVisibility();
  check(c.starts==1&&!c.proxy.paused&&c.foregroundResumePosition==42000&&c.foregroundResumeRequest==9,"foreground restores original resolved URL/position once");
  c.reconcilePlaybackVisibility();check(c.starts==1,"duplicate foreground must not reopen");
  c=new BackgroundCheck();c.backgroundPlaybackEnabled=false;c.reconcilePlaybackVisibility();c.backgroundPlaybackEnabled=true;c.reconcilePlaybackVisibility();
  check(c.starts==1&&!c.backgroundPlaybackSuspended,"enabling from management while background reconciles immediately");
  c=new BackgroundCheck();c.backgroundPlaybackEnabled=false;LegacyWebSession.current=new LegacyWebSession();LegacyWebSession.current.active=true;LegacyWebSession.current.foreground=true;
  c.reconcilePlaybackVisibility();check(c.releases==0,"internal browser still counts as visible");LegacyWebSession.current=null;
  c=new BackgroundCheck();c.backgroundPlaybackEnabled=false;c.reconcilePlaybackVisibility();Channel next=new Channel();
  check(c.deferBackgroundPlayer(next,"http://fixture/next",false,false,true)&&c.backgroundChannel==next&&c.backgroundPosition==0,"late resolver saves new channel without background start");
  c.app.visible=true;c.reconcilePlaybackVisibility();check(c.starts==1,"deferred resolution resumes on foreground");
  System.out.println("PASS background default/disable/enable, proxy and audio focus, position, duplicate lifecycle, internal browser and late resolver gates");
 }
}'''.replace(' METHODS','\n'.join(method(s) for s in ('    void reconcilePlaybackVisibility(', '    private boolean deferBackgroundPlayer(')))
jdk=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
with tempfile.TemporaryDirectory() as temp:
    path=Path(temp)/'BackgroundCheck.java';path.write_text(java,encoding='utf8')
    subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8',str(path)],check=True)
    subprocess.run([str(jdk/'java.exe'),'-cp',temp,'BackgroundCheck'],check=True)
