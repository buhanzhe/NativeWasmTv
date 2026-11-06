"""API 14/15 must not load IJK just to annotate platform-player streams."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parent.parent
stubs = {
    'android/os/Build.java': 'package android.os; public class Build { public static class VERSION {public static int SDK_INT;} }',
    'android/os/SystemClock.java': 'package android.os; public class SystemClock {public static long elapsedRealtime(){return System.nanoTime()/1000000;}}',
    'android/util/Log.java': 'package android.util; public class Log {public static int i(String t,String s){return 0;} public static int w(String t,String s,Throwable e){return 0;}}',
    'xiao/bu/tv/AppLog.java': 'package xiao.bu.tv; public class AppLog {public static void loadIjkLibraries(){} public static void i(String t,String s){} public static void w(String t,String s,Throwable e){}}',
    'xiao/bu/tv/VideoStreamDescription.java': '''package xiao.bu.tv;
class VideoStreamDescription {
 static final VideoStreamDescription UNKNOWN=new VideoStreamDescription();
 String scanType="unknown";boolean complete(){return false;} String debugLabel(){return "test";}
 VideoStreamDescription withFallback(VideoStreamDescription d){return this;}
}''',
    'xiao/bu/tv/IjkStreamMetrics.java': '''package xiao.bu.tv;
class IjkStreamMetrics {static VideoStreamDescription videoDescription(tv.danmaku.ijk.media.player.IjkMediaPlayer p){return VideoStreamDescription.UNKNOWN;}}''',
    'tv/danmaku/ijk/media/player/IjkMediaPlayer.java': '''package tv.danmaku.ijk.media.player;
public class IjkMediaPlayer {
 public static final int OPT_CATEGORY_PLAYER=1,OPT_CATEGORY_CODEC=2,OPT_CATEGORY_FORMAT=3;
 public static volatile int loads;
 public IjkMediaPlayer(){loads++;}
 public interface Prepared {void run(IjkMediaPlayer p);} public interface Error {boolean run(IjkMediaPlayer p,int w,int e);}
 private Prepared ready;
 public void setOnPreparedListener(Prepared p){ready=p;} public void setOnErrorListener(Error e){}
 public void setOption(int c,String n,long v){} public void setVolume(float l,float r){}
 public void setDataSource(String s){} public void prepareAsync(){ready.run(this);} public void release(){}
}''',
    'xiao/bu/tv/Check.java': '''package xiao.bu.tv;
import java.io.File;import java.lang.reflect.Field;import java.util.concurrent.Future;
public class Check {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] args)throws Exception {
  File dir=new File(args[0]);dir.mkdirs();Field task=CachedVideoMetadataProbe.class.getDeclaredField("task");task.setAccessible(true);
  for(int sdk:new int[]{14,15,16,19,25}) {
   android.os.Build.VERSION.SDK_INT=sdk;
   CachedVideoMetadataProbe probe=new CachedVideoMetadataProbe(dir);
   int before=tv.danmaku.ijk.media.player.IjkMediaPlayer.loads;
   probe.submit(new byte[10000]);Future<?> pending=(Future<?>)task.get(probe);
   if(sdk<=15){check(pending==null,"Legacy probe queued native work");check(dir.list().length==0,"Legacy probe wrote sample");}
   else {check(pending!=null,"Supported probe disabled");pending.get(5,java.util.concurrent.TimeUnit.SECONDS);}
   check(tv.danmaku.ijk.media.player.IjkMediaPlayer.loads-before==(sdk<=15?0:1),"Native load policy changed");
   check(dir.list().length==0,"Sample leaked");probe.close();
  }
  CachedVideoMetadataProbe closed=new CachedVideoMetadataProbe(dir);closed.close();closed.submit(new byte[10000]);
  check(task.get(closed)==null,"Closed probe queued work");
  System.out.println("PASS API14/15 zero native loads, zero samples; API16/19/25 metadata path retained; close guard");
 }
}'''
}
with tempfile.TemporaryDirectory() as folder:
    out = Path(folder)
    stubs['xiao/bu/tv/CachedVideoMetadataProbe.java'] = (root/'app/src/main/java/xiao/bu/tv/CachedVideoMetadataProbe.java').read_text(encoding='utf-8')
    for name, source in stubs.items():
        target = out/name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source, encoding='utf-8')
    jdk = Path(os.environ['JAVA_HOME'])/'bin'
    subprocess.run([str(jdk/'javac'), '-encoding', 'UTF-8', '-d', str(out)] + [str(out/name) for name in stubs], check=True)
    subprocess.run([str(jdk/'java'), '-cp', str(out), 'xiao.bu.tv.Check', str(out/'samples')], check=True, timeout=20)
