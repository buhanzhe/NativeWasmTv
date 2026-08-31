package xiao.bu.tv;

import com.bu.cc.tv.NativeYspSigner;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.net.http.SslError;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

final class YangshipinWebResolver {
    interface Callback {
        void onResolved(int requestId, String url, String cmgTag,
                String cmgInitialUpdateTag, String cmgUpdateTag, int cmgUpdateWarmupCount,
                long cmgInitTimeMs, long cmgUpdateBaseTimeMs, String cmgUpdateTrace,
                String cmgNativeTrace);

        void onFailed(int requestId, String reason);
    }

    private static final String TAG = "YangshipinResolver";
    private static final long TIMEOUT_MS = 30000L;
    private static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    private static final String TAG_PREFIX = "__NTV_CMG_MEDIA_TAG__";
    private static final String API_RESULT_PREFIX = "__NTV_YSP_API__";
    private static final String API_AUTH_PREFIX = "__NTV_YSP_AUTH__";
    private static final String API_ERROR_PREFIX = "__NTV_YSP_API_ERROR__";
    private static final String TICKET_KEYSTREAM =
            "6a70e9ac007ccc384c28a88dfd2211decb66494bca83bd6178f85732b2eacc959"
                    + "4e4e5ab720ec3a43e87b3c72b86192ff0dfea8fa8f47794d8792668438c";
    private static final long API_CACHE_MS = 10L * 60L * 1000L;
    private static final long CLOCK_CACHE_MS = 24L * 60L * 60L * 1000L;
    private static final String PREFS_NAME = "yangshipin_resolver";
    private static final String IMPORT_HOOK =
            ";(function(){try{"
                    + "if(!WebAssembly||WebAssembly.__ntvImportHooked)return;"
                    + "WebAssembly.__ntvImportHooked=true;"
                    + "self.__ntvImportTrace=[];self.__ntvEmvalText={};"
                    + "function mem(env){try{return env&&env.memory&&env.memory.buffer?new Uint8Array(env.memory.buffer):null;}catch(e){return null;}}"
                    + "function cstr(env,p){var m=mem(env);if(!m||!p)return '';var s='',i=p,n=Math.min(m.length,p+160);for(;i<n&&m[i];i++){s+=String.fromCharCode(m[i]);}return s;}"
                    + "function u32(env,p){var m=mem(env);if(!m||!p||p+3>=m.length)return 0;return (m[p]|(m[p+1]<<8)|(m[p+2]<<16)|(m[p+3]<<24))>>>0;}"
                    + "function hex(env,p,n){var m=mem(env);if(!m||!p||p>=m.length)return '';var out=[],e=Math.min(m.length,p+n);for(var i=p;i<e;i++){var h=m[i].toString(16);out.push(h.length<2?'0'+h:h);}return out.join('');}"
                    + "function strVal(env,p){var q=u32(env,p),a=q&&q<memoryLength(env)?q:p,l=u32(env,a);if(l>512)return '';var m=mem(env);if(!m||!a||a+4+l>m.length)return '';var s='';for(var i=0;i<l;i++){s+=String.fromCharCode(m[a+4+i]);}return s;}"
                    + "function memoryLength(env){var m=mem(env);return m?m.length:0;}"
                    + "function snap(env){return 'top='+u32(env,17904)+',s18336='+hex(env,18336,64)+',s18656='+hex(env,18656,64)+',h6691728='+hex(env,6691728,96);}"
                    + "function loc(prop){try{return String(location[prop]||'');}catch(e){return '';}}"
                    + "function wrap(importObject){try{var env=importObject&&importObject.env;if(!env||env.__ntvImportWrapped)return;env.__ntvImportWrapped=true;"
                    + "['z','B','C','D','E','F','G'].forEach(function(name){var original=env[name];if(typeof original!=='function')return;"
                    + "env[name]=function(){var args=[];for(var i=0;i<arguments.length;i++){args.push(arguments[i]);}"
                    + "var detail='';try{"
                    + "if(name==='z'){detail=cstr(env,args[1]);}"
                    + "else if(name==='E'){detail=cstr(env,args[0]);}"
                    + "else if(name==='B'){detail=strVal(env,args[1]);}"
                    + "else if(name==='D'){detail=(self.__ntvEmvalText||{})[args[1]]||'';}"
                    + "else if(name==='G'){detail=(self.__ntvEmvalText||{})[args[0]]||'';}"
                    + "}catch(ex){}"
                    + "var beforeD=0,beforeHex='',beforeResultHex='',beforeTop=0;try{if(name==='G'){beforeD=u32(env,args[2]);beforeHex=hex(env,args[2],16);beforeResultHex=hex(env,beforeD,64);beforeTop=u32(env,17904);}}catch(exG0){}"
                    + "var result=original.apply(this,arguments);var emvalDetail=detail;var traceDetail=detail;"
                    + "try{if(name==='G'){detail+=',top0='+beforeTop+',top1='+u32(env,17904)+',d0='+beforeD+',dh0='+beforeHex+',rbh='+beforeResultHex+',d1='+u32(env,args[2])+',dh1='+hex(env,args[2],16)+',r0='+u32(env,result>>>0)+',rh='+hex(env,result>>>0,64)+',cs0='+cstr(env,result>>>0)+',cs4='+cstr(env,(result>>>0)+4);}}catch(exG1){}"
                    + "traceDetail=detail;"
                    + "try{if(name==='B'||name==='C'||name==='E'||name==='F'){detail+=',snap='+snap(env);}}catch(exS){}"
                    + "try{if(name==='B'&&result>4&&emvalDetail){self.__ntvEmvalText[result]=emvalDetail;}"
                    + "if(name==='D'&&result>4){var prop=(self.__ntvEmvalText||{})[args[1]]||'';if(prop){self.__ntvEmvalText[result]=loc(prop);}}"
                    + "if(name==='F'&&args[0]>4){delete self.__ntvEmvalText[args[0]];}}catch(ex2){}"
                    + "if(self.__ntvImportTrace&&self.__ntvImportTrace.length<128){self.__ntvImportTrace.push(name+':'+args.join(',')+':'+traceDetail+'->'+result);}"
                    + "return result;};});}catch(e){}}"
                    + "var oldInstantiate=WebAssembly.instantiate;"
                    + "if(oldInstantiate){WebAssembly.instantiate=function(source,imports){wrap(imports);return oldInstantiate.apply(this,arguments);};}"
                    + "var oldStreaming=WebAssembly.instantiateStreaming;"
                    + "if(oldStreaming){WebAssembly.instantiateStreaming=function(source,imports){wrap(imports);return oldStreaming.apply(this,arguments);};}"
                    + "}catch(e){try{console.log('__NTV_CMG_IMPORT_HOOK_ERROR__'+e);}catch(x){}}})();";
    private static final String HLS_HOOK =
            ";(function(){try{"
                    + "if(!fG||!fG.moduleDecData||fG.__ntvTagHooked)return;"
                    + "fG.__ntvTagHooked=true;"
                    + "if(fG.moduleActive){"
                    + "var originalModuleActive=fG.moduleActive;"
                    + "fG.__ntvUpdateCount=0;"
                    + "fG.__ntvInitNow=0;"
                    + "fG.__ntvInitResult='';"
                    + "fG.__ntvUpdateBaseNow=0;"
                    + "fG.__ntvUpdateTrace=[];"
                    + "fG.__ntvNativeTrace=[];"
                    + "function hookNativeModule(module){try{"
                    + "if(!module||module.__ntvNativeTraceHooked)return;"
                    + "module.__ntvNativeTraceHooked=true;"
                    + "['_jsmalloc','_jsfree','_CMG_InitPlayer','_CMG_UpdatePlayer'].forEach(function(name){"
                    + "var original=module[name];"
                    + "if(typeof original!=='function')return;"
                    + "module[name]=function(){"
                    + "var args=[];for(var i=0;i<arguments.length;i++){args.push(arguments[i]);}"
                    + "var result=original.apply(this,arguments);"
                    + "if(fG.__ntvNativeTrace.length<32){fG.__ntvNativeTrace.push(name+':'+args.join(',')+'->'+result);}"
                    + "return result;"
                    + "};"
                    + "});"
                    + "}catch(e){}}"
                    + "fG.moduleActive=function(module,mediaTagId,action){"
                    + "hookNativeModule(module);"
                    + "var beforeNow=Date.now?Date.now():0;"
                    + "var result=originalModuleActive.apply(this,arguments);"
                    + "try{if(action===fG.INITPLAYER){"
                    + "fG.__ntvInitNow=beforeNow;"
                    + "fG.__ntvInitResult=String(result||'');"
                    + "try{fG.__ntvLocation=[String(location.href||''),String(location.protocol||''),String(location.host||''),String(location.origin||'')].join(',');}catch(ex){fG.__ntvLocation='';}"
                    + "}}catch(e){}"
                    + "try{if(action===fG.UPDATEPLAYER){"
                    + "fG.__ntvUpdateCount++;"
                    + "if(!fG.__ntvUpdateBaseNow){fG.__ntvUpdateBaseNow=beforeNow;}"
                    + "if(fG.__ntvUpdateTrace.length<96){"
                    + "fG.__ntvUpdateTrace.push((beforeNow-fG.__ntvUpdateBaseNow)+','+(self.vmpTag||''));"
                    + "}"
                    + "}}catch(e){}"
                    + "return result;"
                    + "};"
                    + "}"
                    + "var originalModuleDecData=fG.moduleDecData;"
                    + "fG.moduleDecData=function(module,mediaTagId,data){"
                    + "try{"
                    + "var nalType=data&&data.length?(data[0]&31):-1;"
                    + "var tag=self.vmpTag||'';"
                    + "if(!fG.__ntvFirstVmpTag&&nalType===7&&tag){fG.__ntvFirstVmpTag=tag;}"
                    + "if(!fG.__ntvLoggedMediaTag&&(nalType===1||nalType===5)&&tag){"
                    + "fG.__ntvLoggedMediaTag=true;"
                    + "console.log('" + TAG_PREFIX + "'+mediaTagId+'|'+(fG.__ntvFirstVmpTag||tag)+'|'+tag+'|'+(fG.__ntvUpdateCount||0)+'|'+(fG.__ntvInitNow||0)+'|'+(fG.__ntvUpdateBaseNow||0)+'|'+(fG.__ntvInitResult||'')+'|'+(self.activeURL||'')+'|'+(fG.__ntvLocation||'')+'|'+(fG.__ntvNativeTrace||[]).join(';')+'|'+(fG.__ntvUpdateTrace||[]).join(';')+'|'+(self.__ntvImportTrace||[]).join(';'));"
                    + "}"
                    + "}catch(e){}"
                    + "var result=originalModuleDecData.apply(this,arguments);"
                    + "return result;"
                    + "};"
                    + "}catch(e){try{console.log('__NTV_CMG_HOOK_ERROR__'+e);}catch(x){}}"
                    + "})();";

