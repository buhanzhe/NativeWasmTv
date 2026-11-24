package xiao.bu.tv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.net.Uri;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import okhttp3.OkHttpClient;
import org.json.JSONObject;
import xiao.bu.tv.jar.ChannelPlugin;

/** Exercise packaged JARs against the ntv-plugin TVBox fixture without taking over the display. */
public final class TvBoxProtocolInstrumentation extends Instrumentation {
    private String origin;
    private String pythonVersion, tlsUrl, tlsCa;
    private int rounds, pauseSeconds;
    private boolean repairCache, stayOnTvBox;
    private String auditUrl;
    private int auditIndex;
    private String auditTitle;
    private boolean auditProbe, auditPreferences, auditConfigDb, clearConfigDb;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        origin = args.getString("origin", "http://127.0.0.1:39635");
        pythonVersion = args.getString("pythonVersion", "");
        tlsUrl = args.getString("tlsUrl", "");
        tlsCa = args.getString("tlsCa", "");
        rounds = Math.max(1, Math.min(120, Integer.parseInt(args.getString("loops", "1"))));
        pauseSeconds = Math.max(0, Math.min(60, Integer.parseInt(args.getString("pause", "0"))));
        repairCache = "1".equals(args.getString("repairCache"));
        stayOnTvBox = "1".equals(args.getString("stayOnTvBox"));
        auditUrl = args.getString("auditUrl", "");
        auditIndex = Integer.parseInt(args.getString("auditIndex", "0"));
        auditTitle = args.getString("auditTitle", "");
        auditProbe = "1".equals(args.getString("auditProbe", ""));
        clearConfigDb = "1".equals(args.getString("clearConfigDb", ""));
        auditConfigDb = "1".equals(args.getString("auditConfigDb", ""));
        auditPreferences = "1".equals(args.getString("auditPreferences", ""));
        start();
    }

    @Override public void onStart() {
        Bundle output = new Bundle();
        Context context = getTargetContext();
        try {
            if (clearConfigDb) {
                SharedPreferences marker = context.getSharedPreferences("ntv-tvbox-config-db", 0);
                if (!marker.getBoolean("owned", false)) throw new IOException("Database is not owned by this test");
                if (!android.database.sqlite.SQLiteDatabase.deleteDatabase(context.getDatabasePath("tv"))) throw new IOException("Cannot remove test database");
                marker.edit().clear().commit();
                output.putString("stream", "Cleared owned metadata fixture database\n");
                finish(Activity.RESULT_OK, output); return;
            }
            if (auditPreferences) {
                JSONObject report = new JSONObject();
                File folder = new File(context.getApplicationInfo().dataDir, "shared_prefs");
                File[] files = folder.listFiles();
                if (files != null) for (File file : files) {
                    if (!file.getName().endsWith(".xml")) continue;
                    String name = file.getName().substring(0, file.getName().length() - 4);
                    org.json.JSONArray keys = new org.json.JSONArray();
                    for (String key : context.getSharedPreferences(name, 0).getAll().keySet()) {
                        if (!key.matches("[A-Za-z0-9_.:-]{1,80}")) continue;
                        if (name.equals("spUtils") || key.toLowerCase(java.util.Locale.US).matches(".*(quark|cookie|token).*")) keys.put(key);
                    }
                    if (keys.length() > 0) report.put(name, keys);
                }
                output.putString("stream", "AUTH_PREFS " + report + "\n");
                finish(Activity.RESULT_OK, output); return;
            }
            if (!auditUrl.isEmpty()) {
                auditSite(context, output);
                finish(Activity.RESULT_OK, output);
                return;
            }
            File runtimeFile = sampleRuntimeFile(context);
            if (repairCache && !runtimeFile.delete()) throw new AssertionError("Expected an installed runtime file");
            JSONObject runtime = null;
            try (FixtureBox resident = stayOnTvBox ? new FixtureBox(context, origin) : null) {
                for (int round = 1; round <= rounds; round++) {
                    long previousWrite = runtimeFile.lastModified();
                    long started = SystemClock.elapsedRealtime();
                    runtime = probePython(context);
                    long ready = SystemClock.elapsedRealtime();
                    if (!runtimeFile.isFile()) throw new AssertionError("Python site packages were not extracted");
                    if (resident != null) resident.check();
                    else try (FixtureBox fixture = new FixtureBox(context, origin)) { fixture.check(); }
                    long resolved = SystemClock.elapsedRealtime();
                    checkKu9(context);
                    String[] files = new File("/proc/self/fd").list();
                    Bundle metric = new Bundle();
                    metric.putString("stream", "METRIC " + new JSONObject().put("round", round)
                            .put("pythonMs", ready - started).put("tvboxMs", resolved - ready)
                            .put("ku9Ms", SystemClock.elapsedRealtime() - resolved)
                            .put("pssKiB", Debug.getPss()).put("nativeKiB", Debug.getNativeHeapAllocatedSize() / 1024)
                            .put("openFiles", files == null ? -1 : files.length)
                            .put("cacheReused", previousWrite != 0 && previousWrite == runtimeFile.lastModified())
                            + "\n");
                    sendStatus(0, metric);
                    if (round < rounds) SystemClock.sleep(1000L * pauseSeconds);
                }
            }
            output.putString("stream", "PASS Python runtime " + runtime + (tlsUrl.isEmpty() ? "" : " with HTTPS")
                    + "\nPASS TVBox JSON, JS modules, CPython, Quark/UC proxy fixtures and Ku9 Python"
                    + " (" + rounds + " rounds)\n");
            finish(Activity.RESULT_OK, output);
        } catch (Throwable error) {
            output.putString("stream", "FAIL " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, output);
        }
    }

    /** Read-only real-source audit. Keep identifiers, stream URLs and credentials out of the report. */
    private void auditSite(Context context, Bundle output) throws Exception {
        JSONObject report = new JSONObject().put("index", auditIndex);
        File jar = InstalledPlugins.installed(context, "tvbox");
        ClassLoader loader = InstalledPlugins.loader(context, jar);
        Class<?> type = loader.loadClass("xiao.bu.channel.tvbox.BoxSource");
        Class<?> httpType = loader.loadClass("xiao.bu.channel.tvbox.BoxHttp");
        Object http = constructor(httpType, OkHttpClient.class).newInstance(NetworkClient.sharedClient());
        Object box = constructor(type, Context.class, ChannelPlugin.VodHost.class, ClassLoader.class, httpType)
                .newInstance(context, host(context), loader, http);
        String stage = "config";
        try {
            method(type, "load", String.class).invoke(box, auditUrl);
            if (auditConfigDb) {
                File file = context.getDatabasePath("tv");
                SharedPreferences marker = context.getSharedPreferences("ntv-tvbox-config-db", 0);
                if (file.exists() && !marker.getBoolean("owned", false)) throw new IOException("Refusing to change an existing database");
                try (android.database.sqlite.SQLiteDatabase db = context.openOrCreateDatabase("tv", 0, null)) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS Config (id INTEGER PRIMARY KEY, type INTEGER NOT NULL, time INTEGER NOT NULL, url TEXT, json TEXT, name TEXT, logo TEXT, home TEXT, parse TEXT)");
                    java.lang.reflect.Field field = type.getDeclaredField("config"); field.setAccessible(true);
                    android.content.ContentValues values = new android.content.ContentValues();
                    values.put("id", 1); values.put("type", 0); values.put("time", System.currentTimeMillis());
                    values.put("url", auditUrl); values.put("json", field.get(box).toString());
                    values.put("name", "TVBox"); values.put("logo", ""); values.put("home", ""); values.put("parse", "");
                    db.insertWithOnConflict("Config", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);
                }
                if (!marker.edit().putBoolean("owned", true).commit()) throw new IOException("Cannot mark fixture ownership");
                report.put("configDbFixture", true);
            }
            List<?> sites = (List<?>) method(type, "sites").invoke(box);
            Object site = sites.get(auditIndex);
            java.lang.reflect.Field name = site.getClass().getDeclaredField("name");
            name.setAccessible(true);
            report.put("name", name.get(site));
            stage = "select";
            method(type, "select", site.getClass()).invoke(box, site);
            stage = "home";
            JSONObject home = (JSONObject) method(type, "home").invoke(box);
            org.json.JSONArray categories = home.optJSONArray("class"), items = home.optJSONArray("list");
            report.put("home", items == null ? 0 : items.length()).put("categories", categories == null ? 0 : categories.length());
            if (items == null || items.length() == 0) {
                stage = "recommendations";
                try {
                    items = ((JSONObject) method(type, "recommendations").invoke(box)).optJSONArray("list");
                    report.put("recommendations", items == null ? 0 : items.length());
                } catch (Exception error) { report.put("recommendationsError", errorType(error)); }
            }
            if ((items == null || items.length() == 0) && categories != null) {
                stage = "category";
                for (int i = 0; i < Math.min(3, categories.length()); i++) {
                    try {
                        items = ((JSONObject) method(type, "category", String.class, int.class, JSONObject.class)
                                .invoke(box, categories.getJSONObject(i).optString("type_id"), 1, new JSONObject())).optJSONArray("list");
                        report.put("categoryItems", items == null ? 0 : items.length());
                        if (items != null && items.length() > 0) break;
                    } catch (Exception error) { report.put("categoryError", errorType(error)); }
                }
            }
            if (items == null || items.length() == 0) {
                stage = "search";
                try {
                    items = ((JSONObject) method(type, "search", String.class, int.class).invoke(box, "流浪地球", 1)).optJSONArray("list");
                    report.put("searchItems", items == null ? 0 : items.length());
                } catch (Exception error) { report.put("searchError", errorType(error)); }
            }
            if (items == null || items.length() == 0) { report.put("result", "empty"); return; }
            JSONObject item = items.getJSONObject(0);
            if (!auditTitle.isEmpty()) for (int i = 0; i < items.length(); i++) {
                if (items.getJSONObject(i).optString("vod_name").contains(auditTitle)) { item = items.getJSONObject(i); break; }
            }
            report.put("firstTag", item.optString("vod_tag")).put("firstName", item.optString("vod_name"));
            report.put("idScheme", Uri.parse(item.optString("vod_id")).getScheme());
            report.put("itemFields", item.names());
            String poster = item.optString("vod_pic");
            report.put("posterHost", Uri.parse(poster).getHost()).put("posterLegacyProxy", poster.contains(":-1/"))
                    .put("posterHeaders", poster.contains("@Referer=") || poster.contains("|"));
            if (item.optString("vod_id").isEmpty()) { report.put("result", "metadata"); return; }
            if ("action".equals(item.optString("vod_tag")) || !item.optString("action").isEmpty()) {
                report.put("result", "configuration"); return;
            }
            stage = "folder";
            for (int depth = 0; "folder".equals(item.optString("vod_tag")) && depth < 4; depth++) {
                items = ((JSONObject) method(type, "category", String.class, int.class, JSONObject.class)
                        .invoke(box, item.optString("vod_id"), 1, new JSONObject())).optJSONArray("list");
                if (items == null || items.length() == 0) { report.put("result", "empty-folder"); return; }
                item = items.getJSONObject(0);
                report.put("folderDepth", depth + 1);
            }
            stage = "detail";
            org.json.JSONArray details = ((JSONObject) method(type, "detail", String.class).invoke(box, item.optString("vod_id"))).optJSONArray("list");
            if (details == null || details.length() == 0) { report.put("result", "empty-detail"); return; }
            JSONObject video = details.getJSONObject(0);
            report.put("detailFields", video.names());
            String[] flags = video.optString("vod_play_from").split("\\$\\$\\$", -1);
            String[] routes = video.optString("vod_play_url").split("\\$\\$\\$", -1);
            report.put("lines", routes.length);
            String episode = routes[0].split("#", 2)[0];
            report.put("flag", flags[0]).put("episodeName", episode.contains("$") ? episode.substring(0, episode.indexOf('$')) : "");
            if (episode.isEmpty()) { report.put("result", "no-episodes"); return; }
            stage = "player";
            JSONObject result = (JSONObject) method(type, "play", String.class, String.class)
                    .invoke(box, flags[0], episode.substring(episode.indexOf('$') + 1));
            Uri media = Uri.parse(result.getString("url"));
            report.put("scheme", media.getScheme()).put("format", result.optString("format"));
            if (auditProbe) {
                JSONObject headers = result.optJSONObject("header");
                if (headers == null && result.opt("header") instanceof String && !result.optString("header").isEmpty())
                    headers = new JSONObject(result.optString("header"));
                report.put("headerNames", headers == null ? null : headers.names());
                report.put("mediaProbe", probeMedia(result.getString("url"), headers, 2));
            }
            if (media.getPath().endsWith(".mpd") || "mpd".equals(media.getQueryParameter("type"))) {
                okhttp3.Request.Builder request = new okhttp3.Request.Builder().url(result.getString("url"));
                JSONObject headers = result.optJSONObject("header");
                if (headers == null && result.opt("header") instanceof String) headers = new JSONObject(result.optString("header"));
                report.put("headerNames", headers == null ? null : headers.names());
                if (headers != null) for (java.util.Iterator<String> keys = headers.keys(); keys.hasNext();) {
                    String key = keys.next(); request.header(key, headers.optString(key));
                }
                try (okhttp3.Response response = NetworkClient.sharedClient().newCall(request.build()).execute()) {
                    String xml = response.body().string();
                    java.util.regex.Matcher picture = java.util.regex.Pattern.compile("(?s)<Representation[^>]*mimeType=\"video/mp4\"[^>]*>\\s*<BaseURL>(.*?)</BaseURL>").matcher(xml);
                    if (!auditTitle.isEmpty() && picture.find()) {
                        okhttp3.Request.Builder pictureRequest = new okhttp3.Request.Builder().url(picture.group(1).replace("&amp;", "&")).header("Range", "bytes=0-1500000");
                        if (headers != null) for (java.util.Iterator<String> keys = headers.keys(); keys.hasNext();) {
                            String key = keys.next(); pictureRequest.header(key, headers.optString(key));
                        }
                        try (okhttp3.Response clip = NetworkClient.sharedClient().newCall(pictureRequest.build()).execute();
                             java.io.FileOutputStream file = new java.io.FileOutputStream(new File(context.getCacheDir(), "audit-video.mp4"))) {
                            byte[] bytes = clip.body().bytes(); file.write(bytes); report.put("videoProbeBytes", bytes.length);
                        }
                    }
                    java.util.regex.Matcher audio = java.util.regex.Pattern.compile("(?s)<Representation[^>]*mimeType=\"audio/mp4\"[^>]*>\\s*<BaseURL>(.*?)</BaseURL>").matcher(xml);
                    if (!auditTitle.isEmpty() && audio.find()) {
                        okhttp3.Request.Builder audioRequest = new okhttp3.Request.Builder().url(audio.group(1).replace("&amp;", "&")).header("Range", "bytes=0-200000");
                        if (headers != null) for (java.util.Iterator<String> keys = headers.keys(); keys.hasNext();) {
                            String key = keys.next(); audioRequest.header(key, headers.optString(key));
                        }
                        try (okhttp3.Response sound = NetworkClient.sharedClient().newCall(audioRequest.build()).execute();
                             java.io.FileOutputStream file = new java.io.FileOutputStream(new File(context.getCacheDir(), "audit-audio.m4a"))) {
                            byte[] bytes = sound.body().bytes(); file.write(bytes); report.put("audioProbeBytes", bytes.length);
                        }
                    }
                    xml = xml.replaceAll("(?s)<BaseURL>.*?</BaseURL>", "<BaseURL>[media]</BaseURL>")
                            .replaceAll("https?[^\\s<>\"]+", "[media]");
                    report.put("mpd", xml.substring(0, Math.min(12000, xml.length())));
                }
            }
            report.put("result", "resolved");
        } catch (Exception | LinkageError error) {
            report.put("result", "error").put("stage", stage).put("error", errorType(error));
        } finally {
            ((java.io.Closeable) box).close();
            output.putString("stream", "SITE " + report + "\n");
        }
    }

    private static String errorType(Throwable error) {
        while (error instanceof java.lang.reflect.InvocationTargetException && error.getCause() != null) error = error.getCause();
        String message = error.getMessage();
        if (message == null) return error.getClass().getSimpleName();
        message = message.replaceAll("https?://[^\\s]+", "[address]");
        return error.getClass().getSimpleName() + ": " + message.substring(0, Math.min(180, message.length()));
    }

    private static JSONObject probeMedia(String url, JSONObject headers, int depth) throws Exception {
        okhttp3.Request.Builder request = new okhttp3.Request.Builder().url(url).header("Range", "bytes=0-4095");
        if (headers != null) for (java.util.Iterator<String> keys = headers.keys(); keys.hasNext();) {
            String key = keys.next(); if (!"Range".equalsIgnoreCase(key)) request.header(key, headers.optString(key));
        }
        try (okhttp3.Response response = NetworkClient.sharedClient().newCall(request.build()).execute()) {
            byte[] prefix = new byte[4096]; int size = 0;
            try (InputStream input = response.body().byteStream()) {
                for (int count; size < prefix.length && (count = input.read(prefix, size, prefix.length - size)) > 0;) size += count;
            }
            String text = new String(prefix, 0, size, "UTF-8").trim();
            String kind = text.startsWith("#EXTM3U") ? "hls" : text.startsWith("<") ? "markup"
                    : size > 8 && new String(prefix, 4, 4, "US-ASCII").equals("ftyp") ? "mp4"
                    : size > 188 && prefix[0] == 0x47 && prefix[188] == 0x47 ? "mpegts" : "other";
            JSONObject result = new JSONObject().put("http", response.code()).put("mime", response.header("Content-Type"))
                    .put("bytes", size).put("kind", kind);
            if (kind.equals("hls") && response.isSuccessful() && depth > 0) for (String line : text.split("\\r?\\n")) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                okhttp3.HttpUrl child = response.request().url().resolve(line.trim());
                if (child != null) try { result.put("child", probeMedia(child.toString(), headers, depth - 1)); }
                catch (Exception error) { result.put("childError", errorType(error)); }
                break;
            }
            return result;
        }
    }

    private static File sampleRuntimeFile(Context context) throws Exception {
        File archive = InstalledPlugins.installed(context, "python");
        if (archive == null) throw new AssertionError("Install the Python plugin in the test app first");
        return new File(context.getFilesDir(), "python-runtime/" + archive.getName() + "/bs4/__init__.py");
    }

    private JSONObject probePython(Context context) throws Exception {
        JSONObject runtime = InstalledPlugins.python(context, new JSONObject()
                .put("action", "ku9").put("origin", "runtime_probe.py")
                .put("item", new JSONObject().put("tlsUrl", tlsUrl).put("tlsCa", tlsCa))
                .put("source", "import base64, ssl, sys, urllib.request, zlib\n"
                        + "from bs4 import BeautifulSoup\n"
                        + "def main(item):\n"
                        + "    assert zlib.decompress(zlib.compress(b'ntv')) == b'ntv'\n"
                        + "    assert BeautifulSoup('<b>ntv</b>', 'html.parser').b.string == 'ntv'\n"
                        + "    if item['tlsUrl']:\n"
                        + "        context = ssl.create_default_context(cadata=base64.b64decode(item['tlsCa']).decode())\n"
                        + "        with urllib.request.urlopen(item['tlsUrl'], context=context, timeout=15) as reply:\n"
                        + "            assert reply.read() == b'ntv-python'\n"
                        + "    return {'version': sys.version.split()[0], 'ssl': ssl.OPENSSL_VERSION,"
                        + " 'bits': sys.maxsize.bit_length() + 1}\n"));
        if (!pythonVersion.isEmpty() && !pythonVersion.equals(runtime.getString("version")))
            throw new AssertionError("Unexpected Python version " + runtime);
        int bits = "arm64-v8a".equals(BuildConfig.CJS_PLUGIN_ABI) ? 64 : 32;
        if (runtime.getInt("bits") != bits) throw new AssertionError("Unexpected Python architecture " + runtime);
        return runtime;
    }

    private static final class FixtureBox implements java.io.Closeable {
        private final Class<?> type;
        private final Object box;
        private final List<?> sites;

        FixtureBox(Context context, String origin) throws Exception {
            File jar = InstalledPlugins.installed(context, "tvbox");
            if (jar == null) throw new AssertionError("Install TVBox and Python in the test app first");
            ClassLoader loader = InstalledPlugins.loader(context, jar);
            type = loader.loadClass("xiao.bu.channel.tvbox.BoxSource");
            Class<?> httpType = loader.loadClass("xiao.bu.channel.tvbox.BoxHttp");
            Object http = constructor(httpType, OkHttpClient.class).newInstance(NetworkClient.sharedClient());
            box = constructor(type, Context.class, ChannelPlugin.VodHost.class, ClassLoader.class, httpType)
                    .newInstance(context, host(context), loader, http);
            try {
                method(type, "load", String.class).invoke(box, origin + "/config?a=1&b=2");
                sites = (List<?>) method(type, "sites").invoke(box);
                if (sites.size() != 5) throw new AssertionError("Expected five sites in the fixture");
            } catch (Exception | AssertionError error) {
                close();
                throw error;
            }
        }

        void check() throws Exception {
            for (int i = 0; i < sites.size(); i++) {
                Object site = sites.get(i);
                method(type, "select", site.getClass()).invoke(box, site);
                JSONObject home = (JSONObject) method(type, "home").invoke(box);
                if (home.getJSONArray("list").length() != 1) throw new AssertionError("Missing home item");
                JSONObject details = (JSONObject) method(type, "detail", String.class).invoke(box, "1");
                String play = details.getJSONArray("list").getJSONObject(0).getString("vod_play_url");
                JSONObject result = (JSONObject) method(type, "play", String.class, String.class)
                        .invoke(box, "fixture", play.substring(play.indexOf('$') + 1));
                okhttp3.Request.Builder request = new okhttp3.Request.Builder().url(result.getString("url"))
                        .header("Range", "bytes=1-31");
                if (result.optJSONObject("header") != null) request.header("X-Fixture", "verified");
                try (okhttp3.Response response = NetworkClient.sharedClient().newCall(request.build()).execute()) {
                    if (response.code() != 206 || response.body().bytes().length != 31)
                        throw new AssertionError("Media Range request failed for site " + i + " HTTP " + response.code());
                }
            }
        }

        @Override public void close() throws IOException {
            ((java.io.Closeable) box).close();
        }
    }

    private void checkKu9(Context context) throws Exception {
        String ku9 = origin + "/ku9.py?id=21";
        JSONObject result = Ku9Python.run(context, ku9, Ku9Python.load(context, ku9),
                new JSONObject().put("url", ku9).put("name", "Ku9 fixture"));
        if (!result.getString("url").equals(origin + "/fixture.mp4")) throw new AssertionError("Ku9 URL");
    }

    private static Constructor<?> constructor(Class<?> type, Class<?>... args) throws Exception {
        Constructor<?> result = type.getDeclaredConstructor(args);
        result.setAccessible(true);
        return result;
    }

    private static Method method(Class<?> type, String name, Class<?>... args) throws Exception {
        Method result = type.getDeclaredMethod(name, args);
        result.setAccessible(true);
        return result;
    }

    private static ChannelPlugin.VodHost host(Context context) {
        return new ChannelPlugin.VodHost() {
            public Object httpClient() { return NetworkClient.sharedClient(); }
            public JSONObject python(JSONObject request) throws Exception { return InstalledPlugins.python(context, request); }
            public void javascript(String source, ChannelPlugin.ScriptHost host) throws Exception {
                NativeQuickJs.executeModule(source, new NativeQuickJs.Host() {
                    public String invoke(int operation, String[] args) throws Exception { return host.invoke(operation, args); }
                    public boolean isCancelled() { return host.isCancelled(); }
                });
            }
            public void play(String url) { }
            public void playVod(String url, JSONObject headers) { }
            public void status(String message) { }
            public void configureBrowser(WebView webView) { }
            public void chooseFiles(ValueCallback<Uri[]> callback, String accept, boolean multiple) { }
            public void chooseDirectory(ValueCallback<Uri[]> callback) { }
        };
    }
}
