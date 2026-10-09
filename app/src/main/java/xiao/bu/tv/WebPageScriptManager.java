package xiao.bu.tv;

import android.os.Build;
import android.util.Log;
import android.webkit.WebView;

import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Document policy plus lightweight userscripts. GM_* APIs are intentionally out of scope. */
final class WebPageScriptManager {
    private static final String TAG = "WebPageScript";
    private final WebView view;
    private final List<ScriptHandler> handlers = new ArrayList<ScriptHandler>();
    private List<String> userScripts = Collections.emptyList();
    // One configuration only, no Activity/WebView references. Each document still
    // gets its own handler and JavaScript realm; only immutable Java text is shared.
    private static String sharedSources;
    private static List<String> sharedUserScripts = Collections.emptyList();
    private String script = "";
    private boolean documentStart;
    private boolean configured, lastAdBlock, lastWebRtc, lastEnabled;
    private String lastSources = "";

    WebPageScriptManager(WebView view) { this.view = view; }

    void update(boolean adBlock, boolean webRtc, boolean userScriptEnabled, String sourcesJson) {
        String sources = sourcesJson == null ? "" : sourcesJson;
        if (configured && lastAdBlock == adBlock && lastWebRtc == webRtc
                && lastEnabled == userScriptEnabled && lastSources.equals(sources)) return;
        boolean scriptsChanged = !configured || lastEnabled != userScriptEnabled
                || !lastSources.equals(sources);
        configured = true;
        lastAdBlock = adBlock;
        lastWebRtc = webRtc;
        lastEnabled = userScriptEnabled;
        lastSources = sources;
        if (scriptsChanged) userScripts = userScriptEnabled
                ? compiledUserScripts(sources) : Collections.<String>emptyList();
        StringBuilder body = new StringBuilder();
        String compatibility = LegacyWebCompatibility.enabled()
                ? "window.__ntvNativeTvCompatibility=true;\n" + LegacyWebCompatibility.script(view.getContext()) : "";
        body.append(WebMediaSession.script(view.getContext()));
        body.append(systemMouseSelectionScript());
        if (!webRtc) body.append(webRtcBlockScript());
        if (adBlock) body.append(WebAdBlocker.cosmeticScript());
        String next = body.length() == 0 ? "" : "(function(){var k=" + body.toString().hashCode()
                + ";if(window.__ntvPagePolicy===k)return;window.__ntvPagePolicy=k;try{"
                + body + "}catch(e){console.warn('nTv page policy: '+e);}})();";
        // KitKat can evaluate onPageStarted in the departing document. Its policy
        // marker must not suppress a site shim after the URL changes in that realm.
        script = compatibility + next;
        installHandlers();
    }

    private static synchronized List<String> compiledUserScripts(String sources) {
        if (sources.equals(sharedSources)) return sharedUserScripts;
        List<String> compiled = new ArrayList<>();
        if (sources.trim().length() > 0) {
            try {
                JSONArray entries = new JSONArray(sources);
                for (int index = 0; index < entries.length(); index++) {
                    JSONObject item = entries.optJSONObject(index);
                    if (item == null || !item.optBoolean("enabled", true)) continue;
                    String source = item.optString("source", "");
                    if (source.trim().length() == 0) continue;
                    compiled.add(userscript(source,
                            item.optString("name", "脚本 " + (index + 1))));
                }
            } catch (JSONException error) {
                Log.w(TAG, "Unable to parse userscript list", error);
            }
        }
        sharedSources = sources;
        sharedUserScripts = Collections.unmodifiableList(compiled);
        return sharedUserScripts;
    }

    static synchronized void trimMemory() {
        sharedSources = null;
        sharedUserScripts = Collections.emptyList();
    }

    private void installHandlers() {
        dispose();
        if (Build.VERSION.SDK_INT >= 19) {
            try {
                if (!script.isEmpty()
                        && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    handlers.add(WebViewCompat.addDocumentStartJavaScript(view, script,
                            Collections.singleton("*")));
                    // Separate compilation units: one malformed imported script
                    // must not disable WebRTC/ad policies or every other script.
                    for (String userScript : userScripts) {
                        handlers.add(WebViewCompat.addDocumentStartJavaScript(view, userScript,
                                Collections.singleton("*")));
                    }
                    documentStart = true;
                }
            } catch (RuntimeException | LinkageError error) {
                dispose();
                Log.w(TAG, "Document-start script unavailable", error);
            }
        }
    }

