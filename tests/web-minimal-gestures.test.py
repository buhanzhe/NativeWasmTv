"""Exercise minimal browser touch ownership and the production playback entry gate."""
from pathlib import Path
import os, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
web=(root/'app/src/main/java/xiao/bu/tv/WebSourceView.java').read_text('utf8')
main=(root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text('utf8')
def method(text,name):
    start=text.index(name); at=text.index('{',start)+1; depth=1
    while depth:
        depth+=(text[at]=='{')-(text[at]=='}');at+=1
    return text[start:at].replace('@Override ','')
java=r'''import java.util.*;
class MotionEvent {
 static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3;
 int action;boolean primary=true;float x=100,y=200;
 MotionEvent(int a){action=a;}int getActionMasked(){return action;}
 static MotionEvent obtain(MotionEvent e){MotionEvent n=new MotionEvent(e.action);n.primary=e.primary;n.x=e.x;n.y=e.y;return n;}
 void transform(android.graphics.Matrix m){x*=m.x;y*=m.y;}void recycle(){}
}
class android {
 static class graphics {static class Matrix {float x=1,y=1;void setScale(float a,float b){x=a;y=b;}}}
 static class os {static class Bundle extends HashMap<String,Object> {
  boolean getBoolean(String k,boolean d){return containsKey(k)?(Boolean)get(k):d;}
  void putParcelable(String k,Object v){put(k,v);}
 }}
}
class View {static final int VISIBLE=0;int visibility=4;int getVisibility(){return visibility;}int getWidth(){return 1000;}int getHeight(){return 500;}}
class Base {int pageEvents;public boolean dispatchTouchEvent(MotionEvent e){pageEvents++;return true;}}
class Menu {boolean shown;boolean visible(){return shown;}}
class Lite {boolean rendering;boolean isRendering(){return rendering;}}
class WebTouch extends Base {
 static class Swipe {boolean active(){return false;}}
 Swipe channelSwipe=new Swipe();boolean isChannelSwipeActive(){return channelSwipe.active();}
 boolean isInteractivePageFallback(){return false;}
 boolean minimalBrowserMode,pageActive,pageCovered,defaultPageCovered,minimalPlaybackTouchActive;
 class Legacy {boolean visible;android.os.Bundle state=new android.os.Bundle();int sent;void send(String s,android.os.Bundle b){sent++;}}
 Legacy legacy;int calls;Menu tabBar=new Menu();
 interface Listener {void onBrowserPlaybackTouch(MotionEvent e,int w,int h);}
 Listener listener=(e,w,h)->calls++;
 boolean isPageVisible(){return legacy!=null?legacy.visible:pageActive;}
 int getWidth(){return 1000;}int getHeight(){return 500;}
 void updateFullscreenExitForMouse(MotionEvent e){}void presentPlayerControls(){}
 WEB
}
public class MinimalBrowserCheck {
 View root=new View(),channelListPanel=new View(),managementPanel=new View(),backPrompt=new View();
 Lite liteWebPlayback=new Lite();WebTouch webSourceView=new WebTouch();Menu tvUiMenu=new Menu(),jarChannelRuntime=new Menu();
 Object userScriptInstallOverlay;boolean channelSwitchAnimating,gestureReboundAnimating,webViewMinimalMode,playingDiscoveredWebStream;
 boolean playbackGestureTracking,playbackGestureEdgeBlocked;int dispatched;float x,y;
 boolean isAudioArtworkInteractive(){return false;}boolean isPrimaryPlaybackPointer(MotionEvent e){return e.primary;}
 boolean dispatchTouchEvent(MotionEvent e){dispatched++;x=e.x;y=e.y;return true;}
 MAIN
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[] args){
  WebTouch w=new WebTouch();w.pageActive=true;w.minimalBrowserMode=true;
  w.dispatchTouchEvent(new MotionEvent(0));w.dispatchTouchEvent(new MotionEvent(2));w.dispatchTouchEvent(new MotionEvent(1));
  check(w.calls==3&&w.pageEvents==0&&!w.minimalPlaybackTouchActive,"minimal sequence leaks to page");
  w.dispatchTouchEvent(new MotionEvent(0));w.minimalBrowserMode=false;w.dispatchTouchEvent(new MotionEvent(2));w.dispatchTouchEvent(new MotionEvent(3));
  check(w.calls==6&&w.pageEvents==0,"mode change leaked in-flight pointer");
  w.dispatchTouchEvent(new MotionEvent(0));w.dispatchTouchEvent(new MotionEvent(1));check(w.pageEvents==2,"normal browser interaction lost");
  w.minimalBrowserMode=true;w.pageCovered=true;w.dispatchTouchEvent(new MotionEvent(0));w.dispatchTouchEvent(new MotionEvent(1));check(w.pageEvents==4,"cover reveal swallowed");
  w.legacy=w.new Legacy();w.legacy.visible=true;w.legacy.state.put("covered",false);
  w.dispatchTouchEvent(new MotionEvent(0));w.dispatchTouchEvent(new MotionEvent(1));check(w.calls==8&&w.legacy.sent==0,"legacy facade relayed raw page click");
  MinimalBrowserCheck m=new MinimalBrowserCheck();check(m.canStartPlaybackGesture(),"native live gate");
  m.webSourceView.pageActive=true;check(!m.canStartPlaybackGesture(),"normal web must retain page input");
  m.webSourceView.minimalBrowserMode=true;m.webViewMinimalMode=true;check(m.canStartPlaybackGesture(),"minimal web gate closed");
  m.handleMinimalBrowserTouch(new MotionEvent(0),2000,1000);check(m.dispatched==1&&m.x==50&&m.y==100,"IPC dimensions not normalized");
  MotionEvent secondary=new MotionEvent(0);secondary.primary=false;m.handleMinimalBrowserTouch(secondary,1000,500);check(m.dispatched==1,"secondary mouse recurses");
  m.channelListPanel.visibility=0;check(!m.canStartPlaybackGesture(),"channel menu lost ownership");m.channelListPanel.visibility=4;
  m.tvUiMenu.shown=true;check(!m.canStartPlaybackGesture(),"TV menu lost ownership");m.tvUiMenu.shown=false;
  m.userScriptInstallOverlay=new Object();check(!m.canStartPlaybackGesture(),"script dialog lost ownership");m.userScriptInstallOverlay=null;
  m.webSourceView.pageCovered=true;check(!m.canStartPlaybackGesture(),"cover reveal gate");
  m.webSourceView.pageActive=false;m.liteWebPlayback.rendering=true;check(m.canStartPlaybackGesture()&&m.isMinimalWebPlayback(),"lite minimal rendering gate");
  m.webViewMinimalMode=false;check(!m.canStartPlaybackGesture(),"normal lite page input");
  m.playbackGestureTracking=true;m.handleMinimalBrowserTouch(new MotionEvent(1),1000,500);
  check(m.dispatched==2,"in-flight UP lost after mode change");
  System.out.println("PASS minimal input: complete gesture ownership, cancellation/mode changes, cover/normal browser, legacy relay, scaling, mouse guard, menus and lite rendering");
 }
}'''
java=java.replace('WEB','\n'.join(method(web,n) for n in ('boolean ownsMinimalPlaybackInput(', 'boolean isPageCovered(', 'public boolean dispatchTouchEvent(')))
java=java.replace('MAIN','\n'.join(method(main,n) for n in ('private boolean canStartPlaybackGesture(', 'private boolean isMinimalWebPlayback(', 'private void handleMinimalBrowserTouch(')))
jdk=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
with tempfile.TemporaryDirectory() as tmp:
    p=Path(tmp)/'MinimalBrowserCheck.java';p.write_text(java,'utf8')
    subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8',str(p)],check=True)
    subprocess.run([str(jdk/'java.exe'),'-cp',tmp,'MinimalBrowserCheck'],check=True)
