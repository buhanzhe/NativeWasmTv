package xiao.bu.tv;

/** Process-cached GitHub accelerator; ordinary CDN/LAN URLs are left unchanged. */
final class GithubProxy {
    static final String DEFAULT_BASE_URL = "https://gh-proxy.org/";
    static final String PREFERENCE = "github_proxy_base_url";
    static final String ENABLED_PREFERENCE = "github_proxy_enabled";
    private static volatile boolean enabled = true;
    static final String BACKUP_BASE_URL = "https://gh-proxy.com/";
    private static final String[] BUILTIN_BASE_URLS = {DEFAULT_BASE_URL, BACKUP_BASE_URL,
            "https://ghfile.geekertao.top/", "https://github-proxy.memory-echoes.cn/", "https://github.tbap.top/"};
    private static volatile String baseUrl = DEFAULT_BASE_URL;
    private static String workingBase;
    private static long workingUntil;
    private static final java.util.Map<String, Long> failedUntil = new java.util.HashMap<String, Long>();
    private static synchronized void resetRoutes() { workingBase = null; workingUntil = 0; failedUntil.clear(); }

    private GithubProxy() { }

    static void initialize(android.content.Context context) {
        enabled = context.getSharedPreferences("tv_player", 0).getBoolean(ENABLED_PREFERENCE, true);
        try {
            setBaseUrl(context.getSharedPreferences("tv_player", 0)
                    .getString(PREFERENCE, DEFAULT_BASE_URL));
        } catch (IllegalArgumentException ignored) { baseUrl = DEFAULT_BASE_URL; }
    }

    static String baseUrl() { return baseUrl; }
    static boolean isEnabled() { return enabled; }
    static void setEnabled(boolean value) { if (enabled != value) resetRoutes(); enabled = value; }

    static void setBaseUrl(String value) {
        String input = value == null ? "" : value.trim();
        if (input.length() == 0) { if (!DEFAULT_BASE_URL.equals(baseUrl)) resetRoutes(); baseUrl = DEFAULT_BASE_URL; return; }
        try {
            java.net.URI uri = new java.net.URI(input);
            String scheme = uri.getScheme();
            if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getHost().length() == 0
                    || uri.getUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPort() == 0
                    || uri.getPort() > 65535 || input.length() > 1024) {
                throw new IllegalArgumentException();
            }
            String next = input.endsWith("/") ? input : input + "/";
            if (!next.equals(baseUrl)) resetRoutes();
            baseUrl = next;
        } catch (Exception error) {
            throw new IllegalArgumentException("请输入 HTTP/HTTPS 加速站地址，不含账号、查询参数或锚点");
        }
    }

    static String apply(String url) {
        String source = githubSource(url);
        return source == null ? url : enabled ? candidates(source)[0] : source;
    }

    static String githubSource(String url) {
        String source = unwrap(url);
        try {
            java.net.URL parsed = new java.net.URL(source);
            String host = parsed.getHost();
            if (("http".equalsIgnoreCase(parsed.getProtocol()) || "https".equalsIgnoreCase(parsed.getProtocol()))
                    && ("github.com".equalsIgnoreCase(host) || "raw.githubusercontent.com".equalsIgnoreCase(host)
                    || "objects.githubusercontent.com".equalsIgnoreCase(host) || "release-assets.githubusercontent.com".equalsIgnoreCase(host))) return source;
        } catch (Exception ignored) { }
        return null;
    }

    static synchronized String[] candidates(String url) {
        String source = githubSource(url);
        if (source == null) return new String[] {url};
        if (!enabled) return new String[] {source};
        java.util.LinkedHashSet<String> order = new java.util.LinkedHashSet<String>();
        long now = System.currentTimeMillis();
        if (workingBase != null && workingUntil > now) order.add(workingBase);
        order.add(baseUrl);
        java.util.Collections.addAll(order, BUILTIN_BASE_URLS);
        java.util.List<String> candidates = new java.util.ArrayList<String>();
        for (String prefix : order) {
            Long until = failedUntil.get(prefix);
            if (until == null || until <= now) candidates.add(prefix + source);
        }
        // Allow recovery when every accelerator failed recently.
        if (candidates.isEmpty()) for (String prefix : order) candidates.add(prefix + source);
        return candidates.toArray(new String[candidates.size()]);
    }

    static synchronized void succeeded(String requestUrl) {
        String prefix = routePrefix(requestUrl);
        if (prefix == null) return;
        failedUntil.remove(prefix); workingBase = prefix;
        workingUntil = System.currentTimeMillis() + 5 * 60 * 1000;
    }
    static synchronized void failed(String requestUrl) {
        String prefix = routePrefix(requestUrl);
        if (prefix == null) return;
        failedUntil.put(prefix, System.currentTimeMillis() + 60 * 1000);
        if (prefix.equals(workingBase)) { workingBase = null; workingUntil = 0; }
    }
    private static String routePrefix(String requestUrl) {
        if (requestUrl.startsWith(baseUrl) && githubSource(requestUrl) != null) return baseUrl;
        for (String prefix : BUILTIN_BASE_URLS)
            if (requestUrl.startsWith(prefix) && githubSource(requestUrl) != null) return prefix;
        return null;
    }
    static boolean retryable(int status) { return status == 403 || status == 408 || status == 429 || status >= 500; }

    static String apply(android.content.Context ignored, String url) { return apply(url); }

    static String unwrap(String url) { return unwrap(url, baseUrl); }

    private static String unwrap(String url, String prefix) {
        if (url == null) return null;
        if (url.startsWith(prefix)) return url.substring(prefix.length());
        for (String known : BUILTIN_BASE_URLS) {
            if (url.startsWith(known)) return url.substring(known.length());
        }
        return url;
    }
}