    private final Activity activity;
    private final FrameLayout root;
    private WebView webView;
    private final boolean keepTracePage;
    private final SharedPreferences resolverPrefs;
    private final Runnable timeout = new Runnable() {
        @Override
        public void run() {
            Pending pending = pendingRequest;
            if (pending != null) {
                fail(pending, "央视频解析超时");
            }
        }
    };
    private final Runnable pollPage = new Runnable() {
        @Override
        public void run() {
            pollPageForVideoUrl();
        }
    };
    private final Runnable traceKeepAlive = new Runnable() {
        @Override
        public void run() {
            keepTracePlaybackAlive();
        }
    };

    private Pending pendingRequest;
    private final Map<String, CachedUrl> apiCache = new HashMap<String, CachedUrl>();
    private long serverClockOffsetMs = Long.MIN_VALUE;

    @SuppressLint("SetJavaScriptEnabled")
    YangshipinWebResolver(Activity activity, FrameLayout root, boolean keepTracePage) {
        this.activity = activity;
        this.root = root;
        this.keepTracePage = keepTracePage;
        resolverPrefs = activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE);
        long savedAt = resolverPrefs.getLong("clock_saved_at", 0L);
        long savedOffset = resolverPrefs.getLong("clock_offset_ms", Long.MIN_VALUE);
        long savedAge = System.currentTimeMillis() - savedAt;
        if (savedOffset != Long.MIN_VALUE && savedAge >= 0L && savedAge < CLOCK_CACHE_MS) {
            serverClockOffsetMs = savedOffset;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void ensureWebView() {
        if (webView != null) {
            return;
        }
        webView = new WebView(activity.getApplicationContext());
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setAlpha(0.01f);
        webView.setVisibility(View.GONE);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                keepTracePage ? 320 : 1, keepTracePage ? 180 : 1);
        params.gravity = Gravity.LEFT | Gravity.TOP;
        root.addView(webView, params);

        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            CookieSyncManager.createInstance(activity.getApplicationContext());
        }

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUserAgentString(DESKTOP_USER_AGENT);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(false);
        webView.addJavascriptInterface(new SignerBridge(), "NtvYspSigner");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings.setMediaPlaybackRequiresUserGesture(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                if (consoleMessage != null) {
                    String message = consoleMessage.message();
                    if (maybeResolveApiAuth(message) || maybeResolveApiResult(message)) {
                        return true;
                    }
                    maybeResolveCmgTag(message);
                }
                return super.onConsoleMessage(consoleMessage);
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler,
                    SslError error) {
                Log.w(TAG, "Ignoring WebView SSL error: " + error);
                handler.proceed();
            }

