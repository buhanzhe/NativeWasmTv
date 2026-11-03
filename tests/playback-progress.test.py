"""Run the production playback-clock branch against cold-start / live-window samples.

Requires JAVA_HOME. No device or instrumentation APK is needed.
"""
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
source = (root / 'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf-8')
start = source.index('            if (playbackPosition >= 0L && playbackPosition != lastPlaybackPosition)')
end = source.index('            } else {', start)
branch = source[start:end] + '\n}'
test = '''public class PlaybackProgressCheck {
 long lastPlaybackPosition=-1, lastPlaybackProgressAt, lastPlaybackRecoveryAt;
 int playbackRecoveryAttempts, playbackRecoverySourcesTried;
 boolean playbackProgressObserved,windowContentActive=true,buffering,audioOnlyPlayback=true,videoRenderingStarted=true;
 long playbackHealthySince;float outputFps=25;
 static class Player {boolean isPlaying(){return true;}}static class IjkMediaPlayer extends Player{}Player player=new Player();
 static final long PLAYBACK_RECOVERY_HEALTHY_RESET_MS=30000;
 static final String TAG="test";
 static class Log { static void i(String tag,String text){} }
 void sample(long playbackPosition,long now) { BRANCH }
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 public static void main(String[] args){
   PlaybackProgressCheck p=new PlaybackProgressCheck();
   p.sample(0,1000);
   check(!p.playbackProgressObserved,"Initial -1 -> 0 falsely marks playback as started");
   p.sample(0,12000);
   check(!p.playbackProgressObserved,"Cold-start buffer incorrectly enters stall recovery");
   p.sample(40,13000);
   check(p.playbackProgressObserved,"Advancing clock must enable stall recovery");
   p.sample(-1,14000);
   check(p.lastPlaybackPosition==40,"Invalid clock changed baseline");
   p.sample(20,15000);
   check(p.lastPlaybackProgressAt==15000,"Live-window backwards rebase is valid progress");
   p=new PlaybackProgressCheck();
   p.playbackRecoveryAttempts=3;p.playbackRecoverySourcesTried=1;p.lastPlaybackRecoveryAt=1000;
   p.sample(5000,40000);
   check(!p.playbackProgressObserved && p.playbackRecoveryAttempts==3,
       "A nonzero first sample is still only a baseline; it must not reset recovery");
   p.sample(5040,41000);
   check(p.playbackRecoveryAttempts==3,"A single progress sample cannot clear recovery counters");
   p.sample(6040,71000);
   check(p.playbackRecoveryAttempts==0 && p.playbackRecoverySourcesTried==0,
       "30 seconds of healthy playback should clear recovery counters");
   p.playbackRecoveryAttempts=2;p.lastPlaybackRecoveryAt=72000;p.buffering=true;p.sample(6080,72000);
   p.buffering=false;p.sample(6120,100000);check(p.playbackRecoveryAttempts==2,"Buffering must restart healthy window");
   p.sample(6160,130000);check(p.playbackRecoveryAttempts==0,"New continuous healthy window should recover");
   System.out.println("PASS cold-start zero/nonzero baselines, real progress, invalid clocks, live rebase, recovery reset");
 }
}'''.replace('BRANCH', branch)
out = root / '.codex-tmp/playback-progress-test'
out.mkdir(parents=True, exist_ok=True)
java_file = out / 'PlaybackProgressCheck.java'
java_file.write_text(test, encoding='utf-8')
java_bin = Path(os.environ['JAVA_HOME']) / 'bin'
suffix = '.exe' if os.name == 'nt' else ''
subprocess.run([str(java_bin / ('javac' + suffix)), '-encoding', 'UTF-8', str(java_file)], check=True)
subprocess.run([str(java_bin / ('java' + suffix)), '-cp', str(out), 'PlaybackProgressCheck'], check=True)
