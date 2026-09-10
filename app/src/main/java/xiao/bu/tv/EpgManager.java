package xiao.bu.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;

final class EpgManager {
    static final String DEFAULT_URL = "https://github.com/TvWasm/autoEPG/releases/latest/download/epg.xml";
    private static final String FALLBACK_URL = "http://epg.51zmt.top:8000/e.xml.gz";
    private static final String TAG = "EpgManager";
    private static final String CACHE_FILE = "epg-guide-cache.xml";
    private static final String CACHE_PREFS = "epg_cache";
    private static final String CACHE_SOURCE_URL = "source_url";
    private static final String CACHE_RESOLVED_URL = "resolved_url";
    private static final int MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024;
    private static final long KEEP_FUTURE_MS = 36L * 60L * 60L * 1000L;

    interface Listener {
        void onUpdated();
    }

    static final class Program {
        final long startMillis;
        final long stopMillis;
        final String title;

        Program(long startMillis, long stopMillis, String title) {
            this.startMillis = startMillis;
            this.stopMillis = stopMillis;
            this.title = title;
        }

        boolean isPlaying(long now) {
            return now >= startMillis && now < stopMillis;
        }
    }

    private static final class Guide {
        final Map<String, String> channelByAlias = new HashMap<String, String>();
        final Map<String, String> logosByChannel = new HashMap<String, String>();
        final Map<String, List<Program>> programsByChannel =
                new HashMap<String, List<Program>>();
    }

    private final Context context;
    private volatile Guide guide = new Guide();
    private volatile boolean loading;
    private volatile int refreshGeneration;
    private volatile String lastError = "";
    private volatile String loadedUrl = "";
    private volatile String cachedGuideSource;
    private String attemptedSource;
    private long attemptedDay;

