package xiao.bu.tv;

import android.app.*;
import android.content.*;
import android.os.*;
import android.webkit.WebView;
import java.io.*;
import java.net.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Real task/launch requests while switching embedded web channels and recovering failures. */
public class WebTaskNavigationInstrumentation extends Instrumentation {
    MainActivity activity;WebSourceView source;ActivityMonitor mainMonitor,webMonitor;
    final Handler ui=new Handler(Looper.getMainLooper());final JSONArray stages=new JSONArray();
    volatile String fixtureUserAgent="";
    interface Work {void run()throws Exception;}
    void main(Work work)throws Exception{CountDownLatch done=new CountDownLatch(1);Throwable[] error={null};ui.post(()->{try{work.run();}catch(Throwable t){error[0]=t;}finally{done.countDown();}});if(!done.await(8,TimeUnit.SECONDS))throw new AssertionError("UI stalled");if(error[0]!=null)throw new Exception(error[0]);}
    static Object field(Object object,String key)throws Exception{Field f=object.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(object);}
    void set(String key,Object value)throws Exception{Field f=MainActivity.class.getDeclaredField(key);f.setAccessible(true);f.set(activity,value);}
    Object call(String key,Class<?>[] types,Object...args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(key,types);m.setAccessible(true);return m.invoke(activity,args);}
    void call(String key)throws Exception{call(key,new Class<?>[0]);}
    WebView view()throws Exception{return (WebView)field(source,"webView");}
    String js(String script)throws Exception{String[] result={null};CountDownLatch done=new CountDownLatch(1);main(()->view().evaluateJavascript(script,s->{result[0]=s;done.countDown();}));if(!done.await(5,TimeUnit.SECONDS))throw new AssertionError("JS timeout");return result[0];}
    void ready(String path)throws Exception{long until=SystemClock.elapsedRealtime()+12000;do{if("true".equals(js("window.fixtureReady===true&&document.readyState==='complete'&&location.pathname==="+JSONObject.quote(path)))){boolean[] complete={false};main(()->complete[0]=view().getProgress()==100&&!((Boolean)field(source,"clearInitialHistory")));if(complete[0])return;}SystemClock.sleep(150);}while(SystemClock.elapsedRealtime()<until);throw new AssertionError("Local document not ready: "+path);}
    void checkpoint(String name,int task)throws Exception{main(()->{JSONObject row=new JSONObject().put("stage",name).put("taskId",activity.getTaskId()).put("mainLaunchRequests",mainMonitor.getHits()).put("legacyLaunchRequests",webMonitor.getHits()).put("channelIndex",field(activity,"currentChannelIndex"));stages.put(row);if(activity.getTaskId()!=task||mainMonitor.getHits()!=0||webMonitor.getHits()!=0)throw new AssertionError("Web navigation requested an Activity/task transition: "+row);});}
    void restore(SharedPreferences p,Map<String,?> values){SharedPreferences.Editor e=p.edit().clear();for(Map.Entry<String,?> v:values.entrySet()){Object x=v.getValue();if(x instanceof String)e.putString(v.getKey(),(String)x);else if(x instanceof Boolean)e.putBoolean(v.getKey(),(Boolean)x);else if(x instanceof Integer)e.putInt(v.getKey(),(Integer)x);else if(x instanceof Long)e.putLong(v.getKey(),(Long)x);else if(x instanceof Float)e.putFloat(v.getKey(),(Float)x);else if(x instanceof Set)e.putStringSet(v.getKey(),new HashSet<String>((Set<String>)x));}e.commit();}
    void settings(JSONObject request)throws Exception{main(()->call("handleWebSettings",new Class<?>[]{JSONObject.class},request));}
    void domainChecks(Channel a,Channel b,String base,JSONObject report)throws Exception{
        String domain=WebDomainSettings.key(a.sourceUrl(0));
        if(!domain.equals(WebDomainSettings.key("webview://https://127.0.0.1:1/another?ujs=anything")))throw new AssertionError("Host scope includes path, port or scheme");
        settings(new JSONObject().put("webPreferenceScope","global").put("webSourceEngine","lite").put("webViewUserAgent","windows"));
        settings(new JSONObject().put("webPreferenceScope","domain").put("webPreferenceDomain",domain).put("webSourceEngine","system").put("webViewUserAgent","native"));
        if(!"lite".equals(field(activity,"defaultWebSourceEngine"))||!"windows".equals(field(activity,"defaultWebViewUserAgent")))throw new AssertionError("Domain choice changed global defaults");
        main(()->{set("currentChannelIndex",1);call("restoreWebChannelPreferences",new Class<?>[]{ChannelCatalog.Group.class,Channel.class},ChannelCatalog.GROUPS[0],b);});
        if(!"system".equals(field(activity,"webSourceEngine"))||!"native".equals(field(activity,"webViewUserAgent")))throw new AssertionError("Same-host channel did not share overrides");
        settings(new JSONObject().put("webPreferenceScope","global").put("webSourceEngine","lite").put("webViewUserAgent","macos"));
        if(!"system".equals(field(activity,"webSourceEngine"))||!"native".equals(field(activity,"webViewUserAgent")))throw new AssertionError("Global save erased domain overrides");
        Channel other=new Channel("3","其他域名","","webview://http://localhost/other",null,null);
        main(()->{set("quickChannel",other);call("restoreWebChannelPreferences",new Class<?>[]{ChannelCatalog.Group.class,Channel.class},ChannelCatalog.GROUPS[0],other);});
        if(!"lite".equals(field(activity,"webSourceEngine"))||!"macos".equals(field(activity,"webViewUserAgent")))throw new AssertionError("Another host did not inherit globals");
        main(()->{set("quickChannel",null);set("currentChannelIndex",0);});
        settings(new JSONObject().put("webPreferenceScope","global").put("webSourceEngine","system").put("webViewUserAgent","windows"));
        report.put("domainSettings",true);
    }
    void assertAbove(android.view.View control,android.view.View page,String label){android.view.ViewGroup parent=(android.view.ViewGroup)page.getParent();if(control.getVisibility()!=android.view.View.VISIBLE||control.getParent()!=parent||parent.indexOfChild(control)<=parent.indexOfChild(page))throw new AssertionError(label+" is behind the webpage cover");}
    void coverChecks(JSONObject report)throws Exception{
        main(()->{source.applyPageCoverStyle("black");source.applyPageCover(true);call("openChannelList",new Class<?>[]{boolean.class},true);source.bringToFront();assertAbove((android.view.View)field(activity,"channelListPanel"),source,"Channel list");call("closeChannelList");call("showChannelBar",new Class<?>[]{String.class,String.class},"频道遮罩测试","加载网页");source.bringToFront();assertAbove((android.view.View)field(activity,"channelBar"),source,"Channel card");source.applyPageCover(false);});
        report.put("systemCoverControlsAbove",true);
    }
    void liteCoverChecks(Channel channel,JSONObject report)throws Exception{
        settings(new JSONObject().put("webPreferenceScope","domain").put("webPreferenceDomain",WebDomainSettings.key(channel.sourceUrl(0))).put("webViewUserAgent","macos"));
        main(()->{set("webViewPageCover",true);set("webViewPageCoverStyle","black");call("openChannelList",new Class<?>[]{boolean.class},true);call("openLiteWebSource",new Class<?>[]{Channel.class,String.class,int.class,String.class},channel,channel.sourceUrl(0),(Integer)field(activity,"playRequestId"),"");});
        long until=SystemClock.elapsedRealtime()+18000;android.view.View[] page={null};
        do{main(()->{Object lite=field(activity,"liteWebPlayback");if(lite!=null)page[0]=(android.view.View)field(lite,"renderedPage");});if(page[0]!=null)break;SystemClock.sleep(100);}while(SystemClock.elapsedRealtime()<until);
        if(page[0]==null)throw new AssertionError("Lightweight page was not rendered");
        if(!fixtureUserAgent.contains("Macintosh"))throw new AssertionError("Lightweight HTTP did not use the domain UA");
        main(()->{Object lite=field(activity,"liteWebPlayback");LiteEngine engine=(LiteEngine)field(lite,"engine");JSONObject identity=new JSONObject(engine.host("{\"op\":\"browserInfo\"}"));if(!"MacIntel".equals(identity.optString("platform"))||!identity.optString("userAgent").contains("Macintosh"))throw new AssertionError("Lightweight navigator identity differs from domain UA");});
        report.put("liteDomainHttpAndNavigatorUa",true);
        main(()->{assertAbove((android.view.View)field(activity,"channelListPanel"),page[0],"Lightweight channel list");call("closeChannelList");call("showChannelBar",new Class<?>[]{String.class,String.class},"频道遮罩测试","轻量网页");assertAbove((android.view.View)field(activity,"channelBar"),page[0],"Lightweight channel card");});
        report.put("liteCoverControlsAbove",true);
    }
    @Override public void onCreate(Bundle args){start();}
    @Override public void onStart(){Bundle result=new Bundle();JSONObject report=new JSONObject();int code=-1;ServerSocket server=null;ChannelCatalog.Group[] savedGroups=ChannelCatalog.GROUPS;SharedPreferences prefs=getTargetContext().getSharedPreferences("tv_player",0);Map<String,?> saved=new HashMap<>(prefs.getAll());
        try{
            if(Build.VERSION.SDK_INT<24)throw new AssertionError("Embedded-task test requires Android 7+");
            server=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));final ServerSocket fixture=server;
            new Thread(()->{while(!fixture.isClosed())try(Socket peer=fixture.accept()){
                peer.setSoTimeout(3000);BufferedReader in=new BufferedReader(new InputStreamReader(peer.getInputStream(),"UTF-8"));String line;while((line=in.readLine())!=null&&!line.isEmpty()){if(line.toLowerCase(java.util.Locale.US).startsWith("user-agent:"))fixtureUserAgent=line.substring(11).trim();}
                byte[] body=("<!doctype html><meta charset='utf-8'><title>Task fixture</title><button id='action' onclick='window.clicked=(window.clicked||0)+1'>Login button</button><script>window.fixtureReady=true;</script>").getBytes("UTF-8");OutputStream out=peer.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));out.write(body);out.flush();
            }catch(IOException ignored){}},"task-local-fixture").start();
            String base="http://127.0.0.1:"+server.getLocalPort();
            Channel a=new Channel("1","网页A","","webview://"+base+"/a",null,null),b=new Channel("2","网页B","","webview://"+base+"/b",null,null);
            prefs.edit().putBoolean("auto_update_channel_list",false).commit();
            ActivityMonitor launcher=addMonitor(MainActivity.class.getName(),null,false);
            Bundle phase=new Bundle();phase.putString("phase","await_main");sendStatus(0,phase);
            activity=(MainActivity)launcher.waitForActivityWithTimeout(15000);removeMonitor(launcher);
            if(activity==null)throw new AssertionError("Foreground launch timed out");SystemClock.sleep(1200);
            mainMonitor=addMonitor(MainActivity.class.getName(),null,false);webMonitor=addMonitor(LegacyWebActivity.class.getName(),null,false);final int task=activity.getTaskId();
            main(()->{call("stopCurrentChannelLoad");((java.util.concurrent.atomic.AtomicInteger)field(activity,"catalogLoadGeneration")).incrementAndGet();((TvUiMenu)field(activity,"tvUiMenu")).dismiss();call("closeChannelList");ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("任务栈测试",ChannelCatalog.SOURCE_CUSTOM,new Channel[]{a,b})};set("quickChannel",null);set("remoteCatalogUrl","");set("currentGroupIndex",0);set("currentChannelIndex",0);set("currentSourceIndex",0);set("webSourceEngine","system");set("defaultWebSourceEngine","system");set("rememberChannelWebEngine",false);set("autoSwitchSource",false);set("webViewMinimalMode",false);set("webViewAutoPlay",false);set("webViewAutoPlaySniffed",false);source=(WebSourceView)field(activity,"webSourceView");if(field(source,"legacy")!=null)throw new AssertionError("Modern channel unexpectedly uses isolated Activity");call("openWebSource",new Class<?>[]{Channel.class,String.class,int.class},a,a.sourceUrl(0),(Integer)field(activity,"playRequestId"));});
            ready("/a");checkpoint("initial-web-channel",task);
            if(!"1".equals(js("document.getElementById('action').click();window.clicked")))throw new AssertionError("Page button did not respond");checkpoint("page-button",task);
            domainChecks(a,b,base,report);ready("/a");coverChecks(report);checkpoint("domain-and-cover",task);
            main(()->view().loadUrl(base+"/next"));ready("/next");main(()->{if(!view().canGoBack())throw new AssertionError("Missing page history");view().goBack();});ready("/a");checkpoint("page-back",task);
            main(()->call("recoverUnavailableWebChannel",new Class<?>[]{int.class,String.class},(Integer)field(activity,"playRequestId")-1,"stale fixture"));checkpoint("stale-callback",task);
            main(()->call("recoverUnavailableWebChannel",new Class<?>[]{int.class,String.class},(Integer)field(activity,"playRequestId"),"renderer unavailable fixture"));SystemClock.sleep(500);checkpoint("failure-switch-next-channel",task);
            main(()->{if((Integer)field(activity,"currentChannelIndex")!=1)throw new AssertionError("Did not advance channel");});ready("/b");
            main(()->((WebSourceView.Listener)field(source,"listener")).onBrowserHome());checkpoint("return-to-channel-list",task);
            main(()->{Set<String> unavailable=(Set<String>)field(activity,"unavailableWebChannels");unavailable.add("0:0");unavailable.add("0:1");call("recoverUnavailableWebChannel",new Class<?>[]{int.class,String.class},(Integer)field(activity,"playRequestId"),"all unavailable fixture");});checkpoint("all-channels-unavailable",task);
            liteCoverChecks(a,report);checkpoint("lite-cover",task);
            boolean legacyEnabled=getTargetContext().getPackageManager().getActivityInfo(new ComponentName(getTargetContext(),LegacyWebActivity.class),android.content.pm.PackageManager.GET_DISABLED_COMPONENTS).enabled;
            report.put("legacyActivityEnabled",legacyEnabled);if(legacyEnabled)throw new AssertionError("Legacy task Activity remains enabled on Android 7+");
            report.put("passed",true).put("taskId",task);
        }catch(Throwable error){code=0;try{report.put("passed",false).put("failure",android.util.Log.getStackTraceString(error));}catch(Exception ignored){}}
        finally{if(server!=null)try{server.close();}catch(IOException ignored){}try{if(activity!=null){main(()->{call("stopCurrentChannelLoad");activity.finish();});long until=SystemClock.elapsedRealtime()+3000;while(!activity.isDestroyed()&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(50);}}catch(Exception ignored){}if(mainMonitor!=null)removeMonitor(mainMonitor);if(webMonitor!=null)removeMonitor(webMonitor);ChannelCatalog.GROUPS=savedGroups;restore(prefs,saved);try{report.put("preferencesRestored",saved.equals(prefs.getAll())).put("sdk",Build.VERSION.SDK_INT).put("model",Build.MODEL).put("stages",stages);File dir=new File(getTargetContext().getExternalFilesDir(null),"web-task");dir.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(dir,"result.json"))){out.write(report.toString(2).getBytes("UTF-8"));}result.putString("stream",report.toString());}catch(Exception ignored){}finish(code,result);}
    }
}
