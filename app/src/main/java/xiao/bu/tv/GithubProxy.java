package xiao.bu.tv;

/** Uses one HTTPS accelerator on every Android version, including API 14/15. */
final class GithubProxy {
    private static final String HTTPS_PREFIX = "https://gh-proxy.com/";

    private GithubProxy() {
    }

    static String apply(String githubUrl) {
        return HTTPS_PREFIX + unwrap(githubUrl);
    }

    // Playlist and updater callers may pass ordinary CDN or local-network URLs.
    static String apply(android.content.Context ignored, String url) {
        if (url == null) return null;
        String source = unwrap(url);
        try {
            String host = new java.net.URL(source).getHost();
            if ("github.com".equalsIgnoreCase(host)
                    || "raw.githubusercontent.com".equalsIgnoreCase(host)
                    || "objects.githubusercontent.com".equalsIgnoreCase(host)
                    || "release-assets.githubusercontent.com".equalsIgnoreCase(host)) {
                return apply(source);
            }
        } catch (java.net.MalformedURLException ignoredUrl) { }
        return url;
    }

    static String unwrap(String url) {
        return url.startsWith(HTTPS_PREFIX) ? url.substring(HTTPS_PREFIX.length()) : url;
    }
}
