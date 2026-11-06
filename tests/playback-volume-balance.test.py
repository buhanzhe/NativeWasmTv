"""Real attenuation/preference calculations, including quiet and invalid audio."""
import os, pathlib, subprocess, tempfile
root = pathlib.Path(__file__).resolve().parents[1]
java = r'''package xiao.bu.tv;
class Check {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 public static void main(String[] args){
  PlaybackVolumeBalance b=new PlaybackVolumeBalance();
  b.sample(-1);b.sample(0);b.sample(1);b.finishSegment();
  check(!b.ready()&&b.samples==0,"silence cannot set volume");
  for(int segment=0;segment<2;segment++){
   for(int i=0;i<6;i++)b.sample(10737418);b.finishSegment();
  }
  check(!b.ready()&&b.adjust(-30,10,10)==1,"two segments cannot adjust");
  b.sample(10737418);b.finishSegment();
  check(b.ready(),"three measured segments required");
  float gain=b.adjust(-30,10,10);
  check(Math.abs(gain-.31622777f)<.001,"-20 dB attenuated to -30 dB");
  double learned=b.learn(5,10);
  check(Math.abs(learned+36.0206)<.01,"manual volume updates preferred level");
  check(Math.abs(b.adjust(learned,5,10)-gain)<.001,"learning preserves manual change");
  b.adjust(0,10,10);check(b.gain==1,"no boost above unity");
  b.adjust(-60,10,10);check(b.gain>=.1f,"bounded attenuation");
  PlaybackVolumeBalance fresh=new PlaybackVolumeBalance();
  check(!fresh.ready()&&fresh.gain==1,"new channel has no inherited samples");
  System.out.println("Volume balance policy OK");
 }
}'''
jdk = pathlib.Path(os.environ['JAVA_HOME']) / 'bin'
main=(root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf-8')
controller=main[main.index('    private volatile boolean playerVolumeBalance;'):main.index('    private volatile boolean groupPasswordRequired;')]
harness=r'''package xiao.bu.tv;
class MainActivity {
 interface IMediaPlayer {boolean isPlaying();}
 static class IjkMediaPlayer implements IMediaPlayer {public boolean isPlaying(){return true;}}
 static class IjkStreamMetrics {
  static boolean enabled;static long audioPower(IjkMediaPlayer p){return enabled?10737418:0;}
  static void measureAudio(IjkMediaPlayer p,boolean on){enabled=on;}
 }
 static class AudioManager {static final int STREAM_MUSIC=3;int volume=10;
  int getStreamVolume(int stream){return volume;}int getStreamMaxVolume(int stream){return 10;}}
 static class Channel {}
 static class Queue {void removeCallbacks(Runnable r){}void postDelayed(Runnable r,long ms){}}
 static class Toast {static final int LENGTH_SHORT=0;static int count;
  static Toast makeText(MainActivity a,String s,int n){count++;return new Toast();}void show(){}}
 static class Prefs {float target;Prefs edit(){return this;}Prefs putFloat(String k,float f){target=f;return this;}void apply(){}}
 static class AppLog {static void w(String t,String m,Throwable e){throw new AssertionError(e);}}
 static final String AUDIO_SERVICE="audio",PREFERENCES="prefs",TAG="test";static final int MODE_PRIVATE=0;
 final AudioManager audio=new AudioManager();final Prefs prefs=new Prefs();final Channel channel=new Channel();
 final Queue channelBar=new Queue();IMediaPlayer player=new IjkMediaPlayer();
 boolean videoRenderingStarted,audioOnlyPlayback;float applied=1;
 boolean isFinishing(){return false;}boolean isPlaybackMuted(){return false;}
 Object getSystemService(String s){return audio;}Prefs getSharedPreferences(String s,int mode){return prefs;}
 Channel currentChannel(){return channel;}
 void applyPlaybackMuteState(){applied=playerVolumeBalance?volumeBalance.gain:1;}
 CONTROLLER
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] args){
  MainActivity a=new MainActivity();a.playerVolumeBalance=true;a.updateVolumeBalance();
  for(int i=0;i<10;i++){a.balanceSegments++;a.volumeBalanceTick.run();}
  check(!IjkStreamMetrics.enabled&&Toast.count==0&&a.applied==1,"no measurement or adjustment before first frame");
  a.videoRenderingStarted=true;a.volumeBalanceTick.run();
  check(IjkStreamMetrics.enabled,"measurement starts after rendered frame");
  for(int i=0;i<3;i++){a.balanceSegments++;a.volumeBalanceTick.run();}
  check(Toast.count==1&&a.balanceApplied&&a.applied<1,"three segment intervals adjust once");
  check(!IjkStreamMetrics.enabled,"native measurement stops after adjustment");
  float gain=a.applied;
  for(int i=0;i<20;i++){a.balanceSegments++;a.volumeBalanceTick.run();}
  check(Toast.count==1&&a.applied==gain,"later segments cannot adjust again");
  a.audio.volume=5;a.volumeBalanceTick.run();
  check(a.prefs.target< -30&&a.applied==gain&&Toast.count==1,"manual volume persists threshold without another adjustment");
  a.player=new IjkMediaPlayer();a.updateVolumeBalance();a.volumeBalanceTick.run();
  check(a.balanceApplied&&a.applied==gain&&Toast.count==1,"same-channel retry retains one adjustment");
  a.playerVolumeBalance=false;a.updateVolumeBalance();
  check(a.applied==1&&!IjkStreamMetrics.enabled,"disable restores unmodified player gain");
  System.out.println("Actual controller first-frame/once/manual lifecycle OK");
 }
}'''.replace('CONTROLLER',controller)
with tempfile.TemporaryDirectory() as tmp:
    folder=pathlib.Path(tmp); source=folder/'Check.java';source.write_text(java)
    host=folder/'MainActivity.java';host.write_text(harness,encoding='utf-8')
    subprocess.run([str(jdk/'javac'),'-encoding','UTF-8','-d',tmp,str(root/'app/src/main/java/xiao/bu/tv/PlaybackVolumeBalance.java'),str(source),str(host)],check=True)
    subprocess.run([str(jdk/'java'),'-cp',tmp,'xiao.bu.tv.Check'],check=True)
    subprocess.run([str(jdk/'java'),'-cp',tmp,'xiao.bu.tv.MainActivity'],check=True)
