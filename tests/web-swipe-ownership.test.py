"""Run real finger-release and WebChannelSwipe together, including hidden EPG focus."""
from pathlib import Path
import os
import subprocess
import tempfile

root=Path(__file__).resolve().parents[1]
source=(root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf8')
def method(signature):
    start=source.index(signature);pos=source.index('{',start)+1;depth=1
    while depth:
        depth+=(source[pos]=='{')-(source[pos]=='}');pos+=1
    return source[start:pos]

files={
'android/view/animation/DecelerateInterpolator.java':'package android.view.animation;public class DecelerateInterpolator {}',
'android/view/View.java':r'''package android.view;import java.util.*;
public class View {
 public static final int VISIBLE=0,GONE=8;public int visibility=VISIBLE,cancels;public float x,y,alpha=1;
 public static int LAYER_TYPE_HARDWARE=2;public int layer;public int getLayerType(){return layer;}public void setLayerType(int n,Object p){layer=n;}
 public boolean focus;public List<Runnable> queue=new ArrayList<>();public Animation animation=new Animation();
 public int getWidth(){return 1000;}public int getHeight(){return 600;}public int getVisibility(){return visibility;}
 public boolean hasFocus(){return focus;}public int getSelectedItemPosition(){return 0;}public void setSelection(int n){}
 public void setTranslationX(float n){x=n;}public void setTranslationY(float n){y=n;}public void setAlpha(float n){alpha=n;}
 public Animation animate(){return animation;}public void postDelayed(Runnable r,long ms){queue.add(r);}
 public void removeCallbacks(Runnable r){queue.removeIf(v->v==r);}
 public class Animation {
  float nextX,nextY;public Runnable end;
  public Animation translationX(float n){nextX=n;return this;}public Animation translationY(float n){nextY=n;return this;}
  public Animation setDuration(long n){return this;}public Animation setInterpolator(Object o){return this;}
  public Animation withLayer(){return this;}
  public Animation withEndAction(Runnable r){end=r;return this;}public void start(){}public void cancel(){cancels++;end=null;}
  public void finish(){x=nextX;y=nextY;Runnable r=end;end=null;if(r!=null)r.run();}
 }
}''',
'xiao/bu/tv/SwipeOwnershipCheck.java':r'''package xiao.bu.tv;import android.view.View;
public class SwipeOwnershipCheck {
 class Web extends View {
  boolean allowed=true;WebChannelSwipe swipe=new WebChannelSwipe(this,new WebChannelSwipe.Host(){
   public boolean ready(int id){return false;}public void timedOut(){}
  });
  int reserveChannelSwipe(){return swipe.reserve();}boolean isChannelSwipeReserved(int token){return swipe.reserved(token);}
  boolean isChannelSwipeActive(){return swipe.active();}boolean canAnimateChannelSwipe(){return allowed;}
  boolean isPageVisible(){return true;}void animateChannelSwipe(float x,float y,Runnable r){swipe.start(x,y,r);}
  void animateChannelSwipe(float x,float y,View image,Runnable r){swipe.start(x,y,image,r);}
  void cancelChannelSwipe(){swipe.cancel();}
 }
 static class AdapterView {static int INVALID_POSITION=-1;}
 static class Epg {int currentProgramIndex(){return 0;}int getCount(){return 3;}}
 static class PlaybackGesturePolicy {static int CHANNEL=1;}
 Web webSourceView=new Web();View videoView=new View(),channelBar=new View(),epgList=new View(),channelListPanel=new View();
 Epg epgAdapter=new Epg();boolean channelSwitchAnimating,gestureReboundAnimating,playingDiscoveredWebStream;
 boolean playbackGestureVod,playbackGestureVertical=true,playbackGestureHorizontal,playbackGestureTracking=true,playbackGestureControls;
 int playbackGestureAction=1,playRequestId=10,switches,nativeSwipes,restores;Runnable finishGestureRebound=()->{};
 Runnable commitRelativeChannelSwitch=()->{};
 class Frame {View image=new View();boolean preview,defer,failed;Runnable waiting;
  void capture(Runnable r){image.y=webSourceView.y;if(defer)waiting=r;else r.run();}View animationView(){return failed?null:image;}
  boolean isSwipePreview(){return preview;}void clear(){preview=false;}void finishPreview(){preview=false;}}
 Frame webLastFrame;void ensureWebLastFrame(){if(webLastFrame==null)webLastFrame=new Frame();}
 boolean isFinishing(){return false;}boolean isAudioArtworkInteractive(){return false;}
 void switchRelative(int direction){switches+=direction;playRequestId++;webSourceView.swipe.bind(playRequestId);}
 void beginChannelSwipe(float x,float y){nativeSwipes++;channelSwitchAnimating=true;}
 void restorePlaybackLayer(){restores++;webSourceView.setTranslationY(0);}
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 METHODS
 public static void main(String[]args){
  SwipeOwnershipCheck a=new SwipeOwnershipCheck();a.channelListPanel.visibility=View.GONE;a.epgList.focus=true;
  a.webSourceView.y=-140;a.gestureReboundAnimating=true;a.channelBar.queue.add(a.finishGestureRebound);
  a.animateRelativeChannelSwitch(1,0,-1);
  check(a.webSourceView.isChannelSwipeActive(),"hidden focused EPG swallowed committed swipe and caused rebound");
  check(!a.gestureReboundAnimating&&a.channelBar.queue.isEmpty(),"committed web swipe retained old rebound callback");
  a.resetPlaybackGesture();check(a.restores==0&&a.webSourceView.y==-140,"finger UP restored outgoing page to center");
  int cancels=a.webSourceView.cancels;a.setPlaybackLayerTranslation(0,0);
  check(a.webSourceView.y==-140&&a.webSourceView.cancels==cancels,"late preview cancelled committed animation");
  a.resetPlaybackLayerImmediately();check(a.webSourceView.y==-140,"stale cleanup centered committed page");
  a.webLastFrame.image.animation.finish();
  check(a.switches==1&&a.webLastFrame.image.y==-600,"outgoing snapshot failed to navigate offscreen");
  a.resetPlaybackGesture();a.resetPlaybackLayerImmediately();
  check(a.webSourceView.y==-140&&a.restores==0,"loading page changed live browser position");
  a=new SwipeOwnershipCheck();a.channelListPanel.visibility=View.VISIBLE;a.epgList.focus=true;
  a.animateRelativeChannelSwitch(1,0,-1);check(!a.webSourceView.isChannelSwipeActive()&&a.switches==0,"visible EPG lost keyboard navigation");
  a=new SwipeOwnershipCheck();a.channelListPanel.visibility=View.GONE;a.playingDiscoveredWebStream=true;
  a.animateRelativeChannelSwitch(1,0,-1);check(a.nativeSwipes==1&&!a.webSourceView.isChannelSwipeActive(),"native sniffed media used webpage animation");
  a=new SwipeOwnershipCheck();a.resetPlaybackGesture();check(a.restores==1,"uncommitted short gesture no longer rebounds");
  a=new SwipeOwnershipCheck();a.channelListPanel.visibility=View.GONE;a.ensureWebLastFrame();a.webLastFrame.defer=true;a.webSourceView.y=-140;
  a.animateRelativeChannelSwitch(1,0,-1);check(a.webSourceView.isChannelSwipeActive(),"async snapshot gap does not own committed swipe");
  a.resetPlaybackGesture();a.resetPlaybackLayerImmediately();check(a.restores==0&&a.webSourceView.y==-140,"delayed GPU copy let finger release rebound page");
  a.webLastFrame.waiting.run();check(a.webSourceView.isChannelSwipeActive(),"snapshot completion dropped transition ownership");
  a.webLastFrame.image.animation.finish();check(a.switches==1,"delayed capture did not navigate exactly once");
  a=new SwipeOwnershipCheck();a.channelListPanel.visibility=View.GONE;a.ensureWebLastFrame();a.webLastFrame.defer=true;
  a.animateRelativeChannelSwitch(1,0,-1);a.webSourceView.cancelChannelSwipe();a.webLastFrame.waiting.run();
  check(!a.webSourceView.isChannelSwipeActive()&&a.switches==0,"cancelled pending capture restarted swipe");
  a=new SwipeOwnershipCheck();a.channelListPanel.visibility=View.GONE;a.ensureWebLastFrame();a.webLastFrame.failed=true;
  a.animateRelativeChannelSwitch(1,0,-1);check(!a.webSourceView.isChannelSwipeActive()&&a.switches==1,"failed snapshot retained reservation or lost channel navigation");
  System.out.println("PASS "+checks+" swipe ownership checks: hidden EPG, committed UP, pending rebound, late preview/cleanup, offscreen load, native routing and short gesture");
 }
}'''
}
files['xiao/bu/tv/SwipeOwnershipCheck.java']=files['xiao/bu/tv/SwipeOwnershipCheck.java'].replace('METHODS','\n'.join(method(s) for s in [
 '    private void animateRelativeChannelSwitch(final int offset, final float x,',
 '    private boolean animateWebChannelSwipe(', '    private void resetPlaybackGesture(',
 '    private void setPlaybackLayerTranslation(', '    private void resetPlaybackLayerImmediately()',
 '    private static void resetPlaybackLayerImmediately(View view)']))
jdk=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
with tempfile.TemporaryDirectory() as folder:
    paths=[]
    for name,text in files.items():
        p=Path(folder)/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf8');paths.append(str(p))
    subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',folder,*paths,str(root/'app/src/main/java/xiao/bu/tv/WebChannelSwipe.java')],check=True)
    subprocess.run([str(jdk/'java.exe'),'-cp',folder,'xiao.bu.tv.SwipeOwnershipCheck'],check=True)
