"""Exercise the real VOD reconnect/prepare code without installing a test APK."""
import os, subprocess
from pathlib import Path
root=Path(__file__).resolve().parent.parent
source=(root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf-8')
a=source.index('    private void restartPlayerPreservingPosition(')
b=source.index('    /** A signed live URL',a)
method=source[a:b]
completion=source[source.index('    private static boolean isNormalPlaybackCompletion('):a]
mode_start=source.index('    private int systemPlayerMode(')
mode=source[mode_start:source.index('\n    private ',mode_start+8)]
java='''import java.io.IOException;
public class VodReconnectCheck {
 static final String TAG="test";
 static class Log {static void i(String t,String s){}static void w(String t,String s,RuntimeException e){}}
 static class Build {static class VERSION {static final int SDK_INT=19;}}
 static class Channel {boolean radio;}
 static class ITrackInfo {static final int MEDIA_TRACK_TYPE_AUDIO=2,MEDIA_TRACK_TYPE_VIDEO=1,MEDIA_TRACK_TYPE_TIMEDTEXT=3;}
 interface IMediaPlayer {long getDuration();long getCurrentPosition();}
 static class IjkMediaPlayer implements IMediaPlayer {
  long duration,position,seek=-1; boolean paused;
  IjkMediaPlayer(long d,long p){duration=d;position=p;}
  public long getDuration(){return duration;} public long getCurrentPosition(){return position;}
  int getSelectedTrack(int type){return type+10;}
  void seekTo(long p){seek=p;} void pause(){paused=true;}
 }
 IjkMediaPlayer player;
 String directHttpMediaUrl; boolean fail, deferred;
 int[] restartedTracks;
 int androidMp3FallbackRequestId=-1,playRequestId=1;
 boolean systemHls,systemMp3,systemDv,systemAtmos;int systemStarts;
 boolean useSystemDolbyVisionPlayer(){return systemDv;}
 boolean useSystemAtmosPlayer(String url){return systemAtmos;}
 String playerEngine="ijk",decodeMode="auto";
 static final String PLAYER_ENGINE_SYSTEM="system",DECODE_MODE_SOFTWARE="software";
 boolean isNtVCastSource(String url){return false;}
 boolean useAndroidMp4Player(int sdk,String url){return false;}
 boolean useSystemCodecFallback(Channel c,String url,boolean software){return false;}
 boolean useAndroidHlsPlayer(int sdk,String url){return systemHls;}
 boolean useAndroidMp3Player(int sdk,String url){return systemMp3;}
 void startAndroidMediaPlayer(Channel c,String url,boolean software,boolean hls,long resume)throws IOException {
  systemStarts++;player=new IjkMediaPlayer(600000,0);player.seek=resume;
 }
 void startIjkPlayer(Channel c,String u,boolean s,boolean direct,int[] tracks,long initialPositionMs)throws IOException {
  restartedTracks=tracks;
  if(fail)throw new IOException("network");
  if(deferred)return;
  player=new IjkMediaPlayer(600000,0);player.seek=initialPositionMs;
 }
 METHOD

 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void main(String[] args)throws Exception {
  check(isNormalPlaybackCompletion(75560,75560),"MP4 normal EOF must stop without reconnect");
  check(isNormalPlaybackCompletion(75560,75300),"Allow rounded final frame clock");
  check(!isNormalPlaybackCompletion(75560,23000),"Truncated MP4 must still recover");
  check(!isNormalPlaybackCompletion(0,23000),"Live EOF must still recover");
  VodReconnectCheck c=new VodReconnectCheck();
  c.player=new IjkMediaPlayer(600000,67321);IjkMediaPlayer old=c.player;
  c.restartPlayerPreservingPosition(new Channel(),"url",false);
  check(java.util.Arrays.equals(c.restartedTracks,new int[]{12,11,13}),"Keep selected audio/video/subtitle on reconnect");
  check(c.player!=old,"Bind to replacement player");
  check(old.seek==-1,"Old player must not receive the new initial seek");
  check(c.player.seek==67321,"VOD must resume beyond minute one");
  c.startIjkPlayer(new Channel(),"next-channel",false,false,null,0);
  check(c.player.seek==0,"A later channel must not inherit recovery position");
  c.player=new IjkMediaPlayer(0,70000);
  c.restartPlayerPreservingPosition(new Channel(),"live",false);
  check(c.player.seek==0,"Live streams must not seek");
  c.player=new IjkMediaPlayer(75560,90000);
  c.restartPlayerPreservingPosition(new Channel(),"url",false);
  check(c.player.seek==74560,"Clamp stale clock before end of film");
  c.player=new IjkMediaPlayer(600000,-1);
  c.restartPlayerPreservingPosition(new Channel(),"url",false);
  check(c.player.seek==0,"Invalid position must not seek");
  c.player=new IjkMediaPlayer(600000,80000);old=c.player;c.deferred=true;
  c.restartPlayerPreservingPosition(new Channel(),"url",false);
  check(c.player==old&&old.seek==-1,"Deferred surface start must not seek old instance");
  c.deferred=false;c.fail=true;old=c.player;
  try {c.restartPlayerPreservingPosition(new Channel(),"url",false);throw new AssertionError("Expected IOException");}
  catch(IOException expected){}
  check(c.player==old&&old.seek==-1,"Failed restart must not schedule stale seek");
  c.fail=false;c.systemHls=true;c.restartPlayerPreservingPosition(new Channel(),"hls",false);
  check(c.systemStarts==1,"System HLS recovery keeps its backend");
  c.systemHls=false;c.systemMp3=true;c.restartPlayerPreservingPosition(new Channel(),"mp3",false);
  check(c.systemStarts==2,"System MP3 recovery keeps its backend");
  c.androidMp3FallbackRequestId=c.playRequestId;
  c.restartPlayerPreservingPosition(new Channel(),"mp3",false);
  check(c.systemStarts==2,"Failed system MP3 route falls back to IJK");
  c.systemMp3=false;c.systemDv=true;c.restartPlayerPreservingPosition(new Channel(),"dv",false);
  check(c.systemStarts==3,"Dolby Vision recovery keeps its system backend");
  c.systemDv=false;c.systemAtmos=true;c.androidMp3FallbackRequestId=-1;
  c.restartPlayerPreservingPosition(new Channel(),"atmos",false);
  check(c.systemStarts==4,"Atmos recovery keeps its system backend");
  c.androidMp3FallbackRequestId=c.playRequestId;c.player=new IjkMediaPlayer(75560,23000);
  c.restartPlayerPreservingPosition(new Channel(),"atmos",false);
  check(c.systemStarts==4&&c.player.seek==23000,"Failed Atmos backend must fall back and preserve position");
  c.androidMp3FallbackRequestId=-1;c.player=new IjkMediaPlayer(75560,24000);
  c.restartPlayerPreservingPosition(new Channel(),"atmos",true);
  check(c.systemStarts==4&&c.player.seek==24000,"Software retry must not select the failed Atmos system backend");
  c.systemAtmos=false;c.systemMp3=true;c.androidMp3FallbackRequestId=-1;
  Channel radio=new Channel();radio.radio=true;
  c.restartPlayerPreservingPosition(radio,"radio",false);
  check(c.systemStarts==4,"Radio reconnect must not switch to system MP3");
  System.out.println("PASS VOD initial seek, live, end clamp, invalid clock, old instance, deferred/failed restart");
 }
}'''.replace('METHOD',completion+mode+method)
out=root/'.codex-tmp/vod-reconnect-test';out.mkdir(parents=True,exist_ok=True)
(out/'VodReconnectCheck.java').write_text(java,encoding='utf-8')
java_bin=Path(os.environ['JAVA_HOME'])/'bin'
subprocess.run([str(java_bin/'javac.exe'),'-encoding','UTF-8',str(out/'VodReconnectCheck.java')],check=True)
subprocess.run([str(java_bin/'java.exe'),'-cp',str(out),'VodReconnectCheck'],check=True)