    EpgManager(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean isLoading() {
        return loading;
    }

    String getLastError() {
        return lastError;
    }

    String getLoadedUrl() {
        return loadedUrl;
    }

    List<Program> programsFor(Channel channel) {
        if (channel == null) {
            return Collections.emptyList();
        }
        Guide snapshot = guide;
        String channelId = channelIdFor(snapshot, channel);
        List<Program> result = channelId == null
                ? null : snapshot.programsByChannel.get(channelId);
        return result == null ? Collections.<Program>emptyList() : result;
    }

    String logoFor(Channel channel) {
        if (channel == null) return "";
        if (channel.logoUrl.length() > 0) return channel.logoUrl;
        Guide snapshot = guide;
        String id = channelIdFor(snapshot, channel);
        String logo = id == null ? null : snapshot.logosByChannel.get(id);
        return logo == null ? "" : logo;
    }

    private static String channelIdFor(Guide snapshot, Channel channel) {
        String requested = normalize(channel.epgId == null ? channel.name : channel.epgId);
        String channelId = snapshot.channelByAlias.get(requested);
        if (channelId == null) {
            channelId = snapshot.channelByAlias.get(normalize(channel.name));
        }
        if (channelId == null && snapshot.programsByChannel.containsKey(requested)) {
            channelId = requested;
        }
        return channelId;
    }

    void refresh(final String sourceUrl, final Listener listener) {
        final int requestId;
        synchronized (this) {
            String source = sourceUrl == null ? "" : sourceUrl.trim();
            long day = dayStart(System.currentTimeMillis());
            if (source.equals(attemptedSource) && attemptedDay == day) return;
            attemptedSource = source;
            attemptedDay = day;
            requestId = ++refreshGeneration;
            loading = true;
        }
        lastError = "";
        new Thread(new Runnable() {
            @Override
            public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                try {
                    String normalizedUrl = sourceUrl == null ? "" : sourceUrl.trim();
                    // Reuse the parsed guide too, not just the XML download cache.
                    if (normalizedUrl.equals(cachedGuideSource) && isCacheFresh()) return;
                    byte[] cached = readCache();
                    boolean cacheMatches = normalizedUrl.equals(context
                            .getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
                            .getString(CACHE_SOURCE_URL, ""));
                    if (cached.length > 0 && cacheMatches
                            && requestId == refreshGeneration) {
                        try {
                            String cachedUrl = context.getSharedPreferences(CACHE_PREFS,
                                    Context.MODE_PRIVATE).getString(CACHE_RESOLVED_URL, normalizedUrl);
                            publish(parse(cached), cachedUrl);
                            cachedGuideSource = normalizedUrl;
                            if (isCacheFresh()) {
                                return;
                            }
                            // Show stale data while the refresh is downloading. A fresh
                            // cache is notified once from finally after loading is cleared.
                            notifyListener(listener);
                        } catch (Exception cacheError) {
                            context.deleteFile(CACHE_FILE);
                            Log.w(TAG, "Ignoring invalid EPG cache", cacheError);
                        }
                    }
                    Download downloaded;
                    Guide parsed;
                    String loadedSource = normalizedUrl;
                    try {
                        downloaded = download(normalizedUrl);
                        parsed = parse(downloaded.bytes);
                    } catch (Exception primaryError) {
                        if (!DEFAULT_URL.equals(normalizedUrl)) {
                            throw primaryError;
                        }
                        Log.w(TAG, "Primary EPG unavailable; trying fallback", primaryError);
                        downloaded = download(FALLBACK_URL);
                        parsed = parse(downloaded.bytes);
                    }
                    loadedSource = downloaded.url;
                    synchronized (EpgManager.this) {
                        if (requestId == refreshGeneration) {
                            writeCache(downloaded.bytes, normalizedUrl, loadedSource);
                            publish(parsed, loadedSource);
                            cachedGuideSource = normalizedUrl;
                        }
                    }
                } catch (Exception error) {
                    if (requestId == refreshGeneration) {
                        lastError = error.getMessage() == null
                                ? error.getClass().getSimpleName() : error.getMessage();
                    }
                    Log.w(TAG, "Unable to refresh EPG", error);
                } finally {
                    if (requestId == refreshGeneration) {
                        loading = false;
                    }
                    notifyListener(listener);
                }
            }
        }, "epg-refresh").start();
    }

    private void publish(Guide next, String sourceUrl) {
        // Resolve once per guide, not during each information-card refresh.
        for (Map.Entry<String, String> icon : next.logosByChannel.entrySet()) {
            try {
                icon.setValue(checkedHttpUrl(new URL(new URL(sourceUrl), icon.getValue())).toString());
            } catch (Exception ignored) { icon.setValue(""); }
        }
        guide = next;
        loadedUrl = sourceUrl == null ? "" : sourceUrl;
        lastError = "";
        Log.i(TAG, "EPG loaded source=" + loadedUrl
                + " aliases=" + next.channelByAlias.size()
                + " channels=" + next.programsByChannel.size());
    }

    private static void notifyListener(Listener listener) {
        if (listener != null) {
            listener.onUpdated();
        }
    }

    private byte[] readCache() {
        try {
            FileInputStream input = context.openFileInput(CACHE_FILE);
            try {
                return readAll(input);
            } finally {
                input.close();
            }
        } catch (IOException ignored) {
            return new byte[0];
        }
    }

    private boolean isCacheFresh() {
        File cache = context.getFileStreamPath(CACHE_FILE);
        long now = System.currentTimeMillis();
        return cache.isFile() && cache.lastModified() <= now
                && dayStart(cache.lastModified()) == dayStart(now);
    }

    static long dayStart(long millis) {
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(millis);
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        return day.getTimeInMillis();
    }

