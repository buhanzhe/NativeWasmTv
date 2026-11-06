package xiao.bu.tv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import xiao.bu.tv.skin.TvUi;

/** Actual right-hand menu callbacks, engine recreation and per-host restore in an isolated APK. */
public final class WebDomainSettingsInstrumentation extends Instrumentation {
    private MainActivity activity;
    private final AtomicInteger pages=new AtomicInteger();
    private interface Work {void run() throws Exception;}
    private interface Condition {boolean ready() throws Exception;}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private static Field field(Object target,String name)throws Exception {
        for(Class<?> type=target.getClass();type!=null;type=type.getSuperclass())try {
            Field f=type.getDeclaredField(name);f.setAccessible(true);return f;
        }catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(name);
    }
    private Object get(String name)throws Exception{return field(activity,name).get(activity);}
    private void set(String name,Object value)throws Exception{field(activity,name).set(activity,value);}
    private Object call(String name,Class<?>[] types,Object...args)throws Exception {
        Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args);
    }
    private void main(Work work)throws Exception {
        Throwable[] error={null};runOnMainSync(()->{try{work.run();}catch(Throwable e){error[0]=e;}});
        if(error[0]!=null)throw new Exception(error[0]);
    }
    private void await(Condition condition,String message)throws Exception {
        long deadline=SystemClock.elapsedRealtime()+18000;
        while(SystemClock.elapsedRealtime()<deadline){boolean[] ready={false};main(()->ready[0]=condition.ready());if(ready[0])return;Thread.sleep(100);}
        throw new AssertionError(message);
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private void open(Channel channel)throws Exception {
        main(()->{
            ((TvUiMenu)get("tvUiMenu")).dismiss();set("quickChannel",channel);set("currentSourceIndex",1);
            set("openingQuickChannel",true);
            try{call("startChannel",new Class<?>[]{int.class},(Integer)get("currentChannelIndex"));}
            finally{set("openingQuickChannel",false);}
        });
        await(()->((WebSourceView)get("webSourceView")).isPageVisible()||get("liteWebPlayback")!=null,"Web channel did not open");
    }
    private void choose(int category,int option)throws Exception {
        main(()->{
            call("showTvUiBrowserMenu",new Class<?>[]{int.class},category);
            Object view=((TvUiMenu)get("tvUiMenu")).contentView();check(view!=null,"Right menu missing");
            TvUi.Events events=(TvUi.Events)field(view,"events").get(view);
            events.action(category,option);
        });
    }
    private void awaitReload(int request,int count)throws Exception {
        await(()->(Integer)get("playRequestId")>request&&pages.get()>count,"Setting did not reload the current page");
        main(()->check((Integer)get("currentSourceIndex")==1,"Setting changed the selected line"));
    }
    private int request()throws Exception {int[] value={0};main(()->value[0]=(Integer)get("playRequestId"));return value[0];}
    @Override public void onStart(){
        Bundle result=new Bundle();int code=Activity.RESULT_CANCELED;ServerSocket server=null;
        try {
            long deadline=SystemClock.elapsedRealtime()+5000;
            while(getTargetContext().getApplicationContext()==null&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(10);
            check(LiteWebPlayback.supported(),"Use an isolated ARM32 browser APK");
            SharedPreferences p=getTargetContext().getSharedPreferences(MainActivity.PREFERENCES,0);
            check(getTargetContext().getPackageName().endsWith(".domaincheck")
                    ||getTargetContext().getPackageName().equals("xiao.bu.tv.litebrowser")&&p.getAll().isEmpty(),
                    "Use an isolated domaincheck APK, or a fresh emulator with the authorized litebrowser package");
            p.edit().clear().putString("web_source_engine","system").putString("web_view_user_agent","native")
                    .putString("web_view_resolution","720p").putBoolean("web_view_minimal_mode",false)
                    .putBoolean("web_view_auto_play",true).putBoolean("web_view_auto_play_sniffed",true)
                    .putBoolean("auto_update_channel_list",false).putBoolean("neighbor_probe_enabled",false).commit();
            server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));final ServerSocket local=server;
            Thread responder=new Thread(()->{
                while(!local.isClosed())try(Socket socket=local.accept()) {
                    socket.setSoTimeout(1500);BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),"UTF-8"));
                    String request=reader.readLine(),line;
                    while((line=reader.readLine())!=null&&!line.isEmpty()){}
                    if(request!=null&&(request.startsWith("GET /a")||request.startsWith("GET /b")))pages.incrementAndGet();
                    byte[] body="<!doctype html><html><head><title>domain fixture</title></head><body>Domain settings fixture</body></html>".getBytes("UTF-8");
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                    socket.getOutputStream().write(body);
                }catch(Exception ignored){}
            },"domain-web-fixture");responder.setDaemon(true);responder.start();
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Thread.sleep(1500);
            main(()->{TvUiPlugin.select(activity,"ui2");set("defaultWebSourceEngine","system");set("defaultWebViewUserAgent","native");set("autoSwitchSource",false);});
            String base="webview://http://127.0.0.1:"+server.getLocalPort();
            Channel a=new Channel("1","Domain A","domain-a",new String[]{base+"/a?line=0",base+"/a?line=1"},null,null,null,null);
            Channel b=new Channel("2","Domain B","domain-b","webview://http://localhost:"+server.getLocalPort()+"/b",null,null);
            open(a);await(()->pages.get()>0,"Initial fixture did not load");
            int before=request(),count=pages.get();choose(3,2);awaitReload(before,count);
            main(()->check("ipad".equals(get("webViewUserAgent")),"UA did not apply to current channel"));
            before=request();count=pages.get();choose(0,1);awaitReload(before,count);
            main(()->check("1080p".equals(get("webViewResolution")),"Resolution did not apply"));
            before=request();count=pages.get();choose(6,1);awaitReload(before,count);
            main(()->{check("lite".equals(get("webSourceEngine"))&&get("liteWebPlayback")!=null,"Lite selection kept the system engine");
                check(get("quickChannel")==a,"Engine change replaced current channel");
                Object view=((TvUiMenu)get("tvUiMenu")).contentView();
                check(view!=null&&((TvUi.Events)field(view,"events").get(view)).options(6).getInt("selected")==1,"Menu selection did not refresh");});
            before=request();count=pages.get();choose(6,0);awaitReload(before,count);
            main(()->check(get("liteWebPlayback")==null&&((WebSourceView)get("webSourceView")).isPageVisible(),"System selection kept the Lite engine"));
            open(b);main(()->check("native".equals(get("webViewUserAgent"))&&"720p".equals(get("webViewResolution")),"Different host inherited domain settings"));
            open(a);main(()->check("ipad".equals(get("webViewUserAgent"))&&"1080p".equals(get("webViewResolution")),"Returning to host lost its settings"));
            check(p.getString("web_view_user_agent","").equals("native")&&p.getString("web_view_resolution","").equals("720p")
                    &&p.getString("web_source_engine","").equals("system"),"Right menu changed global defaults");
            result.putString("result","PASS actual right menu: domain UA/resolution, immediate system -> Lite -> system recreation and page requests, current channel/line retained, menu selection refreshed, different-host isolation, same-host restore, globals unchanged");
            code=Activity.RESULT_OK;
        }catch(Throwable error){result.putString("error",android.util.Log.getStackTraceString(error));}
        finally {
            if(server!=null)try{server.close();}catch(Exception ignored){}
            if(activity!=null)try{main(()->{((TvUiMenu)get("tvUiMenu")).dismiss();call("stopCurrentChannelLoad",new Class<?>[0]);activity.finish();});}catch(Exception ignored){}
        }
        finish(code,result);
    }
}
