package xiao.bu.tv;

import java.util.Locale;

final class Channel {
    final String number;
    final String name;
    final String streamId;
    final String url;
    final String[] urls;
    final String yangshipinPid;
    final String yangshipinStreamId;
    final String yangshipinMaxDefinition;
    final String epgId;
    final int catalogSource;
    final String favoriteKey;

    Channel(String number, String name, String streamId, String url,
            String yangshipinPid, String yangshipinStreamId) {
        this(number, name, streamId, url, yangshipinPid, yangshipinStreamId, null);
    }

    Channel(String number, String name, String streamId, String url,
            String yangshipinPid, String yangshipinStreamId,
            String yangshipinMaxDefinition) {
        this(number, name, streamId, url, yangshipinPid, yangshipinStreamId,
                yangshipinMaxDefinition, null);
    }

    Channel(String number, String name, String streamId, String url,
            String yangshipinPid, String yangshipinStreamId,
            String yangshipinMaxDefinition, String epgId) {
        this(number, name, streamId,
                url == null ? new String[0] : new String[] { url },
                yangshipinPid, yangshipinStreamId, yangshipinMaxDefinition, epgId);
    }

    Channel(String number, String name, String streamId, String[] urls,
            String yangshipinPid, String yangshipinStreamId,
            String yangshipinMaxDefinition, String epgId) {
        this(number, name, streamId, urls, yangshipinPid, yangshipinStreamId,
                yangshipinMaxDefinition, epgId, -1, null);
    }

    private Channel(String number, String name, String streamId, String[] urls,
            String yangshipinPid, String yangshipinStreamId,
            String yangshipinMaxDefinition, String epgId, int catalogSource,
            String favoriteKey) {
        this.number = number;
        this.name = name;
        this.streamId = streamId;
        this.urls = uniqueSourceUrls(urls);
        this.url = this.urls.length == 0 ? null : this.urls[0];
        this.yangshipinPid = yangshipinPid;
        this.yangshipinStreamId = yangshipinStreamId;
        this.yangshipinMaxDefinition = yangshipinMaxDefinition;
        this.epgId = epgId;
        this.catalogSource = catalogSource;
        this.favoriteKey = favoriteKey;
    }

    Channel withAdditionalUrl(String additionalUrl) {
        if (additionalUrl == null || additionalUrl.trim().length() == 0) {
            return this;
        }
        String candidate = additionalUrl.trim();
        for (String existing : urls) {
            if (sameSourceUrl(existing, candidate)) {
                return this;
            }
        }
        String[] combined = new String[urls.length + 1];
        System.arraycopy(urls, 0, combined, 0, urls.length);
        combined[urls.length] = candidate;
        return new Channel(number, name, streamId, combined,
                yangshipinPid, yangshipinStreamId, yangshipinMaxDefinition, epgId,
                catalogSource, favoriteKey);
    }

    static boolean sameSourceUrl(String first, String second) {
        return canonicalSourceUrl(first).equals(canonicalSourceUrl(second));
    }

    static String canonicalSourceUrl(String sourceUrl) {
        if (sourceUrl == null) {
            return "";
        }
        String value = sourceUrl.trim();
        String lower = value.toLowerCase(Locale.US);
        if (lower.startsWith("webview://")) {
            value = value.substring("webview://".length());
            lower = value.toLowerCase(Locale.US);
        }
        int fragment = value.indexOf('#');
        if (fragment >= 0) {
            value = value.substring(0, fragment);
            lower = value.toLowerCase(Locale.US);
        }
        if (lower.indexOf("yangshipin.cn") >= 0) {
            String pid = queryValue(lower, "pid");
            if (pid.length() > 0) {
                return "yangshipin:pid=" + pid;
            }
        }
        String cctvStream = cctvStreamKey(lower);
        if (cctvStream.length() > 0) {
            return "cctv:stream=" + cctvStream;
        }
        value = value.replace("://www.yangshipin.cn", "://yangshipin.cn")
                .replace("://www.tv.cctv.com", "://tv.cctv.com");
        value = value.replace("/?", "?");
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.toLowerCase(Locale.US);
    }

    /**
     * CCTV's live page and its CDN playlist are two entrances to the same stream.
     * Treat them as one source while keeping Yangshipin as an independent fallback.
     */
    private static String cctvStreamKey(String lowerUrl) {
        String pageMarker = "tv.cctv.com/live/";
        int pageStart = lowerUrl.indexOf(pageMarker);
        if (pageStart >= 0) {
            return pathToken(lowerUrl, pageStart + pageMarker.length());
        }
        String playlistMarker = "/cdrmld";
        int playlistStart = lowerUrl.indexOf(playlistMarker);
        if (playlistStart >= 0) {
            int start = playlistStart + playlistMarker.length();
            int end = lowerUrl.indexOf('_', start);
            if (end > start && lowerUrl.indexOf("/index.m3u8", end) >= 0) {
                return lowerUrl.substring(start, end);
            }
        }
        return "";
    }

    private static String pathToken(String value, int start) {
        int end = start;
        while (end < value.length()) {
            char current = value.charAt(end);
            if (current == '/' || current == '?' || current == '#' || current == '&') {
                break;
            }
            end++;
        }
        return end > start ? value.substring(start, end) : "";
    }

    private static String[] uniqueSourceUrls(String[] sourceUrls) {
        if (sourceUrls == null || sourceUrls.length == 0) {
            return new String[0];
        }
        String[] unique = new String[sourceUrls.length];
        int count = 0;
        for (String sourceUrl : sourceUrls) {
            if (sourceUrl == null || sourceUrl.trim().length() == 0) {
                continue;
            }
            String candidate = sourceUrl.trim();
            boolean duplicate = false;
            for (int index = 0; index < count; index++) {
                if (sameSourceUrl(unique[index], candidate)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                unique[count++] = candidate;
            }
        }
        if (count == unique.length) {
            return unique;
        }
        String[] result = new String[count];
        System.arraycopy(unique, 0, result, 0, count);
        return result;
    }

    private static String queryValue(String lowerUrl, String key) {
        String marker = key.toLowerCase(Locale.US) + "=";
        int start = lowerUrl.indexOf(marker);
        while (start >= 0) {
            if (start == 0 || lowerUrl.charAt(start - 1) == '?'
                    || lowerUrl.charAt(start - 1) == '&') {
                start += marker.length();
                int end = start;
                while (end < lowerUrl.length()) {
                    char value = lowerUrl.charAt(end);
                    if (value == '&' || value == '#') {
                        break;
                    }
                    end++;
                }
                return lowerUrl.substring(start, end);
            }
            start = lowerUrl.indexOf(marker, start + marker.length());
        }
        return "";
    }

    Channel asFavorite(String key, int source) {
        return new Channel(number, name, streamId, urls,
                yangshipinPid, yangshipinStreamId, yangshipinMaxDefinition, epgId,
                source, key);
    }

    Channel withCatalogSource(int source) {
        return new Channel(number, name, streamId, urls,
                yangshipinPid, yangshipinStreamId, yangshipinMaxDefinition, epgId,
                source, favoriteKey);
    }

    int sourceCount() {
        return urls.length;
    }

    String sourceUrl(int index) {
        if (urls.length == 0) {
            return null;
        }
        int wrapped = (index % urls.length + urls.length) % urls.length;
        return urls[wrapped];
    }
}
