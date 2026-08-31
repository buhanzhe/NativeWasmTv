package xiao.bu.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class PlaylistManager {
    private static final String TAG = "PlaylistManager";
    private static final String PREFS = "management";
    private static final String PLAYLIST_URL = "playlist_url";
    private static final String PLAYLIST_SOURCES = "playlist_sources_v1";
    private static final String DISABLED_GROUPS = "disabled_playlist_groups_v1";
    private static final String EMBEDDED_EPG_URL = "embedded_epg_url";
    private static final String LEGACY_CACHE_FILE = "online-playlist.txt";
    private static final String CACHE_PREFIX = "online-playlist-";
    private static final String MOBILE_MERGED_FILE = "mobile-merged-playlist.m3u";
    private static final String IMPORT_DIRECTORY = "imported-playlists";
    private static final String BUILT_IN_PLAYLIST = "builtin_channels.txt";
    private static final String RECOMMENDED_LIVE_TV_PROXY_URL =
            "https://gh-proxy.com/raw.githubusercontent.com/vbskycn/iptv/refs/heads/master/tv/iptv4.txt";
    private static final String RECOMMENDED_LIVE_TV_RAW_URL =
            "https://raw.githubusercontent.com/vbskycn/iptv/refs/heads/master/tv/iptv4.txt";
    private static final int MAX_SOURCES = 20;
    private static final int PLAYLIST_READ_TIMEOUT_MS = 60000;
    private static final int MIN_PLAYLIST_BYTES = 8 * 1024 * 1024;
    private static final int MAX_PLAYLIST_BYTES = 64 * 1024 * 1024;
    private static final Pattern CCTV_NAME = Pattern.compile("^cctv([0-9]+)(\\+?)(.*)$");
    private static final Pattern AES_COOKIE_CHALLENGE = Pattern.compile(
            "a\\s*=\\s*toNumbers\\(\\\"([0-9a-fA-F]+)\\\"\\)\\s*,\\s*"
                    + "b\\s*=\\s*toNumbers\\(\\\"([0-9a-fA-F]+)\\\"\\)\\s*,\\s*"
                    + "c\\s*=\\s*toNumbers\\(\\\"([0-9a-fA-F]+)\\\"\\)");
    private static final Pattern SCRIPT_LOCATION = Pattern.compile(
            "location\\.href\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']");

    private final Context context;
    private final SharedPreferences preferences;
    private final ChannelCatalogStore catalogStore;
    private ChannelCatalog.Group[] availableGroups = new ChannelCatalog.Group[0];
    private volatile ChannelCatalog.Group[] builtInGroups;
    private volatile ChannelCatalog.Group[] catalogMemoryCache;

    PlaylistManager(Context context) {
        this.context = context.getApplicationContext();
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        catalogStore = new ChannelCatalogStore(this.context);
    }

    static String getRecommendedUrl() {
        return getRecommendedWebViewUrl();
    }

    static JSONArray getRecommendedSourcesJson() throws JSONException {
        return new JSONArray()
                .put(new JSONObject()
                        .put("name", "网址导航")
                        .put("url", getRecommendedJoyUrl()))
                .put(new JSONObject()
                        .put("name", "网页电视台")
                        .put("url", getRecommendedWebViewUrl()))
                .put(new JSONObject()
                        .put("name", "直播电视网资源")
                        .put("url", getRecommendedLiveTvUrl()));
    }

    private static String getRecommendedJoyUrl() {
        return GithubProxy.apply(BuildConfig.RECOMMENDED_JOY_SOURCE_URL);
    }

    private static String getRecommendedWebViewUrl() {
        return GithubProxy.apply(BuildConfig.RECOMMENDED_WEBVIEW_SOURCE_URL);
    }

    private static String getRecommendedLiveTvUrl() {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1
                ? GithubProxy.apply(RECOMMENDED_LIVE_TV_RAW_URL)
                : RECOMMENDED_LIVE_TV_PROXY_URL;
    }

    String getPlaylistUrl() {
        List<Source> sources = getSources();
        return sources.isEmpty() ? "" : sources.get(0).location;
    }

    JSONArray getSourcesJson() {
        JSONArray result = new JSONArray();
        for (Source source : getSources()) {
            result.put(source.toJson());
        }
        return result;
    }

    synchronized JSONArray getGroupSettingsJson() {
        Set<String> disabled = getDisabledGroups();
        JSONArray result = new JSONArray();
        for (ChannelCatalog.Group group : availableGroups) {
            try {
                result.put(new JSONObject()
                        .put("name", group.title)
                        .put("channelCount", group.channels.length)
                        .put("enabled", !disabled.contains(groupKey(group.title))));
            } catch (JSONException impossible) {
            }
        }
        return result;
    }

    synchronized ChannelCatalog.Group[] updateGroupStates(JSONArray states)
            throws IOException {
        if (states == null) {
            throw new IOException("频道分组设置无效");
        }
        Set<String> available = new HashSet<String>();
        for (ChannelCatalog.Group group : availableGroups) {
            available.add(groupKey(group.title));
        }
        Set<String> disabled = getDisabledGroups();
        for (int index = 0; index < states.length(); index++) {
            JSONObject state = states.optJSONObject(index);
            if (state == null) {
                continue;
            }
            String key = groupKey(state.optString("name", ""));
            if (!available.contains(key)) {
                continue;
            }
            if (state.optBoolean("enabled", true)) {
                disabled.remove(key);
            } else {
                disabled.add(key);
            }
        }
        ChannelCatalog.Group[] visible = filterGroups(availableGroups, disabled);
        if (availableGroups.length > 0 && visible.length == 0) {
            throw new IOException("至少保留一个频道分组");
        }
        saveDisabledGroups(disabled);
        return visible;
    }

    boolean hasEnabledExternalLocalSource() {
        for (Source source : getSources()) {
            if (source.enabled && requiresExternalPermission(source.location)) {
                return true;
            }
        }
        return false;
    }

    boolean requiresExternalPermission(String location) {
        if (!isLocalLocation(location)) {
            return false;
        }
        String path = localFilePath(location);
        if (path == null) {
            return true;
        }
        try {
            String filesRoot = context.getFilesDir().getCanonicalPath() + File.separator;
            return !new File(path).getCanonicalPath().startsWith(filesRoot);
        } catch (IOException ignored) {
            return true;
        }
    }

    ImportedFile importLocalPlaylist(String sourceId, String fileName, byte[] bytes)
            throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("所选频道源文件为空");
        }
        String safeId = sanitizeId(sourceId);
        if (safeId.length() == 0) {
            safeId = "source_" + Long.toHexString(System.currentTimeMillis());
        }
        File directory = new File(context.getFilesDir(), IMPORT_DIRECTORY);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("无法创建本地频道源目录");
        }
        File target = new File(directory, safeId + ".playlist");
        File temporary = new File(directory, safeId + ".tmp");
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(temporary, false);
            output.write(bytes);
            output.flush();
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (IOException ignored) {
                }
            }
        }
        if (target.exists() && !target.delete()) {
            temporary.delete();
            throw new IOException("无法替换本地频道源文件");
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("无法保存本地频道源文件");
        }
        String displayName = fileName == null ? "" : fileName.trim();
        if (displayName.length() == 0) {
            displayName = "本地频道源";
        }
        return new ImportedFile(displayName, Uri.fromFile(target).toString());
    }

    static final class ImportedFile {
        final String displayName;
        final String location;

        ImportedFile(String displayName, String location) {
            this.displayName = displayName;
            this.location = location;
        }
    }

    String getEmbeddedEpgUrl() {
        return preferences.getString(EMBEDDED_EPG_URL, "");
    }

    boolean hasMobileMerge() {
        return new File(context.getFilesDir(), MOBILE_MERGED_FILE).isFile();
    }

    boolean hasCatalogSnapshot() {
        ChannelCatalog.Group[] memory = catalogMemoryCache;
        return memory != null && memory.length > 0 || catalogStore.hasCatalog();
    }

    ChannelCatalog.Group[] loadCached() {
        ChannelCatalog.Group[] memory = catalogMemoryCache;
        if (memory != null) {
            return rememberAndFilterGroups(memory);
        }
        ChannelCatalog.Group[] stored = catalogStore.load();
        if (stored != null && stored.length > 0) {
            catalogMemoryCache = stored;
            return rememberAndFilterGroups(stored);
        }
        List<Source> sources = getSources();
        List<ChannelCatalog.Group[]> loaded = new ArrayList<ChannelCatalog.Group[]>();
        String embeddedEpg = "";
        appendBuiltInGroups(loaded);
        byte[] mobileMerged = readMobileMerged();
        if (mobileMerged != null && !sources.isEmpty()) {
            try {
                loaded.add(parse(mobileMerged));
                embeddedEpg = discoverEpgUrl(mobileMerged);
                rememberEmbeddedEpgUrl(embeddedEpg);
                return persistAndFilterGroups(merge(loaded));
            } catch (IOException ignored) {
                context.deleteFile(MOBILE_MERGED_FILE);
            }
        }
        for (Source source : sources) {
            if (!source.enabled) {
                continue;
            }
            try {
                byte[] bytes = readSourceForStartup(source);
                if (bytes == null) {
                    continue;
                }
                loaded.add(parse(bytes));
                if (embeddedEpg.length() == 0) {
                    embeddedEpg = discoverEpgUrl(bytes);
                }
            } catch (IOException ignored) {
            }
        }
        rememberEmbeddedEpgUrl(embeddedEpg);
        return persistAndFilterGroups(merge(loaded));
    }

    ChannelCatalog.Group[] loadBuiltIn() {
        ChannelCatalog.Group[] cached = builtInGroups;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (builtInGroups != null) {
                return builtInGroups;
            }
            InputStream input = null;
            try {
                input = context.getAssets().open(BUILT_IN_PLAYLIST);
                builtInGroups = parse(readAll(input));
            } catch (IOException ignored) {
                builtInGroups = new ChannelCatalog.Group[0];
            } finally {
                if (input != null) {
                    try {
                        input.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            return builtInGroups;
        }
    }

    ChannelCatalog.Group[] loadStartupGroups(ChannelCatalog.Group preferredGroup) {
        ChannelCatalog.Group[] builtIn = loadBuiltIn();
        if (preferredGroup == null || preferredGroup.channels.length == 0) {
            return builtIn;
        }
        List<ChannelCatalog.Group[]> sources = new ArrayList<ChannelCatalog.Group[]>();
        sources.add(new ChannelCatalog.Group[] { preferredGroup });
        if (builtIn.length > 0) {
            sources.add(builtIn);
        }
        return merge(sources);
    }

    private void appendBuiltInGroups(List<ChannelCatalog.Group[]> loaded) {
        ChannelCatalog.Group[] groups = loadBuiltIn();
        if (groups.length > 0) {
            loaded.add(groups);
        }
    }

    UpdateResult updateSources(JSONArray input) throws IOException, JSONException {
        List<Source> sources = parseSources(input);
        List<Source> previousSources = getSources();
        saveSources(sources);
        deleteRemovedCaches(previousSources, sources);
        List<LoadedSource> fetched = new ArrayList<LoadedSource>();
        List<String> warnings = new ArrayList<String>();
        int enabledCount = 0;
        int availableCount = 0;
        for (Source source : sources) {
            if (!source.enabled) {
                continue;
            }
            enabledCount++;
            byte[] bytes = null;
            try {
                bytes = readSource(source);
                if (!isLocalLocation(source.location)) {
                    writeCache(source, bytes);
                }
            } catch (IOException error) {
                if (!isLocalLocation(source.location)) {
                    try {
                        bytes = readCache(source);
                        warnings.add(source.name + " 更新失败，已使用缓存");
                    } catch (IOException cacheError) {
                        warnings.add(source.name + "：" + error.getMessage());
                    }
                } else {
                    warnings.add(source.name + "：" + error.getMessage());
                }
            }
            if (bytes != null && bytes.length > 0) {
                availableCount++;
            }
            fetched.add(new LoadedSource(source, bytes));
        }
        if (enabledCount > 0 && availableCount < enabledCount) {
            String detail = warnings.isEmpty() ? "部分频道源无法读取" : warnings.get(0);
            throw new IOException("频道列表刷新未完成，已保留上次频道列表：" + detail);
        }
        String fingerprint = catalogFingerprint("device", sources, fetched, null);
        ChannelCatalog.Group[] unchanged = unchangedCatalog(fingerprint);
        if (unchanged != null) {
            rememberEmbeddedEpgUrl(discoverFirstEpgUrl(fetched));
            context.deleteFile(MOBILE_MERGED_FILE);
            return new UpdateResult(rememberAndFilterGroups(unchanged),
                    enabledCount, warnings);
        }
        List<ChannelCatalog.Group[]> loaded = new ArrayList<ChannelCatalog.Group[]>();
        appendBuiltInGroups(loaded);
        int externalChannelCount = 0;
        for (LoadedSource source : fetched) {
            if (source.bytes != null) {
                ChannelCatalog.Group[] parsed = parse(source.bytes);
                for (ChannelCatalog.Group group : parsed) {
                    externalChannelCount += group.channels.length;
                }
                if (parsed.length > 0) {
                    loaded.add(parsed);
                }
            }
        }
        if (enabledCount > 0 && externalChannelCount == 0) {
            throw new IOException("已启用的频道源中没有可用频道，已保留上次频道列表");
        }
        String embeddedEpg = discoverFirstEpgUrl(fetched);
        rememberEmbeddedEpgUrl(embeddedEpg);
        ChannelCatalog.Group[] mergedGroups = merge(loaded);
        ChannelCatalog.Group[] groups = persistAndFilterGroups(mergedGroups, fingerprint);
        context.deleteFile(MOBILE_MERGED_FILE);
        return new UpdateResult(groups, enabledCount, warnings);
    }

    UpdateResult applyMobileMerge(JSONArray input, byte[] playlist)
            throws IOException, JSONException {
        if (playlist == null || playlist.length == 0) {
            throw new IOException("手机合并后的频道配置为空");
        }
        List<Source> sources = parseSources(input);
        int enabledCount = 0;
        for (Source source : sources) {
            if (source.enabled) {
                enabledCount++;
            }
        }
        List<Source> previousSources = getSources();
        saveSources(sources);
        deleteRemovedCaches(previousSources, sources);
        writeMobileMerged(playlist);
        rememberEmbeddedEpgUrl(discoverEpgUrl(playlist));
        String fingerprint = catalogFingerprint("mobile", sources,
                new ArrayList<LoadedSource>(), playlist);
        ChannelCatalog.Group[] unchanged = unchangedCatalog(fingerprint);
        if (unchanged != null) {
            return new UpdateResult(rememberAndFilterGroups(unchanged),
                    enabledCount, new ArrayList<String>());
        }
        ChannelCatalog.Group[] mobileGroups = parse(playlist);
        List<ChannelCatalog.Group[]> loaded = new ArrayList<ChannelCatalog.Group[]>();
        appendBuiltInGroups(loaded);
        loaded.add(mobileGroups);
        ChannelCatalog.Group[] groups = persistAndFilterGroups(
                merge(loaded), fingerprint);
        return new UpdateResult(groups, enabledCount, new ArrayList<String>());
    }

    byte[] readForMobile(String location) throws IOException {
        String value = location == null ? "" : location.trim();
        if (value.length() == 0 || !isSupportedLocation(value)) {
            throw new IOException("频道源地址无效");
        }
        return isLocalLocation(value) ? readLocal(value) : download(value);
    }

    private synchronized ChannelCatalog.Group[] rememberAndFilterGroups(
            ChannelCatalog.Group[] groups) {
        catalogMemoryCache = groups;
        availableGroups = groups;
        Set<String> disabled = getDisabledGroups();
        ChannelCatalog.Group[] visible = filterGroups(groups, disabled);
        if (visible.length == 0 && groups.length > 0) {
            disabled.remove(groupKey(groups[0].title));
            saveDisabledGroups(disabled);
            visible = filterGroups(groups, disabled);
        }
        return visible;
    }

    private ChannelCatalog.Group[] persistAndFilterGroups(ChannelCatalog.Group[] groups) {
        if (!catalogStore.replace(groups, null)) {
            Log.w(TAG, "Unable to persist startup channel catalog; using memory snapshot");
        }
        return rememberAndFilterGroups(groups);
    }

    private ChannelCatalog.Group[] persistAndFilterGroups(ChannelCatalog.Group[] groups,
            String fingerprint) throws IOException {
        if (!catalogStore.replace(groups, fingerprint)) {
            throw new IOException("频道列表保存失败，已保留上次频道列表");
        }
        return rememberAndFilterGroups(groups);
    }

    private static ChannelCatalog.Group[] filterGroups(ChannelCatalog.Group[] groups,
            Set<String> disabled) {
        List<ChannelCatalog.Group> visible = new ArrayList<ChannelCatalog.Group>();
        for (ChannelCatalog.Group group : groups) {
            if (!disabled.contains(groupKey(group.title))) {
                visible.add(group);
            }
        }
        return visible.toArray(new ChannelCatalog.Group[visible.size()]);
    }

    private Set<String> getDisabledGroups() {
        Set<String> result = new HashSet<String>();
        String saved = preferences.getString(DISABLED_GROUPS, "");
        if (saved.length() == 0) {
            return result;
        }
        try {
            JSONArray values = new JSONArray(saved);
            for (int index = 0; index < values.length(); index++) {
                String key = groupKey(values.optString(index, ""));
                if (key.length() > 0) {
                    result.add(key);
                }
            }
        } catch (JSONException ignored) {
        }
        return result;
    }

    private void saveDisabledGroups(Set<String> disabled) {
        JSONArray values = new JSONArray();
        for (String key : disabled) {
            values.put(key);
        }
        // Group switches are saved from the control server thread. Commit before
        // replying so an immediate app restart cannot lose the user's selection.
        //noinspection ApplySharedPref
        preferences.edit().putString(DISABLED_GROUPS, values.toString()).commit();
    }

    private static String groupKey(String title) {
        String value = title == null ? "" : title.trim();
        return value.length() == 0 ? ""
                : normalizeGroupTitle(value).toLowerCase(Locale.US);
    }

    ChannelCatalog.Group[] downloadAndSave(String sourceUrl) throws IOException {
        JSONArray sources = new JSONArray();
        String normalized = sourceUrl == null ? "" : sourceUrl.trim();
        try {
            if (normalized.length() > 0) {
                sources.put(new JSONObject().put("id", "legacy").put("name", "频道源 1")
                        .put("location", normalized).put("enabled", true));
            }
            return updateSources(sources).groups;
        } catch (JSONException error) {
            throw new IOException(error.getMessage());
        }
    }

    static boolean isLocalLocation(String location) {
        if (location == null) {
            return false;
        }
        String value = location.trim().toLowerCase(Locale.US);
        return value.startsWith("file://") || value.startsWith("content://")
                || value.startsWith("/");
    }

    private List<Source> getSources() {
        String saved = preferences.getString(PLAYLIST_SOURCES, "");
        if (saved.length() > 0) {
            try {
                return parseSources(new JSONArray(saved));
            } catch (Exception ignored) {
            }
        }
        String legacy = preferences.getString(PLAYLIST_URL, "").trim();
        List<Source> migrated = new ArrayList<Source>();
        if (legacy.length() > 0) {
            migrated.add(new Source("legacy", "频道源 1", legacy, true));
            saveSources(migrated);
        }
        return migrated;
    }

    private List<Source> parseSources(JSONArray input) throws JSONException {
        if (input == null) {
            throw new JSONException("频道源配置不能为空");
        }
        if (input.length() > MAX_SOURCES) {
            throw new JSONException("最多可添加 " + MAX_SOURCES + " 个频道源");
        }
        List<Source> result = new ArrayList<Source>();
        Set<String> usedIds = new HashSet<String>();
        for (int index = 0; index < input.length(); index++) {
            JSONObject item = input.optJSONObject(index);
            if (item == null) {
                continue;
            }
            String location = item.optString("location",
                    item.optString("url", "")).trim();
            String name = item.optString("name", "").trim();
            if (location.length() == 0 && name.length() == 0) {
                continue;
            }
            if (location.length() == 0) {
                throw new JSONException("“" + name + "”缺少频道源地址或本地路径");
            }
            if (!isSupportedLocation(location)) {
                throw new JSONException("“" + (name.length() == 0 ? "频道源" : name)
                        + "”仅支持 HTTP(S)、file://、content:// 或绝对路径");
            }
            if (name.length() == 0) {
                name = "频道源 " + (result.size() + 1);
            }
            String id = sanitizeId(item.optString("id", ""));
            if (id.length() == 0 || usedIds.contains(id)) {
                id = newSourceId(index, usedIds);
            }
            usedIds.add(id);
            result.add(new Source(id, name, location, item.optBoolean("enabled", true)));
        }
        return result;
    }

    private void saveSources(List<Source> sources) {
        JSONArray json = new JSONArray();
        for (Source source : sources) {
            json.put(source.toJson());
        }
        SharedPreferences.Editor editor = preferences.edit()
                .putString(PLAYLIST_SOURCES, json.toString());
        if (sources.isEmpty()) {
            editor.remove(PLAYLIST_URL);
        } else {
            editor.putString(PLAYLIST_URL, sources.get(0).location);
        }
        editor.apply();
    }

    private void deleteRemovedCaches(List<Source> previous, List<Source> current) {
        Set<String> retained = new HashSet<String>();
        for (Source source : current) {
            retained.add(source.id);
        }
        for (Source source : previous) {
            if (!retained.contains(source.id)) {
                context.deleteFile(cacheFile(source));
                if ("legacy".equals(source.id)) {
                    context.deleteFile(LEGACY_CACHE_FILE);
                }
            }
        }
    }

    private byte[] readSourceForStartup(Source source) throws IOException {
        if (isLocalLocation(source.location)) {
            return readLocal(source.location);
        }
        return readCache(source);
    }

    private byte[] readSource(Source source) throws IOException {
        return isLocalLocation(source.location) ? readLocal(source.location)
                : download(source.location);
    }

    private byte[] readLocal(String location) throws IOException {
        InputStream input = null;
        try {
            if (location.toLowerCase(Locale.US).startsWith("content://")) {
                input = context.getContentResolver().openInputStream(Uri.parse(location));
            } else {
                String path = location;
                if (location.toLowerCase(Locale.US).startsWith("file://")) {
                    path = Uri.parse(location).getPath();
                }
                if (path == null || path.length() == 0) {
                    throw new IOException("本地文件路径无效");
                }
                input = new FileInputStream(new File(path));
            }
            if (input == null) {
                throw new IOException("无法读取本地频道源");
            }
            return readAll(input);
        } catch (SecurityException error) {
            throw new IOException("没有本地文件读取权限");
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String localFilePath(String location) {
        if (location == null) {
            return null;
        }
        if (location.toLowerCase(Locale.US).startsWith("content://")) {
            return null;
        }
        if (location.toLowerCase(Locale.US).startsWith("file://")) {
            return Uri.parse(location).getPath();
        }
        return location.startsWith("/") ? location : null;
    }

    private byte[] download(String sourceUrl) throws IOException {
        return download(sourceUrl, null, null, 0);
    }

    private byte[] download(String sourceUrl, String cookie, String referer,
            int challengeCount) throws IOException {
        URL url = new URL(sourceUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(PLAYLIST_READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "nTv/1.5");
        if (cookie != null) {
            connection.setRequestProperty("Cookie", cookie);
        }
        if (referer != null) {
            connection.setRequestProperty("Referer", referer);
        }
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("下载失败：HTTP " + status);
            }
            byte[] body = readAll(connection.getInputStream());
            AesCookieChallenge challenge = parseAesCookieChallenge(body, url);
            if (challenge == null) {
                return body;
            }
            if (challengeCount >= 2) {
                throw new IOException("频道源的网页验证未通过");
            }
            return download(challenge.location, "__test=" + challenge.cookie,
                    sourceUrl, challengeCount + 1);
        } finally {
            connection.disconnect();
        }
    }

    private static AesCookieChallenge parseAesCookieChallenge(byte[] body, URL baseUrl)
            throws IOException {
        String html = new String(body, "UTF-8");
        if (html.indexOf("slowAES.decrypt") < 0 || html.indexOf("__test") < 0) {
            return null;
        }
        Matcher values = AES_COOKIE_CHALLENGE.matcher(html);
        Matcher location = SCRIPT_LOCATION.matcher(html);
        if (!values.find() || !location.find()) {
            throw new IOException("无法解析频道源的网页验证");
        }
        try {
            byte[] key = decodeHex(values.group(1));
            byte[] iv = decodeHex(values.group(2));
            byte[] encrypted = decodeHex(values.group(3));
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(iv));
            String cookie = encodeHex(cipher.doFinal(encrypted));
            String target = new URL(baseUrl, location.group(1)).toString();
            return new AesCookieChallenge(cookie, target);
        } catch (Exception error) {
            throw new IOException("无法完成频道源的网页验证", error);
        }
    }

    private static byte[] decodeHex(String value) throws IOException {
        if ((value.length() & 1) != 0) {
            throw new IOException("网页验证数据无效");
        }
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < result.length; index++) {
            int high = Character.digit(value.charAt(index * 2), 16);
            int low = Character.digit(value.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IOException("网页验证数据无效");
            }
            result[index] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private static String encodeHex(byte[] value) {
        final char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[value.length * 2];
        for (int index = 0; index < value.length; index++) {
            int current = value[index] & 0xff;
            result[index * 2] = digits[current >>> 4];
            result[index * 2 + 1] = digits[current & 0x0f];
        }
        return new String(result);
    }

    private byte[] readCache(Source source) throws IOException {
        InputStream input = null;
        try {
            String filename = cacheFile(source);
            try {
                input = context.openFileInput(filename);
            } catch (IOException error) {
                if (!"legacy".equals(source.id)) {
                    throw error;
                }
                input = context.openFileInput(LEGACY_CACHE_FILE);
            }
            return readAll(input);
        } finally {
            if (input != null) {
                input.close();
            }
        }
    }

    private void writeCache(Source source, byte[] bytes) throws IOException {
        FileOutputStream output = context.openFileOutput(cacheFile(source), Context.MODE_PRIVATE);
        try {
            output.write(bytes);
        } finally {
            output.close();
        }
    }

    private byte[] readMobileMerged() {
        InputStream input = null;
        try {
            input = context.openFileInput(MOBILE_MERGED_FILE);
            return readAll(input);
        } catch (IOException ignored) {
            return null;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void writeMobileMerged(byte[] bytes) throws IOException {
        FileOutputStream output = context.openFileOutput(
                MOBILE_MERGED_FILE, Context.MODE_PRIVATE);
        try {
            output.write(bytes);
            output.flush();
        } finally {
            output.close();
        }
    }

    private static String cacheFile(Source source) {
        return CACHE_PREFIX + source.id + ".txt";
    }

    private void rememberEmbeddedEpgUrl(String discovered) {
        SharedPreferences.Editor editor = preferences.edit();
        if (discovered == null || discovered.length() == 0) {
            editor.remove(EMBEDDED_EPG_URL);
        } else {
            editor.putString(EMBEDDED_EPG_URL, discovered);
        }
        editor.apply();
    }

    private static String discoverEpgUrl(byte[] bytes) {
        String text = decode(bytes);
        int end = text.indexOf('\n');
        String header = end >= 0 ? text.substring(0, end) : text;
        if (!header.startsWith("#EXTM3U")) {
            return "";
        }
        String value = attribute(header, "x-tvg-url");
        if (value == null || value.trim().length() == 0) {
            value = attribute(header, "url-tvg");
        }
        return value == null ? "" : value.trim();
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() > playlistByteLimit() - count) {
                throw new IOException("频道文件超过设备可安全处理的大小");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static int playlistByteLimit() {
        long heapBudget = Runtime.getRuntime().maxMemory() / 4L;
        return (int) Math.max(MIN_PLAYLIST_BYTES,
                Math.min(MAX_PLAYLIST_BYTES, heapBudget));
    }

    private static ChannelCatalog.Group[] merge(List<ChannelCatalog.Group[]> sourceGroups) {
        Map<String, ChannelBucket> merged = new LinkedHashMap<String, ChannelBucket>();
        for (ChannelCatalog.Group[] groups : sourceGroups) {
            for (ChannelCatalog.Group group : groups) {
                String groupTitle = normalizeGroupTitle(group.title);
                ChannelBucket bucket = merged.get(groupTitle);
                if (bucket == null) {
                    bucket = new ChannelBucket();
                    merged.put(groupTitle, bucket);
                }
                for (Channel incoming : group.channels) {
                    bucket.add(incoming);
                }
            }
        }
        ChannelCatalog.Group[] result = new ChannelCatalog.Group[merged.size()];
        int index = 0;
        for (Map.Entry<String, ChannelBucket> entry : merged.entrySet()) {
            List<Channel> channels = entry.getValue().channels;
            result[index++] = new ChannelCatalog.Group(entry.getKey(),
                    ChannelCatalog.SOURCE_CUSTOM,
                    channels.toArray(new Channel[channels.size()]));
        }
        return result;
    }

    private ChannelCatalog.Group[] unchangedCatalog(String fingerprint) {
        if (!catalogStore.hasFingerprint(fingerprint)) {
            return null;
        }
        ChannelCatalog.Group[] memory = catalogMemoryCache;
        if (memory != null && memory.length > 0) {
            return memory;
        }
        ChannelCatalog.Group[] stored = catalogStore.load();
        if (stored != null && stored.length > 0) {
            catalogMemoryCache = stored;
            return stored;
        }
        return null;
    }

    private static String discoverFirstEpgUrl(List<LoadedSource> sources) {
        for (LoadedSource source : sources) {
            if (source.bytes == null) {
                continue;
            }
            String discovered = discoverEpgUrl(source.bytes);
            if (discovered.length() > 0) {
                return discovered;
            }
        }
        return "";
    }

    private static String catalogFingerprint(String mode, List<Source> sources,
            List<LoadedSource> loaded, byte[] mergedPlaylist) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("设备不支持频道缓存校验", impossible);
        }
        updateDigest(digest, "ntv-catalog-v1");
        updateDigest(digest, mode);
        for (Source source : sources) {
            updateDigest(digest, source.id);
            updateDigest(digest, source.name);
            updateDigest(digest, source.location);
            updateDigest(digest, source.enabled ? "1" : "0");
        }
        for (LoadedSource source : loaded) {
            updateDigest(digest, source.source.id);
            updateDigest(digest, source.bytes);
        }
        updateDigest(digest, mergedPlaylist);
        return encodeHex(digest.digest());
    }

    private static void updateDigest(MessageDigest digest, String value)
            throws IOException {
        updateDigest(digest, value == null ? null : value.getBytes("UTF-8"));
    }

    private static void updateDigest(MessageDigest digest, byte[] value) {
        int length = value == null ? -1 : value.length;
        digest.update((byte) (length >>> 24));
        digest.update((byte) (length >>> 16));
        digest.update((byte) (length >>> 8));
        digest.update((byte) length);
        if (value != null) {
            digest.update(value);
        }
    }

    private static String canonicalChannelName(String name) {
        String value = canonicalText(name);
        Matcher cctv = CCTV_NAME.matcher(value);
        if (cctv.matches()) {
            String number = cctv.group(1);
            String plus = cctv.group(2);
            String suffix = cctv.group(3);
            String variant = "";
            if (suffix.indexOf("8k") >= 0
                    || ("8".equals(number) && suffix.startsWith("k"))) {
                variant = ":8k";
            } else if (suffix.indexOf("4k") >= 0
                    || ("4".equals(number) && suffix.startsWith("k"))) {
                variant = ":4k";
            } else if ("4".equals(number) && (suffix.indexOf("欧洲") >= 0
                    || suffix.indexOf("欧") >= 0)) {
                variant = ":europe";
            } else if ("4".equals(number) && (suffix.indexOf("美洲") >= 0
                    || suffix.indexOf("美") >= 0)) {
                variant = ":america";
            }
            return "cctv" + number + plus + variant;
        }
        return value.replace("蓝光", "").replace("超清", "")
                .replace("高清", "").replace("频道", "").replace("hd", "");
    }

    private static String canonicalText(String value) {
        if (value == null) {
            return "";
        }
        String lower = value.toLowerCase(Locale.US);
        StringBuilder result = new StringBuilder(lower.length());
        for (int index = 0; index < lower.length(); index++) {
            char current = lower.charAt(index);
            if ((current >= 'a' && current <= 'z')
                    || (current >= '0' && current <= '9') || current == '+'
                    || (current >= '\u4e00' && current <= '\u9fff')) {
                result.append(current);
            }
        }
        return result.toString();
    }

    private static String normalizeGroupTitle(String title) {
        String value = title == null ? "" : title.trim();
        boolean changed = true;
        while (changed && value.startsWith("在线")) {
            changed = false;
            String suffix = value.substring("在线".length()).trim();
            if (suffix.startsWith("·") || suffix.startsWith("・")
                    || suffix.startsWith("-") || suffix.startsWith("－")
                    || suffix.startsWith("—") || suffix.startsWith(":" )
                    || suffix.startsWith("：")) {
                value = suffix.substring(1).trim();
                changed = true;
            }
        }
        return value.length() == 0 ? "在线频道" : value;
    }

    private static ChannelCatalog.Group[] parse(byte[] bytes) throws IOException {
        String text = decode(bytes);
        Map<String, ChannelBucket> groups = new LinkedHashMap<String, ChannelBucket>();
        String currentGroup = "在线频道";
        String pendingName = null;
        String pendingGroup = null;
        String pendingEpgId = null;
        int count = 0;
        int lineStart = 0;
        while (lineStart <= text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            int contentEnd = lineEnd;
            if (contentEnd > lineStart && text.charAt(contentEnd - 1) == '\r') {
                contentEnd--;
            }
            String line = text.substring(lineStart, contentEnd).trim();
            lineStart = lineEnd + 1;
            if (line.length() == 0) {
                if (lineEnd == text.length()) {
                    break;
                }
                continue;
            }
            if (line.startsWith("#EXTINF:")) {
                pendingName = attribute(line, "tvg-name");
                pendingGroup = attribute(line, "group-title");
                pendingEpgId = attribute(line, "tvg-id");
                int comma = line.lastIndexOf(',');
                if (comma >= 0 && comma + 1 < line.length()) {
                    pendingName = line.substring(comma + 1).trim();
                }
                if (lineEnd == text.length()) {
                    break;
                }
                continue;
            }
            if (line.startsWith("#")) {
                if (lineEnd == text.length()) {
                    break;
                }
                continue;
            }
            if (pendingName != null && isStreamUrl(line)) {
                String groupName = emptyToDefault(pendingGroup, currentGroup);
                add(groups, groupName, pendingName, line, pendingEpgId, count++);
                pendingName = null;
                pendingGroup = null;
                pendingEpgId = null;
            } else {
                int comma = line.indexOf(',');
                if (comma <= 0 || comma + 1 >= line.length()) {
                    continue;
                }
                String name = line.substring(0, comma).trim();
                String value = line.substring(comma + 1).trim();
                if ("#genre#".equalsIgnoreCase(value)) {
                    currentGroup = name.length() == 0 ? "在线频道" : name;
                } else if (isStreamUrl(value)) {
                    add(groups, currentGroup, name, value, null, count++);
                }
            }
            if (lineEnd == text.length()) {
                break;
            }
        }
        if (count == 0) {
            throw new IOException("频道源中没有找到可播放的 HTTP 或 WebView 地址");
        }
        ChannelCatalog.Group[] result = new ChannelCatalog.Group[groups.size()];
        int index = 0;
        for (Map.Entry<String, ChannelBucket> entry : groups.entrySet()) {
            List<Channel> channels = entry.getValue().channels;
            result[index++] = new ChannelCatalog.Group(
                    entry.getKey(),
                    ChannelCatalog.SOURCE_CUSTOM,
                    channels.toArray(new Channel[channels.size()]));
        }
        return result;
    }

    private static String decode(byte[] bytes) {
        int offset = bytes.length >= 3 && (bytes[0] & 0xff) == 0xef
                && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf ? 3 : 0;
        try {
            return new String(bytes, offset, bytes.length - offset, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            return new String(bytes, offset, bytes.length - offset);
        }
    }

    private static void add(Map<String, ChannelBucket> groups, String groupName,
            String name, String url, String epgId, int index) {
        String safeGroup = normalizeGroupTitle(groupName);
        ChannelBucket bucket = groups.get(safeGroup);
        if (bucket == null) {
            bucket = new ChannelBucket();
            groups.put(safeGroup, bucket);
        }
        String safeName = name == null || name.trim().length() == 0
                ? "频道 " + (index + 1) : name.trim();
        Channel incoming = new Channel(channelNumber(safeName, epgId,
                bucket.channels.size() + 1),
                safeName, "custom_" + index, url, null, null, null,
                epgId == null || epgId.trim().length() == 0 ? safeName : epgId.trim());
        bucket.add(incoming);
    }

    private static String channelNumber(String name, String epgId, int fallback) {
        String id = epgId == null ? "" : epgId.trim().toUpperCase(Locale.US);
        if (id.startsWith("CCTV") && id.length() > 4) {
            String suffix = id.substring(4);
            if (isChannelNumber(suffix)) {
                return suffix;
            }
        }
        if (name != null && name.startsWith("CCTV-")) {
            int end = name.indexOf(' ', 5);
            String suffix = end > 5 ? name.substring(5, end) : name.substring(5);
            if (isChannelNumber(suffix)) {
                return suffix;
            }
        }
        return String.valueOf(fallback);
    }

    private static boolean isChannelNumber(String value) {
        if (value == null || value.length() == 0) {
            return false;
        }
        int digits = value.endsWith("+") ? value.length() - 1 : value.length();
        if (digits == 0) {
            return false;
        }
        for (int index = 0; index < digits; index++) {
            char current = value.charAt(index);
            if (current < '0' || current > '9') {
                return false;
            }
        }
        return true;
    }

    private static final class ChannelBucket {
        final List<Channel> channels = new ArrayList<Channel>();
        private final Map<String, Integer> indexes = new HashMap<String, Integer>();

        void add(Channel incoming) {
            int existingIndex = find(incoming);
            if (existingIndex < 0) {
                existingIndex = channels.size();
                channels.add(incoming);
                index(incoming, existingIndex);
                return;
            }
            Channel existing = channels.get(existingIndex);
            for (String url : incoming.urls) {
                existing = existing.withAdditionalUrl(url);
            }
            channels.set(existingIndex, existing);
            index(existing, existingIndex);
        }

        private int find(Channel channel) {
            int result = Integer.MAX_VALUE;
            for (String key : keys(channel)) {
                Integer index = indexes.get(key);
                if (index != null && index < result) {
                    result = index;
                }
            }
            return result == Integer.MAX_VALUE ? -1 : result;
        }

        private void index(Channel channel, int position) {
            for (String key : keys(channel)) {
                Integer current = indexes.get(key);
                if (current == null || position < current) {
                    indexes.put(key, position);
                }
            }
        }

        private static List<String> keys(Channel channel) {
            List<String> keys = new ArrayList<String>(channel.urls.length + 3);
            keys.add("name:" + channel.name.toLowerCase(Locale.US));
            for (String url : channel.urls) {
                String canonical = Channel.canonicalSourceUrl(url);
                if (canonical.length() > 0) {
                    keys.add("url:" + canonical);
                }
            }
            String epg = canonicalText(channel.epgId);
            if (epg.length() > 0) {
                keys.add("epg:" + epg);
            }
            String canonicalName = canonicalChannelName(channel.name);
            if (canonicalName.length() > 0) {
                keys.add("channel:" + canonicalName);
            }
            return keys;
        }
    }

    private static final class LoadedSource {
        final Source source;
        final byte[] bytes;

        LoadedSource(Source source, byte[] bytes) {
            this.source = source;
            this.bytes = bytes;
        }
    }

    private static boolean isStreamUrl(String text) {
        String value = text.toLowerCase(Locale.US);
        return value.startsWith("http://") || value.startsWith("https://")
                || value.startsWith("rtmp://") || value.startsWith("rtmpt://")
                || value.startsWith("rtmps://")
                || value.startsWith("rtsp://")
                || value.startsWith("webview://http://")
                || value.startsWith("webview://https://");
    }

    private static boolean isSupportedLocation(String location) {
        String value = location.toLowerCase(Locale.US);
        return value.startsWith("http://") || value.startsWith("https://")
                || isLocalLocation(value);
    }

    private static String sanitizeId(String id) {
        if (id == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < id.length() && result.length() < 48; index++) {
            char value = id.charAt(index);
            if ((value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z')
                    || (value >= '0' && value <= '9') || value == '_' || value == '-') {
                result.append(value);
            }
        }
        return result.toString();
    }

    private static String newSourceId(int index, Set<String> usedIds) {
        String base = "source_" + Long.toHexString(System.currentTimeMillis()) + "_" + index;
        String candidate = base;
        int suffix = 1;
        while (usedIds.contains(candidate)) {
            candidate = base + "_" + suffix++;
        }
        return candidate;
    }

    private static String emptyToDefault(String value, String fallback) {
        return value == null || value.trim().length() == 0 ? fallback : value.trim();
    }

    private static String attribute(String line, String name) {
        String marker = name + "=\"";
        int start = line.indexOf(marker);
        if (start < 0) {
            return null;
        }
        start += marker.length();
        int end = line.indexOf('"', start);
        return end < 0 ? null : line.substring(start, end);
    }

    static final class Source {
        final String id;
        final String name;
        final String location;
        final boolean enabled;

        Source(String id, String name, String location, boolean enabled) {
            this.id = id;
            this.name = name;
            this.location = location;
            this.enabled = enabled;
        }

        JSONObject toJson() {
            JSONObject result = new JSONObject();
            try {
                result.put("id", id).put("name", name)
                        .put("location", location).put("enabled", enabled);
            } catch (JSONException impossible) {
            }
            return result;
        }
    }

    private static final class AesCookieChallenge {
        final String cookie;
        final String location;

        AesCookieChallenge(String cookie, String location) {
            this.cookie = cookie;
            this.location = location;
        }
    }

    static final class UpdateResult {
        final ChannelCatalog.Group[] groups;
        final int enabledCount;
        final List<String> warnings;

        UpdateResult(ChannelCatalog.Group[] groups, int enabledCount, List<String> warnings) {
            this.groups = groups;
            this.enabledCount = enabledCount;
            this.warnings = warnings;
        }
    }
}
