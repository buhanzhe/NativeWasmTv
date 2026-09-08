package xiao.bu.tv;

import android.app.Activity;
import android.text.TextUtils;
import android.util.Log;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Locale;

/** Executes CJS scripts in bounded QuickJS runtimes on background threads. */
final class CjsSiteResolver {
    interface Callback {
        void onResolved(int requestId, Result result);
        void onFailed(int requestId, String reason);
    }

    static final class Result {
        final String url;
        final String referer;
        final boolean directDataSource;
        final String transformer;
        final String[] transformerArgs;
        final String[] mediaHosts;

        Result(String url, String referer, String transformer,
                String[] transformerArgs, String[] mediaHosts) {
            this.url = url;
            this.directDataSource = Ku9JsContract.isDirectDataSource(url);
            this.referer = referer;
            this.transformer = transformer;
            this.transformerArgs = transformerArgs;
            this.mediaHosts = mediaHosts;
        }
    }

    private static final String TAG = "CjsSiteResolver";
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    private final Activity activity;
    private volatile Pending pending;
    private volatile int generation;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Ku9PlaylistServer playlistServer;
    private final Runnable refreshPlaylist = new Runnable() {
        @Override public void run() {
            Pending request = pending;
            if (request != null && request.generation == generation) execute(request);
        }
    };

    CjsSiteResolver(Activity activity, FrameLayout root) {
        this.activity = activity;
    }

