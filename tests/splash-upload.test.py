"""Production upload streaming and sampled file decoding, with a small JVM heap."""
from pathlib import Path
import os,subprocess,tempfile
root=Path(__file__).resolve().parents[1]
server=(root/'app/src/main/java/xiao/bu/tv/LocalControlServer.java').read_text(encoding='utf-8')
wallpaper=(root/'app/src/main/java/xiao/bu/tv/PersonalBackground.java').read_text(encoding='utf-8')
def method(source,marker):
 start=source.index(marker);end=source.index('{',start)+1;depth=1
 while depth:depth+=(source[end]=='{')-(source[end]=='}');end+=1
 return source[start:end]
java=r'''import java.io.*;import java.net.*;import java.util.*;
public class SplashUploadCheck{
 static class ControlRequestException extends IOException{ControlRequestException(int status,String message){super(message);}}
 static ThreadLocal<byte[]> transferBuffer=new ThreadLocal<byte[]>(){protected byte[] initialValue(){return new byte[65536];}};
 static class Payload extends InputStream{long remaining;Payload(long n){remaining=n;}public int read(){if(remaining==0)return -1;remaining--;return 90;}public int read(byte[] b,int o,int n){if(remaining==0)return -1;int k=(int)Math.min(n,remaining);Arrays.fill(b,o,o+k,(byte)90);remaining-=k;return k;}}
 static class Counter extends OutputStream{long count;public void write(int b){count++;}public void write(byte[]b,int o,int n){check(n<=65536,"Bounded buffer");count+=n;}}
 static class Listener{File temporary;long bytes;boolean fail;File createBackgroundUploadFile()throws Exception{temporary=File.createTempFile("splash-test-",".tmp");return temporary;}String uploadBackgroundFile(File f)throws Exception{bytes=f.length();if(fail)throw new IOException("invalid image");return "ok";}}
 Listener listener=new Listener();int status;
 void send(Socket s,int code,String type,byte[] body){status=code;}
 METHODS
 static class Bitmap{enum Config{RGB_565}}
 static class BitmapFactory{static int sampled;static boolean oom;static class Options{boolean inJustDecodeBounds;int outWidth,outHeight,inSampleSize;Bitmap.Config inPreferredConfig;}static Bitmap decodeFile(String path,Options o){if(oom)throw new OutOfMemoryError();if(o.inJustDecodeBounds){o.outWidth=20000;o.outHeight=10000;return null;}sampled=o.inSampleSize;return new Bitmap();}}
 DECODE
 static void check(boolean ok,String m){if(!ok)throw new AssertionError(m);}
 public static void main(String[]args)throws Exception{
  Counter counter=new Counter();copyFixedUpload(new Payload(96L*1024*1024),counter,96L*1024*1024);check(counter.count==96L*1024*1024,"Large file without a full-size heap array");
  long size=9L*1024*1024;InputStream chunks=new SequenceInputStream(Collections.enumeration(Arrays.asList(new ByteArrayInputStream((Long.toHexString(size)+";x=1\r\n").getBytes("US-ASCII")),new Payload(size),new ByteArrayInputStream("\r\n0\r\n\r\n".getBytes("US-ASCII")))));
  SplashUploadCheck s=new SplashUploadCheck();s.receiveBackgroundUpload(new Socket(),chunks,true,0);check(s.listener.bytes==size&&s.status==200,"Chunked upload above old cap");check(!s.listener.temporary.exists(),"Success cleans temporary file");
  try{s.receiveBackgroundUpload(new Socket(),new Payload(2),false,(long)Integer.MAX_VALUE+1);throw new AssertionError("Truncated body accepted");}catch(IOException expected){}check(!s.listener.temporary.exists(),"Truncation cleans temporary file");
  s.listener.fail=true;try{s.receiveBackgroundUpload(new Socket(),new Payload(4),false,4);throw new AssertionError("Invalid image accepted");}catch(IOException expected){}check(!s.listener.temporary.exists(),"Decode failure cleans temporary file");
  check(decode(new File("sample.jpg"))!=null&&BitmapFactory.sampled==16,"Large image is sampled before decoding");
  BitmapFactory.oom=true;try{decode(new File("sample.jpg"));throw new AssertionError("OOM not converted");}catch(IOException expected){}
  System.out.println("PASS splash streaming: large fixed/chunked bodies, 24 MB heap, strict truncation, cleanup and sampled image decode");
 }
}'''
markers=['    private void receiveBackgroundUpload(', '    private static void copyFixedUpload(', '    private static void copyChunkedUpload(', '    private static String readLine(']
methods='\n'.join(method(server,m) for m in markers)
java=java.replace('METHODS',methods).replace('DECODE',method(wallpaper,'    private static Bitmap decode(File file)'))
assert 'long contentLength = 0;' in server
assert server.index('receiveBackgroundUpload(socket,input,chunkedBody,contentLength)')<server.index('if (contentLength > bodyLimit)')
activity=(root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf-8')
a=activity.index('if (full || "personalization".equals(view))');b=activity.index('if (full || "advanced".equals(view))',a)
assert '.put("groupPasswordRequired",groupPasswordRequired)' in activity[a:b]
jdk=Path(os.environ.get('JAVA_HOME','C:/Program Files/Android/Android Studio/jbr'))/'bin'
with tempfile.TemporaryDirectory() as folder:
 p=Path(folder)/'SplashUploadCheck.java';p.write_text(java,encoding='utf-8')
 subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8',str(p)],check=True)
 subprocess.run([str(jdk/'java.exe'),'-Xmx24m','-cp',folder,'SplashUploadCheck'],check=True,timeout=30)
