package xiao.bu.tv;

import java.net.URI;
import java.security.MessageDigest;

/** Fingerprinted upstream defects only; never general decoding or error suppression. */
final class LegacyScriptSourceAdapter {
    private static final String URL = "https://www.hrbtv.net/t/1/7/js/subnav.js?v=20241218214025";
    private static final String SHA256 = "5b7b329f8fa198968a0e154c020cfe23a70211da63c49f5024c5660cecf33b0d";
    static String adapt(String document, String requested, String source) {
        if ("http://www.hsnewsnet.com/js/pc/liveList20211026.js".equals(requested))
            return huangshanServer(document, source);
        if ("http://www.hsnewsnet.com/js/pc/jCarouselLite.js".equals(requested))
            return huangshan(document, source);
        if ("https://v.xiancity.cn/t/1/4/js/base.js".equals(requested))
            return xian(document, source);
        if (source == null || source.length() > 2048 || !URL.equals(requested)) return source;
        try {
            URI page = URI.create(document);
            if (!"https".equals(page.getScheme()) || !"www.hrbtv.net".equals(page.getHost())
                    || page.getPort() != -1 || page.getRawUserInfo() != null
                    || !"/folder79/folder81/".equals(page.getPath())) return source;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes("UTF-8"));
            StringBuilder hash = new StringBuilder(64);
            for (byte value : digest) hash.append("0123456789abcdef".charAt((value >> 4) & 15))
                    .append("0123456789abcdef".charAt(value & 15));
            if (!SHA256.equals(hash.toString())) return source;
            // Only entities present in this exact captured JavaScript. No recursive
            // decoding, DOM execution, script replacement, or upstream requests.
            return source.replace("&#036;", "$").replace("&quot;", "\"")
                    .replace("&lt;", "<").replace("&gt;", ">");
        } catch (Exception mismatch) { return source; }
    }
    private static String xian(String document, String source) {
        if (source == null || source.length() > 32768) return source;
        try {
            URI page = URI.create(document);
            if (!"https".equals(page.getScheme()) || !"v.xiancity.cn".equals(page.getHost())
                    || page.getPort() != -1 || page.getRawUserInfo() != null
                    || !"/folder8/folder39/folder29/".equals(page.getPath())) return source;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes("UTF-8"));
            StringBuilder hash = new StringBuilder(64);
            for (byte value : digest) hash.append("0123456789abcdef".charAt((value >> 4) & 15))
                    .append("0123456789abcdef".charAt(value & 15));
            if (!"aefac8d3b9f2a73f908f31703763d1066ff5281c1c0d1c26a7fca899c801a755".equals(hash.toString())) return source;
            // This exact page's observer can run during navigation after body teardown.
            // Skip only the absent-body invocation. Keep the observer and recursive
            // live-DOM work intact; invalid inputs with a live body must still throw.
            String entry = "function removeText(node, targetStr) {";
            String ready = "document.addEventListener('DOMContentLoaded', function () {";
            return source.replace(entry, entry + "\n    if (node === null && document.body === null) return;")
                    .replace(ready, ready + "\n    if (document.body === null) return;");
        } catch (Exception mismatch) { return source; }
    }
    private static String huangshan(String document, String source) {
        if (source == null || source.length() > 16384) return source;
        try {
            URI page = URI.create(document);
            if (!"http".equals(page.getScheme()) || !"www.hsnewsnet.com".equals(page.getHost())
                    || page.getPort() != -1 || page.getRawUserInfo() != null
                    || !"/web/tvnews/".equals(page.getPath())
                    || page.getRawQuery() != null) return source;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes("UTF-8"));
            StringBuilder hash = new StringBuilder(64);
            for (byte value : digest) hash.append("0123456789abcdef".charAt((value >> 4) & 15))
                    .append("0123456789abcdef".charAt(value & 15));
            if (!"3f6e9ad7109eef107f66b993ee51c17e189a8db6e2b4f2ba2f504f2bad731438".equals(hash.toString())) return source;
            // The captured page contains an empty carousel UL. This plugin assumes
            // a first LI exists when measuring offsetWidth. Skip only that empty
            // per-container initialization, preserving chaining and nonempty work.
            String entry = "        if(o.circular) {";
            int offset = source.indexOf(entry);
            if (offset < 0) return source;
            return source.substring(0, offset) + "        if (tl === 0) return;\n\n"
                    + source.substring(offset);
        } catch (Exception mismatch) { return source; }
    }
    private static String huangshanServer(String document, String source) {
        if (source == null || source.length() > 16384) return source;
        try {
            URI page = URI.create(document);
            if (!"http".equals(page.getScheme()) || !"www.hsnewsnet.com".equals(page.getHost())
                    || page.getPort() != -1 || page.getRawUserInfo() != null
                    || !"/web/tvnews/".equals(page.getPath())
                    || page.getRawQuery() != null) return source;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes("UTF-8"));
            StringBuilder hash = new StringBuilder(64);
            for (byte value : digest) hash.append("0123456789abcdef".charAt((value >> 4) & 15))
                    .append("0123456789abcdef".charAt(value & 15));
            if (!"b03cd86fc5987d8bd1004fa28945a0545b2f1d6c54f14e2c2a58c1c612002913".equals(hash.toString())) return source;
            // Match the same page's initial player configuration. The channel
            // selector mistakenly supplies a bare hostname to new URL(path, base).
            // Repair this one known config; do not alter URL parsing or HTTPS URLs.
            return source.replace("server: \"hslive.hsnewsnet.com\"", "server: \"http://hslive.hsnewsnet.com\"");
        } catch (Exception mismatch) { return source; }
    }
    private LegacyScriptSourceAdapter() { }
}
