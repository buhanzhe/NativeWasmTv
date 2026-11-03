package xiao.bu.tv;
import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.lang.reflect.Method;

/** Checks real result/header forwarding without depending on CCTV availability. */
public final class Ku9SignedHeadersInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) {super.onCreate(args);start();}
    private static HttpURLConnection connection(String host)throws Exception {
        return new HttpURLConnection(new URL("https://"+host+"/seg.ts")) {
            final java.util.Map<String,String> sent = new java.util.HashMap<>();
            @Override public void setRequestProperty(String name,String value){sent.put(name,value);}
            @Override public String getRequestProperty(String name){return sent.get(name);}
            public void connect() {} public void disconnect() {} public boolean usingProxy(){return false;}
        };
    }
    @Override public void onStart() {
        Bundle result=new Bundle();HlsProxyServer proxy=null;int code=-1;
        try {
            checkMediaHeaders();
            JSONObject fields=new JSONObject("{\"headers\":{\"uid\":\"fixture\",\"APPID\":\"app\",\"APPSIGN\":\"sig1\",\"APPRANDOMSTR\":\"nonce1\"}}");
            Ku9ScriptResolver.Result initial=new Ku9ScriptResolver.Result("http://127.0.0.1/live.m3u8",false,fields);
            check(initial.signedHeaders.size()==4&&!initial.playlistRefresh,"result fields");
            proxy=new HlsProxyServer();
            Method apply=HlsProxyServer.class.getDeclaredMethod("applyRequestHeaders",HttpURLConnection.class,String.class);apply.setAccessible(true);
            proxy.setWebRequestHeaders(initial.referer,initial.userAgent,initial.cookies,false,false,initial.signedHeaders);
            HttpURLConnection cdn=connection("liveali.cctv.cn");apply.invoke(proxy,cdn,cdn.getURL().toString());
            check("sig1".equals(cdn.getRequestProperty("APPSIGN"))&&"nonce1".equals(cdn.getRequestProperty("APPRANDOMSTR")),"initial signed media request");
            HttpURLConnection other=connection("example.test");apply.invoke(proxy,other,other.getURL().toString());
            check(other.getRequestProperty("APPSIGN")==null,"unrelated origin");
            fields.getJSONObject("headers").put("APPSIGN","sig2");
            Ku9ScriptResolver.Result refresh=new Ku9ScriptResolver.Result(initial.url,false,fields,true);
            check(refresh.playlistRefresh,"refresh marker");
            proxy.setWebRequestHeaders(refresh.referer,refresh.userAgent,refresh.cookies,false,false,refresh.signedHeaders);
            cdn=connection("liveali.cctv.cn");apply.invoke(proxy,cdn,cdn.getURL().toString());
            check("sig2".equals(cdn.getRequestProperty("APPSIGN")),"renewed signature");
            proxy.setWebRequestHeaders(null,null,null);
            cdn=connection("liveali.cctv.cn");apply.invoke(proxy,cdn,cdn.getURL().toString());
            check(cdn.getRequestProperty("APPSIGN")==null,"channel header reset");
            result.putString("stream","PASS signed header parsing, media forwarding, refresh, origin scope and channel reset\n");
        } catch(Throwable error) {code=0;result.putString("stream","FAIL "+android.util.Log.getStackTraceString(error));}
        finally {if(proxy!=null)proxy.close();}
        finish(code,result);
    }
    private static void check(boolean condition,String reason){if(!condition)throw new AssertionError(reason);}
    static void checkMediaHeaders() throws Exception {
        JSONObject fields=new JSONObject().put("headers",new JSONObject()
                .put("Content-Type","application/json").put("Origin","https://share.jxgdw.com")
                .put("X-Test","value").put("Range","bytes=0-1").put("Host","wrong.test")
                .put("X-Bad","value\r\nInjected: yes").put("Bad Name","value")
                .put("APPSIGN","cctv-only"));
        Ku9ScriptResolver.Result parsed=new Ku9ScriptResolver.Result("http://127.0.0.1/live.m3u8",false,fields);
        check(parsed.mediaHeaders.size()==3,"media header filtering");
        HlsProxyServer proxy=new HlsProxyServer();
        try {
            Method apply=HlsProxyServer.class.getDeclaredMethod("applyRequestHeaders",HttpURLConnection.class,String.class);
            apply.setAccessible(true);
            proxy.setWebRequestHeaders(parsed.referer,parsed.userAgent,parsed.cookies,false,false,
                    parsed.signedHeaders,parsed.mediaHeaders);
            HttpURLConnection segment=connection("play-live-jsp.jxtvcn.com.cn");
            apply.invoke(proxy,segment,segment.getURL().toString());
            check("application/json".equals(segment.getRequestProperty("Content-Type")),"Jiangxi segment Content-Type");
            check("https://share.jxgdw.com".equals(segment.getRequestProperty("Origin")),"explicit Origin");
            check(segment.getRequestProperty("APPSIGN")==null,"CCTV signature remains scoped");
            check(segment.getRequestProperty("Range")==null,"player owns range");
            proxy.setWebRequestHeaders(null,null,null);
            segment=connection("play-live-jsp.jxtvcn.com.cn");
            apply.invoke(proxy,segment,segment.getURL().toString());
            check(segment.getRequestProperty("Content-Type")==null,"media headers reset between channels");
        } finally {proxy.close();}
    }
}
