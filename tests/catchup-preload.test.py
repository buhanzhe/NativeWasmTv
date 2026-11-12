"""Exercise the real preload lifecycle with deterministic decoder/network seams."""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
sources = {
    'xiao/bu/tv/AppLog.java': '''package xiao.bu.tv;
final class AppLog {static void loadIjkLibraries(){tv.danmaku.ijk.media.player.IjkMediaPlayer.loadLibrariesOnce(null);}}''',
    'android/os/Looper.java': '''package android.os;
public class Looper { public static Looper getMainLooper(){return new Looper();} }''',
    'android/os/Handler.java': '''package android.os;
import java.util.concurrent.*;
public class Handler {
 public static final BlockingQueue<Runnable> posts=new LinkedBlockingQueue<>();
 public static Runnable timeout;
 public Handler(Looper looper){}
 public void post(Runnable run){posts.add(run);}
 public void postDelayed(Runnable run,long delay){timeout=run;}
 public void removeCallbacks(Runnable run){if(timeout==run)timeout=null;}
}''',
    'android/graphics/SurfaceTexture.java': '''package android.graphics;
public class SurfaceTexture { public SurfaceTexture(int id){} public void release(){} }''',
    'android/view/Surface.java': '''package android.view;
public class Surface { public Surface(android.graphics.SurfaceTexture texture){} public void release(){} }''',
    'tv/danmaku/ijk/media/player/IjkMediaPlayer.java': '''package tv.danmaku.ijk.media.player;
public class IjkMediaPlayer {
 public static final int OPT_CATEGORY_PLAYER=1,OPT_CATEGORY_CODEC=2,OPT_CATEGORY_FORMAT=3;
 public static volatile IjkMediaPlayer latest;
 public volatile boolean released,started;
 public float volume=1;
 public interface Prepared {void call(IjkMediaPlayer p);}
 public interface Info {boolean call(IjkMediaPlayer p,int what,int extra);}
 public interface Error {boolean call(IjkMediaPlayer p,int what,int extra);}
 public interface Completion {void call(IjkMediaPlayer p);}
 public Prepared prepared;public Info info;public Error error;public Completion completion;
 public IjkMediaPlayer(){latest=this;}
 public static void loadLibrariesOnce(Object loader){}
 public void setOption(int category,String name,long value){}
 public void setVolume(float left,float right){volume=left;}
 public void setSurface(android.view.Surface surface){if(surface==null)throw new IllegalStateException("Detached output");}
 public void setOnPreparedListener(Prepared listener){prepared=listener;}
 public void setOnInfoListener(Info listener){info=listener;}
 public void setOnErrorListener(Error listener){error=listener;}
 public void setOnCompletionListener(Completion listener){completion=listener;}
 public void setDataSource(String url){}
 public void prepareAsync(){}
 public int getVideoWidth(){return 160;}
 public void start(){started=true;}
 public void release(){released=true;}
}''',
    'xiao/bu/tv/HlsProxyServer.java': '''package xiao.bu.tv;
import java.io.*;import java.util.concurrent.*;
final class HlsProxyServer {
 interface CatchupListener {void onTimeline(int state,long actual,String error);}
 volatile boolean started,closed,fail;CatchupListener timeline;
 CountDownLatch bound,continueStart;
 void setCatchupWindow(long start,long stop,boolean strict,CatchupListener listener){timeline=listener;}
 void start()throws IOException{
  if(bound!=null){bound.countDown();try{continueStart.await();}catch(InterruptedException e){throw new IOException(e);}}
  started=true;
 }
 void prepareManifest(String url)throws IOException{if(fail)throw new IOException("HTTP 404");}
 String mediaUrl(String url){return url;}
 String playerHlsUrl(String url){return url;}
 void close(){closed=true;started=false;}
}''',
    'xiao/bu/tv/CatchupPreloadCheck.java': '''package xiao.bu.tv;
import android.os.Handler;import java.util.concurrent.*;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;
public class CatchupPreloadCheck {
 static int checks;
 static void check(boolean value,String text){checks++;if(!value)throw new AssertionError(text);}
 static class Result implements CatchupPreloader.Listener {
  int ready,failed;String reason;CatchupPreloader load;
  public void ready(CatchupPreloader load){ready++;this.load=load;}
  public void failed(String reason){failed++;this.reason=reason;}
 }
 static CatchupPreloader open(HlsProxyServer proxy,Result result,boolean strict,boolean direct){
  CatchupPreloader load=new CatchupPreloader(()->proxy,"archive",0,1000,strict,direct,result);load.start();return load;
 }
 static void dispatch()throws Exception{
  Runnable next=Handler.posts.poll(2,TimeUnit.SECONDS);check(next!=null,"Missing UI callback");next.run();
 }
 static void released(HlsProxyServer proxy,IjkMediaPlayer player)throws Exception{
  long end=System.currentTimeMillis()+2000;
  while((!proxy.closed||player!=null&&!player.released)&&System.currentTimeMillis()<end)Thread.sleep(5);
  check(proxy.closed&&(player==null||player.released),"Resources leaked");
 }
 public static void main(String[] args)throws Exception {
  HlsProxyServer p=new HlsProxyServer();p.fail=true;Result r=new Result();open(p,r,false,false);dispatch();released(p,null);
  check(r.failed==1&&r.ready==0&&r.reason.contains("404"),"HTTP failure accepted");
  p=new HlsProxyServer();r=new Result();CatchupPreloader load=open(p,r,false,false);dispatch();
  IjkMediaPlayer decoder=IjkMediaPlayer.latest;decoder.prepared.call(decoder);
  check(decoder.started&&decoder.volume==0&&r.ready==0,"Prepared state incorrectly switched playback or was audible");
  decoder.info.call(decoder,10002,0);check(r.ready==0,"Video stream switched on audio readiness before its first frame");
  decoder.error.call(decoder,-1,0);released(p,decoder);
  check(r.failed==1&&r.ready==0,"Decoder failure accepted");
  decoder.error.call(decoder,-1,0);check(r.failed==1,"Duplicate failure callback");
  p=new HlsProxyServer();r=new Result();load=open(p,r,true,false);dispatch();decoder=IjkMediaPlayer.latest;
  p.timeline.onTimeline(2,999,"Wrong programme time");dispatch();released(p,decoder);
  check(r.failed==1&&r.ready==0,"Mismatched time accepted");
  p=new HlsProxyServer();r=new Result();load=open(p,r,false,false);dispatch();decoder=IjkMediaPlayer.latest;
  Handler.timeout.run();released(p,decoder);check(r.failed==1&&r.ready==0,"Timeout accepted");
  p=new HlsProxyServer();r=new Result();load=open(p,r,false,false);load.close();released(p,null);
  Runnable queued=Handler.posts.poll(100,TimeUnit.MILLISECONDS);if(queued!=null)queued.run();
  check(r.ready==0&&r.failed==0,"Cancelled request delivered a callback");
  p=new HlsProxyServer();p.bound=new CountDownLatch(1);p.continueStart=new CountDownLatch(1);
  r=new Result();load=open(p,r,false,false);check(p.bound.await(2,TimeUnit.SECONDS),"Proxy did not bind");
  final CatchupPreloader racing=load;Thread cancel=new Thread(racing::close);cancel.start();
  p.continueStart.countDown();cancel.join(2000);released(p,null);
  queued=Handler.posts.poll(100,TimeUnit.MILLISECONDS);if(queued!=null)queued.run();
  check(r.ready==0&&r.failed==0&&!p.started,"Cancellation reopened a closed proxy");
  p=new HlsProxyServer();r=new Result();load=open(p,r,false,false);dispatch();decoder=IjkMediaPlayer.latest;
  decoder.completion.call(decoder);released(p,decoder);
  check(r.failed==1&&r.ready==0,"Empty media accepted");
  p=new HlsProxyServer();r=new Result();load=open(p,r,false,false);dispatch();decoder=IjkMediaPlayer.latest;
  decoder.info.call(decoder,3,0);check(r.ready==1&&r.failed==0,"First frame did not become ready");
  HlsProxyServer retained=load.takeProxy();Thread.sleep(50);
  check(retained==p&&!p.closed&&decoder.released&&Handler.timeout==null,"Warm proxy handoff lost resources");
  decoder.error.call(decoder,-1,0);check(r.failed==0,"Retired probe failed after handoff");p.close();
  p=new HlsProxyServer();r=new Result();open(p,r,true,true);
  check(r.failed==1&&r.ready==0&&!p.started,"Unverifiable inferred direct media accepted");
  System.out.println("PASS "+checks+" preload lifecycle checks: first frame, HTTP/decode/time failure, timeout, cancellation, muted probe, warm handoff and late events");
 }
}''',
}
with tempfile.TemporaryDirectory() as folder:
    out = Path(folder)
    production = 'xiao/bu/tv/CatchupPreloader.java'
    sources[production] = (root / 'app/src/main/java' / production).read_text(encoding='utf8')
    for name, content in sources.items():
        target = out / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding='utf8')
    jdk = Path(os.environ.get('JAVA_HOME', root / '.codex-tmp/jdk8/runtime/jdk8u504-b01')) / 'bin'
    subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '-d', folder,
                    *[str(out / name) for name in sources]], check=True)
    subprocess.run([str(jdk / 'java.exe'), '-cp', folder, 'xiao.bu.tv.CatchupPreloadCheck'], check=True)