    void resolve(final int requestId, final String channelName, final String pageUrl, final String quality,
            final String sourceUrl,
            final Callback callback) {
        cancel();
        final int requestGeneration = generation;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final CjsPluginRuntime.SitePlugin site =
                            CjsPluginRuntime.siteForUrl(pageUrl);
                    if (site == null) {
                        throw new IOException("没有匹配该网页的在线站点插件");
                    }
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (requestGeneration == generation && !activity.isFinishing()) {
                                start(new Pending(requestId, requestGeneration,
                                        channelName, pageUrl, quality, sourceUrl, site, callback));
                            }
                        }
                    });
                } catch (final Exception error) {
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (requestGeneration == generation) {
                                callback.onFailed(requestId, safeMessage(error));
                            }
                        }
                    });
                }
            }
        }, "cjs-site-load").start();
    }

    private void start(Pending request) {
        android.content.SharedPreferences cache = activity.getSharedPreferences("cjs-result-cache", 0);
        String key = cacheKey(request);
        long now = System.currentTimeMillis();
        long expires = cache.getLong(key + ":until", 0L);
        if (expires > now && expires - now <= 600000L) {
            String saved = cache.getString(key, "");
            if (saved.length() > 0) {
                pending = request;
                Log.i(TAG, "Using site URL cache id=" + request.site.id + " quality=" + request.quality);
                complete(request, saved);
                return;
            }
        }
        pending = request;
        execute(request);
    }

    private void execute(final Pending work) {
        new Thread(new Runnable() {
            @Override public void run() {
                Bridge host = new Bridge(work);
                long started = android.os.SystemClock.elapsedRealtime();
                try {
                    Log.i(TAG, "QuickJS starting site=" + work.site.id);
                    NativeQuickJs.execute(buildJavascript(work), host);
                    if (!host.terminal && !host.isCancelled()) host.fail("脚本没有返回播放结果");
                } catch (Throwable error) {
                    if (!host.isCancelled()) host.fail("QuickJS: " + safeMessage(error));
                } finally {
                    Log.i(TAG, "QuickJS finished site=" + work.site.id + " elapsedMs="
                            + (android.os.SystemClock.elapsedRealtime() - started));
                }
            }
        }, "cjs-quickjs").start();
    }

    private static String buildJavascript(Pending request) {
        JSONObject item = new JSONObject();
        try {
            item.put("url", "ku9".equals(request.site.jsApi) && !TextUtils.isEmpty(request.sourceUrl) ? request.sourceUrl : request.pageUrl);
            item.put("pageUrl", request.pageUrl);
            item.put("quality", CjsPluginRuntime.quality(request.site.id, request.quality, null));
            item.put("name", request.channelName == null ? "" : request.channelName);
            CjsSource source = CjsSource.parse(request.sourceUrl);
            item.put("source", request.sourceUrl);
            item.put("params", source == null ? new JSONObject() : new JSONObject(source.parameters));
        } catch (Exception ignored) {
        }
        return buildJavascript(request.site.script, item);
    }

    static String buildJavascript(String script, JSONObject item) {
        return "(function(){'use strict';"
                + Ku9JsContract.bootstrap("NtvCjsBridge")
                + "window.cjs=window.ku9;"
                + "function done(v){NtvCjsBridge.complete(JSON.stringify(v==null?{}:v));}"
                + "function fail(e){NtvCjsBridge.fail(String(e)+(e&&e.stack?'\\n'+e.stack:''));}"
                + "try{var pluginMain=(function(){" + script + "\n"
                + "return typeof main==='function'?main:null;})();"
                + "if(typeof pluginMain!=='function')throw new Error('站点插件没有 main(item) 入口');"
                + "var r=pluginMain(" + item.toString() + ");"
                + "if(r&&typeof r.then==='function'){r.then(done,fail);}else{done(r);}}"
                + "catch(e){fail(e);}})();";
    }

    private void complete(final Pending request, String json) {
        try {
            Ku9JsContract.Output output = Ku9JsContract.parse(json);
            JSONObject value = output.fields;
            String url = output.url;
            JSONArray streams = value.optJSONArray("streams");
            if (streams != null) {
                if (streams.length() < 1 || streams.length() > 3) throw new IOException("站点最多返回三档清晰度");
                java.util.HashSet<String> tiers = new java.util.HashSet<String>();
                String selected = null;
                for (int i = 0; i < streams.length(); i++) {
                    JSONObject stream = streams.getJSONObject(i);
                    String tier = stream.getString("quality"), candidate = stream.getString("url");
                    if ((!"high".equals(tier) && !"medium".equals(tier) && !"low".equals(tier))
                            || !tiers.add(tier) || !isOnlineMedia(candidate)) throw new IOException("站点清晰度列表无效");
                    if (i == 0) url = candidate;
                    if (tier.equals(request.quality)) selected = candidate;
                }
                if (selected != null) url = selected;
            }
            String referer = value.optString("referer", request.pageUrl).trim();
            String transformer = value.optString("transformer", "").trim();
            String[] transformerArgs = stringArray(value.optJSONArray("transformerArgs"));
            String[] mediaHosts = stringArray(value.optJSONArray("mediaHosts"));
            if (output.playlist.length() == 0 && !isOnlineMedia(url)) {
                throw new IOException("站点插件没有返回在线播放地址");
            }
            if (!isOnline(referer)) {
                referer = request.pageUrl;
            }
            if (transformer.length() > 0 && (!transformer.equals(request.site.transformer)
                    || transformerArgs.length == 0 || mediaHosts.length == 0)) {
                throw new IOException("站点插件解密参数不匹配");
            }
            if (transformer.length() > 0 && !"gxtv.so".equals(request.site.nativeModule)) {
                throw new IOException("当前宿主不支持该站点原生接口");
            }
            if (output.playlist.length() > 0) {
                if (playlistServer == null) {
                    playlistServer = new Ku9PlaylistServer();
                    playlistServer.start();
                }
                playlistServer.update(output.playlist);
                handler.removeCallbacks(refreshPlaylist);
                // Finite VOD lists need no refresh; live lists use Ku9's existing cadence.
                if (!output.playlist.contains("#EXT-X-ENDLIST"))
                    handler.postDelayed(refreshPlaylist, Ku9ScriptResolver.playlistRefreshDelay(output.playlist));
                if (!request.initialCompleted) {
                    request.initialCompleted = true;
                    request.callback.onResolved(request.requestId, new Result(playlistServer.url(),
                            referer, transformer, transformerArgs, mediaHosts));
                }
                return;
            }
            long ttl = Math.min(600L, Math.max(0L, value.optLong("ttlSec", 0)));
            if (ttl > 0) {
                android.content.SharedPreferences cache = activity.getSharedPreferences("cjs-result-cache", 0);
                android.content.SharedPreferences.Editor edit = cache.edit();
                if (cache.getAll().size() >= 128) edit.clear();
                // Cache hits don't refresh their own expiry.
                String key = cacheKey(request);
                if (cache.getLong(key + ":until", 0) <= System.currentTimeMillis()) {
                    edit.putString(key, json).putLong(key + ":until", System.currentTimeMillis() + ttl * 1000).apply();
                }
            }
            final Result result = new Result(url, referer, transformer,
                    transformerArgs, mediaHosts);
            clearPending();
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (generation == request.generation) {
                        request.callback.onResolved(request.requestId, result);
                    }
                }
            });
        } catch (final Exception error) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    fail(request, "站点插件结果无效: " + safeMessage(error));
                }
            });
        }
    }

    private void fail(final Pending request, final String reason) {
        if (request == null || pending != request) {
            return;
        }
        if (request.initialCompleted) {
            Log.w(TAG, reason + "; retaining last live playlist");
            handler.removeCallbacks(refreshPlaylist);
            handler.postDelayed(refreshPlaylist, 5000L);
            return;
        }
        clearPending();
        closePlaylistServer();
        request.callback.onFailed(request.requestId, reason);
    }

    private void clearPending() {
        handler.removeCallbacks(refreshPlaylist);
        pending = null;
    }

    private void closePlaylistServer() {
        if (playlistServer != null) { playlistServer.close(); playlistServer = null; }
    }

    void cancel() { generation++; clearPending(); closePlaylistServer(); }

    void destroy() { cancel(); }

    private static String cacheKey(Pending request) {
        // Site script digest prevents a new plugin version reusing old resolver output.
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((request.site.id + "\n" + request.site.script
                    + "\n" + request.pageUrl + "\n" + request.quality + "\n" + request.sourceUrl).getBytes("UTF-8"));
            return android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private static boolean isOnlineMedia(String url) {
        return isOnline(url) || (url != null && (url.startsWith("rtsp://") || url.startsWith("rtmp://")));
    }

    private static boolean isOnline(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private static String[] stringArray(JSONArray values) {
        if (values == null) {
            return new String[0];
        }
        String[] result = new String[values.length()];
        for (int index = 0; index < values.length(); index++) {
            result[index] = values.optString(index, "").trim();
        }
        return result;
    }

    private static String safeMessage(Throwable error) {
        String value = error == null ? null : error.getMessage();
        return TextUtils.isEmpty(value) ? "未知错误" : value;
    }

    private final class Bridge implements NativeQuickJs.Host {
        private final Pending request;
        private boolean terminal;
        Bridge(Pending request) { this.request = request; }

        @Override public boolean isCancelled() {
            return generation != request.generation || Thread.currentThread().isInterrupted();
        }

        @Override public String invoke(int operation, String[] args) throws Exception {
            if (isCancelled()) throw new IOException("CJS cancelled");
            switch (operation) {
                case 0: return get(args[0], args[1]);
                case 1: return post(args[0], args[1], args[2]);
                case 2: return request(args[0], args[1], args[2], args[3], Boolean.parseBoolean(args[4]));
                case 3: return md5(args[0]);
                case 4: log(args[0]); return null;
                case 5: complete(args[0]); return null;
                case 6: fail(args[0]); return null;
                case 7: return scriptCache().get(args[0]);
                case 8: scriptCache().put(args[0], args[1], Double.parseDouble(args[2])); return null;
                default: throw new IOException("Unknown CJS host operation");
            }
        }

        private Ku9SiteCache scriptCache() {
            return new Ku9SiteCache(activity, request.site.id);
        }

        public String get(String url, String headersJson) {
            if (!isOnline(url)) {
                return "";
            }
            try {
                return Ku9HttpClient.getText(url,
                        Ku9HttpClient.parseHeaders(headersJson), MAX_RESPONSE_BYTES);
            } catch (IOException error) {
                Log.w(TAG, "CJS GET failed " + url, error);
                return "";
            }
        }

        public String post(String url, String body, String headersJson) {
            return isOnline(url) ? Ku9HttpClient.postText(
                    url, body, headersJson, MAX_RESPONSE_BYTES) : "";
        }

        public String request(String url, String method, String headersJson,
                String body, boolean followRedirects) {
            if (!isOnline(url)) {
                return "{\"code\":0,\"error\":\"online URL required\"}";
            }
            long startedAt = android.os.SystemClock.elapsedRealtime();
            String result = Ku9HttpClient.requestJson(url, method, headersJson, body,
                    followRedirects, MAX_RESPONSE_BYTES);
            try {
                JSONObject response = new JSONObject(result);
                if (response.optInt("code") == 0) Log.w(TAG, "Site HTTP failure: " + response.optString("error"));
            } catch (JSONException ignored) { }
            Log.i(TAG, "Site request completed elapsedMs="
                    + (android.os.SystemClock.elapsedRealtime() - startedAt)
                    + " url=" + url);
            return result;
        }

        public String md5(String value) {
            try {
                byte[] bytes = MessageDigest.getInstance("MD5")
                        .digest(String.valueOf(value).getBytes("UTF-8"));
                StringBuilder result = new StringBuilder(bytes.length * 2);
                for (byte item : bytes) {
                    result.append(String.format(Locale.US, "%02x", item & 0xff));
                }
                return result.toString();
            } catch (Exception error) {
                return "";
            }
        }

        public void log(String value) {
            Log.i(TAG, value);
        }

        public void complete(final String json) {
            if (!terminal && !isCancelled()) {
                terminal = true;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (pending == request && generation == request.generation)
                            CjsSiteResolver.this.complete(request, json);
                    }
                });
            }
        }

        public void fail(final String reason) {
            if (!terminal && !isCancelled()) {
                terminal = true;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        CjsSiteResolver.this.fail(request,
                                "站点插件执行失败: " + reason);
                    }
                });
            }
        }
    }

    private static final class Pending {
        final int requestId;
        final int generation;
        final String channelName;
        final String pageUrl;
        final String quality;
        final String sourceUrl;
        final CjsPluginRuntime.SitePlugin site;
        final Callback callback;
        boolean initialCompleted;

        Pending(int requestId, int generation, String channelName, String pageUrl, String quality,
                String sourceUrl,
                CjsPluginRuntime.SitePlugin site, Callback callback) {
            this.requestId = requestId;
            this.generation = generation;
            this.channelName = channelName;
            this.pageUrl = pageUrl;
            this.quality = quality;
            this.sourceUrl = sourceUrl;
            this.site = site;
            this.callback = callback;
        }
    }
}
