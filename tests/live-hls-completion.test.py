"""Execute the IJK completion callback: live EOF recovers; finite media stays ended."""
from pathlib import Path
import os, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
s=(root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf8')
a=s.index('public void onCompletion(final IMediaPlayer endedPlayer)');b=s.index('{',a)+1;depth=1
while depth:
    depth+=(s[b]=='{')-(s[b]=='}');b+=1
java=r'''
import java.util.concurrent.atomic.AtomicBoolean;
class Check {
 static class IMediaPlayer {long getDuration(){return 100;}long getCurrentPosition(){return 100;}}
 static class Lite {boolean finishTemporary(boolean b){return false;}}
 static class Proxy {boolean finite,forbidden;boolean finiteMedia(){return finite;}boolean wasPlaybackForbidden(){return forbidden;}}
 static class Art {void setPlaying(boolean b){}}
 static class Channel {String name="live";}
 IMediaPlayer player=new IMediaPlayer();Lite liteWebPlayback;Proxy proxy=new Proxy();Art audioArtwork;Channel channel=new Channel();
 int sourceRequestId=1,playRequestId=1,continuousHttpSourceRequestId=-1,recovered,completed,denied;boolean prepared=true,realtimeCastSource;Object catchupProgram;
 AtomicBoolean nativeForbidden=new AtomicBoolean();String streamUrl="https://test/live.m3u8";long duration;
 long seekableDuration(){return duration;}boolean isNormalPlaybackCompletion(long a,long b){return true;}
 void completeVideoPlayback(){completed++;}void hideLoading(){}void showChannelBar(String a,String b){}
 void showPlaybackForbidden(Channel c){denied++;}boolean returnFromFailedSniffedPlayback(String s){return false;}
 boolean isResolvedHlsSource(String s){return s.endsWith(".m3u8");}
 void recoverStalledPlayback(int id,IMediaPlayer p,String reason){recovered++;}
 CALLBACK
 static void ok(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  Check c=new Check();c.onCompletion(c.player);ok(c.recovered==1);
  c=new Check();c.proxy.finite=true;c.duration=100;c.onCompletion(c.player);ok(c.completed==1&&c.recovered==0);
  c=new Check();c.proxy.forbidden=true;c.onCompletion(c.player);ok(c.denied==1&&c.recovered==0);
  c=new Check();c.playRequestId=2;c.onCompletion(c.player);ok(c.recovered==0);
  c=new Check();c.onCompletion(new IMediaPlayer());ok(c.recovered==0);
  c=new Check();c.catchupProgram=new Object();c.onCompletion(c.player);ok(c.recovered==0);
  System.out.println("PASS live HLS EOF reconnect, finite completion, forbidden, stale request/player and catchup exclusions");
 }
}
'''.replace('CALLBACK',s[a:b])
jdk=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
with tempfile.TemporaryDirectory() as folder:
    p=Path(folder)/'Check.java';p.write_text(java,encoding='utf8')
    subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8',str(p)],check=True)
    subprocess.run([str(jdk/'java.exe'),'-cp',folder,'Check'],check=True)
