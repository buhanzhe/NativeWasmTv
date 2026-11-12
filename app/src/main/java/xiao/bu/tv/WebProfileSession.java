package xiao.bu.tv;

import android.content.Context;
import android.os.*;
import android.webkit.*;
import java.util.*;
import org.json.*;

/** UI-thread bridge. Never queries CookieManager from a network interceptor. */
final class WebProfileSession {
    private static final String PREFIX="ntv-profile:";
    private final Context context;private final WebView view;private final Runnable retry;private final Handler ui=new Handler(Looper.getMainLooper());
    private String page="",lease="",nonce="",bootstrap="";private int generation;private boolean bootstrapping;
    private final ArrayList<Runnable> flushed=new ArrayList<>();
    WebProfileSession(Context c,WebView v,Runnable retry){context=c.getApplicationContext();view=v;this.retry=retry;}
    boolean bootstrap(String url){return url!=null&&url.equals(bootstrap);}
    boolean bootstrapDocument(){return bootstrapping||bootstrap(view.getUrl());}
    boolean active(){return !bootstrapDocument()&&!lease.isEmpty()&&!SharedWebProfile.origin(page).isEmpty()&&!bootstrapping;}
    void load(String url){
        final int epoch=++generation;page=url;nonce=UUID.randomUUID().toString();bootstrap="";bootstrapping=false;
        try{
            Bundle args=new Bundle();args.putString("url",url);Bundle reply=SharedWebProfile.rpc(context,"open",args);lease=reply.getString("lease","");
            JSONObject state=new JSONObject(SharedWebProfile.unpack(reply,"state"));JSONArray cookies=state.optJSONArray("cookies");
            Runnable navigate=()->{if(epoch!=generation)return;try{
                if(state.optBoolean("storageKnown")){
                    JSONObject storage=state.optJSONObject("storage");String payload=storage==null?"{}":storage.toString();
                    bootstrap=okhttp3.HttpUrl.parse(url).newBuilder().encodedPath("/.__ntv_profile_"+nonce).query(null).fragment(null).build().toString();bootstrapping=true;
                    String script="(function(){try{var v=JSON.parse("+JSONObject.quote(payload)+");localStorage.clear();Object.keys(v).forEach(function(k){localStorage.setItem(k,v[k])})}catch(e){}prompt("+JSONObject.quote(PREFIX+nonce+":ready")+",'')})()";
                    ui.postDelayed(()->{if(epoch==generation&&bootstrapping){bootstrapping=false;retry.run();AppLog.d("WebProfile","Storage restore callback timed out; renderer retry");}},1500);
                    view.onResume();
                    view.loadDataWithBaseURL(bootstrap,"<!doctype html><script>"+script.replace("</","<\\/")+"</script>","text/html","UTF-8",null);
                }else view.loadUrl(url);
            }catch(RuntimeException error){bootstrapping=false;view.loadUrl(url);}};
            if(cookies==null||cookies.length()==0){navigate.run();return;}
            CookieManager manager=CookieManager.getInstance();
            if(Build.VERSION.SDK_INT>=21){final java.util.concurrent.atomic.AtomicInteger pending=new java.util.concurrent.atomic.AtomicInteger(cookies.length());
                for(int i=0;i<cookies.length();i++){JSONArray row=cookies.getJSONArray(i);manager.setCookie(row.getString(0),row.getString(1),ok->{int left=pending.decrementAndGet();if(left==0)navigate.run();});}
            }else{CookieSyncManager.createInstance(context);for(int i=0;i<cookies.length();i++){JSONArray row=cookies.getJSONArray(i);manager.setCookie(row.getString(0),row.getString(1));}manager.getCookie(url);navigate.run();}
        }catch(Exception error){lease="";bootstrapping=false;view.loadUrl(url);}
    }
    boolean prompt(String source,String message,String value,JsPromptResult result){
        if(message==null||!message.startsWith(PREFIX))return false;
        if(nonce.isEmpty()){result.confirm("");return true;}
        if(message.equals(PREFIX+nonce+":ready")&&bootstrapping){result.confirm("");bootstrapping=false;final int epoch=generation;ui.post(()->{if(epoch==generation)view.loadUrl(page);});return true;}
        if(!message.equals(PREFIX+nonce+":capture")){result.confirm("");return true;}
        result.confirm("");
        if(value==null||value.length()>1024*1024||bootstrapDocument()||!SharedWebProfile.origin(source).isEmpty()&&!SharedWebProfile.origin(source).equals(SharedWebProfile.origin(page)))return true;
        final int epoch=generation;ui.post(()->{
            if(epoch!=generation||bootstrapDocument()||!SharedWebProfile.origin(view.getUrl()).equals(SharedWebProfile.origin(page)))return;
            try{JSONObject state=new JSONObject(value);if(!SharedWebProfile.origin(page).equals(state.optString("origin")))return;
                Bundle args=new Bundle();args.putString("url",view.getUrl());args.putString("lease",lease);args.putString("cookies",CookieManager.getInstance().getCookie(view.getUrl()));SharedWebProfile.pack(args,"state",state.toString());SharedWebProfile.rpc(context,"capture",args);
            }catch(Exception error){AppLog.d("WebProfile","Snapshot unavailable: "+error.getClass().getSimpleName());}
            completeFlush();
        });return true;
    }
    void document(String url){
        if(bootstrap(url)||SharedWebProfile.origin(url).isEmpty())return;
        if(!SharedWebProfile.origin(url).equals(SharedWebProfile.origin(page))){
            page=url;nonce=UUID.randomUUID().toString();Bundle args=new Bundle();args.putString("url",url);lease=SharedWebProfile.rpc(context,"open",args).getString("lease","");
        }
        inject(false);
    }
    void flush(Runnable done){
        if(!active()){done.run();return;}flushed.add(done);inject(true);ui.postDelayed(this::completeFlush,250);
    }
    private void completeFlush(){ArrayList<Runnable> copy=new ArrayList<>(flushed);flushed.clear();for(Runnable done:copy)done.run();}
    void close(){generation++;lease="";nonce="";completeFlush();}
    private void inject(boolean force){
        if(!active())return;
        String command=JSONObject.quote(PREFIX+nonce+":capture"),token=JSONObject.quote(nonce);
        String js="(function(){if(window.top!==window)return;var token="+token+",key='__ntvSharedProfile';if(window[key]&&window[key].token===token){if("+force+")window[key](true);return;}"
            +"var writes=[],pending=false,last='';function send(force){pending=false;try{var data=Object.create(null),s=window.localStorage;for(var i=0;i<s.length;i++){var k=s.key(i);data[k]=s.getItem(k)}var o={origin:location.protocol+'//'+location.hostname.replace(/^\\[|\\]$/g,'')+':'+(location.port||(location.protocol==='https:'?'443':'80')),storage:data,visible:document.cookie,writes:writes};var text=JSON.stringify(o);if(text.length>1048576)return;if(force||text!==last){last=text;prompt("+command+",text)}writes=[]}catch(e){}}"
            +"function changed(){if(!pending){pending=true;setTimeout(function(){send(false)},40)}}"
            +"var fn=function(force){send(force)};fn.token=token;window[key]=fn;"
            +"try{['setItem','removeItem','clear'].forEach(function(k){var f=Storage.prototype[k];Storage.prototype[k]=function(){var r=f.apply(this,arguments);if(this===window.localStorage)changed();return r}})}catch(e){}"
            +"try{var p=document,d;while(p&&!d){d=Object.getOwnPropertyDescriptor(p,'cookie');p=Object.getPrototypeOf(p)}if(d&&d.get&&d.set)Object.defineProperty(document,'cookie',{configurable:true,get:function(){return d.get.call(document)},set:function(v){d.set.call(document,v);if(writes.length<64)writes.push(String(v));changed()}})}catch(e){}"
            +"addEventListener('storage',changed,false);setTimeout(function(){send("+force+")},0)})()";
        if(Build.VERSION.SDK_INT>=19)view.evaluateJavascript(js,null);else view.loadUrl("javascript:"+js);
    }
}
