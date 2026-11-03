"""Exercise denied telemetry, HLS-only options and bounded 4K diagnostics."""
from pathlib import Path
import os,subprocess,tempfile
root=Path(__file__).resolve().parents[1]
s=(root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf8')
start=s.index('    private float sampleSystemLoadAverage()')
end=s.index('\n    private static boolean isCpuDirectoryName',start)
load=s[start:end]
start=s.index('        if (isResolvedHlsSource(streamUrl))\n            nextPlayer.setOption')
end=s.index(';',start)+1
option=s[start:end]
java='''package xiao.bu.tv;
import java.io.IOException;
class OverheadCheck {
 static class File {File(String name){}}
 boolean loadAverageCpuUnavailable,hls;int reads,failure;String text="4.0 2 1 1/100 4";
 String readSmallAsciiFile(File f)throws IOException {reads++;if(failure==1)throw new IOException("EACCES");if(failure==2)throw new SecurityException();return text;}
 LOAD
 static class IjkMediaPlayer {static int OPT_CATEGORY_FORMAT=1;}
 static class Player {int options,value;void setOption(int category,String key,int v){if(!key.equals("live_start_index"))throw new AssertionError();options++;value=v;}}
 Player nextPlayer=new Player();boolean isResolvedHlsSource(String url){return hls;}
 void configure(int liveStartIndex){String streamUrl="http://fixture/stream";OPTION}
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  for(int failure:new int[]{1,2}){
   OverheadCheck a=new OverheadCheck();a.failure=failure;
   for(int i=0;i<1000;i++)check(a.sampleSystemLoadAverage()==-1);
   check(a.reads==1&&a.loadAverageCpuUnavailable);
  }
  OverheadCheck a=new OverheadCheck();a.text="invalid";check(a.sampleSystemLoadAverage()==-1&&!a.loadAverageCpuUnavailable);
  a.text="4.0 2 1";check(a.sampleSystemLoadAverage()==400f/Runtime.getRuntime().availableProcessors()&&a.reads==2);
  a.configure(-3);check(a.nextPlayer.options==0);a.hls=true;a.configure(-2);check(a.nextPlayer.options==1&&a.nextPlayer.value==-2);
  a.configure(0);check(a.nextPlayer.options==2&&a.nextPlayer.value==0);
  HighResolutionLogPolicy p=new HighResolutionLogPolicy();
  for(int i=0;i<10000;i++)check(!p.shouldLog(1,1920,1080,false,i));
  check(p.shouldLog(1,3840,2160,false,10000));check(!p.shouldLog(1,3840,2160,false,24999));
  check(p.shouldLog(1,3840,2160,false,25000));
  check(!p.shouldLog(1,3840,2160,true,25999));check(p.shouldLog(1,3840,2160,true,26000));
  check(!p.shouldLog(1,3840,2160,false,26999));check(p.shouldLog(1,3840,2160,false,27000));
  check(p.shouldLog(2,3840,2160,false,27001));
  check(p.shouldLog(2,3840,2160,false,5)); // Defensive clock reset.
  int count=0;p=new HighResolutionLogPolicy();
  for(int t=0;t<60000;t+=1)if(p.shouldLog(1,3840,2160,false,t))count++;
  check(count==4);
  System.out.println("PASS 1000 denied reads attempted once, readable/malformed telemetry, HLS-only demux options and 60000 ticks limited to four 4K log lines");
 }
}'''.replace('LOAD',load).replace('OPTION',option)
with tempfile.TemporaryDirectory() as temp:
 p=Path(temp)/'OverheadCheck.java';p.write_text(java,encoding='utf8')
 policy=root/'app/src/main/java/xiao/bu/tv/HighResolutionLogPolicy.java'
 jdk=Path(os.environ.get('JAVA_HOME',root/'.codex-tmp/jdk8/runtime/jdk8u504-b01'))/'bin'
 subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',temp,str(policy),str(p)],check=True)
 subprocess.run([str(jdk/'java.exe'),'-cp',temp,'xiao.bu.tv.OverheadCheck'],check=True)
