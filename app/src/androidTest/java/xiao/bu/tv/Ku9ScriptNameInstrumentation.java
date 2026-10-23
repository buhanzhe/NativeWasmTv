package xiao.bu.tv;

import android.app.Instrumentation;
import android.os.Bundle;

import java.net.URI;

/** Verifies Unicode Ku9 script names without weakening path traversal protection. */
public final class Ku9ScriptNameInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            check(Ku9ScriptLoader.isSource("https://tvt.hrtj.ccwu.cc/js/ku9.js?id=https://m.gdtv.cn/tvChannelDetail/43"), "Short JS path rejected");
            check(Ku9ScriptLoader.isSource("https://example.com/custom.JS?id=43"), "Root JS path rejected");
            check(Ku9ScriptLoader.isSource("http://A/ku9/js/local.js?id=43"), "Local placeholder rejected");
            check(Ku9ScriptLoader.isSource("https://example.com/k-web/ku9/js/test.js?id=43"), "Legacy path rejected");
            check(!Ku9ScriptLoader.isSource("https://example.com/live?id=https://example.com/ku9/js/test.js"), "Query mistaken for script path");
            check(!Ku9ScriptLoader.isSource("https://example.com/video.m3u8"), "Video mistaken for JS");
            check(!Ku9ScriptLoader.isSource("file:///js/ku9.js"), "Non-HTTP URL accepted");
            check(Ku9ScriptLoader.isSafeScriptName("江苏综艺.js"), "Chinese name rejected");
            check(Ku9ScriptLoader.isSafeScriptName("湖北教育-高清_2.js"),
                    "mixed Unicode name rejected");
            check(Ku9ScriptLoader.isSafeScriptName("Cafe\u0301.js"),
                    "decomposed Unicode name rejected");
            checkUrlName("http://A/ku9/js/江苏综艺.js?id=jszy", "江苏综艺.js");
            checkUrlName("http://A/ku9/js/%E6%B1%9F%E8%8B%8F%E7%BB%BC%E8%89%BA.js?id=jszy",
                    "江苏综艺.js");
            check(!Ku9ScriptLoader.isSafeScriptName("../江苏综艺.js"),
                    "parent traversal accepted");
            check(!Ku9ScriptLoader.isSafeScriptName("目录/江苏综艺.js"),
                    "path separator accepted");
            check(!Ku9ScriptLoader.isSafeScriptName("江苏综艺.txt"),
                    "non-JavaScript extension accepted");
            check(!Ku9ScriptLoader.isSafeScriptName("江苏 综艺.js"),
                    "whitespace accepted");
            result.putString("stream", "PASS Unicode Ku9 script names and traversal guards\n");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL " + error + "\n");
            finish(1, result);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void checkUrlName(String url, String expected) {
        String path = URI.create(url).getPath();
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        check(expected.equals(fileName), "URL name was not decoded: " + fileName);
        check(Ku9ScriptLoader.isSafeScriptName(fileName), "URL name rejected: " + fileName);
    }
}
