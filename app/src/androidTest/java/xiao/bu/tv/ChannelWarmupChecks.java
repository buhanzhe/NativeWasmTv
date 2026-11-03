package xiao.bu.tv;
import android.app.*;
import android.widget.FrameLayout;
import android.os.SystemClock;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
final class ChannelWarmupChecks {
 static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
 static void run(Instrumentation test,Activity activity,FrameLayout root)throws Exception{
  ServerSocket server=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"));
  ExecutorService peers=Executors.newCachedThreadPool();
  String base="http://127.0.0.1:"+server.getLocalPort();
  String playlist="#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\n"+base+"/segment.ts\n#EXT-X-ENDLIST\n";
  String script="function main(item){return {m3u8:"+org.json.JSONObject.quote(playlist)+",headers:{'X-Warm-Test':'preserved'},referer:'https://example.test/'};}";
  Thread accept=new Thread(()->{while(!server.isClosed())try{Socket c=server.accept();peers.execute(()->{try{BufferedReader in=new BufferedReader(new InputStreamReader(c.getInputStream(),"UTF-8"));String line=in.readLine();if(line==null)return;while((line=in.readLine())!=null&&!line.isEmpty()){}byte[] bytes=script.getBytes("UTF-8");c.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: "+bytes.length+"\r\nContent-Type: application/javascript\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));c.getOutputStream().write(bytes);c.close();}catch(Exception ignored){}});}catch(Exception done){break;}},"warm-script-fixture");accept.start();
  AtomicReference<ChannelNetworkWarmup> manager=new AtomicReference<>();AtomicReference<ChannelNetworkWarmup.Ku9Lease> lease=new AtomicReference<>();
  String source=base+"/fixture.js?id=actual";
  try{
   test.runOnMainSync(()->{ChannelNetworkWarmup m=new ChannelNetworkWarmup(activity,root);manager.set(m);m.prepare(Collections.singletonList(new ChannelNetworkWarmup.Target("Fixture",source)),"high");});
   java.lang.reflect.Field entries=ChannelNetworkWarmup.class.getDeclaredField("entries");entries.setAccessible(true);
   long end=SystemClock.uptimeMillis()+15000;boolean[] ready={false};
   while(!ready[0]&&SystemClock.uptimeMillis()<end){test.runOnMainSync(()->{try{for(Object e:((Map<?,?>)entries.get(manager.get())).values()){java.lang.reflect.Field f=e.getClass().getDeclaredField("ku9Result");f.setAccessible(true);if(f.get(e)!=null)ready[0]=true;}}catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(50);}
   check(ready[0],"Actual QuickJS neighbour did not resolve");
   test.runOnMainSync(()->{manager.get().foreground(source);lease.set(manager.get().takeKu9(source,77,new Ku9ScriptResolver.Callback(){public void onResolved(int id,Ku9ScriptResolver.Result result){}public void onFailed(int id,String reason){throw new AssertionError(reason);}}));});
   check(lease.get()!=null&&lease.get().result!=null,"Ready resolver not adopted");
   check(!lease.get().result.playlistRefresh&&"preserved".equals(lease.get().result.mediaHeaders.get("X-Warm-Test")),"Initial handoff or headers changed");
   test.runOnMainSync(()->{check(manager.get().takeKu9(source,78,null)==null,"Result consumed twice");manager.get().close();});
   java.lang.reflect.Field speculative=Ku9ScriptResolver.class.getDeclaredField("speculative");speculative.setAccessible(true);check(!speculative.getBoolean(lease.get().resolver),"Adopted resolver retained speculative policy");
   URL local=new URL(lease.get().result.url);HttpURLConnection connection=(HttpURLConnection)local.openConnection();connection.setReadTimeout(2000);
   try{check(connection.getResponseCode()==200,"Adopted playlist service was closed");BufferedReader in=new BufferedReader(new InputStreamReader(connection.getInputStream(),"UTF-8"));check("#EXTM3U".equals(in.readLine()),"Not a real local playlist");}finally{connection.disconnect();}
   Ku9SiteCache cache=new Ku9SiteCache(test.getTargetContext(),"warmup-contract");String key="isolation-"+System.nanoTime();Ku9WarmCache overlay=new Ku9WarmCache();overlay.put(cache,"fixture",key,"private",10000);check("".equals(cache.get(key)),"Speculation committed cache");check("private".equals(overlay.get(cache,"fixture",key)),"Private cache read");overlay.promote();check("private".equals(cache.get(key)),"Promotion lost cache");cache.put(key,"",1);
   android.os.Bundle status=new android.os.Bundle();status.putString("stream","PASS actual Ku9 QuickJS pre-resolution, one-use adoption, preserved headers, live local service ownership, speculative cache isolation and promotion\n");test.sendStatus(0,status);
  }finally{test.runOnMainSync(()->{if(manager.get()!=null)manager.get().close();if(lease.get()!=null)lease.get().resolver.destroy();});server.close();peers.shutdownNow();accept.join(1000);}
 }
}
