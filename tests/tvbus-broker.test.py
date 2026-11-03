"""Exercise the actual Java decoder and binary broker relay against a local HTTP server."""
from pathlib import Path
import subprocess, tempfile
root = Path(__file__).resolve().parents[1]
files = {
'android/util/Log.java': '''package android.util;public class Log{public static int i(String t,String m){return 0;}public static int w(String t,String m){return 0;}}''',
'xiao/bu/tv/TvbusLiveConfig.java': '''package xiao.bu.tv;class TvbusLiveConfig {
static String online(String s)throws java.io.IOException{try{java.net.URI u=new java.net.URI(s);if((u.getScheme().equals("http")||u.getScheme().equals("https"))&&u.getHost()!=null)return s;}catch(Exception e){}throw new java.io.IOException("Invalid URL");}}''',
'xiao/bu/tv/NetworkClient.java': '''package xiao.bu.tv;class NetworkClient {static java.net.HttpURLConnection openHttp1(java.net.URL u,int t)throws java.io.IOException{return (java.net.HttpURLConnection)u.openConnection();}}''',
'xiao/bu/tv/BrokerCheck.java': r'''package xiao.bu.tv;
import java.net.*;import java.io.*;import java.util.*;import java.util.concurrent.atomic.*;import com.sun.net.httpserver.HttpServer;
public class BrokerCheck {
static void check(boolean b){if(!b)throw new AssertionError();}
interface Action{void run()throws Exception;}
static void reject(Action action)throws Exception{try{action.run();throw new AssertionError("accepted bad address");}catch(IOException expected){}}
static byte[] read(InputStream i)throws Exception{ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] b=new byte[1024];int n;while((n=i.read(b))!=-1)o.write(b,0,n);return o.toByteArray();}
public static void main(String[] args)throws Exception {
 String address="tvbus://38TRb4vQYyBPj58zv1KcJv99R91XqmVfsdVpqmKkTZHoW8xd1k";
 check(TvbusAddress.decode(address).equals("sop://127.0.0.1:53812/6000"));
 check(TvbusAddress.decode("tvbus://8NLd2KqJ344hKdRuGDAYc83JBWr6yihTGB8GhoswrKDXE3KGA6").equals("sop://broker.6868a.cc:443/23111"));
 check(TvbusAddress.matches("SOP://server/123"));check(!TvbusAddress.matches("http://server/123"));
 reject(()->TvbusAddress.decode("tvbus://invalid0"));reject(()->TvbusAddress.decode("tvbus://111"));
 String localHelper;
 try(ServerSocket helper=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
  localHelper="sop://127.0.0.1:"+helper.getLocalPort()+"/6000";
  check(TvbusAddress.route(localHelper,"").broker.equals("http://127.0.0.1:"+helper.getLocalPort()));
 }
 final String closedHelper=localHelper;reject(()->TvbusAddress.route(closedHelper,""));
 reject(()->TvbusAddress.route("sop://host:80/6000@app",""));
 reject(()->TvbusAddress.route("sop://user:pass@host/123",""));
 check(TvbusAddress.route("sop://host:443/123","").broker.equals("https://host:443"));
 byte[] body={0,1,2,-1,0,4},reply={-1,0,5,6,7};AtomicInteger calls=new AtomicInteger();
 HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
 com.sun.net.httpserver.HttpHandler handler=e->{try{
  check(e.getRequestMethod().equals("POST"));check(e.getRequestHeaders().getFirst("Content-Type").equals("application/vnd.brk"));
  check(e.getRequestHeaders().getFirst("X-Message-Seed").equals("unchanged-seed"));
  check(Arrays.equals(read(e.getRequestBody()),body));calls.incrementAndGet();
  e.getResponseHeaders().set("X-Message-Seed","upstream-seed");e.sendResponseHeaders(200,reply.length);e.getResponseBody().write(reply);
 }catch(Exception error){throw new RuntimeException(error);}finally{e.close();}};
 server.createContext("/6000",handler);server.createContext("/v1/new_peer",handler);
 server.createContext("/v1/unavailable",e->{e.sendResponseHeaders(503,-1);e.close();});server.start();
 String upstream="http://127.0.0.1:"+server.getAddress().getPort();
 String local;
 try(TvbusBroker relay=new TvbusBroker(TvbusAddress.route(address,upstream))) {
  local=relay.address().replace("sop://","http://");
  HttpURLConnection c=(HttpURLConnection)new URL(local).openConnection();c.setDoOutput(true);c.setRequestProperty("X-Message-Seed","unchanged-seed");
  c.getOutputStream().write(body);check(c.getResponseCode()==200);check(Arrays.equals(read(c.getInputStream()),reply));
  check(c.getHeaderField("X-Message-Seed").equals("upstream-seed"));c.disconnect();
  c=(HttpURLConnection)new URL(local.replace("/6000","/v1/new_peer")).openConnection();c.setDoOutput(true);c.setRequestProperty("X-Message-Seed","unchanged-seed");c.getOutputStream().write(body);
  check(c.getResponseCode()==200);check(Arrays.equals(read(c.getInputStream()),reply));c.disconnect();
  c=(HttpURLConnection)new URL(local.replace("/6000","/v1/unavailable")).openConnection();c.setDoOutput(true);c.getOutputStream().write(body);
  check(c.getResponseCode()==503);check(relay.failureDetail().contains("503"));c.disconnect();
  c=(HttpURLConnection)new URL(local.replace("/6000","/other")).openConnection();c.setDoOutput(true);c.getOutputStream().write(body);check(c.getResponseCode()==404);c.disconnect();
  c=(HttpURLConnection)new URL(local).openConnection();check(c.getResponseCode()==405);c.disconnect();check(calls.get()==2);
 }
 server.stop(0);reject(()->new URL(local).openConnection().getInputStream());
 System.out.println("PASS TVBus address decoding, missing upstream, SOP routes, unchanged binary/seed broker relay, channel isolation and shutdown");
}}'''}
with tempfile.TemporaryDirectory() as temporary:
    temp = Path(temporary)
    for name, body in files.items():
        p = temp / name; p.parent.mkdir(parents=True, exist_ok=True); p.write_text(body, encoding='utf-8')
    sources = [*temp.rglob('*.java'), *(root / 'app/src/main/java/xiao/bu/tv' / name for name in ('TvbusAddress.java','TvbusBroker.java'))]
    subprocess.run(['javac','-encoding','UTF-8','-d',temporary,*map(str,sources)],check=True)
    subprocess.run(['java','-cp',temporary,'xiao.bu.tv.BrokerCheck'],check=True,timeout=25)
