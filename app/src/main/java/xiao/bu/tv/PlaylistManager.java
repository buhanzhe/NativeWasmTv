package xiao.bu.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import xiao.bu.tv.AppLog;

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
import java.util.Arrays;
import java.util.Collections;
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
    private static final String COLLAPSED_SOURCES = "collapsed_playlist_sources_v1";
    private static final String CHANNEL_SETTINGS = "playlist_channel_settings_v1";
    private static final String MERGED_CHANNEL_SETTINGS = "merged_playlist_channel_settings_v1";
    private static final String DISABLED_MERGED_GROUPS = "disabled_merged_playlist_groups_v1";
    private static final String MERGED_GROUP_ORDER = "merged_playlist_group_order_v1";
    private static final String FAVORITES_GROUP_VISIBLE = "favorites_group_visible";
    private static final String EMBEDDED_EPG_URL = "embedded_epg_url";
    private static final String LEGACY_CACHE_FILE = "online-playlist.txt";
    private static final String CACHE_PREFIX = "online-playlist-";
    private static final String CACHE_METADATA_PREFIX = "playlist_cache_metadata_";
    private static final String MOBILE_CACHE_INDEX = "mobile_playlist_cache_index_v1";
    private static final String MOBILE_MERGED_FILE = "mobile-merged-playlist.m3u";
    private static final String IMPORT_DIRECTORY = "imported-playlists";
    private static final String BUILT_IN_PLAYLIST = "builtin_channels.txt";
    private static final int MAX_SOURCES = 20;
    private static final int PLAYLIST_READ_TIMEOUT_MS = 25000;
    private static final int MOBILE_READ_BUDGET_MS = 6000;
    private static final int MOBILE_ORIGIN_READ_BUDGET_MS = 25000;
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
    private ChannelCatalog.Group[] visibleGroups = new ChannelCatalog.Group[0];
    private ChannelCatalog.Group[] managementGroups = new ChannelCatalog.Group[0];
    private volatile ChannelCatalog.Group[] builtInGroups;
    private volatile ChannelCatalog.Group[] catalogMemoryCache;

    PlaylistManager(Context context) {
        this.context = context.getApplicationContext();
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        catalogStore = new ChannelCatalogStore(this.context);
    }

    String getRecommendedUrl() {
        return getRecommendedWebViewUrl();
    }

    JSONArray getRecommendedSourcesJson() throws JSONException {
        return new JSONArray()
                .put(new JSONObject()
                        .put("name", "网址导航")
                        .put("url", getRecommendedJoyUrl()))
                .put(new JSONObject()
                        .put("name", "电台资源")
                        .put("url", BuildConfig.RECOMMENDED_FM_SOURCE_URL))
                .put(new JSONObject()
                        .put("name", "网页测试")
                        .put("url", BuildConfig.RECOMMENDED_TEST_SOURCE_URL))
                .put(new JSONObject()
                        .put("name", "网页电视大全")
                        .put("url", getRecommendedWebViewUrl()));
    }

    private String getRecommendedJoyUrl() {
        return BuildConfig.RECOMMENDED_JOY_SOURCE_URL;
    }

    private String getRecommendedWebViewUrl() {
        return BuildConfig.RECOMMENDED_WEBVIEW_SOURCE_URL;
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
        for (ChannelCatalog.Group group : orderedManagementGroups()) {
            try {
                result.put(new JSONObject().put("id", mergedGroupId(group))
                        .put("name", group.title).put("channelCount", group.channels.length)
                        .put("enabled", !disabled.contains(mergedGroupId(group))));
            } catch (JSONException impossible) { }
        }
        return result;
    }

    boolean isFavoritesGroupVisible() { return preferences.getBoolean(FAVORITES_GROUP_VISIBLE, true); }

    synchronized ChannelCatalog.Group[] updateFavoritesGroupVisible(boolean visible) throws IOException {
        if (!preferences.edit().putBoolean(FAVORITES_GROUP_VISIBLE, visible).commit())
            throw new IOException("收藏分组设置保存失败，请重试");
        return visibleGroups;
    }

    synchronized ChannelCatalog.Group[] updateGroupOrder(JSONArray order) throws IOException {
        if (order == null || order.length() != managementGroups.length)
            throw new ControlRequestException(409, "频道分组已变化，请刷新后重试");
        Set<String> available = new HashSet<String>();
        for (ChannelCatalog.Group group : managementGroups) available.add(mergedGroupId(group));
        for (int i = 0; i < order.length(); i++)
            if (!available.remove(order.optString(i)))
                throw new IOException("频道分组顺序无效，请刷新后重试");
        if (!preferences.edit().putString(MERGED_GROUP_ORDER, order.toString()).commit())
            throw new IOException("分组顺序保存失败，请重试");
        visibleGroups = filterGroups(getDisabledGroups(), readChannelSettings());
        return visibleGroups;
    }

    private List<ChannelCatalog.Group> orderedManagementGroups() {
        final Map<String, Integer> ranks = new HashMap<String, Integer>();
        try {
            JSONArray order = new JSONArray(preferences.getString(MERGED_GROUP_ORDER, "[]"));
            for (int i = 0; i < order.length(); i++) ranks.put(order.optString(i), i);
        } catch (JSONException ignored) { }
        List<ChannelCatalog.Group> groups = new ArrayList<ChannelCatalog.Group>(Arrays.asList(managementGroups));
        Collections.sort(groups, (a, b) -> {
            Integer left = ranks.get(mergedGroupId(a)), right = ranks.get(mergedGroupId(b));
            int l = left == null ? Integer.MAX_VALUE : left, r = right == null ? Integer.MAX_VALUE : right;
            return l < r ? -1 : l == r ? 0 : 1;
        });
        return groups;
    }

    synchronized ChannelCatalog.Group[] updateGroupStates(JSONArray states) throws IOException {
        if (states == null) throw new IOException("频道分组设置无效");
        Set<String> available = new HashSet<String>();
        for (ChannelCatalog.Group group : managementGroups) available.add(mergedGroupId(group));
        Set<String> disabled = getDisabledGroups();
        for (int i = 0; i < states.length(); i++) {
            JSONObject state = states.optJSONObject(i);
            if (state == null) continue;
            String id = state.optString("id");
            if (!available.contains(id)) throw new ControlRequestException(409, "频道分组已变化，请刷新后重试");
            if (state.optBoolean("enabled", true)) disabled.remove(id);
            else disabled.add(id);
        }
        ChannelCatalog.Group[] visible = filterGroups(disabled, readChannelSettings());
        if (managementGroups.length > 0 && visible.length == 0) throw new IOException("至少保留一个可见频道分组");
        if (!preferences.edit().putString(DISABLED_MERGED_GROUPS, new JSONArray(disabled).toString()).commit())
            throw new IOException("频道分组保存失败，请重试");
        visibleGroups = visible;
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
                loaded.add(parseSource(bytes, source));
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
                SourceRead read = readSource(source);
                bytes = read.bytes;
                if (!isLocalLocation(source.location) && read.cacheChanged) {
                    writeCache(source, bytes);
                }
                if (!isLocalLocation(source.location) && read.metadata != null) {
                    saveCacheMetadata(source, read.metadata);
                }
                bytes = new TvbusLiveConfig(context).expand(bytes, source.location, true);
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
                ChannelCatalog.Group[] parsed = parseSource(source.bytes, source.source);
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
        return applyMobileMerge(input, playlist, null);
    }

    synchronized JSONObject getVisibleGroupStatsJson() throws JSONException {
        int count = 0;
        for (ChannelCatalog.Group group : visibleGroups) count += group.channels.length;
        return new JSONObject().put("groupCount", visibleGroups.length).put("channelCount", count);
    }

    UpdateResult applyMobileMerge(JSONArray input, byte[] playlist, JSONArray sourcePlaylists)
            throws IOException, JSONException {
        return applyMobileMerge(input,playlist,sourcePlaylists,null);
    }

    UpdateResult applyMobileMerge(JSONArray input, byte[] playlist, JSONArray sourcePlaylists,
            JSONArray failedSourceIds) throws IOException, JSONException {
        if (playlist == null || playlist.length == 0) {
            throw new IOException("手机合并后的频道配置为空");
        }
        List<Source> sources = parseSources(input);
        // Keep source boundaries. A flattened phone merge cannot reconstruct which
        // parent owns a group after restart, or when two sources share its title.
        if (sourcePlaylists != null) {
            Map<String,JSONArray> structured = new HashMap<>();
            Set<String> failed = stringSet(failedSourceIds);
            List<Source> previous = getSources();
            List<String> warnings = new ArrayList<String>();
            List<LoadedSource> fetched = new ArrayList<LoadedSource>();
            List<ChannelCatalog.Group[]> loaded = new ArrayList<ChannelCatalog.Group[]>();
            appendBuiltInGroups(loaded);
            int enabledCount = 0;
            for (Source source : sources) {
                if (!source.enabled) continue;
                enabledCount++;
                byte[] bytes = null;
                for (int index = 0; index < sourcePlaylists.length(); index++) {
                    JSONObject item = sourcePlaylists.optJSONObject(index);
                    if (item != null && source.id.equals(item.optString("id"))) {
                        bytes = item.optString("playlist", "").getBytes("UTF-8");
                        if(item.has("channels"))structured.put(source.id,item.getJSONArray("channels"));
                        break;
                    }
                }
                if (bytes == null || bytes.length == 0) {
                    if (!failed.contains(source.id))
                        throw new ControlRequestException(400, source.name + " 未读取成功，已保留上次频道列表");
                    // Keep a known source's channels only when its address is
                    // unchanged. Never attach an old address's cache to a new one.
                    for (Source old : previous) if (old.id.equals(source.id)
                            && old.location.equals(source.location)) {
                        try { bytes=readCache(source); } catch(IOException unavailable) { }
                        break;
                    }
                    warnings.add(source.name + (bytes==null?" 待读取，已保存地址":" 更新失败，已保留缓存"));
                    if(bytes==null||bytes.length==0)continue;
                }
                fetched.add(new LoadedSource(source, bytes));
            }
            if (fetched.isEmpty()) throw new ControlRequestException(400,"没有可用频道源，已保留上次频道列表");
            List<LoadedSource> identities=new ArrayList<>();
            for(LoadedSource source:fetched){
                identities.add(source);
                if(structured.containsKey(source.source.id))identities.add(new LoadedSource(source.source,structured.get(source.source.id).toString().getBytes("UTF-8")));
            }
            String fingerprint = catalogFingerprint(structured.isEmpty()?"mobile-sources":"mobile-json-v1", sources, identities, null);
            ChannelCatalog.Group[] unchanged = unchangedCatalog(fingerprint);
            if (unchanged != null) {
                saveSources(sources);
                context.deleteFile(MOBILE_MERGED_FILE);
                rememberEmbeddedEpgUrl(discoverFirstEpgUrl(fetched));
                return new UpdateResult(rememberAndFilterGroups(unchanged),enabledCount,warnings);
            }
            for (LoadedSource source : fetched) {
                JSONArray channels=structured.get(source.source.id);
                if(channels==null)loaded.add(parseSource(source.bytes,source.source));
                else {
                    ChannelCatalog.Group[] groups=JsonChannelPlaylist.parse(channels,source.source.location);
                    for(int i=0;i<groups.length;i++)groups[i]=new ChannelCatalog.Group(groups[i].title,groups[i].source,groups[i].channels,source.source.id,source.source.name);
                    loaded.add(groups);
                }
            }
            ChannelCatalog.Group[] raw = merge(loaded);
            ChannelCatalog.Group[] visible = persistAndFilterGroups(raw, fingerprint);
            saveSources(sources);
            deleteRemovedCaches(previous, sources);
            for (LoadedSource source : fetched)
                if (!isLocalLocation(source.source.location)) writeCache(source.source, source.bytes);
            context.deleteFile(MOBILE_MERGED_FILE);
            rememberEmbeddedEpgUrl(discoverFirstEpgUrl(fetched));
            return new UpdateResult(visible, enabledCount, warnings);
        }
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
            throw new ControlRequestException(400, "频道源地址无效");
        }
        // Uploaded playlists live on the television, so the web editor must use
        // the same local reader as device refresh. Read again on each refresh;
        // HTTP metadata and caches do not apply to file/content sources.
        if (isLocalLocation(value)) {
            return new TvbusLiveConfig(context).expand(readLocal(value), value, true);
        }
        Source source = null;
        for (Source configured : getSources()) {
            if (value.equals(configured.location)) {
                source = configured;
                break;
            }
        }
        if (source == null) {
            source = new Source(mobileCacheId(value), "手机频道源", value, true);
        }
        SourceRead read = readSource(source,
                android.os.SystemClock.elapsedRealtime()
                        + (GithubProxy.githubSource(value) != null
                        ? MOBILE_READ_BUDGET_MS : MOBILE_ORIGIN_READ_BUDGET_MS));
        if (read.cacheChanged) writeCache(source, read.bytes);
        if (read.metadata != null) saveCacheMetadata(source, read.metadata);
        rememberMobileCache(source);
        return new TvbusLiveConfig(context).expand(read.bytes, value, true);
    }

    private synchronized ChannelCatalog.Group[] rememberAndFilterGroups(
            ChannelCatalog.Group[] groups) {
        if (groups == availableGroups) return visibleGroups;
        catalogMemoryCache = groups;
        availableGroups = groups;
        List<ChannelCatalog.Group[]> all = new ArrayList<ChannelCatalog.Group[]>();
        all.add(groups);
        managementGroups = mergeVisible(all);
        migrateMergedSettings();
        Set<String> disabled = getDisabledGroups();
        ChannelCatalog.Group[] visible = filterGroups(disabled, readChannelSettings());
        if (visible.length == 0 && managementGroups.length > 0) {
            disabled.remove(mergedGroupId(managementGroups[0]));
            saveDisabledGroups(disabled);
            visible = filterGroups(disabled, readChannelSettings());
        }
        visibleGroups = visible;
        return visible;
    }

    private ChannelCatalog.Group[] persistAndFilterGroups(ChannelCatalog.Group[] groups) {
        if (!catalogStore.replace(groups, null)) {
            AppLog.w(TAG, "Unable to persist startup channel catalog; using memory snapshot");
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

    private static String mergedGroupId(ChannelCatalog.Group group) {
        return "group:" + groupKey(group.title);
    }
    static String channelGroupId(String title) { return "group:" + groupKey(title); }

    private ChannelCatalog.Group[] filterGroups(Set<String> disabled, JSONObject settings) {
        List<ChannelCatalog.Group> visible = new ArrayList<ChannelCatalog.Group>();
        for (ChannelCatalog.Group group : orderedManagementGroups()) {
            String id = mergedGroupId(group);
            if (disabled.contains(id)) continue;
            Channel[] channels = configuredChannels(group.channels, settings.optJSONObject(id), false);
            if (channels.length > 0) visible.add(new ChannelCatalog.Group(group.title, group.source, channels));
        }
        return visible.toArray(new ChannelCatalog.Group[visible.size()]);
    }

    private JSONObject readChannelSettings() {
        try { return new JSONObject(preferences.getString(MERGED_CHANNEL_SETTINGS, "{}")); }
        catch (JSONException ignored) { return new JSONObject(); }
    }

    // One-time migration: only hide a merged channel/group if every contributing
    // source hid it. A visible copy in another source must remain manageable.
    private void migrateMergedSettings() {
        if (preferences.contains(MERGED_CHANNEL_SETTINGS)) return;
        Set<String> legacyDisabled = getLegacyDisabledGroups(), disabled = new HashSet<String>();
        Set<String> collapsed = getCollapsedSources();
        JSONObject legacy;
        try { legacy = new JSONObject(preferences.getString(CHANNEL_SETTINGS, "{}")); }
        catch (JSONException ignored) { legacy = new JSONObject(); }
        JSONObject settings = new JSONObject();
        for (ChannelCatalog.Group group : managementGroups) {
            ChannelBucket full = new ChannelBucket(), ordered = new ChannelBucket(), shown = new ChannelBucket();
            for (Channel channel : group.channels) full.add(channel);
            boolean enabled = false, edited = false;
            for (ChannelCatalog.Group raw : availableGroups) {
                if (!groupKey(raw.title).equals(groupKey(group.title))) continue;
                if (collapsed.contains(raw.playlistSourceId) || (!legacyDisabled.contains(stateKey(raw))
                        && !legacyDisabled.contains(groupKey(raw.title)))) enabled = true;
                JSONObject setting = legacy.optJSONObject(collapsed.contains(raw.playlistSourceId)
                        ? "source:" + raw.playlistSourceId : stateKey(raw));
                if (setting != null) edited = true;
                for (Channel channel : configuredChannels(raw.channels, setting, true)) ordered.add(channel);
                for (Channel channel : configuredChannels(raw.channels, setting, false)) shown.add(channel);
            }
            if (!enabled) disabled.add(mergedGroupId(group));
            if (!edited) continue;
            JSONArray order = new JSONArray(), hidden = new JSONArray();
            for (Channel channel : ordered.channels) {
                int index = full.find(channel);
                if (index >= 0) order.put(channelSettingKey(full.channels.get(index)));
            }
            for (Channel channel : full.channels) if (shown.find(channel) < 0) hidden.put(channelSettingKey(channel));
            try { settings.put(mergedGroupId(group), new JSONObject().put("order", order).put("hidden", hidden)); }
            catch (JSONException impossible) { }
        }
        preferences.edit().putString(MERGED_CHANNEL_SETTINGS, settings.toString())
                .putString(DISABLED_MERGED_GROUPS, new JSONArray(disabled).toString()).commit();
    }

    private static String channelSettingKey(Channel channel) {
        // The parser already merges duplicate names. Stream URLs and channel numbers
        // can change on a source refresh, so neither belongs in the saved identity.
        return channel.name.toLowerCase(Locale.US);
    }

    private Channel[] managementChannels(String groupId) throws IOException {
        for (ChannelCatalog.Group group : managementGroups)
            if (mergedGroupId(group).equals(groupId)) return group.channels;
        throw new ControlRequestException(409, "频道分组已变化，请刷新后重试");
    }

    private static Set<String> stringSet(JSONArray values) {
        Set<String> result = new HashSet<String>();
        if (values != null) for (int i = 0; i < values.length(); i++) result.add(values.optString(i));
        return result;
    }

    private static Channel[] configuredChannels(Channel[] channels, JSONObject setting, boolean includeHidden) {
        if (setting == null) return channels;
        Set<String> hidden = stringSet(setting.optJSONArray("hidden"));
        hidden.addAll(stringSet(setting.optJSONArray("removed")));
        Map<String, Channel> remaining = new LinkedHashMap<String, Channel>();
        for (Channel channel : channels) remaining.put(channelSettingKey(channel), channel);
        List<Channel> ordered = new ArrayList<Channel>();
        JSONArray order = setting.optJSONArray("order");
        if (order != null) for (int i = 0; i < order.length(); i++) {
            String key = order.optString(i);
            Channel channel = remaining.remove(key);
            if (channel != null && (includeHidden || !hidden.contains(key))) ordered.add(channel);
        }
        for (Map.Entry<String, Channel> entry : remaining.entrySet())
            if (includeHidden || !hidden.contains(entry.getKey())) ordered.add(entry.getValue());
        return ordered.toArray(new Channel[0]);
    }

    private static String channelRevision(Channel[] channels, JSONObject setting) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Channel channel : channels) updateDigest(digest, channelSettingKey(channel));
            updateDigest(digest, setting == null ? "" : setting.toString());
            return encodeHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }

    synchronized JSONObject getChannelSettingsJson(String groupId) throws IOException, JSONException {
        Channel[] raw = managementChannels(groupId);
        JSONObject setting = readChannelSettings().optJSONObject(groupId);
        Set<String> hidden = setting == null ? new HashSet<String>() : stringSet(setting.optJSONArray("hidden"));
        if (setting != null) hidden.addAll(stringSet(setting.optJSONArray("removed")));
        JSONArray channels = new JSONArray();
        for (Channel channel : configuredChannels(raw, setting, true)) channels.put(new JSONObject()
                .put("id", channelSettingKey(channel)).put("name", channel.name)
                .put("enabled", !hidden.contains(channelSettingKey(channel))));
        return new JSONObject().put("ok", true).put("channels", channels)
                .put("revision", channelRevision(raw, setting));
    }

    synchronized ChannelCatalog.Group[] updateChannelSettings(JSONObject request) throws IOException, JSONException {
        if (request == null) throw new IOException("频道设置无效");
        String groupId = request.optString("groupId");
        Channel[] raw = managementChannels(groupId);
        JSONObject settings = readChannelSettings();
        if (!channelRevision(raw, settings.optJSONObject(groupId)).equals(request.optString("revision")))
            throw new IOException("频道列表或设置已变化，请重新展开分组后重试");
        Set<String> available = new HashSet<String>();
        for (Channel channel : raw) available.add(channelSettingKey(channel));
        JSONArray order = request.optJSONArray("order"), hidden = request.optJSONArray("hidden");
        if (order == null || hidden == null || order.length() != available.size()
                || stringSet(order).size() != available.size() || !available.containsAll(stringSet(order))
                || !available.containsAll(stringSet(hidden))) throw new IOException("频道列表已变化，请重新展开分组后重试");
        Set<String> removed = stringSet(settings.optJSONObject(groupId) == null ? null
                : settings.optJSONObject(groupId).optJSONArray("removed"));
        removed.retainAll(stringSet(hidden));
        Set<String> manuallyHidden = stringSet(hidden); manuallyHidden.removeAll(removed);
        settings.put(groupId, new JSONObject().put("order", order)
                .put("hidden", new JSONArray(manuallyHidden)).put("removed", new JSONArray(removed)));
        ChannelCatalog.Group[] visible = filterGroups(getDisabledGroups(), settings);
        if (availableGroups.length > 0 && visible.length == 0) throw new IOException("至少保留一个可见频道");
        if (!preferences.edit().putString(MERGED_CHANNEL_SETTINGS, settings.toString()).commit())
            throw new IOException("频道设置保存失败，请重试");
        visibleGroups = visible;
        return visible;
    }

    /** Local removal survives source refresh; does not edit a provider's file. */
    synchronized ChannelCatalog.Group[] removeChannel(String groupId, String channelName)
            throws IOException, JSONException {
        Channel[] raw = managementChannels(groupId);
        String key = channelName == null ? "" : channelName.toLowerCase(Locale.US);
        boolean found = false;
        for (Channel channel : raw) if (channelSettingKey(channel).equals(key)) found = true;
        if (!found) throw new IOException("频道已变化，请重新选择后移除");
        JSONObject settings = readChannelSettings(), setting = settings.optJSONObject(groupId);
        if (setting == null) setting = new JSONObject();
        Set<String> removed = stringSet(setting.optJSONArray("removed")); removed.add(key);
        setting.put("removed", new JSONArray(removed)); settings.put(groupId, setting);
        ChannelCatalog.Group[] visible = filterGroups(getDisabledGroups(), settings);
        if (availableGroups.length > 0 && visible.length == 0) throw new IOException("至少保留一个可见频道");
        if (!preferences.edit().putString(MERGED_CHANNEL_SETTINGS, settings.toString()).commit())
            throw new IOException("移除记录保存失败，请重试");
        visibleGroups = visible; return visible;
    }

    synchronized ChannelCatalog.Group[] restoreRemovedChannels() throws IOException, JSONException {
        JSONObject settings = readChannelSettings(); boolean changed = false;
        for (java.util.Iterator<String> keys = settings.keys(); keys.hasNext();) {
            JSONObject setting = settings.optJSONObject(keys.next());
            if (setting != null && setting.optJSONArray("removed") != null
                    && setting.optJSONArray("removed").length() > 0) {
                setting.remove("removed"); changed = true;
            }
        }
        if (!changed) throw new IOException("没有可恢复的移除频道");
        if (!preferences.edit().putString(MERGED_CHANNEL_SETTINGS, settings.toString()).commit())
            throw new IOException("恢复记录保存失败，请重试");
        visibleGroups = filterGroups(getDisabledGroups(), settings); return visibleGroups;
    }

    private Set<String> getCollapsedSources() {
        Set<String> result = new HashSet<String>();
        try {
            JSONArray saved = new JSONArray(preferences.getString(COLLAPSED_SOURCES, "[]"));
            for (int i = 0; i < saved.length(); i++) result.add(saved.optString(i));
        } catch (JSONException ignored) { }
        return result;
    }

    private static String stateKey(ChannelCatalog.Group group) {
        return group.playlistSourceId.length() == 0 ? groupKey(group.title)
                : "child:" + group.playlistSourceId + ":" + groupKey(group.title);
    }

    private ChannelCatalog.Group[] parseSource(byte[] bytes, Source source) throws IOException {
        bytes = new TvbusLiveConfig(context).expand(bytes, source.location, false);
        ChannelCatalog.Group[] groups = parse(bytes, source.location);
        for (int i = 0; i < groups.length; i++) {
            ChannelCatalog.Group group = groups[i];
            groups[i] = new ChannelCatalog.Group(group.title, group.source, group.channels,
                    source.id, source.name);
        }
        return groups;
    }

    JSONObject tvbusCore(String sourceId, String url) throws IOException {
        return new TvbusLiveConfig(context).find(sourceId == null ? "" : sourceId, url, getSources());
    }

    private static ChannelCatalog.Group[] mergeVisible(List<ChannelCatalog.Group[]> sources) {
        Map<String, ChannelBucket> buckets = new LinkedHashMap<String, ChannelBucket>();
        Map<String, String> titles = new HashMap<String, String>();
        for (ChannelCatalog.Group[] groups : sources) for (ChannelCatalog.Group group : groups) {
            String key = groupKey(group.title);
            ChannelBucket bucket = buckets.get(key);
            if (bucket == null) {
                bucket = new ChannelBucket(); buckets.put(key, bucket);
                titles.put(key, normalizeGroupTitle(group.title));
            }
            for (Channel channel : group.channels) bucket.add(channel);
        }
        List<ChannelCatalog.Group> result = new ArrayList<ChannelCatalog.Group>();
        for (Map.Entry<String, ChannelBucket> entry : buckets.entrySet()) result.add(new ChannelCatalog.Group(
                titles.get(entry.getKey()), ChannelCatalog.SOURCE_CUSTOM,
                defaultChannelOrder(titles.get(entry.getKey()), entry.getValue().channels.toArray(new Channel[0]))));
        return result.toArray(new ChannelCatalog.Group[result.size()]);
    }

    // Apply the legacy default before saved user order/visibility. Reordering in
    // ChannelCatalog after configuredChannels() would override the user's edits.
    private static Channel[] defaultChannelOrder(String title, Channel[] channels) {
        if (!"央视频道".equals(title) || channels == null || channels.length < 18) {
            return channels;
        }
        int sourceIndex = -1;
        for (int index = 0; index < channels.length; index++) {
            String name = channels[index].name == null ? ""
                    : channels[index].name.toUpperCase(java.util.Locale.US)
                            .replace("-", "").replace(" ", "");
            if (name.startsWith("CCTV5+")) {
                sourceIndex = index;
                break;
            }
        }
        int targetIndex = 17;
        if (sourceIndex < 0 || sourceIndex == targetIndex) {
            return channels;
        }
        Channel[] reordered = channels.clone();
        Channel moved = reordered[sourceIndex];
        if (sourceIndex < targetIndex) {
            System.arraycopy(reordered, sourceIndex + 1, reordered, sourceIndex,
                    targetIndex - sourceIndex);
        } else {
            System.arraycopy(reordered, targetIndex, reordered, targetIndex + 1,
                    sourceIndex - targetIndex);
        }
        reordered[targetIndex] = moved;
        return reordered;
    }

    private Set<String> getDisabledGroups() {
        try { return stringSet(new JSONArray(preferences.getString(DISABLED_MERGED_GROUPS, "[]"))); }
        catch (JSONException ignored) { return new HashSet<String>(); }
    }

    private Set<String> getLegacyDisabledGroups() {
        Set<String> result = new HashSet<String>();
        String saved = preferences.getString(DISABLED_GROUPS, "");
        if (saved.length() == 0) {
            return result;
        }
        try {
            JSONArray values = new JSONArray(saved);
            for (int index = 0; index < values.length(); index++) {
                String key = values.optString(index, "").trim();
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
        preferences.edit().putString(DISABLED_MERGED_GROUPS, values.toString()).commit();
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
                        + "”需要频道列表地址、本地文件或完整的 RTP/UDP/RTSP 频道地址");
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
                preferences.edit().remove(cacheMetadataKey(source)).apply();
                if ("legacy".equals(source.id)) {
                    context.deleteFile(LEGACY_CACHE_FILE);
                }
            }
        }
    }

    private byte[] readSourceForStartup(Source source) throws IOException {
        if (MulticastPlaylist.matches(source.location)) return MulticastPlaylist.bytes(source.name, source.location);
        if (isLocalLocation(source.location)) {
            return readLocal(source.location);
        }
        return readCache(source);
    }

    private SourceRead readSource(Source source) throws IOException {
        return readSource(source, android.os.SystemClock.elapsedRealtime() + PLAYLIST_READ_TIMEOUT_MS);
    }

    private SourceRead readSource(Source source, long deadline) throws IOException {
        if (MulticastPlaylist.matches(source.location))
            return new SourceRead(MulticastPlaylist.bytes(source.name, source.location), false, null);
        if (isLocalLocation(source.location)) {
            return new SourceRead(readLocal(source.location), false, null);
        }
        File cache = context.getFileStreamPath(cacheFile(source));
        RemoteFileMetadata previous = readCacheMetadata(source);
        RemoteDownload downloaded = download(source.location, null, null, 0,
                previous, cache, deadline);
        if (downloaded.notModified) {
            return new SourceRead(readCache(source), false, previous);
        }
        boolean unchanged = previous != null && previous.sameContent(downloaded.metadata)
                && previous.matchesLocal(cache);
        return new SourceRead(downloaded.bytes, !unchanged, downloaded.metadata);
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

    private static int remainingMobileTime(long deadline) throws IOException {
        long remaining = deadline - android.os.SystemClock.elapsedRealtime();
        if (remaining <= 0L) throw new java.net.SocketTimeoutException("频道源读取超时，请由手机重试");
        return (int) Math.min(Integer.MAX_VALUE, remaining);
    }

    private HttpURLConnection openPlaylistConnection(URL url, final long deadline)
            throws IOException {
        if (deadline == 0L) return NetworkClient.open(url);
        // One shared budget covers HEAD, GET, mirrors, redirects and cookie challenges.
        String github = GithubProxy.githubSource(url.toString());
        if (github != null && GithubProxy.isEnabled())
            return new GithubConnection(url, target -> NetworkClient.openBounded(
                    target, remainingMobileTime(deadline)));
        return NetworkClient.openBounded(github == null ? url : new URL(github),
                remainingMobileTime(deadline));
    }

    private RemoteDownload download(String sourceUrl, String cookie, String referer,
            int challengeCount, RemoteFileMetadata previous, File cachedFile, long deadline)
            throws IOException {
        return download(sourceUrl, cookie, referer, challengeCount, previous, cachedFile, deadline, false);
    }

    private RemoteDownload download(String sourceUrl, String cookie, String referer,
            int challengeCount, RemoteFileMetadata previous, File cachedFile, long deadline,
            boolean forceIdentity) throws IOException {
        String requestUrl = GithubProxy.apply(context, sourceUrl);
        URL url = new URL(requestUrl);
        boolean reusableCache = previous != null && previous.matchesLocal(cachedFile);
        if (previous != null && previous.appliesTo(sourceUrl)
                && reusableCache && previous.shouldProbeMd5()
                && playlistHeadMatches(url, sourceUrl, cookie, referer,
                        previous, cachedFile, deadline)) {
            AppLog.i(TAG, "Channel source unchanged by size/MD5 " + sourceUrl);
            return RemoteDownload.notModified();
        }
        HttpURLConnection connection = openPlaylistConnection(url, deadline);
        connection.setConnectTimeout(deadline == 0L ? 12000 : Math.min(12000, remainingMobileTime(deadline)));
        connection.setReadTimeout(deadline == 0L ? PLAYLIST_READ_TIMEOUT_MS : Math.min(PLAYLIST_READ_TIMEOUT_MS, remainingMobileTime(deadline)));
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "nTv/1.5");
        // OkHttp transparently decodes gzip and removes the wire Content-Length.
        // Preserve identity when validating a server's representation MD5.
        boolean identity = forceIdentity || previous != null && previous.serverMd5.length() > 0;
        if (identity) connection.setRequestProperty("Accept-Encoding", "identity");
        if (cookie != null) {
            connection.setRequestProperty("Cookie", cookie);
        }
        if (referer != null) {
            connection.setRequestProperty("Referer", referer);
        }
        if (reusableCache) {
            previous.applyConditionalHeaders(connection, sourceUrl);
        }
        boolean responseConsumed = false;
        try {
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED
                    && reusableCache && previous.appliesTo(sourceUrl)) {
                connection.getInputStream().close();
                responseConsumed = true;
                return RemoteDownload.notModified();
            }
            if (status < 200 || status >= 300) {
                throw new IOException("下载失败：HTTP " + status);
            }
            RemoteFileMetadata response = RemoteFileMetadata.fromResponse(
                    connection, sourceUrl);
            if (response.contentLength > playlistByteLimit()) {
                throw new IOException("频道文件超过设备可安全处理的大小");
            }
            byte[] body;
            try (InputStream input = connection.getInputStream()) { body = readAll(input); }
            responseConsumed = true;
            try { response = response.verify(body, "频道源"); }
            catch (IOException invalid) {
                // A first gzip response may carry a digest of the encoded entity.
                // Retry identity once, within the same total deadline, to verify it.
                if (!identity && response.serverMd5.length() > 0)
                    return download(sourceUrl, cookie, referer, challengeCount,
                            previous, cachedFile, deadline, true);
                throw invalid;
            }
            AesCookieChallenge challenge = parseAesCookieChallenge(body, url);
            if (challenge == null) {
                return new RemoteDownload(body, response, false);
            }
            if (challengeCount >= 2) {
                throw new IOException("频道源的网页验证未通过");
            }
            return download(challenge.location, "__test=" + challenge.cookie,
                    sourceUrl, challengeCount + 1, previous, cachedFile, deadline, forceIdentity);
        } catch (IOException failure) {
            // OkHttp may surface a call deadline as HTTP/2 CANCEL rather than
            // SocketTimeoutException. Report the exhausted budget consistently.
            if (deadline != 0L && android.os.SystemClock.elapsedRealtime() >= deadline) {
                java.net.SocketTimeoutException timeout = new java.net.SocketTimeoutException(
                        "频道源读取超时，已放弃此来源，请稍后重试");
                timeout.initCause(failure);
                throw timeout;
            }
            throw failure;
        } finally {
            if (!responseConsumed) connection.disconnect();
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

    private synchronized byte[] readCache(Source source) throws IOException {
        InputStream input = null;
        try {
            String filename = cacheFile(source);
            RemoteFileMetadata.recover(context.getFileStreamPath(filename));
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

    private synchronized void writeCache(Source source, byte[] bytes) throws IOException {
        File target = context.getFileStreamPath(cacheFile(source));
        RemoteFileMetadata.writeAtomically(target, bytes, "频道源");
    }

    private boolean playlistHeadMatches(URL url, String sourceUrl, String cookie,
            String referer, RemoteFileMetadata previous, File cachedFile, long deadline) {
        HttpURLConnection connection = null;
        try {
            connection = openPlaylistConnection(url, deadline);
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(deadline == 0L ? 12000 : Math.min(2000, remainingMobileTime(deadline)));
            connection.setReadTimeout(deadline == 0L ? 12000 : Math.min(4000, remainingMobileTime(deadline)));
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "nTv/1.5");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (cookie != null) connection.setRequestProperty("Cookie", cookie);
            if (referer != null) connection.setRequestProperty("Referer", referer);
            int status = connection.getResponseCode();
            return status >= 200 && status < 300 && previous.matchesRemote(
                    RemoteFileMetadata.fromResponse(connection, sourceUrl), cachedFile);
        } catch (IOException ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private RemoteFileMetadata readCacheMetadata(Source source) {
        return RemoteFileMetadata.fromJson(preferences.getString(
                cacheMetadataKey(source), ""));
    }

    private void saveCacheMetadata(Source source, RemoteFileMetadata metadata) {
        preferences.edit().putString(cacheMetadataKey(source), metadata.toJson()).apply();
    }

    private static String cacheMetadataKey(Source source) {
        return CACHE_METADATA_PREFIX + source.id;
    }

    private synchronized void rememberMobileCache(Source source) {
        if (!source.id.startsWith("mobile_")) return;
        List<String> ids = new ArrayList<String>();
        String saved = preferences.getString(MOBILE_CACHE_INDEX, "");
        if (saved.length() > 0) {
            try {
                JSONArray stored = new JSONArray(saved);
                for (int index = 0; index < stored.length(); index++) {
                    String id = sanitizeId(stored.optString(index, ""));
                    if (id.length() > 0 && !id.equals(source.id)) ids.add(id);
                }
            } catch (JSONException ignored) {
            }
        }
        ids.add(source.id);
        while (ids.size() > MAX_SOURCES) {
            String expired = ids.remove(0);
            Source old = new Source(expired, "", "", false);
            context.deleteFile(cacheFile(old));
            preferences.edit().remove(cacheMetadataKey(old)).apply();
        }
        JSONArray updated = new JSONArray();
        for (String id : ids) updated.put(id);
        preferences.edit().putString(MOBILE_CACHE_INDEX, updated.toString()).apply();
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
        Map<String, ChannelCatalog.Group> owners = new LinkedHashMap<String, ChannelCatalog.Group>();
        for (ChannelCatalog.Group[] groups : sourceGroups) {
            for (ChannelCatalog.Group group : groups) {
                String groupTitle = normalizeGroupTitle(group.title);
                String key = group.playlistSourceId + "\u001f" + groupTitle;
                ChannelBucket bucket = merged.get(key);
                if (bucket == null) {
                    bucket = new ChannelBucket();
                    merged.put(key, bucket);
                    owners.put(key, group);
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
            ChannelCatalog.Group owner = owners.get(entry.getKey());
            result[index++] = new ChannelCatalog.Group(normalizeGroupTitle(owner.title),
                    ChannelCatalog.SOURCE_CUSTOM,
                    channels.toArray(new Channel[channels.size()]),
                    owner.playlistSourceId, owner.playlistSourceName);
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
        updateDigest(digest, "ntv-catalog-v2-source-groups");
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
        String[] bookmarkPrefixes = { "Chrome书签 / ", "Chrome 书签 / " };
        for (String prefix : bookmarkPrefixes) {
            if (value.startsWith(prefix)) {
                value = value.substring(prefix.length()).trim();
                break;
            }
        }
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
        return parse(bytes, "");
    }

    private static ChannelCatalog.Group[] parse(byte[] bytes, String base) throws IOException {
        String text = decode(bytes);
        Map<String, ChannelBucket> groups = new LinkedHashMap<String, ChannelBucket>();
        String currentGroup = "在线频道";
        String pendingName = null;
        String pendingGroup = null;
        String pendingEpgId = null;
        String pendingLogo = null;
        boolean pendingRadio = false;
        IptvCatchup pendingCatchup=null;
        Map<String,String> catchupDefaults=java.util.Collections.emptyMap();
        String pendingSubtitles = "";
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
            if(line.startsWith("#EXTM3U"))catchupDefaults=IptvCatchup.attributes(line);
            if (line.startsWith("#EXTINF:")) {
                pendingCatchup=IptvCatchup.from(line,catchupDefaults,base);
                pendingName = attribute(line, "tvg-name");
                pendingGroup = attribute(line, "group-title");
                pendingEpgId = attribute(line, "tvg-id");
                pendingLogo = attribute(line, "tvg-logo");
                String radio = attribute(line, "radio");
                pendingRadio = "true".equalsIgnoreCase(radio) || "1".equals(radio);
                pendingSubtitles = Channel.resolveSubtitleUrl(attribute(line, "subtitles"), base);
                if (pendingSubtitles.isEmpty()) pendingSubtitles = Channel.resolveSubtitleUrl(attribute(line, "subtitle"), base);
                int comma = line.lastIndexOf(',');
                if (comma >= 0 && comma + 1 < line.length()) {
                    pendingName = line.substring(comma + 1).trim();
                }
                if (lineEnd == text.length()) {
                    break;
                }
                continue;
            }
            if (pendingName != null && line.regionMatches(true, 0, "#EXTVLCOPT:sub-file=", 0, 20)) {
                String subtitle = Channel.resolveSubtitleUrl(line.substring(20), base);
                if (!subtitle.isEmpty()) pendingSubtitles += (pendingSubtitles.isEmpty() ? "" : "\n") + subtitle;
            }
            if (line.startsWith("#")) {
                if (lineEnd == text.length()) {
                    break;
                }
                continue;
            }
            if (pendingName != null && isStreamUrl(line)) {
                String groupName = emptyToDefault(pendingGroup, currentGroup);
                add(groups, groupName, pendingName, line, pendingEpgId, pendingLogo, pendingSubtitles, pendingRadio, pendingCatchup, count++);
                pendingName = null;
                pendingGroup = null;
                pendingEpgId = null;
                pendingLogo = null;
                pendingRadio = false;
                pendingSubtitles = "";
            } else {
                int comma = line.indexOf(',');
                if (comma <= 0 || comma + 1 >= line.length()) {
                    continue;
                }
                String name = line.substring(0, comma).trim();
                String value = line.substring(comma + 1).trim();
                // Accept empty CSV fields produced by PHP/TXT playlist generators.
                // Preserve every character inside a real URL (including query commas).
                value = value.replaceFirst("(?i)^(?:,\\s*)+(?=(?:https?|rtmps?|rtmpt|rtsp|rtp|udp|tvbus|sop|webview)://)", "");
                if (value.matches("(?i)#genre#(?:\\s*,\\s*)*")) {
                    currentGroup = name.length() == 0 ? "在线频道" : name;
                } else if (isStreamUrl(value)) {
                    add(groups, currentGroup, name, value, null, null, "", false, null, count++);
                }
            }
            if (lineEnd == text.length()) {
                break;
            }
        }
        if (count == 0) {
            throw new IOException("频道源中没有找到可播放的 HTTP、RTP、UDP、RTSP、RTMP 或 WebView 地址");
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
            String name, String url, String epgId, String logoUrl, String subtitles, boolean radio, IptvCatchup catchup, int index) {
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
        bucket.add(incoming.withLogo(logoUrl).withSubtitles(subtitles).withRadio(radio).withCatchup(url,catchup));
    }

    static String channelNumber(String name, String epgId, int fallback) {
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
            if (existing.logoUrl.length() == 0 && incoming.logoUrl.length() > 0)
                existing = existing.withLogo(incoming.logoUrl);
            existing = existing.withSubtitles(incoming.subtitleUrlsText());
            existing = existing.withRadio(existing.radio || incoming.radio).withCatchups(incoming.catchupText());
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
            // EPG IDs describe schedules, not channel identity. Providers may
            // reuse one ID for a whole family (CDTV1..6, movie/children channels).
            // Merge by channel name or source; retain epgId for programme lookup.
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

    private static final class SourceRead {
        final byte[] bytes;
        final boolean cacheChanged;
        final RemoteFileMetadata metadata;

        SourceRead(byte[] bytes, boolean cacheChanged, RemoteFileMetadata metadata) {
            this.bytes = bytes;
            this.cacheChanged = cacheChanged;
            this.metadata = metadata;
        }
    }

    private static final class RemoteDownload {
        final byte[] bytes;
        final RemoteFileMetadata metadata;
        final boolean notModified;

        RemoteDownload(byte[] bytes, RemoteFileMetadata metadata, boolean notModified) {
            this.bytes = bytes;
            this.metadata = metadata;
            this.notModified = notModified;
        }

        static RemoteDownload notModified() {
            return new RemoteDownload(null, null, true);
        }
    }

    private static boolean isStreamUrl(String text) {
        String value = text.toLowerCase(Locale.US);
        return value.startsWith("http://") || value.startsWith("https://")
                || value.startsWith("rtmp://") || value.startsWith("rtmpt://")
                || value.startsWith("rtmps://")
                || value.startsWith("rtp://") || value.startsWith("udp://")
                || value.startsWith("rtsp://") || value.startsWith("tvbus://") || value.startsWith("sop://")
                || value.startsWith("webview://http://")
                || value.startsWith("webview://https://");
    }

    private static boolean isSupportedLocation(String location) {
        String value = location.toLowerCase(Locale.US);
        return value.startsWith("http://") || value.startsWith("https://")
                || isLocalLocation(value) || MulticastPlaylist.valid(location);
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

    private static String mobileCacheId(String location) {
        try {
            String digest = encodeHex(MessageDigest.getInstance("SHA-256").digest(
                    location.getBytes("UTF-8")));
            return "mobile_" + digest.substring(0, 20);
        } catch (Exception impossible) {
            return "mobile_" + Integer.toHexString(location.hashCode());
        }
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
