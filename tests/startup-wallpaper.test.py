"""Real drawable and async apply paths: landscape-only crop, one startup, late callback safety."""
from pathlib import Path
import os, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
source=(root/'app/src/main/java/xiao/bu/tv/PersonalBackground.java').read_text(encoding='utf8')
def extract(signature):
    start=source.index(signature);at=source.index('{',start)+1;depth=1
    while depth:
        depth+=(source[at]=='{')-(source[at]=='}');at+=1
    return source[start:at].replace("@Override ", "")
java=r'''import java.util.*;
class StartupCheck {
 static class Bitmap {int w,h;boolean recycled;Bitmap(int w,int h){this.w=w;this.h=h;}int getWidth(){return w;}int getHeight(){return h;}void recycle(){recycled=true;}}
 static class Rect {int left,top,right,bottom;Rect(){}Rect(int l,int t,int r,int b){set(l,t,r,b);}Rect(Rect r){this(r.left,r.top,r.right,r.bottom);}void set(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}int width(){return right-left;}int height(){return bottom-top;}}
 static class Paint {static int FILTER_BITMAP_FLAG=1;Paint(int i){}void setAlpha(int a){}void setColorFilter(ColorFilter f){}}
 static class ColorFilter{}static class PixelFormat {static final int TRANSLUCENT=-3;}
 static class Canvas {int draws;Rect crop,destination;void drawBitmap(Bitmap b,Rect s,Rect d,Paint p){draws++;crop=new Rect(s);destination=new Rect(d);}}
 static abstract class Drawable {Rect bounds=new Rect();void setBounds(int w,int h){bounds=new Rect(0,0,w,h);onBoundsChange(bounds);}Rect getBounds(){return bounds;}void invalidateSelf(){}protected void onBoundsChange(Rect b){}public abstract void draw(Canvas c);public abstract void setAlpha(int a);public abstract void setColorFilter(ColorFilter f);public abstract int getOpacity();}
 static class View {static final int GONE=8,VISIBLE=0;int visibility=GONE;Drawable background;void setVisibility(int v){visibility=v;}void setBackgroundDrawable(Drawable d){background=d;}Drawable getBackground(){return background;}}
 static class Clock {static long now=100;}
 static class android {static class os {static class SystemClock {static long uptimeMillis(){return Clock.now;}}}}
 static class Task {Runnable r;long due;Task(Runnable r,long due){this.r=r;this.due=due;}}
 static class Handler {List<Task> q=new ArrayList<>();void post(Runnable r){postDelayed(r,0);}void postDelayed(Runnable r,long n){q.add(new Task(r,Clock.now+n));}void removeCallbacks(Runnable r){q.removeIf(t->t.r==r);}void flush(){for(int i=0;i<1000;i++){Task ready=null;for(Task t:q)if(t.due<=Clock.now){ready=t;break;}if(ready==null)return;q.remove(ready);ready.r.run();}throw new AssertionError("busy loop");}void advance(long n){Clock.now+=n;flush();}}
 static class Worker {void shutdownNow(){}}
 boolean bingAutoUpdate=true;boolean closed,startupFinished,playbackReady,imageLoading,deferredBingUpdate,startupExpired;long firstDrawAt=-1;int generation=1;String mode="bing",status="",bingChoice="";int refreshCalls;void refresh(boolean cachedStartup){refreshCalls++;}View root=new View();Handler ui=new Handler();Worker worker=new Worker();
 long minimumDisplayMs=1000;static final long MAXIMUM_STARTUP_MS=30000;
 final Runnable expireStartup=()->{startupExpired=true;completeStartupIfReady();};
 final Runnable removeStartup=this::completeStartupIfReady;
 void draw(int w,int h){root.background.setBounds(w,h);root.background.draw(new Canvas());ui.flush();}
 METHODS
 WALLPAPER
 static class Layer {int startupDrawIndex=-1;LAYER}
 static void check(boolean b,String label){if(!b)throw new AssertionError(label);}
 public static void main(String[] args){
  Wallpaper image=new Wallpaper(new Bitmap(1280,720),null);Canvas canvas=new Canvas();
  image.setBounds(1080,1920);image.draw(canvas);check(canvas.draws==0,"Portrait launch must never display a cropped wallpaper frame");
  image.setBounds(1920,1080);image.draw(canvas);check(canvas.draws==1&&canvas.crop.width()==1280&&canvas.crop.height()==720,"16:9 image retains full width/height in 16:9 landscape");
  image.setBounds(2400,1080);image.draw(canvas);check(canvas.crop.left==0&&canvas.crop.top==72&&canvas.crop.width()==1280&&canvas.crop.height()==576,"20:9 center crop trims equal top/bottom without stretch");
  image.setBounds(1080,1920);int before=canvas.draws;image.draw(canvas);check(canvas.draws==before,"Transient portrait bounds never paint old landscape/portrait content");
  image.setBounds(0,0);image.draw(canvas);check(canvas.draws==before,"Unmeasured viewport must not draw");
  image=new Wallpaper(new Bitmap(600,1200),null);image.setBounds(1920,1080);image.draw(canvas);check(canvas.crop.width()==600&&canvas.crop.height()==338&&canvas.crop.top==431,"Portrait custom image is center-cropped, never stretched or rotated");
  StartupCheck a=new StartupCheck();a.imageLoading=true;Bitmap b=new Bitmap(1280,720);a.apply(1,b);a.ui.flush();
  a.finishStartup();a.draw(1080,1920);a.ui.advance(2000);check(a.playbackReady&&!a.startupFinished&&a.firstDrawAt<0,"Video proceeds independently; portrait/loading time does not count");
  a.draw(1920,1080);a.ui.advance(999);check(a.root.visibility==View.VISIBLE&&!a.startupFinished,"First 999 ms stays above playing video");a.finishStartup();a.ui.advance(1);check(a.root.visibility==View.GONE&&a.root.background==null&&a.startupFinished,"At 1000 ms remove; repeated frame callback does not reset deadline");check(!b.recycled,"Drawn bitmap remains safe for display list");
  Bitmap late=new Bitmap(1280,720);a.apply(1,late);a.ui.flush();check(late.recycled&&a.root.background==null,"No reappearance during later playback");
  a=new StartupCheck();a.apply(1,new Bitmap(1280,720));a.ui.flush();a.draw(1920,1080);a.ui.advance(2500);check(!a.startupFinished,"Do not hide until slow video is ready");a.finishStartup();check(a.startupFinished,"Slow video immediately removes wallpaper that already met minimum");
  a=new StartupCheck();a.imageLoading=true;late=new Bitmap(1280,720);a.apply(1,late);a.finishStartup();a.ui.flush();check(a.root.visibility==View.VISIBLE&&!late.recycled,"Fast playback does not skip pending configured wallpaper");a.draw(1920,1080);a.ui.advance(1000);check(a.startupFinished,"Late decoded image gets full second above playback");
  a=new StartupCheck();a.mode="off";a.finishStartup();check(a.startupFinished&&a.ui.q.isEmpty(),"Off never delays playback");
  a=new StartupCheck();a.imageLoading=true;a.finishStartup();a.imageLoading=false;a.completeStartupIfReady();check(a.startupFinished,"Failed image leaves no invisible startup wait");
  a=new StartupCheck();late=new Bitmap(1280,720);a.apply(0,late);a.ui.flush();check(late.recycled&&a.root.background==null,"Stale generation recycled");
  a=new StartupCheck();a.apply(1,new Bitmap(1280,720));a.ui.flush();a.draw(1920,1080);a.finishStartup();a.close();late=new Bitmap(1280,720);a.apply(1,late);a.ui.advance(2000);check(late.recycled&&a.ui.q.isEmpty(),"Close cancels hide timer and handles queued decode");
  a=new StartupCheck();a.deferredBingUpdate=true;a.apply(1,new Bitmap(1280,720));a.ui.flush();a.draw(1920,1080);a.finishStartup();a.ui.advance(999);check(a.refreshCalls==0,"Cached wallpaper does not start network while video startup is covered");a.ui.advance(1);check(a.startupFinished&&a.refreshCalls==0,"Hide cached wallpaper before daily update");a.ui.advance(1499);check(a.refreshCalls==0,"Allow playback to settle before deferred update");a.ui.advance(1);check(a.refreshCalls==1,"Refresh stale cache once after startup has completed");
  a=new StartupCheck();a.deferredBingUpdate=true;a.finishStartup();a.generation++;a.ui.advance(1500);check(a.refreshCalls==0,"Changing wallpaper invalidates deferred update");
  a=new StartupCheck();a.deferredBingUpdate=true;a.finishStartup();a.close();a.ui.advance(1500);check(a.refreshCalls==0,"Closed activity never refreshes stale wallpaper");
  a=new StartupCheck();a.apply(1,new Bitmap(1280,720));a.ui.flush();a.draw(1920,1080);a.ui.postDelayed(a.expireStartup,MAXIMUM_STARTUP_MS);
  a.ui.advance(MAXIMUM_STARTUP_MS-1);check(!a.startupFinished,"No premature timeout before deadline");a.ui.advance(1);check(a.startupFinished&&!a.playbackReady&&a.root.visibility==View.GONE,"Missing first frame cannot leave wallpaper over controls forever");
  late=new Bitmap(1280,720);a.apply(1,late);a.ui.flush();check(late.recycled&&a.root.background==null,"Late download cannot resurrect expired wallpaper");
  a=new StartupCheck();a.imageLoading=true;a.finishStartup();a.ui.postDelayed(a.expireStartup,MAXIMUM_STARTUP_MS);a.ui.advance(MAXIMUM_STARTUP_MS);check(a.startupFinished,"Hung image load cannot cover ready playback forever");
  a=new StartupCheck();a.apply(1,new Bitmap(1280,720));a.ui.flush();a.draw(1080,1920);a.finishStartup();a.ui.postDelayed(a.expireStartup,MAXIMUM_STARTUP_MS);a.ui.advance(MAXIMUM_STARTUP_MS);check(a.startupFinished,"Missing landscape draw is bounded");
  a=new StartupCheck();a.ui.postDelayed(a.expireStartup,MAXIMUM_STARTUP_MS);a.close();a.ui.advance(MAXIMUM_STARTUP_MS);check(!a.startupExpired&&a.ui.q.isEmpty(),"Close cancels startup deadline");
  for(int seconds:new int[]{1,3,5,17,120}){
   a=new StartupCheck();a.minimumDisplayMs=seconds*1000L;a.apply(1,new Bitmap(1280,720));a.ui.flush();a.draw(1920,1080);a.finishStartup();
   a.ui.advance(seconds*1000L-1);check(!a.startupFinished,"Configured display duration retained");a.ui.advance(1);check(a.startupFinished,"Configured duration expires after first frame");
   a=new StartupCheck();a.minimumDisplayMs=seconds*1000L;a.apply(1,new Bitmap(1280,720));a.ui.flush();a.draw(1920,1080);a.ui.advance(seconds*1000L+500);check(!a.startupFinished,"Slow decoder waits beyond configured duration");a.finishStartup();check(a.startupFinished,"Late first frame removes wallpaper after duration met");
  }
  Layer layer=new Layer();for(int n=1;n<40;n++)for(int index=-1;index<n;index++){
   layer.startupDrawIndex=index;Set<Integer> seen=new HashSet<>();int previous=-1;
   for(int pos=0;pos<n;pos++){int child=layer.getChildDrawingOrder(n,pos);check(child>=0&&child<n&&seen.add(child),"Layer order permutation");if(child!=index){check(child>previous,"Other layers preserve order");previous=child;}}
   check(index<0||layer.getChildDrawingOrder(n,n-1)==index,"Wallpaper remains above promoted player layers");
  }
  System.out.println("PASS actual-draw minimum 1 s, playback independent, portrait crop, slow/fast/late video, off/failure/close, layer ordering");
 }
}'''
java=java.replace('METHODS','\n'.join(extract(s) for s in ('    private void apply(', '    private String appliedStatus(', '    void finishStartup(', '    private void completeStartupIfReady(', '    @Override public synchronized void close(')))
java=java.replace('WALLPAPER',extract('    private static final class Wallpaper'))
layer_source=(root/'app/src/main/java/xiao/bu/tv/PlayerRootLayout.java').read_text(encoding='utf8')
at=layer_source.index('    @Override protected int getChildDrawingOrder(');end=layer_source.index('\n    }',at)+6
java=java.replace('LAYER',layer_source[at:end].replace('@Override ',''))
jdk=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
with tempfile.TemporaryDirectory() as folder:
    path=Path(folder)/'StartupCheck.java';path.write_text(java,encoding='utf8')
    subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8',str(path)],check=True)
    subprocess.run([str(jdk/'java.exe'),'-cp',folder,'StartupCheck'],check=True)
