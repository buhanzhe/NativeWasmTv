package xiao.bu.tv;

import android.content.Context;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;

final class EpgManager {
    static final String DEFAULT_URL = "https://iptv.burningc4.com/guide.xml";
    private static final String TAG = "EpgManager";
    private static final String CACHE_FILE = "epg-guide-cache.xml";
    private static final int MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024;
    private static final long KEEP_PAST_MS = 6L * 60L * 60L * 1000L;
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
        final Map<String, List<Program>> programsByChannel =
                new HashMap<String, List<Program>>();
    }

    private final Context context;
    private volatile Guide guide = new Guide();
    private volatile boolean loading;
    private volatile int refreshGeneration;
    private volatile String lastError = "";
    private volatile String loadedUrl = "";

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
        String requested = normalize(channel.epgId == null ? channel.name : channel.epgId);
        String channelId = snapshot.channelByAlias.get(requested);
        if (channelId == null) {
            channelId = snapshot.channelByAlias.get(normalize(channel.name));
        }
        if (channelId == null && snapshot.programsByChannel.containsKey(requested)) {
            channelId = requested;
        }
        List<Program> result = channelId == null
                ? null : snapshot.programsByChannel.get(channelId);
        return result == null ? Collections.<Program>emptyList() : result;
    }

    void refresh(final String sourceUrl, final Listener listener) {
        final int requestId;
        synchronized (this) {
            requestId = ++refreshGeneration;
            loading = true;
        }
        lastError = "";
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] cached = readCache();
                    if (cached.length > 0 && requestId == refreshGeneration) {
                        publish(parse(cached), sourceUrl);
                        notifyListener(listener);
                    }
                    byte[] downloaded = download(sourceUrl);
                    Guide parsed = parse(downloaded);
                    writeCache(downloaded);
                    if (requestId == refreshGeneration) {
                        publish(parsed, sourceUrl);
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
        guide = next;
        loadedUrl = sourceUrl == null ? "" : sourceUrl;
        lastError = "";
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

    private void writeCache(byte[] bytes) throws IOException {
        FileOutputStream output = context.openFileOutput(CACHE_FILE, Context.MODE_PRIVATE);
        try {
            output.write(bytes);
        } finally {
            output.close();
        }
    }

    private static byte[] download(String sourceUrl) throws IOException {
        if (sourceUrl == null || sourceUrl.trim().length() == 0) {
            throw new IOException("未配置节目单地址");
        }
        URL url = new URL(sourceUrl.trim());
        String protocol = url.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            throw new IOException("节目单地址仅支持 HTTP 或 HTTPS");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(25000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "nTv/1.5");
        connection.setRequestProperty("Accept-Encoding", "identity");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("节目单下载失败：HTTP " + status);
            }
            if (connection.getContentLength() > MAX_DOWNLOAD_BYTES) {
                throw new IOException("节目单文件超过 8 MB");
            }
            return readAll(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
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
                        && "programme".equals(parser.getName())) {
                    parseProgramme(parser, result, now);
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

    private static void parseProgramme(XmlPullParser parser, Guide result, long now)
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
                || stop < now - KEEP_PAST_MS || start > now + KEEP_FUTURE_MS) {
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
        return result;
    }
}
