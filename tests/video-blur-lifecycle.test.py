"""Real PixelCopy lifecycle: cancellation must outlive the worker looper."""
from pathlib import Path
import os,subprocess,tempfile
root=Path(__file__).resolve().parents[1]
files={
'android/annotation/TargetApi.java':'package android.annotation;public @interface TargetApi{int value();}',
'android/os/Looper.java':'''package android.os;import java.util.*;public class Looper{public boolean stopped;public List<Runnable> queue=new ArrayList<>();static Looper main=new Looper();public static Looper getMainLooper(){return main;}public void drain(){while(!queue.isEmpty())queue.remove(0).run();}}''',
'android/os/Handler.java':'''package android.os;public class Handler{final Looper loop;public Handler(Looper l){loop=l;}public boolean post(Runnable r){if(loop.stopped)return false;loop.queue.add(r);return true;}}''',
'android/os/HandlerThread.java':'package android.os;public class HandlerThread{public final Looper loop=new Looper();public HandlerThread(String n,int p){}public void start(){}public Looper getLooper(){return loop;}public void quitSafely(){loop.stopped=true;}}',
'android/os/Build.java':'package android.os;public class Build{public static class VERSION{public static int SDK_INT=37;}}',
'android/os/Process.java':'package android.os;public class Process{public static int THREAD_PRIORITY_BACKGROUND=10;}',
'android/os/SystemClock.java':'package android.os;public class SystemClock{public static long uptimeMillis(){return 1000;}}',
'android/graphics/Bitmap.java':'''package android.graphics;public class Bitmap{int w,h;public boolean recycled;int[] pixels;public enum Config{ARGB_8888}public static Bitmap createBitmap(int w,int h,Config c){Bitmap b=new Bitmap();b.w=w;b.h=h;b.pixels=new int[w*h];java.util.Arrays.fill(b.pixels,0xffff0000);return b;}public int getWidth(){return w;}public int getHeight(){return h;}public void recycle(){recycled=true;}public void getPixels(int[] a,int o,int s,int x,int y,int w,int h){System.arraycopy(pixels,0,a,0,pixels.length);}public void setPixels(int[] a,int o,int s,int x,int y,int w,int h){System.arraycopy(a,0,pixels,0,pixels.length);}}''',
'android/graphics/Paint.java':'package android.graphics;public class Paint{public static int FILTER_BITMAP_FLAG=1;public Paint(int f){}}',
'android/graphics/Rect.java':'package android.graphics;public class Rect{public void set(int a,int b,int c,int d){}}',
'android/graphics/Region.java':'package android.graphics;public class Region{public enum Op{DIFFERENCE}}',
'android/graphics/Canvas.java':'package android.graphics;public class Canvas{public int save(){return 0;}public void clipRect(int a,int b,int c,int d,Region.Op o){}public void drawBitmap(Bitmap b,Object s,Rect r,Paint p){}public void drawColor(int c){}public void restoreToCount(int s){}}',
'android/view/Display.java':'package android.view;public class Display{public float getRefreshRate(){return 60;}}',
'android/view/View.java':'package android.view;public class View{public int left=10,width=80;public int getWidth(){return width;}public int getHeight(){return 60;}public int getLeft(){return left;}public int getRight(){return left+width;}public int getTop(){return 0;}public int getBottom(){return 60;}}',
'android/view/SurfaceView.java':'package android.view;public class SurfaceView extends View{public int left=10,width=80;public int getWidth(){return width;}public int getHeight(){return 60;}public int getLeft(){return left;}public int getRight(){return left+width;}public int getTop(){return 0;}public int getBottom(){return 60;}}',
'android/view/PixelCopy.java':'''package android.view;import android.graphics.Bitmap;import android.os.Handler;public class PixelCopy{public interface OnPixelCopyFinishedListener{void onPixelCopyFinished(int result);}public static Bitmap sample;public static int requests;static Handler handler;static OnPixelCopyFinishedListener callback;public static void request(SurfaceView v,Bitmap b,OnPixelCopyFinishedListener l,Handler h){requests++;sample=b;callback=l;handler=h;}public static void finish(int result){handler.post(()->callback.onPixelCopyFinished(result));}}''',
'xiao/bu/tv/DirectVideoView.java':'''package xiao.bu.tv;import android.view.*;public class DirectVideoView{SurfaceView surface=new SurfaceView();Runnable tick;long delay;SurfaceView getSurfaceView(){return surface;}View getRenderView(){return surface;}boolean isTextureOutput(){return false;}interface FrameCopyResult{void done(int r);}void copyVideoFrame(android.graphics.Bitmap b,android.os.Handler h,FrameCopyResult r){PixelCopy.request(surface,b,r::done,h);}public int getWidth(){return 100;}public int getHeight(){return 60;}public boolean isShown(){return true;}public boolean isFrameCovered(){return false;}public boolean isSurfaceReady(){return true;}public Display getDisplay(){return new Display();}public void invalidate(){}public void removeCallbacks(Runnable r){tick=null;}public void postDelayed(Runnable r,long ms){tick=r;delay=ms;}void step(){Runnable r=tick;tick=null;r.run();}}''',
'xiao/bu/tv/VideoBlurBackdrop.java':(root/'app/src/main/java/xiao/bu/tv/VideoBlurBackdrop.java').read_text(encoding='utf8'),
'xiao/bu/tv/Check.java':r'''package xiao.bu.tv;import android.graphics.*;import android.os.*;import android.view.*;import java.lang.reflect.*;
public class Check{
 static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 static void ok(boolean b,String message){if(!b)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception{
  DirectVideoView view=new DirectVideoView();VideoBlurBackdrop blur=new VideoBlurBackdrop(view);blur.enable(true);blur.setFrameRateSource(()->25f);
  blur.draw(new Canvas());view.step();Bitmap stale=PixelCopy.sample;HandlerThread old=(HandlerThread)field(blur,"worker");
  blur.clear();ok(old.loop.stopped,"Worker stops on clear");PixelCopy.finish(0);Looper.getMainLooper().drain();
  ok(stale.recycled&&field(blur,"image")==null,"Copy finishing after clear must release its sample");
  blur.draw(new Canvas());view.step();PixelCopy.finish(0);Looper.getMainLooper().drain();HandlerThread worker=(HandlerThread)field(blur,"worker");worker.loop.drain();Looper.getMainLooper().drain();
  ok(field(blur,"image")!=null&&!PixelCopy.sample.recycled,"New generation publishes normally");ok(view.delay==40,"25 fps schedules 40ms");
  view.step();ok(worker==field(blur,"worker"),"Reuse one worker across frames");Bitmap failed=PixelCopy.sample;PixelCopy.finish(1);Looper.getMainLooper().drain();ok(failed.recycled&&view.delay>=200,"Failure recycles and backs off");
  Bitmap published=(Bitmap)field(blur,"image");blur.clear();ok(!published.recycled,"Displayed bitmap remains valid for hardware display list");blur.setFrameRateSource(()->-1f);int requests=PixelCopy.requests;blur.draw(new Canvas());view.step();ok(PixelCopy.requests==requests&&view.delay==250,"Pause polls without copy");
  view.surface.left=0;view.surface.width=100;blur.draw(new Canvas());ok(view.tick==null,"No bars stops scheduled sampling");
  System.out.println("PASS late PixelCopy cancellation, fresh generation, worker reuse, fps, error backoff, pause and no bars");
 }
}'''
}
jdk=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
with tempfile.TemporaryDirectory() as folder:
 paths=[]
 for name,source in files.items():
  p=Path(folder)/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source,encoding='utf8');paths.append(str(p))
 subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',folder]+paths,check=True)
 subprocess.run([str(jdk/'java.exe'),'-cp',folder,'xiao.bu.tv.Check'],check=True)
