package xiao.bu.tv;

/** Bundled management website. Exact routes keep unrelated app assets private. */
final class ControlSite {
    private static final String[] PAGES = {
            "media", "cast", "playback", "channels", "browser", "system", "groups", "flymouse",
            "advanced"
    };

    private ControlSite() { }

    /** Exact server origin only; never resolve host names on the input path. */
    static boolean ownsOrigin(String url, int port, String lanAddress) {
        if (url == null || port <= 0) return false;
        try {
            java.net.URI uri = new java.net.URI(url);
            String host = uri.getHost();
            return "http".equalsIgnoreCase(uri.getScheme()) && uri.getRawUserInfo() == null
                    && uri.getPort() == port && ("127.0.0.1".equals(host)
                    || (lanAddress != null && lanAddress.equals(host)));
        } catch (java.net.URISyntaxException error) {
            return false;
        }
    }

    static boolean contains(String path) {
        if (isAdvancedAlias(path)) return true;
        if ("/".equals(path) || "/index.html".equals(path)
                || "/css/common.css".equals(path) || "/js/common.js".equals(path)
                || "/css/night.css".equals(path) || "/js/theme.js".equals(path)
                || "/css/media.css".equals(path)
                || "/js/channel-picker.js".equals(path)
                || "/js/pointer-queue.js".equals(path)
                || "/js/pages/home.js".equals(path)) {
            return true;
        }
        for (String page : PAGES) {
            if (("/pages/" + page + ".html").equals(path)
                    || ("/js/pages/" + page + ".js").equals(path)) {
                return true;
            }
        }
        return false;
    }

    static String assetPath(String path) {
        if (!contains(path)) {
            throw new IllegalArgumentException("网页资源不存在");
        }
        if (isAdvancedAlias(path)) return "control/pages/advanced.html";
        return "control/" + ("/".equals(path) ? "index.html" : path.substring(1));
    }

    private static boolean isAdvancedAlias(String path) {
        return "/pages/operation.html".equals(path) || "/pages/decoder.html".equals(path);
    }

    static String contentType(String path) {
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        return "text/html; charset=utf-8";
    }
}
