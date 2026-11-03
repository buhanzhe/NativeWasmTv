from pathlib import Path
import subprocess,tempfile
root=Path(__file__).resolve().parents[1];s=(root/'app/src/main/java/xiao/bu/tv/SniffedMediaProbe.java').read_text(encoding='utf-8');a=s.index('    private static long segmentBitrate(');b=s.index('    private String readManifest(',a)
java='''import java.net.*;import java.io.*;class HeadCheck {
 static Conn next;static HttpURLConnection openConnection(String u,String p,String a,String c){return next;}
 static class Conn extends HttpURLConnection {int status,closed,disconnected;Conn(int status)throws Exception{super(new URL("http://local/"));this.status=status;}
 public int getResponseCode(){return status;}public int getContentLength(){return 3000;}public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){disconnected++;}
 public InputStream getInputStream(){return stream();}public InputStream getErrorStream(){return stream();}
 InputStream stream(){return new ByteArrayInputStream(new byte[0]){public void close(){closed++;}};}}
 METHOD
 public static void main(String[]args)throws Exception{
  next=new Conn(200);if(segmentBitrate("u","p","a","c",3)!=8000||next.closed!=1||next.disconnected!=1)throw new AssertionError("HEAD response leaked");
  next=new Conn(403);if(segmentBitrate("u","p","a","c",3)!=0||next.closed!=1||next.disconnected!=1)throw new AssertionError("Error response leaked");
  System.out.println("PASS actual HEAD bitrate success/error response closure and unchanged bitrate calculation");
 }}'''.replace('METHOD',s[a:b])
with tempfile.TemporaryDirectory()as tmp:
 f=Path(tmp)/'HeadCheck.java';f.write_text(java,encoding='utf-8');jdk='C:/Users/Administrator/.jdks/jbr-17.0.14/bin/'
 subprocess.run([jdk+'javac.exe','-d',tmp,str(f)],check=True);subprocess.run([jdk+'java.exe','-cp',tmp,'HeadCheck'],check=True)
