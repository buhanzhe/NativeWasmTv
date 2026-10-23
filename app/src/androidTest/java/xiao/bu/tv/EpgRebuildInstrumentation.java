package xiao.bu.tv;

import android.app.Instrumentation;
import android.os.Bundle;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real HTTP/XML/SQLite regression for manual saves that retain the same URL. */
public final class EpgRebuildInstrumentation extends Instrumentation {
    private volatile String title="Old programme";
    private volatile boolean fail,delay;
    private final AtomicInteger requests=new AtomicInteger(),conditional=new AtomicInteger(),noCache=new AtomicInteger();
    private volatile CountDownLatch entered,release;
    private ServerSocket server;
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static Object field(Object v,String name)throws Exception{java.lang.reflect.Field f=v.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(v);}
    private void serve(Socket client){
        try(Socket socket=client){
            BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream(),"UTF-8"));
            String request=in.readLine(),line;boolean head=request.startsWith("HEAD ");
            while((line=in.readLine())!=null&&!line.isEmpty()){
                String lower=line.toLowerCase(Locale.US);
                if(lower.startsWith("if-none-match:")||lower.startsWith("if-modified-since:"))conditional.incrementAndGet();
                if(lower.equals("cache-control: no-cache"))noCache.incrementAndGet();
            }
            requests.incrementAndGet();String value=title;
            if(delay){delay=false;entered.countDown();release.await(10,TimeUnit.SECONDS);}
            java.text.SimpleDateFormat date=new java.text.SimpleDateFormat("yyyyMMddHHmmss Z",Locale.US);
            long now=System.currentTimeMillis();
            String xml="<tv><channel id=\"rebuild_test\"><display-name>Rebuild Test</display-name></channel><programme channel=\"rebuild_test\" start=\""+date.format(new Date(now-60000))+"\" stop=\""+date.format(new Date(now+3600000))+"\"><title>"+value+"</title></programme></tv>";
            byte[] body=xml.getBytes("UTF-8");
            OutputStream out=socket.getOutputStream();
            out.write(((fail?"HTTP/1.1 500 Failed":"HTTP/1.1 200 OK")+"\r\nContent-Type: application/xml\r\nETag: \"same-tag\"\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            if(!head)out.write(body);out.flush();
        }catch(Exception ignored){}
    }
    private void refresh(EpgManager manager,String source,boolean force,boolean fetch)throws Exception{
        CountDownLatch done=new CountDownLatch(1);
        if(force)manager.rebuild(new String[]{source},fetch,done::countDown);
        else manager.refresh(new String[]{source},done::countDown);
        check(done.await(15,TimeUnit.SECONDS),"Refresh timeout");
    }
    private int rows(EpgManager manager,String table)throws Exception{
        android.database.sqlite.SQLiteOpenHelper helper=(android.database.sqlite.SQLiteOpenHelper)field(manager,"database");
        android.database.Cursor c=helper.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM "+table,null);
        try{c.moveToFirst();return c.getInt(0);}finally{c.close();}
    }
    @Override public void onStart(){
        Bundle result=new Bundle();EpgManager manager=null;
        try{
            server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
            Thread accepting=new Thread(()->{while(!server.isClosed())try{Socket socket=server.accept();Thread t=new Thread(()->serve(socket));t.setDaemon(true);t.start();}catch(IOException ignored){}});
            accepting.setDaemon(true);accepting.start();
            String source="http://127.0.0.1:"+server.getLocalPort()+"/guide.xml";
            Channel channel=new Channel("1","Rebuild Test","rebuild","https://stream.invalid",null,null,null,"rebuild_test");
            manager=new EpgManager(getTargetContext());
            refresh(manager,source,true,true);
            check("Old programme".equals(manager.programsFor(channel).get(0).title),"Initial load");
            int before=requests.get();title="New programme";manager.refresh(new String[]{source},()->{});
            Thread.sleep(200);check(requests.get()==before,"Automatic same-day caching changed");
            refresh(manager,source,true,true);
            check(requests.get()==before+1,"Same-URL manual rebuild skipped network");
            check("New programme".equals(manager.programsFor(channel).get(0).title),"Old memory guide survived");
            check(rows(manager,"programmes")==1&&rows(manager,"sources")==1,"Database was not replaced");
            check(conditional.get()==0&&noCache.get()>=2,"Conditional or cached response reused");

            title="Superseded programme";delay=true;entered=new CountDownLatch(1);release=new CountDownLatch(1);
            CountDownLatch old=new CountDownLatch(1);manager.rebuild(new String[]{source},true,old::countDown);
            check(entered.await(5,TimeUnit.SECONDS),"Delayed request did not start");
            title="Latest programme";CountDownLatch latest=new CountDownLatch(1);manager.rebuild(new String[]{source},true,latest::countDown);release.countDown();
            check(latest.await(15,TimeUnit.SECONDS),"Replacement rebuild timeout");
            check("Latest programme".equals(manager.programsFor(channel).get(0).title),"Superseded request overwrote latest data");
            EpgManager cold=new EpgManager(getTargetContext());CountDownLatch restored=new CountDownLatch(1);cold.restoreCached(new String[]{source},restored::countDown);
            check(restored.await(5,TimeUnit.SECONDS),"Cold restore failed");
            check("Latest programme".equals(cold.programsFor(channel).get(0).title),"SQLite contains old programme");
            ((android.database.sqlite.SQLiteOpenHelper)field(cold,"database")).close();
            fail=true;refresh(manager,source,true,true);
            check(manager.programsFor(channel).isEmpty()&&!manager.getLastError().isEmpty(),"Failed rebuild reused stale content");
            check(rows(manager,"sources")==0&&rows(manager,"programmes")==0,"Failure retained stale database");
            fail=false;refresh(manager,source,true,true);before=requests.get();
            refresh(manager,source,true,false);
            check(requests.get()==before,"Disabled EPG fetched network");
            for(String table:new String[]{"sources","aliases","logos","programmes"})check(rows(manager,table)==0,"Disabled rebuild retained "+table);
            check(getTargetContext().getSharedPreferences("epg_cache",0).getAll().isEmpty(),"Metadata retained");
            File[] files=getTargetContext().getFilesDir().listFiles();if(files!=null)for(File file:files)check(!file.getName().startsWith("epg-guide-cache"),"XML retained");
            MainActivity activity=(MainActivity)startActivitySync(new android.content.Intent(getTargetContext(),MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            try{
                EpgManager webManager=(EpgManager)field(activity,"epgManager");
                java.lang.reflect.Method settings=MainActivity.class.getDeclaredMethod("handleWebSettings",org.json.JSONObject.class);settings.setAccessible(true);
                for(String expected:new String[]{"Web save one","Web save two"}){
                    title=expected;
                    settings.invoke(activity,new org.json.JSONObject().put("epgEnabled",true).put("epgUrls",new org.json.JSONArray().put(source)));
                    long deadline=System.currentTimeMillis()+8000;
                    while(System.currentTimeMillis()<deadline&&(webManager.isLoading()||webManager.programsFor(channel).isEmpty()
                            ||!expected.equals(webManager.programsFor(channel).get(0).title)))Thread.sleep(50);
                    check(!webManager.programsFor(channel).isEmpty()&&expected.equals(webManager.programsFor(channel).get(0).title),"Settings API same-URL save did not rebuild");
                }
            }finally{runOnMainSync(activity::finish);}
            result.putString("stream","PASS same-day automatic caching, repeated same-URL full rebuild, unconditional HTTP, SQLite replacement/cold restore, overlapping saves, failed rebuild without stale fallback, disabled cleanup without network, Activity settings API repeated saves\n");finish(-1,result);
        }catch(Throwable error){result.putString("stream",android.util.Log.getStackTraceString(error));finish(0,result);}
        finally{if(release!=null)release.countDown();if(server!=null)try{server.close();}catch(IOException ignored){}if(manager!=null)try{((android.database.sqlite.SQLiteOpenHelper)field(manager,"database")).close();}catch(Exception ignored){}}
    }
}