            @Override
            public void onLoadResource(WebView view, String url) {
                maybeResolve(url);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                Pending pending = pendingRequest;
                if (pending != null && pending.apiHtml != null
                        && "https://www.yangshipin.cn/".equals(url)) {
                    try {
                        return new WebResourceResponse("text/html", "UTF-8",
                                new ByteArrayInputStream(pending.apiHtml.getBytes("UTF-8")));
                    } catch (UnsupportedEncodingException error) {
                        Log.e(TAG, "Unable to serve Yangshipin API page", error);
                    }
                }
                if (pending != null && pending.bootstrapScript != null && url != null
                        && url.startsWith("https://www.yangshipin.cn/tv/home")) {
                    WebResourceResponse response = patchOfficialPage(url, pending.bootstrapScript);
                    if (response != null) {
                        return response;
                    }
                }
                maybeResolve(url);
                if (url != null && url.toLowerCase().contains("hls.cmg.js")) {
                    WebResourceResponse response = patchHlsCmgScript(url);
                    if (response != null) {
                        return response;
                    }
                }
                return super.shouldInterceptRequest(view, url);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode,
                    String description, String failingUrl) {
                Pending pending = pendingRequest;
                if (pending != null && failingUrl != null
                        && failingUrl.contains("yangshipin.cn")) {
                    fail(pending, description == null ? "央视频页面加载失败" : description);
                }
            }
        });
    }

    void resolve(final int requestId, final Channel channel, final String definition,
            final Callback callback) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                clearPending(false);
                if (channel.yangshipinPid == null || channel.yangshipinPid.length() == 0) {
                    callback.onFailed(requestId, "频道缺少央视频 pid");
                    return;
                }
                pendingRequest = new Pending(requestId, channel, definition, callback);
                String cacheKey = pendingRequest.cacheKey();
                CachedUrl cached = apiCache.get(cacheKey);
                if (cached == null) {
                    String key = "url_" + cacheKey;
                    String persistedUrl = resolverPrefs.getString(key, "");
                    long persistedAt = resolverPrefs.getLong(key + "_at", 0L);
                    if (persistedUrl.length() > 0) {
                        cached = new CachedUrl(persistedUrl, persistedAt);
                    }
                }
                long cacheAge = cached == null ? Long.MAX_VALUE
                        : System.currentTimeMillis() - cached.createdAt;
                if (cached != null && cacheAge >= 0L && cacheAge < API_CACHE_MS) {
                    Log.i(TAG, "Using cached Yangshipin " + pendingRequest.definition
                            + " URL for " + channel.name);
                    completeApi(pendingRequest, cached.url);
                    return;
                }
                try {
                    ensureWebView();
                    webView.setVisibility(View.VISIBLE);
                    boolean clockKnown = serverClockOffsetMs != Long.MIN_VALUE;
                    long guidTimeMs = clockKnown
                            ? System.currentTimeMillis() + serverClockOffsetMs
                            : System.currentTimeMillis();
                    YangshipinApiPayload payload = YangshipinApiPayload.create(
                            channel, guidTimeMs, pendingRequest.definition);
                    String html = buildApiPage(channel, payload);
                    pendingRequest.apiHtml = html;
                    pendingRequest.apiPayload = payload;
                    pendingRequest.clockSynced = clockKnown;
                    pendingRequest.bootstrapScript = buildOfficialBootstrapScript(payload);
                    installApiCookies(payload);
                    Log.i(TAG, "Resolving Yangshipin " + pendingRequest.definition
                            + " API for " + channel.name);
                    webView.onResume();
                    webView.loadUrl("https://www.yangshipin.cn/");
                    webView.postDelayed(timeout, 15000L);
                } catch (Exception error) {
                    Log.e(TAG, "Unable to build Yangshipin API request", error);
                    fail(pendingRequest, "央视频请求生成失败");
                }
            }
        });
    }

    private String buildApiPage(Channel channel, YangshipinApiPayload payload) {
        String authBody = JSONObject.quote(payload.authBody);
        return "<!doctype html><meta charset=\"utf-8\">"
                + "<meta name=\"referrer\" content=\"origin\">"
                + "<script>"
                + "(function(){var done=false;function fail(m){if(done)return;done=true;console.log('"
                + API_ERROR_PREFIX + "'+String(m||'unknown'));}"
                + "var a=new XMLHttpRequest();a.open('POST','https://player-api.yangshipin.cn/v1/player/auth',true);"
                + "a.withCredentials=true;a.timeout=10000;a.setRequestHeader('Accept','application/json, text/plain, */*');"
                + "a.setRequestHeader('Content-Type','application/x-www-form-urlencoded;charset=UTF-8');"
                + "a.setRequestHeader('yspappid','519748109');a.onerror=function(){fail('auth-network');};"
                + "a.ontimeout=function(){fail('auth-timeout');};a.onreadystatechange=function(){if(a.readyState!==4||done)return;"
                + "if(a.status!==200){fail('auth-http-'+a.status);return;}var j;try{j=JSON.parse(a.responseText);}" 
                + "catch(e){fail('auth-json');return;}if(!j||j.code!==0||!j.data||!j.data.token||!j.data.ts){fail('auth-data');return;}"
                + "done=true;console.log('" + API_AUTH_PREFIX
                + "'+encodeURIComponent(j.data.token)+'|'+String(j.data.ts));};"
                + "a.send(" + authBody + ");})();</script>";
    }

    private boolean maybeResolveApiAuth(String message) {
        final Pending pending = pendingRequest;
        if (pending == null || pending.apiPayload == null || message == null
                || !message.startsWith(API_AUTH_PREFIX)) {
            return false;
        }
        try {
            String value = message.substring(API_AUTH_PREFIX.length());
            int separator = value.lastIndexOf('|');
            if (separator <= 0) {
                throw new IllegalArgumentException("missing auth separator");
            }
            String token = Uri.decode(value.substring(0, separator));
            long serverSeconds = Long.parseLong(value.substring(separator + 1));
            serverClockOffsetMs = serverSeconds * 1000L - System.currentTimeMillis();
            resolverPrefs.edit()
                    .putLong("clock_offset_ms", serverClockOffsetMs)
                    .putLong("clock_saved_at", System.currentTimeMillis())
                    .apply();
            if (!pending.clockSynced) {
                YangshipinApiPayload corrected = YangshipinApiPayload.create(
                        pending.channel, serverSeconds * 1000L, pending.definition);
                pending.apiPayload = corrected;
                pending.clockSynced = true;
                pending.apiHtml = buildApiPage(pending.channel, corrected);
                pending.bootstrapScript = buildOfficialBootstrapScript(corrected);
                installApiCookies(corrected);
                Log.i(TAG, "Yangshipin clock calibrated offsetMs=" + serverClockOffsetMs
                        + "; refreshing auth identity");
                webView.loadUrl("about:blank");
                webView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (pendingRequest == pending) {
                            webView.loadUrl("https://www.yangshipin.cn/");
                        }
                    }
                }, 50L);
                return true;
            }
            String liveBody = YangshipinApiPayload.createLiveBody(pending.channel,
                    pending.apiPayload.guid, pending.apiPayload.liveRandom, serverSeconds,
                    pending.definition);
            String sdkInput = YangshipinApiPayload.createSdkInput(liveBody);
            Log.i(TAG, "Yangshipin live request serverSeconds=" + serverSeconds
                    + " guid=" + pending.apiPayload.guid
                    + " requestId=" + pending.apiPayload.requestId);
            String script = buildLiveRequestScript(pending, token, serverSeconds,
                    liveBody, sdkInput);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                webView.evaluateJavascript(script, null);
            } else {
                webView.loadUrl("javascript:" + script);
            }
        } catch (Exception error) {
            Log.e(TAG, "Unable to continue Yangshipin server-time request", error);
            fail(pending, "央视频服务器时间处理失败");
        }
        return true;
    }

    private String buildLiveRequestScript(Pending pending, String token,
            long serverSeconds, String liveBody, String sdkInput) {
        YangshipinApiPayload payload = pending.apiPayload;
        String pid = JSONObject.quote(pending.channel.yangshipinPid);
        String guid = JSONObject.quote(payload.guid);
        String ticketRandom = JSONObject.quote(payload.ticketRandom);
        String requestId = JSONObject.quote(payload.requestId);
        String fullSdkInput = sdkInput + "-" + payload.guid + "-1-" + payload.requestId;
        long tokenTimeMs = serverSeconds * 1000L;
        return "(function(){var done=false;function fail(m){if(done)return;done=true;console.log('"
                + API_ERROR_PREFIX + "'+String(m||'live'));}"
                + "function ticket(){var p=" + pid + "+'&'+'" + serverSeconds + "'+'&'+" + guid
                + "+'&519748109&'+" + ticketRandom + ";var k='" + TICKET_KEYSTREAM
                + "',o='',i,b;for(i=0;i<p.length;i++){b=p.charCodeAt(i)^parseInt(k.substr(i*2,2),16);"
                + "o+=(b<16?'0':'')+b.toString(16);}return o;}"
                + "function send(sig){var x=new XMLHttpRequest();x.open('POST','https://player-api.yangshipin.cn/v1/player/get_live_info',true);"
                + "x.withCredentials=true;x.timeout=10000;x.setRequestHeader('Accept','application/json, text/plain, */*');"
                + "x.setRequestHeader('Content-Type','application/json;charset=UTF-8');"
                + "x.setRequestHeader('yspappid','519748109');x.setRequestHeader('yspplayertoken',"
                + JSONObject.quote(token) + ");x.setRequestHeader('yspticket',ticket());"
                + "x.setRequestHeader('seqid','1');x.setRequestHeader('request-id'," + requestId + ");"
                + "x.setRequestHeader('yspsdkinput'," + JSONObject.quote(sdkInput) + ");"
                + "x.setRequestHeader('yspsdksign',sig);"
                + "x.onerror=function(){fail('live-network');};x.ontimeout=function(){fail('live-timeout');};"
                + "x.onreadystatechange=function(){if(x.readyState!==4||done)return;if(x.status!==200){"
                + "fail('live-http-'+x.status);return;}done=true;console.log('" + API_RESULT_PREFIX
                + "'+encodeURIComponent(x.responseText));};x.send(" + JSONObject.quote(liveBody) + ");}"
                + "function openToken(){var ts='" + tokenTimeMs + "',rnd='',base='"
                + "https://h5access.yangshipin.cn/web/open/token?yspappid=519748109"
                + "&guid='+encodeURIComponent(" + guid + ")+"
                + "'&vappid=59306155&vsecret=b42702bf7309a179d102f3d51b1add2fda0bc7ada64cb801"
                + "&raw=1&version=v1&ts='+ts;try{rnd=NtvYspSigner.tokenRnd(" + guid + ",ts);}" 
                + "catch(e){fail('sdk-rnd');return;}if(!rnd){fail('sdk-rnd-empty');return;}"
                + "var q=new XMLHttpRequest();q.open('GET',base+'&rnd='+encodeURIComponent(rnd),true);"
                + "q.withCredentials=true;q.timeout=8000;q.onerror=function(){fail('sdk-token-network');};"
                + "q.ontimeout=function(){fail('sdk-token-timeout');};q.onreadystatechange=function(){"
                + "if(q.readyState!==4||done)return;if(q.status!==200){fail('sdk-token-http-'+q.status);return;}"
                + "var j,t;try{j=JSON.parse(q.responseText);t=j&&j.ret===0&&j.data&&j.data.token;}"
                + "catch(e){fail('sdk-token-json');return;}if(!t){fail('sdk-token-empty');return;}"
                + "var input=" + JSONObject.quote(fullSdkInput) + ",sig='';try{sig=NtvYspSigner.signature("
                + guid + ",String(t),input);}catch(e){fail('sdk-sign');return;}"
                + "if(!sig){fail('sdk-sign-empty');return;}send(sig+'-'+input);};q.send(null);}openToken();})();";
    }

    final class SignerBridge {
        @JavascriptInterface
        public String tokenRnd(String guid, String timestampMs) {
            try {
                return NativeYspSigner.tokenRnd(guid, timestampMs);
            } catch (Throwable error) {
                Log.e(TAG, "Unable to create Yangshipin token rnd", error);
                return "";
            }
        }

        @JavascriptInterface
        public String signature(String guid, String token, String input) {
            try {
                return NativeYspSigner.signature(guid, token, input);
            } catch (Throwable error) {
                Log.e(TAG, "Unable to create Yangshipin SDK signature", error);
                return "";
            }
        }
    }

    private void installApiCookies(YangshipinApiPayload payload) {
        CookieManager cookies = CookieManager.getInstance();
        String domain = "; Domain=.yangshipin.cn; Path=/";
        cookies.setCookie("https://player-api.yangshipin.cn", "guid=" + payload.guid + domain);
        cookies.setCookie("https://player-api.yangshipin.cn", "versionName=99.99.99" + domain);
        cookies.setCookie("https://player-api.yangshipin.cn", "versionCode=999999" + domain);
        cookies.setCookie("https://player-api.yangshipin.cn", "vplatform=109" + domain);
        cookies.setCookie("https://player-api.yangshipin.cn", "platformVersion=Chrome" + domain);
        cookies.setCookie("https://player-api.yangshipin.cn", "deviceModel=148" + domain);
        cookies.setCookie("https://player-api.yangshipin.cn", "newLogin=1" + domain);
        cookies.setCookie("https://player-api.yangshipin.cn", "pc_version=1.1.16" + domain);
        cookies.setCookie("https://player-api.yangshipin.cn", "ysp_uinfo_pc=" + domain);
        cookies.setCookie("https://player-api.yangshipin.cn", "nseqId=1" + domain);
        cookies.setCookie("https://player-api.yangshipin.cn",
                "nrequest-id=" + payload.requestId + domain);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            CookieSyncManager.getInstance().sync();
        }
    }

    private String buildOfficialBootstrapScript(YangshipinApiPayload payload) {
        String pid = JSONObject.quote(pendingRequest.channel.yangshipinPid);
        String guid = JSONObject.quote(payload.guid);
        String ticketRandom = JSONObject.quote(payload.ticketRandom);
        String requestId = JSONObject.quote(payload.requestId);
        String liveBody = JSONObject.quote(payload.liveBody);
        return "(function(){if(window.__ntvAuthHook)return;window.__ntvAuthHook=true;"
                + "var done=false;function fail(m){if(done)return;done=true;console.log('"
                + API_ERROR_PREFIX + "'+String(m||'bootstrap'));}"
                + "function ticket(ts){var p=" + pid + "+'&'+String(ts)+'&'+" + guid
                + "+'&519748109&'+" + ticketRandom + ";var k='" + TICKET_KEYSTREAM
                + "',o='',i,b;for(i=0;i<p.length;i++){b=p.charCodeAt(i)^parseInt(k.substr(i*2,2),16);"
                + "o+=(b<16?'0':'')+b.toString(16);}return o;}"
                + "function live(d){if(done||!d||!d.token||!d.ts)return;"
                + "var x=new XMLHttpRequest();x.open('POST','https://player-api.yangshipin.cn/v1/player/get_live_info',true);"
                + "x.timeout=10000;x.setRequestHeader('Accept','application/json, text/plain, */*');"
                + "x.setRequestHeader('Content-Type','application/json;charset=UTF-8');"
                + "x.setRequestHeader('yspappid','519748109');x.setRequestHeader('yspplayertoken',d.token);"
                + "x.setRequestHeader('yspticket',ticket(d.ts));x.setRequestHeader('seqid','1');"
                + "x.setRequestHeader('request-id'," + requestId
                + ");x.onerror=function(){fail('bootstrap-live-network');};"
                + "x.ontimeout=function(){fail('bootstrap-live-timeout');};x.onreadystatechange=function(){"
                + "if(x.readyState!==4||done)return;if(x.status!==200){fail('bootstrap-live-http-'+x.status);return;}"
                + "done=true;console.log('" + API_RESULT_PREFIX
                + "'+encodeURIComponent(x.responseText));};x.send(" + liveBody + ");}"
                + "var op=XMLHttpRequest.prototype.open,sd=XMLHttpRequest.prototype.send;"
                + "XMLHttpRequest.prototype.open=function(m,u){this.__ntvAuthUrl=String(u||'');return op.apply(this,arguments);};"
                + "XMLHttpRequest.prototype.send=function(){var q=this;if(q.__ntvAuthUrl.indexOf('/v1/player/auth')>=0){"
                + "q.addEventListener('readystatechange',function(){if(q.readyState===4&&q.status===200&&!done){"
                + "try{var j=JSON.parse(q.responseText);if(j&&j.code===0)live(j.data);}catch(e){fail('bootstrap-auth-json');}}});}"
                + "return sd.apply(this,arguments);};setTimeout(function(){fail('bootstrap-auth-timeout');},12000);})();";
    }

    private boolean maybeResolveApiResult(String message) {
        Pending pending = pendingRequest;
        if (pending == null || message == null) {
            return false;
        }
        if (message.startsWith(API_ERROR_PREFIX)) {
            if (message.indexOf("auth-http-401") >= 0 && !pending.bootstrapAttempted) {
                startOfficialBootstrap(pending);
                return true;
            }
            fail(pending, "央视频接口失败：" + message.substring(API_ERROR_PREFIX.length()));
            return true;
        }
        if (!message.startsWith(API_RESULT_PREFIX)) {
            return false;
        }
        try {
            String encoded = message.substring(API_RESULT_PREFIX.length());
            String response = Uri.decode(encoded);
            JSONObject root = new JSONObject(response);
            JSONObject data = root.optJSONObject("data");
            String url = data == null ? "" : data.optString("playurl", "");
            String extended = data == null ? "" : data.optString("extended_param", "");
            if (root.optInt("code", -1) != 0 || url.length() == 0
                    || !url.contains(".m3u8") || !url.contains("ysp.cctv.cn")) {
                Log.w(TAG, "Yangshipin live response code=" + root.optInt("code", -1)
                        + " msg=" + root.optString("msg", "")
                        + " urlLength=" + url.length());
                fail(pending, "央视频接口未返回可用线路");
                return true;
            }
            if (extended.length() > 0 && url.indexOf(extended) < 0) {
                url += extended;
            }
            apiCache.put(pending.cacheKey(),
                    new CachedUrl(url, System.currentTimeMillis()));
            String key = "url_" + pending.cacheKey();
            resolverPrefs.edit()
                    .putString(key, url)
                    .putLong(key + "_at", System.currentTimeMillis())
                    .apply();
            completeApi(pending, url);
        } catch (Exception error) {
            Log.e(TAG, "Unable to parse Yangshipin API response", error);
            fail(pending, "央视频接口数据异常");
        }
        return true;
    }

    private void startOfficialBootstrap(Pending pending) {
        pending.bootstrapAttempted = true;
        pending.apiHtml = null;
        Log.i(TAG, "Starting Yangshipin official auth bootstrap for " + pending.channel.name);
        webView.loadUrl("https://www.yangshipin.cn/tv/home?pid="
                + Uri.encode(pending.channel.yangshipinPid));
    }

    private void completeApi(Pending pending, String url) {
        long now = serverClockOffsetMs == Long.MIN_VALUE
                ? System.currentTimeMillis()
                : System.currentTimeMillis() + serverClockOffsetMs;
        pending.resolvedUrl = url;
        pending.cmgTag = String.valueOf(now);
        pending.cmgInitialUpdateTag = "";
        pending.cmgUpdateTag = "";
        pending.cmgUpdateWarmupCount = 0;
        pending.cmgInitTimeMs = now;
        pending.cmgUpdateBaseTimeMs = now;
        pending.cmgUpdateTrace = "";
        Log.i(TAG, "Resolved Yangshipin " + pending.definition + " API for "
                + pending.channel.name
                + " in native-light mode");
        complete(pending);
    }

    void destroy() {
        clearPending(false);
        if (webView == null) {
            return;
        }
        webView.removeCallbacks(traceKeepAlive);
        webView.stopLoading();
        webView.loadUrl("about:blank");
        ViewGroup parent = (ViewGroup) webView.getParent();
        if (parent != null) {
            parent.removeView(webView);
        }
        webView.destroy();
        webView = null;
    }

    private void maybeResolve(String url) {
        Pending pending = pendingRequest;
        if (pending == null || url == null) {
            return;
        }
        String lower = url.toLowerCase();
        if (!lower.contains(".m3u8") || !lower.contains("ysp.cctv.cn")) {
            return;
        }
        if (url.indexOf("pid=" + pending.channel.yangshipinPid) < 0) {
            return;
        }
        pending.resolvedUrl = url;
        tryComplete(pending);
    }

    private void maybeResolveCmgTag(String message) {
        Pending pending = pendingRequest;
        if (pending == null || message == null || !message.startsWith(TAG_PREFIX)) {
            return;
        }
        String tag = message.substring(TAG_PREFIX.length()).trim();
        if (tag.length() == 0) {
            return;
        }
        String updateTag = "";
        String initialUpdateTag = "";
        int warmupCount = 0;
        long initTimeMs = 0L;
        long warmupBaseTimeMs = 0L;
        String warmupTrace = "";
        String[] parts = tag.split("\\|", -1);
        if (parts.length >= 10) {
            tag = parts[0].trim();
            initialUpdateTag = parts[1].trim();
            updateTag = parts[2].trim();
            warmupCount = parsePositiveInt(parts[3].trim());
            initTimeMs = parsePositiveLong(parts[4].trim());
            warmupBaseTimeMs = parsePositiveLong(parts[5].trim());
            pending.cmgInitResult = parts[6].trim();
            pending.cmgActiveUrl = parts[7].trim();
            pending.cmgLocation = parts[8].trim();
            pending.cmgNativeTrace = parts[9].trim();
            if (parts.length >= 11) {
                warmupTrace = parts[10].trim();
            }
            if (parts.length >= 12) {
                pending.cmgImportTrace = parts[11].trim();
            }
        } else if (parts.length >= 9) {
            tag = parts[0].trim();
            initialUpdateTag = parts[1].trim();
            updateTag = parts[2].trim();
            warmupCount = parsePositiveInt(parts[3].trim());
            initTimeMs = parsePositiveLong(parts[4].trim());
            warmupBaseTimeMs = parsePositiveLong(parts[5].trim());
            pending.cmgInitResult = parts[6].trim();
            pending.cmgActiveUrl = parts[7].trim();
            pending.cmgLocation = parts[8].trim();
            if (parts.length >= 10) {
                warmupTrace = parts[9].trim();
            }
        } else if (parts.length >= 8) {
            tag = parts[0].trim();
            initialUpdateTag = parts[1].trim();
            updateTag = parts[2].trim();
            warmupCount = parsePositiveInt(parts[3].trim());
            initTimeMs = parsePositiveLong(parts[4].trim());
            warmupBaseTimeMs = parsePositiveLong(parts[5].trim());
            pending.cmgInitResult = parts[6].trim();
            pending.cmgActiveUrl = parts[7].trim();
            if (parts.length >= 9) {
                warmupTrace = parts[8].trim();
            }
        } else if (parts.length >= 6) {
            tag = parts[0].trim();
            initialUpdateTag = parts[1].trim();
            updateTag = parts[2].trim();
            warmupCount = parsePositiveInt(parts[3].trim());
            initTimeMs = parsePositiveLong(parts[4].trim());
            warmupBaseTimeMs = parsePositiveLong(parts[5].trim());
            if (parts.length >= 7) {
                warmupTrace = parts[6].trim();
            }
        } else if (parts.length >= 4) {
            tag = parts[0].trim();
            initialUpdateTag = parts[1].trim();
            updateTag = parts[2].trim();
            warmupCount = parsePositiveInt(parts[3].trim());
            if (parts.length >= 5) {
                warmupBaseTimeMs = parsePositiveLong(parts[4].trim());
            }
        } else {
            int separator = tag.indexOf('|');
            if (separator >= 0) {
                String rest = tag.substring(separator + 1).trim();
                tag = tag.substring(0, separator).trim();
                int secondSeparator = rest.indexOf('|');
                if (secondSeparator >= 0) {
                    initialUpdateTag = rest.substring(0, secondSeparator).trim();
                    updateTag = rest.substring(secondSeparator + 1).trim();
                } else {
                    initialUpdateTag = rest;
                    updateTag = rest;
                }
            }
        }
        if (tag.length() == 0 || initialUpdateTag.length() == 0 || updateTag.length() == 0) {
            return;
        }
        pending.cmgTag = tag;
        pending.cmgInitialUpdateTag = initialUpdateTag;
        pending.cmgUpdateTag = updateTag;
        pending.cmgUpdateWarmupCount = warmupCount;
        pending.cmgInitTimeMs = initTimeMs;
        pending.cmgUpdateBaseTimeMs = warmupBaseTimeMs;
        pending.cmgUpdateTrace = warmupTrace;
        Log.i(TAG, "Resolved Yangshipin CMG tag for " + pending.channel.name + ": "
                + tag + " initialTag=" + initialUpdateTag + " updateTag=" + updateTag
                + " warmupCount=" + warmupCount + " initTime=" + initTimeMs
                + " initResult=" + pending.cmgInitResult
                + " activeUrl=" + pending.cmgActiveUrl
                + " location=" + pending.cmgLocation
                + " nativeTrace=" + pending.cmgNativeTrace
                + " importTrace=" + pending.cmgImportTrace
                + " warmupBase=" + warmupBaseTimeMs + " traceLen=" + warmupTrace.length());
        tryComplete(pending);
    }

    private static int parsePositiveInt(String text) {
        try {
            int value = Integer.parseInt(text);
            return Math.max(0, value);
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private static long parsePositiveLong(String text) {
        try {
            long value = Long.parseLong(text);
            return Math.max(0L, value);
        } catch (NumberFormatException error) {
            return 0L;
        }
    }

    private void tryComplete(Pending pending) {
        if (pending.resolvedUrl == null || pending.cmgTag == null
                || pending.cmgInitialUpdateTag == null
                || pending.cmgUpdateTag == null) {
            return;
        }
        complete(pending);
    }

    private void pollPageForVideoUrl() {
        if (pendingRequest == null) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            return;
        }
        webView.evaluateJavascript(
                "(function(){"
                        + "function scan(o,d){"
                        + "if(!o||d>5)return '';"
                        + "if(typeof o==='string')return o.indexOf('.m3u8')>=0?o:'';"
                        + "if(typeof o!=='object')return '';"
                        + "if(Array.isArray(o)){for(var i=0;i<o.length;i++){var r=scan(o[i],d+1);if(r)return r;}return '';}"
                        + "if(o.videoUrl&&String(o.videoUrl).indexOf('.m3u8')>=0)return String(o.videoUrl);"
                        + "for(var k in o){var v=o[k];"
                        + "if(/url|video|src|current/i.test(k)){var r=scan(v,d+1);if(r)return r;}"
                        + "}"
                        + "return '';"
                        + "}"
                        + "var nodes=document.querySelectorAll('*');"
                        + "for(var i=0;i<nodes.length;i++){"
                        + "if(nodes[i].__vue__){var r=scan(nodes[i].__vue__.$data,0);if(r)return r;}"
                        + "}"
                        + "return '';"
                        + "})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        String url = decodeJsString(value);
                        if (url != null && url.length() > 0) {
                            maybeResolve(url);
                        }
                        if (pendingRequest != null) {
                            webView.postDelayed(pollPage, 1000L);
                        }
                    }
                });
    }

    private static String decodeJsString(String value) {
        if (value == null || "null".equals(value)) {
            return "";
        }
        try {
            return new JSONArray("[" + value + "]").optString(0, "");
        } catch (JSONException error) {
            return "";
        }
    }

    private void complete(final Pending pending) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (pendingRequest != pending) {
                    return;
                }
                Log.i(TAG, "Resolved Yangshipin HLS for " + pending.channel.name + ": "
                        + pending.resolvedUrl + " cmgTag=" + pending.cmgTag
                        + " initialTag=" + pending.cmgInitialUpdateTag
                        + " updateTag=" + pending.cmgUpdateTag);
                clearPending(true);
                pending.callback.onResolved(pending.requestId, pending.resolvedUrl,
                        pending.cmgTag, pending.cmgInitialUpdateTag, pending.cmgUpdateTag,
                        pending.cmgUpdateWarmupCount, pending.cmgInitTimeMs,
                        pending.cmgUpdateBaseTimeMs, pending.cmgUpdateTrace,
                        pending.cmgNativeTrace);
            }
        });
    }

    private void fail(final Pending pending, final String reason) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (pendingRequest != pending) {
                    return;
                }
                Log.w(TAG, "Yangshipin resolve failed for " + pending.channel.name + ": " + reason);
                clearPending(true);
                pending.callback.onFailed(pending.requestId, reason);
            }
        });
    }

    private void clearPending(boolean stopPage) {
        WebView currentWebView = webView;
        if (currentWebView != null) {
            currentWebView.removeCallbacks(timeout);
            currentWebView.removeCallbacks(pollPage);
        }
        pendingRequest = null;
        if (currentWebView == null) {
            return;
        }
        if (stopPage) {
            if (keepTracePage) {
                currentWebView.setVisibility(View.VISIBLE);
                currentWebView.onResume();
                startTraceKeepAlive();
                return;
            }
            currentWebView.removeCallbacks(traceKeepAlive);
            pauseWebMedia();
            currentWebView.stopLoading();
            currentWebView.loadDataWithBaseURL("about:blank", "", "text/html", "UTF-8", null);
            currentWebView.setVisibility(View.GONE);
            currentWebView.onPause();
        } else {
            currentWebView.removeCallbacks(traceKeepAlive);
        }
    }

    private void startTraceKeepAlive() {
        if (!keepTracePage || webView == null) {
            return;
        }
        webView.removeCallbacks(traceKeepAlive);
        webView.postDelayed(traceKeepAlive, 1000L);
    }

    private void keepTracePlaybackAlive() {
        if (!keepTracePage || webView == null
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            return;
        }
        try {
            webView.onResume();
            webView.evaluateJavascript(
                    "(function(){try{"
                            + "self.__ntvKeepAliveLogCount=self.__ntvKeepAliveLogCount||0;"
                            + "var nodes=document.querySelectorAll('video');"
                            + "var info=[];"
                            + "for(var i=0;i<nodes.length;i++){"
                            + "var v=nodes[i];"
                            + "try{"
                            + "v.muted=true;v.volume=0;v.autoplay=true;"
                            + "v.setAttribute('muted','');v.setAttribute('playsinline','');"
                            + "var p=v.play&&v.play();"
                            + "if(p&&p.catch){p.catch(function(){});}"
                            + "info.push(Math.round((v.currentTime||0)*1000)+','+(v.readyState||0)+','+(v.paused?1:0));"
                            + "}catch(e){}"
                            + "}"
                            + "if(self.__ntvKeepAliveLogCount<32){"
                            + "self.__ntvKeepAliveLogCount++;"
                            + "console.log('__NTV_CMG_KEEPALIVE__'+info.join(';'));"
                            + "}"
                            + "}catch(e){try{console.log('__NTV_CMG_KEEPALIVE_ERROR__'+e);}catch(x){}}"
                            + "})()",
                    null);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to keep Yangshipin trace playback alive", error);
        }
        webView.postDelayed(traceKeepAlive, 2500L);
    }

    private void pauseWebMedia() {
        if (webView == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            return;
        }
        try {
            webView.evaluateJavascript(
                    "(function(){try{"
                            + "var nodes=document.querySelectorAll('video,audio');"
                            + "for(var i=0;i<nodes.length;i++){"
                            + "try{nodes[i].pause();nodes[i].removeAttribute('src');nodes[i].load();}catch(e){}"
                            + "}"
                            + "if(window.hls){try{window.hls.destroy();}catch(e){}}"
                            + "}catch(e){}})()",
                    null);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to pause Yangshipin WebView media", error);
        }
    }

    private static final class Pending {
        final int requestId;
        final Channel channel;
        final String definition;
        final Callback callback;
        String resolvedUrl;
        String cmgTag;
        String cmgInitialUpdateTag;
        String cmgUpdateTag;
        int cmgUpdateWarmupCount;
        long cmgInitTimeMs;
        long cmgUpdateBaseTimeMs;
        String cmgInitResult = "";
        String cmgActiveUrl = "";
        String cmgLocation = "";
        String cmgNativeTrace = "";
        String cmgImportTrace = "";
        String cmgUpdateTrace = "";
        String apiHtml;
        YangshipinApiPayload apiPayload;
        boolean clockSynced;
        String bootstrapScript;
        boolean bootstrapAttempted;

        Pending(int requestId, Channel channel, String definition, Callback callback) {
            this.requestId = requestId;
            this.channel = channel;
            this.definition = "shd".equals(definition) || "hd".equals(definition)
                    || "sd".equals(definition)
                    ? definition : "fhd";
            this.callback = callback;
        }

        String cacheKey() {
            return channel.yangshipinPid + "_" + definition;
        }
    }

    private static final class CachedUrl {
        final String url;
        final long createdAt;

        CachedUrl(String url, long createdAt) {
            this.url = url;
            this.createdAt = createdAt;
        }
    }

    private static WebResourceResponse patchHlsCmgScript(String url) {
        HttpURLConnection connection = null;
        boolean responseConsumed = false;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", DESKTOP_USER_AGENT);
            String body = new String(readFully(connection.getInputStream()), "UTF-8");
            responseConsumed = true;
            String patched = patchHlsCmgSource(body);
            return new WebResourceResponse("application/javascript", "UTF-8",
                    new ByteArrayInputStream(patched.getBytes("UTF-8")));
        } catch (IOException error) {
            Log.w(TAG, "Unable to patch hls.cmg.js", error);
            return null;
        } finally {
            if (connection != null && !responseConsumed) {
                connection.disconnect();
            }
        }
    }

    private static WebResourceResponse patchOfficialPage(String url, String bootstrapScript) {
        HttpURLConnection connection = null;
        boolean responseConsumed = false;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", DESKTOP_USER_AGENT);
            String body = new String(readFully(connection.getInputStream()), "UTF-8");
            responseConsumed = true;
            int head = body.toLowerCase().indexOf("<head>");
            String injection = "<script>" + bootstrapScript + "</script>";
            String patched = head >= 0
                    ? body.substring(0, head + 6) + injection + body.substring(head + 6)
                    : injection + body;
            return new WebResourceResponse("text/html", "UTF-8",
                    new ByteArrayInputStream(patched.getBytes("UTF-8")));
        } catch (IOException error) {
            Log.w(TAG, "Unable to patch Yangshipin bootstrap page", error);
            return null;
        } finally {
            if (connection != null && !responseConsumed) {
                connection.disconnect();
            }
        }
    }

    private static String patchHlsCmgSource(String body) {
        String marker = ";var fI=function";
        int index = body.indexOf(marker);
        if (index < 0) {
            return IMPORT_HOOK + body + HLS_HOOK;
        }
        return IMPORT_HOOK + body.substring(0, index) + HLS_HOOK + body.substring(index);
    }

    private static byte[] readFully(java.io.InputStream input) throws IOException {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(4096);
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }
}
