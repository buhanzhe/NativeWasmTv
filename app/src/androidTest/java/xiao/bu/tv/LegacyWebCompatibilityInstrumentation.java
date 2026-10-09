package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import org.json.*;

/** Measures actual document readiness through the production isolated browser. */
public final class LegacyWebCompatibilityInstrumentation extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle value) { super.onCreate(value); args=value; start(); }
    private static Object field(Object object,String name)throws Exception {
        Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    @Override public void onStart() {
        if (args.containsKey("generic")) { generic(); return; }
        if (args.containsKey("transpiler")) { transpiler(); return; }
        if (args.containsKey("benchmark")) { benchmark(); return; }
        if (args.containsKey("fixture")) { fixture(); return; }
        if (args.containsKey("native")) { nativePlayback(); return; }
        Bundle result=new Bundle();int code=-1;MainActivity activity=null;PrintWriter writer=null;
        try {
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(1500);
            final MainActivity owner=activity;
            WebSourceView source=(WebSourceView)field(activity,"webSourceView");
            runOnMainSync(()->source.setLegacyTranspilation(args.containsKey("transpile")));
            runOnMainSync(()->{try{
                ((java.util.concurrent.atomic.AtomicInteger)field(owner,"catalogLoadGeneration")).incrementAndGet();
                Field auto=MainActivity.class.getDeclaredField("webViewAutoPlaySniffed");auto.setAccessible(true);auto.setBoolean(owner,false);
                for(String name:new String[]{"cancelCustomSourceTimeout","clearPendingPlayer","cancelRemoteResolve","releasePlayer","closeWebSource"}) {
                    Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(owner);
                }
            }catch(Exception e){throw new RuntimeException(e);}});
            writer=new PrintWriter(new File(getTargetContext().getExternalFilesDir(null),
                    "web-compat-"+args.getString("label","test")+".jsonl"),"UTF-8");
            String urls=args.getString("urls");
            if(urls==null)try(BufferedReader reader=new BufferedReader(new InputStreamReader(
                    new FileInputStream(args.getString("list")),"UTF-8"))) {
                StringBuilder list=new StringBuilder();String line;
                while((line=reader.readLine())!=null)if(!line.trim().isEmpty())list.append(line.trim()).append('|');
                urls=list.toString();
            }
            int limit=Integer.parseInt(args.getString("timeoutSeconds","15"))*1000,ready=0,settle=Integer.parseInt(args.getString("settleSeconds","0"))*1000;
            for(String url:urls.split("\\|")) {
                if(url.isEmpty())continue;
                long begin=SystemClock.elapsedRealtime();
                runOnMainSync(()->source.open(7654,url,"",false));
                JSONObject snapshot=new JSONObject();boolean complete=false,dumped=false;long playedAt=0,completedAt=0;double firstTime=0;boolean playback=args.containsKey("playback");
                while(SystemClock.elapsedRealtime()-begin<limit) {
                    CountDownLatch done=new CountDownLatch(1);JSONObject[] value={new JSONObject()};
                    runOnMainSync(()->source.inspectCompatibility(v->{value[0]=v;done.countDown();}));
                    if(done.await(4,TimeUnit.SECONDS))snapshot=value[0];
                    if(args.containsKey("dumpStall")&&!dumped&&snapshot.length()==0&&SystemClock.elapsedRealtime()-begin>5000){
                        LegacyWebSession session=(LegacyWebSession)field(source,"legacy");
                        if(session!=null&&session.pid>0){android.os.Process.sendSignal(session.pid,3);dumped=true;}
                    }
                    if(!snapshot.optString("url").isEmpty()&&!snapshot.optString("url").startsWith("about:")
                            &&"complete".equals(snapshot.optString("ready"))) {
                        complete=true;
                        if(completedAt==0)completedAt=SystemClock.elapsedRealtime();
                        if(!playback&&SystemClock.elapsedRealtime()-completedAt>=settle){ready++;break;}
                    }
                    JSONObject video=snapshot.optJSONObject("video");
                    if(playback&&video!=null&&!video.optBoolean("paused",true)&&video.optInt("ready")>=2&&video.optDouble("time")>0){
                        if(playedAt==0){playedAt=SystemClock.elapsedRealtime();firstTime=video.optDouble("time");}
                        if(SystemClock.elapsedRealtime()-playedAt>=10000&&video.optDouble("time")-firstTime>=8){ready++;break;}
                    }
                    SystemClock.sleep(400);
                }
                JSONObject row=new JSONObject().put("requested",url).put("elapsedMs",SystemClock.elapsedRealtime()-begin)
                        .put("snapshot",snapshot).put("retained",source.hasRetainedPage()).put("complete",complete)
                        .put("playbackAdvanced",playedAt>0&&snapshot.optJSONObject("video")!=null&&snapshot.optJSONObject("video").optDouble("time")-firstTime>=8);
                writer.println(row);writer.flush();System.out.println(row);
                if(args.containsKey("proof")&&!"PASS".equals(snapshot.optString("genericProof")))throw new AssertionError("Production generic proof missing: "+row);
                runOnMainSync(source::closePage);SystemClock.sleep(400);
            }
            result.putString("stream","Completed browser probes, ready="+ready+"\n");
        }catch(Throwable error){code=0;result.putString("stream",android.util.Log.getStackTraceString(error));}
        finally {if(writer!=null)writer.close();if(activity!=null)activity.finish();}
        finish(code,result);
    }
    private void nativePlayback(){
        Bundle result=new Bundle();int code=-1;MainActivity activity=null;PrintWriter writer=null;
        java.util.Map<String,?> saved=getTargetContext().getSharedPreferences(MainActivity.PREFERENCES,0).getAll();
        ChannelCatalog.Group[] groups=ChannelCatalog.GROUPS;
        try{
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(1500);
            groups=ChannelCatalog.GROUPS;final MainActivity owner=activity;
            final WebSourceView source=(WebSourceView)field(owner,"webSourceView");
            runOnMainSync(()->source.setLegacyTranspilation(args.containsKey("transpile")));
            writer=new PrintWriter(new File(getTargetContext().getExternalFilesDir(null),"web-compat-"+args.getString("label","native")+".jsonl"),"UTF-8");
            java.util.List<String> urls=new java.util.ArrayList<>();
            if(args.containsKey("urls"))urls.add(args.getString("urls"));else try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(args.getString("list")),"UTF-8"))){String s;while((s=r.readLine())!=null)if(!s.trim().isEmpty())urls.add(s.trim());}
            for(String url:urls){
                long began=SystemClock.elapsedRealtime();
                runOnMainSync(()->{try{
                    ((java.util.concurrent.atomic.AtomicInteger)field(owner,"catalogLoadGeneration")).incrementAndGet();
                    Field auto=MainActivity.class.getDeclaredField("webViewAutoPlaySniffed");auto.setAccessible(true);auto.setBoolean(owner,true);
                    Field switchSource=MainActivity.class.getDeclaredField("autoSwitchSource");switchSource.setAccessible(true);switchSource.setBoolean(owner,false);
                    ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("WEB_COMPAT",ChannelCatalog.SOURCE_CUSTOM,new Channel[]{new Channel("1","WEB_COMPAT","diag","webview://"+url,null,null)})};
                    for(String name:new String[]{"currentGroupIndex","currentChannelIndex","currentSourceIndex"}){Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.setInt(owner,0);}
                    Method start=MainActivity.class.getDeclaredMethod("startChannel",int.class);start.setAccessible(true);start.invoke(owner,0);
                }catch(Exception e){throw new RuntimeException(e);}});
                tv.danmaku.ijk.media.player.IjkMediaPlayer player=null;boolean hadPage=false,pageRetired=false;
                while(SystemClock.elapsedRealtime()-began<45000){
                    if((Boolean)field(owner,"videoRenderingStarted")){Object p=field(owner,"player");if(p instanceof tv.danmaku.ijk.media.player.IjkMediaPlayer){player=(tv.danmaku.ijk.media.player.IjkMediaPlayer)p;break;}}
                    hadPage|=source.hasRetainedPage();
                    if(hadPage&&!source.hasRetainedPage()&&field(owner,"player")==null&&field(owner,"pendingPlayerChannel")==null){pageRetired=true;break;}
                    SystemClock.sleep(100);
                }
                long first=SystemClock.elapsedRealtime()-began;JSONObject row=new JSONObject().put("requested",url).put("firstFrame",player!=null).put("firstFrameMs",first).put("pageRetired",pageRetired);
                if(player!=null){long position=player.getCurrentPosition();int buffers=(Integer)field(owner,"bufferingEventId");SystemClock.sleep(10000);
                    row.put("fps",player.getVideoOutputFramesPerSecond()).put("positionAdvancedMs",player.getCurrentPosition()-position).put("rebufferEvents",(Integer)field(owner,"bufferingEventId")-buffers);
                    String shotName="web-compat-"+args.getString("label","native")+"-"+urls.indexOf(url)+".png";
                    android.graphics.Bitmap shot=getUiAutomation().takeScreenshot();if(shot!=null){try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),shotName))){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);row.put("screenshot",shotName);}finally{shot.recycle();}}
                }
                CountDownLatch inspected=new CountDownLatch(1);JSONObject[] snapshot={new JSONObject()};
                runOnMainSync(()->source.inspectCompatibility(value->{snapshot[0]=value;inspected.countDown();}));
                inspected.await(4,TimeUnit.SECONDS);
                row.put("snapshot",snapshot[0]).put("retained",source.hasRetainedPage());
                writer.println(row);writer.flush();System.out.println(row);
                runOnMainSync(()->{try{for(String name:new String[]{"closeWebSource","releasePlayer"}){Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(owner);}}catch(Exception e){throw new RuntimeException(e);}});
            }
            result.putString("stream","Completed native handoff probes\n");
        }catch(Throwable error){code=0;result.putString("stream",android.util.Log.getStackTraceString(error));}
        finally{
            if(writer!=null)writer.close();ChannelCatalog.GROUPS=groups;
            android.content.SharedPreferences.Editor e=getTargetContext().getSharedPreferences(MainActivity.PREFERENCES,0).edit().clear();
            for(java.util.Map.Entry<String,?> v:saved.entrySet()){Object x=v.getValue();String k=v.getKey();if(x instanceof String)e.putString(k,(String)x);else if(x instanceof Boolean)e.putBoolean(k,(Boolean)x);else if(x instanceof Integer)e.putInt(k,(Integer)x);else if(x instanceof Long)e.putLong(k,(Long)x);else if(x instanceof Float)e.putFloat(k,(Float)x);else if(x instanceof java.util.Set)e.putStringSet(k,(java.util.Set<String>)x);}e.commit();
            if(activity!=null)activity.finish();
        }
        finish(code,result);
    }
    private void fixture() {
        Bundle result=new Bundle();android.webkit.WebView[] view={null};CountDownLatch loaded=new CountDownLatch(1);
        try {
            String script;
            try(InputStream input=getTargetContext().getAssets().open("control/userscripts/kitkat-tv.user.js")) {
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;
                while((n=input.read(b))!=-1)bytes.write(b,0,n);script=bytes.toString("UTF-8");
            }
            final String source=script;
            runOnMainSync(()->{
                view[0]=new android.webkit.WebView(getTargetContext());view[0].getSettings().setJavaScriptEnabled(true);
                view[0].setWebChromeClient(new android.webkit.WebChromeClient(){
                    @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage m){android.util.Log.i("CompatFixture",m.message());return true;}
                });
                view[0].setWebViewClient(new android.webkit.WebViewClient(){
                    @Override public void onPageFinished(android.webkit.WebView v,String url){v.evaluateJavascript("(function(){return {compat:window.__ntvTvCompatibility,first:window.firstSiteScript,fetch:typeof fetch,params:typeof URLSearchParams,modern:'noModule' in document.createElement('script'),ua:navigator.userAgent};})()",value->{result.putString("stream",value);loaded.countDown();});}
                });
                WebPageScriptManager manager=new WebPageScriptManager(view[0]);manager.update(false,false,false,"");
                String prelude=args.containsKey("policy")?manager.legacyDocumentPrelude():"<script>"+source+"</script>";
                view[0].loadDataWithBaseURL("https://live.jstv.com/","<!doctype html><html><head>"+prelude+"<script>window.firstSiteScript={fetch:typeof fetch,params:typeof URLSearchParams,set:typeof Set,map:typeof Map,symbol:typeof Symbol,weakMap:typeof WeakMap};try{window.firstSiteScript.collections=Array.from(new Set([1,2,1])).join(',')==='1,2'&&new Map([[NaN,3]]).get(NaN)===3;var k=Object.freeze({}),wm=new WeakMap();wm.set(k,4);var sym=Symbol('key'),obj={};obj[sym]=7;window.firstSiteScript.symbols=wm.get(k)===4&&Object.keys(obj).length===0&&Object.getOwnPropertySymbols(obj)[0]===sym;window.firstSiteScript.promise=typeof Promise.prototype['finally']==='function';}catch(e){window.firstSiteScript.error=String(e);}</script></head><body>fixture</body></html>","text/html","UTF-8",null);
            });
            if(!loaded.await(10,TimeUnit.SECONDS))throw new AssertionError("Fixture timeout");
            JSONObject snapshot=new JSONObject(result.getString("stream"));JSONObject first=snapshot.getJSONObject("first");
            if(!"function".equals(first.optString("fetch"))||!"function".equals(first.optString("params")))throw new AssertionError("Missing API before first site script: "+snapshot);
            if(android.os.Build.VERSION.SDK_INT<=19&&!snapshot.has("compat"))throw new AssertionError("KitKat shim absent");
            if(!first.optBoolean("collections")||!first.optBoolean("symbols")||!first.optBoolean("promise"))throw new AssertionError("core-js behavior failed before site scripts: "+snapshot);
            runOnMainSync(()->view[0].destroy());finish(-1,result);
        }catch(Throwable error){result.putString("stream",android.util.Log.getStackTraceString(error));finish(0,result);}
    }
    private void benchmark(){
        Bundle result=new Bundle();PrintWriter writer=null;
        try{
            String[] scripts=new String[2];
            try(InputStream input=new FileInputStream(args.getString("baseline"))){scripts[0]=scriptText(input);}
            try(InputStream input=getTargetContext().getAssets().open("control/userscripts/kitkat-tv.user.js")){scripts[1]=scriptText(input);}
            writer=new PrintWriter(new File(getTargetContext().getExternalFilesDir(null),"web-compat-corejs-benchmark.jsonl"),"UTF-8");
            for(int sample=0;sample<10;sample++)for(int variant=0;variant<2;variant++){
                final android.webkit.WebView[] view={null};CountDownLatch done=new CountDownLatch(1);String[] snapshot={null};
                final String code=scripts[variant];
                runOnMainSync(()->{
                    view[0]=new android.webkit.WebView(getTargetContext());view[0].getSettings().setJavaScriptEnabled(true);
                    view[0].setWebViewClient(new android.webkit.WebViewClient(){
                        @Override public void onPageFinished(android.webkit.WebView v,String url){v.evaluateJavascript("JSON.stringify({elapsedMs:window.benchmarkElapsed,compat:window.__ntvTvCompatibility,set:typeof Set,map:typeof Map,symbol:typeof Symbol,heap:performance.memory&&performance.memory.usedJSHeapSize})",value->{snapshot[0]=value;done.countDown();});}
                    });
                    String literal=JSONObject.quote(code).replace("<","\\u003c");
                    view[0].loadDataWithBaseURL("https://live.jstv.com/","<!doctype html><html><head><script>var started=performance.now();(0,eval)("+literal+");window.benchmarkElapsed=performance.now()-started;</script></head><body>benchmark</body></html>","text/html","UTF-8",null);
                });
                boolean loaded=done.await(10,TimeUnit.SECONDS);
                runOnMainSync(()->{if(view[0]!=null)view[0].destroy();});
                if(!loaded)throw new AssertionError("Benchmark timeout");
                Object decoded=new JSONTokener(snapshot[0]).nextValue();
                JSONObject row=new JSONObject(decoded.toString());
                if(!row.has("compat")||!row.has("elapsedMs"))throw new AssertionError("Benchmark script failed: "+row);
                if(variant==1&&!"function".equals(row.optString("set")))throw new AssertionError("Missing Set");
                row.put("variant",variant==0?"before":"corejs").put("sample",sample);writer.println(row);writer.flush();
            }
            result.putString("stream","Completed 10 alternating samples per bundle\n");finish(-1,result);
        }catch(Throwable error){result.putString("stream",android.util.Log.getStackTraceString(error));finish(0,result);}
        finally{if(writer!=null)writer.close();}
    }
    private static String scriptText(InputStream input)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=input.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");
    }
    private void transpiler(){
        Bundle result=new Bundle();PrintWriter writer=null;
        try{
            LegacyJsTranspiler transformer=LegacyJsTranspiler.get(getTargetContext());
            writer=new PrintWriter(new File(getTargetContext().getExternalFilesDir(null),"web-compat-transpiler.jsonl"),"UTF-8");
            String runTag=java.util.UUID.randomUUID().toString();
            String modern="const fn=(x=2)=>x+1; class Example { value(){return fn();} } async function run(){return new Example().value();} run().then(x=>window.proof=x);/*"+runTag+"*/";
            long started=SystemClock.elapsedRealtime();JSONObject first=transformer.transform(modern,"https://generic.invalid/modern.js",false);
            first.remove("code");first.put("elapsedMs",SystemClock.elapsedRealtime()-started).put("case","cold");writer.println(first);writer.flush();
            started=SystemClock.elapsedRealtime();JSONObject cached=transformer.transform(modern,"https://generic.invalid/modern.js",false);
            if(!cached.optBoolean("cacheHit"))throw new AssertionError("No compiler cache hit");
            cached.remove("code");cached.put("elapsedMs",SystemClock.elapsedRealtime()-started).put("case","cached");writer.println(cached);writer.flush();
            started=SystemClock.elapsedRealtime();JSONObject updated=transformer.transform(modern+"window.sourceChanged=true;","https://generic.invalid/modern.js",false);
            if(updated.optBoolean("cacheHit"))throw new AssertionError("Changed source used stale cache");updated.remove("code");updated.put("elapsedMs",SystemClock.elapsedRealtime()-started).put("case","changed-source");writer.println(updated);writer.flush();
            String version=(String)field(transformer,"compilerVersion");
            java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");StringBuilder hash=new StringBuilder();
            for(byte b:digest.digest((version+"\nfalse\nhttps://generic.invalid/modern.js\n"+modern).getBytes("UTF-8")))hash.append(String.format(java.util.Locale.US,"%02x",b&255));
            File codeFile=new File(new File(getTargetContext().getCacheDir(),"legacy-js-cache"),hash+".js");
            if(!codeFile.delete())throw new AssertionError("Synthetic cached output absent");
            JSONObject repaired=transformer.transform(modern,"https://generic.invalid/modern.js",false);
            if(repaired.optBoolean("cacheHit"))throw new AssertionError("Missing cached output not rebuilt");repaired.remove("code");repaired.put("case","missing-cache-rebuilt");writer.println(repaired);writer.flush();
            for(int size:args.containsKey("quick")?new int[0]:new int[]{10000,100000,300000}){
                StringBuilder code=new StringBuilder();int i=0;while(code.length()<size)code.append("const value").append(i++).append("=(x=1)=>x+2;\n");
                code.append("/*").append(runTag).append("*/");
                started=SystemClock.elapsedRealtime();JSONObject probe;
                try{probe=transformer.transform(code.toString(),"https://generic.invalid/size-"+size+".js",false);}
                catch(Exception limit){probe=new JSONObject().put("boundedFailure",limit.getMessage());}
                probe.remove("code");probe.put("elapsedMs",SystemClock.elapsedRealtime()-started).put("bytes",code.length()).put("case","size");writer.println(probe);writer.flush();
            }
            JSONObject plain=transformer.transform("var answer=42;","https://generic.invalid/es5.js",false);if(plain.optBoolean("changed"))throw new AssertionError("ES5 modified");
            boolean failed=false;try{transformer.transform("const = ;","https://generic.invalid/broken.js",false);}catch(Exception expected){failed=true;}
            if(!failed)throw new AssertionError("Malformed JS accepted");
            final String[] bridge={null};NativeQuickJs.Host host=new NativeQuickJs.Host(){
                public String invoke(int operation,String[] values){if(operation==5)bridge[0]=values[0];return operation==0?values[0]:null;}
                public boolean isCancelled(){return false;}
            };
            NativeQuickJs.execute("Promise.resolve(40n+2n).then(x=>NtvCjsBridge.complete(String(x)+'中文😀'))",host);
            if(!"42中文😀".equals(bridge[0]))throw new AssertionError("Original QuickJS bridge regression");
            result.putString("stream","PASS native compiler, cache, size probes, ES5 pass-through and malformed source\n");finish(-1,result);
        }catch(Throwable error){result.putString("stream",android.util.Log.getStackTraceString(error));finish(0,result);}
        finally{if(writer!=null)writer.close();}
    }
    private void generic(){
        Bundle result=new Bundle();PrintWriter writer=null;
        try{
            writer=new PrintWriter(new File(getTargetContext().getExternalFilesDir(null),"web-compat-"+args.getString("label","generic")+".jsonl"),"UTF-8");
            boolean adapt=!"before".equals(args.getString("mode"));
            for(String url:args.getString("generic").split("\\|")){
                final android.webkit.WebView[] view={null};final String[] proof={""};
                final LegacyWebTranspilation transformer=new LegacyWebTranspilation(getTargetContext());transformer.navigate(url,"");
                final long begin=SystemClock.elapsedRealtime();
                runOnMainSync(()->{
                    view[0]=new android.webkit.WebView(getTargetContext());view[0].getSettings().setJavaScriptEnabled(true);
                    WebPageScriptManager manager=new WebPageScriptManager(view[0]);manager.update(false,false,false,"");final String prelude=manager.legacyDocumentPrelude(),ua=view[0].getSettings().getUserAgentString();
                    view[0].setWebChromeClient(new android.webkit.WebChromeClient(){@Override public boolean onConsoleMessage(android.webkit.ConsoleMessage m){android.util.Log.i("GenericFixture",m.message());return true;}});
                    view[0].setWebViewClient(new android.webkit.WebViewClient(){
                        @Override public android.webkit.WebResourceResponse shouldInterceptRequest(android.webkit.WebView v,String request){
                            if(request.equals(url))return LegacyWebHttp.intercept(request,ua,"",(u,c)->{},u->{},false,prelude,adapt?transformer:null);
                            return adapt?transformer.resource(request,ua):null;
                        }
                    });view[0].loadUrl(url);
                });
                long elapsed=0;boolean pass=false;
                while(SystemClock.elapsedRealtime()-begin<30000){
                    CountDownLatch done=new CountDownLatch(1);
                    runOnMainSync(()->view[0].evaluateJavascript("JSON.stringify({proof:window.__ntvGenericProof||'',ready:document.readyState,errors:window.__ntvCompatErrors||[]})",value->{proof[0]=value;done.countDown();}));
                    done.await(2,TimeUnit.SECONDS);elapsed=SystemClock.elapsedRealtime()-begin;
                    if(proof[0].contains("PASS")){pass=true;break;}if(!adapt&&elapsed>3000)break;SystemClock.sleep(150);
                }
                JSONObject row=new JSONObject().put("url",url).put("adapt",adapt).put("pass",pass).put("elapsedMs",elapsed).put("snapshot",proof[0]).put("transpiler",transformer.stats());
                writer.println(row);writer.flush();System.out.println(row);runOnMainSync(()->view[0].destroy());
                if(adapt&&!pass)throw new AssertionError("Generic fixture failed: "+row);
            }
            result.putString("stream","PASS generic page probes\n");finish(-1,result);
        }catch(Throwable error){result.putString("stream",android.util.Log.getStackTraceString(error));finish(0,result);}
        finally{if(writer!=null)writer.close();}
    }
}
