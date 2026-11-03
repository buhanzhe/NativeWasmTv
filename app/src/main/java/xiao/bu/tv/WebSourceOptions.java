package xiao.bu.tv;

import android.util.Base64;
import java.net.URLDecoder;
import java.util.ArrayList;

/** Client-side options carried by a webview source, separate from the site's URL. */
final class WebSourceOptions {
    final String url;
    final String script;

    private WebSourceOptions(String url, String script) {
        this.url = url;
        this.script = script;
    }

    static WebSourceOptions parse(String url, String explicitScript) {
        String script = explicitScript == null ? "" : explicitScript;
        int fragment = url.indexOf('#');
        int query = url.indexOf('?');
        if (query < 0 || fragment >= 0 && query > fragment) return new WebSourceOptions(url, script);
        int end = fragment < 0 ? url.length() : fragment;
        ArrayList<String> kept = new ArrayList<>();
        String decoded = "";
        boolean found = false;
        for (String parameter : url.substring(query + 1, end).split("&", -1)) {
            int equals = parameter.indexOf('=');
            String name = equals < 0 ? parameter : parameter.substring(0, equals);
            if (!"ujs".equalsIgnoreCase(decodeParameter(name))) {
                kept.add(parameter);
                continue;
            }
            found = true;
            if (decoded.length() == 0 && equals >= 0) {
                String value = parameter.substring(equals + 1);
                if (value.length() <= 256 * 1024) try {
                    // URLDecoder normally turns '+' into a space, corrupting standard Base64.
                    value = decodeParameter(value).replace('-', '+').replace('_', '/').replaceAll("\\s", "");
                    if (!value.matches("[A-Za-z0-9+/]*={0,2}") || value.length() % 4 == 1)
                        throw new IllegalArgumentException("Invalid ujs Base64");
                    decoded = new String(Base64.decode(value, Base64.DEFAULT), "UTF-8");
                } catch (Exception ignored) { /* An invalid optional script must not crash the page. */ }
            }
        }
        if (!found) return new WebSourceOptions(url, script);
        StringBuilder target = new StringBuilder(url.substring(0, query));
        for (int i = 0; i < kept.size(); i++) target.append(i == 0 ? '?' : '&').append(kept.get(i));
        if (fragment >= 0) target.append(url.substring(fragment));
        if (decoded.length() > 0) script = script.length() == 0 ? decoded : script + "\n;\n" + decoded;
        return new WebSourceOptions(target.toString(), script);
    }

    private static String decodeParameter(String value) {
        try { return URLDecoder.decode(value.replace("+", "%2B"), "UTF-8"); }
        catch (Exception ignored) { return value; }
    }
}
