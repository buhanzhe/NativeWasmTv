"""Exercise the production video-output guard independently of the audio clock."""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf-8')
start = source.index('            if (!windowContentActive || !player.isPlaying() || buffering) resetPlaybackStallClock();')
end = source.index('            // API 14-15 MediaPlayer can render HLS', start)
guard = source[start:end]
reset_start=source.index('    private void resetPlaybackStallClock()')
reset=source[reset_start:source.index('\n    @SuppressLint',reset_start)]
start = source.index('            boolean retrySoftwareDecode = activeSoftwareDecode')
end = source.index(';', start)
fallback = source[start:end] + '; return retrySoftwareDecode;'
start = source.index('    private static String playbackRecoveryMessage(')
end = source.index('\n    private ', start+8)
message = source[start:end]
start=source.index('    private static boolean retryableLegacyHlsError(')
legacy=source[start:source.index('\n    private ',start+8)]
test = '''class VideoOutputRecoveryCheck {
static class Build {static class VERSION {static int SDK_INT=28;}static class VERSION_CODES {static int N_MR1=25;}}
static class Player {boolean playing=true,advance=true;long position=1;boolean isPlaying(){return playing;}long getCurrentPosition(){return advance?position++:position;}}
static class SystemClock {static long now;static long elapsedRealtime(){return now;}}
static class Log {static void i(String tag,String message){}}
static final String TAG="test";
static class IjkMediaPlayer extends Player {float source=25;}
static class IjkStreamMetrics {static int sourceQueries;static float sourceFrameRate(IjkMediaPlayer p){sourceQueries++;return p.source;}}
static class Proxy {boolean finite;boolean finiteMedia(){return finite;}}
Player player=new IjkMediaPlayer();Proxy proxy=new Proxy();
boolean windowContentActive=true;boolean videoRenderingStarted=true,buffering,audioOnlyPlayback,activeSoftwareDecode;
String activePlayerStreamUrl="live",decodeMode="auto";
static final String DECODE_MODE_AUTO="auto";
static final long PLAYBACK_STALL_RECOVERY_MS=10000,NTV_CAST_STALL_RECOVERY_MS=5000;
static final long PLAYBACK_RECOVERY_HEALTHY_RESET_MS=30000;
long lastPlaybackPosition=-1,lastPlaybackProgressAt,playbackHealthySince,lastPlaybackRecoveryAt;
boolean playbackProgressObserved;int playbackRecoveryAttempts,playbackRecoverySourcesTried;
long lastVideoOutputAt=100;int playRequestId=7,calls;boolean software;
boolean isNtVCastSource(String u){return "cast".equals(u);}
boolean isResolvedHlsSource(String u){return "live".equals(u);}
void recoverStalledPlayback(int request,Player p,String reason){calls++;software=false;}
void recoverStalledPlayback(int request,Player p,String reason,boolean stopped){calls++;software=choose(p,stopped);}
boolean choose(Player watchedPlayer,boolean videoOutputStopped){boolean decoderFailure=videoOutputStopped;FALLBACK}
MESSAGE
RESET
void sample(long now,float outputFps){SystemClock.now=now;GUARD}
static void check(boolean ok){if(!ok)throw new AssertionError();}
public static void main(String[] args){
VideoOutputRecoveryCheck s=new VideoOutputRecoveryCheck();s.sample(10099,0);check(s.calls==0);
check(IjkStreamMetrics.sourceQueries==0); // Do not cross JNI before the stall deadline.
s.sample(10100,0);check(s.calls==1&&!s.software); // Restart hardware; audio must not hide a video freeze.
check(IjkStreamMetrics.sourceQueries==1);
s=new VideoOutputRecoveryCheck();s.sample(20000,2);check(s.calls==0&&s.lastVideoOutputAt==20000);
s=new VideoOutputRecoveryCheck();s.player.playing=false;s.sample(20000,0);check(s.calls==0);
s.player.playing=true;s.sample(20001,0);check(s.calls==0);s.sample(30000,0);check(s.calls==1);
s=new VideoOutputRecoveryCheck();s.windowContentActive=false;s.sample(20000,0);check(s.calls==0&&s.lastVideoOutputAt==20000);
s=new VideoOutputRecoveryCheck();s.buffering=true;s.sample(20000,0);s.buffering=false;s.sample(20001,0);check(s.calls==0);
s=new VideoOutputRecoveryCheck();s.audioOnlyPlayback=true;s.sample(20000,0);check(s.calls==0);
s=new VideoOutputRecoveryCheck();s.proxy.finite=true;s.sample(20000,0);check(s.calls==0);
s=new VideoOutputRecoveryCheck();s.videoRenderingStarted=false;s.sample(20000,0);check(s.calls==0);
s=new VideoOutputRecoveryCheck();((IjkMediaPlayer)s.player).source=0;s.sample(20000,0);check(s.calls==0);
s=new VideoOutputRecoveryCheck();s.player=new Player();s.sample(20000,0);check(s.calls==0);
s=new VideoOutputRecoveryCheck();s.decodeMode="hardware";s.sample(20000,0);check(s.calls==1&&!s.software);
s.decodeMode="auto";check(!s.choose(s.player,false));check(!s.choose(s.player,true));
check(s.decodeMode.equals("auto")&&!s.activeSoftwareDecode); // Choice does not alter saved/global mode.
s.activeSoftwareDecode=true;check(s.choose(s.player,true)); // Explicit software playback stays software.
s.activeSoftwareDecode=false;s.playbackRecoveryAttempts=2;
check(s.choose(s.player,true)); // Repeated decoder/output failures fall back in auto mode.
check(!s.choose(s.player,false)); // Network stalls keep hardware.
s.decodeMode="hardware";check(!s.choose(s.player,true));
s.decodeMode="auto";Build.VERSION.SDK_INT=19;check(!s.choose(s.player,true));
Build.VERSION.SDK_INT=28;s.player=new Player();check(!s.choose(s.player,true));
check(playbackRecoveryMessage(true,false,false,false,1,5).contains("视频输出停滞"));
check(retryableLegacyHlsError("system HLS error 1/-1007"));
check(!retryableLegacyHlsError("system HLS error 1/-2147483648"));
check(!retryableLegacyHlsError("system HLS error 100/0"));
check(!retryableLegacyHlsError("system HLS error 1/-1004"));
check(!playbackRecoveryMessage(true,false,false,false,1,5).contains("崩溃"));
check(playbackRecoveryMessage(false,false,true,false,1,5).contains("系统解码器崩溃，正在恢复"));
check(playbackRecoveryMessage(true,false,false,true,1,5).contains("网络已断开"));
check(!playbackRecoveryMessage(true,true,false,false,1,5).contains("系统解码器"));
s.activePlayerStreamUrl="cast";s.calls=0;s.sample(20000,0);check(s.calls==1&&!s.software);
// Both watchdog clocks must restart after pause, buffer and background intervals.
for(int mode=0;mode<3;mode++){
 s=new VideoOutputRecoveryCheck();
 s.sample(1000,25);s.sample(2000,25);check(s.playbackProgressObserved);
 s.player.advance=false;
 if(mode==0)s.player.playing=false;else if(mode==1)s.buffering=true;else s.windowContentActive=false;
 s.sample(60000,0);check(s.calls==0&&!s.playbackProgressObserved);
 s.player.playing=true;s.buffering=false;s.windowContentActive=true;
 s.sample(60001,0);check(s.calls==0);s.sample(69999,0);check(s.calls==0);
 s.sample(70000,0);check(s.calls==1);
}
// Audio clock progress alone cannot erase repeated video failures.
s=new VideoOutputRecoveryCheck();s.playbackRecoveryAttempts=2;s.lastPlaybackRecoveryAt=100;
for(int now=1000;now<50000;now+=1000){s.lastVideoOutputAt=now;s.sample(now,0);}
check(s.playbackRecoveryAttempts==2);
s.sample(50000,25);s.sample(51000,25);check(s.playbackHealthySince==50000);
s.buffering=true;s.sample(79000,0);s.buffering=false;
s.sample(80000,25);s.sample(81000,25);check(s.playbackRecoveryAttempts==2);
for(int now=82000;now<=111000;now+=1000)s.sample(now,25);
check(s.playbackRecoveryAttempts==0&&s.lastPlaybackRecoveryAt==0);
// An audio-only player can recover with a continuously advancing audio clock.
s=new VideoOutputRecoveryCheck();s.audioOnlyPlayback=true;s.playbackRecoveryAttempts=1;s.lastPlaybackRecoveryAt=100;
for(int now=1000;now<=32000;now+=1000)s.sample(now,0);
check(s.playbackRecoveryAttempts==0);
}}
'''.replace('GUARD', guard).replace('FALLBACK', fallback).replace('MESSAGE', legacy+'\n'+message).replace('\nRESET\n','\n'+reset+'\n')
jdk = Path(os.environ.get('JAVA_HOME', 'C:/Users/Administrator/.jdks/corretto-1.8.0_492'))/'bin'
with tempfile.TemporaryDirectory(prefix='ntv-output-recovery-') as directory:
    file = Path(directory)/'VideoOutputRecoveryCheck.java'
    file.write_text(test, encoding='utf-8')
    subprocess.run([str(jdk/'javac.exe'), '-encoding', 'UTF-8', str(file)], check=True)
    subprocess.run([str(jdk/'java.exe'), '-cp', directory, 'VideoOutputRecoveryCheck'], check=True)
print('PASS video-only freeze, sparse output, pause/resume, buffering, audio, VOD and decode-mode guards')
