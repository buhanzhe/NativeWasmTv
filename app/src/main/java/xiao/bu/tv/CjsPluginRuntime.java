package xiao.bu.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import xiao.bu.tv.AppLog;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;

/** Online per-site plugins with file integrity checks. Network I/O never holds the runtime monitor. */
public final class CjsPluginRuntime {
    public static final int HOST_PROTOCOL = 5;
    public static final String DEFAULT_MANIFEST_URL =
            "https://raw.githubusercontent.com/TvWasm/cjs/main/catalog.json";
    private static final String TAG = "CjsPlugin";
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;
    private static final int MAX_SCRIPT_BYTES = 512 * 1024;
    private static final int MAX_NATIVE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_COMPONENT_CONFIG_BYTES = 2048;
    private static Context context;
    private static JSONObject catalog;
    private static boolean catalogReadAttempted;
    private static boolean abiChangedAtStartup;
    private static final Map<String, State> states = new HashMap<String, State>();
    private static final Object INSTALL_LOCK = new Object();
    private static boolean startupUpdateAttempted;

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
        return requireContext().getSharedPreferences("cjs_sites_v5", Context.MODE_PRIVATE);
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
    private static File root() { return new File(requireContext().getFilesDir(), "cjs-sites-v5"); }
    private static String currentAbi() { return BuildConfig.CJS_PLUGIN_ABI; }
    static String currentProfile() { return CjsNativeProfile.select(currentAbi(), Build.VERSION.SDK_INT); }
    private static String pref(State s, String key) { return s.id + ":" + currentProfile() + ":" + key; }
    private static File siteRoot(State s) { return new File(new File(root(), s.id), currentProfile()); }
    private static File directory(State s, int version) { return new File(siteRoot(s), String.valueOf(version)); }

