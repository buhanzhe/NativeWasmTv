package xiao.bu.tv;

import android.content.SharedPreferences;
import java.util.Iterator;
import org.json.JSONException;
import org.json.JSONObject;

/** A host owns all paths/queries/ports; distinct subdomains remain independent. */
final class WebDomainSettings {
    private static final String PREFIX="web-domain\u001f";
    private static final String[] KEYS={"webViewResolution","webViewPageCover","webViewPageCoverStyle",
            "webViewMinimalMode","webViewUserAgent","webViewBrowserVersion","webViewPageScale",
            "webSourceEngine","webViewLoadImages","webViewAutoPlaySniffed","webViewAutoPlay"};
    static String key(String url) {
        if(url==null)return null;
        if(url.startsWith("webview://"))url=url.substring(10);
        okhttp3.HttpUrl parsed=okhttp3.HttpUrl.parse(url);
        return parsed==null?null:PREFIX+parsed.host();
    }
    private static String storageKey(String domain) throws JSONException {
        if(domain==null||!domain.startsWith(PREFIX))throw new JSONException("请先打开需要设置的网页频道");
        String host=domain.substring(PREFIX.length());
        try {
            if(!host.equals(new okhttp3.HttpUrl.Builder().scheme("https").host(host).build().host()))
                throw new IllegalArgumentException();
        } catch(IllegalArgumentException invalid){throw new JSONException("网页域名无效");}
        return "web_domain_settings_"+host;
    }
    static JSONObject defaults(SharedPreferences p,String engine,String ua) {
        try {
            return new JSONObject().put("webViewResolution",p.getString("web_view_resolution","720p"))
                    .put("webViewPageCover",p.getBoolean("web_view_page_cover",false))
                    .put("webViewPageCoverStyle",p.getString("web_view_page_cover_style","status"))
                    .put("webViewMinimalMode",p.getBoolean("web_view_minimal_mode",false))
                    .put("webViewUserAgent","auto".equals(ua)?"windows":ua).put("webSourceEngine",engine)
                    .put("webViewBrowserVersion",p.getString("web_view_browser_version","native"))
                    .put("webViewPageScale",(double)PreferenceNumbers.readFloat(p,"web_view_page_scale",1f))
                    .put("webViewLoadImages",p.getBoolean("web_view_load_images",true))
                    .put("webViewAutoPlaySniffed",p.getBoolean("web_view_auto_play_sniffed",true))
                    .put("webViewAutoPlay",p.getBoolean("web_view_auto_play",true));
        } catch(JSONException impossible){throw new IllegalStateException(impossible);}
    }
    private static JSONObject stored(SharedPreferences p,String domain) {
        if(domain==null)return new JSONObject();
        try{
            String storage=storageKey(domain);
            JSONObject saved=new JSONObject(p.getString(storage,"{}"));
            if("auto".equals(saved.optString("webViewUserAgent"))){
                saved.put("webViewUserAgent","windows");p.edit().putString(storage,saved.toString()).apply();
            }
            return saved;
        }
        catch(JSONException invalid){return new JSONObject();}
    }
    static JSONObject effective(SharedPreferences p,String domain,JSONObject defaults,boolean liteSupported) {
        JSONObject saved=stored(p,domain);
        try {
            JSONObject result=new JSONObject(defaults.toString());
            for(String name:KEYS)if(saved.has(name))try {
                result.put(name,validated(name,saved.get(name),true));
            } catch(JSONException invalid){/* A corrupt saved field uses the global default. */}
            if(!liteSupported)result.put("webSourceEngine","system");
            return result;
        } catch(JSONException impossible){throw new IllegalStateException(impossible);}
    }
    static synchronized void save(SharedPreferences p,String domain,JSONObject patch,boolean liteSupported) throws JSONException {
        String storage=storageKey(domain);
        JSONObject next=stored(p,domain);
        boolean changed=false;
        for(Iterator<String> it=patch.keys();it.hasNext();) {
            String name=it.next();
            if("webPreferenceScope".equals(name)||"webPreferenceDomain".equals(name))continue;
            next.put(name,validated(name,patch.get(name),liteSupported));changed=true;
        }
        if(!changed)throw new JSONException("网页配置为空");
        // Validate the entire patch before the single preference write.
        p.edit().putString(storage,next.toString()).apply();
    }
    private static Object validated(String name,Object value,boolean liteSupported) throws JSONException {
        String allowed;
        switch(name) {
            case "webViewResolution":allowed="|720p|1080p|2k|4k|";break;
            case "webViewPageCoverStyle":allowed="|status|black|";break;
            case "webViewUserAgent":allowed="|windows|macos|ipad|native|";break;
            case "webViewBrowserVersion":allowed="|native|118|128|138|155|";break;
            case "webSourceEngine":
                if("lite".equals(value)&&!liteSupported)throw new JSONException("此安装包未包含轻量内核");
                allowed="|system|lite|";break;
            case "webViewPageScale":
                if(value instanceof Number) {
                    double scale=((Number)value).doubleValue();
                    if(!Double.isNaN(scale)&&scale>=0.5&&scale<=3)return scale;
                }
                throw new JSONException("屏幕缩放系数应为 50% 到 300%");
            case "webViewPageCover":case "webViewMinimalMode":case "webViewLoadImages":
            case "webViewAutoPlaySniffed":case "webViewAutoPlay":
                if(value instanceof Boolean)return value;
                throw new JSONException("网页配置开关无效");
            default:throw new JSONException("不支持的域名网页配置："+name);
        }
        if(value instanceof String&&!((String)value).isEmpty()&&((String)value).indexOf('|')<0
                &&allowed.contains("|"+value+"|"))return value;
        throw new JSONException("网页配置值无效："+name);
    }
}
