"""Run the production pre-MediaCodec player selection for FLV and supported formats."""
from pathlib import Path
import os, re, subprocess, tempfile

root = Path(__file__).resolve().parents[1]
source = (root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf8')
def method(name):
    start = source.index('    private '+name)
    brace = source.index('{', start); end = brace+1; depth = 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}'); end += 1
    return source[start:end]
methods = '\n'.join(method(name) for name in [
    'int systemPlayerMode(', 'boolean canFallbackToSystemCodec(',
    'boolean useSystemCodecFallback(', 'boolean useLegacyX86SoftwareDecode(', 'static boolean useAndroidHlsPlayer(',
    'static boolean useAndroidMp4Player(', 'static boolean useAndroidMp3Player('])
software = re.search(r'final boolean softwareDecode = (.*?);', source[source.index('boolean hintedSoftware ='):], re.S).group(1)
guard_start=source.index('// On API 16-19 an unexpected hardware failure')
reject=re.search(r'if \((.*?)\) \{',source[guard_start:],re.S).group(1)
java = r'''
import java.util.*;
class Check {
 static class Build {static String CPU_ABI="armeabi-v7a";static class VERSION {static int SDK_INT;} static class VERSION_CODES {static final int JELLY_BEAN=16,KITKAT=19,ICE_CREAM_SANDWICH_MR1=15;}}
 static class Uri {java.net.URI uri;static Uri parse(String s){Uri u=new Uri();u.uri=java.net.URI.create(s);return u;}String getPath(){return uri.getPath();}}
 static class Channel {boolean radio;}
 static class IjkMediaPlayer {static final int FFP_PROPV_DECODER_AVCODEC=1;int getVideoDecoder(){return 1;}}
 static final String DECODE_MODE_SOFTWARE="software",DECODE_MODE_AUTO="auto",PLAYER_ENGINE_SYSTEM="system";
 String decodeMode="auto",playerEngine="ijk",systemCodecFallbackUrl;int androidMp3FallbackRequestId=-1,playRequestId=5,systemCodecFallbackRequestId=-1,queries;
 Boolean hardwareVideoDecoderAvailable=Boolean.FALSE;
 boolean continuousTsEnabled;Object catchupProgram;static class Proxy {boolean finiteMedia(){return false;}}Proxy proxy;
 int missingCodecSoftwareRetryRequestId=-1,sourceRequestId=5;
 void availableHardwareDecoderNames(){queries++;}
 static boolean isHttpHlsSource(String u){return u!=null&&u.endsWith(".m3u8");}
 static boolean isExplicitAudioSource(String u){return false;}
 boolean isNtVCastSource(String u){return u!=null&&u.startsWith("ntv:");}
 boolean useSystemDolbyVisionPlayer(){return false;}boolean useSystemAtmosPlayer(String u){return false;}
 boolean shouldUseSoftwareDecode(){return false;}
 METHODS
 boolean software(boolean forceSoftwareDecode,boolean hintedSoftware){return SOFTWARE_EXPRESSION;}
 boolean rejects(boolean softwareDecode){boolean audioOnlyPlayback=false;IjkMediaPlayer nextPlayer=new IjkMediaPlayer();return REJECT_EXPRESSION;}
 static void check(boolean b,String reason){if(!b)throw new AssertionError(reason);}
 public static void main(String[] args){
  for(int sdk:new int[]{14,15,16,19,21,25,34}){
   Build.VERSION.SDK_INT=sdk;Check c=new Check();Channel video=new Channel();
   for(String u:new String[]{"https://live.example/yy/123","http://cdn.example/live.flv"}){
    check(c.systemPlayerMode(video,u,false)==(sdk<16?0:1),"FLV/extensionless automatic route API"+sdk);
   }
   check(c.software(false,false)==(sdk<16),"Pre-MediaCodec uses IJK software options API"+sdk);
   check(c.rejects(c.software(false,false))==(sdk>=16&&sdk<=19),"Expected software must survive onPrepared API"+sdk);
   check(c.rejects(false)==(sdk<=19),"Unexpected legacy hardware failure remains guarded API"+sdk);
   check(!c.rejects(true),"Selected software must survive onPrepared API"+sdk);
   c.missingCodecSoftwareRetryRequestId=5;
   check(c.software(true,false),"Confirmed missing HEVC permits the single software retry API"+sdk);
   c.missingCodecSoftwareRetryRequestId=-1;
   if(sdk<16){
    check(c.queries==0,"Unavailable MediaCodec API must not be enumerated");
    check(c.systemPlayerMode(video,"https://cdn.example/live.m3u8",false)==1,"Framework HLS preserved");
    check(c.systemPlayerMode(video,"https://cdn.example/movie.mp4",false)==1,"Framework MP4 preserved");
    check(c.systemPlayerMode(video,"https://cdn.example/music.mp3",false)==2,"Framework MP3 preserved");
   }
   c.decodeMode="software";check(c.systemPlayerMode(video,"https://cdn.example/live.flv",false)==0,"Explicit software preserved");
   check(c.software(false,false),"Explicit software options preserved");
   c.decodeMode="auto";c.playerEngine="system";
   check(c.systemPlayerMode(video,"https://cdn.example/live.flv",false)==1,"Manual system player preserved");
   c=new Check();c.hardwareVideoDecoderAvailable=Boolean.TRUE;
   check(c.systemPlayerMode(video,"https://cdn.example/live.flv",false)==0,"Available codec stays IJK");
   check(!c.canFallbackToSystemCodec(video,"ntv:test",false),"Cast transport must stay IJK");
  }
  Build.CPU_ABI="x86";Build.VERSION.SDK_INT=19;Check x=new Check();Channel tv=new Channel();
  check(x.systemPlayerMode(tv,"https://cdn.example/live.m3u8",false)==0&&x.software(false,false),"KitKat x86 without any hardware decoder selects IJK software before prepare");
  check(!x.rejects(x.software(false,false)),"The chosen KitKat x86 software decoder is accepted by the actual prepared-player guard");
  for(Boolean known:new Boolean[]{null,Boolean.TRUE}){x.hardwareVideoDecoderAvailable=known;check(!x.useLegacyX86SoftwareDecode()&&!x.software(false,false),"Unknown or available hardware keeps automatic codec selection");}
  x.hardwareVideoDecoderAvailable=Boolean.FALSE;x.decodeMode="hardware";check(!x.useLegacyX86SoftwareDecode()&&x.systemPlayerMode(tv,"https://cdn.example/live.m3u8",false)==1,"Explicit hardware choice is respected");
  x.decodeMode="auto";x.playerEngine="system";check(x.systemPlayerMode(tv,"https://cdn.example/live.m3u8",false)==1,"Explicit framework player is respected");
  for(int sdk:new int[]{15,16,18,21,34}){Build.VERSION.SDK_INT=sdk;check(!x.useLegacyX86SoftwareDecode(),"Other Android releases keep their existing decoder policy");}
  System.out.println("PASS API14-34 FLV/extensionless routing, IJK software options, supported legacy formats and manual player selection");
 }
}'''.replace('METHODS', methods).replace('SOFTWARE_EXPRESSION', software).replace('REJECT_EXPRESSION',reject)
with tempfile.TemporaryDirectory() as folder:
    p=Path(folder)/'Check.java';p.write_text(java,encoding='utf8')
    jdk=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
    subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8',str(p)],check=True)
    subprocess.run([str(jdk/'java.exe'),'-cp',folder,'Check'],check=True)
