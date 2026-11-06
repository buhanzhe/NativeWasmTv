package xiao.bu.tv;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** One cancellable, silent metadata probe; no UI-thread database/network work. */
final class ChannelHealth {
    static final class State {final int status,source;State(int s,int i){status=s;source=i;}}
    private final Context context;private final Runnable changed;private final Handler main=new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<String,State> cache=new ConcurrentHashMap<>();
    private final LinkedHashMap<String,Channel> queue=new LinkedHashMap<>();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"channel-health");t.setPriority(Thread.MIN_PRIORITY);return t;});
    private final AtomicInteger epoch=new AtomicInteger();private final Store store;private volatile boolean closed,allowed;private boolean draining;
    private volatile LiteEngine page;private volatile SniffedMediaProbe media;volatile int completed,total;volatile boolean scanning;
    ChannelHealth(Context c,Runnable update){context=c.getApplicationContext();changed=update;store=new Store(context);worker.execute(()->{try(Cursor rows=store.getReadableDatabase().rawQuery("SELECT id,status,source FROM health",null)){while(rows.moveToNext())cache.putIfAbsent(rows.getString(0),new State(rows.getInt(1),rows.getInt(2)));main.post(changed);}catch(Exception e){AppLog.w("ChannelHealth","Cache load failed",e);}});}
    private static final Map<Channel,String> keys=Collections.synchronizedMap(new WeakHashMap<Channel,String>());
    static String key(Channel c){String known=keys.get(c);if(known!=null)return known;try{java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");if(c.sourceCount()==0)digest.update((c.name+"|"+c.yangshipinPid+"|"+c.streamId).getBytes("UTF-8"));for(int i=0;i<c.sourceCount();i++){String value=c.sourceUrl(i);byte[] b=(value==null?"":value).getBytes("UTF-8");digest.update((byte)(b.length>>>24));digest.update((byte)(b.length>>>16));digest.update((byte)(b.length>>>8));digest.update((byte)b.length);digest.update(b);}StringBuilder s=new StringBuilder(64);final char[] hex="0123456789abcdef".toCharArray();for(byte b:digest.digest()){s.append(hex[(b&255)>>>4]);s.append(hex[b&15]);}String id=s.toString();keys.put(c,id);return id;}catch(Exception e){throw new IllegalStateException(e);}}
    boolean bad(Channel c){State s=cache.get(key(c));return s!=null&&s.status<0;}
    int source(Channel c,int fallback){State s=cache.get(key(c));return s!=null&&s.status>0&&s.source<c.sourceCount()?s.source:fallback;}
    void success(Channel c,int source){
        String id=key(c);State s=new State(1,source);
        synchronized(queue){
            if(closed)return;
            State previous=cache.put(id,s);
            if(queue.remove(id)!=null){completed++;if(queue.isEmpty())scanning=false;}
            if(previous==null||previous.status!=1||previous.source!=source){worker.execute(()->save(id,s));main.post(changed);}
        }
    }
    private void save(String id,State s){try{ContentValues v=new ContentValues();v.put("id",id);v.put("status",s.status);v.put("source",s.source);v.put("checked",System.currentTimeMillis());store.getWritableDatabase().insertWithOnConflict("health",null,v,SQLiteDatabase.CONFLICT_REPLACE);}catch(Exception e){AppLog.w("ChannelHealth","Cache save failed",e);}}
    void pause(){allowed=false;synchronized(queue){if(!scanning)queue.clear();}epoch.incrementAndGet();SniffedMediaProbe m=media;if(m!=null)m.clear();LiteEngine p=page;if(p!=null)p.close();}
    void resume(){allowed=true;start();}
    void all(ChannelCatalog.Group[] groups){if(closed||scanning)return;pause();synchronized(queue){queue.clear();completed=0;total=0;for(ChannelCatalog.Group g:groups)for(Channel c:g.channels)if(!queue.containsKey(key(c))){queue.put(key(c),c);total++;}scanning=!queue.isEmpty();}start();}
    void neighbors(ChannelCatalog.Group[] groups,int group,int index){
        ArrayList<Channel> list=new ArrayList<>();int current=-1;
        if(group<0||group>=groups.length)return;
        if(groups[group].source==ChannelCatalog.SOURCE_FAVORITES){
            Collections.addAll(list,groups[group].channels);current=index;
        }else for(int g=0;g<groups.length;g++){
            if(groups[g].source==ChannelCatalog.SOURCE_FAVORITES)continue;
            for(int i=0;i<groups[g].channels.length;i++){
                if(g==group&&i==index)current=list.size();list.add(groups[g].channels[i]);
            }
        }
        if(current<0||current>=list.size()||list.size()<2)return;
        synchronized(queue){
            for(int d=1;d<=3;d++)for(int sign:new int[]{-1,1}){
                Channel c=list.get((current+sign*d%list.size()+list.size())%list.size());String id=key(c);
                if(c!=list.get(current)&&!cache.containsKey(id))queue.put(id,c);
            }
        }
        start();
    }
    private void start(){
        synchronized(queue){
            if(closed||!allowed||draining||queue.isEmpty())return;
            draining=true;worker.execute(this::drain);
        }
    }
    // Yield the worker after each item so foreground-success writes never wait
    // behind a long scan. A cancelled generation cannot remove a newer item.
    private void drain(){
        try{
            if(closed||!allowed)return;
            String id;Channel c;int generation;State prior;
            synchronized(queue){
                if(queue.isEmpty()){scanning=false;return;}
                id=queue.keySet().iterator().next();c=queue.get(id);generation=epoch.get();prior=cache.get(id);
            }
            State state=probe(c,generation);
            synchronized(queue){
                if(closed||generation!=epoch.get()||!allowed)return;
                State live=cache.get(id);
                if(live!=prior&&live!=null&&live.status>0)state=live;
                cache.put(id,state);
                if(queue.remove(id)!=null)completed++;
                if(queue.isEmpty())scanning=false;
            }
            save(id,state);main.post(changed);
        }finally{
            synchronized(queue){draining=false;}
            if(allowed&&!closed)start();
        }
    }
    private State probe(Channel c,int generation){boolean unknown=c.sourceCount()==0;for(int i=0;i<c.sourceCount()&&allowed&&generation==epoch.get();i++){String url=c.sourceUrl(i);int label=url.indexOf("$");if(label>=0)url=url.substring(0,label);try{int status=probeSource(url,generation);if(status>0)return new State(1,i);if(status==0)unknown=true;}catch(Exception e){unknown=true;}}return new State(unknown?0:-1,0);}
    private int probeSource(String url,int generation)throws Exception{return probeSource(url,generation,0,SystemClock.elapsedRealtime()+30000);}
    private int probeSource(String url,int generation,int redirects,long deadline)throws Exception{
        if(redirects>4||SystemClock.elapsedRealtime()>=deadline)return 0;
        if(url.startsWith("webview://")){
            if(!LiteWebPlayback.supported())return 0;
            android.app.ActivityManager.MemoryInfo info=new android.app.ActivityManager.MemoryInfo();((android.app.ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(info);
            if(info.availMem<160L*1024*1024)return 0;
            String[] found={null},navigation={null};Map<String,String>[] headers=new Map[]{null};CountDownLatch discovered=new CountDownLatch(1);int[] documentError={0};
            LiteEngine engine=new LiteEngine(context,new LiteEngine.Listener(){public void frame(JSONObject packet){}public void navigate(String next){navigation[0]=next;discovered.countDown();}public void media(String source,Map<String,String> h){found[0]=source;headers[0]=h;discovered.countDown();}public void message(String message){if(message.startsWith("加载失败：")){documentError[0]=message.contains("404")||message.contains("410")?-1:0;discovered.countDown();}}},null,2);page=engine;
            try{engine.setAuditMemoryMiB(64);engine.setHeadless(true);
            SharedPreferences preferences=context.getSharedPreferences("tv_player",0);
            String mode=new ChannelPlaybackMemory(preferences).userAgent(WebDomainSettings.key(url),preferences.getString("web_view_user_agent","auto"));
            if(!"auto".equals(mode)&&!"native".equals(mode)){
                String version=preferences.getString("web_view_browser_version","native"),fallback=LiteEngine.defaultUserAgent(url.substring(10));
                engine.userAgentOverride("ipad".equals(mode)?DesktopWebProfile.ipadUserAgent(fallback,version):DesktopWebProfile.userAgent(mode,fallback,version));
            }
            WebSourceOptions options=WebSourceOptions.parse(url.substring(10),"");engine.load(options.url,options.script);long until=deadline;while(allowed&&generation==epoch.get()&&SystemClock.elapsedRealtime()<until&&!discovered.await(100,TimeUnit.MILLISECONDS)){}if(found[0]==null){if(navigation[0]!=null){engine.close();return probeSource("webview://"+navigation[0],generation,redirects+1,deadline);}return documentError[0];}return metadata(found[0],headers[0],generation,until);}finally{engine.close();page=null;}
        }
        if(!url.matches("(?i)^(https?|rtmp|rtsp|rtp|udp)://.*"))return 0;
        return metadata(url,null,generation,deadline);
    }
    private int metadata(String url,Map<String,String> headers,int generation)throws Exception{return metadata(url,headers,generation,Long.MAX_VALUE);}
    private int metadata(String url,Map<String,String> headers,int generation,long deadline)throws Exception{
        if(SystemClock.elapsedRealtime()>=deadline)return 0;
        SniffedMediaProbe p=new SniffedMediaProbe();media=p;int[] state={0};CountDownLatch done=new CountDownLatch(1);
        try{SniffedMediaProbe.Callback callback=r->{String error=r.error.toLowerCase(Locale.US);
                // A short advisory probe cannot prove a slow/offline source dead.
                // Keep uncertain results selectable and refresh them only on an
                // explicit full scan or a successful foreground retry.
                state[0]="ready".equals(r.status)?1:error.contains("timeout")||error.contains("timed out")||error.contains("超时")||error.contains("unknownhost")||error.contains("unable to resolve")?0:-1;done.countDown();};if(headers==null)p.submitLightweight(url,callback);else p.submit(url,headers.get("Referer"),headers.get("User-Agent"),headers.get("Cookie"),callback);
            long until=Math.min(deadline,SystemClock.elapsedRealtime()+12000);while(allowed&&generation==epoch.get()&&SystemClock.elapsedRealtime()<until&&!done.await(100,TimeUnit.MILLISECONDS)){}return state[0];
        }finally{p.close();media=null;}
    }
    JSONObject status(){JSONObject j=new JSONObject();try{j.put("running",scanning).put("completed",completed).put("total",total);int bad=0,good=0,unknown=0;Set<String> active=new HashSet<>();for(ChannelCatalog.Group g:ChannelCatalog.GROUPS)for(Channel c:g.channels)active.add(key(c));for(String id:active){State state=cache.get(id);if(state==null)continue;if(state.status<0)bad++;else if(state.status>0)good++;else unknown++;}j.put("bad",bad).put("good",good).put("unknown",unknown);}catch(Exception ignored){}return j;}
    void close(){
        synchronized(queue){if(closed)return;closed=true;queue.clear();worker.execute(store::close);worker.shutdown();}
        pause();
    }
    private static final class Store extends SQLiteOpenHelper{Store(Context c){super(c,"channel-health.db",null,1);}public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE health(id TEXT PRIMARY KEY,status INTEGER NOT NULL,source INTEGER NOT NULL,checked INTEGER NOT NULL)");}public void onUpgrade(SQLiteDatabase db,int a,int b){}}
}
