"""Run the real menu capture lifecycle with delayed PixelCopy completions."""
from pathlib import Path
import os, subprocess, tempfile

root = Path(__file__).resolve().parents[1]
direct = (root/'app/src/main/java/xiao/bu/tv/DirectVideoView.java').read_text(encoding='utf8')
guard = direct[direct.index('    boolean canCaptureMenuBackdrop()'):direct.index('    boolean isFrameCovered()')]
files = {
'android/annotation/TargetApi.java':'package android.annotation;public @interface TargetApi{int value();}',
'android/os/Build.java':'package android.os;public class Build{public static class VERSION{public static int SDK_INT=25;}}',
'android/os/Looper.java':'''package android.os;import java.util.concurrent.*;public class Looper{static Looper main=new Looper();ConcurrentLinkedQueue<Runnable> q=new ConcurrentLinkedQueue<>();public static Looper getMainLooper(){return main;}public void drain(){Runnable r;while((r=q.poll())!=null)r.run();}}''',
'android/os/Handler.java':'package android.os;public class Handler{Looper l;public Handler(Looper l){this.l=l;}public boolean post(Runnable r){l.q.add(r);return true;}}',
'android/os/Process.java':'package android.os;public class Process{public static final int THREAD_PRIORITY_BACKGROUND=10;public static volatile int priority;public static void setThreadPriority(int p){priority=p;}}',
'android/view/View.java':'''package android.view;public class View{public boolean shown=true;public View root=this;public boolean isShown(){return shown;}public View getRootView(){return root;}public int getWidth(){return 1920;}public int getHeight(){return 1080;}public void getLocationOnScreen(int[] p){p[0]=0;p[1]=0;}public void invalidate(){}}''',
'android/view/ViewGroup.java':'package android.view;public class ViewGroup extends View{public View[] children=new View[0];public int getChildCount(){return children.length;}public View getChildAt(int i){return children[i];}}',
'android/view/SurfaceView.java':'package android.view;import android.graphics.Rect;public class SurfaceView extends View{public Rect frame=new Rect(1920,1080);public SurfaceView getHolder(){return this;}public Rect getSurfaceFrame(){return frame;}}',
'android/view/PixelCopy.java':'''package android.view;import android.os.*;import android.graphics.*;public class PixelCopy{public interface OnPixelCopyFinishedListener{void done(int r);}public static int requests;public static Bitmap sample;static Handler handler;static OnPixelCopyFinishedListener callback;public static void request(SurfaceView v,Bitmap b,OnPixelCopyFinishedListener l,Handler h){requests++;sample=b;callback=l;handler=h;}public static void finish(int r){handler.post(()->callback.done(r));}}''',
'android/graphics/Rect.java':'package android.graphics;public class Rect{int w,h;public Rect(int w,int h){this.w=w;this.h=h;}public int width(){return w;}public int height(){return h;}}',
'android/graphics/RectF.java':'package android.graphics;public class RectF{public float left,top,r,b;public void set(float l,float t,float r,float b){left=l;top=t;this.r=r;this.b=b;}public float width(){return r-left;}public float height(){return b-top;}}',
'android/graphics/Matrix.java':'package android.graphics;public class Matrix{public void setScale(float x,float y){}public void postTranslate(float x,float y){}}',
'android/graphics/Shader.java':'package android.graphics;public class Shader{public enum TileMode{CLAMP}}',
'android/graphics/BitmapShader.java':'package android.graphics;public class BitmapShader extends Shader{public BitmapShader(Bitmap b,TileMode x,TileMode y){}public void setLocalMatrix(Matrix m){}}',
'android/graphics/Paint.java':'package android.graphics;public class Paint{public static int ANTI_ALIAS_FLAG=1,FILTER_BITMAP_FLAG=2;public Paint(int f){}public void setShader(Shader s){}}',
'android/graphics/Canvas.java':'package android.graphics;public class Canvas{public void drawRoundRect(RectF r,float x,float y,Paint p){}}',
'android/graphics/Bitmap.java':'''package android.graphics;public class Bitmap{int w,h;public boolean recycled;public enum Config{ARGB_8888}public static Bitmap createBitmap(int w,int h,Config c){Bitmap b=new Bitmap();b.w=w;b.h=h;return b;}public int getWidth(){return w;}public int getHeight(){return h;}public void recycle(){recycled=true;}public void getPixels(int[] a,int o,int s,int x,int y,int w,int h){}public void setPixels(int[] a,int o,int s,int x,int y,int w,int h){}}''',
'xiao/bu/tv/DirectVideoView.java':'''package xiao.bu.tv;import android.view.*;public class DirectVideoView extends ViewGroup{int videoWidth=1920,videoHeight=1080;boolean ready=true,covered;SurfaceView surface=new SurfaceView();SurfaceView getSurfaceView(){return surface;}boolean isSurfaceReady(){return ready;}boolean isFrameCovered(){return covered;}GUARD}'''.replace('GUARD',guard),
'xiao/bu/tv/FrostedBackdrop.java':(root/'app/src/main/java/xiao/bu/tv/FrostedBackdrop.java').read_text(encoding='utf8'),
'xiao/bu/tv/Check.java':'''package xiao.bu.tv;import android.view.*;import android.graphics.*;import android.os.*;import java.lang.reflect.*;
public class Check{
 static void ok(boolean b,String m){if(!b)throw new AssertionError(m);}
 static Object field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 static void finish(int r)throws Exception{PixelCopy.finish(r);for(int i=0;i<1000;i++){Looper.getMainLooper().drain();Thread.sleep(1);if(!((Boolean)field(new FrostedBackdrop(new View()),"copying")))return;}throw new AssertionError("Copy never released");}
 public static void main(String[] args)throws Exception{
  View owner=new View();DirectVideoView video=new DirectVideoView();owner.root=video;FrostedBackdrop glass=new FrostedBackdrop(owner);
  video.videoWidth=3840;video.videoHeight=2160;
  for(int i=0;i<100;i++){glass.capture();glass.clear();}ok(PixelCopy.requests==0,"4K metadata with 1080p display must not capture");
  video.videoWidth=1920;video.videoHeight=1080;video.surface.frame=new Rect(3840,2160);glass.capture();ok(PixelCopy.requests==0,"4K real buffer must not capture");video.surface.frame=new Rect(1920,1080);
  video.videoWidth=0;glass.capture();video.videoWidth=1920;video.covered=true;glass.capture();video.covered=false;video.ready=false;glass.capture();video.ready=true;owner.shown=false;glass.capture();owner.shown=true;Build.VERSION.SDK_INT=23;glass.capture();Build.VERSION.SDK_INT=25;ok(PixelCopy.requests==0,"Unknown, covered, unready, hidden, legacy surface skipped");
  glass.capture();glass.capture();ok(PixelCopy.requests==1,"Visibility and layout must coalesce");Bitmap stale=PixelCopy.sample;glass.clear();glass.capture();ok(PixelCopy.requests==1,"Closing does not release an outstanding GPU copy");finish(0);ok(stale.recycled&&field(glass,"image")==null,"Stale frame not published");
  glass.capture();finish(0);Bitmap published=PixelCopy.sample;ok(field(glass,"image")==published&&!published.recycled,"HD frame published");glass.capture();ok(PixelCopy.requests==2,"One frame per opening");ok(android.os.Process.priority==10,"Blur runs at background priority");glass.clear();ok(!published.recycled,"Displayed bitmap survives display-list use");
  glass.capture();finish(1);glass.capture();ok(PixelCopy.requests==3&&PixelCopy.sample.recycled,"Copy error does not trigger driver retry loop");glass.clear();glass.capture();finish(0);ok(PixelCopy.requests==4,"New opening recovers after failure");
  System.out.println("PASS 4K metadata/buffer guard, 100 menu cycles, capture coalescing, cancellation, failure and HD backdrop");
 }
}'''
}
jdk = Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
with tempfile.TemporaryDirectory() as folder:
    paths=[]
    for name, source in files.items():
        p=Path(folder)/name
        p.parent.mkdir(parents=True,exist_ok=True)
        p.write_text(source,encoding='utf8')
        paths.append(str(p))
    subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',folder]+paths,check=True)
    subprocess.run([str(jdk/'java.exe'),'-cp',folder,'xiao.bu.tv.Check'],check=True)
