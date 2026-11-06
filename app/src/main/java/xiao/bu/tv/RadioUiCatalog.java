package xiao.bu.tv;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.Executors;

/** Only choice metadata is bundled; every renderer is downloaded and cached separately. */
final class RadioUiCatalog {
    static final String URL = "https://raw.githubusercontent.com/buhanzhe/NativeWasmTv/master/plugins/radio-ui/catalog.json";
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
            String saved=c.getSharedPreferences("radio_ui",0).getString("catalog","");
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
            long checked=c.getSharedPreferences("radio_ui",0).getLong("catalog_checked_at",0),now=System.currentTimeMillis();
            if(refreshing||!force&&checked<=now&&now-checked<REFRESH_MS)return;
            refreshing=true;
        }
        worker.execute(()->{
            try {
                String json=new String(download(URL,65536,8000),"UTF-8");List<Entry> remote=parse(json),local;
                try(InputStream in=c.getAssets().open("radio-ui/catalog.json")){local=parse(new String(read(in,65536),"UTF-8"));}
                if(!c.getSharedPreferences("radio_ui",0).edit().putString("catalog",json).commit())throw new IOException("无法保存界面列表");
                synchronized(RadioUiCatalog.class){entries=Collections.unmodifiableList(merge(local,remote));}
                failure="";
            }catch(Exception e){xiao.bu.tv.AppLog.w("RadioUi","Catalog refresh failed",e);failure="在线列表暂不可用，继续使用本地选项";}
            finally{c.getSharedPreferences("radio_ui",0).edit().putLong("catalog_checked_at",System.currentTimeMillis()).apply();refreshing=false;}
        });
    }
    static byte[] download(String url,int limit,int timeoutMs) throws IOException {
        long deadline=android.os.SystemClock.elapsedRealtime()+timeoutMs;IOException last=null;
        for(String route:GithubProxy.candidates(url)){
            int remaining=(int)(deadline-android.os.SystemClock.elapsedRealtime());if(remaining<=0)break;
            HttpURLConnection connection=NetworkClient.openBounded(new URL(route),Math.min(remaining,timeoutMs>8000?15000:4000));
            try {
                connection.setConnectTimeout(Math.min(4000,remaining));connection.setReadTimeout(Math.min(8000,remaining));
                int code=connection.getResponseCode();if(code<200||code>=300)throw new IOException("下载 HTTP "+code);
                if(connection.getContentLength()>limit)throw new IOException("界面文件过大");
                try(InputStream in=connection.getInputStream()){byte[] data=read(in,limit);GithubProxy.succeeded(route);return data;}
            }catch(IOException e){last=e;GithubProxy.failed(route);}finally{connection.disconnect();}
        }
        throw last==null?new IOException("界面下载超时"):last;
    }
    static byte[] read(InputStream in,int limit) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[8192];int n;
        while((n=in.read(block))!=-1){if(out.size()+n>limit)throw new IOException("界面文件过大");out.write(block,0,n);}return out.toByteArray();
    }
}