    private static JSONObject readManifest(byte[] bytes) throws Exception {
        return readManifest(new JSONObject(new String(bytes, "UTF-8")));
    }
    private static JSONObject readManifest(JSONObject value) throws Exception {
        if (value.optInt("protocol") != HOST_PROTOCOL) throw new IOException("插件协议不兼容");
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
        if (context == null || catalogReadAttempted) return false;
        // A missing catalog is normal before the first download. Do not reopen it
        // on every state query; refreshCatalog publishes downloaded catalogs directly.
        catalogReadAttempted = true;
        try {
            File cache = new File(root(), "catalog.json");
            JSONObject stored = new JSONObject(new String(readFile(cache, MAX_MANIFEST_BYTES), "UTF-8"));
            JSONObject value = readManifest(stored);
            useCatalog(value);
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
            JSONObject discovered = !knowsSource(source) ? discoverSourceDescriptor(source) : null;
            playbackUrl(url); // Validate parameters before retrying playback.
            State selected = sourceState(source);
            if (selected != null && !isInstalled(selected.id)) {
                installOrUpdate(selected.id, discovered);
            }
        }
    }
    /** A self-contained descriptor is an import source, not a catalog allowlist entry. */
    private static JSONObject discoverSourceDescriptor(CjsSource source) throws Exception {
        String descriptor = GithubProxy.unwrap(source.descriptorUrl);
        JSONObject probe = new JSONObject(new String(download(cacheBustedUrl(online(descriptor)),
                MAX_MANIFEST_BYTES), "UTF-8"));
        String id = name(probe.getString("id"));
        if (probe.optInt("v") < 1) throw new IOException("CJS 来源版本无效");
        online(probe.getString("manifest"));
        JSONObject selected = probe.optJSONObject("site");
        // Older descriptors may reuse known site metadata, but their own URL selects the release.
        if (selected == null) {
            synchronized (CjsPluginRuntime.class) {
                State existing = states.get(id);
                if (existing != null) selected = new JSONObject(existing.entry.toString());
            }
        }
        if (selected == null || !id.equals(selected.optString("id")) || !selected.has("playback"))
            throw new IOException("CJS 来源缺少站点播放配置");
        selected = new JSONObject(selected.toString());
        selected.put("config", descriptor);
        selected.put("sources", new JSONArray().put(descriptor));
        // Validate all channel parameters before selecting the remote component.
        JSONObject playback = selected.getJSONObject("playback");
        JSONObject rules = playback.getJSONObject("parameters");
        Map<String, String> parameters = new LinkedHashMap<String, String>();
        Iterator<String> keys = rules.keys();
        while (keys.hasNext()) { String key = keys.next(); parameters.put(key, rules.getString(key)); }
        String page = online(source.page(playback.getString("page"), parameters));
        if (!matchesHost(URI.create(page).getHost(), selected.optJSONArray("hosts")))
            throw new IOException("CJS 频道页面与站点不匹配");
        synchronized (CjsPluginRuntime.class) {
            JSONObject merged = catalog == null ? new JSONObject().put("protocol", HOST_PROTOCOL)
                    .put("sites", new JSONArray()) : new JSONObject(catalog.toString());
            JSONArray mergedEntries = new JSONArray();
            JSONArray current = merged.getJSONArray("sites");
            for (int i = 0; i < current.length(); i++) {
                JSONObject entry = current.getJSONObject(i);
                if (!selected.getString("id").equals(entry.getString("id"))) mergedEntries.put(entry);
            }
            mergedEntries.put(selected);
            merged.put("sites", mergedEntries);
            useCatalog(merged);
            persistCatalog(merged);
        }
        return probe;
    }
    static synchronized String playbackUrl(String url) throws Exception {
        CjsSource source = CjsSource.parse(url);
        if (source == null) return url;
        State s = sourceState(source);
        if (s == null || !s.entry.has("playback"))
            throw new IOException("CJS 来源尚未读取播放配置");
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
        // Validate before publishing; preserve the previous catalog if validation fails.
        useCatalog(value);
        persistCatalog(value);
    }
    private static void persistCatalog(JSONObject value) throws Exception {
        if (!root().isDirectory() && !root().mkdirs()) throw new IOException("无法创建插件目录");
        File temp = new File(root(), "catalog.tmp");
        writeAndSync(temp, value.toString().getBytes("UTF-8"));
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
        if (!currentProfile().equals(new String(readFile(new File(dir, "profile.txt"), 64), "UTF-8"))) return null;
        JSONObject result = new JSONObject(new String(readFile(new File(dir, "runtime.json"), MAX_SCRIPT_BYTES), "UTF-8"));
        return result.optInt("protocol") == HOST_PROTOCOL && s.id.equals(result.optString("id"))
                && result.optInt("version") > 0 && result.optJSONObject("scripts") != null ? result : null;
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
                catch (Exception error) { AppLog.w(TAG, "Pending site incomplete; retaining active " + id, error); }
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
                .put("nativeProfile", currentProfile()).put("abiChangedAtStartup", abiChangedAtStartup).put("manifestUrl", getManifestUrl());
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
        return installOrUpdate(id, null);
    }
    /** Playback uses local files independently of background/manual version probes. */
    static String ensureInstalled(String id) throws Exception {
        if (isInstalled(id)) return String.valueOf(state(id).runtime.getInt("version"));
        synchronized (INSTALL_LOCK) {
            if (isInstalled(id)) {
                State s = state(id);
                return String.valueOf(s.runtime.getInt("version"));
            }
            return installOrUpdate(id);
        }
    }
    /** One background batch per process, including when its network request fails. */
    static void checkInstalledOnce() throws Exception {
        synchronized (CjsPluginRuntime.class) {
            if (startupUpdateAttempted) return;
            startupUpdateAttempted = true;
        }
        synchronized (INSTALL_LOCK) {
            List<String> installed = new ArrayList<String>();
            synchronized (CjsPluginRuntime.class) {
                if (!hasCatalog()) return;
                for (String id : states.keySet()) if (isInstalled(id)) installed.add(id);
            }
            if (installed.isEmpty()) return;
            // Imported sites may use a different branch or descriptor URL. Keep
            // their persisted routing during automatic checks; only an explicit
            // catalog refresh may replace it.
            for (String id : installed) {
                try { installOrUpdate(id); }
                catch (Exception error) { AppLog.w(TAG, "Startup site update failed: " + id, error); }
            }
        }
    }
    private static String installOrUpdate(String id, JSONObject discovered) throws Exception {
        synchronized (INSTALL_LOCK) {
            ensureCatalog();
            State s = state(id);
            JSONObject probe = discovered != null ? discovered : new JSONObject(new String(download(cacheBustedUrl(
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
                    if (!"all".equals(abi)) {
                        if (!currentProfile().equals(f.optString("profile"))) continue;
                        if (!currentAbi().equals(abi) || f.optInt("minSdk", -1) != CjsNativeProfile.minSdk(currentProfile())
                                || Build.VERSION.SDK_INT < f.getInt("minSdk")) throw new IOException("插件平台声明不匹配");
                    } else if (f.has("profile") || !"runtime.json".equals(f.optString("name"))) {
                        throw new IOException("共享插件文件声明无效");
                    }
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
                writeAndSync(new File(staging, "profile.txt"), currentProfile().getBytes("UTF-8"));
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
                            AppLog.w(TAG, "Unable to restore incomplete cache " + target);
                        throw new IOException("无法启用站点插件");
                    }
                    if (s.used) preferences().edit().putInt(pref(s, "pending"), version).commit();
                    else {
                        preferences().edit().putInt(pref(s, "active"), version).remove(pref(s, "pending")).commit();
                        s.runtime = next; s.directory = target;
                    }
                }
                deleteRecursively(replaced);
                AppLog.i(TAG, "Site installed id=" + id + " version=" + version + " abi=" + currentAbi() + " pending=" + s.used);
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
                AppLog.i(TAG, "Site native loaded id=" + id);
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
        State s = executableSite(pageUrl);
        if (s == null) return null;
        return new SitePlugin(s.id, script(s.id, s.runtime.getString("entry"), null),
                s.entry.getString("module") + ".so", s.runtime.optString("transformer"),
                s.runtime.optString("engine"), s.runtime.optString("jsApi", "cjs-v4"));
    }
    private static State executableSite(String pageUrl) throws IOException, JSONException {
        String id = componentForUrl(pageUrl);
        if (id.length() == 0 || !isInstalled(id)) return null;
        State s = state(id);
        String entry = s.runtime.optString("entry");
        if (entry.length() == 0 || !s.runtime.getJSONObject("scripts").has(entry)) return null;
        String jsApi = s.runtime.optString("jsApi", "cjs-v4");
        if (!"ku9".equals(jsApi) && !"cjs-v4".equals(jsApi))
            throw new IOException("站点脚本接口需要更新客户端: " + jsApi);
        return s;
    }
    static synchronized boolean supportsSite(String pageUrl) {
        // A site's homepage is a browsable document, not a live-channel resolver
        // input. Host matching alone must not intercept it after plugin install.
        try {
            if (pageUrl == null) return false;
            URI uri = URI.create(pageUrl);
            String path = uri.getPath();
            if ((path == null || path.length() == 0 || "/".equals(path))
                    && uri.getRawQuery() == null) return false;
        } catch (IllegalArgumentException ignored) { return false; }
        // Capability queries must not pin a script version before it is executed.
        try { return executableSite(pageUrl) != null; } catch (Exception ignored) { return false; }
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
        int expectedMachine = "arm64-v8a".equals(abi) ? 183
                : "x86".equals(abi) ? 3 : 40;
        int elfClass = data[4] & 0xff;
        int machine = (data[18] & 0xff) | ((data[19] & 0xff) << 8);
        if (elfClass != expectedClass || machine != expectedMachine) {
            throw new IOException("插件架构不匹配：" + name + " 需要 " + abi);
        }
    }

    static boolean retryableDownloadFailure(Throwable error) {
        if(Thread.currentThread().isInterrupted()) return false;
        for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
            if(cause instanceof SocketException || cause instanceof EOFException
                    || cause instanceof SocketTimeoutException) return true;
            String message=cause.getMessage();
            if(message!=null) {
                String text=message.toLowerCase(Locale.US);
                if(text.contains("stream was reset") || text.contains("unexpected end of stream")
                        || text.contains("connection shutdown") || text.equals("canceled")
                        || text.equals("cancelled")) return true;
            }
        }
        return false;
    }

    private static byte[] download(String originalUrl, int maxBytes) throws IOException {
        final long startedAt=android.os.SystemClock.elapsedRealtime();
        final String resource=new URL(originalUrl).getPath();
        final int budgetMs=resource.endsWith(".so") ? 30000 : 8000;
        String url=GithubProxy.apply(originalUrl);
        AppLog.i(TAG,"Download begin resource="+resource);
        for(int attempt=0;attempt<2;attempt++) {
            if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Plugin download cancelled");
            long remaining=budgetMs-(android.os.SystemClock.elapsedRealtime()-startedAt);
            if(remaining<=0) throw new SocketTimeoutException("Plugin download timeout");
            try {
                return downloadAttempt(url,maxBytes,attempt>0,(int)remaining);
            } catch(IOException error) {
                if(attempt!=0 || !retryableDownloadFailure(error)
                        || android.os.SystemClock.elapsedRealtime()-startedAt>=budgetMs) {
                    AppLog.w(TAG,"Download failed resource="+resource+" elapsedMs="
                            +(android.os.SystemClock.elapsedRealtime()-startedAt),error);
                    throw error;
                }
                AppLog.w(TAG,"Plugin download interrupted; retry once over HTTP/1.1: "+resource,error);
            }
        }
        throw new IOException("Plugin download failed");
    }

    static final class DownloadHttpException extends IOException {
        final int status;
        DownloadHttpException(int status) { super("插件下载失败：HTTP " + status); this.status = status; }
    }

    private static byte[] downloadAttempt(String url,int maxBytes,boolean http1,int remainingMs) throws IOException {
        long startedAt=android.os.SystemClock.elapsedRealtime();
        HttpURLConnection connection=NetworkClient.openComponent(new URL(url),http1,remainingMs);
        connection.setConnectTimeout(Math.min(10000,remainingMs));
        connection.setReadTimeout(Math.min(20000,remainingMs));
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept-Encoding","identity");
        try {
            int status=connection.getResponseCode();
            long headersAt=android.os.SystemClock.elapsedRealtime();
            if(status<200 || status>=300) throw new DownloadHttpException(status);
            int length=connection.getContentLength();
            if(length>maxBytes) throw new IOException("插件文件过大");
            try(InputStream input=connection.getInputStream()) {
                ByteArrayOutputStream output=new ByteArrayOutputStream(length>0?Math.min(length,maxBytes):16384);
                byte[] buffer=new byte[32*1024];int count,total=0;
                while((count=input.read(buffer))!=-1) {
                    if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Plugin download cancelled");
                    total+=count;
                    if(total>maxBytes) throw new IOException("插件文件过大");
                    output.write(buffer,0,count);
                }
                if(length>=0 && total!=length) throw new EOFException("Unexpected end of stream downloading plugin");
                AppLog.i(TAG,"Download complete bytes="+total+" http1Retry="+http1
                        +" waitMs="+(headersAt-startedAt)+" bodyMs="+(android.os.SystemClock.elapsedRealtime()-headersAt)
                        +" host="+connection.getURL().getHost());
                return output.toByteArray();
            }
        } finally {connection.disconnect();}
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

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        if (!file.delete()) AppLog.w(TAG, "Unable to delete " + file);
    }
}
