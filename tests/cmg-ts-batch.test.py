"""Compare old/new production TS transforms, boundaries and read overhead."""
from pathlib import Path
import subprocess
root=Path(__file__).resolve().parents[1];out=root/'.codex-tmp/ijk-packet-batch';out.mkdir(exist_ok=True)
java=r'''package xiao.bu.tv;
import java.io.*;import java.util.*;import java.util.zip.*;
public final class BatchCheck {
 static void ok(boolean b){if(!b)throw new AssertionError();}
 static class Input extends ByteArrayInputStream{int reads;final int chunk;Input(byte[] b,int chunk){super(b);this.chunk=chunk;}public synchronized int read(byte[] b,int o,int l){reads++;return super.read(b,o,Math.min(chunk,l));}}
 static int pid(byte[] p){return p.length>=376?256:-1;}
 static byte[] decode(byte[] p,int pid){for(int i=0;i<p.length;i+=188)if((((p[i+1]&31)<<8)|(p[i+2]&255))==pid)p[i+100]^=0x5a;return p;}
 static final CmgTsStream.Decoder newer=new CmgTsStream.Decoder(){public int videoPid(byte[] p){return pid(p);}public byte[] decode(byte[] p,int pid){return BatchCheck.decode(p,pid);}};
 static final ReferenceCmgTsStream.Decoder older=new ReferenceCmgTsStream.Decoder(){public int videoPid(byte[] p){return pid(p);}public byte[] decode(byte[] p,int pid){return BatchCheck.decode(p,pid);}};
 static byte[] data(int packets,int seed){Random r=new Random(seed);byte[] b=new byte[packets*188];r.nextBytes(b);for(int n=0;n<packets;n++){int i=n*188,pid=n<2?n*100:n%5==0?257:256;b[i]=0x47;b[i+1]=(byte)((pid>>8)|((n<3||n%23==0)?64:0));b[i+2]=(byte)pid;b[i+3]=(byte)(0x10|r.nextInt(16));if(n>3&&n%31==0){b[i+3]|=0x20;b[i+4]=7;}}return b;}
 public static void main(String[]args)throws Exception{
  for(int seed=0;seed<100;seed++)for(int chunk:new int[]{1,7,187,188,189,16384,65536}){
   byte[] b=data(10+new Random(seed).nextInt(1500),seed);ByteArrayOutputStream x=new ByteArrayOutputStream(),y=new ByteArrayOutputStream();List<Integer> a=new ArrayList<>(),c=new ArrayList<>();
   new ReferenceCmgTsStream(older,1024*1024).copy(new Input(b,chunk),p->{a.add(p.length);x.write(p);});
   new CmgTsStream(newer,1024*1024).copy(new Input(b,chunk),p->{c.add(p.length);y.write(p);});
   ok(a.equals(c)&&Arrays.equals(x.toByteArray(),y.toByteArray()));
  }
  byte[] b=data(1000,11);
  for(int mode=0;mode<4;mode++){byte[] bad=b.clone();int limit=1024*1024;if(mode==0)bad[188*333]=0;if(mode==1)bad=Arrays.copyOf(bad,bad.length-1);if(mode==2)bad[188*333+3]=0;if(mode==3)limit=188;
   for(boolean next:new boolean[]{false,true}){boolean failed=false;try{if(next)new CmgTsStream(newer,limit).copy(new Input(bad,65536),p->{});else new ReferenceCmgTsStream(older,limit).copy(new Input(bad,65536),p->{});}catch(IOException expected){failed=true;}ok(failed);}}
  System.out.println("PASS 700 fragmented TS comparisons: exact output, PES order/sizes, mixed audio/PSI, discontinuities and malformed/limited input");
  byte[] large=data(12000,19);long before=System.nanoTime();long checksum=0;int reads=0;for(int n=0;n<10;n++){Input input=new Input(large,16384);final CRC32 crc=new CRC32();new ReferenceCmgTsStream(older,1024*1024).copy(input,crc::update);checksum+=crc.getValue();reads+=input.reads;}long oldNs=System.nanoTime()-before;
  before=System.nanoTime();int newReads=0;for(int n=0;n<10;n++){Input input=new Input(large,16384);final CRC32 crc=new CRC32();new CmgTsStream(newer,1024*1024).copy(input,crc::update);checksum-=crc.getValue();newReads+=input.reads;}ok(checksum==0);
  System.out.println("OldMs="+oldNs/1000000+" newMs="+(System.nanoTime()-before)/1000000+" oldReads="+reads+" newReads="+newReads);
 }
}'''
(out/'BatchCheck.java').write_text(java,encoding='utf-8')
subprocess.run(['javac','--release','8','-encoding','UTF-8','-d',str(out),str(root/'app/src/main/java/xiao/bu/tv/CmgTsStream.java'),str(root/'tests/fixtures/cmg-ts-reference.java'),str(out/'BatchCheck.java')],check=True)
subprocess.run(['java','-cp',str(out),'xiao.bu.tv.BatchCheck'],check=True,timeout=45)
