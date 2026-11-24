"""Five-second taps and time-based held-key preview/commit against production state."""
from pathlib import Path
import os, subprocess, tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path(os.environ["JAVA_HOME"]) / "bin"
TEST = r'''package xiao.bu.tv;
public final class RemoteSeekCheck {
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 public static void main(String[] args){
  RemoteSeekState s=new RemoteSeekState(500);
  s.begin(1,30000,120000,1000);check(s.preview(1499)==-1,"Tap became a hold");
  check(s.finish(1100,30100,false)==35100,"Right tap must add exactly 5 seconds");
  s.begin(-1,30000,120000,2000);check(s.finish(2100,30000,false)==25000,"Left tap must subtract 5 seconds");
  s.begin(1,30000,120000,3000);check(s.preview(3500)==35000,"Hold starts at five seconds");
  long first=s.preview(4100);check(first>35000,"Hold preview did not advance");
  check(s.preview(4100)==first,"Duplicate repeat events accelerated the preview");
  long next=s.preview(5300);check(next>first,"Held preview must progress with elapsed time");
  check(s.finish(5300,32000,false)==next,"Release did not commit the preview target");
  check(!s.active()&&s.finish(5400,32000,false)==-1,"Stale key-up committed twice");
  s.begin(-1,2000,120000,6000);check(s.preview(7000)==0,"Backward preview crossed zero");
  check(s.finish(7000,2000,false)==0,"Backward commit crossed zero");
  s.begin(1,119000,120000,8000);check(s.preview(10000)==119999,"Preview crossed the duration");
  check(s.finish(10000,119000,true)==-1,"Cancelled key-up sought");
  s.begin(1,10000,120000,11000);s.preview(12000);s.cancel();
  check(s.preview(13000)==-1&&s.finish(13000,10000,false)==-1,"Hidden overlay retained a seek");
  s.begin(1,10000,0,14000);check(!s.active(),"Live playback accepted a seek");
  s.begin(1,10000,120000,15000);s.preview(17000);s.begin(-1,30000,120000,17000);
  check(s.finish(17100,30000,false)==25000,"Direction change retained an old preview");
  System.out.println("PASS remote seek: exact 5s taps, time-based hold, repeat deduplication, one commit, cancellation, bounds and reset");
 }
}'''
with tempfile.TemporaryDirectory() as directory:
    folder=Path(directory)
    state=ROOT/'app/src/main/java/xiao/bu/tv/RemoteSeekState.java'
    test=folder/'RemoteSeekCheck.java';test.write_text(TEST,encoding='utf-8')
    subprocess.run([str(JAVA/'javac.exe'),'-encoding','UTF-8','-d',directory,str(state),str(test)],check=True)
    subprocess.run([str(JAVA/'java.exe'),'-cp',directory,'xiao.bu.tv.RemoteSeekCheck'],check=True)
