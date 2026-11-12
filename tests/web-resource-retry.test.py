"""Exercise the actual provider client against dead-first DNS, HTTP denial and deadlines."""
import pathlib,os,subprocess,tempfile
root=pathlib.Path(__file__).resolve().parents[1]
source=(root/'app/src/main/java/xiao/bu/tv/WebResourceProvider.java').read_text('utf8')
def method(start):
 a=source.index(start);b=source.index('{',a)+1;depth=1
 while depth:depth+=(source[b]=='{')-(source[b]=='}');b+=1
 return source[a:b]
methods='\n'.join(method(x) for x in ['    private OkHttpClient guarded(', '    private static boolean allowedType('])
cache=pathlib.Path.home()/'.gradle/caches/modules-2/files-2.1'
jars=[next(p for p in (cache/g/a).rglob('*.jar') if not p.name.endswith(('-sources.jar','-javadoc.jar'))) for g,a in [('com.squareup.okhttp3','okhttp'),('com.squareup.okio','okio')]]
code=r'''package xiao.bu.tv;
import okhttp3.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import java.net.*;import java.io.*;import com.sun.net.httpserver.*;
class Check {
 Cache cache=null;static final String PUBLIC="X-Test-Public";
 METHODS
 static void ok(boolean value){if(!value)throw new AssertionError();}
 public static void main(String[] args)throws Exception {
  AtomicInteger hits=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  ExecutorService workers=Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;});server.setExecutor(workers);
  server.createContext("/",exchange->{hits.incrementAndGet();try{if(exchange.getRequestURI().getPath().equals("/slow.js"))Thread.sleep(500);byte[] data="ok".getBytes("UTF-8");exchange.getResponseHeaders().add("Content-Type","application/javascript");exchange.sendResponseHeaders(exchange.getRequestURI().getPath().equals("/denied.js")?401:200,data.length);exchange.getResponseBody().write(data);}catch(Exception ignored){}finally{exchange.close();}});server.start();
  OkHttpClient base=new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(host->Arrays.asList(InetAddress.getByName("127.0.0.2"),InetAddress.getByName("127.0.0.1"))).connectTimeout(200,TimeUnit.MILLISECONDS).readTimeout(1,TimeUnit.SECONDS).retryOnConnectionFailure(false).build();
  OkHttpClient client=new Check().guarded(base);String url="http://dual.example:"+server.getAddress().getPort();
  try {
   boolean refused=false;try(Response response=base.newCall(new Request.Builder().url(url+"/script.js").build()).execute()){}catch(IOException expected){refused=true;}ok(refused&&hits.get()==0);
   Call call=client.newCall(new Request.Builder().url(url+"/script.js").build());call.timeout().timeout(700,TimeUnit.MILLISECONDS);try(Response response=call.execute()){ok(response.code()==200&&response.body().string().equals("ok"));}ok(hits.get()==1);
   try(Response response=client.newCall(new Request.Builder().url(url+"/denied.js").build()).execute()){ok(response.code()==401);}ok(hits.get()==2);
   long start=System.nanoTime();call=client.newCall(new Request.Builder().url(url+"/slow.js").build());call.timeout().timeout(100,TimeUnit.MILLISECONDS);boolean timed=false;try(Response response=call.execute()){}catch(IOException expected){timed=true;}ok(timed&&TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<600);
   System.out.println("PASS actual provider GET client: alternate DNS route, HTTP denial is not retried, one bounded overall deadline");
  }finally{client.dispatcher().cancelAll();client.connectionPool().evictAll();base.connectionPool().evictAll();server.stop(0);workers.shutdownNow();}
 }
}'''.replace('METHODS',methods)
with tempfile.TemporaryDirectory() as folder:
 p=pathlib.Path(folder);(p/'Check.java').write_text(code,'utf8');cp=os.pathsep.join(map(str,jars))
 subprocess.run(['javac','-encoding','UTF-8','-cp',cp,'-d',folder,str(p/'Check.java')],check=True)
 subprocess.run(['java','-cp',folder+os.pathsep+cp,'xiao.bu.tv.Check'],check=True,timeout=20)
