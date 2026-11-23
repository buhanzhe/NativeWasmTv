package xiao.bu.tv;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import org.json.JSONArray;
import org.json.JSONObject;

/** Catalog and artifact URLs point to the same immutable GitHub Release tag. */
final class PluginCatalog {
    private static final String RELEASES = "https://github.com/buhanzhe/ntv-plugin/releases/";
    static final String URL = RELEASES + "latest/download/plugins.json";
    private static final int CATALOG_LIMIT = 128 * 1024;
    private static final int SCRIPT_LIMIT = 2 * 1024 * 1024;
    private static final long CACHE_MS = 10 * 60 * 1000L;
    private static final long SAVED_RETRY_MS = 30000;
    private static Snapshot cached;

    static JSONObject list(Activity context, boolean refresh) throws Exception {
        Snapshot snapshot = load(context, refresh);
        JSONArray result = new JSONArray();
        for (int i = 0; i < snapshot.plugins.length(); i++) {
            JSONObject entry = snapshot.plugins.getJSONObject(i);
            String id = entry.getString("id"), kind = entry.getString("kind");
            JSONObject asset = select(entry);
            String action = action(id, kind, asset);
            JSONObject option = new JSONObject().put("id", id).put("name", entry.getString("name"))
                    .put("kind", kind).put("action", action).put("current", isCurrent(context, id, kind, asset));
            if (asset != null) option.put("version", asset.optString("version"))
                    .put("file", asset.getString("file")).put("bytes", asset.getInt("bytes"));
            if (asset != null && asset.has("pythonVersion")) option.put("pythonVersion", asset.getString("pythonVersion"));
            result.put(option);
        }
        return new JSONObject().put("ok", true).put("release", snapshot.release)
                .put("cached", snapshot.saved).put("plugins", result);
    }

    static JSONObject install(Activity context, String id, String release) throws Exception {
        Snapshot snapshot = load(context, false);
        if (!snapshot.release.equals(release)) throw new IOException("插件列表已更新，请刷新后重试");
        for (int i = 0; i < snapshot.plugins.length(); i++) {
            JSONObject entry = snapshot.plugins.getJSONObject(i);
            if (!id.equals(entry.getString("id"))) continue;
            JSONObject asset = select(entry);
            String kind = entry.getString("kind");
            if (asset == null || !"install".equals(action(id, kind, asset)))
                throw new IOException("本设备没有对应的安装包");
            int bytes = asset.getInt("bytes");
            File file = InstalledPlugins.stagingFile(context);
            try {
                String name = asset.getString("file"), sha = asset.getString("sha256");
                RadioUiCatalog.downloadVerifiedRelease(asset.getString("url"), file, bytes, sha, 60000);
                JSONObject result = new JSONObject().put("ok", true);
                if ("ku9-js".equals(kind)) {
                    byte[] script;
                    try (FileInputStream input = new FileInputStream(file)) {
                        script = RadioUiCatalog.read(input, SCRIPT_LIMIT);
                    }
                    Ku9ScriptLoader.SavedScript saved = Ku9ScriptLoader.saveUserScript(context, name, script);
                    return result.put("path", saved.path);
                }
                return result.put("version", InstalledPlugins.install(context, id, file, sha));
            } finally { file.delete(); }
        }
        throw new IOException("发布中没有该插件");
    }

    private static boolean isCurrent(Activity context, String id, String kind, JSONObject asset) throws Exception {
        if (asset == null) return false;
        String sha = asset.getString("sha256");
        if ("tvbus".equals(kind)) {
            TvbusBundle.Installed tvbus = TvbusBundle.installed(context);
            return tvbus != null && sha.equals(tvbus.digest);
        }
        if ("ku9-js".equals(kind)) {
            File script = Ku9ScriptLoader.findUserScript(context, asset.getString("file"));
            if (script == null || script.length() != asset.getInt("bytes")) return false;
            try { return sha.equals(InstalledPlugins.sha256(script)); }
            catch (IOException error) { return false; }
        }
        return sha.equals(InstalledPlugins.digest(context, id));
    }

    private static String action(String id, String kind, JSONObject asset) {
        if ("radio-ui".equals(kind) || "tv-ui".equals(kind)) return "settings";
        if ("archive".equals(kind)) return "archive";
        if ("tvbus".equals(id) && asset == null) return "upload";
        return asset == null ? "unavailable" : "install";
    }