    private void writeCache(byte[] bytes, String sourceUrl, String resolvedUrl) throws IOException {
        FileOutputStream output = context.openFileOutput(CACHE_FILE, Context.MODE_PRIVATE);
        try {
            output.write(bytes);
        } finally {
            output.close();
        }
        SharedPreferences preferences = context.getSharedPreferences(
                CACHE_PREFS, Context.MODE_PRIVATE);
        // Keep the cache file and its source identity in sync across cold starts.
        //noinspection ApplySharedPref
        preferences.edit().putString(CACHE_SOURCE_URL, sourceUrl)
                .putString(CACHE_RESOLVED_URL, resolvedUrl).commit();
    }

    private static final class Download {
        final byte[] bytes;
        final String url;
        Download(byte[] bytes, String url) { this.bytes = bytes; this.url = url; }
    }

    private static Download download(String sourceUrl) throws IOException {
        if (sourceUrl == null || sourceUrl.trim().length() == 0) {
            throw new IOException("未配置节目单地址");
        }
        URL current = checkedHttpUrl(new URL(sourceUrl.trim()));
        for (int redirects = 0; redirects <= 5; redirects++) {
            // Apply the shared accelerator to GitHub EPGs and release redirects.
            // Keep the logical source URL for cache identity and relative icons.
            URL requestUrl = checkedHttpUrl(new URL(GithubProxy.apply(null, current.toString())));
            HttpURLConnection connection = NetworkClient.open(requestUrl);
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(25000);
            // Android 7 does not reliably follow an HTTP -> HTTPS redirect. Handle
            // redirects here so stable EPG entry points can rotate their CDN URL.
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 nTv/1.6");
            connection.setRequestProperty("Accept",
                    "application/xml,text/xml,application/gzip,*/*");
            connection.setRequestProperty("Accept-Encoding", "identity");
            try {
                int status = connection.getResponseCode();
                if (status == HttpURLConnection.HTTP_MOVED_PERM
                        || status == HttpURLConnection.HTTP_MOVED_TEMP
                        || status == HttpURLConnection.HTTP_SEE_OTHER
                        || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.trim().length() == 0) {
                        throw new IOException("节目单重定向地址为空");
                    }
                    current = checkedHttpUrl(new URL(requestUrl, location.trim()));
                    continue;
                }
                if (status < 200 || status >= 300) {
                    throw new IOException("节目单下载失败：HTTP " + status);
                }
                if (connection.getContentLength() > MAX_DOWNLOAD_BYTES) {
                    throw new IOException("节目单文件超过 8 MB");
                }
                return new Download(readAll(connection.getInputStream()), GithubProxy.unwrap(current.toString()));
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("节目单重定向次数过多");
    }

    private static URL checkedHttpUrl(URL url) throws IOException {
        String protocol = url.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            throw new IOException("节目单地址仅支持 HTTP 或 HTTPS");
        }
        return url;
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > MAX_DOWNLOAD_BYTES) {
                throw new IOException("节目单文件超过 8 MB");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static Guide parse(byte[] bytes) throws Exception {
        InputStream input = new ByteArrayInputStream(bytes);
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b) {
            input = new GZIPInputStream(input);
        }
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(input, null);
            Guide result = new Guide();
            String currentChannelId = null;
            long now = System.currentTimeMillis();
            long today = dayStart(now);
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && "channel".equals(parser.getName())) {
                    currentChannelId = normalize(parser.getAttributeValue(null, "id"));
                    if (currentChannelId.length() > 0) {
                        result.channelByAlias.put(currentChannelId, currentChannelId);
                    }
                } else if (event == XmlPullParser.END_TAG
                        && "channel".equals(parser.getName())) {
                    currentChannelId = null;
                } else if (event == XmlPullParser.START_TAG
                        && "display-name".equals(parser.getName())
                        && currentChannelId != null) {
                    String alias = normalize(parser.nextText());
                    if (alias.length() > 0) {
                        result.channelByAlias.put(alias, currentChannelId);
                    }
                } else if (event == XmlPullParser.START_TAG
                        && "icon".equals(parser.getName()) && currentChannelId != null) {
                    String icon = parser.getAttributeValue(null, "src");
                    if (icon != null && icon.trim().length() > 0
                            && !result.logosByChannel.containsKey(currentChannelId)) {
                        result.logosByChannel.put(currentChannelId, icon.trim());
                    }
                } else if (event == XmlPullParser.START_TAG
                        && "programme".equals(parser.getName())) {
                    parseProgramme(parser, result, now, today);
                }
            }
            for (List<Program> programs : result.programsByChannel.values()) {
                Collections.sort(programs, new Comparator<Program>() {
                    @Override
                    public int compare(Program left, Program right) {
                        return left.startMillis < right.startMillis ? -1
                                : left.startMillis == right.startMillis ? 0 : 1;
                    }
                });
            }
            return result;
        } finally {
            input.close();
        }
    }

    private static void parseProgramme(XmlPullParser parser, Guide result, long now, long today)
            throws Exception {
        String channelId = normalize(parser.getAttributeValue(null, "channel"));
        long start = parseXmlTvTime(parser.getAttributeValue(null, "start"));
        long stop = parseXmlTvTime(parser.getAttributeValue(null, "stop"));
        String title = "未命名节目";
        int depth = parser.getDepth();
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && "title".equals(parser.getName())) {
                String value = parser.nextText();
                if (value != null && value.trim().length() > 0) {
                    title = value.trim();
                }
            } else if (event == XmlPullParser.END_TAG && parser.getDepth() == depth
                    && "programme".equals(parser.getName())) {
                break;
            }
        }
        if (channelId.length() == 0 || start <= 0L || stop <= start
                || stop <= today || start > now + KEEP_FUTURE_MS) {
            return;
        }
        List<Program> programs = result.programsByChannel.get(channelId);
        if (programs == null) {
            programs = new ArrayList<Program>();
            result.programsByChannel.put(channelId, programs);
        }
        programs.add(new Program(start, stop, title));
    }

    private static long parseXmlTvTime(String raw) {
        if (raw == null) {
            return -1L;
        }
        String value = raw.trim();
        String[] patterns = new String[] { "yyyyMMddHHmmss Z", "yyyyMMddHHmmssZ",
                "yyyyMMddHHmmss" };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                format.setLenient(false);
                if (pattern.indexOf('Z') < 0) {
                    format.setTimeZone(TimeZone.getDefault());
                }
                Date parsed = format.parse(value);
                if (parsed != null) {
                    return parsed.getTime();
                }
            } catch (ParseException ignored) {
            }
        }
        return -1L;
    }

    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.toUpperCase(Locale.US)
                .replace("中央电视台", "CCTV")
                .replace("央视", "CCTV")
                .replace("中国教育电视台", "CETV")
                .replace("福建东南卫视", "东南卫视")
                .replace("CGTN阿拉伯语", "CGTN阿语")
                .replace("CGTN西班牙语", "CGTN西语")
                .replace("CGTN外语纪录", "CGTN纪录")
                .replace("高清", "")
                .replace("频道", "")
                .replace("HD", "")
                .replace("PLUS", "+")
                .replace('＋', '+');
        StringBuilder normalized = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isLetterOrDigit(character) || character == '+'
                    || (character >= '\u4e00' && character <= '\u9fff')) {
                normalized.append(character);
            }
        }
        String result = normalized.toString();
        if (result.startsWith("CCTV")) {
            if ("CCTV4K".equals(result) || "CCTV8K".equals(result)) {
                return result;
            }
            if (result.startsWith("CCTV16") && result.endsWith("4K")) {
                return "CCTV16";
            }
            int index = 4;
            StringBuilder number = new StringBuilder("CCTV");
            while (index < result.length() && Character.isDigit(result.charAt(index))) {
                number.append(result.charAt(index++));
            }
            if (index < result.length() && result.charAt(index) == '+') {
                number.append('+');
            }
            if (number.length() > 4) {
                return number.toString();
            }
        }
        if (result.endsWith("卫视4K")) {
            return result.substring(0, result.length() - 2);
        }
        return result;
    }
}
