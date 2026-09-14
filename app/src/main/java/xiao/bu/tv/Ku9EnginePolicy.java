package xiao.bu.tv;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative capability detection; a script header can explicitly select its engine. */
final class Ku9EnginePolicy {
    private static final Pattern DECLARATION = Pattern.compile(
            "(?im)^\\s*//\\s*@ku9-engine\\s*:?\\s*(webview|quickjs)\\s*$");
    private static final Pattern BROWSER = Pattern.compile(
            "document|navigator|location|history|screen|XMLHttpRequest|fetch|DOMParser|XMLSerializer|"
            + "Image|Audio|HTMLElement|HTMLCanvasElement|OffscreenCanvas|localStorage|sessionStorage|"
            + "setTimeout|setInterval|requestAnimationFrame|addEventListener|WebSocket|Worker|"
            + "URL|URLSearchParams|indexedDB|MutationObserver|FileReader|Blob|FormData|performance");

    static boolean usesWebView(String script) {
        Matcher declaration = DECLARATION.matcher(script.substring(0, Math.min(script.length(), 4096)));
        if (declaration.find()) return "webview".equalsIgnoreCase(declaration.group(1));
        String previous = "", beforePrevious = "";
        // Scan once without recursive regex matching: bundled scripts may contain
        // very long strings. Comments and ordinary string contents are not APIs.
        int length = script.length();
        for (int i = 0; i < length;) {
            char c = script.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '/' && i + 1 < length && script.charAt(i + 1) == '/') {
                i += 2;
                while (i < length && script.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < length && script.charAt(i + 1) == '*') {
                int end = script.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
                continue;
            }
            int start = i++;
            boolean quoted = c == '\'' || c == '"' || c == '`';
            if (quoted) {
                while (i < length) {
                    char next = script.charAt(i++);
                    if (next == '\\' && i < length) { i++; continue; }
                    if (next == c) break;
                }
            } else if (Character.isJavaIdentifierStart(c)) {
                while (i < length && Character.isJavaIdentifierPart(script.charAt(i))) i++;
            }
            String token = script.substring(start, i);
            if (!quoted && BROWSER.matcher(token).matches()) return true;
            if (quoted && token.length() >= 2 && "[".equals(previous)
                    && ("window".equals(beforePrevious) || "globalThis".equals(beforePrevious))
                    && BROWSER.matcher(token.substring(1, token.length() - 1)).matches()) return true;
            beforePrevious = previous;
            previous = token;
        }
        return false;
    }

    private Ku9EnginePolicy() { }
}
