package xiao.bu.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** The JS-facing Ku9 contract, shared by the browser and native CJS engines. */
final class Ku9JsContract {
    private final android.content.Context context;
    private String cryptoModuleSource;
    private String jsEncryptModuleSource;

    Ku9JsContract(android.content.Context context) { this.context = context; }

    private static final java.util.regex.Pattern CRYPTO_REQUIRE = java.util.regex.Pattern.compile(
            "require\\s*\\(\\s*['\"](?:crypto|crypto-js)['\"]",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern JSENCRYPT_REQUIRE = java.util.regex.Pattern.compile(
            "require\\s*\\(\\s*['\"]jsencrypt['\"]", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final String ES5_COMPAT =
            "if(!String.prototype.startsWith){String.prototype.startsWith=function(s,p){"
                    + "p=p||0;return this.substr(p,s.length)===s;};}"
                    + "if(!String.prototype.endsWith){String.prototype.endsWith=function(s,p){"
                    + "var t=String(this);if(p===undefined||p>t.length)p=t.length;"
                    + "return t.substring(p-s.length,p)===s;};}"
                    + "if(!String.prototype.includes){String.prototype.includes=function(s,p){"
                    + "return this.indexOf(s,p||0)!==-1;};}"
                    + "if(!Array.prototype.includes){Array.prototype.includes=function(v,p){"
                    + "return this.indexOf(v,p||0)!==-1;};}";

    synchronized String build(String script, JSONObject item) {
        // Ku9 scripts and the legacy RSA module intentionally create a few globals,
        // so this compatibility scope must use the browser's normal sloppy mode.
        return "(function(){window.navigator=window.navigator||{appName:'Netscape'};"
                + bootstrap("NtvCjsBridge")
                + ES5_COMPAT
                + "try{"
                + moduleBootstrap(script)
                + "function crypto(){return require('crypto');}"
                + "function word(v,hex){return hex?crypto().enc.Hex.parse(String(v||'')):crypto().enc.Utf8.parse(String(v||''));}"
                + "function ku9Crypto(dec,data,type,key,format,iv){var c=crypto(),p=String(type||'AES-256-CBC').toUpperCase().split('-'),"
                + "mode=c.mode[p[p.length-1]]||c.mode.CBC,cfg={mode:mode,padding:c.pad.Pkcs7},keyWord=word(key,false);"
                + "cfg.iv=iv!==undefined&&iv!==null&&String(iv)!==''?word(iv,false):c.lib.WordArray.create([0,0,0,0]);"
                + "if(dec){var cipher=format===1?c.enc.Hex.parse(String(data)):c.enc.Base64.parse(String(data));"
                + "return c.AES.decrypt({ciphertext:cipher},keyWord,cfg).toString(c.enc.Utf8);}"
                + "var out=c.AES.encrypt(String(data),keyWord,cfg).ciphertext;return (format===1?c.enc.Hex:c.enc.Base64).stringify(out);}"
                + "function ku9Rc4(dec,data,key,input,output){var c=crypto(),keyWord=word(key,false),value;"
                + "if(dec){value=input===1?c.enc.Hex.parse(String(data)):c.enc.Utf8.parse(String(data));"
                + "value=c.RC4.decrypt({ciphertext:value},keyWord).toString(output===1?c.enc.Hex:c.enc.Utf8);}"
                + "else{value=input===1?c.enc.Hex.parse(String(data)):String(data);value=c.RC4.encrypt(value,keyWord).ciphertext;"
                + "value=(output===1?c.enc.Hex:c.enc.Utf8).stringify(value);}return value;}"
                + "function done(v){try{if(v===undefined||v===null)v={};"
                + "NtvCjsBridge.complete(JSON.stringify(v));}catch(e){fail(e);}}"
                + "function fail(e){NtvCjsBridge.fail(String(e)+(e&&e.stack?'\\n'+e.stack:''));}"
                + "var module={exports:{}},exports=module.exports;" + script + "\n"
                + "var entry=typeof main==='function'?main:(typeof module.exports==='function'?module.exports:module.exports.main);"
                + "if(typeof entry!=='function')throw new Error('脚本没有 main(item) 入口');"
                + "var input=" + item.toString() + ",query=uri(input.url).Params;"
                + "for(var key in query){if(Object.prototype.hasOwnProperty.call(query,key)&&input[key]===undefined)input[key]=query[key];}"
                + "var r=entry(input);"
                + "if(r&&typeof r.then==='function'){r.then(done,fail);}else{done(r);}}"
                + "catch(e){fail(e);}})();";
    }

    private String moduleBootstrap(String script) {
        StringBuilder javascript = new StringBuilder(
                "var __ku9Modules={};function require(n){var k=String(n||'').toLowerCase();"
                        + "if(k==='crypto-js')k='crypto';if(Object.prototype.hasOwnProperty.call(__ku9Modules,k))return __ku9Modules[k];"
                        + "throw new Error('酷9脚本引用了不支持的模块: '+n);}");
        if (CRYPTO_REQUIRE.matcher(script).find() || script.contains("opensslEncrypt")
                || script.contains("opensslDecrypt") || script.contains("rc4Encrypt")
                || script.contains("rc4Decrypt")) {
            javascript.append(registerModule("crypto", loadModuleAsset(true)))
                    .append("window.CryptoJS=__ku9Modules.crypto;");
        }
        if (JSENCRYPT_REQUIRE.matcher(script).find()) {
            javascript.append(registerModule("jsencrypt", loadModuleAsset(false)))
                    .append("var __rsa=__ku9Modules.jsencrypt,"
                            + "__rsaCtor=__rsa&&(__rsa.JSEncrypt||__rsa.default||__rsa);"
                            + "if(typeof __rsaCtor==='function'){__rsaCtor.JSEncrypt=__rsaCtor;"
                            + "__rsaCtor.default=__rsaCtor;__ku9Modules.jsencrypt=__rsaCtor;}");
        }
        return javascript.toString();
    }

    private static String registerModule(String name, String source) {
        return "(function(){var module={exports:{}},exports=module.exports;" + source
                + "\n__ku9Modules['" + name + "']=module.exports;})();";
    }

    private String loadModuleAsset(boolean crypto) {
        String source = crypto ? cryptoModuleSource : jsEncryptModuleSource;
        if (source != null) {
            return source;
        }
        String path = crypto ? "ku9/crypto-js.min.js" : "ku9/jsencrypt.min.js";
        try {
            java.io.InputStream input = context.getAssets().open(path);
            try {
                source = Ku9HttpClient.readUtf8(input, 1024 * 1024);
            } finally {
                input.close();
            }
        } catch (java.io.IOException error) {
            android.util.Log.e("Ku9JsContract", "Unable to load Ku9 module " + path, error);
            source = "throw new Error('酷9内置模块加载失败: " + path + "');";
        }
        if (crypto) {
            cryptoModuleSource = source;
        } else {
            jsEncryptModuleSource = source;
        }
        return source;
    }


    static String bootstrap(String bridge) {
        return "var __ku9Host=" + bridge + ";"
                + "function parseJson(v,d){try{return JSON.parse(v);}catch(e){return d;}}"
                + "function headerJson(v){return typeof v==='string'?v:JSON.stringify(v||{});}"
                + "function parseStored(v){if(v===null||v===undefined||v==='')return null;return parseJson(v,v);}"
                + "function uri(v){return parseJson(__ku9Host.parseUri(String(v||'')),{});}"
                + "window.ku9={"
                + "get:function(u,h){return __ku9Host.get(String(u),headerJson(h));},"
                + "post:function(u,a,b){var h=a&&typeof a==='object'?a:b,body=a&&typeof a==='object'?b:a;"
                + "return __ku9Host.post(String(u),String(body||''),headerJson(h));},"
                + "request:function(u,m,h,b,f){var r=parseJson(__ku9Host.request(String(u),String(m||'GET'),headerJson(h),String(b||''),f!==false),{});"
                + "if(r.furl===undefined)r.furl=r.url||'';return r;},"
                + "getHeaders:function(u,h,f,m,b){return JSON.stringify(ku9.request(u,m||'GET',h,b||'',f!==false).headers||{});},"
                + "getQuery:function(u,n){try{var q=String(u).split('?')[1]||'',a=q.split('&');for(var i=0;i<a.length;i++){var p=a[i].split('=');if(decodeURIComponent(p[0]||'')===String(n))return decodeURIComponent((p.slice(1).join('=')||'').replace(/\\+/g,' '));}}catch(e){}return '';},"
                + "getCache:function(k){return parseStored(__ku9Host.getCache(String(k)));},"
                + "setCache:function(k,v,t){__ku9Host.setCache(String(k),JSON.stringify(v),Number(t)||0);},"
                + "Uri:uri,"
                + "md5:function(v){return __ku9Host.md5(String(v));},"
                + "sha1:function(v){return __ku9Host.digest('SHA-1',String(v));},"
                + "sha256:function(v){return __ku9Host.digest('SHA-256',String(v));},"
                + "sha512:function(v){return __ku9Host.digest('SHA-512',String(v));},"
                + "encodeBase64:function(v){return __ku9Host.encodeBase64(String(v));},"
                + "decodeBase64:function(v){return __ku9Host.decodeBase64(String(v));},"
                + "isBase64:function(v){var s=String(v||'').replace(/\\s/g,'');return s.length>0&&s.length%4===0&&/^[A-Za-z0-9+/]*={0,2}$/.test(s);},"
                + "isJsonObject:function(v){var x=parseJson(String(v),null);return x!==null&&!Array.isArray(x)&&typeof x==='object';},"
                + "isJsonArray:function(v){return Array.isArray(parseJson(String(v),null));},"
                + "toTimestamp:function(v,f,z){return Number(__ku9Host.toTimestamp(String(v),String(f||'yyyy-MM-dd HH:mm:ss'),String(z||'')));},"
                + "toDate:function(v,f,z){return __ku9Host.toDate(Number(v),String(f||'yyyy-MM-dd HH:mm:ss'),String(z||''));},"
                + "formatDateTime:function(v,i,o,d,iz,oz){return __ku9Host.formatDateTime(String(v),String(i||'yyyy-MM-dd HH:mm:ss'),String(o||'yyyy-MM-dd HH:mm:ss'),Number(d)||0,String(iz||''),String(oz||''));},"
                + "opensslEncrypt:function(d,t,k,o,iv){return ku9Crypto(false,d,t,k,o,iv);},"
                + "opensslDecrypt:function(d,t,k,i,iv){return ku9Crypto(true,d,t,k,i,iv);},"
                + "rc4Encrypt:function(d,k,i,o,c){return ku9Rc4(false,d,k,i,o);},"
                + "rc4Decrypt:function(d,k,i,o,c){return ku9Rc4(true,d,k,i,o);},"
                + "log:function(v){__ku9Host.log(String(v));}"
                + "};"
                + BROWSER_GLOBALS + "window.cjs=window.ku9;";
    }

    // Ku9 scripts commonly use these browser globals even when all I/O uses ku9.*.
    // Preserve WebView implementations; QuickJS only receives the missing functions.
    static final String BROWSER_GLOBALS =
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
        final String url, playlist, webViewUrl, pageScript;
        Output(JSONObject fields, String url, String playlist) {
            this.fields = fields; this.url = url; this.playlist = playlist;
            this.webViewUrl = first(fields, "webview");
            this.pageScript = fields.optString("jscode", "");
        }
    }

    static Output parse(String json) throws org.json.JSONException {
        Object value = new JSONTokener(json == null ? "" : json).nextValue();
        // Some published Ku9 scripts return JSON.stringify({url: ...}). Unwrap
        // one object layer; ordinary URL and M3U strings retain their meaning.
        if (value instanceof String && ((String) value).trim().startsWith("{")) {
            try {
                Object nested = new JSONTokener((String) value).nextValue();
                if (nested instanceof JSONObject) value = nested;
            } catch (org.json.JSONException ignored) { }
        }
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
        if (url.length() == 0 && playlist.length() == 0 && first(fields, "webview").length() == 0) {
            String error = first(fields, "error", "message", "msg");
            if (error.length() > 0) throw new org.json.JSONException(error);
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

    static String resultHeader(JSONObject fields, String fieldName, String headerName) {
        String explicit = first(fields, fieldName);
        if (explicit.length() > 0) return explicit;
        JSONObject headers = fields.optJSONObject("headers");
        if (headers == null) return "";
        java.util.Iterator<String> names = headers.keys();
        while (names.hasNext()) {
            String name = names.next();
            if (headerName.equalsIgnoreCase(name)) return headers.optString(name, "").trim();
        }
        return "";
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
