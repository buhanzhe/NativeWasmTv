from pathlib import Path
import subprocess,tempfile
root=Path(__file__).resolve().parents[1]
source=root/'app/src/main/java/xiao/bu/tv/InterlacedDecodeMonitor.java'
main=(root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf-8')
a=main.index('    private boolean shouldUseSoftwareDecode()');b=main.index('    private static String sanitizeDecodeMode',a)
policy=main[a:b]
a=main.index('    private String interlacedPreferenceKey(');b=main.index('    private boolean shouldUseSoftwareDecode()',a)
classkey=main[a:b]
a=main.index('final boolean softwareDecode = DECODE_MODE_SOFTWARE');b=main.index(';',a)
choice=main[a:b].split('=',1)[1].strip()
check=r"""package xiao.bu.tv;
class MonitorCheck {
 static class Build {static String FINGERPRINT="device-firmware";static class VERSION {static int SDK_INT;}static class VERSION_CODES {static final int JELLY_BEAN=16,KITKAT=19;}}
 static final String DECODE_MODE_SOFTWARE="software",DECODE_MODE_AUTO="auto";
 static class Description {int bitDepth=8;}
 static class PlaybackDebugStats {String videoCodec="H.264",videoDecoderName="OMX.avc";Description videoDescription=new Description();}
 static class MediaTrackSelection {static String urlKey(String type,String value){return type+value;}}
 String hardwareDecoder="auto";
 CLASSKEY
 String decodeMode="auto";boolean autoSoftwareDecode=true,hintedSoftware;
 int missingCodecSoftwareRetryRequestId=-1,sourceRequestId=1;
 POLICY
 float rememberedHardwareFps;
 boolean choose(boolean forceSoftwareDecode){return CHOICE;}

 static void check(boolean value){if(!value)throw new AssertionError();}
 static boolean run(InterlacedDecodeMonitor m,Object p,int from,int to,float fps,boolean healthy,int step){
  boolean result=false;for(int i=from;i<=to;i++)result |= m.sample(p,1000L+i*1000L,i*(long)step,healthy,fps);return result;
 }
 public static void main(String[] args){
  MonitorCheck settings=new MonitorCheck();
  PlaybackDebugStats avc=new PlaybackDebugStats(),same=new PlaybackDebugStats();
  check(settings.interlacedPreferenceKey(avc).equals(settings.interlacedPreferenceKey(same)));
  same.videoCodec="H.265";check(!settings.interlacedPreferenceKey(avc).equals(settings.interlacedPreferenceKey(same)));
  same.videoCodec="H.264";same.videoDescription.bitDepth=10;
  check(!settings.interlacedPreferenceKey(avc).equals(settings.interlacedPreferenceKey(same)));
Build.VERSION.SDK_INT=19;
  check(!settings.shouldUseSoftwareDecode()&&!settings.choose(true));
  settings.rememberedHardwareFps=25f;check(!settings.choose(false));
  settings.decodeMode="hardware";settings.rememberedHardwareFps=0f;check(!settings.choose(false));
  settings.decodeMode="software";check(settings.choose(false));
  settings.decodeMode="auto";Build.VERSION.SDK_INT=25;check(settings.choose(false));
  settings.rememberedHardwareFps=0f;check(settings.choose(true));
  Object p=new Object();InterlacedDecodeMonitor m=new InterlacedDecodeMonitor();
  check(!run(m,p,0,7,25,true,1000));check(run(m,p,8,8,25,true,1000));
  check(!run(m,p,9,60,25,true,1000)); // one attempt per player
  m=new InterlacedDecodeMonitor();check(!run(m,p,0,60,50,true,1000));
  m=new InterlacedDecodeMonitor();check(!run(m,p,0,60,25,false,1000));
  m=new InterlacedDecodeMonitor();check(!run(m,p,0,60,25,true,500)); // slow clock/network
  m=new InterlacedDecodeMonitor();check(!run(m,p,0,60,0,true,1000));
  m=new InterlacedDecodeMonitor();check(!run(m,p,0,6,25,true,1000));
  check(!run(m,p,7,7,50,true,1000));check(!run(m,p,8,12,25,true,1000));
  check(!run(m,new Object(),32,32,25,true,1000)); // channel change resets warmup
  System.out.println("PASS warmup, sustained deficit, full rate, buffering, clock stalls, zero FPS, recovery and player changes");
 }
}
"""
check=check.replace('POLICY',policy).replace('CHOICE',choice).replace('CLASSKEY',classkey)
with tempfile.TemporaryDirectory() as folder:
 p=Path(folder);(p/'MonitorCheck.java').write_text(check)
 subprocess.run(['javac','-d',folder,str(source),str(p/'MonitorCheck.java')],check=True)
 subprocess.run(['java','-cp',folder,'xiao.bu.tv.MonitorCheck'],check=True)
