package xiao.bu.tv;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Online .cjs channel address. No Android or network dependency. */
final class CjsSource {
    final String url;
    final String descriptorUrl;
    final Map<String, String> parameters;

    private CjsSource(String url, String descriptorUrl, Map<String, String> parameters) {
        this.url = url;
        this.descriptorUrl = descriptorUrl;
        this.parameters = Collections.unmodifiableMap(parameters);
    }

    static boolean isSource(String value) {
        if (value == null) return false;
        String path = value.trim().split("[?#]", 2)[0];
        return path.toLowerCase(java.util.Locale.US).endsWith(".cjs");
    }

    static CjsSource parse(String value) throws IOException {
        if (!isSource(value)) return null;
        try {
            String source = value.trim();
            URI uri = new URI(source);
            if (source.length() > 8192 || (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
                throw new IOException("CJS 频道仅支持在线 HTTP/HTTPS 地址");
            Map<String, String> params = new LinkedHashMap<String, String>();
            String query = uri.getRawQuery();
            if (query != null && query.length() > 0) for (String pair : query.split("&", -1)) {
                String[] parts = pair.split("=", 2);
                String key = URLDecoder.decode(parts[0], "UTF-8");
                String item = parts.length == 2 ? URLDecoder.decode(parts[1], "UTF-8") : "";
                if (!key.matches("[A-Za-z][A-Za-z0-9_]{0,63}") || item.length() > 2048
                        || params.containsKey(key) || params.size() >= 32)
                    throw new IOException("CJS 频道参数无效或重复");
                params.put(key, item);
            }
            String quality = params.get("quality");
            if (quality != null && !"high".equals(quality) && !"medium".equals(quality) && !"low".equals(quality))
                throw new IOException("CJS 清晰度仅支持 high、medium、low");
            int mark = source.indexOf('?');
            return new CjsSource(source, mark < 0 ? source : source.substring(0, mark), params);
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("CJS 频道地址或参数无效", error); }
    }

    String page(String template, Map<String, String> rules) throws IOException {
        for (Map.Entry<String, String> rule : rules.entrySet()) {
            String value = parameters.get(rule.getKey());
            if (value == null || !value.matches(rule.getValue()))
                throw new IOException("CJS 参数缺失或格式错误：" + rule.getKey());
        }
        Matcher match = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)\\}").matcher(template);
        StringBuffer output = new StringBuffer();
        while (match.find()) {
            String key = match.group(1), value = parameters.get(key);
            if (value == null || !rules.containsKey(key)) throw new IOException("CJS 站点参数未声明：" + key);
            match.appendReplacement(output, Matcher.quoteReplacement(URLEncoder.encode(value, "UTF-8")));
        }
        match.appendTail(output);
        return output.toString();
    }
}
