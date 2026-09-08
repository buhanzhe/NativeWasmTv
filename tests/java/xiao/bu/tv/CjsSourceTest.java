package xiao.bu.tv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;

/** Standalone JVM regression checks; no Android device or network required. */
public final class CjsSourceTest {
    private static final String BASE = "https://raw.githubusercontent.com/TvWasm/cjs/main/";

    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual);
    }

    private static void reject(String source) throws Exception {
        try { CjsSource.parse(source); }
        catch (IOException expected) { return; }
        throw new AssertionError("Accepted invalid source: " + source);
    }

    public static void main(String[] args) throws Exception {
        CjsSource source = CjsSource.parse(BASE + "gxtv.cjs?id=abc&quality=low&token=a%2Bb%26c&label=%E4%B8%AD%E6%96%87");
        equal(BASE + "gxtv.cjs", source.descriptorUrl);
        equal("low", source.parameters.get("quality"));
        equal("a+b&c", source.parameters.get("token"));
        equal("中文", source.parameters.get("label"));
        equal("https://example.com/abc", source.page("https://example.com/{id}", Collections.singletonMap("id", "[a-z]+")));
        if (CjsSource.parse("https://example.com/live.m3u8?id=1") != null) throw new AssertionError("HLS misclassified");
        reject("file:///tmp/gxtv.cjs?id=1");
        reject("content://plugin/gxtv.cjs?id=1");
        reject("https://user:password@example.com/gxtv.cjs?id=1");
        reject(BASE + "gxtv.cjs?id=1#bad");
        reject(BASE + "gxtv.cjs?id=1&id=2");
        reject(BASE + "gxtv.cjs?id=1&%69d=2");
        reject(BASE + "gxtv.cjs?id=%ZZ");
        reject(BASE + "gxtv.cjs?id=1&quality=4k");
        reject(BASE + "gxtv.cjs?id=1&=empty");
        try {
            CjsSource.parse(BASE + "gxtv.cjs?id=..%2F..%2Ffile").page(
                    "https://example.com/{id}", Collections.singletonMap("id", "[a-z0-9]+"));
            throw new AssertionError("Path injection accepted");
        } catch (IOException expected) { }
        try {
            CjsSource.parse(BASE + "gxtv.cjs").page("https://example.com/{id}", Collections.singletonMap("id", "[a-z]+"));
            throw new AssertionError("Missing id accepted");
        } catch (IOException expected) { }
        int total = 0;
        for (String alias : new String[] {"cctv", "cmg", "gxtv"}) {
            int count = 0;
            for (String line : Files.readAllLines(Paths.get(args[0], alias + ".m3u"), StandardCharsets.UTF_8)) {
                if (line.length() == 0 || line.startsWith("#")) continue;
                CjsSource entry = CjsSource.parse(line);
                if (entry == null) throw new AssertionError("Not direct CJS: " + line);
                equal(BASE + alias + ".cjs", entry.descriptorUrl);
                String pattern = "gxtv".equals(alias) ? "[a-fA-F0-9]{32}" : "cmg".equals(alias) ? "[0-9]{1,20}" : "[a-zA-Z0-9]{1,64}";
                String template = "gxtv".equals(alias) ? "https://tv.gxtv.cn/channel/channelivePlay_{id}.html"
                        : "cmg".equals(alias) ? "https://yangshipin.cn/tv/home?pid={id}" : "https://tv.cctv.com/live/{id}/";
                Map<String, String> rules = Collections.singletonMap("id", pattern);
                equal(template.replace("{id}", entry.parameters.get("id")), entry.page(template, rules));
                count++;
            }
            equal("cctv".equals(alias) ? 20 : "cmg".equals(alias) ? 63 : 8, count);
            total += count;
        }
        System.out.println("PASS: " + total + " direct CJS channels; decoding, quality, invalid inputs and template validation");
    }
}
