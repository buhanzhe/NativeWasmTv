package xiao.bu.tv;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.*;
import java.net.*;
import org.json.JSONObject;

/** Exercise the main APK's actual QuickJS/event bridge under response pressure. */
public final class LiteEventMemoryInstrumentation extends Instrumentation {
    private LiteWebPlayback page;
    private ServerSocket server;
    private volatile boolean serving=true;
    private volatile String failure;
    public void onCreate(Bundle arguments) { super.onCreate(arguments);start(); }
    private void serve(Socket socket) {
        try(Socket peer=socket) {
            peer.setSoTimeout(3000);
            BufferedReader input=new BufferedReader(new InputStreamReader(peer.getInputStream(),"UTF-8"));
            input.readLine();String line;while((line=input.readLine())!=null&&!line.isEmpty()){}
            ByteArrayOutputStream body=new ByteArrayOutputStream();
            try(InputStream asset=getContext().getAssets().open("xhr-event-memory.html")) {
                byte[] block=new byte[4096];int n;while((n=asset.read(block))!=-1)body.write(block,0,n);
            }
            byte[] bytes=body.toByteArray();
            peer.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            peer.getOutputStream().write(bytes);
        } catch(Exception error) { if(serving)failure=error.toString(); }
    }
    public void onStart() {
        Bundle result=new Bundle();int status=-1;
        try {
            server=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"));
            new Thread(()->{while(serving)try{serve(server.accept());}catch(Exception ignored){}},"xhr-memory-fixture").start();
            runOnMainSync(()->{
                page=new LiteWebPlayback(getTargetContext(),new LiteWebPlayback.Host(){
                    public void stream(String url,boolean temporary){failure="Unexpected media";}
                    public void status(String value){}
                    public void failure(String value){failure=value;}
                    public boolean playing(){return false;}
                    public long position(){return 0;}
                    public long duration(){return 0;}
                    public int width(){return 0;}
                    public int height(){return 0;}
                    public void setPaused(boolean paused){}
                });
                page.enableAudit();page.open("http://127.0.0.1:"+server.getLocalPort()+"/xhr-event-memory.html","");
            });
            long end=SystemClock.elapsedRealtime()+20000;JSONObject[] snapshot={null};
            do {
                runOnMainSync(()->snapshot[0]=page.auditSnapshot());
                if(snapshot[0].optJSONObject("page")!=null&&snapshot[0].optJSONObject("page").optBoolean("memoryPass"))break;
                if(failure!=null)throw new AssertionError(failure);
                Thread.sleep(100);
            } while(SystemClock.elapsedRealtime()<end);
            JSONObject actual=snapshot[0].optJSONObject("page");
            if(actual==null||!actual.optBoolean("memoryPass")||actual.optInt("delivered")!=181)
                throw new AssertionError("Actual QuickJS memory fixture failed: "+snapshot[0]);
            result.putString("stream","PASS 180 one-MiB XHR response/listener cycles; live callback preserved: "+actual);
        } catch(Throwable error) { status=0;result.putString("stream","FAIL "+android.util.Log.getStackTraceString(error)); }
        finally {
            if(page!=null)runOnMainSync(()->page.close());
            serving=false;try{if(server!=null)server.close();}catch(IOException ignored){}
            finish(status,result);
        }
    }
}
