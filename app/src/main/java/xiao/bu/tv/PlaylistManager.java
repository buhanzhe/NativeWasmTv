package xiao.bu.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

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
import java.util.ArrayList;
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
    private static final String RELEASE_URL = "https://github.com/buhanzhe/webSourceM3U8/"
            + "releases/latest/download/webview.txt";
    private static final String PREFS = "management";
    private static final String PLAYLIST_URL = "playlist_url";
    private static final String PLAYLIST_SOURCES = "playlist_sources_v1";
    private static final String DISABLED_GROUPS = "disabled_playlist_groups_v1";
    private static final String EMBEDDED_EPG_URL = "embedded_epg_url";
    private static final String LEGACY_CACHE_FILE = "online-playlist.txt";
    private static final String CACHE_PREFIX = "online-playlist-";
    private static final String IMPORT_DIRECTORY = "imported-playlists";
    private static final String BUILT_IN_PLAYLIST = "builtin_channels.txt";
    private static final int MAX_DOWNLOAD_BYTES = 2 * 1024 * 1024;
    private static final int MAX_CHANNELS = 2000;
    private static final int MAX_SOURCES = 20;
    private static final Pattern CCTV_NAME = Pattern.compile("^cctv([0-9]+)(\\+?)(.*)$");
    private static final Pattern AES_COOKIE_CHALLENGE = Pattern.compile(
            "a\\s*=\\s*toNumbers\\(\\\"([0-9a-fA-F]+)\\\"\\)\\s*,\\s*"
                    + "b\\s*=\\s*toNumbers\\(\\\"([0-9a-fA-F]+)\\\"\\)\\s*,\\s*"
                    + "c\\s*=\\s*toNumbers\\(\\\"([0-9a-fA-F]+)\\\"\\)");
    private static final Pattern SCRIPT_LOCATION = Pattern.compile(
            "location\\.href\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']");

    private final Context context;
    private final SharedPreferences preferences;
    private ChannelCatalog.Group[] availableGroups = new ChannelCatalog.Group[0];

    PlaylistManager(Context context) {
        this.context = context.getApplicationContext();
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String getRecommendedUrl() {
        return GithubProxy.apply(RELEASE_URL);
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
        if (bytes.length > MAX_DOWNLOAD_BYTES) {
            throw new IOException("频道源文件超过 2 MB");
        }
        ChannelCatalog.Group[] groups = parse(bytes);
        if (groups.length == 0) {
            throw new IOException("文件中没有可用的频道");
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

    ChannelCatalog.Group[] loadCached() {
        List<Source> sources = getSources();
        List<ChannelCatalog.Group[]> loaded = new ArrayList<ChannelCatalog.Group[]>();
        String embeddedEpg = "";
        appendBuiltInGroups(loaded);
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
        return rememberAndFilterGroups(merge(loaded));
    }

    private void appendBuiltInGroups(List<ChannelCatalog.Group[]> loaded) {
        InputStream builtIn = null;
        try {
            builtIn = context.getAssets().open(BUILT_IN_PLAYLIST);
            loaded.add(parse(readAll(builtIn)));
        } catch (IOException ignored) {
        } finally {
            if (builtIn != null) {
                try {
                    builtIn.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    UpdateResult updateSources(JSONArray input) throws IOException, JSONException {
        List<Source> sources = parseSources(input);
        List<Source> previousSources = getSources();
        saveSources(sources);
        deleteRemovedCaches(previousSources, sources);
        List<ChannelCatalog.Group[]> loaded = new ArrayList<ChannelCatalog.Group[]>();
        appendBuiltInGroups(loaded);
        List<String> warnings = new ArrayList<String>();
        String embeddedEpg = "";
        int enabledCount = 0;
        for (Source source : sources) {
            if (!source.enabled) {
                continue;
            }
            enabledCount++;
            byte[] bytes = null;
            ChannelCatalog.Group[] groups = null;
            try {
                bytes = readSource(source);
                groups = parse(bytes);
                if (!isLocalLocation(source.location)) {
                    writeCache(source, bytes);
                }
            } catch (IOException error) {
                if (!isLocalLocation(source.location)) {
                    try {
                        bytes = readCache(source);
                        groups = parse(bytes);
                        warnings.add(source.name + " 更新失败，已使用缓存");
                    } catch (IOException cacheError) {
                        warnings.add(source.name + "：" + error.getMessage());
                    }
                } else {
                    warnings.add(source.name + "：" + error.getMessage());
                }
            }
            if (bytes == null || groups == null) {
                continue;
            }
            loaded.add(groups);
            if (embeddedEpg.length() == 0) {
                embeddedEpg = discoverEpgUrl(bytes);
            }
        }
        rememberEmbeddedEpgUrl(embeddedEpg);
        ChannelCatalog.Group[] mergedGroups = merge(loaded);
        if (enabledCount > 0 && mergedGroups.length == 0) {
            String message = warnings.isEmpty() ? "已启用的频道源中没有可用频道"
                    : warnings.get(0);
            throw new IOException(message);
        }
        ChannelCatalog.Group[] groups = rememberAndFilterGroups(mergedGroups);
        return new UpdateResult(groups, enabledCount, warnings);
    }

    private synchronized ChannelCatalog.Group[] rememberAndFilterGroups(
            ChannelCatalog.Group[] groups) {
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
        connection.setReadTimeout(20000);
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
            int length = connection.getContentLength();
            if (length > MAX_DOWNLOAD_BYTES) {
                throw new IOException("频道源文件超过 2 MB");
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
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > MAX_DOWNLOAD_BYTES) {
                throw new IOException("频道源文件超过 2 MB");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static ChannelCatalog.Group[] merge(List<ChannelCatalog.Group[]> sourceGroups) {
        Map<String, List<Channel>> merged = new LinkedHashMap<String, List<Channel>>();
        for (ChannelCatalog.Group[] groups : sourceGroups) {
            for (ChannelCatalog.Group group : groups) {
                String groupTitle = normalizeGroupTitle(group.title);
                List<Channel> channels = merged.get(groupTitle);
                if (channels == null) {
                    channels = new ArrayList<Channel>();
                    merged.put(groupTitle, channels);
                }
                for (Channel incoming : group.channels) {
                    int existingIndex = findChannel(channels, incoming);
                    if (existingIndex < 0) {
                        channels.add(incoming);
                    } else {
                        Channel existing = channels.get(existingIndex);
                        for (String url : incoming.urls) {
                            existing = existing.withAdditionalUrl(url);
                        }
                        channels.set(existingIndex, existing);
                    }
                }
            }
        }
        ChannelCatalog.Group[] result = new ChannelCatalog.Group[merged.size()];
        int index = 0;
        for (Map.Entry<String, List<Channel>> entry : merged.entrySet()) {
            List<Channel> channels = entry.getValue();
            result[index++] = new ChannelCatalog.Group(entry.getKey(),
                    ChannelCatalog.SOURCE_CUSTOM,
                    channels.toArray(new Channel[channels.size()]));
        }
        return result;
    }

    private static int findChannel(List<Channel> channels, Channel incoming) {
        for (int index = 0; index < channels.size(); index++) {
            if (sameChannel(channels.get(index), incoming)) {
                return index;
            }
        }
        return -1;
    }

    private static boolean sameChannel(Channel first, Channel second) {
        if (first.name.equalsIgnoreCase(second.name)) {
            return true;
        }
        for (String firstUrl : first.urls) {
            for (String secondUrl : second.urls) {
                if (Channel.sameSourceUrl(firstUrl, secondUrl)) {
                    return true;
                }
            }
        }
        String firstEpg = canonicalText(first.epgId);
        String secondEpg = canonicalText(second.epgId);
        if (firstEpg.length() > 0 && firstEpg.equals(secondEpg)) {
            return true;
        }
        String firstName = canonicalChannelName(first.name);
        String secondName = canonicalChannelName(second.name);
        return firstName.length() > 0 && firstName.equals(secondName);
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
        return value.toLowerCase(Locale.US).replaceAll("[^a-z0-9+\\u4e00-\\u9fff]", "");
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
        Map<String, List<Channel>> groups = new LinkedHashMap<String, List<Channel>>();
        String currentGroup = "在线频道";
        String pendingName = null;
        String pendingGroup = null;
        String pendingEpgId = null;
        int count = 0;
        String[] lines = text.replace("\r", "").split("\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.length() == 0) {
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
                continue;
            }
            if (line.startsWith("#")) {
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
            if (count >= MAX_CHANNELS) {
                break;
            }
        }
        if (count == 0) {
            throw new IOException("频道源中没有找到可播放的 HTTP 或 WebView 地址");
        }
        ChannelCatalog.Group[] result = new ChannelCatalog.Group[groups.size()];
        int index = 0;
        for (Map.Entry<String, List<Channel>> entry : groups.entrySet()) {
            List<Channel> channels = entry.getValue();
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

    private static void add(Map<String, List<Channel>> groups, String groupName,
            String name, String url, String epgId, int index) {
        String safeGroup = normalizeGroupTitle(groupName);
        List<Channel> channels = groups.get(safeGroup);
        if (channels == null) {
            channels = new ArrayList<Channel>();
            groups.put(safeGroup, channels);
        }
        String safeName = name == null || name.trim().length() == 0
                ? "频道 " + (index + 1) : name.trim();
        Channel incoming = new Channel(channelNumber(safeName, epgId, channels.size() + 1),
                safeName, "custom_" + index, url, null, null, null,
                epgId == null || epgId.trim().length() == 0 ? safeName : epgId.trim());
        int existingIndex = findChannel(channels, incoming);
        if (existingIndex >= 0) {
            channels.set(existingIndex, channels.get(existingIndex).withAdditionalUrl(url));
            return;
        }
        channels.add(incoming);
    }

    private static String channelNumber(String name, String epgId, int fallback) {
        String id = epgId == null ? "" : epgId.trim().toUpperCase(Locale.US);
        if (id.startsWith("CCTV") && id.length() > 4) {
            String suffix = id.substring(4);
            if (suffix.matches("[0-9]+\\+?")) {
                return suffix;
            }
        }
        if (name != null && name.startsWith("CCTV-")) {
            int end = name.indexOf(' ', 5);
            String suffix = end > 5 ? name.substring(5, end) : name.substring(5);
            if (suffix.matches("[0-9]+\\+?")) {
                return suffix;
            }
        }
        return String.valueOf(fallback);
    }

    private static boolean isStreamUrl(String text) {
        String value = text.toLowerCase(Locale.US);
        return value.startsWith("http://") || value.startsWith("https://")
                || value.startsWith("rtmp://") || value.startsWith("rtmpt://")
                || value.startsWith("rtmps://")
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
