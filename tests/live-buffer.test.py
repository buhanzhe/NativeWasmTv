"""Common A/V windows and overlay idempotence; actual playback is device-tested."""
from pathlib import Path
import subprocess,tempfile,hashlib
root=Path(__file__).resolve().parents[1]
jdk=Path('C:/Users/Administrator/.jdks/jbr-17.0.14/bin')
java='''package xiao.bu.tv;
class LiveBufferCheck {
 static void equal(long actual,long expected){if(actual!=expected)throw new AssertionError(actual+" != "+expected);}
 public static void main(String[] args){
  equal(LiveBufferWindow.duration(6000,4000,true,true),4000);
  equal(LiveBufferWindow.duration(6000,0,true,true),0);
  equal(LiveBufferWindow.duration(6000,0,true,false),6000);
  equal(LiveBufferWindow.duration(0,4000,false,true),4000);
  equal(LiveBufferWindow.duration(6000,4000,false,false),0);
  equal(LiveBufferWindow.duration(-1,4000,true,true),0);
  equal(LiveBufferWindow.seekMaximum(0),0);
  equal(LiveBufferWindow.seekMaximum(249),0);
  equal(LiveBufferWindow.seekMaximum(5000),4750);
  equal(LiveBufferWindow.clamp(9000,5000),4750);
  equal(LiveBufferWindow.clamp(-200,5000),0);
  equal(LiveBufferWindow.clamp(2500,5000),2500);
  System.out.println("PASS 12 live buffer window/track/clamp cases");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
    p=Path(tmp)/'LiveBufferCheck.java';p.write_text(java,encoding='utf8')
    subprocess.run([str(jdk/'javac.exe'),'--release','8','-d',tmp,str(root/'app/src/main/java/xiao/bu/tv/LiveBufferWindow.java'),str(p)],check=True)
    subprocess.run([str(jdk/'java.exe'),'-cp',tmp,'xiao.bu.tv.LiveBufferCheck'],check=True)
tree=root/'.codex-tmp/ijkplayer-0.8.8'
if tree.exists():
    files=[tree/'ijkmedia/ijkplayer'/name for name in ['ff_ffplay.c','ff_ffplay_def.h']]
    before=[hashlib.sha256(p.read_bytes()).hexdigest() for p in files]
    subprocess.run(['node',str(root/'tools/ijk/apply-live-buffer-patches.cjs'),str(tree)],check=True)
    subprocess.run(['node',str(root/'tools/ijk/apply-ntv-patches.cjs'),str(tree)],check=True)
    after=[hashlib.sha256(p.read_bytes()).hexdigest() for p in files]
    assert before==after,'The full overlay pipeline must not duplicate native buffer hooks'
    print('PASS native live-buffer/full-overlay rerun is byte-identical')
