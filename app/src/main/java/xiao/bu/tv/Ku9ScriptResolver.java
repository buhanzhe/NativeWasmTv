package xiao.bu.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs Ku9 scripts in QuickJS on legacy Android, or an isolated WebView on API 21+. */
final class Ku9ScriptResolver {
    interface Callback {
        void onResolved(int requestId, Result result);

        void onFailed(int requestId, String reason);
    }

    static final class Result {
        final String url;
        final boolean directDataSource;

        Result(String url, boolean directDataSource) {
            this.url = url;
            this.directDataSource = directDataSource;
        }
    }

    private static final String TAG = "Ku9ScriptResolver";
    private final boolean nativeExecution = Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final long TIMEOUT_MS = 30000L;
    private static final long MIN_PLAYLIST_REFRESH_MS = 2000L;
    private static final long MAX_PLAYLIST_REFRESH_MS = 5000L;
    private static final String EXECUTOR_URL = "https://ntv.local/ku9/";
    private static final Pattern TARGET_DURATION =
            Pattern.compile("(?m)^#EXT-X-TARGETDURATION:(\\d+)");
    private static final Pattern CRYPTO_REQUIRE = Pattern.compile(
            "require\\s*\\(\\s*['\"](?:crypto|crypto-js)['\"]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern JSENCRYPT_REQUIRE = Pattern.compile(
            "require\\s*\\(\\s*['\"]jsencrypt['\"]", Pattern.CASE_INSENSITIVE);
    private static final String EXECUTOR_PAGE =
            "<!doctype html><html><head><meta charset=\"utf-8\"></head>"
                    + "<body></body></html>";
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

    private final Activity activity;
    private final FrameLayout root;
    private final SharedPreferences cache;
    private final Ku9ScriptLoader scriptLoader;
    private WebView webView;
    private volatile Pending pending;
    private Ku9PlaylistServer playlistServer;
    private volatile int generation;
    private String cryptoModuleSource;
    private String jsEncryptModuleSource;
    private final Runnable timeout = new Runnable() {
        @Override
        public void run() {
            Pending request = pending;
            if (request != null) {
                fail(request, "酷9脚本解析超时");
            }
        }
    };
    private final Runnable refreshPlaylist = new Runnable() {
        @Override
        public void run() {
            Pending request = pending;
            if (request != null && request.initialCompleted
                    && request.generation == generation) {
                execute(request);
            }
        }
    };

    Ku9ScriptResolver(Activity activity, FrameLayout root) {
        this.activity = activity;
        this.root = root;
        cache = activity.getSharedPreferences("ku9_script_cache", Activity.MODE_PRIVATE);
        scriptLoader = new Ku9ScriptLoader(activity);
    }

    static boolean isKu9Source(String value) {
        return Ku9ScriptLoader.isSource(value);
    }

