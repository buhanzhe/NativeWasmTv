"""Execute production startup watchdog, live control guards, PMT and presentation clock."""
from pathlib import Path
import os
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[1]
main = (root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf8')
start = main.index('    private void armPlayerStartupWatchdog(')
end = main.index('\n    private void markAndroidHlsVideoReady(', start)
watch = main[start:end]
start = main.index('        } else if ("seek".equals(action)) {', main.index('private void applyMediaControl('))
end = main.index('        } else if ("videoTrack".equals(action)) {', start)
controls = '        if ("seek".equals(action)) {' + main[start:].split('{', 1)[1][:end-start-(main[start:].index('{')+1)] + '\n        }'

java = r'''package xiao.bu.tv;
import java.util.*;
import java.io.IOException;
class StartupCheck {
 static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
 interface IMediaPlayer {long getDuration();}
 static class IjkMediaPlayer implements IMediaPlayer {int speeds;public long getDuration(){return 40000;}void setSpeed(float s){speeds++;}}
 static class AndroidMediaPlayer implements IMediaPlayer {public long getDuration(){return 0;}}
 static class Channel {boolean radio;}
 static class Proxy {Throwable lastPlaybackFailure(){return null;}}
 static class Clock {static long now;}
 static class SystemClock {static long elapsedRealtime(){return Clock.now;}}
 static class VideoDecoderSupport {static Boolean supported;static Boolean hasDecoder(String mime){return supported;}}
 static class Queue {Runnable pending;void postDelayed(Runnable r,long delay){pending=r;}void removeCallbacks(Runnable r){if(pending==r)pending=null;}}
 IMediaPlayer player=new IjkMediaPlayer();Channel activePlayerChannel=new Channel();Proxy proxy=new Proxy();
 int playRequestId=1,failures;boolean prepared,audioOnlyPlayback,videoRenderingStarted,windowContentActive=true;String mime="",message;
 Queue channelBar=new Queue();Runnable playerStartupWatchdog;
 boolean isFinishing(){return false;}String startupVideoMime(){return mime;}
 void failPlayerStartup(int r,IMediaPlayer p,String m,String d){failures++;message=m;}
 static class Log {static void w(String t,String m){}}
 String decodeMode="auto",directHttpMediaUrl="";static String TAG="test",DECODE_MODE_HARDWARE="hardware";
 int missingCodecSoftwareRetryRequestId=-1,softwareAttempts;
 void updateLoadingStatus(String text){}void startIjkPlayer(Channel c,String url,boolean software,boolean direct)throws IOException{softwareAttempts++;player=new IjkMediaPlayer();}
 RETRY
 WATCH
 void tick(long time){Clock.now=time;Runnable r=channelBar.pending;channelBar.pending=null;if(r!=null)r.run();}
 static class Request {String key="source";long position;double speed=2;boolean has(String s){return true;}String optString(String s,String d){return key;}long optLong(String s,long d){return position;}double optDouble(String s,double d){return speed;}}
 boolean live=true;int seeks,mediaTrackChangeGeneration;float playbackSpeed=1;
 String mediaSourceKey(){return "source";}long seekableDuration(){return live?0:40000;}
 void seekPlayback(long position){seeks++;}void showPlaybackProgress(long p){}
 boolean playbackSpeedAvailable(IMediaPlayer p){return !live;}
 void control(String action,Request request)throws Exception {IMediaPlayer activePlayer=player; CONTROLS }
 static byte[] crc(byte[] body){int value=-1;for(byte b:body){value^=(b&255)<<24;for(int i=0;i<8;i++)value=(value<<1)^((value<0)?0x04c11db7:0);}byte[] out=Arrays.copyOf(body,body.length+4);for(int i=0;i<4;i++)out[body.length+i]=(byte)(value>>>(24-i*8));return out;}
 static byte[] packet(int pid,byte[] section){byte[] ts=new byte[188];Arrays.fill(ts,(byte)255);ts[0]=0x47;ts[1]=(byte)(0x40|(pid>>8));ts[2]=(byte)pid;ts[3]=0x10;ts[4]=0;System.arraycopy(section,0,ts,5,section.length);return ts;}
 static byte[] ts(int type){byte[] pat=packet(0,crc(new byte[]{0,(byte)0xb0,13,0,1,(byte)0xc1,0,0,0,1,(byte)0xe1,0}));
  byte[] pmt=packet(256,crc(new byte[]{2,(byte)0xb0,18,0,1,(byte)0xc1,0,0,(byte)0xe1,1,(byte)0xf0,0,(byte)type,(byte)0xe1,1,(byte)0xf0,0}));
  byte[] out=Arrays.copyOf(pat,376);System.arraycopy(pmt,0,out,188,188);return out;}
 public static void main(String[] args)throws Exception {
  check(HlsVideoCodecProbe.mime(ts(0x24)).equals("video/hevc"),"HEVC PMT");
  check(HlsVideoCodecProbe.mime(ts(0x1b)).equals("video/avc"),"AVC PMT");
  HlsVideoCodecProbe.Stream stream=new HlsVideoCodecProbe.Stream();byte[] validTs=ts(0x24);
  for(int i=0;i<validTs.length;i++)stream.accept(validTs,i,1);
  check(stream.mime().equals("video/hevc"),"Split network reads preserve PAT/PMT");
  if(args.length>0)check(HlsVideoCodecProbe.mime(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[0]))).equals("video/hevc"),"Actual FFmpeg HEVC TS");
  byte[] broken=ts(0x24);broken[210]^=1;check(HlsVideoCodecProbe.mime(broken).isEmpty(),"Corrupt PMT ignored");
  check(HlsVideoCodecProbe.mime(new byte[5]).isEmpty(),"Short TS ignored");
  StartupCheck soft=new StartupCheck();IjkMediaPlayer original=(IjkMediaPlayer)soft.player;
  soft.retryMissingVideoCodec(original,soft.activePlayerChannel,"http://stream",1);
  soft.retryMissingVideoCodec(original,soft.activePlayerChannel,"http://stream",1);
  check(soft.softwareAttempts==1&&soft.failures==0,"Only one software fallback; old callback ignored");
  soft=new StartupCheck();soft.decodeMode="hardware";soft.retryMissingVideoCodec((IjkMediaPlayer)soft.player,soft.activePlayerChannel,"http://stream",1);
  check(soft.softwareAttempts==0&&soft.failures==1&&soft.message.contains("H.265"),"Explicit hardware fails clearly");
  Clock.now=0;StartupCheck c=new StartupCheck();c.armPlayerStartupWatchdog(c.player,1);c.tick(24999);check(c.failures==0,"Not timed out early");c.tick(25000);check(c.failures==1&&c.message.contains("准备超时"),"Prepare bounded at 25s");
  Clock.now=0;c=new StartupCheck();c.prepared=true;c.armPlayerStartupWatchdog(c.player,1);c.tick(25000);check(c.failures==1&&c.message.contains("无法解码"),"Prepared audio clock cannot hide black video");
  Clock.now=0;c=new StartupCheck();c.prepared=true;c.videoRenderingStarted=true;c.armPlayerStartupWatchdog(c.player,1);c.tick(25000);check(c.failures==0,"Healthy video is preserved");
  Clock.now=0;c=new StartupCheck();c.prepared=true;c.audioOnlyPlayback=true;c.armPlayerStartupWatchdog(c.player,1);c.tick(25000);check(c.failures==0,"Real radio is preserved");
  Clock.now=0;c=new StartupCheck();c.player=new AndroidMediaPlayer();c.mime="video/hevc";VideoDecoderSupport.supported=false;c.armPlayerStartupWatchdog(c.player,1);c.tick(500);check(c.failures==1&&c.message.contains("H.265"),"Known missing system HEVC decoder fails promptly");
  Clock.now=0;c=new StartupCheck();c.armPlayerStartupWatchdog(c.player,1);c.playRequestId=2;c.tick(25000);check(c.failures==0,"Old channel timeout ignored");
  Clock.now=0;c=new StartupCheck();c.armPlayerStartupWatchdog(c.player,1);c.player=new IjkMediaPlayer();c.tick(25000);check(c.failures==0,"Old backend timeout ignored");
  Clock.now=0;c=new StartupCheck();c.windowContentActive=false;c.armPlayerStartupWatchdog(c.player,1);c.tick(90000);check(c.failures==0,"Background surface loss is not a startup decoder failure");c.windowContentActive=true;c.tick(90500);check(c.failures==0,"Resume gets active startup time");c.tick(115000);check(c.failures==1,"Foreground startup remains bounded");
  c=new StartupCheck();Request request=new Request();request.position=95361122;
  for(int i=0;i<20;i++)for(String action:new String[]{"seek","speed"}){try{c.control(action,request);throw new AssertionError("Live accepted "+action);}catch(ControlRequestException e){check(e.status==409&&e.getMessage().contains("不支持"),"Specific 409 client error");}}
  check(c.seeks==0&&((IjkMediaPlayer)c.player).speeds==0,"Rejected commands never reach decoder");
  c.live=false;request.speed=Double.NaN;try{c.control("speed",request);throw new AssertionError("NaN accepted");}catch(ControlRequestException e){check(e.status==400,"Invalid speed is 400");}
  MediaPositionClock clock=new MediaPositionClock();Object owner=new Object();
  check(clock.position(owner,1,95361122,0,0,true)==0,"Live PTS starts at elapsed zero");
  check(clock.position(owner,1,95362122,0,1000,true)==1000,"Live advances by actual delta");
  check(clock.position(owner,1,500,0,2000,true)==2000,"HLS rebase preserves elapsed clock");
  check(clock.position(owner,1,95361122,0,3000,true)==3000,"Bad clock jump normalized");
  check(clock.position(owner,1,95361122,0,4000,false)==3000,"Pause does not advance");
  check(clock.position(owner,2,95361122,0,5000,true)==0,"New source resets clock");
  check(clock.position(owner,2,999999,40000,6000,true)==40000,"VOD clamped to duration");
  System.out.println("PASS actual prepare/first-frame watchdog, stale identity, HEVC/CRC, 40 rejected live controls, NaN and live PTS normalization");
 }
}'''.replace('WATCH',watch).replace('CONTROLS',controls).replace('RETRY',main[main.index('    private void retryMissingVideoCodec('):main.index('    private void failPlayerStartup(')])

with tempfile.TemporaryDirectory(prefix='ntv-startup-') as folder:
    file=Path(folder)/'StartupCheck.java';file.write_text(java,encoding='utf8')
    source=root/'app/src/main/java/xiao/bu/tv'
    jdk=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
    subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',folder,str(file),
                    *[str(source/name) for name in ['PlaybackStartupPolicy.java','MediaPositionClock.java',
                                                   'HlsVideoCodecProbe.java','ControlRequestException.java']]],check=True)
    subprocess.run([str(jdk/'java.exe'),'-cp',folder,'xiao.bu.tv.StartupCheck',*sys.argv[1:]],check=True)
