package xiao.bu.tv;

import android.app.*;
import android.content.*;
import android.content.res.Resources;
import android.os.*;
import android.view.*;
import android.webkit.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** Same-device real-site comparison. Does not clear user cookies, storage or caches. */
public final class WebLoadBenchmarkInstrumentation extends Instrumentation {
    Bundle args; Activity host; WebView view; WebSourceView source; DesktopWebProfile desktop;
    final Handler ui = new Handler(Looper.getMainLooper());
    final ConcurrentLinkedQueue<JSONObject> interceptions = new ConcurrentLinkedQueue<>();
    long began; volatile long committed;
    interface Work { void run() throws Exception; }
    void main(Work work) throws Exception {
        CountDownLatch done = new CountDownLatch(1); Throwable[] error = {null};
        ui.post(() -> { try { work.run(); } catch (Throwable e) { error[0] = e; } finally { done.countDown(); } });
        if (!done.await(10, TimeUnit.SECONDS)) throw new AssertionError("UI stalled");
        if (error[0] != null) throw new Exception(error[0]);
    }
    static Object field(Object o, String n) throws Exception { Field f=o.getClass().getDeclaredField(n); f.setAccessible(true); return f.get(o); }
    JSONObject sample() throws Exception {
        CountDownLatch done = new CountDownLatch(1); String[] result = {null};
        main(() -> view.evaluateJavascript("(function(){var n=performance.getEntriesByType('navigation')[0]||{},p=performance.getEntriesByType('paint'),r=performance.getEntriesByType('resource');return JSON.stringify({url:location.origin+location.pathname,title:document.title,ready:document.readyState,text:document.body?(document.body.innerText||'').length:0,dom:document.getElementsByTagName('*').length,fcp:(p.filter(function(x){return x.name==='first-contentful-paint'})[0]||{}).startTime||0,responseStart:n.responseStart||0,responseEnd:n.responseEnd||0,dcl:n.domContentLoadedEventEnd||0,load:n.loadEventEnd||0,resources:r.length,slow:r.filter(function(x){return x.duration>1000}).sort(function(a,b){return b.duration-a.duration}).slice(0,8).map(function(x){var u=new URL(x.name);return {url:u.origin+u.pathname,duration:x.duration,type:x.initiatorType}})});})()", v -> {result[0]=v;done.countDown();}));
        if (!done.await(4, TimeUnit.SECONDS)) return new JSONObject().put("jsTimeout",true);
        if (result[0] == null || result[0].equals("null")) return new JSONObject();
        return new JSONObject(new JSONTokener(result[0]).nextValue().toString());
    }
    void close() throws Exception { main(() -> {
        if (source != null) { source.destroyPage(); source=null; }
        else if (view != null) { view.stopLoading(); view.destroy(); }
        if (desktop != null) {desktop.dispose();desktop=null;}
        view=null;host.setContentView(new android.widget.FrameLayout(host));
    }); }
    void open(String mode, String url, boolean images) throws Exception {
        interceptions.clear();committed=0;
        main(() -> {
            began=SystemClock.elapsedRealtime();
            if (mode.equals("production")) {
                Resources base=host.getResources();Resources local=new Resources(base.getAssets(),base.getDisplayMetrics(),base.getConfiguration()) {
                    @Override public boolean getBoolean(int id) throws NotFoundException {return id==R.bool.legacy_web_activity_enabled?false:super.getBoolean(id);}
                };
                Context ctx=new ContextThemeWrapper(host,android.R.style.Theme_Holo_NoActionBar) {@Override public Resources getResources(){return local;}};
                source=new WebSourceView(ctx);host.setContentView(source);
                source.applyConfiguration("720p",images,"windows","native",1f,false,false,false,"[]");
                source.applyInteractiveBrowser(true);source.applyMinimalMode(true);source.applyPageCover(false);source.applyAutoPlay(false);
                source.open(100,url,"",false,"网页速度测试","");view=(WebView)field(source,"webView");
            } else {
                view=new WebView(host);host.setContentView(view);WebSettings settings=view.getSettings();
                String nativeUa=settings.getUserAgentString(),ua=DesktopWebProfile.userAgent("windows",nativeUa,"native");
                settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setDatabaseEnabled(true);
                settings.setUseWideViewPort(true);settings.setLoadWithOverviewMode(true);settings.setCacheMode(WebSettings.LOAD_DEFAULT);
                settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);settings.setUserAgentString(ua);
                desktop=new DesktopWebProfile(view);desktop.update("windows",nativeUa,"native",1280,720,1f);
                view.setWebChromeClient(new WebChromeClient());
                view.setWebViewClient(new WebViewClient() {
                    @Override public void onPageCommitVisible(WebView v,String u){if(committed==0)committed=SystemClock.elapsedRealtime()-began;}
                    @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                        if (!"GET".equals(r.getMethod())) return null;
                        String u=r.getUrl().toString();
                        if(!images&&BlankWebImage.matches(u,r.getMethod(),r.isForMainFrame(),r.getRequestHeaders())) {
                            WebResourceResponse out=new WebResourceResponse("image/png",null,BlankWebImage.open());
                            Map<String,String> headers=new HashMap<>();headers.put("Cache-Control","no-store");headers.put("Access-Control-Allow-Origin","*");out.setResponseHeaders(headers);return out;
                        }
                        if (!mode.equals("shared")) return null;
                        long at=SystemClock.elapsedRealtime();
                        WebResourceResponse out=SharedWebResources.intercept(host,u,url,ua,r.getRequestHeaders(),r.isForMainFrame());
                        long elapsed=SystemClock.elapsedRealtime()-at;
                        if(elapsed>50||out!=null)try {android.net.Uri safe=android.net.Uri.parse(u);interceptions.add(new JSONObject().put("url",safe.getScheme()+"://"+safe.getAuthority()+safe.getPath()).put("ms",elapsed).put("handled",out!=null));}catch(Exception ignored){}
                        return out;
                    }
                });
                view.loadUrl(url);
            }
        });
    }
    JSONObject transportChecks() throws Exception {
        AtomicInteger requests=new AtomicInteger();
        java.net.ServerSocket server=new java.net.ServerSocket(0,8,java.net.InetAddress.getByName("127.0.0.1"));
        Thread worker=new Thread(()->{
            while(!server.isClosed())try(java.net.Socket socket=server.accept()){
                socket.setSoTimeout(3000);BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),"UTF-8"));
                String request=input.readLine(),line;while((line=input.readLine())!=null&&!line.isEmpty()){}
                requests.incrementAndGet();boolean cacheable=request!=null&&(request.contains("/cached.js")||request.contains("large.js")||request.contains("/gzip.js"));
                byte[] body=request!=null&&request.contains("large.js")?new byte[65536]:(cacheable?"window.cacheFixture=true;":"{\"dynamic\":true}").getBytes("UTF-8");
                boolean gzip=request!=null&&request.contains("gzip");if(gzip){ByteArrayOutputStream compressed=new ByteArrayOutputStream();try(java.util.zip.GZIPOutputStream zip=new java.util.zip.GZIPOutputStream(compressed)){zip.write(body);}body=compressed.toByteArray();}
                OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: "+(cacheable?"application/javascript":"application/json")+"\r\nCache-Control: "+(cacheable?"public, max-age=300":"no-store")+(gzip?"\r\nContent-Encoding: gzip":"")+"\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));out.write(body);out.flush();
            }catch(Exception ignored){}
        },"web-cache-fixture");worker.setDaemon(true);worker.start();
        String base="http://127.0.0.1:"+server.getLocalPort(),ua="WebCacheFixture";
        try {
            main(()->{view=new WebView(host);host.setContentView(view);});
            int before=requests.get();
            try(okhttp3.Response r=new SharedWebResourcesLoader(getTargetContext()).load(base+"/unsupported.js",ua)) { if(r.code()!=200)throw new AssertionError("Fixture response failed"); }
            int genericRequests=requests.get()-before;
            before=requests.get();WebResourceResponse declined=SharedWebResources.intercept(getTargetContext(),base+"/unsupported.js",base,ua,null,false);
            int browserRequests=requests.get()-before;
            if(declined!=null||genericRequests!=2||browserRequests!=1)throw new AssertionError("Duplicate fallback: generic="+genericRequests+" browser="+browserRequests);
            before=requests.get();
            for(int i=0;i<2;i++) {
                WebResourceResponse hit=SharedWebResources.intercept(getTargetContext(),base+"/cached.js",base,ua,null,false);
                if(hit==null)throw new AssertionError("Shared cached script unavailable");
                try(InputStream in=hit.getData()){byte[] b=new byte[1024];while(in.read(b)!=-1){}}
            }
            int sharedDownloads=requests.get()-before;
            if(sharedDownloads!=1)throw new AssertionError("Shared cache was not reused: "+sharedDownloads);
            android.net.Uri authority=android.net.Uri.parse("content://"+getTargetContext().getPackageName()+".webresources");
            String probe="inline-"+UUID.randomUUID();Bundle open=new Bundle();open.putString("url",base+"/cached.js");
            open.putStringArrayList("names",new ArrayList<>(Arrays.asList("User-Agent","Referer")));open.putStringArrayList("values",new ArrayList<>(Arrays.asList(ua,base)));
            Bundle reply=getTargetContext().getContentResolver().call(authority,"open",probe,open);byte[] inline=reply.getByteArray("body");
            if("true".equals(args.getString("inlineExpected"))&&(inline==null||inline.length!=25||!reply.getBoolean("cached")||reply.getBoolean("network")))throw new AssertionError("Small fresh cache hit did not use inline body");
            if(inline==null)try(ParcelFileDescriptor fd=getTargetContext().getContentResolver().openFileDescriptor(authority.buildUpon().appendPath("data").appendPath(probe).build(),"r");InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(input.read()!=-1){}}
            JSONArray timings=new JSONArray();
            for(String path:new String[]{"/cached.js","/large.js","/gzip.js","/gzip-large.js"}) {
                int hits=requests.get();long total=0;ArrayList<Long> elapsed=new ArrayList<>();
                for(int i=0;i<210;i++){
                    long start=System.nanoTime();WebResourceResponse hit=SharedWebResources.intercept(getTargetContext(),base+path,base,ua,null,false);if(hit==null)throw new AssertionError("Warm resource missing");
                    int received=0;try(InputStream input=hit.getData()){byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1)received+=n;}
                    if(received!=(path.contains("large")?65536:25))throw new AssertionError("Cached body lost bytes: "+path+" "+received);
                    long took=System.nanoTime()-start;if(i>=10){total+=took;elapsed.add(took);}
                }
                Collections.sort(elapsed);timings.put(new JSONObject().put("path",path).put("reads",200).put("meanUs",total/200000.0).put("p50Us",elapsed.get(100)/1000.0).put("p95Us",elapsed.get(190)/1000.0).put("upstreamRequests",requests.get()-hits));
            }
            String gzipProbe="gzip-"+UUID.randomUUID();open.putString("url",base+"/gzip.js");Bundle gzipReply=getTargetContext().getContentResolver().call(authority,"open",gzipProbe,open);byte[] gzipInline=gzipReply.getByteArray("body");
            if("true".equals(args.getString("inlineExpected"))&&(gzipInline==null||gzipInline.length!=25||gzipReply.getLong("length")!=25))throw new AssertionError("Decoded gzip cache did not use the bounded inline path");
            if(gzipInline==null)try(ParcelFileDescriptor fd=getTargetContext().getContentResolver().openFileDescriptor(authority.buildUpon().appendPath("data").appendPath(gzipProbe).build(),"r");InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(input.read()!=-1){}}
            return new JSONObject().put("genericLoaderFallbackRequests",genericRequests).put("webViewBridgeRequestsBeforeNativeFallback",browserRequests).put("twoSharedScriptReadsDownloads",sharedDownloads).put("inlineFreshCache",inline!=null).put("inlineGzipFreshCache",gzipInline!=null).put("cachedReadTimings",timings).put("passed",true);
        }finally{server.close();}
    }
    private static final class SharedWebResourcesLoader {
        final Context context; SharedWebResourcesLoader(Context c){context=c;}
        okhttp3.Response load(String url,String ua)throws Exception {
            // The public hub retains the lightweight engine's normal network fallback.
            return WebResourceHub.newCall(NetworkClient.sharedClient(),new okhttp3.Request.Builder().url(url).header("User-Agent",ua).build()).execute();
        }
    }
    @Override public void onCreate(Bundle value) {args=value;start();}
    @Override public void onStart() {
        Bundle status=new Bundle();JSONObject report=new JSONObject();JSONArray cases=new JSONArray();int code=0;
        File dir=new File(getTargetContext().getExternalFilesDir(null),"web-load-benchmark");dir.mkdirs();
        String id=args.getString("id","baseline");
        try {
            if(Build.VERSION.SDK_INT<21&&!"true".equals(args.getString("popupOnly"))||!id.matches("[a-zA-Z0-9_-]{1,50}"))throw new AssertionError("Unsupported fixture");
            SharedWebResources.install(getTargetContext());
            if("1".equals(args.getString("externalLaunch"))){
                ActivityMonitor launcher=addMonitor("xiao.bu.tv.WebPlaybackTestActivity",null,false);Bundle phase=new Bundle();phase.putString("phase","await_host");sendStatus(0,phase);
                host=launcher.waitForActivityWithTimeout(20000);removeMonitor(launcher);if(host==null)throw new AssertionError("Benchmark host did not launch");
            }else host=startActivitySync(new Intent().setClassName(getTargetContext(),"xiao.bu.tv.WebPlaybackTestActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            if("true".equals(args.getString("fixtureOnly"))) {
                report.put("transportChecks",transportChecks()).put("completed",true);code=-1;return;
            }
            if("true".equals(args.getString("popupOnly"))) {
                report.put("popupChecks",UpdatePopupChecks.run(this,host)).put("completed",true);code=-1;return;
            }
            int rounds=Integer.parseInt(args.getString("rounds","2")),seconds=Integer.parseInt(args.getString("seconds","18"));
            boolean images="true".equals(args.getString("images"));
            for(int round=0;round<rounds;round++)for(String url:new String[]{"https://www.yangshipin.cn/","https://www.iqiyi.com/"}) {
                String[] modes=round%2==0?new String[]{"native","shared","production"}:new String[]{"production","shared","native"};
                for(String mode:modes) {
                    open(mode,url,images);JSONArray samples=new JSONArray();long firstText=0;
                    for(int i=0;i<seconds*2;i++) {
                        SystemClock.sleep(500);JSONObject row=sample().put("wallMs",SystemClock.elapsedRealtime()-began);samples.put(row);
                        if(firstText==0&&row.optInt("text")>=100)firstText=row.optLong("wallMs");
                    }
                    JSONObject row=new JSONObject().put("round",round).put("mode",mode).put("url",url).put("firstText100Ms",firstText).put("commitMs",committed).put("samples",samples).put("interceptions",new JSONArray(interceptions));cases.put(row);
                    android.graphics.Bitmap bitmap=getUiAutomation().takeScreenshot();if(bitmap!=null)try(OutputStream out=new FileOutputStream(new File(dir,id+"-"+round+"-"+mode+"-"+(url.contains("iqiyi")?"iqiyi":"ysp")+".png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);bitmap.recycle();}
                    report.put("cases",cases).put("images",images).put("ua","Windows identity matching production").put("seconds",seconds).put("sdk",Build.VERSION.SDK_INT);
                    try(Writer out=new OutputStreamWriter(new FileOutputStream(new File(dir,id+".json")),"UTF-8")){out.write(report.toString(2));}
                    Bundle progress=new Bundle();progress.putString("stream",id+" "+round+" "+mode+" "+url+" text100="+firstText+"\n");sendStatus(0,progress);close();SystemClock.sleep(250);
                }
            }
            report.put("completed",true);code=-1;
        } catch(Throwable error) {try{report.put("failure",android.util.Log.getStackTraceString(error));}catch(Exception ignored){}}
        finally {
            try{close();main(()->host.finish());}catch(Exception ignored){}
            try(Writer out=new OutputStreamWriter(new FileOutputStream(new File(dir,id+".json")),"UTF-8")){out.write(report.toString(2));}catch(Exception ignored){}
            status.putString("stream",id+" completed="+report.optBoolean("completed")+" cases="+cases.length()+" "+report.optString("failure"));finish(code,status);
        }
    }
}