    void resolve(final int requestId, final String channelName, final String sourceUrl,
            final Callback callback) {
        cancel();
        final int requestGeneration = generation;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String script = scriptLoader.load(sourceUrl, !nativeExecution);
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (requestGeneration != generation || activity.isFinishing()) {
                                return;
                            }
                            startExecution(new Pending(requestId, requestGeneration,
                                    channelName, sourceUrl, script, callback));
                        }
                    });
                } catch (final IOException error) {
                    Log.w(TAG, "Unable to load Ku9 script " + sourceUrl, error);
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (requestGeneration == generation) {
                                callback.onFailed(requestId, error.getMessage());
                            }
                        }
                    });
                }
            }
        }, "ku9-script-load").start();
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void ensureWebView(Pending request) throws IOException {
        if (webView != null) {
            return;
        }
        try {
            webView = new WebView(activity);
        } catch (RuntimeException error) {
            throw new IOException("系统 WebView 不可用", error);
        }
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setDatabaseEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setAlpha(0f);
        webView.setTranslationX(-10000f);
        webView.setTranslationY(-10000f);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1, 1);
        params.gravity = Gravity.LEFT | Gravity.TOP;
        root.addView(webView, params);
        webView.addJavascriptInterface(new Bridge(request), "NtvKu9Bridge");
        WebViewRecovery.attach(webView, new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                Pending request = pending;
                if (view == webView && request != null && EXECUTOR_URL.equals(url)) {
                    execute(request);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !EXECUTOR_URL.equals(url);
            }
        }, this::onRendererGone);
    }

    private void onRendererGone(WebView failed, boolean crashed) {
        if (failed != webView) return;
        final Pending request = pending;
        failed.removeCallbacks(timeout);
        failed.removeCallbacks(refreshPlaylist);
        webView = null;
        pending = null;
        final int expectedGeneration = ++generation;
        closePlaylistServer();
        if (request != null) root.post(new Runnable() {
            @Override public void run() {
                if (generation == expectedGeneration && !activity.isFinishing()) {
                    request.callback.onFailed(request.requestId, "酷9解析进程已退出，请重新选择频道");
                }
            }
        });
    }

    private void startExecution(Pending request) {
        if (nativeExecution) {
            pending = request;
            execute(request);
            return;
        }
        try {
            ensureWebView(request);
        } catch (IOException error) {
            request.callback.onFailed(request.requestId, error.getMessage());
            return;
        }
        pending = request;
        webView.removeCallbacks(timeout);
        webView.postDelayed(timeout, TIMEOUT_MS);
        webView.loadDataWithBaseURL(EXECUTOR_URL, EXECUTOR_PAGE,
                "text/html", "UTF-8", null);
    }

    private void execute(Pending request) {
        if (pending != request || request.generation != generation) {
            return;
        }
        if (nativeExecution) {
            final Pending work = request;
            handler.removeCallbacks(timeout);
            handler.postDelayed(timeout, TIMEOUT_MS);
            new Thread(new Runnable() {
                @Override public void run() {
                    Bridge host = new Bridge(work);
                    long started = android.os.SystemClock.elapsedRealtime();
                    try {
                        NativeQuickJs.execute(buildJavascript(work), host);
                        if (!host.terminal && !host.isCancelled()) host.fail("脚本没有返回播放结果");
                    } catch (Throwable error) {
                        if (!host.isCancelled()) host.fail("QuickJS: " + safeMessage(error));
                    } finally {
                        Log.i(TAG, "QuickJS channel=" + work.channelName + " elapsedMs="
                                + (android.os.SystemClock.elapsedRealtime() - started));
                    }
                }
            }, "ku9-quickjs").start();
        } else {
            webView.evaluateJavascript(buildJavascript(request), null);
        }
    }

    private String buildJavascript(Pending request) {
        JSONObject item = new JSONObject();
        try {
            item.put("url", request.sourceUrl);
            item.put("name", request.channelName == null ? "" : request.channelName);
        } catch (JSONException ignored) {
        }
        // Ku9 scripts and the legacy RSA module intentionally create a few globals,
        // so this compatibility scope must use the browser's normal sloppy mode.
        return (nativeExecution
                ? "var NtvKu9Bridge=NtvCjsBridge;window.navigator=window.navigator||{appName:'Netscape'};"
                : "") + "(function(){"
                + "function parseJson(v,d){try{return JSON.parse(v);}catch(e){return d;}}"
                + "function headerJson(v){return typeof v==='string'?v:JSON.stringify(v||{});}"
                + "function parseStored(v){if(v===null||v===undefined||v==='')return null;return parseJson(v,v);}"
                + "function uri(v){if(typeof NtvKu9Bridge.parseUri==='function')"
                + "return parseJson(NtvKu9Bridge.parseUri(String(v||'')),{});"
                + "var a=document.createElement('a'),u=String(v||'');a.href=u;"
                + "var q=a.search||'',p={},s=q.charAt(0)==='?'?q.substring(1):q,parts=s?s.split('&'):[];"
                + "for(var i=0;i<parts.length;i++){var pair=parts[i].split('='),k=decodeURIComponent(pair.shift()||''),"
                + "val=decodeURIComponent(pair.join('=').replace(/\\+/g,' '));if(k)p[k]=val;}"
                + "var full=a.pathname||'/';return{Scheme:(a.protocol||'').replace(':',''),Host:a.hostname||'',"
                + "Port:a.port?Number(a.port):-1,Path:full.substring(0,full.lastIndexOf('/')+1),"
                + "Query:q,Fragment:a.hash||'',FullPath:full,Params:p};}"
                + "window.ku9={"
                + "get:function(u,h){return NtvKu9Bridge.get(String(u),headerJson(h));},"
                + "post:function(u,a,b){var h=a&&typeof a==='object'?a:b,body=a&&typeof a==='object'?b:a;"
                + "return NtvKu9Bridge.post(String(u),String(body||''),headerJson(h));},"
                + "request:function(u,m,h,b,f){var r=parseJson(NtvKu9Bridge.request(String(u),String(m||'GET'),headerJson(h),String(b||''),f!==false),{});"
                + "if(r.furl===undefined)r.furl=r.url||'';return r;},"
                + "getHeaders:function(u,h,f,m,b){return JSON.stringify(ku9.request(u,m||'GET',h,b||'',f!==false).headers||{});},"
                + "getQuery:function(u,n){try{var q=String(u).split('?')[1]||'',a=q.split('&');for(var i=0;i<a.length;i++){var p=a[i].split('=');if(decodeURIComponent(p[0]||'')===String(n))return decodeURIComponent((p.slice(1).join('=')||'').replace(/\\+/g,' '));}}catch(e){}return '';},"
                + "getCache:function(k){return parseStored(NtvKu9Bridge.getCache(String(k)));},"
                + "setCache:function(k,v,t){NtvKu9Bridge.setCache(String(k),JSON.stringify(v),Number(t)||0);},"
                + "Uri:uri,"
                + "md5:function(v){return NtvKu9Bridge.md5(String(v));},"
                + "sha1:function(v){return NtvKu9Bridge.digest('SHA-1',String(v));},"
                + "sha256:function(v){return NtvKu9Bridge.digest('SHA-256',String(v));},"
                + "sha512:function(v){return NtvKu9Bridge.digest('SHA-512',String(v));},"
                + "encodeBase64:function(v){return NtvKu9Bridge.encodeBase64(String(v));},"
                + "decodeBase64:function(v){return NtvKu9Bridge.decodeBase64(String(v));},"
                + "isBase64:function(v){var s=String(v||'').replace(/\\s/g,'');return s.length>0&&s.length%4===0&&/^[A-Za-z0-9+/]*={0,2}$/.test(s);},"
                + "isJsonObject:function(v){var x=parseJson(String(v),null);return x!==null&&!Array.isArray(x)&&typeof x==='object';},"
                + "isJsonArray:function(v){return Array.isArray(parseJson(String(v),null));},"
                + "toTimestamp:function(v,f,z){return Number(NtvKu9Bridge.toTimestamp(String(v),String(f||'yyyy-MM-dd HH:mm:ss'),String(z||'')));},"
                + "toDate:function(v,f,z){return NtvKu9Bridge.toDate(Number(v),String(f||'yyyy-MM-dd HH:mm:ss'),String(z||''));},"
                + "formatDateTime:function(v,i,o,d,iz,oz){return NtvKu9Bridge.formatDateTime(String(v),String(i||'yyyy-MM-dd HH:mm:ss'),String(o||'yyyy-MM-dd HH:mm:ss'),Number(d)||0,String(iz||''),String(oz||''));},"
                + "opensslEncrypt:function(d,t,k,o,iv){return ku9Crypto(false,d,t,k,o,iv);},"
                + "opensslDecrypt:function(d,t,k,i,iv){return ku9Crypto(true,d,t,k,i,iv);},"
                + "rc4Encrypt:function(d,k,i,o,c){return ku9Rc4(false,d,k,i,o);},"
                + "rc4Decrypt:function(d,k,i,o,c){return ku9Rc4(true,d,k,i,o);},"
                + "log:function(v){NtvKu9Bridge.log(String(v));}"
                + "};"
                + Ku9JsContract.BROWSER_GLOBALS
                + "window.cjs=window.ku9;"
                + ES5_COMPAT
                + "try{"
                + moduleBootstrap(request.script)
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
                + "NtvKu9Bridge.complete(JSON.stringify(v));}catch(e){fail(e);}}"
                + "function fail(e){NtvKu9Bridge.fail(String(e&&e.stack?e.stack:e));}"
                + "var module={exports:{}},exports=module.exports;" + request.script + "\n"
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
            InputStream input = activity.getAssets().open(path);
            try {
                source = Ku9HttpClient.readUtf8(input, 1024 * 1024);
            } finally {
                input.close();
            }
        } catch (IOException error) {
            Log.e(TAG, "Unable to load Ku9 module " + path, error);
            source = "throw new Error('酷9内置模块加载失败: " + path + "');";
        }
        if (crypto) {
            cryptoModuleSource = source;
        } else {
            jsEncryptModuleSource = source;
        }
        return source;
    }

    private void complete(Pending request, String json) {
        try {
            Ku9JsContract.Output output = Ku9JsContract.parse(json);
            String url = output.url;
            String m3u8 = output.playlist;
            if (!TextUtils.isEmpty(m3u8) && m3u8.trim().startsWith("#EXTM3U")) {
                if (!nativeExecution && containsIpv6Literal(m3u8) && !hasUsableIpv6Network()) {
                    throw new IOException("当前网络没有 IPv6，无法播放此频道");
                }
                completeLivePlaylist(request, m3u8.trim() + "\n");
                return;
            } else if (!TextUtils.isEmpty(url)) {
                if (!nativeExecution && containsIpv6Literal(url) && !hasUsableIpv6Network()) {
                    throw new IOException("当前网络没有 IPv6，无法播放此频道");
                }
                Result result = new Result(url.trim(), Ku9JsContract.isDirectDataSource(url));
                clearPending();
                request.callback.onResolved(request.requestId, result);
                return;
            } else {
                throw new IOException("酷9脚本没有返回可播放地址");
            }
        } catch (Exception error) {
            fail(request, "酷9脚本结果无效: " + safeMessage(error));
        }
    }

    private void completeLivePlaylist(Pending request, String content) throws IOException {
        if (playlistServer == null) {
            playlistServer = new Ku9PlaylistServer();
            playlistServer.start();
        }
        playlistServer.update(content);
        if (nativeExecution) {
            handler.removeCallbacks(timeout);
            handler.removeCallbacks(refreshPlaylist);
            if (!content.contains("#EXT-X-ENDLIST"))
                handler.postDelayed(refreshPlaylist, playlistRefreshDelay(content));
        }
        if (webView != null) {
            webView.removeCallbacks(timeout);
            webView.removeCallbacks(refreshPlaylist);
            webView.postDelayed(refreshPlaylist, playlistRefreshDelay(content));
        }
        if (!request.initialCompleted) {
            request.initialCompleted = true;
            request.callback.onResolved(request.requestId,
                    new Result(playlistServer.url(), false));
        }
    }

    static long playlistRefreshDelay(String content) {
        Matcher matcher = TARGET_DURATION.matcher(content);
        long targetMs = matcher.find() ? (long) parseInt(matcher.group(1)) * 500L
                : MAX_PLAYLIST_REFRESH_MS;
        return Math.max(MIN_PLAYLIST_REFRESH_MS,
                Math.min(MAX_PLAYLIST_REFRESH_MS, targetMs));
    }

    private static boolean containsIpv6Literal(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.US);
        return lower.contains("http://[") || lower.contains("https://[");
    }

    /** Link-local, loopback and multicast addresses do not provide Internet IPv6. */
    private static boolean hasUsableIpv6Network() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet6Address && !address.isAnyLocalAddress()
                            && !address.isLoopbackAddress() && !address.isLinkLocalAddress()
                            && !address.isMulticastAddress()) {
                        byte first = address.getAddress()[0];
                        // fc00::/7 is private ULA and cannot reach the public 2400::/12 CDN.
                        if ((first & 0xfe) != 0xfc) {
                            return true;
                        }
                    }
                }
            }
        } catch (SocketException error) {
            Log.w(TAG, "Unable to inspect IPv6 network state", error);
        }
        return false;
    }

    private void fail(Pending request, String reason) {
        if (request == null || pending != request) {
            return;
        }
        if (request.initialCompleted) {
            Log.w(TAG, reason + "; retaining the last live playlist");
            if (nativeExecution) {
                handler.removeCallbacks(timeout);
                handler.removeCallbacks(refreshPlaylist);
                handler.postDelayed(refreshPlaylist, MAX_PLAYLIST_REFRESH_MS);
            }
            if (webView != null) {
                webView.removeCallbacks(refreshPlaylist);
                webView.postDelayed(refreshPlaylist, MAX_PLAYLIST_REFRESH_MS);
            }
            return;
        }
        clearPending();
        closePlaylistServer();
        request.callback.onFailed(request.requestId, reason);
    }

    private void clearPending() {
        handler.removeCallbacks(timeout);
        handler.removeCallbacks(refreshPlaylist);
        if (webView != null) {
            webView.removeCallbacks(timeout);
            webView.removeCallbacks(refreshPlaylist);
        }
        pending = null;
    }

    void cancel() {
        generation++;
        clearPending();
        closePlaylistServer();
        destroyWebView();
    }

    void destroy() {
        cancel();
    }

    private void destroyWebView() {
        if (webView != null) {
            webView.stopLoading();
            webView.removeJavascriptInterface("NtvKu9Bridge");
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) {
                parent.removeView(webView);
            }
            webView.destroy();
            webView = null;
        }
    }

    private void closePlaylistServer() {
        if (playlistServer != null) {
            playlistServer.close();
            playlistServer = null;
        }
    }

    private final class Bridge implements NativeQuickJs.Host {
        private final Pending request;
        private boolean terminal;

        @Override public boolean isCancelled() {
            return request.generation != generation || pending != request || Thread.currentThread().isInterrupted();
        }

        @Override public String invoke(int operation, String[] args) throws Exception {
            if (isCancelled()) throw new IOException("Ku9 request cancelled");
            switch (operation) {
                case 0: return get(args[0], args[1]);
                case 1: return post(args[0], args[1], args[2]);
                case 2: return request(args[0], args[1], args[2], args[3], Boolean.parseBoolean(args[4]));
                case 3: return md5(args[0]);
                case 4: log(args[0]); return null;
                case 5: complete(args[0]); return null;
                case 6: fail(args[0]); return null;
                case 7: return getCache(args[0]);
                case 8: setCache(args[0], args[1], Double.parseDouble(args[2])); return null;
                case 9: return digest(args[0], args[1]);
                case 10: return encodeBase64(args[0]);
                case 11: return decodeBase64(args[0]);
                case 12: return String.valueOf(toTimestamp(args[0], args[1], args[2]));
                case 13: return toDate(Double.parseDouble(args[0]), args[1], args[2]);
                case 14: return formatDateTime(args[0], args[1], args[2],
                        Double.parseDouble(args[3]), args[4], args[5]);
                case 15: return parseUri(args[0]);
                default: throw new IOException("Unknown Ku9 host operation");
            }
        }

        Bridge(Pending request) {
            this.request = request;
        }

        @JavascriptInterface
        public String get(String url, String headersJson) {
            try {
                return Ku9HttpClient.getText(url, Ku9HttpClient.parseHeaders(headersJson),
                        MAX_RESPONSE_BYTES);
            } catch (IOException error) {
                Log.w(TAG, "Ku9 GET failed: " + url, error);
                return "";
            }
        }

        @JavascriptInterface
        public String post(String url, String body, String headersJson) {
            return Ku9HttpClient.postText(url, body, headersJson, MAX_RESPONSE_BYTES);
        }

        @JavascriptInterface
        public String request(String url, String method, String headersJson, String body,
                boolean followRedirects) {
            return Ku9HttpClient.requestJson(url, method, headersJson, body,
                    followRedirects, MAX_RESPONSE_BYTES);
        }

        @JavascriptInterface
        public String getCache(String key) {
            long expiresAt = cache.getLong(key + "__expires", 0L);
            if (expiresAt > 0L && expiresAt < System.currentTimeMillis()) {
                cache.edit().remove(key).remove(key + "__expires").apply();
                return "";
            }
            return cache.getString(key, "");
        }

        @JavascriptInterface
        public void setCache(String key, String value, double ttlMs) {
            long expiresAt = ttlMs <= 0 ? 0L
                    : System.currentTimeMillis() + Math.max(0L, (long) ttlMs);
            cache.edit().putString(key, value == null ? "" : value)
                    .putLong(key + "__expires", expiresAt).apply();
        }

        // Native equivalent of the browser anchor parser used by ku9.Uri.
        private String parseUri(String value) throws Exception {
            java.net.URI uri = java.net.URI.create(value);
            String path = uri.getRawPath();
            if (path == null || path.length() == 0) path = "/";
            String query = uri.getRawQuery(), fragment = uri.getRawFragment();
            JSONObject params = new JSONObject();
            if (query != null && query.length() > 0) {
                for (String part : query.split("&")) {
                    int equals = part.indexOf('=');
                    String key = equals < 0 ? part : part.substring(0, equals);
                    String val = equals < 0 ? "" : part.substring(equals + 1);
                    if (key.length() > 0) params.put(java.net.URLDecoder.decode(key, "UTF-8"),
                            java.net.URLDecoder.decode(val, "UTF-8"));
                }
            }
            return new JSONObject().put("Scheme", uri.getScheme() == null ? "" : uri.getScheme())
                    .put("Host", uri.getHost() == null ? "" : uri.getHost())
                    .put("Port", uri.getPort()).put("FullPath", path)
                    .put("Path", path.substring(0, path.lastIndexOf('/') + 1))
                    .put("Query", query == null ? "" : "?" + query)
                    .put("Fragment", fragment == null ? "" : "#" + fragment)
                    .put("Params", params).toString();
        }

        @JavascriptInterface
        public String md5(String value) {
            return digest("MD5", value);
        }

        @JavascriptInterface
        public String digest(String algorithm, String value) {
            try {
                MessageDigest digest = MessageDigest.getInstance(algorithm);
                byte[] bytes = digest.digest(value.getBytes("UTF-8"));
                StringBuilder result = new StringBuilder(bytes.length * 2);
                for (byte item : bytes) {
                    result.append(String.format(Locale.US, "%02x", item & 0xff));
                }
                return result.toString();
            } catch (Exception error) {
                return "";
            }
        }

        @JavascriptInterface
        public String encodeBase64(String value) {
            try {
                return Base64.encodeToString(value.getBytes("UTF-8"), Base64.NO_WRAP);
            } catch (Exception error) {
                return "";
            }
        }

        @JavascriptInterface
        public String decodeBase64(String value) {
            try {
                return new String(Base64.decode(value, Base64.DEFAULT), "UTF-8");
            } catch (Exception error) {
                return "";
            }
        }

        @JavascriptInterface
        public double toTimestamp(String value, String format, String timezone) {
            try {
                return dateFormat(format, timezone).parse(value).getTime();
            } catch (Exception error) {
                return 0;
            }
        }

        @JavascriptInterface
        public String toDate(double timestamp, String format, String timezone) {
            try {
                return dateFormat(format, timezone).format(new Date((long) timestamp));
            } catch (Exception error) {
                return "";
            }
        }

        @JavascriptInterface
        public String formatDateTime(String value, String inputFormat, String outputFormat,
                double daysOffset, String inputTimezone, String outputTimezone) {
            try {
                Date date = dateFormat(inputFormat, inputTimezone).parse(value);
                Calendar calendar = Calendar.getInstance(timeZone(inputTimezone));
                calendar.setTime(date);
                calendar.add(Calendar.DAY_OF_MONTH, (int) daysOffset);
                return dateFormat(outputFormat, outputTimezone).format(calendar.getTime());
            } catch (Exception error) {
                return "";
            }
        }

        @JavascriptInterface
        public void log(String value) {
            Log.d(TAG, value);
        }

        @JavascriptInterface
        public void complete(final String resultJson) {
            if (nativeExecution && terminal) return;
            if (nativeExecution) terminal = true;
            if (pending != request) {
                return;
            }
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (pending == request && request.generation == generation) {
                        Ku9ScriptResolver.this.complete(request, resultJson);
                    }
                }
            });
        }

        @JavascriptInterface
        public void fail(final String reason) {
            if (nativeExecution && terminal) return;
            if (nativeExecution) terminal = true;
            if (pending != request) {
                return;
            }
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (pending == request && request.generation == generation) {
                        Ku9ScriptResolver.this.fail(request,
                                "酷9脚本执行失败: " + reason);
                    }
                }
            });
        }
    }

    private static SimpleDateFormat dateFormat(String pattern, String timezone) {
        SimpleDateFormat format = new SimpleDateFormat(
                TextUtils.isEmpty(pattern) ? "yyyy-MM-dd HH:mm:ss" : pattern, Locale.US);
        format.setLenient(false);
        format.setTimeZone(timeZone(timezone));
        return format;
    }

    private static TimeZone timeZone(String timezone) {
        return TextUtils.isEmpty(timezone) ? TimeZone.getDefault()
                : TimeZone.getTimeZone(timezone);
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return TextUtils.isEmpty(message) ? "未知错误" : message;
    }

    private static final class Pending {
        final int requestId;
        final int generation;
        final String channelName;
        final String sourceUrl;
        final String script;
        final Callback callback;
        boolean initialCompleted;

        Pending(int requestId, int generation, String channelName, String sourceUrl,
                String script, Callback callback) {
            this.requestId = requestId;
            this.generation = generation;
            this.channelName = channelName;
            this.sourceUrl = sourceUrl;
            this.script = script;
            this.callback = callback;
        }
    }

}
