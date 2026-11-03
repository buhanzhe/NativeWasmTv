"""Regress covered-page readiness and native takeover gesture routing."""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]

def method(file, signature):
    source = (root/'app/src/main/java/xiao/bu/tv'/file).read_text(encoding='utf8')
    start = source.index(signature)
    pos = source.index('{', start) + 1
    depth = 1
    while depth:
        depth += (source[pos] == '{') - (source[pos] == '}')
        pos += 1
    return source[start:pos]

java = r'''class TransitionRoutingCheck {
 static class View {}
 static int checks;
 static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
 static class Web {
  Object legacy,webView=new Object();int requestId=7;
  boolean minimalBrowserMode=true,interactivePageFallback,visible=true,pageActive=true;
  boolean pageCovered=true,autoPlayEnabled=true,pageLoading,ready,active=true;
  boolean isPageVisible(){return visible;}
  boolean isActiveDocument(Object view){return active;}
  boolean hasPlayableWebMedia(){return ready;}
  boolean ownsMinimalPlaybackInput(){return visible&&minimalBrowserMode&&!interactivePageFallback;}
  void setPageCovered(boolean value){pageCovered=value;}
  void animateChannelSwipe(float x,float y,Runnable run){animations++;}
  void animateChannelSwipe(float x,float y,View frame,Runnable run){animations++;}
  int reservation;boolean reserved;int reserveChannelSwipe(){reserved=true;return ++reservation;}boolean isChannelSwipeReserved(int token){return reserved&&token==reservation;}
  void cancelChannelSwipe(){reserved=false;}
  int animations;
  WEB_METHODS
 }
 Web webSourceView=new Web();boolean playingDiscoveredWebStream,webViewMinimalMode=true;
 static class Lite {boolean isRendering(){return true;}}
 Lite liteWebPlayback;int playRequestId=7;boolean isFinishing(){return false;}
 static class Bar {void removeCallbacks(Runnable r){}}
 Bar channelBar=new Bar();Runnable finishGestureRebound;boolean gestureReboundAnimating;
 static class Frame {void capture(Runnable r){r.run();}View animationView(){return new View();}void clear(){}}
 Frame webLastFrame;void ensureWebLastFrame(){webLastFrame=new Frame();}
 MAIN_METHODS
 public static void main(String[] args){
  Web w=new Web();
  check(!w.isChannelPresentationReady(7),"DOM completion under black cover revealed incoming page before video");
  w.ready=true;check(w.isChannelPresentationReady(7),"actual video did not release last frame");
  check(!w.isChannelPresentationReady(6),"stale page released last frame");
  w.revealForChannelTransition();check(!w.pageCovered,"black page mask survived last-frame/slide cover");
  w.interactivePageFallback=true;check(w.canCaptureChannelFrame(),"login fallback bypassed list snapshot");
  check(!w.canAnimateChannelSwipe(),"unplayed login page swallowed webpage gestures");
  w.interactivePageFallback=false;check(w.canAnimateChannelSwipe(),"late playback cannot slide");
  w.minimalBrowserMode=false;w.pageCovered=true;w.revealForChannelTransition();
  check(w.pageCovered&&!w.canCaptureChannelFrame(),"normal browser mode changed");
  TransitionRoutingCheck a=new TransitionRoutingCheck();
  a.webSourceView.pageCovered=false;
  check(a.animateWebChannelSwipe(0,-1,()->{})&&a.webSourceView.animations==1,"webpage skipped its own slide");
  a.playingDiscoveredWebStream=true;a.liteWebPlayback=new Lite();
  check(!a.isMinimalWebPlayback(),"native sniffed playback used webpage gesture policy");
  check(!a.animateWebChannelSwipe(0,-1,()->{})&&a.webSourceView.animations==1,"native sniffed playback animated retained webpage");
  a.webSourceView.visible=false;check(!a.webSourceView.canCaptureChannelFrame(),"hidden retained webpage captured instead of native Surface");
  System.out.println("PASS "+checks+" transition routing checks: covered DOM vs frame, login snapshot, late playback, normal mode and native takeover");
 }
}'''
java = java.replace('WEB_METHODS', '\n'.join(method('WebSourceView.java', signature) for signature in [
    '    boolean canCaptureChannelFrame(', '    boolean canAnimateChannelSwipe(',
    '    boolean isChannelPresentationReady(', '    void revealForChannelTransition(']))
java = java.replace('MAIN_METHODS', '\n'.join(method('MainActivity.java', signature) for signature in [
    '    private boolean isMinimalWebPlayback(', '    private boolean animateWebChannelSwipe(']))
jdk = Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
with tempfile.TemporaryDirectory() as folder:
    path = Path(folder)/'TransitionRoutingCheck.java'
    path.write_text(java, encoding='utf8')
    subprocess.run([str(jdk/'javac.exe'), '-encoding', 'UTF-8', '-d', folder, str(path)], check=True)
    subprocess.run([str(jdk/'java.exe'), '-cp', folder, 'TransitionRoutingCheck'], check=True)
