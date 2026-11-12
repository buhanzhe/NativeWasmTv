package xiao.bu.tv;

import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import okhttp3.*;
import okhttp3.Cookie;
import org.json.*;

/** Main-process profile owner. WebView processes exchange bounded snapshots, not files. */
final class SharedWebProfile {
    private static final Map<String,String> leases=new HashMap<>();
    static String origin(String value){HttpUrl u=HttpUrl.parse(value==null?"":value);return u==null?"":u.scheme()+"://"+u.host()+":"+u.port();}
    static Bundle rpc(Context context,String action,Bundle args){
        Bundle reply=context.getContentResolver().call(Uri.parse("content://"+context.getPackageName()+".webresources"),"profile_"+action,null,args);
        return reply==null?new Bundle():reply;
    }
    static String userAgent(Context c,String page,String mode,String version,String proposed,boolean pin){
        if(proposed==null||proposed.isEmpty()||origin(page).isEmpty())return proposed;
        Bundle args=new Bundle();args.putString("url",page);args.putString("config",mode+":"+version);args.putString("ua",proposed);args.putBoolean("pin",pin);
        try{return rpc(c,"ua",args).getString("ua",proposed);}catch(RuntimeException error){return proposed;}
    }
    static void beginLite(Context c,String page){Bundle args=new Bundle();args.putString("url",page);rpc(c,"lite",args);}
    static String signature(Context c,String page){Bundle args=new Bundle();args.putString("url",page);try{return rpc(c,"signature",args).getString("signature","");}catch(RuntimeException error){return "";}}
    static void pack(Bundle target,String key,String value)throws IOException{
        byte[] bytes=value.getBytes("UTF-8");boolean compressed=bytes.length>8192;
        if(compressed){ByteArrayOutputStream out=new ByteArrayOutputStream();try(GZIPOutputStream zip=new GZIPOutputStream(out)){zip.write(bytes);}bytes=out.toByteArray();}
        if(bytes.length>700*1024)throw new IOException("Profile snapshot limit");target.putByteArray(key,bytes);target.putBoolean(key+"_zip",compressed);
    }
    static String unpack(Bundle source,String key)throws IOException{
        byte[] bytes=source.getByteArray(key);if(bytes==null)return "{}";if(bytes.length>700*1024)throw new IOException("Profile snapshot limit");
        if(!source.getBoolean(key+"_zip"))return new String(bytes,"UTF-8");
        try(InputStream input=new GZIPInputStream(new ByteArrayInputStream(bytes));ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] block=new byte[8192];int n,total=0;while((n=input.read(block))!=-1){if((total+=n)>2*1024*1024)throw new IOException("Profile snapshot limit");output.write(block,0,n);}return new String(output.toByteArray(),"UTF-8");
        }
    }
    private static String cookieUrl(HttpUrl page,Cookie c){return new HttpUrl.Builder().scheme(c.secure()?"https":page.scheme()).host(c.hostOnly()?c.domain():page.host()).encodedPath(c.path()).build().toString();}
    private static String identity(Cookie c){return c.name()+"\n"+c.domain()+"\n"+c.path();}
    private static String deletion(Cookie c){StringBuilder value=new StringBuilder(c.name()+"=; Max-Age=0; Path="+c.path());if(!c.hostOnly())value.append("; Domain=").append(c.domain());if(c.secure())value.append("; Secure");if(c.httpOnly())value.append("; HttpOnly");return value.toString();}
    /** Called only by the unexported provider in the owning application process. */
    static synchronized Bundle handle(Context c,String action,Bundle args){
        Bundle reply=new Bundle();HttpUrl page=HttpUrl.parse(args==null?"":args.getString("url",""));if(page==null)return reply;
        String origin=origin(page.toString());SharedPreferences metadata=c.getSharedPreferences("shared-web-profile",0);
        LiteCookies.PROFILE.initialize(c);
        try{
            if(action.equals("ua")){
                String key="ua:"+page.host(),config=args.getString("config",""),proposed=args.getString("ua","");JSONObject saved=new JSONObject(metadata.getString(key,"{}"));
                String chosen=config.equals(saved.optString("config"))?saved.optString("ua",proposed):proposed;
                if(args.getBoolean("pin")&&!chosen.isEmpty()&&!saved.toString().equals(new JSONObject().put("config",config).put("ua",chosen).toString()))metadata.edit().putString(key,new JSONObject().put("config",config).put("ua",chosen).toString()).apply();
                reply.putString("ua",chosen);return reply;
            }
            if(action.equals("lite")){leases.clear();metadata.edit().putBoolean("known:"+origin,true).apply();reply.putBoolean("ok",true);return reply;}
            if(action.equals("signature")){
                JSONArray cookies=new JSONArray();for(Cookie value:LiteCookies.PROFILE.forHost(page))cookies.put(value.toString());
                for(Cookie gone:LiteCookies.PROFILE.deletions(page))cookies.put(gone.toString());
                String values=c.getSharedPreferences("lite-storage",0).getString(origin,"{}");
                java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");digest.update((cookies.toString()+"\n"+values+"\n"+metadata.getString("ua:"+page.host(),"")).getBytes("UTF-8"));reply.putString("signature",android.util.Base64.encodeToString(digest.digest(),android.util.Base64.NO_WRAP));return reply;
            }
            if(action.equals("open")){
                String lease=UUID.randomUUID().toString();if(leases.size()>=64&&!leases.containsKey(origin))leases.remove(leases.keySet().iterator().next());leases.put(origin,lease);reply.putString("lease",lease);
                JSONArray rows=new JSONArray(),current=new JSONArray();Set<String> present=new HashSet<>();
                for(Cookie cookie:LiteCookies.PROFILE.forHost(page)){String url=cookieUrl(page,cookie);rows.put(new JSONArray().put(url).put(cookie.toString()));current.put(new JSONArray().put(url).put(deletion(cookie)));present.add(identity(cookie));}
                for(Cookie gone:LiteCookies.PROFILE.deletions(page))rows.put(new JSONArray().put(cookieUrl(page,gone)).put(deletion(gone)));
                JSONArray old=new JSONArray(metadata.getString("sent:"+page.host(),"[]"));
                for(int i=0;i<old.length();i++){JSONArray row=old.optJSONArray(i);if(row==null)continue;HttpUrl previous=HttpUrl.parse(row.optString(0));Cookie cookie=previous==null?null:Cookie.parse(previous,row.optString(1));if(cookie!=null&&!present.contains(identity(cookie)))rows.put(new JSONArray().put(previous.toString()).put(deletion(cookie)));}
                metadata.edit().putString("sent:"+page.host(),current.toString()).apply();
                SharedPreferences storage=c.getSharedPreferences("lite-storage",0);JSONObject state=new JSONObject().put("cookies",rows).put("storageKnown",storage.contains(origin)||metadata.getBoolean("known:"+origin,false)).put("storage",new JSONObject(storage.getString(origin,"{}")));
                pack(reply,"state",state.toString());reply.putBoolean("ok",true);return reply;
            }
            if(!args.getString("lease","").equals(leases.get(origin)))return reply;
            if(action.equals("capture")){
                JSONObject state=new JSONObject(unpack(args,"state"));JSONObject storage=state.optJSONObject("storage");
                if(storage!=null){String result=LiteWebStorage.replace(c,origin,storage);if(!"null".equals(result))return reply;metadata.edit().putBoolean("known:"+origin,true).apply();}
                JSONArray writes=state.optJSONArray("writes");if(writes!=null)for(int i=0;i<Math.min(64,writes.length());i++){Cookie cookie=Cookie.parse(page,writes.optString(i));if(cookie!=null&&!cookie.httpOnly())LiteCookies.PROFILE.saveFromResponse(page,Collections.singletonList(cookie));}
                LiteCookies.PROFILE.observe(page,args.getString("cookies",""),state.optString("visible",""));
                reply.putBoolean("ok",true);return reply;
            }
            if(action.equals("response")){
                HttpUrl resource=HttpUrl.parse(args.getString("resource",""));if(resource==null)return reply;
                ArrayList<String> values=args.getStringArrayList("setCookies");if(values!=null)for(String value:values){Cookie cookie=Cookie.parse(resource,value);if(cookie!=null)LiteCookies.PROFILE.saveFromResponse(resource,Collections.singletonList(cookie));}reply.putBoolean("ok",true);
            }
        }catch(Exception error){AppLog.d("WebProfile","Profile transfer unavailable: "+error.getClass().getSimpleName());}
        return reply;
    }
}
