package xiao.bu.tv;

import android.os.Build;

/** Selects the GitHub accelerator supported by the current Android TLS stack. */
final class GithubProxy {
    private static final String LEGACY_HTTP_PREFIX = "http://gh.3w.pm/";
    private static final String HTTPS_PREFIX = "https://gh-proxy.com/";

    private GithubProxy() {
    }

    static String apply(String githubUrl) {
        return prefix() + unwrap(githubUrl);
    }

    static String unwrap(String url) {
        if (url.startsWith(LEGACY_HTTP_PREFIX)) {
            return url.substring(LEGACY_HTTP_PREFIX.length());
        }
        if (url.startsWith(HTTPS_PREFIX)) {
            return url.substring(HTTPS_PREFIX.length());
        }
        return url;
    }

    private static String prefix() {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1
                ? LEGACY_HTTP_PREFIX : HTTPS_PREFIX;
    }
}
