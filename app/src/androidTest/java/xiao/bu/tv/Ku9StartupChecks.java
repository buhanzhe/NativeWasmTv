package xiao.bu.tv;

import android.app.Activity;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Network-count assertions: caching code must never mean caching signed playback URLs. */
final class Ku9StartupChecks {
    static String run(Activity activity) throws Exception {
        final AtomicInteger requests=new AtomicInteger();
        final ServerSocket server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
        final java.util.concurrent.atomic.AtomicBoolean unavailable=new java.util.concurrent.atomic.AtomicBoolean();
        final String script="function main(){return 'https://test/"+UUID.randomUUID()+"';}";
        Thread responder=new Thread(()->{
            while(!server.isClosed())try(Socket socket=server.accept()) {
                BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),"UTF-8"));
                String line;while((line=reader.readLine())!=null&&line.length()>0){}
                requests.incrementAndGet();byte[] body=script.getBytes("UTF-8");
                socket.getOutputStream().write(((unavailable.get()?"HTTP/1.1 503 Unavailable":"HTTP/1.1 200 OK")
                        +"\r\nContent-Type: text/plain\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                socket.getOutputStream().write(body);
            }catch(IOException error){if(!server.isClosed())throw new RuntimeException(error);}
        },"ku9-cache-fixture");responder.start();
        String url="http://127.0.0.1:"+server.getLocalPort()+"/k-web/ku9/js/"+UUID.randomUUID()+".js";
        StringBuilder key=new StringBuilder();for(byte b:MessageDigest.getInstance("MD5").digest(url.getBytes("UTF-8")))key.append(String.format(Locale.US,"%02x",b&255));
        File cached=new File(new File(activity.getCacheDir(),"ku9-scripts"),key+".js");
        try {
            Ku9ScriptLoader loader=new Ku9ScriptLoader(activity);
            check(script.equals(loader.load(url+"?id=first")),"initial code");
            check(script.equals(loader.load(url+"?id=second"))&&requests.get()==1,"different channels must share code download");
            check(cached.setLastModified(System.currentTimeMillis()-31*60*1000L),"expire code cache");
            check(script.equals(loader.load(url))&&requests.get()==2,"expired code must refresh");
            unavailable.set(true);
            check(cached.setLastModified(System.currentTimeMillis()-31*60*1000L),"expire again");
            check(script.equals(loader.load(url))&&requests.get()==3,"offline fallback must retain code");
            return "PASS script cache: shared across channel IDs, expiry refresh, offline fallback\n";
        }finally{server.close();responder.join(2000);cached.delete();}
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
