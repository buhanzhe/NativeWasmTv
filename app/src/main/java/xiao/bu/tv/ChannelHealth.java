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
    static final class State {final int status,source;final long checked;
        State(int s,int i){this(s,i,System.currentTimeMillis());}
        State(int s,int i,long at){status=s;source=i;checked=at;}
        boolean fresh(){return ChannelHealthPolicy.fresh(status,checked,System.currentTimeMillis());}
    }
    private final Context context;private final Runnable changed;private final Handler main=new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<String,State> cache=new ConcurrentHashMap<>();
    private final LinkedHashMap<String,Channel> queue=new LinkedHashMap<>();
    // Only HTTP headers run ahead; native media and web engines remain serial.
    private final ExecutorService entryWorkers=Executors.newFixedThreadPool(ContinuousTsPolicy.physicalCores()>1?2:1,r->{Thread t=new Thread(r,"channel-entry-check");t.setPriority(Thread.MIN_PRIORITY);return t;});
    private final LinkedHashMap<String,EntryCheck> entries=new LinkedHashMap<>();
    private final long sessionId=System.currentTimeMillis();
    private long scanId,revision,scanStarted,scanFinished;private int reused,scanGood,scanBad,scanUnknown;
    private String currentName="";private boolean scanItem,cancelled;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"channel-health");t.setPriority(Thread.MIN_PRIORITY);return t;});
    private final AtomicInteger epoch=new AtomicInteger();private final Store store;private volatile boolean closed,allowed;private boolean draining;
    private volatile okhttp3.Call webCall;private volatile LiteEngine page;private volatile SniffedMediaProbe media;volatile int completed,total;volatile boolean scanning;
    ChannelHealth(Context c,Runnable update){context=c.getApplicationContext();changed=update;store=new Store(context);worker.execute(()->{try(Cursor rows=store.getReadableDatabase().rawQuery("SELECT id,status,source,checked FROM health",null)){while(rows.moveToNext())cache.putIfAbsent(rows.getString(0),new State(rows.getInt(1),rows.getInt(2),rows.getLong(3)));main.post(changed);}catch(Exception e){AppLog.w("ChannelHealth","Cache load failed",e);}});}
    private static final Map<Channel,String> keys=Collections.synchronizedMap(new WeakHashMap<Channel,String>());
    static String key(Channel c){String known=keys.get(c);if(known!=null)return known;try{java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");if(c.sourceCount()==0)digest.update((c.name+"|"+c.yangshipinPid+"|"+c.streamId).getBytes("UTF-8"));for(int i=0;i<c.sourceCount();i++){String value=c.sourceUrl(i);byte[] b=(value==null?"":value).getBytes("UTF-8");digest.update((byte)(b.length>>>24));digest.update((byte)(b.length>>>16));digest.update((byte)(b.length>>>8));digest.update((byte)b.length);digest.update(b);}StringBuilder s=new StringBuilder(64);final char[] hex="0123456789abcdef".toCharArray();for(byte b:digest.digest()){s.append(hex[(b&255)>>>4]);s.append(hex[b&15]);}String id=s.toString();keys.put(c,id);return id;}catch(Exception e){throw new IllegalStateException(e);}}
    boolean bad(Channel c){State s=cache.get(key(c));return s!=null&&s.fresh()&&s.status<0;}
    int source(Channel c,int fallback){State s=cache.get(key(c));return s!=null&&s.fresh()&&s.status>0&&s.source>=0&&s.source<c.sourceCount()?s.source:fallback;}
    void success(Channel c,int source){
        String id=key(c);State s=new State(1,source);
        synchronized(queue){
            if(closed)return;
            State previous=cache.put(id,s);
            if(queue.remove(id)!=null){complete(s);if(queue.isEmpty())finishScan();}
            if(previous==null||previous.status!=1||previous.source!=source){worker.execute(()->save(id,s));main.post(changed);}
        }
    }
    private void save(String id,State s){try{ContentValues v=new ContentValues();v.put("id",id);v.put("status",s.status);v.put("source",s.source);v.put("checked",s.checked);store.getWritableDatabase().insertWithOnConflict("health",null,v,SQLiteDatabase.CONFLICT_REPLACE);}catch(Exception e){AppLog.w("ChannelHealth","Cache save failed",e);}}
    void pause(){synchronized(queue){if(allowed)revision++;allowed=false;if(!scanning)queue.clear();epoch.incrementAndGet();for(EntryCheck entry:entries.values())entry.cancel();entries.clear();}okhttp3.Call call=webCall;if(call!=null)call.cancel();SniffedMediaProbe m=media;if(m!=null)m.clear();LiteEngine p=page;if(p!=null)p.close();}
    void resume(){synchronized(queue){if(!allowed)revision++;allowed=true;}start();}
    void cancelAll(long expectedScanId){
        synchronized(queue){
            if(!scanning||expectedScanId>0&&expectedScanId!=scanId)return;
            boolean ready=allowed;pause();queue.clear();cancelled=true;finishScan();allowed=ready;
        }
        main.post(changed);
    }
    boolean busy(){synchronized(queue){return scanning||draining;}}
    void all(ChannelCatalog.Group[] groups){
        synchronized(queue){
            if(closed||scanning)return;
            boolean ready=allowed;pause();allowed=ready;queue.clear();completed=total=reused=scanGood=scanBad=scanUnknown=0;
            scanId=Math.max(scanId+1,System.currentTimeMillis());revision++;scanStarted=SystemClock.elapsedRealtime();scanFinished=0;currentName="";scanItem=false;cancelled=false;
            HashSet<String> unique=new HashSet<>();
            for(ChannelCatalog.Group g:groups)for(Channel c:g.channels){String id=key(c);if(!unique.add(id))continue;total++;
                State known=cache.get(id);
                if(known!=null&&known.status>0&&known.fresh()){completed++;scanGood++;reused++;}
                else queue.put(id,c);
            }
            scanning=!queue.isEmpty();if(!scanning)finishScan();
        }
        main.post(changed);start();
    }
    private void complete(State state){if(scanId>0&&!scanning)return;completed++;if(scanning){if(state.status>0)scanGood++;else if(state.status<0)scanBad++;else scanUnknown++;}revision++;}
    private void finishScan(){if(scanning||scanFinished==0)scanFinished=SystemClock.elapsedRealtime();scanning=false;currentName="";scanItem=false;for(EntryCheck check:entries.values())check.cancel();entries.clear();revision++;}
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
            if(scanning)return;
            for(int d=1;d<=3;d++)for(int sign:new int[]{-1,1}){
                Channel c=list.get((current+sign*d%list.size()+list.size())%list.size());String id=key(c);
                State known=cache.get(id);if(c!=list.get(current)&&(known==null||!known.fresh()))queue.put(id,c);
            }
        }
        start();
    }
    private void start(){
        synchronized(queue){
            if(closed||!allowed||draining||queue.isEmpty())return;
            if(scanning)prefetchEntries();
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
                scanItem=scanning;if(scanItem){currentName=c.name;revision++;}
            }
            State state=probe(c,generation);
            synchronized(queue){
                if(closed||generation!=epoch.get()||!allowed)return;
                State live=cache.get(id);
                if(live!=prior&&live!=null&&live.status>0)state=live;
                cache.put(id,state);
                if(queue.remove(id)!=null)complete(state);
                if(queue.isEmpty())finishScan();else{currentName="";scanItem=false;}
                for(String source:c.urls){String url=entryUrl(source);EntryCheck entry=entries.remove(url);if(entry!=null)entry.cancel();}
            }
            save(id,state);AppLog.i("ChannelHealth","Probe channel="+c.name+" status="+state.status+" source="+state.source);main.post(changed);
        }finally{
            synchronized(queue){draining=false;}
            if(allowed&&!closed)start();
        }
    }
    private State probe(Channel c,int generation){boolean unknown=c.sourceCount()==0;long deadline=SystemClock.elapsedRealtime()+12000;int i=0;for(;i<c.sourceCount()&&allowed&&generation==epoch.get()&&SystemClock.elapsedRealtime()<deadline;i++){String url=entryUrl(c.sourceUrl(i));try{int status=probeSource(url,generation,0,deadline);if(status>0)return new State(1,i);if(status==0)unknown=true;}catch(Exception e){unknown=true;AppLog.w("ChannelHealth","Probe could not confirm media",e);}}return new State(unknown||i<c.sourceCount()?0:-1,0);}
    private static String entryUrl(String source){int label=source.indexOf('$');return label<0?source:source.substring(0,label);}
    private static boolean http(String url){return url.startsWith("http://")||url.startsWith("https://");}
    private static final class Entry {final int status;Entry(int value){status=value;}}
    private final class EntryCheck {
        volatile java.net.HttpURLConnection connection;final FutureTask<Entry> task;
        EntryCheck(String url){task=new FutureTask<>(()->readEntry(url,null,SystemClock.elapsedRealtime()+2500,this));}
        void cancel(){task.cancel(true);java.net.HttpURLConnection c=connection;if(c!=null)c.disconnect();}
    }
    private void prefetchEntries(){
        // Bound lookahead as well as concurrency, even for very large lists.
        int ahead=0;
        for(Channel c:queue.values()){
            if(ahead++>=4||entries.size()>=4)break;
            if(c.sourceCount()==0)continue;String url=entryUrl(c.sourceUrl(0));
            if(!http(url)||ChannelHealthPolicy.unresolved(url)||entries.containsKey(url))continue;
            EntryCheck check=new EntryCheck(url);entries.put(url,check);entryWorkers.execute(check.task);
        }
    }
    private Entry readEntry(String url,Map<String,String> headers,long deadline,EntryCheck check)throws Exception{
        java.net.HttpURLConnection entry=NetworkClient.openBounded(new java.net.URL(url),(int)Math.max(1,Math.min(2500,deadline-SystemClock.elapsedRealtime())));
        if(check!=null){check.connection=entry;if(check.task.isCancelled()){entry.disconnect();throw new java.io.InterruptedIOException("Probe cancelled");}}
        entry.setConnectTimeout(2500);entry.setReadTimeout(2500);entry.setRequestProperty("Range","bytes=0-4095");
        entry.setRequestProperty("User-Agent",headers!=null&&headers.get("User-Agent")!=null?headers.get("User-Agent"):HlsProxyServer.DEFAULT_USER_AGENT);
        if(headers!=null)for(String name:new String[]{"Referer","Cookie"})if(headers.get(name)!=null)entry.setRequestProperty(name,headers.get(name));
        try{
            int status=entry.getResponseCode();if(ChannelHealthPolicy.entryStatus(status)<0)return new Entry(-1);
            if(status<200||status>=300)return new Entry(0);
            String type=entry.getContentType();return new Entry(type!=null&&type.toLowerCase(Locale.US).contains("text/html")?0:1);
        }finally{entry.disconnect();if(check!=null)check.connection=null;}
    }
    private Entry entry(String url,Map<String,String> headers,int generation,long deadline)throws Exception{
        EntryCheck check; synchronized(queue){check=headers==null?entries.get(url):null;}
        if(check==null)return readEntry(url,headers,deadline,null);
        while(allowed&&generation==epoch.get()&&SystemClock.elapsedRealtime()<deadline){
            try{return check.task.get(100,TimeUnit.MILLISECONDS);}catch(TimeoutException waiting){}
        }
        throw new java.io.InterruptedIOException("Probe cancelled");
    }
    private int probeSource(String url,int generation,int redirects,long deadline)throws Exception{
        if(redirects>4||SystemClock.elapsedRealtime()>=deadline)return 0;
        if(ChannelHealthPolicy.unresolved(url))return 0;
        if(url.startsWith("webview://")){
            Integer staticResult=staticWebMedia(url,generation,deadline);
            if(staticResult!=null)return staticResult;
            if(!LiteWebPlayback.supported())return 0;
            android.app.ActivityManager.MemoryInfo info=new android.app.ActivityManager.MemoryInfo();((android.app.ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(info);
            if(info.availMem<160L*1024*1024)return 0;
            String[] found={null},navigation={null};Map<String,String>[] headers=new Map[]{null};CountDownLatch discovered=new CountDownLatch(1);int[] documentError={0};
            LiteEngine engine=LiteEngine.forHealthProbe(context,new LiteEngine.Listener(){public void frame(JSONObject packet){}public void navigate(String next){navigation[0]=next;discovered.countDown();}public void media(String source,Map<String,String> h){found[0]=source;headers[0]=h;discovered.countDown();}public void message(String message){if(message.startsWith("加载失败：")){documentError[0]=message.contains("404")||message.contains("410")?-1:0;discovered.countDown();}}},NetworkClient.sharedClient().dns());page=engine;
            try{engine.setAuditMemoryMiB(64);engine.setHeadless(true);
            engine.userAgentOverride(webAgent(url));engine.loadImages(webSettings(url).optBoolean("webViewLoadImages",true));
            WebSourceOptions options=WebSourceOptions.parse(url.substring(10),"");engine.load(options.url,options.script);long until=deadline;while(allowed&&generation==epoch.get()&&SystemClock.elapsedRealtime()<until&&!discovered.await(100,TimeUnit.MILLISECONDS)){}if(found[0]==null){if(navigation[0]!=null){engine.close();return probeSource("webview://"+navigation[0],generation,redirects+1,deadline);}return documentError[0];}return metadata(found[0],headers[0],generation,until);}finally{engine.close();page=null;}
        }
        if(!url.matches("(?i)^(https?|rtmp|rtsp|rtp|udp)://.*"))return 0;
        return metadata(url,null,generation,deadline);
    }
    private JSONObject webSettings(String source){
        SharedPreferences p=context.getSharedPreferences(MainActivity.PREFERENCES,0);String key=WebDomainSettings.key(source);
        String mode=new ChannelPlaybackMemory(p).userAgent(key,p.getString("web_view_user_agent","windows"));
        return WebDomainSettings.effective(p,key,WebDomainSettings.defaults(p,"system",mode),LiteWebPlayback.supported());
    }
    private String webAgent(String source){
        JSONObject settings=webSettings(source);String mode=WebSiteProfile.userAgentMode(settings.optString("webViewUserAgent","windows"),source),version=settings.optString("webViewBrowserVersion","native");
        String page=source.substring(10),fallback=LiteEngine.defaultUserAgent(page);
        String proposed="native".equals(mode)?fallback:"ipad".equals(mode)?DesktopWebProfile.ipadUserAgent(fallback,version):DesktopWebProfile.userAgent(mode,fallback,version);
        return SharedWebProfile.userAgent(context,page,mode,version,proposed,false);
    }
    private Integer staticWebMedia(String source,int generation,long deadline)throws Exception {
        WebSourceOptions options=WebSourceOptions.parse(source.substring(10),"");String agent=webAgent(source);
        LiteCookies.PROFILE.initialize(context);LiteCookies cookies=LiteCookies.snapshot();
        okhttp3.OkHttpClient client=NetworkClient.sharedClient().newBuilder().cookieJar(cookies).retryOnConnectionFailure(false).build();
        okhttp3.Request request=new okhttp3.Request.Builder().url(options.url).header("User-Agent",agent).header("Referer",options.url)
                .header("Accept","text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8").tag(WebResourceHub.Kind.class,WebResourceHub.Kind.DOCUMENT).build();
        okhttp3.Call call=WebResourceHub.newCall(client,request);webCall=call;
        call.timeout().timeout(Math.max(1,Math.min(4000,deadline-SystemClock.elapsedRealtime())),TimeUnit.MILLISECONDS);
        Map<String,String> headers=new HashMap<>();headers.put("User-Agent",agent);String text,pageUrl=options.url;
        try(okhttp3.Response response=call.execute()){
            if(!allowed||generation!=epoch.get())return 0;
            if(ChannelHealthPolicy.entryStatus(response.code())<0)return -1;if(!response.isSuccessful()||response.body()==null)return null;
            pageUrl=response.request().url().toString();headers.put("Referer",pageUrl);
            String type=response.header("Content-Type","");if(type.startsWith("video/")||type.startsWith("audio/"))return metadata(pageUrl,cookieHeaders(headers,cookies,pageUrl),generation,deadline);
            try(java.io.InputStream input=response.body().byteStream();java.io.ByteArrayOutputStream body=new java.io.ByteArrayOutputStream()){
                byte[] block=new byte[4096];int n;
                while(body.size()<128*1024&&allowed&&generation==epoch.get()&&SystemClock.elapsedRealtime()<deadline&&(n=input.read(block,0,Math.min(block.length,128*1024-body.size())))>0)body.write(block,0,n);
                text=new String(body.toByteArray(),"UTF-8");
            }
        }finally{if(webCall==call)webCall=null;}
        java.util.regex.Matcher media=java.util.regex.Pattern.compile("<(?:video|source)\\b[^>]*\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
        int attempts=0;
        while(media.find()&&attempts++<3&&allowed&&generation==epoch.get()&&SystemClock.elapsedRealtime()<deadline){
            String address=HlsUri.resolve(java.net.URI.create(pageUrl),media.group(1).replace("&amp;","&")).toString();
            if(address.startsWith("http://")||address.startsWith("https://"))if(metadata(address,cookieHeaders(headers,cookies,address),generation,deadline)>0)return 1;
        }
        return null;
    }
    private static Map<String,String> cookieHeaders(Map<String,String> source,LiteCookies jar,String url){
        Map<String,String> result=new HashMap<>(source);StringBuilder value=new StringBuilder();
        for(okhttp3.Cookie c:jar.loadForRequest(okhttp3.HttpUrl.parse(url))){if(value.length()>0)value.append("; ");value.append(c.name()).append('=').append(c.value());}
        if(value.length()>0)result.put("Cookie",value.toString());return result;
    }
    private int metadata(String url,Map<String,String> headers,int generation)throws Exception{return metadata(url,headers,generation,Long.MAX_VALUE);}
    private int metadata(String url,Map<String,String> headers,int generation,long deadline)throws Exception{
        if(SystemClock.elapsedRealtime()>=deadline)return 0;
        SniffedMediaProbe p=new SniffedMediaProbe(context.getSharedPreferences("tv_player",0).getString("rtsp_transport","tcp"));media=p;int[] state={0};CountDownLatch done=new CountDownLatch(1);
        try{
            if(http(url)){Entry checked=entry(url,headers,generation,deadline);if(checked.status<=0)return checked.status;}
            SniffedMediaProbe.Callback callback=r->{
                state[0]=ChannelHealthPolicy.result("ready".equals(r.status),r.hasAudio,r.hasVideo,r.width,r.height);
                done.countDown();
            };
            if(headers==null)p.submitLightweight(url,callback);else p.submit(url,headers.get("Referer"),headers.get("User-Agent"),headers.get("Cookie"),callback);
            long until=Math.min(deadline,SystemClock.elapsedRealtime()+12000);while(allowed&&generation==epoch.get()&&SystemClock.elapsedRealtime()<until&&!done.await(100,TimeUnit.MILLISECONDS)){}return state[0];
        }finally{p.close();media=null;}
    }
    JSONObject progress(){synchronized(queue){JSONObject j=new JSONObject();try{
        j.put("sessionId",sessionId).put("scanId",scanId).put("revision",revision).put("running",scanning).put("completed",completed).put("total",total)
            .put("phase",scanning?(allowed?"scanning":"waiting"):cancelled?"cancelled":scanId>0?"complete":"idle").put("current",scanItem&&allowed?currentName:"")
            .put("cached",reused).put("scanGood",scanGood).put("scanBad",scanBad).put("scanUnknown",scanUnknown)
            .put("elapsedMs",scanStarted==0?0:Math.max(0,(scanning?SystemClock.elapsedRealtime():scanFinished)-scanStarted));
    }catch(Exception ignored){}return j;}}
    JSONObject status(){JSONObject j=progress();try{int bad=0,good=0,unknown=0;Set<String> active=new HashSet<>();for(ChannelCatalog.Group g:ChannelCatalog.GROUPS)for(Channel c:g.channels)active.add(key(c));for(String id:active){State state=cache.get(id);if(state==null||!state.fresh())continue;if(state.status<0)bad++;else if(state.status>0)good++;else unknown++;}j.put("bad",bad).put("good",good).put("unknown",unknown);}catch(Exception ignored){}return j;}
    void close(){
        synchronized(queue){if(closed)return;closed=true;queue.clear();worker.execute(store::close);worker.shutdown();}
        pause();
        entryWorkers.shutdownNow();
    }
    private static final class Store extends SQLiteOpenHelper{Store(Context c){super(c,"channel-health.db",null,2);}public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE health(id TEXT PRIMARY KEY,status INTEGER NOT NULL,source INTEGER NOT NULL,checked INTEGER NOT NULL)");}public void onUpgrade(SQLiteDatabase db,int a,int b){if(a<2)db.execSQL("UPDATE health SET status=0,checked=0 WHERE status<0");}}
}