    private static JSONObject select(JSONObject entry) throws Exception {
        JSONArray assets = entry.getJSONArray("artifacts");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (asset.getInt("minSdk") > Build.VERSION.SDK_INT) continue;
            JSONArray abis = asset.getJSONArray("abis");
            if (abis.length() == 0) return asset;
            for (int j = 0; j < abis.length(); j++)
                if (BuildConfig.CJS_PLUGIN_ABI.equals(abis.getString(j))) return asset;
        }
        return null;
    }

    private static synchronized Snapshot load(Context context, boolean refresh) throws Exception {
        if (!refresh && cached != null && SystemClock.elapsedRealtime() - cached.loadedAt
                < (cached.saved ? SAVED_RETRY_MS : CACHE_MS)) return cached;
        try {
            String json = new String(RadioUiCatalog.downloadDirect(URL, CATALOG_LIMIT, 15000), "UTF-8");
            Snapshot next = parse(json, false);
            preferences(context).edit().putString("json", json).apply();
            return cached = next;
        } catch (Exception error) {
            AppLog.w("PluginRelease", "Catalog refresh failed; using saved catalog", error);
            return cached = cached == null ? loadStored(context) : new Snapshot(cached.release, cached.plugins, true);
        }
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("plugin-release-catalog", Context.MODE_PRIVATE);
    }

    static Snapshot loadStored(Context context) throws Exception {
        try {
            String json = preferences(context).getString("json", "");
            if (!json.isEmpty()) return parse(json, true);
        } catch (Exception error) {
            AppLog.w("PluginRelease", "Saved catalog is unreadable", error);
        }
        try (InputStream input = context.getAssets().open("plugins/catalog.json")) {
            return parse(new String(RadioUiCatalog.read(input, CATALOG_LIMIT), "UTF-8"), true);
        }
    }

    private static Snapshot parse(String json, boolean saved) throws Exception {
        JSONObject catalog = new JSONObject(json);
        String tag = catalog.getString("release");
        JSONArray plugins = catalog.getJSONArray("plugins");
        if (catalog.getInt("schema") != 1 || !tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+")
                || plugins.length() == 0 || plugins.length() > 64)
            throw new IOException("在线插件列表格式无效");
        HashSet<String> ids = new HashSet<>();
        for (int i = 0; i < plugins.length(); i++) {
            JSONObject plugin = plugins.getJSONObject(i);
            String id = plugin.getString("id"), kind = plugin.getString("kind");
            String name = plugin.getString("name");
            JSONArray assets = plugin.getJSONArray("artifacts");
            if (!id.matches("[a-z0-9][a-z0-9-]{0,39}") || !ids.add(id)
                    || name.trim().isEmpty() || name.length() > 80 || assets.length() > 8
                    || !validKind(id, kind)) throw new IOException("在线插件条目无效");
            for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.getJSONObject(j);
                String file = asset.getString("file");
                int bytes = asset.getInt("bytes");
                if (!file.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,79}\\.(jar|js)")
                        || file.contains("..") || !asset.getString("sha256").matches("[0-9a-f]{64}")
                        || asset.getString("version").length() > 64 || asset.getInt("minSdk") < 14
                        || !asset.getString("url").equals(RELEASES + "download/" + tag + "/" + file)
                        || bytes < 1 || bytes > ("ku9-js".equals(kind) ? SCRIPT_LIMIT : InstalledPlugins.LIMIT)
                        || ("ku9-js".equals(kind) != file.endsWith(".js")))
                    throw new IOException("在线插件安装包信息无效");
                JSONArray abis = asset.getJSONArray("abis");
                if (abis.length() > 4 || ("python".equals(kind) && abis.length() == 0))
                    throw new IOException("在线插件架构无效");
            }
        }
        return new Snapshot(tag, plugins, saved);
    }

    private static boolean validKind(String id, String kind) {
        if ("channel".equals(kind)) return InstalledPlugins.isChannel(id);
        if ("tvbus".equals(kind) || "python".equals(kind)) return kind.equals(id);
        return "ku9-js".equals(kind) || "radio-ui".equals(kind)
                || "tv-ui".equals(kind) || "archive".equals(kind);
    }

    static final class Snapshot {
        final String release;
        final JSONArray plugins;
        final boolean saved;
        final long loadedAt = SystemClock.elapsedRealtime();
        Snapshot(String release, JSONArray plugins, boolean saved) {
            this.release = release;
            this.plugins = plugins;
            this.saved = saved;
        }
    }

    private PluginCatalog() { }
}
