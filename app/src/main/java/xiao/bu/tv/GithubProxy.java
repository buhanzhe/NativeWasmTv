package xiao.bu.tv;

/** Uses one HTTPS accelerator on every Android version, including API 14/15. */
final class GithubProxy {
    private static final String HTTPS_PREFIX = "https://gh-proxy.com/";

    private GithubProxy() {
    }

    static String apply(String githubUrl) {
        return HTTPS_PREFIX + unwrap(githubUrl);
    }

    static String unwrap(String url) {
        return url.startsWith(HTTPS_PREFIX) ? url.substring(HTTPS_PREFIX.length()) : url;
    }
}
