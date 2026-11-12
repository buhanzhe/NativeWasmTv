"""Compile the production routing method; cover manual choice and legacy defaults."""
from pathlib import Path
import os, subprocess, tempfile

root = Path(__file__).resolve().parents[1]
source = (root / 'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf-8')
start = source.index('    private int systemPlayerMode(')
end = source.index('\n    private boolean fallbackToSystemCodec', start)
method = source[start:end]
audio_start=source.index('    private static boolean isExplicitAudioSource(')
audio_end=source.index('\n    private void prefetchAdjacentArtwork(',audio_start)
method+='\n'+source[audio_start:audio_end]
java = '''import java.util.Locale;class PlayerEngineCheck {
 static class Uri {static java.net.URI parse(String value){return java.net.URI.create(value);}}
 static final String PLAYER_ENGINE_SYSTEM="system",DECODE_MODE_SOFTWARE="software";
 String playerEngine="ijk",decodeMode="auto";int androidMp3FallbackRequestId=-1,playRequestId=1;
 int systemCodecFallbackRequestId=-1;String systemCodecFallbackUrl;
 Boolean hardwareVideoDecoderAvailable=true;boolean continuousTsEnabled;Object catchupProgram;
 static class Proxy {boolean finite;boolean finiteMedia(){return finite;}} Proxy proxy=new Proxy();
 boolean isHttpHlsSource(String u){return hls;}
 void availableHardwareDecoderNames(){}
 static class Build {static class VERSION {static int SDK_INT=16;}static class VERSION_CODES {static final int JELLY_BEAN=16;}}
 static class Channel {boolean radio;Channel(boolean r){radio=r;}}
 boolean hls,mp4,mp3,vision,atmos,cast;
 boolean isNtVCastSource(String u){return cast;}
 boolean useAndroidHlsPlayer(int s,String u){return hls&&s<=15;}
 boolean useAndroidMp4Player(int s,String u){return mp4&&s<=15;}
 boolean useAndroidMp3Player(int s,String u){return mp3&&s<=15;}
 boolean useSystemDolbyVisionPlayer(){return vision;}
 boolean useSystemAtmosPlayer(String u){return atmos;}
 METHOD
 static void eq(int actual,int want){if(actual!=want)throw new AssertionError(actual+" != "+want);}
 public static void main(String[] args){
  Channel tv=new Channel(false),radio=new Channel(true);String url="http://fixture/live.m3u8";
  PlayerEngineCheck a=new PlayerEngineCheck();
  // Manual system supports the requested Android 4.1 path, even with saved soft decode.
  for(int sdk:new int[]{14,15,16,19,25,28,30})for(String decode:new String[]{"auto","hardware","software"}){
   Build.VERSION.SDK_INT=sdk;a.decodeMode=decode;a.playerEngine="system";
   eq(a.systemPlayerMode(tv,url,false),1);eq(a.systemPlayerMode(tv,url,true),1);
   eq(a.systemPlayerMode(radio,url,false),2);
   eq(a.systemPlayerMode(tv,"http://fixture/audio.mp3?key=1",false),2);
   eq(a.systemPlayerMode(tv,"https://fixture/audio.aac",true),2);
   a.androidMp3FallbackRequestId=a.playRequestId;eq(a.systemPlayerMode(tv,url,false),1);
   a.cast=true;eq(a.systemPlayerMode(tv,url,false),0);a.cast=false;
   a.playerEngine="ijk";eq(a.systemPlayerMode(tv,url,false),0);
  }
  a=new PlayerEngineCheck();a.hls=true;Build.VERSION.SDK_INT=15;eq(a.systemPlayerMode(tv,url,false),1);
  a.decodeMode="software";eq(a.systemPlayerMode(tv,url,false),0);
  a.decodeMode="auto";Build.VERSION.SDK_INT=16;eq(a.systemPlayerMode(tv,url,false),0);
  a.hls=false;a.mp4=true;Build.VERSION.SDK_INT=15;eq(a.systemPlayerMode(tv,url,false),1);eq(a.systemPlayerMode(tv,url,true),0);
  a.mp4=false;a.mp3=true;eq(a.systemPlayerMode(tv,url,false),2);eq(a.systemPlayerMode(radio,url,false),0);
  a.androidMp3FallbackRequestId=1;eq(a.systemPlayerMode(tv,url,false),0);
  a.mp3=false;a.vision=true;eq(a.systemPlayerMode(tv,url,false),1);
  a.vision=false;a.atmos=true;a.androidMp3FallbackRequestId=-1;eq(a.systemPlayerMode(tv,url,false),1);
  eq(a.systemPlayerMode(tv,url,true),0);
  a=new PlayerEngineCheck();Build.VERSION.SDK_INT=25;
  eq(a.systemPlayerMode(radio,"file:/cache/test.wav",false),2);
  a.decodeMode="software";eq(a.systemPlayerMode(radio,"file:/cache/test.wav",false),0);
  a.decodeMode="auto";eq(a.systemPlayerMode(radio,"file:/cache/test.wav",true),0);
  a.androidMp3FallbackRequestId=a.playRequestId;eq(a.systemPlayerMode(radio,"file:/cache/test.wav",false),0);
  // No hardware video decoder exposed by the firmware: bypass IJK's software path.
  a=new PlayerEngineCheck();a.hardwareVideoDecoderAvailable=false;Build.VERSION.SDK_INT=17;
  eq(a.systemPlayerMode(tv,url,false),1);eq(a.systemPlayerMode(radio,url,false),0);
  a.decodeMode="software";eq(a.systemPlayerMode(tv,url,false),0);
  a.decodeMode="hardware";eq(a.systemPlayerMode(tv,url,false),1);
  eq(a.systemPlayerMode(tv,url,true),0);
  a.hardwareVideoDecoderAvailable=null;eq(a.systemPlayerMode(tv,url,false),0);
  a.hardwareVideoDecoderAvailable=true;a.systemCodecFallbackRequestId=1;a.systemCodecFallbackUrl=url;
  // A per-stream decoder failure survives reconnects, without changing the global engine.
  eq(a.systemPlayerMode(tv,url,false),1);eq(a.systemPlayerMode(tv,url+"?other",false),0);
  a.playRequestId=2;eq(a.systemPlayerMode(tv,url,false),0);
  a.hardwareVideoDecoderAvailable=false;a.cast=true;eq(a.systemPlayerMode(tv,url,false),0);
  a=new PlayerEngineCheck();a.hls=true;a.continuousTsEnabled=true;Build.VERSION.SDK_INT=19;
  eq(a.systemPlayerMode(tv,url,false),1);eq(a.systemPlayerMode(radio,url,false),0);
  a.catchupProgram=new Object();eq(a.systemPlayerMode(tv,url,false),0);a.catchupProgram=null;
  a.proxy.finite=true;eq(a.systemPlayerMode(tv,url,false),0);a.proxy.finite=false;
  a.decodeMode="software";eq(a.systemPlayerMode(tv,url,false),0);a.decodeMode="auto";
  eq(a.systemPlayerMode(tv,url,true),0);a.cast=true;eq(a.systemPlayerMode(tv,url,false),0);
  a.cast=false;a.continuousTsEnabled=false;eq(a.systemPlayerMode(tv,url,false),0);
  System.out.println("PASS manual system/IJK, missing hardware, unknown query, stream-bound recovery, explicit software, radio, cast and legacy defaults");
 }
}'''.replace('METHOD', method)
# Initial play and buffer-recovery paths must use the exact same routing decision.
assert source.count('= systemPlayerMode(channel, ') == 2
assert '.putString(PLAYER_ENGINE, playerEngine).apply()' in source
assert '.put("playerEngine", playerEngine)' in source
with tempfile.TemporaryDirectory() as temp:
    out = Path(temp) / 'PlayerEngineCheck.java'
    out.write_text(java, encoding='utf-8')
    jdk = Path(os.environ.get('JAVA_HOME', root / '.codex-tmp/jdk8/runtime/jdk8u504-b01')) / 'bin'
    subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '-d', temp, str(out)], check=True)
    subprocess.run([str(jdk / 'java.exe'), '-cp', temp, 'PlayerEngineCheck'], check=True)
