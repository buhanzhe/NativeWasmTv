package xiao.bu.tv;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.Executors;
import okio.ByteString;

/** Only choice metadata is bundled; every renderer is downloaded and cached separately. */
final class RadioUiCatalog {
    static final String URL = "https://github.com/buhanzhe/ntv-plugin/releases/latest/download/radio-ui.json";
    private static final long REFRESH_MS = 6 * 60 * 60 * 1000L;
    private static final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
    private static volatile List<Entry> entries;
    private static volatile boolean refreshing;
    private static volatile String failure = "";

    static final class Entry {
        final String id, name, style, url, sha;
        Entry(JSONObject o) throws Exception {
            id=o.getString("id"); name=o.getString("name").trim(); style=o.optString("style",id);
            url=new URL(new URL(URL),o.getString("url")).toString(); sha=o.getString("sha256");
            URL address=new URL(url);
            if(!id.matches("[a-z0-9][a-z0-9_-]{0,47}")||!style.matches("[a-z0-9][a-z0-9_-]{0,47}")
                    ||name.length()==0||name.length()>48||!sha.matches("[a-f0-9]{64}")||url.length()>4096
                    ||!"https".equalsIgnoreCase(address.getProtocol())||address.getHost().length()==0||address.getUserInfo()!=null)throw new IOException("电台界面配置无效");
        }
        JSONObject json() throws JSONException {
            return new JSONObject().put("id",id).put("name",name).put("style",style)
                    .put("url",url).put("sha256",sha);
        }
    }
    static List<Entry> parse(String json) throws Exception {
        JSONObject root=new JSONObject(json); JSONArray list=root.getJSONArray("styles");
        if(root.getInt("schema")!=1||list.length()==0||list.length()>40)throw new IOException("电台界面列表版本不支持");
        List<Entry> result=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(int i=0;i<list.length();i++){Entry e=new Entry(list.getJSONObject(i));if(!ids.add(e.id))throw new IOException("电台界面编号重复");result.add(e);}
        return result;
    }
    static synchronized List<Entry> choices(Context c) throws Exception {
        if(entries!=null)return entries;
        try(InputStream in=c.getAssets().open("radio-ui/catalog.json")) {
            List<Entry> local=parse(new String(read(in,65536),"UTF-8"));
            String saved=c.getSharedPreferences("radio_ui",0).getString("release_catalog","");
            try { if(!saved.isEmpty())local=merge(local,parse(saved)); }catch(Exception ignored) { }
            entries=Collections.unmodifiableList(local);return entries;
        }
    }
    private static List<Entry> merge(List<Entry> local,List<Entry> remote) {
        Map<String,Entry> result=new LinkedHashMap<>();
        for(Entry e:local)result.put(e.id,e);for(Entry e:remote)result.put(e.id,e);
        return new ArrayList<>(result.values());
    }
    static Entry find(Context c,String id) throws Exception {for(Entry e:choices(c))if(e.id.equals(id))return e;return null;}
    static boolean refreshing(){return refreshing;}
    static String failure(){return failure;}
    static void refresh(Context context, boolean force) throws Exception {
        final Context c=context.getApplicationContext();choices(c);
        synchronized(RadioUiCatalog.class){
            long checked=c.getSharedPreferences("radio_ui",0).getLong("release_catalog_checked_at",0),now=System.currentTimeMillis();
            if(refreshing||!force&&checked<=now&&now-checked<REFRESH_MS)return;
            refreshing=true;
        }
        worker.execute(()->{
            try {
                String json=new String(download(URL,65536,8000),"UTF-8");List<Entry> remote=parse(json),local;
                try(InputStream in=c.getAssets().open("radio-ui/catalog.json")){local=parse(new String(read(in,65536),"UTF-8"));}
                if(!c.getSharedPreferences("radio_ui",0).edit().putString("release_catalog",json).commit())throw new IOException("无法保存界面列表");
                synchronized(RadioUiCatalog.class){entries=Collections.unmodifiableList(merge(local,remote));}
                failure="";
            }catch(Exception e){xiao.bu.tv.AppLog.w("RadioUi","Catalog refresh failed",e);failure="在线列表暂不可用，继续使用本地选项";}
            finally{c.getSharedPreferences("radio_ui",0).edit().putLong("release_catalog_checked_at",System.currentTimeMillis()).apply();refreshing=false;}
        });
    }
    static byte[] download(String url,int limit,int timeoutMs) throws IOException {
        return download(GithubProxy.candidates(url),limit,timeoutMs,timeoutMs>8000?15000:4000,false,input->read(input,limit));
    }
    static byte[] downloadDirect(String url,int limit,int timeoutMs) throws IOException {
        return download(new String[]{url, url}, limit, timeoutMs, Math.max(1, timeoutMs / 2), true,
                input -> read(input, limit));
    }
    static void downloadVerifiedRelease(String url, File target, int bytes, String sha, int timeoutMs) throws IOException {
        Set<String> routes = new LinkedHashSet<>();
        Collections.addAll(routes, GithubProxy.candidates(url));
        routes.add(url);
        int attemptMs = routes.size() == 1 ? timeoutMs : bytes >= 1024 * 1024 ? 30000 : 12000;
        download(routes.toArray(new String[0]), bytes, timeoutMs, attemptMs, true, input -> {
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException error) { throw new IOException("无法校验插件 SHA-256", error); }
            try (FileOutputStream output = new FileOutputStream(target)) {
                if (copy(new DigestInputStream(input, digest), output, bytes) != bytes)
                    throw new IOException("插件文件大小与发布清单不一致");
                if (!sha.equals(ByteString.of(digest.digest()).hex())) throw new IOException("插件 SHA-256 校验失败");
                output.getFD().sync();
            }
            return null;
        });
    }
    private interface Transfer<T> { T receive(InputStream input) throws IOException; }
    private static <T> T download(String[] routes,int limit,int timeoutMs,int attemptMs,boolean http1,
                                  Transfer<T> transfer) throws IOException {
        long deadline=android.os.SystemClock.elapsedRealtime()+timeoutMs;IOException last=null;
        for(String route:routes){
            int remaining=(int)(deadline-android.os.SystemClock.elapsedRealtime());if(remaining<=0)break;
            HttpURLConnection connection=null;
            URL address=new URL(route);
            long started=android.os.SystemClock.elapsedRealtime();
            try {
                connection=NetworkClient.openBounded(address,Math.min(remaining,attemptMs),http1);
                connection.setConnectTimeout(Math.min(4000,remaining));connection.setReadTimeout(Math.min(8000,remaining));
                int code=connection.getResponseCode();if(code<200||code>=300)throw new IOException("下载 HTTP "+code);
                if(connection.getContentLength()>limit)throw new IOException("下载文件过大");
                try(InputStream in=connection.getInputStream()){
                    T data=transfer.receive(in);
                    GithubProxy.succeeded(route);
                    if(http1) AppLog.i("PluginRelease", "Received host="+address.getHost()+" length="
                            +connection.getContentLength()+" ms="+(android.os.SystemClock.elapsedRealtime()-started));
                    return data;
                }
            }catch(IOException e){
                last=e;GithubProxy.failed(route);
                if(http1) AppLog.w("PluginRelease", "Failed host="+address.getHost()+" ms="
                        +(android.os.SystemClock.elapsedRealtime()-started)+" "+e.getClass().getSimpleName());
            }finally{if(connection!=null)connection.disconnect();}
        }
        throw last==null?new IOException("下载超时"):last;
    }
    static byte[] read(InputStream in,int limit) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        copy(in,out,limit);
        return out.toByteArray();
    }
    private static int copy(InputStream in,OutputStream out,int limit) throws IOException {
        byte[] block=new byte[8192];int n,total=0;
        while((n=in.read(block))!=-1){
            if(n>limit-total)throw new IOException("下载文件过大");
            out.write(block,0,n);total+=n;
        }
        return total;
    }
}
