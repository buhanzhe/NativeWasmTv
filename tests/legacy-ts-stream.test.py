"""Run the real continuous TS transport across rotating live windows and cancellation."""
from pathlib import Path
import os, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
java=r'''package xiao.bu.tv;
import java.io.*;import java.util.*;
class Check {
 static void ok(boolean b,String why){if(!b)throw new AssertionError(why);}
 static class Source implements LegacyTsStream.Source {
  int reloads,waits;long clock;boolean active=true,stall,corrupt,finite,master,failOnce;List<String> reads=new ArrayList<>();
  public boolean active(){return active;}
  public long now(){return clock;}
  public void waitForUpdate(){waits++;clock+=1000;if(!stall&&waits==5)active=false;}
  public String playlist(String url){
   if(master&&url.endsWith("master.m3u8"))return "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nmedia.m3u8\n";
   long seq=1786488188L+(stall?0:Math.min(waits,3));reloads++;
   String s="#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:"+seq+"\n";
   for(int i=0;i<3;i++)s+="#EXTINF:2,\n"+(seq+i)+".ts?signature="+reloads+"\n";
   return s+(finite?"#EXT-X-ENDLIST\n":"");
  }
  public byte[] segment(String url)throws IOException{reads.add(url);if(failOnce){failOnce=false;throw new EOFException("truncated TS");}byte[] b=new byte[188*3];for(int i=0;i<b.length;i+=188)b[i]=0x47;if(corrupt)b[0]=0;return b;}
 }
 public static void main(String[] args)throws Exception{
  ok("64/256/5".equals(LegacyTsStream.playerHeaders().get("x-cache-config")),"bounded live cache overrides multi-megabyte vendor high water");
  ok(!LegacyTsStream.playerHeaders().containsKey("x-disconnect-at-highwatermark"),"unseekable TS connection must not disconnect for cache cycling");
  Source s=new Source();s.master=true;ByteArrayOutputStream bytes=new ByteArrayOutputStream();
  LegacyTsStream.pipe(s,"http://fixture/master.m3u8",bytes);
  ok(s.reads.size()==6&&bytes.size()==6*188*3,"continues beyond initial three segments without replaying overlap or signed URL rotations");
  ok(s.reads.get(0).contains("1786488188.ts?signature=1"),"large media sequence and signed query survive exactly");
  s=new Source();s.failOnce=true;bytes=new ByteArrayOutputStream();LegacyTsStream.pipe(s,"http://fixture/live.m3u8",bytes);
  ok(s.reads.get(0).equals(s.reads.get(1))&&s.reads.size()==7&&bytes.size()==6*188*3,"transient truncation retries the same TS without duplicating bytes");
  s=new Source();s.stall=true;bytes=new ByteArrayOutputStream();
  try{LegacyTsStream.pipe(s,"http://fixture/live.m3u8",bytes);throw new AssertionError("stalled source accepted");}
  catch(IOException expected){ok(s.clock==30000&&s.reads.size()==3,"stale playlist ends after bounded wait, never repeats media");}
  for(boolean finite:new boolean[]{false,true}){
   s=new Source();s.finite=finite;s.corrupt=!finite;bytes=new ByteArrayOutputStream();
   try{LegacyTsStream.pipe(s,"http://fixture/live.m3u8",bytes);throw new AssertionError("invalid media accepted");}
   catch(IOException expected){ok(bytes.size()==0,"finite/corrupt media rejected before any video bytes");if(finite)ok(expected instanceof LegacyTsStream.UnsupportedMediaException,"finite source can fall back to normal HLS");}
  }
  Source progressive=new Source(){int calls;public boolean streamFirstSegment(String url,OutputStream out)throws IOException{if(++calls!=1)throw new AssertionError("Only first segment streams progressively");byte[] b=segment(url);out.write(b);return true;}};
  bytes=new ByteArrayOutputStream();LegacyTsStream.pipe(progressive,"http://fixture/live.m3u8",bytes);ok(bytes.size()==6*188*3,"progressive first segment followed by validated segments without replay");
  Source brokenFirst=new Source(){public boolean streamFirstSegment(String url,OutputStream out)throws IOException{out.write(71);throw new EOFException("partial first PES");}};
  bytes=new ByteArrayOutputStream();try{LegacyTsStream.pipe(brokenFirst,"http://fixture/live.m3u8",bytes);throw new AssertionError();}catch(EOFException expected){ok(bytes.size()==1,"partial first segment is never retried into an existing decoder stream");}
  ok(ContinuousTsPolicy.defaultEnabled(1)&&!ContinuousTsPolicy.defaultEnabled(0)&&!ContinuousTsPolicy.defaultEnabled(4),"single-core default only");
  s=new Source();s.active=false;LegacyTsStream.pipe(s,"http://fixture/live.m3u8",new ByteArrayOutputStream());
  ok(s.reloads==0&&s.reads.isEmpty(),"retired stream performs no network work");
  System.out.println("Continuous TS live windows, cancellation, corruption and device routing OK");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
    p=Path(tmp)/'Check.java';p.write_text(java,encoding='utf-8');jdk=Path(os.environ['JAVA_HOME'])/'bin'
    subprocess.run([str(jdk/'javac'),'-encoding','UTF-8','-d',tmp,str(root/'app/src/main/java/xiao/bu/tv/LegacyTsStream.java'),str(root/'app/src/main/java/xiao/bu/tv/ContinuousTsPolicy.java'),str(p)],check=True)
    subprocess.run([str(jdk/'java'),'-cp',tmp,'xiao.bu.tv.Check'],check=True)
