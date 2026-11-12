"""Same-task pause/play must survive an asynchronously applied decoder state."""
from pathlib import Path
import subprocess, tempfile

root=Path(__file__).resolve().parents[1]
source=(root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text('utf8')
start=source.index('            public void setPaused(boolean paused)')
end=source.index('            public long position()',start)
method=source[start:end]
java=r'''import java.util.*;
class Check {
 static final class Decoder {
  boolean playing=true; List<Boolean> pending=new ArrayList<>();
  boolean isPlaying(){return playing;}
  void pause(){pending.add(false);} void start(){pending.add(true);}
  void drain(){for(boolean state:pending)playing=state;pending.clear();}
 }
 Decoder player=new Decoder(); boolean prepared=true,owned=true;
 boolean current(){return owned;}
 METHOD
 static void check(boolean value){if(!value)throw new AssertionError();}
 public static void main(String[] args){
  Check host=new Check();
  host.setPaused(true);host.setPaused(false);
  check(host.player.isPlaying());host.player.drain();
  check(host.player.isPlaying());
  host.setPaused(false);host.setPaused(true);host.player.drain();
  check(!host.player.isPlaying());
  host.owned=false;host.setPaused(false);host.player.drain();
  check(!host.player.isPlaying());
  host.owned=true;host.prepared=false;host.setPaused(false);
  check(host.player.pending.isEmpty());
 }
}'''.replace('METHOD',method)
with tempfile.TemporaryDirectory() as directory:
    path=Path(directory)/'Check.java';path.write_text(java,'utf8')
    subprocess.run(['javac','--release','8',str(path)],check=True)
    subprocess.run(['java','-cp',directory,'Check'],check=True)
print('PASS queued decoder intents, manual pause and stale-channel ownership')