    boolean hasDocumentStartScript() { return documentStart; }

    /** Separate units preserve imported-script syntax error isolation on old engines. */
    String legacyDocumentPrelude() {
        StringBuilder html = new StringBuilder();
        appendInline(html, script);
        for (String value : userScripts) appendInline(html, value);
        return html.toString();
    }
    private static void appendInline(StringBuilder html, String value) {
        if (value.isEmpty()) return;
        String literal = JSONObject.quote(value).replace("<", "\\u003c")
                .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        html.append("<script>(0,eval)(").append(literal).append(");</script>");
    }

    void applyToCurrentDocument() {
        if (script.isEmpty()) return;
        // A KitKat callback can execute in the departing about:blank document.
        // core-js captures that document's MutationObserver for Promise jobs;
        // reusing it after navigation leaves all async module/site work pending.
        String guard=LegacyWebCompatibility.enabled()
                ? "if(!/^https?:$/.test(location.protocol)||(!window.__ntvTvCompatibility&&document.readyState==='loading'))return;" : "";
        evaluate("(function(){"+guard+script+"})();");
        for (String userScript : userScripts) evaluate("(function(){"+guard+userScript+"})();");
    }

    private void evaluate(String value) {
        if (Build.VERSION.SDK_INT >= 19) view.evaluateJavascript(value, null);
        else view.loadUrl("javascript:" + value);
    }

    void dispose() {
        for (ScriptHandler handler : handlers) {
            try { handler.remove(); } catch (RuntimeException | LinkageError ignored) { }
        }
        handlers.clear();
        documentStart = false;
    }

    void release() {
        dispose();
        configured = false;
        script = lastSources = "";
        userScripts = Collections.emptyList();
    }

    private static String webRtcBlockScript() {
        return "var deny=function(){throw new DOMException('WebRTC is disabled','NotAllowedError')};"
                + "['RTCPeerConnection','webkitRTCPeerConnection','mozRTCPeerConnection'].forEach(function(n){"
                + "try{Object.defineProperty(window,n,{configurable:true,get:function(){return deny}})}catch(e){try{window[n]=deny}catch(x){}}});"
                + "try{if(navigator.mediaDevices)navigator.mediaDevices.getUserMedia=function(){return Promise.reject(new DOMException('WebRTC is disabled','NotAllowedError'))}}catch(e){}"
                + "try{navigator.getUserMedia=navigator.webkitGetUserMedia=navigator.mozGetUserMedia=deny}catch(e){};";
    }

    /** Called only by native physical-mouse events; remote flymouse input stays unchanged. */
    private static String systemMouseSelectionScript() {
        return "if(!window.__ntvMouseSelection){window.__ntvMouseSelection=(function(){"
                + "var anchor=null,active=false;"
                + "function point(nx,ny){var x=nx*innerWidth,y=ny*innerHeight,r=null;"
                + "try{if(document.caretRangeFromPoint)r=document.caretRangeFromPoint(x,y);"
                + "else if(document.caretPositionFromPoint){var p=document.caretPositionFromPoint(x,y);"
                + "if(p){r=document.createRange();r.setStart(p.offsetNode,p.offset);r.collapse(true)}}}catch(e){}return r}"
                + "function blocked(nx,ny){var e=document.elementFromPoint(nx*innerWidth,ny*innerHeight);"
                + "for(var i=0;e&&i++<8;e=e.parentElement){var t=(e.tagName||'').toLowerCase();"
                + "if(t==='input'||t==='textarea'||t==='select'||t==='button'||t==='video'||t==='canvas'"
                + "||e.isContentEditable)return true}return false}"
                + "return{start:function(nx,ny){anchor=null;active=false;if(blocked(nx,ny))return false;"
                + "var r=point(nx,ny);if(!r)return false;anchor={n:r.startContainer,o:r.startOffset};return true},"
                + "move:function(nx,ny){if(!anchor)return false;var r=point(nx,ny),s=window.getSelection&&window.getSelection();"
                + "if(!r||!s)return false;try{var a=document.createRange();a.setStart(anchor.n,anchor.o);a.collapse(true);"
                + "s.removeAllRanges();s.addRange(a);if(s.extend)s.extend(r.startContainer,r.startOffset);"
                + "else{a.setEnd(r.startContainer,r.startOffset);s.removeAllRanges();s.addRange(a)}active=true;return true}catch(e){return false}},"
                + "end:function(){anchor=null;var was=active;active=false;return was}}})()};";
    }

