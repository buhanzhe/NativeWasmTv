"""Run production gesture dispatch with controlled touch/audio state (no decoder)."""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf8')
def method(signature):
    start = source.index(signature)
    opening = source.index('{', start)
    depth = 1
    at = opening + 1
    while depth:
        depth += (source[at] == '{') - (source[at] == '}')
        at += 1
    return source[start:at]

methods = '\n'.join(method(name) for name in (
    '    private void beginPlaybackGesture(', '    private boolean handlePlaybackGesture(',
    '    private void updateGestureVolume(', '    private void updateGestureBrightness(',
    '    private void resetPlaybackGesture(',
))
java = r'''package xiao.bu.tv;
public class GestureSettingsCheck {
 static class MotionEvent {
  static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3;
  float x,y;int action,pointers=1;
  MotionEvent(int a,float x,float y){action=a;this.x=x;this.y=y;}
  static MotionEvent obtain(MotionEvent e){return new MotionEvent(e.action,e.x,e.y);}
  static MotionEvent obtain(long d,long t,int a,float x,float y,int m){return new MotionEvent(a,x,y);}
  int getActionMasked(){return action;}int getPointerCount(){return pointers;}
  float getX(){return x;}float getY(){return y;}long getDownTime(){return 0;}long getEventTime(){return 1;}
  void setAction(int a){action=a;}void recycle(){}
 }
 static class Root {int getWidth(){return 1000;}int getHeight(){return 1000;}void removeCallbacks(Runnable r){}}
 static class AudioManager {
  static final int STREAM_MUSIC=3;int volume=5,sets;
  int getStreamMaxVolume(int stream){return 10;}int getStreamVolume(int stream){return volume;}
  void setStreamVolume(int stream,int value,int flags){volume=value;sets++;}
 }
 static class Toast {
  static final int LENGTH_SHORT=0;static Toast makeText(Object o,String s,int t){return new Toast();}
  void setText(String s){}void show(){}
 }
 static class PlaybackBrightness {static int percent(GestureSettingsCheck o){return o.brightness;}static void apply(GestureSettingsCheck o,int v){o.brightness=v;}}
 static class TvUiPlugin {static Object current(){return null;}}
 static class Controls {boolean shown;int events;boolean visible(){return shown;}boolean handleSurfaceGesture(MotionEvent e){events++;return true;}}
 static class WebSource {boolean visible,swiping;int brightness;boolean isPageVisible(){return visible;}boolean isChannelSwipeActive(){return swiping;}void setPlaybackBrightness(int v){brightness=v;}}
 WebSource webSourceView=new WebSource();
 Root root=new Root(),channelBar=new Root();Controls videoControlsOverlay=new Controls();AudioManager playbackAudioManager=new AudioManager();
 static final String AUDIO_SERVICE="audio";Toast playbackBrightnessToast;Runnable finishGestureRebound;
 String leftVerticalGesture="brightness",rightVerticalGesture="channel";
 boolean gestureReboundAnimating,playbackGestureTracking,playbackGestureVertical,playbackGestureHorizontal,playbackGestureLeftSide,
 playbackGestureVod,playbackGestureControls,playbackGestureEdgeBlocked,audioOnlyPlayback,prepared,channelSwitchAnimating;
 Object player;boolean vod;int brightness=50,previews,rebound,channels,sources,restored;
 static class Frame {boolean isSwipePreview(){return false;}}Frame webLastFrame;
 float playbackGestureDownX,playbackGestureDownY,playbackGestureDeltaX,playbackGestureDeltaY;
 int playbackGestureTouchSlop=8,playbackGestureAction,playbackGestureStartVolume,playbackGestureLastVolume,playbackGestureMaxVolume,
 playbackGestureStartBrightness,playbackGestureLastBrightness;
 boolean isAudioArtworkInteractive(){return false;}long seekableDuration(){return vod?90000:0;}
 GestureSettingsCheck getApplicationContext(){return this;}Object getSystemService(String s){return playbackAudioManager;}
 void showVideoControls(){videoControlsOverlay.shown=true;}void animateGestureRebound(){rebound++;}
 void moveSwitchPreview(float x,float y){previews++;}float dampedGestureDistance(float v,float size){return v;}
 void animateRelativeChannelSwitch(int direction,float shift){channels+=direction;channelSwitchAnimating=true;}
 void animateSourceSwitch(int direction,float shift){sources+=direction;}void restorePlaybackLayer(){restored++;}
 void openChannelList(){}void openTvUiMenu(){}void openWebManagement(){}
 void down(float x){beginPlaybackGesture(new MotionEvent(0,x,650));}
 void move(float x,float y){handlePlaybackGesture(new MotionEvent(2,x,y));}
 void up(float x,float y){handlePlaybackGesture(new MotionEvent(1,x,y));}
 static void check(boolean v,String message){if(!v)throw new AssertionError(message);}
 public static void main(String[] args){
  check(PlaybackGesturePolicy.resolve("brightness","channel",false,false)==PlaybackGesturePolicy.CHANNEL,"live default");
  check(PlaybackGesturePolicy.resolve("brightness","channel",false,true)==PlaybackGesturePolicy.VOLUME,"VOD default");
  check(PlaybackGesturePolicy.resolve("brightness","channel",false,true)==PlaybackGesturePolicy.VOLUME,"VOD ignores channel preference");
  check(PlaybackGesturePolicy.left("invalid").equals("brightness")&&PlaybackGesturePolicy.right("invalid").equals("channel"),"corrupt preference fallback");
  check(!PlaybackGesturePolicy.validLeft("channel")&&!PlaybackGesturePolicy.validRight("bad"),"validation");
  for(String left:new String[]{"brightness","volume","invalid"})for(String right:new String[]{"channel","brightness","volume","auto","invalid"}){
   check(PlaybackGesturePolicy.resolve(left,right,true,true)==PlaybackGesturePolicy.BRIGHTNESS,"VOD left always brightness");
   check(PlaybackGesturePolicy.resolve(left,right,false,true)==PlaybackGesturePolicy.VOLUME,"VOD right always volume");
  }
  GestureSettingsCheck a=new GestureSettingsCheck();a.leftVerticalGesture="volume";a.down(200);a.move(200,350);
  check(a.playbackAudioManager.volume==10&&a.brightness==50&&a.previews==0,"left volume must not dim or preview channel");
  a.move(200,350);check(a.playbackAudioManager.sets==1,"same volume must not write again");
  a.rightVerticalGesture="brightness";a.leftVerticalGesture="brightness";a.up(200,350);
  check(a.channels==0&&a.brightness==50,"in-flight gesture retains chosen volume action");
  GestureSettingsCheck b=new GestureSettingsCheck();b.rightVerticalGesture="brightness";b.down(800);b.move(800,350);b.up(800,350);
  check(b.brightness>50&&b.channels==0&&b.previews==0&&b.rebound==0&&b.restored==0,"right brightness must never switch or move Surface");
  GestureSettingsCheck c=new GestureSettingsCheck();c.down(800);c.move(800,350);check(c.channels==0&&c.previews==1,"channel preview only before release");c.up(800,350);check(c.channels==1,"up next channel");
  GestureSettingsCheck d=new GestureSettingsCheck();d.down(800);d.move(800,350);d.handlePlaybackGesture(new MotionEvent(3,800,350));check(d.channels==0,"cancelled swipe cannot switch");
  GestureSettingsCheck e=new GestureSettingsCheck();e.rightVerticalGesture="volume";e.down(800);e.move(800,950);e.up(800,950);check(e.playbackAudioManager.volume<5&&e.channels==0&&e.previews==0,"right volume downward");
  GestureSettingsCheck f=new GestureSettingsCheck();f.leftVerticalGesture="volume";f.down(200);f.move(550,650);f.up(550,650);check(f.sources==-1&&f.playbackAudioManager.sets==0,"horizontal live source swipe unchanged");
  GestureSettingsCheck g=new GestureSettingsCheck();g.vod=true;g.leftVerticalGesture="volume";g.rightVerticalGesture="channel";g.down(800);g.move(800,350);g.up(800,350);check(g.videoControlsOverlay.events==3&&g.channels==0,"VOD goes through the original controls");
  GestureSettingsCheck h=new GestureSettingsCheck();h.down(800);h.move(800,600);h.up(800,600);check(h.channels==0&&h.rebound==1,"short channel swipe rebounds");
  GestureSettingsCheck browser=new GestureSettingsCheck();browser.webSourceView.visible=true;browser.rightVerticalGesture="brightness";
  browser.down(800);browser.move(800,350);browser.up(800,350);
  check(browser.webSourceView.brightness==browser.brightness&&browser.brightness>50,"browser foreground receives brightness");
  GestureSettingsCheck multi=new GestureSettingsCheck();multi.down(800);multi.move(800,350);
  MotionEvent two=new MotionEvent(2,800,350);two.pointers=2;multi.handlePlaybackGesture(two);
  check(multi.channels==0&&!multi.playbackGestureTracking&&multi.playbackGestureEdgeBlocked&&multi.rebound==1,"second pointer cancels channel switch");
  System.out.println("PASS gesture dispatch: defaults, validation, left/right volume and brightness, volume deduplication, frozen action, release/cancel thresholds, fixed VOD controls, VOD delegation and horizontal source preservation");
 }
''' + methods + '\n}\n'
jdk = Path(os.environ.get('JAVA_HOME', 'C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
with tempfile.TemporaryDirectory() as folder:
    target=Path(folder)/'GestureSettingsCheck.java';target.write_text(java,encoding='utf8')
    subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',folder,str(target),
        str(root/'app/src/main/java/xiao/bu/tv/PlaybackGesturePolicy.java')],check=True)
    subprocess.run([str(jdk/'java.exe'),'-cp',folder,'xiao.bu.tv.GestureSettingsCheck'],check=True)
