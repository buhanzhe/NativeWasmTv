"""Run the real legacy HEVC option and quick-play restart/exhaustion branches."""
from pathlib import Path
import os,re,subprocess,tempfile
root=Path(__file__).resolve().parents[1]
s=(root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf8')
option=re.search(r'nextPlayer.setOption\(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc",\s*([^;]+)\);',s).group(1)
start=s.index('            final boolean keepCurrentSource = request.has("playerEngine")')
choice=s[start:s.index('\n            runOnUiThread',start)]
start=s.index('                    if (keepCurrentSource && quickChannel != null)')
restart=s[start:s.index('\n                }\n            });',start)]
start=s.index('        if (decoderFailure || legacyHlsFailure || quickChannel != null)')
stop=s[start:s.index('        int sourceCount = ',start)]
java='''import java.util.*;
class Check {
 static class Build {static class VERSION {static int SDK_INT;}static class VERSION_CODES {static final int JELLY_BEAN=16;}}
 static int hevc(boolean softwareDecode){return OPTION;}
 Object quickChannel;boolean openingQuickChannel,kept,advanced;int currentChannelIndex=7,currentSourceIndex=2,selected=-1,released;
 static class Request {Set<String> keys;Request(String...k){keys=new HashSet<>(Arrays.asList(k));}boolean has(String k){return keys.contains(k);}}
 void startChannel(int i){kept=openingQuickChannel;selected=i;}
 void switchChannel(int i){selected=i;currentSourceIndex=0;}
 void switchChannel(int i,int line){selected=i;currentSourceIndex=line;}
 void restart(Request request){CHOICE RESTART}
 static class Channel{String name="fixture";}Channel currentChannel(){return new Channel();}
 void abortChannelSwitchAnimation(){}void releasePlayer(boolean clear){released++;}void hideLoading(){}void showChannelBar(String n,String s){}
 void exhausted(boolean decoderFailure){boolean legacyHlsFailure=false;Channel channel=currentChannel();STOP advanced=true;}
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] args){
  for(int sdk:new int[]{14,15,16,17,19,21,25,34}){Build.VERSION.SDK_INT=sdk;
   check(hevc(false)==(sdk>=16?1:0),"actual HEVC selector allowed from MediaCodec API16");
   check(hevc(true)==0,"explicit software stays software");}
  for(String setting:new String[]{"decodeMode","channelDecodeMode","playerEngine"}){
   Check c=new Check();c.quickChannel=new Object();c.restart(new Request(setting));
   check(c.kept&&!c.openingQuickChannel&&c.selected==7,"quick target preserved: "+setting);
   c=new Check();c.restart(new Request(setting));check(c.selected==7&&c.currentSourceIndex==2,"catalog line preserved: "+setting);}
  Check c=new Check();c.quickChannel=new Object();c.exhausted(false);check(c.released==1&&!c.advanced,"failed temporary URL never advances unrelated catalog");
  c=new Check();c.exhausted(true);check(c.released==1&&!c.advanced,"decoder failure stays on target");
  c=new Check();c.exhausted(false);check(c.released==0&&c.advanced,"ordinary catalog recovery retains backup selection");
  System.out.println("PASS API14-34 HEVC gate, explicit software, quick/catalog decoder changes, failed temporary address isolation");
 }
}'''.replace('OPTION',option).replace('CHOICE',choice).replace('RESTART',restart).replace('STOP',stop)
with tempfile.TemporaryDirectory() as folder:
 p=Path(folder)/'Check.java';p.write_text(java,encoding='utf8');jdk=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
 subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8',str(p)],check=True)
 subprocess.run([str(jdk/'java.exe'),'-cp',folder,'Check'],check=True)
