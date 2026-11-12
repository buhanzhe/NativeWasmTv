"""Run the actual ICS live-completion compatibility gate, with bounded retries."""
from pathlib import Path
import os, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
source=(root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf-8')
a=source.index('    private boolean retryLegacyHlsCompatibility(');b=source.index('{',a)+1;depth=1
while depth:
    depth+=(source[b]=='{')-(source[b]=='}');b+=1
method=source[a:b]
java=r'''import java.io.*;
class Check {
 static class Build {static class VERSION {static int SDK_INT=15;}}
 static class Channel {boolean radio;String name="live";}
 static class AndroidMediaPlayer {long getCurrentPosition(){return 24000;}long getDuration(){return 0;}int getVideoWidth(){return 960;}int getVideoHeight(){return 540;}}
 static class Proxy {boolean finite;boolean finiteMedia(){return finite;}}
 static class AppLog {static void w(String t,String s){}static void w(String t,String s,Throwable e){}}
 static final String TAG="test";
 static boolean LEGACY_TS_COMPATIBILITY_ENABLED;boolean continuousTsEnabled=true;
 int playRequestId=7,systemFirstFrameRequestId=-1,starts,recoveries;boolean software,systemVideo;
 Channel legacyHlsCompatibilityChannel;Object catchupProgram;Proxy proxy=new Proxy();AndroidMediaPlayer player=new AndroidMediaPlayer();
 void showLoading(String n,String s){}
 void hideLoading(){}void showChannelBar(String n,String s){}
 void startAndroidMediaPlayer(Channel c,String u,boolean soft,boolean video)throws IOException{starts++;software=soft;systemVideo=video;}
 void recoverStalledPlayback(int id,AndroidMediaPlayer p,String r){recoveries++;}
 METHOD
 static void ok(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void main(String[]args){
  Check c=new Check();Channel channel=new Channel();
  ok(!c.retryLegacyHlsCompatibility(7,c.player,channel,"signed-url",true),"disabled compatibility gate prevents retries");
  LEGACY_TS_COMPATIBILITY_ENABLED=true;
  ok(c.retryLegacyHlsCompatibility(7,c.player,channel,"signed-url",true),"legacy live EOS without frame retries");
  ok(c.starts==1&&!c.software&&c.systemVideo,"retains framework hardware, never forced software");
  ok(!c.retryLegacyHlsCompatibility(7,c.player,channel,"signed-url",true)&&c.starts==1,"one compatibility retry per channel");
  for(int mode=0;mode<8;mode++){
   c=new Check();channel=new Channel();Build.VERSION.SDK_INT=15;
   if(mode==0)Build.VERSION.SDK_INT=16;
   if(mode==1)c.proxy.finite=true;
   if(mode==2)c.systemFirstFrameRequestId=7;
   if(mode==3)c.catchupProgram=new Object();
   if(mode==4)channel.radio=true;
   if(mode==5)c.proxy=null;
   ok(!c.retryLegacyHlsCompatibility(mode==6?6:7,c.player,channel,"url",mode!=7),"unrelated completion excluded: "+mode);
   ok(c.starts==0,"unrelated backend unchanged");
  }
  System.out.println("Legacy HLS compatibility guards OK");
 }
}'''.replace('METHOD',method)
with tempfile.TemporaryDirectory() as tmp:
    p=Path(tmp)/'Check.java';p.write_text(java,encoding='utf-8');jdk=Path(os.environ['JAVA_HOME'])/'bin'
    subprocess.run([str(jdk/'javac'),'-encoding','UTF-8','-d',tmp,str(p)],check=True)
    subprocess.run([str(jdk/'java'),'-cp',tmp,'Check'],check=True)
