package xiao.bu.tv;

import android.app.Activity;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.security.MessageDigest;

/** CJS site metadata and native-transform extensions over the shared Ku9 execution contract. */
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
    private final Ku9JsContract contract;
    private final Ku9ScriptEngine engine;

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

    CjsSiteResolver(Activity activity) {
        this.activity = activity;
        contract = new Ku9JsContract(activity);
        engine = new Ku9ScriptEngine(activity);
    }

    void resolve(final int requestId, final String channelName, final String pageUrl, final String quality,
            final String sourceUrl,
            final Callback callback) {
        cancel();
        final int requestGeneration = generation;
        new Thread(new Runnable() {
            @Override
            public void run() {
                long loadStarted = android.os.SystemClock.elapsedRealtime();
                try {
                    final CjsPluginRuntime.SitePlugin site =
                            CjsPluginRuntime.siteForUrl(pageUrl);
                    if (site == null) {
                        throw new IOException("没有匹配该网页的在线站点插件");
                    }
                    if (requestGeneration != generation) return;
                    final Pending request = new Pending(requestId, requestGeneration,
                            channelName, pageUrl, quality, sourceUrl, site, callback);
                    Log.i(TAG, "CJS local load site=" + site.id + " request=" + requestId
                            + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - loadStarted));
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (requestGeneration == generation && !activity.isFinishing()) {
                                start(request);
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
        String key = request.cacheKey;
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
        if (pending != work || generation != work.generation) return;
        new Thread(() -> {
            try {
                if (work.javascript == null) work.javascript = buildJavascript(work);
                activity.runOnUiThread(() -> {
                    if (pending == work && generation == work.generation)
                        engine.execute(work.javascript, new Bridge(work), work.browser);
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    if (pending == work && generation == work.generation)
                        fail(work, "站点脚本准备失败: " + safeMessage(error));
                });
            }
        }, "cjs-prepare").start();
    }

    private String buildJavascript(Pending request) {
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
        return contract.build(request.site.script, item);
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
            String referer = Ku9JsContract.resultHeader(value, "referer", "Referer");
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
                String key = request.cacheKey;
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
        engine.cancel();
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

    private final class Bridge extends Ku9Host {
        private final Pending request;
        private final Ku9SiteCache cache;
        Bridge(Pending request) {
            this.request = request;
            cache = new Ku9SiteCache(activity, request.site.id);
        }
        @Override protected boolean isRequestCancelled() {
            return generation != request.generation || pending != request || Thread.currentThread().isInterrupted();
        }
        @Override @android.webkit.JavascriptInterface public String getCache(String key) { return cache.get(key); }
        @Override @android.webkit.JavascriptInterface public void setCache(String key, String value, double ttlMs) {
            cache.put(key, value, ttlMs);
        }
        @Override protected void onComplete(final String json) {
            activity.runOnUiThread(() -> {
                if (pending == request && generation == request.generation)
                    CjsSiteResolver.this.complete(request, json);
            });
        }
        @Override protected void onFailure(final String reason) {
            activity.runOnUiThread(() -> {
                if (pending == request && generation == request.generation)
                    CjsSiteResolver.this.fail(request, "站点插件执行失败: " + reason);
            });
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
        final String cacheKey;
        String javascript;
        final boolean browser;
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
            this.browser = Ku9EnginePolicy.usesWebView(site.script);
            this.callback = callback;
            // Prepared on the loader thread, once per channel selection. Live
            // playlist refreshes reuse the source and never hash it on the UI thread.
            this.cacheKey = cacheKey(this);
            // Build only on a cache miss; live playlist refreshes reuse the result.
        }
    }
}
