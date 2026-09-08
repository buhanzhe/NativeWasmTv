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
                + "log:function(v){" + bridge + ".log(String(v));}};";
    }

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
