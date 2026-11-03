"""Execute the real completion callback: a sniffed live EOF cannot stay black."""
from pathlib import Path
import os, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
source=(root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf8')
start=source.index('new IMediaPlayer.OnCompletionListener()')
at=source.index('{',start)+1;depth=1
while depth:
 depth+=(source[at]=='{')-(source[at]=='}');at+=1
callback=source[start:at]
java=r'''class SniffEofCheck {
 static class IMediaPlayer {long duration,position;long getDuration(){return duration;}long getCurrentPosition(){return position;}interface OnCompletionListener{void onCompletion(IMediaPlayer p);}}
 static class Lite {boolean finishTemporary(boolean b){return false;}}
 static class Proxy {boolean finite=true,forbidden;boolean finiteMedia(){return finite;}boolean wasPlaybackForbidden(){return forbidden;}}
 static class Art {void setPlaying(boolean b){}}
 IMediaPlayer player=new IMediaPlayer();Lite liteWebPlayback;Proxy proxy=new Proxy();Art audioArtwork=new Art();
 java.util.concurrent.atomic.AtomicBoolean nativeForbidden=new java.util.concurrent.atomic.AtomicBoolean();
 int sourceRequestId=7,playRequestId=7,continuousHttpSourceRequestId=-1,fallbacks,recoveries,completed,denied;
 static class Channel {String name="Test";}
 String streamUrl="fixture.flv";boolean hls;boolean isResolvedHlsSource(String u){return hls;}
 boolean prepared=true,realtimeCastSource,retained=true;Object catchupProgram;Channel channel=new Channel();
 long seekableDuration(){return player.duration;}
 boolean isNormalPlaybackCompletion(long duration,long position){return position>=duration-1000;}
 void completeVideoPlayback(){completed++;}void hideLoading(){}void showChannelBar(Object c,String message){}
 void showPlaybackForbidden(Object c){denied++;}void recoverStalledPlayback(int id,IMediaPlayer p,String reason){recoveries++;}
 boolean returnFromFailedSniffedPlayback(String reason){if(retained){fallbacks++;return true;}return false;}
 IMediaPlayer.OnCompletionListener listener(){return CALLBACK;}
 static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
 public static void main(String[] args){
  SniffEofCheck c=new SniffEofCheck();c.player.position=818;c.listener().onCompletion(c.player);
  check(c.fallbacks==1&&c.recoveries==0,"sniffed live EOF returns to retained original player");
  c=new SniffEofCheck();c.player.duration=60000;c.player.position=60000;c.listener().onCompletion(c.player);check(c.completed==1&&c.fallbacks==0,"normal VOD end stays completed");
  c=new SniffEofCheck();c.continuousHttpSourceRequestId=7;c.listener().onCompletion(c.player);check(c.recoveries==1&&c.fallbacks==0,"existing live HTTP recovery keeps priority");
  c=new SniffEofCheck();c.hls=true;c.proxy.finite=false;c.listener().onCompletion(c.player);check(c.recoveries==1&&c.fallbacks==0,"live HLS EOF keeps native recovery priority");
  c=new SniffEofCheck();c.nativeForbidden.set(true);c.listener().onCompletion(c.player);check(c.denied==1&&c.fallbacks==0,"403 handling retains priority");
  c=new SniffEofCheck();c.catchupProgram=new Object();c.listener().onCompletion(c.player);check(c.fallbacks==0,"catchup handling unchanged");
  c=new SniffEofCheck();c.retained=false;c.listener().onCompletion(c.player);check(c.fallbacks==0&&c.recoveries==0,"ordinary sources unaffected");
  c=new SniffEofCheck();c.playRequestId++;c.listener().onCompletion(c.player);check(c.fallbacks==0,"stale completion cannot switch a newer channel");
  c=new SniffEofCheck();c.listener().onCompletion(new IMediaPlayer());check(c.fallbacks==0,"retired player completion ignored");
  System.out.println("PASS sniffed live EOF fallback, normal VOD/catchup, forbidden, existing recovery and stale player isolation");
 }
}'''.replace('CALLBACK',callback)
jdk=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
with tempfile.TemporaryDirectory() as folder:
 p=Path(folder)/'SniffEofCheck.java';p.write_text(java,encoding='utf8')
 subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',folder,str(p)],check=True)
 subprocess.run([str(jdk/'java.exe'),'-cp',folder,'SniffEofCheck'],check=True)
