package xiao.bu.tv;

import java.net.URI;
import java.util.Locale;

/** Automatic profiles affect channel documents only; explicit UA choices win. */
final class WebSiteProfile {
    static boolean validUserAgentMode(String mode) {
        return "auto".equals(mode) || "windows".equals(mode) || "macos".equals(mode)
                || "ipad".equals(mode) || "native".equals(mode);
    }

    static String userAgentMode(String selected, String url) {
        if (!"auto".equals(selected)) return selected;
        String host = host(url);
        return matches(host, "douyu.com") || matches(host, "huya.com")
                || matches(host, "yy.com") ? "ipad" : "windows";
    }

    static String pageUrl(String selected, String url) {
        if (!"auto".equals(selected) || url == null) return url;
        try {
            URI uri = new URI(url);
            String host = uri.getHost(), path = uri.getRawPath();
            // Only canonical live-room entries. Keep clips, login, custom ports,
            // queries and explicit browser identities under the user's control.
            if (uri.getRawUserInfo() != null || uri.getPort() != -1
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || !("www.douyu.com".equalsIgnoreCase(host) || "douyu.com".equalsIgnoreCase(host))
                    || path == null || !path.matches("/[0-9]+/?")) return url;
            return "https://m.douyu.com" + path
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                    + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
        } catch (Exception ignored) { return url; }
    }

    private static String host(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? "" : host.toLowerCase(Locale.US);
        } catch (Exception ignored) { return ""; }
    }

    private static boolean matches(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private WebSiteProfile() { }
}
