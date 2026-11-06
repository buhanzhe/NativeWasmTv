from pathlib import Path
import subprocess,tempfile
root=Path(__file__).resolve().parents[1];s=(root/'app/src/main/java/xiao/bu/tv/WebSourceView.java').read_text(encoding='utf-8')
a=s.index('    private void resumeMinimalPlaybackInput(');b=s.index('    private void showInteractiveFallback',a)
java='''class CoverCheck {
 static class Build {static class VERSION {static int SDK_INT=25;}}
 static class JSONObject {boolean playable;int width=320,height=180,frames=1;JSONObject(boolean v){playable=v;}int optInt(String key){return key.equals("videoWidth")?width:height;}int optInt(String key,int fallback){return frames;}}
 boolean destroyed,pageActive=true,minimalBrowserMode=true,interactivePageFallback,pageCovered=true,visible=true,fullscreen,automaticPageCover=true,huya,autoPlay=true;int requestId=1,updates;JSONObject webMediaState;Object interactiveFallbackTask=new Object();
 boolean canRevealOriginalPlayer(){return huya&&autoPlay&&automaticPageCover;}
 boolean isPageVisible(){return visible;}boolean hasPlayableWebMedia(JSONObject m){return m.playable;}
 void setPageCovered(boolean v){pageCovered=v;updates++;}void setLoadingVisible(boolean v){}void removeCallbacks(Object r){}void setBrowserFullscreen(boolean v){fullscreen=v;}
 METHOD
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[]a){
  CoverCheck early=new CoverCheck();early.resumeMinimalPlaybackInput(1,new JSONObject(true));check(!early.pageCovered&&early.fullscreen);early.resumeMinimalPlaybackInput(1,new JSONObject(true));check(early.updates==1);
  CoverCheck late=new CoverCheck();late.pageCovered=false;late.interactivePageFallback=true;late.resumeMinimalPlaybackInput(1,new JSONObject(true));check(!late.interactivePageFallback&&late.fullscreen);
  CoverCheck paused=new CoverCheck();paused.resumeMinimalPlaybackInput(1,new JSONObject(false));check(paused.pageCovered);
  CoverCheck stale=new CoverCheck();stale.resumeMinimalPlaybackInput(2,new JSONObject(true));check(stale.pageCovered);
  CoverCheck hidden=new CoverCheck();hidden.visible=false;hidden.resumeMinimalPlaybackInput(1,new JSONObject(true));check(hidden.pageCovered);
  CoverCheck huya=new CoverCheck();huya.huya=true;huya.minimalBrowserMode=false;huya.resumeMinimalPlaybackInput(1,new JSONObject(true));check(!huya.pageCovered&&!huya.fullscreen);
  CoverCheck audio=new CoverCheck();audio.huya=true;JSONObject noVideo=new JSONObject(true);noVideo.width=0;noVideo.height=0;audio.resumeMinimalPlaybackInput(1,noVideo);check(audio.pageCovered);
  CoverCheck noFrame=new CoverCheck();noFrame.huya=true;JSONObject pendingFrame=new JSONObject(true);pendingFrame.frames=0;noFrame.resumeMinimalPlaybackInput(1,pendingFrame);check(noFrame.pageCovered);
  pendingFrame.frames=-1;noFrame.resumeMinimalPlaybackInput(1,pendingFrame);check(!noFrame.pageCovered);
  CoverCheck old=new CoverCheck();old.huya=true;pendingFrame.frames=0;Build.VERSION.SDK_INT=19;old.resumeMinimalPlaybackInput(1,pendingFrame);check(!old.pageCovered);Build.VERSION.SDK_INT=25;
  CoverCheck manual=new CoverCheck();manual.huya=true;manual.automaticPageCover=false;manual.resumeMinimalPlaybackInput(1,new JSONObject(true));check(manual.pageCovered);
  CoverCheck off=new CoverCheck();off.huya=true;off.autoPlay=false;off.minimalBrowserMode=false;off.resumeMinimalPlaybackInput(1,new JSONObject(true));check(off.pageCovered);
  System.out.println("PASS cover transition: Huya normal/minimal modes, decoded-frame/audio guards, old WebViews, manual cover, autoplay-off, stale/paused/hidden guards");
 }}'''.replace('METHOD',s[a:b])
with tempfile.TemporaryDirectory()as tmp:
 f=Path(tmp)/'CoverCheck.java';f.write_text(java,encoding='utf-8');jdk='C:/Users/Administrator/.jdks/jbr-17.0.14/bin/'
 subprocess.run([jdk+'javac.exe','-d',tmp,str(f)],check=True);subprocess.run([jdk+'java.exe','-cp',tmp,'CoverCheck'],check=True)
