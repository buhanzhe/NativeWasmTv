"""Exercise production menu handoff and Surface retirement with controlled draw timing."""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
src = (root / 'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf8')

def method(start, end):
    return src[src.index(start):src.index(end, src.index(start))]

methods = method('    private void switchBrowsingChannel(', '    private void cancelPendingChannelListSelection(')
methods += method('    private void finishPhoneSurfaceSwipe()', '    private boolean isTelevisionDevice()')
methods += method('    private boolean isWaitingForIncomingFrame(', '    private void animateSourceSwitch(')

observer = '''package android.view;
public class ViewTreeObserver {
 public interface OnPreDrawListener {boolean onPreDraw();}
 public OnPreDrawListener listener;
 public boolean isAlive(){return true;}
 public void addOnPreDrawListener(OnPreDrawListener value){listener=value;}
 public void removeOnPreDrawListener(OnPreDrawListener value){if(listener==value)listener=null;}
}'''
java = r'''import java.util.*;
class ViewGroup implements android.view.ViewParent {
 void removeView(FrameHandoffCheck.DirectVideoView v){v.detached=true;v.parent=null;}
}
class FrameHandoffCheck {
 static class WebSource {boolean isPageVisible(){return false;}void nativeChannelSwipeReady(int id){}void cancelChannelSwipe(){}}
 WebSource webSourceView;
 static class WebLastFrame {void nativeReady(int id){}}
 WebLastFrame webLastFrame;
 boolean holdWebChannelFrame(Runnable navigate){return false;}
 static class PersonalBackground {int ready;void finishStartup(){ready++;}}
 PersonalBackground personalBackground=new PersonalBackground();
 static class DirectVideoView {
  boolean covered,released,detached;float x,y;int cancels;
  ViewGroup parent=new ViewGroup();
  android.view.ViewParent getParent(){return parent;}
  void releaseOutput(){released=true;}
  DirectVideoView animate(){return this;}void cancel(){cancels++;}
  void setFrameCovered(boolean value){covered=value;}
  void setTranslationX(float value){x=value;}void setTranslationY(float value){y=value;}
 }
 static class Root {
  android.view.ViewTreeObserver observer=new android.view.ViewTreeObserver();List<Runnable> posts=new ArrayList<>();
  int getWidth(){return 1280;}void invalidate(){}android.view.ViewTreeObserver getViewTreeObserver(){return observer;}
  void post(Runnable run){posts.add(run);}
  void draw(){if(observer.listener!=null)observer.listener.onPreDraw();List<Runnable> next=new ArrayList<>(posts);posts.clear();for(Runnable run:next)run.run();}
 }
 static class Channel {String key;Channel(String key){this.key=key;}}
 static class ChannelCatalog {
  static class Group {Channel[] channels={new Channel("A"),new Channel("B"),new Channel("C")};}
  static int wrapIndex(Channel[] channels,int i){return (i+channels.length)%channels.length;}
 }
 static class BrowseCursor {int selected;String selectedKey;}
 static class Ui {void removeCallbacks(Runnable r){}}
 Root root=new Root();Ui channelBar=new Ui();ChannelCatalog.Group group=new ChannelCatalog.Group();
 DirectVideoView videoView=new DirectVideoView(),standbyVideoView,retiringVideoView;
 int currentGroupIndex,browsingGroupIndex,currentChannelIndex,playRequestId=10,concealedVideoRequestId=-1,channelSwitchRequestId=-1;
 boolean channelSwitchAnimating,channelSwitchEntering,menu=true,observedCoverAtClose,finishing;
 float channelSwitchDirectionX,channelSwitchDirectionY;
 String remoteCatalogUrl="";int switches;
 Map<String,BrowseCursor> channelCursors=new HashMap<>();Runnable pendingChannelListSelection,advanceChannelSwipe=()->{};
 android.view.ViewTreeObserver.OnPreDrawListener channelListSelectionDraw;
 void cancelPendingChannelListSelection(){pendingChannelListSelection=null;}
 ChannelCatalog.Group currentGroup(){return group;}
 void closeChannelList(boolean overlay){observedCoverAtClose=videoView.covered;menu=false;}
 String groupNavigationKey(ChannelCatalog.Group g){return "group";}String channelNavigationKey(Channel c){return c.key;}
 boolean isFinishing(){return finishing;}void applyClockLocation(){}
 void switchChannel(int index){switches++;playRequestId++;if(videoView.covered)concealedVideoRequestId=playRequestId;}
 boolean passwordAllowed=true;
 boolean requireGroupPassword(int group,int channel,boolean restore,Runnable resume){return passwordAllowed;}
 void resetPlaybackLayerImmediately(){videoView.x=videoView.y=0;}
 void invalidatePlaybackDebugInfo(){}
 METHODS
 static int checks;
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 public static void main(String[] args){
  FrameHandoffCheck a=new FrameHandoffCheck();a.passwordAllowed=false;a.switchBrowsingChannel(2);
  check(a.menu&&!a.videoView.covered&&a.switches==0&&a.pendingChannelListSelection==null,"Locked group covered or replaced outgoing playback before authentication");
  a.passwordAllowed=true;a.switchBrowsingChannel(2);
  DirectVideoView retained=a.videoView;
  check(!a.observedCoverAtClose&&!a.videoView.covered,"Closing menu blacked out retained frame");
  check(a.switches==0&&!a.menu,"Decoder work ran before the closing draw");
  a.revealIncomingChannel(10);check(!a.videoView.covered,"Old callback changed retained picture");
  a.root.draw();check(a.switches==1&&!a.videoView.covered&&a.videoView==retained&&!retained.released,"List switch cleared or replaced retained Surface");
  check(a.concealedVideoRequestId==-1,"List switch unexpectedly concealed retained frame");
  a.revealIncomingChannel(11);check(!a.videoView.covered,"Intermediate request blacked out retained picture");
  a.revealIncomingChannel(12);check(!a.videoView.covered,"Target first frame remained hidden");
  a=new FrameHandoffCheck();a.switchBrowsingChannel(1);Runnable stale=a.pendingChannelListSelection;
  a.switchBrowsingChannel(2);stale.run();check(a.switches==0&&!a.videoView.covered,"Stale menu selection started decoder or blacked out Surface");
  a.root.draw();check(a.switches==1&&a.currentChannelIndex==2&&!a.videoView.covered,"Latest list selection blacked out Surface");
  a.revealIncomingChannel(a.playRequestId);check(!a.videoView.covered,"Latest frame not revealed");
  a=new FrameHandoffCheck();a.videoView.covered=true;a.switchBrowsingChannel(1);a.root.draw();
  check(a.videoView.covered&&a.concealedVideoRequestId==a.playRequestId,"List exposed a Surface that had no valid incoming frame yet");
  a.revealIncomingChannel(a.playRequestId);check(!a.videoView.covered,"Previously pending Surface never revealed target frame");
  a=new FrameHandoffCheck();DirectVideoView outgoing=a.videoView,incoming=new DirectVideoView();
  a.videoView=incoming;a.standbyVideoView=outgoing;a.retiringVideoView=outgoing;
  a.channelSwitchAnimating=true;a.channelSwitchEntering=true;a.channelSwitchDirectionY=-1;a.channelSwitchRequestId=10;
  incoming.covered=true;incoming.y=50;a.abortChannelSwitchAnimation();
  check(a.videoView==incoming&&outgoing.covered&&outgoing.released&&outgoing.detached&&a.standbyVideoView==null,"Retired Surface still retains older channel");
  check(!incoming.released&&!incoming.detached,"Retirement destroyed incoming output");
  check(a.retiringVideoView==null&&!a.channelSwitchAnimating&&incoming.y==0,"Menu inherited unfinished swipe");
  a.switchBrowsingChannel(2);a.root.draw();a.revealIncomingChannel(a.playRequestId);
  check(!incoming.covered&&outgoing.covered,"Incoming first frame revealed retired Surface");
  a=new FrameHandoffCheck();outgoing=a.videoView;incoming=new DirectVideoView();
  a.videoView=incoming;a.standbyVideoView=outgoing;a.retiringVideoView=outgoing;a.channelSwitchRequestId=-1;
  a.finishPhoneSurfaceSwipe();
  check(a.videoView==outgoing&&!outgoing.covered&&!outgoing.released&&!outgoing.detached&&a.standbyVideoView==null&&incoming.covered&&incoming.released&&incoming.detached,"Retained decoder rollback destroyed wrong Surface");
  a.finishPhoneSurfaceSwipe();check(!outgoing.released,"Duplicate cleanup destroyed restored decoder");
  a=new FrameHandoffCheck();a.remoteCatalogUrl="remote";a.switchBrowsingChannel(2);
  check(!a.videoView.covered,"Retained cast now waits for nonexistent first-frame callback");
  System.out.println("PASS "+checks+" frame handoff checks: closing draw, stale requests, repeated list selections, swipe retirement and retained cast");
 }
}'''.replace('METHODS', methods)
with tempfile.TemporaryDirectory() as folder:
    out = Path(folder)
    target = out / 'android/view/ViewTreeObserver.java'
    target.parent.mkdir(parents=True)
    target.write_text(observer, encoding='utf8')
    parent = out / 'android/view/ViewParent.java'
    parent.write_text('package android.view; public interface ViewParent {}', encoding='utf8')
    check = out / 'FrameHandoffCheck.java'
    check.write_text(java, encoding='utf8')
    jdk = Path(os.environ.get('JAVA_HOME', root / '.codex-tmp/jdk8/runtime/jdk8u504-b01')) / 'bin'
    subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '-d', folder, str(check), str(target), str(parent)], check=True)
    subprocess.run([str(jdk / 'java.exe'), '-cp', folder, 'FrameHandoffCheck'], check=True)
