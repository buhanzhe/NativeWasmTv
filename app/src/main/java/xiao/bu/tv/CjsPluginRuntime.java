package xiao.bu.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;

/** Online per-site plugins with file integrity checks. Network I/O never holds the runtime monitor. */
public final class CjsPluginRuntime {
    public static final int HOST_PROTOCOL = 4;
    public static final String DEFAULT_MANIFEST_URL =
            "https://raw.githubusercontent.com/TvWasm/cjs/main/catalog.json";
    private static final String TAG = "CjsPlugin";
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;
    private static final int MAX_SCRIPT_BYTES = 512 * 1024;
    private static final int MAX_NATIVE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_COMPONENT_CONFIG_BYTES = 2048;
    private static Context context;
    private static JSONObject catalog;
    private static boolean abiChangedAtStartup;
    private static final Map<String, State> states = new HashMap<String, State>();
    private static final Set<String> checked = new HashSet<String>();
    private static final Object INSTALL_LOCK = new Object();

    private static final class State {
        final String id;
        JSONObject entry;
        JSONObject runtime;
        File directory;
        boolean used;
        boolean loaded;
        State(String id, JSONObject entry) { this.id = id; this.entry = entry; }
    }

    private CjsPluginRuntime() { }
    public static void initialize(Context value) {
        if (context != null || value == null) return;
        context = value.getApplicationContext();
        String abi = currentAbi(), previous = preferences().getString("last_host_abi", "");
        abiChangedAtStartup = previous.length() > 0 && !abi.equals(previous);
        if (!abi.equals(previous)) preferences().edit().putString("last_host_abi", abi).apply();
    }
    private static Context requireContext() {
        if (context == null) throw new IllegalStateException("CJS runtime not initialized");
        return context;
    }
    private static SharedPreferences preferences() {
        return requireContext().getSharedPreferences("cjs_sites_v4", Context.MODE_PRIVATE);
    }
    public static String getManifestUrl() {
        return preferences().getString("catalog_url", DEFAULT_MANIFEST_URL);
    }
    public static void setManifestUrl(String url) {
        online(url);
        preferences().edit().putString("catalog_url", url.trim()).apply();
    }
    private static String online(String url) {
        try {
            URI uri = URI.create(url);
            if (("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null) return url;
        } catch (Exception ignored) { }
        throw new IllegalArgumentException("插件地址仅支持在线 HTTP/HTTPS");
    }
    private static String name(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
            throw new IOException("插件名称无效");
        return value;
    }
    private static File root() { return new File(requireContext().getFilesDir(), "cjs-sites-v4"); }
    private static String currentAbi() { return BuildConfig.CJS_PLUGIN_ABI; }
    private static String pref(State s, String key) { return s.id + ":" + currentAbi() + ":" + key; }
    private static File siteRoot(State s) { return new File(new File(root(), s.id), currentAbi()); }
    private static File directory(State s, int version) { return new File(siteRoot(s), String.valueOf(version)); }

    private static JSONObject readManifest(byte[] bytes) throws Exception {
        return readManifest(new JSONObject(new String(bytes, "UTF-8")));
    }
    private static JSONObject readManifest(JSONObject value) throws Exception {
        if (value.optInt("protocol") != HOST_PROTOCOL) throw new IOException("插件协议不兼容");
        // Read old cached envelopes during upgrade; newly published manifests are plain JSON.
        if (value.has("payload")) {
            value = new JSONObject(new String(Base64.decode(value.getString("payload"), Base64.DEFAULT), "UTF-8"));
            value.put("protocol", HOST_PROTOCOL);
        }
        return value;
    }
    private static synchronized void useCatalog(JSONObject value) throws Exception {
        JSONArray entries = value.getJSONArray("sites");
        Set<String> ids = new HashSet<String>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            String id = name(e.getString("id"));
            if (!ids.add(id)) throw new IOException("站点 ID 重复");
            name(e.getString("module"));
            State existing = states.get(id);
            if (existing != null && !existing.entry.getString("module").equals(e.getString("module")))
                throw new IOException("同一站点的原生模块名不可改变");
            online(e.getString("config"));
            JSONObject q = e.getJSONObject("qualities");
            if (q.length() < 1 || q.length() > 3 || !q.has("high")) throw new IOException("清晰度档位无效");
            Iterator<String> keys = q.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (!"high".equals(k) && !"medium".equals(k) && !"low".equals(k))
                    throw new IOException("清晰度档位无效");
            }
        }
        catalog = value;
        // Refresh discovery/config URLs while preserving pinned runtime/native versions.
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            String id = e.getString("id");
            if (!states.containsKey(id)) states.put(id, new State(id, e));
            else states.get(id).entry = e;
        }
    }
    public static synchronized boolean hasCatalog() {
        if (catalog != null) return true;
        try {
            File cache = new File(root(), "catalog.json");
            JSONObject stored = new JSONObject(new String(readFile(cache, MAX_MANIFEST_BYTES), "UTF-8"));
            JSONObject value = readManifest(stored);
            useCatalog(value);
            if (stored.has("payload")) {
                // Convert once without a network request or re-downloading any site artifacts.
                try {
                    File temp = new File(root(), "catalog-migration.tmp");
                    writeAndSync(temp, value.toString().getBytes("UTF-8"));
                    if (!temp.renameTo(cache)) Log.w(TAG, "Unable to migrate cached catalog");
                } catch (Exception error) { Log.w(TAG, "Cached catalog migration deferred", error); }
            }
            return true;
        } catch (Exception ignored) { return false; }
    }
    public static void ensureCatalog() throws Exception {
        synchronized (INSTALL_LOCK) {
            if (hasCatalog()) return;
            refreshCatalog();
        }
    }
    private static synchronized State sourceState(CjsSource source) {
        if (source == null || !hasCatalog()) return null;
        String url = GithubProxy.unwrap(source.descriptorUrl);
        for (State s : states.values()) {
            if (url.equals(s.entry.optString("config"))) return s;
            JSONArray sources = s.entry.optJSONArray("sources");
            if (sources != null) for (int i = 0; i < sources.length(); i++)
                if (url.equals(sources.optString(i))) return s;
        }
        return null;
    }
    static synchronized boolean knowsSource(CjsSource source) {
        State s = sourceState(source);
        return s != null && s.entry.has("playback");
    }
    static void prepareSource(String url) throws Exception {
        CjsSource source = CjsSource.parse(url);
        if (source == null) throw new IOException("不是 CJS 频道地址");
        synchronized (INSTALL_LOCK) {
            boolean cached = hasCatalog();
            ensureCatalog();
            if (!knowsSource(source) && cached) refreshCatalog();
            playbackUrl(url); // Validate parameters before retrying playback.
        }
    }
    static synchronized String playbackUrl(String url) throws Exception {
        CjsSource source = CjsSource.parse(url);
        if (source == null) return url;
        State s = sourceState(source);
        if (s == null || !s.entry.has("playback"))
            throw new IOException("插件目录未登记此 CJS 频道入口，请检查目录配置");
        JSONObject playback = s.entry.getJSONObject("playback");
        JSONObject declared = playback.getJSONObject("parameters");
        Map<String, String> rules = new LinkedHashMap<String, String>();
        Iterator<String> keys = declared.keys();
        while (keys.hasNext()) { String key = keys.next(); rules.put(key, declared.getString(key)); }
        String page = online(source.page(playback.getString("page"), rules));
        if (!matchesHost(URI.create(page).getHost(), s.entry.optJSONArray("hosts")))
            throw new IOException("CJS 频道页面与站点不匹配");
        return "webview://" + page;
    }
    private static void refreshCatalog() throws Exception {
        byte[] bytes = download(cacheBustedUrl(getManifestUrl()), MAX_MANIFEST_BYTES);
        JSONObject value = readManifest(bytes);
        if (!root().isDirectory() && !root().mkdirs()) throw new IOException("无法创建插件目录");
        // Validate before publishing; preserve the previous catalog if validation fails.
        useCatalog(value);
        File temp = new File(root(), "catalog.tmp");
        writeAndSync(temp, bytes);
        if (!temp.renameTo(new File(root(), "catalog.json"))) throw new IOException("无法保存站点目录");
    }
    private static synchronized State state(String id) throws IOException {
        if (!hasCatalog() || !states.containsKey(id)) throw new IOException("站点插件未配置：" + id);
        return states.get(id);
    }
    private static JSONObject readRuntime(State s, File dir) throws Exception {
        File library = new File(dir, s.entry.getString("module") + ".so");
        if (!library.isFile()) return null;
        byte[] header = new byte[20];
        DataInputStream input = new DataInputStream(new FileInputStream(library));
        try { input.readFully(header); } finally { input.close(); }
        verifyNativeAbi(header, currentAbi(), library.getName());
        if (!currentAbi().equals(new String(readFile(new File(dir, "abi.txt"), 64), "UTF-8"))) return null;
        JSONObject result = new JSONObject(new String(readFile(new File(dir, "runtime.json"), MAX_SCRIPT_BYTES), "UTF-8"));
        return result.optInt("protocol") == HOST_PROTOCOL && s.id.equals(result.optString("id")) ? result : null;
    }
    public static synchronized boolean isInstalled(String id) {
        try {
            State s = state(id);
            if (s.runtime != null) return true;
            int pending = preferences().getInt(pref(s, "pending"), 0);
            int active = preferences().getInt(pref(s, "active"), 0);
            if (!s.used && pending > active) {
                JSONObject next = null;
                try { next = readRuntime(s, directory(s, pending)); }
                catch (Exception error) { Log.w(TAG, "Pending site incomplete; retaining active " + id, error); }
                if (next != null) {
                    active = pending;
                    preferences().edit().putInt(pref(s, "active"), active).remove(pref(s, "pending")).commit();
                }
            }
            if (active <= 0) return false;
            s.directory = directory(s, active);
            s.runtime = readRuntime(s, s.directory);
            return s.runtime != null;
        } catch (Exception ignored) { return false; }
    }
    public static synchronized boolean isNativeLoaded(String id) {
        State s = states.get(id); return s != null && s.loaded;
    }
    public static synchronized JSONObject statusJson() throws JSONException {
        JSONArray items = new JSONArray();
        if (hasCatalog()) for (State s : states.values()) {
            items.put(new JSONObject().put("id", s.id).put("installed", isInstalled(s.id))
                    .put("version", preferences().getInt(pref(s, "active"), 0))
                    .put("pendingVersion", preferences().getInt(pref(s, "pending"), 0))
                    .put("qualities", s.entry.optJSONObject("qualities")));
        }
        return new JSONObject().put("installed", hasCatalog()).put("version", "按站点管理")
                .put("pendingVersion", "").put("sites", items).put("abi", currentAbi())
                .put("abiChangedAtStartup", abiChangedAtStartup).put("manifestUrl", getManifestUrl());
    }
    /** Manual update affects only sites already installed on this ABI. */
    public static String installOrUpdate() throws Exception {
        synchronized (INSTALL_LOCK) {
            refreshCatalog();
            List<String> ids;
            synchronized (CjsPluginRuntime.class) { ids = new ArrayList<String>(states.keySet()); }
            int count = 0;
            for (String id : ids) if (isInstalled(id)) { installOrUpdate(id); count++; }
            return "已检查 " + count + " 个站点";
        }
    }
    public static String installOrUpdate(String id) throws Exception {
        synchronized (INSTALL_LOCK) {
            ensureCatalog();
            State s = state(id);
            JSONObject probe = new JSONObject(new String(download(cacheBustedUrl(
                    online(s.entry.getString("config"))), MAX_COMPONENT_CONFIG_BYTES), "UTF-8"));
            if (!id.equals(probe.optString("id")) || probe.optInt("v") < 1) throw new IOException("站点配置无效");
            int version = probe.getInt("v");
            int active = preferences().getInt(pref(s, "active"), 0);
            int pending = preferences().getInt(pref(s, "pending"), 0);
            if (isInstalled(id) && (version <= active || (version == pending
                    && readRuntime(s, directory(s, pending)) != null))) return String.valueOf(Math.max(active, pending));
            JSONObject manifest = readManifest(download(cacheBustedUrl(online(probe.getString("manifest"))), MAX_MANIFEST_BYTES));
            if (!id.equals(manifest.optString("id")) || version != manifest.optInt("version"))
                throw new IOException("站点清单版本或 ID 不匹配");
            JSONArray files = manifest.getJSONArray("files");
            File siteRoot = siteRoot(s);
            if (!siteRoot.isDirectory() && !siteRoot.mkdirs()) throw new IOException("无法创建站点目录");
            File staging = new File(siteRoot, ".staging-" + System.nanoTime());
            if (!staging.mkdirs()) throw new IOException("无法创建暂存目录");
            try {
                Set<String> downloaded = new HashSet<String>();
                String module = s.entry.getString("module") + ".so";
                for (int i = 0; i < files.length(); i++) {
                    JSONObject f = files.getJSONObject(i);
                    String abi = f.getString("abi");
                    if (!"all".equals(abi) && !currentAbi().equals(abi)) continue;
                    String filename = name(f.getString("name"));
                    if ((!"runtime.json".equals(filename) && !module.equals(filename)) || !downloaded.add(filename))
                        throw new IOException("站点文件声明无效");
                    byte[] data = download(online(f.getString("url")), filename.endsWith(".so") ? MAX_NATIVE_BYTES : MAX_SCRIPT_BYTES);
                    verifySha256(data, f.getString("sha256"), filename);
                    if (filename.endsWith(".so")) verifyNativeAbi(data, currentAbi(), filename);
                    writeAndSync(new File(staging, filename), data);
                }
                if (downloaded.size() != 2) throw new IOException("站点插件不完整");
                writeAndSync(new File(staging, "abi.txt"), currentAbi().getBytes("UTF-8"));
                JSONObject next = readRuntime(s, staging);
                if (next == null || next.optInt("version") != version) throw new IOException("站点脚本版本无效");
                File target = directory(s, version);
                File replaced = null;
                synchronized (CjsPluginRuntime.class) {
                    // Repair an incomplete disk cache, but never replace files pinned by this process.
                    if (target.exists()) {
                        if (s.used && target.equals(s.directory))
                            throw new IOException("站点版本正在使用，请重启后修复");
                        replaced = new File(siteRoot, ".replaced-" + System.nanoTime());
                        if (!target.renameTo(replaced)) throw new IOException("无法隔离旧站点缓存");
                    }
                    if (!staging.renameTo(target)) {
                        if (replaced != null && !replaced.renameTo(target))
                            Log.w(TAG, "Unable to restore incomplete cache " + target);
                        throw new IOException("无法启用站点插件");
                    }
                    if (s.used) preferences().edit().putInt(pref(s, "pending"), version).commit();
                    else {
                        preferences().edit().putInt(pref(s, "active"), version).remove(pref(s, "pending")).commit();
                        s.runtime = next; s.directory = target;
                    }
                }
                deleteRecursively(replaced);
                Log.i(TAG, "Site installed id=" + id + " version=" + version + " abi=" + currentAbi() + " pending=" + s.used);
                return String.valueOf(version);
            } finally { deleteRecursively(staging); }
        }
    }
    public static synchronized void loadNativeLibrary(String id) {
        if (!isInstalled(id)) throw new UnsatisfiedLinkError("站点插件未安装：" + id);
        try {
            State s = state(id);
            if (!s.loaded) {
                System.load(new File(s.directory, s.entry.getString("module") + ".so").getAbsolutePath());
                s.used = true; s.loaded = true;
                Log.i(TAG, "Site native loaded id=" + id);
            }
        } catch (Exception e) { throw new UnsatisfiedLinkError(e.toString()); }
    }
    public static synchronized String script(String id, String name, Map<String,String> values) throws IOException, JSONException {
        if (!isInstalled(id)) throw new IOException("站点脚本未安装：" + id);
        State s = state(id); s.used = true;
        String result = s.runtime.getJSONObject("scripts").getString(name);
        if (values != null) for (Map.Entry<String,String> e : values.entrySet()) result = result.replace("{{" + e.getKey() + "}}", e.getValue());
        return result;
    }
    static synchronized String componentForUrl(String pageUrl) {
        if (!hasCatalog() || pageUrl == null) return "";
        try {
            String host = URI.create(pageUrl).getHost();
            if (host == null) return "";
            for (State s : states.values()) if (matchesHost(host, s.entry.optJSONArray("hosts"))) return s.id;
        } catch (Exception ignored) { }
        return "";
    }
    static synchronized SitePlugin siteForUrl(String pageUrl) throws IOException, JSONException {
        String id = componentForUrl(pageUrl);
        if (id.length() == 0 || !isInstalled(id)) return null;
        State s = state(id);
        String entry = s.runtime.optString("entry");
        if (entry.length() == 0) return null;
        String jsApi = s.runtime.optString("jsApi", "cjs-v4");
        if (!"ku9".equals(jsApi) && !"cjs-v4".equals(jsApi))
            throw new IOException("站点脚本接口需要更新客户端: " + jsApi);
        return new SitePlugin(id, script(id, entry, null), s.entry.getString("module") + ".so",
                s.runtime.optString("transformer"), s.runtime.optString("engine"), jsApi);
    }
    static boolean supportsSite(String pageUrl) {
        try { return siteForUrl(pageUrl) != null; } catch (Exception ignored) { return false; }
    }
    static synchronized JSONArray qualityOptions(String id) {
        JSONArray result = new JSONArray();
        try {
            State s = state(id);
            JSONObject q = (isInstalled(id) ? s.runtime : s.entry).getJSONObject("qualities");
            for (String k : new String[] {"high", "medium", "low"}) if (q.has(k)) result.put(k);
        } catch (Exception ignored) {
            result.put("high").put("medium").put("low");
        }
        return result;
    }
    static synchronized String quality(String id, String requested, String ceiling) {
        String selected = "low".equals(requested) ? "low" : "medium".equals(requested) ? "medium" : "high";
        try {
            State s = state(id);
            JSONObject q = (isInstalled(id) ? s.runtime : s.entry).getJSONObject("qualities");
            String[] order = {"low", "medium", "high"};
            String value = q.optString(selected, q.getString("high"));
            // Channel metadata may impose a lower maximum, expressed in provider values.
            if (ceiling != null) {
                int cap = -1, chosen = -1;
                for (int i = 0; i < order.length; i++) {
                    if (ceiling.equals(q.optString(order[i]))) cap = i;
                    if (value.equals(q.optString(order[i]))) chosen = i;
                }
                if (cap >= 0 && chosen > cap) value = ceiling;
            }
            return value;
        } catch (Exception e) { return selected; }
    }
    static synchronized boolean needsComponentCheck(String id) {
        return id != null && id.length() > 0 && isInstalled(id) && !checked.contains(id);
    }
    static synchronized void componentCheckFailed(String id) { checked.remove(id); }
    static boolean ensureComponentCurrent(String id) throws Exception {
        synchronized (CjsPluginRuntime.class) {
            if (!needsComponentCheck(id)) return false;
            checked.add(id);
        }
        State s = state(id);
        int previous = preferences().getInt(pref(s, "active"), 0);
        int pending = preferences().getInt(pref(s, "pending"), 0);
        int version = Integer.parseInt(installOrUpdate(id));
        Log.i(TAG, "Site checked id=" + id + " local=" + previous + " remote=" + version);
        return version > Math.max(previous, pending);
    }
    private static boolean matchesHost(String host, JSONArray hosts) {
        if (hosts == null) return false;
        host = host.toLowerCase(Locale.US);
        for (int i = 0; i < hosts.length(); i++) {
            String h = hosts.optString(i).toLowerCase(Locale.US);
            if (h.length() > 0 && (host.equals(h) || host.endsWith("." + h))) return true;
        }
        return false;
    }
    static final class SitePlugin {
        final String id, component, script, nativeModule, transformer, engine, jsApi;
        SitePlugin(String id, String script, String module, String transformer, String engine, String jsApi) {
            this.id=id; this.component=id; this.script=script; this.nativeModule=module; this.transformer=transformer; this.engine=engine; this.jsApi=jsApi;
        }
    }
    private static void verifyNativeAbi(byte[] data, String abi, String name)
            throws IOException {
        if (data.length < 20 || data[0] != 0x7f || data[1] != 'E'
                || data[2] != 'L' || data[3] != 'F' || data[5] != 1) {
            throw new IOException("插件 native 文件无效：" + name);
        }
        int expectedClass = "arm64-v8a".equals(abi) ? 2 : 1;
        int expectedMachine = "arm64-v8a".equals(abi) ? 183 : 40;
        int elfClass = data[4] & 0xff;
        int machine = (data[18] & 0xff) | ((data[19] & 0xff) << 8);
        if (elfClass != expectedClass || machine != expectedMachine) {
            throw new IOException("插件架构不匹配：" + name + " 需要 " + abi);
        }
    }

    private static byte[] download(String originalUrl, int maxBytes) throws IOException {
        String url = originalUrl;
        if (url.contains("github.com/") || url.contains("raw.githubusercontent.com/")) {
            url = GithubProxy.apply(url);
        }
        HttpURLConnection connection = NetworkClient.open(new URL(url));
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept-Encoding", "identity");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("插件下载失败：HTTP " + status);
            }
            int length = connection.getContentLength();
            if (length > maxBytes) throw new IOException("插件文件过大");
            InputStream input = connection.getInputStream();
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream(
                        length > 0 ? Math.min(length, maxBytes) : 16384);
                byte[] buffer = new byte[32 * 1024];
                int total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > maxBytes) throw new IOException("插件文件过大");
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            } finally {
                input.close();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String cacheBustedUrl(String url) {
        String separator = url != null && url.indexOf('?') >= 0 ? "&" : "?";
        return url + separator + "ntv=" + System.currentTimeMillis();
    }

    private static void verifySha256(byte[] data, String expected, String name) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String actual = hex(digest.digest(data));
        if (!actual.equalsIgnoreCase(expected)) {
            throw new SecurityException("插件哈希校验失败：" + name);
        }
    }

    private static String hex(byte[] data) {
        StringBuilder value = new StringBuilder(data.length * 2);
        for (byte item : data) value.append(String.format(Locale.US, "%02x", item & 0xff));
        return value.toString();
    }

    private static String safeName(String value) {
        if (value == null) return "";
        return value.replaceAll("[^A-Za-z0-9._-]", "");
    }

    private static void writeAndSync(File file, byte[] data) throws IOException {
        FileOutputStream output = new FileOutputStream(file);
        try {
            output.write(data);
            output.flush();
            output.getFD().sync();
        } finally {
            output.close();
        }
    }

    private static byte[] readFile(File file, int maxBytes) throws IOException {
        if (!file.isFile() || file.length() > maxBytes) throw new IOException("插件文件不可用");
        FileInputStream input = new FileInputStream(file);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) file.length());
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static void pruneOldVersions(File root, String active) {
        File[] children = root.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory() && !active.equals(child.getName())
                    && !child.getName().startsWith(".staging-")) deleteRecursively(child);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        if (!file.delete()) Log.w(TAG, "Unable to delete " + file);
    }
}
