package xiao.bu.tv;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicInteger;

/** Local HTTP evidence for the production headless-to-rendered session. */
final class BrowserSessionChecks {
    interface Condition { boolean ready(); }
    private static void await(Condition condition,long timeout,String label)throws Exception {
        long end=SystemClock.elapsedRealtime()+timeout;
        while(SystemClock.elapsedRealtime()<end){if(condition.ready())return;Thread.sleep(50);}
        throw new AssertionError(label);
    }
    static String run(Instrumentation test,Activity activity)throws Exception {
        if(!LiteWebPlayback.supported())return "SKIP lightweight rendering: ABI has no lightweight core\n";
        AtomicInteger pages=new AtomicInteger(),states=new AtomicInteger(),handoffs=new AtomicInteger(),runtime=new AtomicInteger();
        ServerSocket server=new ServerSocket(0,8,java.net.InetAddress.getByName("127.0.0.1"));
        Thread responder=new Thread(()->{
            while(!server.isClosed())try(Socket socket=server.accept()){
                socket.setSoTimeout(2000);
                BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),"UTF-8"));
                String request=input.readLine(),line;
                while((line=input.readLine())!=null&&!line.isEmpty()){}
                boolean media=request!=null&&request.contains("/late.m3u8");
                boolean state=request!=null&&request.contains("/state?hits=1&kept=YES");
                boolean checked=request!=null&&request.contains("/runtime-ok");
                if(state)states.incrementAndGet();if(checked)runtime.incrementAndGet();
                if(!media&&!state&&!checked)pages.incrementAndGet();
                String html="<!doctype html><html><head><style>body{background:#123456;color:white}h1{font-size:26px}</style></head><body>"
                        +"<h1 id='kept'>KEEP DOM</h1><button id='go'>选择</button><video id='v'></video><script>window.hits=(window.hits||0)+1;window.kept='YES';document.getElementById('go').onclick=function(){window.ujsOnce=(window.ujsOnce||0)+1;document.getElementById('kept').textContent='KEEP DOM '+window.ujsOnce;};</script></body></html>";
                byte[] body=(media?"#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\nsegment.ts\n":state?"ok":html).getBytes("UTF-8");
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: "+(media?"application/vnd.apple.mpegurl":"text/html; charset=utf-8")+"\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                socket.getOutputStream().write(body);
            }catch(Exception ignored){}
        },"browser-render-fixture");responder.setDaemon(true);responder.start();
        String base="http://127.0.0.1:"+server.getLocalPort();
        LiteWebPlayback[] session={null};View[] rendered={null};String[] failure={null};
        long[] renderedAt={0};
        try {
            test.runOnMainSync(()->{
                session[0]=new LiteWebPlayback(activity,new LiteWebPlayback.Host(){
                    public void stream(String url,boolean temporary){handoffs.incrementAndGet();}
                    public void status(String value){}
                    public void failure(String value){failure[0]=value;}
                    public void render(View page){renderedAt[0]=SystemClock.elapsedRealtime();rendered[0]=page;
                        ((FrameLayout)activity.findViewById(android.R.id.content)).addView(page,new FrameLayout.LayoutParams(-1,-1));}
                    public boolean playing(){return false;}public void setPaused(boolean value){}
                    public long position(){return 0;}public long duration(){return 0;}
                    public int width(){return 0;}public int height(){return 0;}
                });
                session[0].open(base+"/page","document.getElementById('go').click();");
            });
            java.lang.reflect.Field field=LiteWebPlayback.class.getDeclaredField("engine");field.setAccessible(true);
            LiteEngine engine=(LiteEngine)field.get(session[0]);
            await(()->engine.documentReadyAt()>0,15000,"DOM/UJS never became ready: "+failure[0]);
            long ready=engine.documentReadyAt();
            engine.evaluate("(function(){var b=new Uint8Array([0,97,115,109,1,0,0,0,1,7,1,96,2,127,127,1,127,3,2,1,0,7,7,1,3,97,100,100,0,0,10,9,1,7,0,32,0,32,1,106,11]);WebAssembly.instantiate(b).then(function(w){if(w.instance.exports.add(20,22)!==42||BigInt('4294967296')+1n!==4294967297n)throw Error('native runtime');return crypto.subtle.digest('SHA-256',new Uint8Array([97,98,99]));}).then(function(d){var a=new Uint8Array(d),h=Array.prototype.map.call(a,function(v){return('0'+v.toString(16)).slice(-2);}).join('');if(h!=='ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')throw Error('digest');var x=new XMLHttpRequest();x.open('GET','/runtime-ok',true);x.send();});})();");
            await(()->runtime.get()==1,5000,"QuickJS bigint/WASM/SHA256 runtime failed");
            Thread.sleep(1000);
            if(rendered[0]!=null||engine.frames()!=0)throw new AssertionError("Rendered before timeout");
            await(()->rendered[0]!=null||failure[0]!=null,12500,"10-second render timeout missing");
            if(failure[0]!=null)throw new AssertionError(failure[0]);
            long wait=renderedAt[0]-ready;
            if(wait<10000||wait>11500)throw new AssertionError("Wrong post-ready delay: "+wait);
            await(()->engine.frames()>0,6000,"Same engine produced no display list");
            await(()->{
                boolean[] text={false};test.runOnMainSync(()->{
                    LiteCanvas view=(LiteCanvas)((FrameLayout)rendered[0]).getChildAt(0);
                    try{for(java.lang.reflect.Field f:LiteCanvas.class.getDeclaredFields())if(f.getType()==org.json.JSONArray.class){f.setAccessible(true);org.json.JSONArray commands=(org.json.JSONArray)f.get(view);StringBuilder words=new StringBuilder();for(int i=0;i<commands.length();i++){org.json.JSONArray a=commands.optJSONArray(i);if(a!=null&&"text".equals(a.optString(0)))words.append(a.optString(9));}text[0]=words.toString().replace(" ","").contains("KEEPDOM1");}}
                    catch(Exception e){throw new RuntimeException(e);}
                });return text[0];
            },4000,"Retained DOM text missing from actual display list");
            test.runOnMainSync(()->{
                session[0].applyPageCover(true,"black");
                if(!session[0].revealPage())throw new AssertionError("Black cover did not reveal");
                engine.evaluate("if(window.ujsOnce===1&&window.hits===1){var x=new XMLHttpRequest();x.open('GET','/state?hits='+hits+'&kept='+kept,true);x.send();}");
            });
            await(()->states.get()==1,4000,"DOM/JS/UJS state not retained after rendering");
            int firstFrames=engine.frames();Thread.sleep(2200);
            if(engine.frames()-firstFrames>3)throw new AssertionError("Rendering exceeded 1 fps cap");
            if(pages.get()!=1)throw new AssertionError("Page unexpectedly reloaded: "+pages.get());
            test.runOnMainSync(()->engine.evaluate("document.getElementById('v').src='"+base+"/late.m3u8';document.getElementById('v').play();"));
            await(()->handoffs.get()>0||failure[0]!=null,10000,"Late sniffed stream never reached native host");
            boolean[] retired={false};test.runOnMainSync(()->retired[0]=!session[0].isRendering()&&rendered[0].getParent()==null);
            if(handoffs.get()!=1||!retired[0])
                throw new AssertionError("Native handoff did not retire the rendered page exactly once");
            return "PASS lightweight session: DOM/UJS-ready waitMs="+wait+", QuickJS bigint/WASM/SHA256, same realm/load once, UJS once, display list, 1fps cap, black reveal, late native handoff and view cleanup\n";
        }finally{
            server.close();test.runOnMainSync(()->{if(session[0]!=null)session[0].close();});
        }
    }
}