    private static String userscript(String source, String name) {
        List<String> matches = new ArrayList<String>();
        List<String> includes = new ArrayList<String>();
        List<String> excludes = new ArrayList<String>();
        List<String> excludeMatches = new ArrayList<String>();
        boolean metadata = false;
        boolean runAtEnd = false;
        StringBuilder code = new StringBuilder();
        for (String raw : source.replace("\r", "").split("\n", -1)) {
            String line = raw.trim();
            if (line.equals("// ==UserScript==")) { metadata = true; continue; }
            if (line.equals("// ==/UserScript==")) { metadata = false; continue; }
            if (metadata) {
                String value = directive(line, "@match");
                if (value.length() > 0) matches.add(value);
                value = directive(line, "@include");
                if (value.length() > 0) includes.add(value);
                value = directive(line, "@exclude-match");
                if (value.length() > 0) excludeMatches.add(value);
                value = directive(line, "@exclude");
                if (value.length() > 0) excludes.add(value);
                value = directive(line, "@run-at");
                if ("document-end".equals(value) || "document-idle".equals(value)) runAtEnd = true;
            } else code.append(raw).append('\n');
        }
        String matchJson = new JSONArray(matches).toString();
        String excludeJson = new JSONArray(excludes).toString();
        String key = JSONObject.quote(scriptKey(source));
        String execute = "function r(){var ran=window.__ntvUserScripts||(window.__ntvUserScripts={});"
                + "if(ran[" + key + "])return;ran[" + key + "]=true;try{\n" + code
                + "}catch(e){console.warn('nTv userscript '+" + JSONObject.quote(name)
                + "+': '+e)}};";
        if (runAtEnd) execute += "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',r,{once:true});else r();";
        else execute += "r();";
        return "(function(){var u=location.href;"
                + "function glob(p,v){var s=p.replace(/[.+?^${}()|[\\]\\\\]/g,'\\\\$&').replace(/\\*/g,'.*');return new RegExp('^'+s+'$').test(v)}"
                + "function g(p){return p==='<all_urls>'?/^(https?|file|ftp):/.test(u):glob(p,u)}"
                + "function match(p){if(p==='<all_urls>')return /^(https?|file|ftp):/.test(u);"
                + "var a=/^(\\*|https?|file|ftp):\\/\\/([^/]*)(\\/.*)$/.exec(p);if(!a)return false;"
                + "var scheme=location.protocol.slice(0,-1),host=location.hostname.toLowerCase(),want=a[2].toLowerCase();"
                + "if(a[1]==='*'?scheme!=='http'&&scheme!=='https':scheme!==a[1])return false;"
                + "if(want!=='*'&&host!==want){if(want.slice(0,2)!=='*.')return false;var base=want.slice(2);"
                + "if(host!==base&&host.slice(-(base.length+1))!=='.'+base)return false}"
                + "return glob(a[3],location.pathname+location.search)}"
                + "var m=" + matchJson + ",i=" + new JSONArray(includes) + ",x=" + excludeJson
                + ",e=" + new JSONArray(excludeMatches) + ";"
                + "if(x.some(g)||e.some(match)||((m.length||i.length)&&!m.some(match)&&!i.some(g)))return;"
                + execute + "})();";
    }

    private static String directive(String line, String name) {
        String prefix = "// " + name;
        if (!line.startsWith(prefix)) return "";
        if (line.length() > prefix.length()
                && !Character.isWhitespace(line.charAt(prefix.length()))) return "";
        String value = line.substring(prefix.length()).trim();
        return value;
    }

    private static String scriptKey(String source) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes("UTF-8"));
            StringBuilder key = new StringBuilder(64);
            for (byte b : digest) {
                key.append(Character.forDigit((b & 255) >>> 4, 16));
                key.append(Character.forDigit(b & 15, 16));
            }
            return key.toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
