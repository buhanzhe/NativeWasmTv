package xiao.bu.tv;
import java.net.*;
import java.io.*;
import org.json.*;
public final class VodProbeTest {
    static int count;
    static void check(boolean b,String s){count++;if(!b)throw new AssertionError(s);}
    static JSONObject run(final String mode) throws Exception {
        VodProbe probe=new VodProbe("https://example.invalid/master.m3u8?signature=unchanged",new TvBoxService.Transport(){
            public HttpURLConnection open(URL url)throws IOException {
                String path=url.getPath();byte[] body;
                if(path.equals("/master.m3u8")) {
                    check(url.getQuery().equals("signature=unchanged"),"query preserved");
                    body=(mode.equals("html")?"<html>no</html>":"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nchild.m3u8\n").getBytes("UTF-8");
                } else if(path.equals("/child.m3u8")) body=("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:1,\npart.ts\n#EXT-X-ENDLIST").getBytes("UTF-8");
                else if(path.equals("/key")) body=new byte[mode.equals("badkey")?17:16];
                else body=new byte[65536];
                final byte[] bytes=body;
                return new HttpURLConnection(url){public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}
                    public int getResponseCode(){return 200;}public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}};
            }
        });
        probe.start();long until=System.currentTimeMillis()+3000;while(probe.running()&&System.currentTimeMillis()<until)Thread.sleep(5);
        check(!probe.running(),"probe terminates");return probe.state();
    }
    public static void main(String[] args)throws Exception {
        JSONObject good=run("good");check(good.getString("status").equals("sample_ok"),"master key segment sample");
        check(!good.toString().contains("signature"),"no URL leakage");
        JSONObject bad=run("badkey");check(bad.getString("status").equals("failed")&&bad.getString("stage").equals("key"),"invalid key fails");
        check(run("html").getString("status").equals("failed"),"HTML is not HLS");
        System.out.println("PASS "+count+" media probe assertions");
    }
}
