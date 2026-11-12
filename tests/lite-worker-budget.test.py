"""Small Dalvik heaps can run native demux workers without unbounded queues."""
import os, pathlib, subprocess, tempfile
root=pathlib.Path(__file__).resolve().parents[1]
source=root/'browser-core/litebrowser/src/main/java/xiao/bu/tv/LiteWorkerBudget.java'
code=r'''package xiao.bu.tv;
class Check {
 static void ok(boolean value){if(!value)throw new AssertionError();}
 public static void main(String[] args){
  long m=1024L*1024;
  // Original 0.3 GB single-core device: retain the memory fallback.
  ok(new LiteWorkerBudget(64*m,120*m,false).count==0);
  ok(new LiteWorkerBudget(64*m,255*m,false).count==0);
  ok(new LiteWorkerBudget(64*m,256*m,false).count==1);
  LiteWorkerBudget emulator=new LiteWorkerBudget(64*m,700*m,false);
  ok(emulator.count==4&&emulator.heapBytes==32*m);
  // A real 1 MiB clone fits; oversized clones must fail before JNI copies.
  ok(emulator.packetBytes>m*8/3&&emulator.packetBytes<2*m*8/3&&emulator.queuedBytes<=8*m);
  ok(new LiteWorkerBudget(32*m,700*m,false).count==0);
  ok(new LiteWorkerBudget(256*m,700*m,true).count==0);
  LiteWorkerBudget large=new LiteWorkerBudget(128*m,700*m,false);
  ok(large.count==4&&large.heapBytes==64*m&&large.packetBytes==24*m);
  System.out.println("PASS native worker headroom, small-device fallback, binary clone budgets");
 }
}'''
with tempfile.TemporaryDirectory() as folder:
 p=pathlib.Path(folder);check=p/'Check.java';check.write_text(code,'utf8')
 subprocess.run(['javac','-d',folder,str(source),str(check)],check=True)
 subprocess.run(['java','-cp',folder,'xiao.bu.tv.Check'],check=True)
