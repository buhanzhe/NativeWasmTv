package xiao.bu.tv;

/** Explicit browser identities only; legacy automatic values fall back to Windows. */
final class WebSiteProfile {
    static boolean validUserAgentMode(String mode) {
        return "windows".equals(mode) || "macos".equals(mode)
                || "ipad".equals(mode) || "native".equals(mode);
    }

    static String userAgentMode(String selected, String url) {
        return validUserAgentMode(selected) ? selected : "windows";
    }

    static String pageUrl(String selected, String url) {
        return url;
    }

    private WebSiteProfile() { }
}
