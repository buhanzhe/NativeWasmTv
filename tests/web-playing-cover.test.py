from pathlib import Path
import subprocess,tempfile
root=Path(__file__).resolve().parents[1];s=(root/'app/src/main/java/xiao/bu/tv/WebSourceView.java').read_text(encoding='utf-8')
a=s.index('    private void resumeMinimalPlaybackInput(');b=s.index('    private void showInteractiveFallback',a)
java='''class CoverCheck {
 static class JSONObject {boolean playable;JSONObject(boolean v){playable=v;}}
 boolean destroyed,pageActive=true,minimalBrowserMode=true,interactivePageFallback,pageCovered=true,visible=true,fullscreen;int requestId=1,updates;JSONObject webMediaState;Object interactiveFallbackTask=new Object();
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
  System.out.println("PASS actual cover transition: early/late playback, no repeated updates, stale/paused/hidden guards");
 }}'''.replace('METHOD',s[a:b])
with tempfile.TemporaryDirectory()as tmp:
 f=Path(tmp)/'CoverCheck.java';f.write_text(java,encoding='utf-8');jdk='C:/Users/Administrator/.jdks/jbr-17.0.14/bin/'
 subprocess.run([jdk+'javac.exe','-d',tmp,str(f)],check=True);subprocess.run([jdk+'java.exe','-cp',tmp,'CoverCheck'],check=True)
