"""Real OkHttp WebSocket handshake policy and browser identity, without network IO."""
from pathlib import Path
import os, subprocess, tempfile
root=Path(__file__).resolve().parents[1];cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
def jar(group,name):return next(p for p in (cache/group/name).rglob('*.jar') if not p.name.endswith(('-sources.jar','-javadoc.jar')))
cp=os.pathsep.join(map(str,[jar('org.json','json'),jar('com.squareup.okhttp3','okhttp'),jar('com.squareup.okio','okio')]))
java=r'''package xiao.bu.tv;
import okhttp3.*;import java.util.*;import java.util.concurrent.*;import org.json.*;
class Check {
 static void ok(boolean value){if(!value)throw new AssertionError();}
 public static void main(String[] args)throws Exception{
  ok(LiteWebSockets.boundedStartupConnect(19,true));ok(LiteWebSockets.boundedStartupConnect(15,true));
  ok(!LiteWebSockets.boundedStartupConnect(25,true));ok(!LiteWebSockets.boundedStartupConnect(19,false));
  final List<Integer> connects=new ArrayList<>(),reads=new ArrayList<>();
  OkHttpClient client=new OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS).readTimeout(8,TimeUnit.SECONDS).callTimeout(12,TimeUnit.SECONDS).addInterceptor(chain->{
   ok("Selected browser UA".equals(chain.request().header("User-Agent")));ok("https://www.gdtv.cn".equals(chain.request().header("Origin")));
   synchronized(connects){connects.add(chain.connectTimeoutMillis());reads.add(chain.readTimeoutMillis());}
   return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(400).message("Handshake fixture").body(ResponseBody.create(null,"not an upgrade")).build();
  }).build();
  try{
   for(String url:new String[]{"wss://tcdn-ws.itouchtv.cn:3800/connect","wss://other.example/connect"}){
    CountDownLatch close=new CountDownLatch(1);List<String> events=new ArrayList<>();
    LiteWebSockets sockets=new LiteWebSockets(client,new LiteWebSockets.Sink(){public void evaluate(String value){synchronized(events){events.add(value);}if(value.contains("\"type\":\"close\""))close.countDown();}});
    try{sockets.execute(new JSONObject().put("id",1).put("action","open").put("url",url),"https://www.gdtv.cn","Selected browser UA",connects.isEmpty());ok(close.await(3,TimeUnit.SECONDS));synchronized(events){ok(events.size()==2&&events.get(0).contains("\"type\":\"error\"")&&events.get(1).contains("1006"));}}finally{sockets.close();}
   }
   ok(connects.equals(Arrays.asList(3000,8000))&&reads.equals(Arrays.asList(3000,8000)));
  }finally{client.dispatcher().executorService().shutdownNow();client.connectionPool().evictAll();}
  System.out.println("PASS domain-independent legacy startup handshake deadlines, unrelated socket policy, selected UA/origin and actual failed-handshake error/close events");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
    check=Path(tmp)/'Check.java';check.write_text(java,encoding='utf8')
    build=Path(tmp)/'android/os/Build.java';build.parent.mkdir(parents=True);build.write_text('package android.os; public class Build {public static class VERSION {public static int SDK_INT=19;}}')
    jdk=Path(os.environ['JAVA_HOME'])/'bin'
    subprocess.run([str(jdk/'javac'),'-encoding','UTF-8','-cp',cp,'-d',tmp,str(build),str(root/'browser-core/litebrowser/src/main/java/xiao/bu/tv/LiteWebSockets.java'),str(check)],check=True)
    subprocess.run([str(jdk/'java'),'-cp',tmp+os.pathsep+cp,'xiao.bu.tv.Check'],check=True,timeout=10)
