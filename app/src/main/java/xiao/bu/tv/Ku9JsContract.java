package xiao.bu.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** The JS-facing Ku9 contract, shared by the browser and native CJS engines. */
final class Ku9JsContract {
    private Ku9JsContract() { }

    static String bootstrap(String bridge) {
        return "function parseJson(v,d){try{return JSON.parse(v);}catch(e){return d;}}"
                + "function headerJson(v){return typeof v==='string'?v:JSON.stringify(v||{});}"
                + "window.ku9={"
                + "get:function(u,h){return " + bridge + ".get(String(u),headerJson(h));},"
                + "post:function(u,b,h){return " + bridge + ".post(String(u),String(b||''),headerJson(h));},"
                + "request:function(u,m,h,b,f){return parseJson(" + bridge + ".request(String(u),String(m||'GET'),headerJson(h),String(b||''),f!==false),{});},"
                + "getQuery:function(u,n){try{var q=String(u).split('?')[1]||'',a=q.split('&');for(var i=0;i<a.length;i++){var p=a[i].split('=');if(decodeURIComponent(p[0]||'')===String(n))return decodeURIComponent((p.slice(1).join('=')||'').replace(/\\+/g,' '));}}catch(e){}return '';},"
                + "getCache:function(k){return " + bridge + ".getCache(String(k));},"
                + "setCache:function(k,v,t){" + bridge + ".setCache(String(k),String(v),Number(t)||0);},"
                + "md5:function(v){return " + bridge + ".md5(String(v));},"
                + "log:function(v){" + bridge + ".log(String(v));}};"
                + BROWSER_GLOBALS;
    }

    // Ku9 scripts commonly use these browser globals even when all I/O uses ku9.*.
    // Preserve WebView implementations; QuickJS only receives the missing functions.
    private static final String BROWSER_GLOBALS =
            "(function(){var alphabet='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';"
            + "if(typeof window.atob!=='function')window.atob=function(value){"
            + "var s=String(value).replace(/[\\t\\n\\f\\r ]/g,'');"
            + "if(s.length%4===0)s=s.replace(/==?$/,'');"
            + "if(s.length%4===1||/[^A-Za-z0-9+/]/.test(s))throw new Error('Invalid base64');"
            + "var out='',bits=0,buffer=0;for(var i=0;i<s.length;i++){"
            + "buffer=(buffer<<6)|alphabet.indexOf(s.charAt(i));bits+=6;"
            + "if(bits>=8){bits-=8;out+=String.fromCharCode((buffer>>bits)&255);}}return out;};"
            + "if(typeof window.btoa!=='function')window.btoa=function(value){"
            + "var s=String(value),out='',bits=0,buffer=0;for(var i=0;i<s.length;i++){"
            + "var c=s.charCodeAt(i);if(c>255)throw new Error('Invalid binary string');"
            + "buffer=(buffer<<8)|c;bits+=8;while(bits>=6){bits-=6;out+=alphabet.charAt((buffer>>bits)&63);}}"
            + "if(bits)out+=alphabet.charAt((buffer<<(6-bits))&63);while(out.length%4)out+='=';return out;};"
            + "if(!window.console)window.console={};"
            + "var names=['log','info','warn','error','debug'];"
            + "for(var n=0;n<names.length;n++)if(typeof window.console[names[n]]!=='function')"
            + "window.console[names[n]]=(function(level){return function(){var a=[];"
            + "for(var j=0;j<arguments.length;j++)a.push(String(arguments[j]));"
            + "ku9.log('['+level+'] '+a.join(' '));};})(names[n]);})();";

    static final class Output {
        final JSONObject fields;
        final String url, playlist;
        Output(JSONObject fields, String url, String playlist) {
            this.fields = fields; this.url = url; this.playlist = playlist;
        }
    }

    static Output parse(String json) throws org.json.JSONException {
        Object value = new JSONTokener(json == null ? "" : json).nextValue();
        JSONObject fields = value instanceof JSONObject ? (JSONObject) value : new JSONObject();
        String url = "", playlist = "";
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (text.startsWith("#EXTM3U")) playlist = text;
            else url = text;
        } else if (value instanceof JSONObject) {
            url = first(fields, "url", "playUrl", "playurl");
            playlist = first(fields, "m3u8", "content");
            JSONArray urls = fields.optJSONArray("urls");
            if (url.length() == 0 && urls != null && urls.length() > 0) url = urls.optString(0, "").trim();
        }
        return new Output(fields, url, playlist.startsWith("#EXTM3U") ? playlist + "\n" : "");
    }

    static boolean isDirectDataSource(String value) {
        String lower = value == null ? "" : value.toLowerCase(java.util.Locale.US);
        if (lower.startsWith("file://") || lower.startsWith("rtsp://") || lower.startsWith("rtmp://")) return true;
        int query = lower.indexOf('?'), fragment = lower.indexOf('#');
        int end = lower.length();
        if (query >= 0) end = Math.min(end, query);
        if (fragment >= 0) end = Math.min(end, fragment);
        String path = lower.substring(0, end);
        return path.endsWith(".flv") || path.endsWith(".mp4") || path.endsWith(".ts");
    }

    private static String first(JSONObject value, String... keys) {
        for (String key : keys) {
            if (value.isNull(key)) continue;
            String text = value.optString(key, "").trim();
            if (text.length() > 0) return text;
        }
        return "";
    }
}
