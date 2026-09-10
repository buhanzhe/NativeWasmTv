package xiao.bu.tv;

/** Process-cached GitHub accelerator; ordinary CDN/LAN URLs are left unchanged. */
final class GithubProxy {
    static final String DEFAULT_BASE_URL = "https://gh-proxy.com/";
    static final String PREFERENCE = "github_proxy_base_url";
    private static volatile String baseUrl = DEFAULT_BASE_URL;

    private GithubProxy() { }

    static void initialize(android.content.Context context) {
        try {
            setBaseUrl(context.getSharedPreferences("tv_player", 0)
                    .getString(PREFERENCE, DEFAULT_BASE_URL));
        } catch (IllegalArgumentException ignored) { baseUrl = DEFAULT_BASE_URL; }
    }

    static String baseUrl() { return baseUrl; }

    static void setBaseUrl(String value) {
        String input = value == null ? "" : value.trim();
        if (input.length() == 0) { baseUrl = DEFAULT_BASE_URL; return; }
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
            baseUrl = input.endsWith("/") ? input : input + "/";
        } catch (Exception error) {
            throw new IllegalArgumentException("请输入 HTTP/HTTPS 加速站地址，不含账号、查询参数或锚点");
        }
    }

    static String apply(String url) {
        if (url == null) return null;
        String prefix = baseUrl;
        String source = unwrap(url, prefix);
        try {
            java.net.URL parsed = new java.net.URL(source);
            String host = parsed.getHost();
            if (("http".equalsIgnoreCase(parsed.getProtocol()) || "https".equalsIgnoreCase(parsed.getProtocol()))
                    && ("github.com".equalsIgnoreCase(host)
                    || "raw.githubusercontent.com".equalsIgnoreCase(host)
                    || "objects.githubusercontent.com".equalsIgnoreCase(host)
                    || "release-assets.githubusercontent.com".equalsIgnoreCase(host))) {
                return prefix + source;
            }
        } catch (java.net.MalformedURLException ignored) { }
        return url;
    }

    static String apply(android.content.Context ignored, String url) { return apply(url); }

    static String unwrap(String url) { return unwrap(url, baseUrl); }

    private static String unwrap(String url, String prefix) {
        if (url == null) return null;
        if (url.startsWith(prefix)) return url.substring(prefix.length());
        return url.startsWith(DEFAULT_BASE_URL) ? url.substring(DEFAULT_BASE_URL.length()) : url;
    }
}
