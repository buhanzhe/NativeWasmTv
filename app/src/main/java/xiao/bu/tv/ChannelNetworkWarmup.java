package xiao.bu.tv;

import android.app.Activity;
import android.os.*;
import android.util.Log;
import android.widget.FrameLayout;
import java.util.*;
import java.util.concurrent.*;

/** Two neighbouring sources, prepared only after real foreground output. */
final class ChannelNetworkWarmup implements java.io.Closeable {
    static final class Target {
        final String name,source;
        Target(String name,String source){this.name=name;this.source=source;}
    }
    private static final long MAX_AGE=30000;
    private final Activity activity;
    private final FrameLayout root;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService planner=Executors.newSingleThreadExecutor(r->new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);r.run();},"channel-network-warmup"));
    private final Map<String,Entry> entries=new LinkedHashMap<>();
    private final ArrayDeque<Plan> scripts=new ArrayDeque<>();
    private volatile LitePageWarmup pages;
    private okhttp3.Dns resolver;
    private volatile int generation;
    private int sequence=-1000;
    private volatile boolean closed;
    private boolean resolving;
    private static final class Plan {
        String raw,name,page,source,quality,key;boolean ku9,cjs;
    }
    private static final class Entry {
        Plan plan;long started=SystemClock.elapsedRealtime(),readyAt;
        Ku9ScriptResolver ku9;CjsSiteResolver cjs;
        Ku9ScriptResolver.Result ku9Result;CjsSiteResolver.Result cjsResult;
        Ku9ScriptResolver.Callback ku9Callback;CjsSiteResolver.Callback cjsCallback;
        int request;boolean cancelled,completed,adopted;
        void cancel(){if(adopted)return;cancelled=true;if(ku9!=null)ku9.destroy();if(cjs!=null)cjs.destroy();}
    }
    static final class Ku9Lease {
        final Ku9ScriptResolver resolver;final Ku9ScriptResolver.Result result;
        Ku9Lease(Entry e){resolver=e.ku9;result=e.ku9Result==null?null:e.ku9Result.asInitial();}
    }
    static final class CjsLease {
        final CjsSiteResolver resolver;final CjsSiteResolver.Result result;
        CjsLease(Entry e){resolver=e.cjs;result=e.cjsResult;}
    }
    ChannelNetworkWarmup(Activity activity,FrameLayout root){this.activity=activity;this.root=root;}
    void prepare(List<Target> targets,String quality){
        if(closed)return;final int epoch=++generation;clearEntries();scripts.clear();resolving=false;LitePageWarmup existing=pages;if(existing!=null)existing.cancel();
        final List<Target> bounded=new ArrayList<>(targets.subList(0,Math.min(2,targets.size())));
        planner.execute(()->{
            try{
                if(closed||epoch!=generation)return;
                okhttp3.Dns selected=NetworkClient.sharedClient().dns();
                if(pages==null||resolver!=selected){if(pages!=null)pages.close();resolver=selected;pages=new LitePageWarmup(activity.getApplicationContext(),selected);}
                if(closed){pages.close();return;}
                List<Plan> plans=new ArrayList<>();
                for(Target target:bounded){
                    if(closed||epoch!=generation)return;
                    if(target.source==null)continue;
                    Plan p=new Plan();p.raw=target.source;p.name=target.name;p.source="";p.quality=quality;
                    String effective=p.raw;
                    if(CjsSource.isSource(p.raw)){
                        CjsSource source=CjsSource.parse(p.raw);
                        if(!CjsPluginRuntime.knowsSource(source)){pages.dns(source.descriptorUrl);continue;}
                        effective=CjsPluginRuntime.playbackUrl(p.raw);p.source=p.raw;
                        if(source.parameters.get("quality")!=null)p.quality=source.parameters.get("quality");
                    }
                    if(effective.startsWith("webview://")){
                        p.page=effective.substring("webview://".length());p.cjs=CjsPluginRuntime.supportsSite(p.page);
                        pages.dns(p.page);if(!p.cjs)pages.page(p.page);
                    }else if(Ku9ScriptResolver.isKu9Source(effective)){
                        p.ku9=true;p.page=effective;pages.dns(effective);
                    }else if(effective.startsWith("http://")||effective.startsWith("https://")){
                        pages.dns(effective);continue;
                    }else continue;
                    p.key=p.ku9?"ku9\n"+effective:"cjs\n"+p.page+'\n'+p.quality+'\n'+p.source;
                    if(p.ku9||p.cjs)plans.add(p);
                }
                main.post(()->{if(closed||epoch!=generation)return;scripts.addAll(plans);next();});
            }catch(Exception error){Log.d("ChannelWarmup","Network preparation deferred: "+error.getClass().getSimpleName());}
        });
    }
    private void next(){
        if(closed||resolving||scripts.isEmpty())return;Plan p=scripts.removeFirst();Entry e=new Entry();e.plan=p;entries.put(p.key,e);resolving=true;
        final int request=sequence--;
        main.postDelayed(()->{if(entries.get(p.key)==e){entries.remove(p.key);e.cancel();if(!e.completed){e.completed=true;resolving=false;next();}}},MAX_AGE);
        if(p.ku9){
            e.ku9=new Ku9ScriptResolver(activity,root,true);
            e.ku9.resolve(request,p.name,p.page,new Ku9ScriptResolver.Callback(){
                public void onResolved(int id,Ku9ScriptResolver.Result result){
                    if(e.cancelled)return;e.ku9Result=result;e.readyAt=SystemClock.elapsedRealtime();dnsResult(result.url);ready(e);
                    if(e.adopted)e.ku9Callback.onResolved(e.request,result);
                }
                public void onFailed(int id,String reason){failed(e);if(e.adopted)e.ku9Callback.onFailed(e.request,reason);}
            });
        }else{
            e.cjs=new CjsSiteResolver(activity,true);
            e.cjs.resolve(request,p.name,p.page,p.quality,p.source,new CjsSiteResolver.Callback(){
                public void onResolved(int id,CjsSiteResolver.Result result){
                    if(e.cancelled)return;e.cjsResult=result;e.readyAt=SystemClock.elapsedRealtime();dnsResult(result.url);ready(e);
                    if(e.adopted)e.cjsCallback.onResolved(e.request,result);
                }
                public void onFailed(int id,String reason){failed(e);if(e.adopted)e.cjsCallback.onFailed(e.request,reason);}
            });
        }
    }
    private void dnsResult(String url){LitePageWarmup p=pages;if(p!=null)p.dns(url);}
    private void ready(Entry e){if(!e.completed){e.completed=true;resolving=false;Log.d("ChannelWarmup","Neighbour resolver ready in "+(SystemClock.elapsedRealtime()-e.started)+" ms");next();}}
    private void failed(Entry e){
        if(e.cancelled)return;
        if(!e.adopted){entries.remove(e.plan.key);e.cancel();}
        if(!e.completed){e.completed=true;resolving=false;next();}
    }
    /** Keep only the imminent source; unrelated preparation never races a tune. */
    void foreground(String source){
        ++generation;scripts.clear();resolving=false;LitePageWarmup p=pages;if(p!=null)p.cancel();
        Iterator<Entry> iterator=entries.values().iterator();while(iterator.hasNext()){
            Entry e=iterator.next();if(!e.plan.raw.equals(source)&&!("webview://"+e.plan.page).equals(source)){iterator.remove();e.cancel();}
        }
    }
    private Entry take(String key){
        Entry e=entries.remove(key);if(e==null)return null;
        if(e.cancelled||SystemClock.elapsedRealtime()-e.started>=MAX_AGE){e.cancel();return null;}
        e.adopted=true;return e;
    }
    Ku9Lease takeKu9(String source,int request,Ku9ScriptResolver.Callback callback){
        Entry e=take("ku9\n"+source);if(e==null)return null;e.request=request;e.ku9Callback=callback;e.ku9.promoteWarmup();return new Ku9Lease(e);
    }
    CjsLease takeCjs(String page,String quality,String source,int request,CjsSiteResolver.Callback callback){
        Entry e=take("cjs\n"+page+'\n'+quality+'\n'+source);if(e==null)return null;e.request=request;e.cjsCallback=callback;e.cjs.promoteWarmup();return new CjsLease(e);
    }
    private void clearEntries(){for(Entry e:entries.values())e.cancel();entries.clear();}
    public void close(){closed=true;++generation;clearEntries();scripts.clear();main.removeCallbacksAndMessages(null);planner.shutdownNow();LitePageWarmup p=pages;if(p!=null)p.close();}
}
