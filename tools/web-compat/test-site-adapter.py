#!/usr/bin/env python3
"""Offline transport/routing contract tests for the production TV site adapter.

Run with Python 3 and a JDK on PATH or in JAVA_HOME. The host contract checks need
no Android SDK, downloads, network, or third-party Python packages. Add
--android-jar /path/to/platforms/android-19/android.jar for a separate real API19
compile check (also discovered through ANDROID_HOME or ANDROID_SDK_ROOT).
Generated sources/classes live under the self-ignored .codex-tmp directory and
are removed after each run.

The host checks compile production LegacyTvSiteAdapter and LegacyWebCompatibility
against small Android/NetworkClient/JSONObject test doubles. The actual API gate
is tested using mutable Build.VERSION.SDK_INT values. JSONObject only checks
object delimiters and retains fixture text; it is NOT a JSON parser. These tests
establish call contracts and cleanup, not Android JSON/runtime behavior, real
NetworkClient timeout enforcement, TLS, WebView interception, upstream service
availability, or media playback. The optional separate API19 compile uses the
real Android platform types with only a NetworkClient declaration stub.
"""

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]


def executable(name):
    home = os.environ.get("JAVA_HOME")
    if home:
        for suffix in (".exe", "") if os.name == "nt" else ("", ".exe"):
            candidate = Path(home) / "bin" / (name + suffix)
            if candidate.is_file():
                return str(candidate)
    return shutil.which(name)


def compiler(java):
    javac = executable("javac")
    if javac:
        return [javac]
    # Some stripped JDK distributions retain the compiler module but no launcher.
    # JDK 8 keeps the same entry point in tools.jar instead of a module.
    java_home = Path(java).resolve().parent.parent
    for jar in (java_home / "lib/tools.jar", java_home.parent / "lib/tools.jar"):
        if jar.is_file():
            return [java, "-cp", str(jar), "com.sun.tools.javac.Main"]
    return [java, "com.sun.tools.javac.Main"]


SOURCES = {
    "android/webkit/WebResourceResponse.java": r'''
package android.webkit;
import java.io.InputStream;
public class WebResourceResponse {
    private final String mime, encoding;
    private final InputStream data;
    public WebResourceResponse(String mime, String encoding, InputStream data) {
        this.mime = mime; this.encoding = encoding; this.data = data;
    }
    public String getMimeType() { return mime; }
    public String getEncoding() { return encoding; }
    public InputStream getData() { return data; }
}
''',
    "android/util/Log.java": r'''
package android.util;
public class Log {
    public static int warnings;
    public static int w(String tag, String message) { warnings++; return 0; }
    public static int i(String tag, String message) { return 0; }
}
''',
    "android/util/Base64.java": r'''
package android.util;
public class Base64 {
    public static final int NO_WRAP = 2, DEFAULT = 0;
    // Compile-only declarations. Hebei must never reach the IQilu crypto path.
    public static byte[] encode(byte[] input, int flags) {
        throw new AssertionError("Unexpected Base64 path");
    }
    public static byte[] decode(String input, int flags) {
        throw new AssertionError("Unexpected Base64 path");
    }
}
''',
    "org/json/JSONObject.java": r'''
package org.json;
public class JSONObject {
    private String raw;
    public JSONObject() { raw = "{}"; }
    public JSONObject(String input) {
        raw = input.trim();
        if (!raw.startsWith("{") || !raw.endsWith("}"))
            throw new IllegalArgumentException("Fixture is not an object");
    }
    public JSONObject put(String key, Object value) {
        if (!(value instanceof Number))
            throw new AssertionError("Only the Hebei server-date insertion is expected");
        raw = raw.substring(0, raw.length() - 1)
            + (raw.length() > 2 ? "," : "") + "\"" + key + "\":" + value + "}";
        return this;
    }
    public String toString() { return raw; }
    public boolean optBoolean(String key) { throw unexpected(); }
    public JSONObject optJSONObject(String key) { throw unexpected(); }
    public JSONObject getJSONObject(String key) { throw unexpected(); }
    public int optInt(String key) { throw unexpected(); }
    public int optInt(String key, int fallback) { throw unexpected(); }
    public String optString(String key) { throw unexpected(); }
    public String optString(String key, String fallback) { throw unexpected(); }
    private AssertionError unexpected() {
        return new AssertionError("Unexpected non-Hebei JSON access");
    }
}
''',
    "android/os/Build.java": r'''
package android.os;
public class Build {
    public static class VERSION { public static int SDK_INT = 19; }
}
''',
    "android/content/Context.java": r'''
package android.content;
public class Context {
    public android.content.res.AssetManager getAssets() {
        throw new AssertionError("Unexpected asset loading");
    }
}
''',
    "android/content/res/AssetManager.java": r'''
package android.content.res;
public class AssetManager {
    public java.io.InputStream open(String name) throws java.io.IOException {
        throw new AssertionError("Unexpected asset loading");
    }
}
''',
    "xiao/bu/tv/NetworkClient.java": r'''
package xiao.bu.tv;
import java.io.IOException;
import java.net.*;
final class NetworkClient {
    static int calls, budget;
    static URL target;
    static SiteAdapterTest.Connection next;
    static boolean failOpen;
    static HttpURLConnection openBounded(URL url, int timeout) throws IOException {
        calls++; target = url; budget = timeout;
        if (calls != 1) throw new AssertionError("Unexpected network retry");
        if (failOpen) throw new IOException("Injected open failure");
        if (next == null) throw new AssertionError("Unexpected network request: " + url);
        // No URL.openConnection(), sockets, or live network access exist in this double.
        return next;
    }
}
''',
    "xiao/bu/tv/SiteAdapterTest.java": r'''
package xiao.bu.tv;
import android.webkit.WebResourceResponse;
import java.io.*;
import java.net.*;
import java.util.*;

public class SiteAdapterTest {
    static final String PAGE = "https://www.hebtv.com/19/19js/st/xdszb/index.shtml";
    static final String RESOURCE = "https://www.hebtv.com/__ntv_kitkat__/hebei-play-info";
    static final String ENDPOINT = "https://api.cmc.hebrts.cn/cmsback/api/com/article/getArticleList?catalogId=32557&siteId=1";
    static final String AGENT = "Contract-Test/1.0";
    static final int LIMIT = 256 * 1024;
    static int assertions, cases;

    static void check(boolean condition, String reason) {
        assertions++;
        if (!condition) throw new AssertionError(reason);
    }
    static byte[] utf8(String text) throws Exception { return text.getBytes("UTF-8"); }
    static String body(WebResourceResponse response) throws Exception {
        check(response != null, "Expected synthetic response");
        check("UTF-8".equals(response.getEncoding()), "Response is UTF-8");
        try (InputStream input = response.getData()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] block = new byte[4096]; int count;
            while ((count = input.read(block)) != -1) bytes.write(block, 0, count);
            return bytes.toString("UTF-8");
        }
    }
    static class Input extends InputStream {
        final ByteArrayInputStream source;
        int readBytes, closes;
        boolean failRead, failClose;
        Input(byte[] bytes) { source = new ByteArrayInputStream(bytes); }
        public int read() throws IOException {
            byte[] one = new byte[1]; int count = read(one, 0, 1);
            return count < 0 ? -1 : one[0] & 255;
        }
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (failRead) throw new IOException("Injected body read failure");
            int count = source.read(bytes, offset, length);
            if (count > 0) readBytes += count;
            return count;
        }
        public void close() throws IOException {
            closes++;
            if (failClose) throw new IOException("Injected body close failure");
        }
    }
    static class Output extends OutputStream {
        int bytes, closes;
        boolean failClose;
        public void write(int value) { bytes++; }
        public void close() throws IOException {
            closes++;
            if (failClose) throw new IOException("Injected output close failure");
        }
    }
    static class Connection extends HttpURLConnection {
        final Input input;
        final Output output = new Output();
        int status = 200, disconnects, outputCalls, inputCalls, fixedLength = -1;
        long date;
        boolean failOutput, failInput, failStatus, failMethod, failDate;
        Connection(String payload) throws Exception {
            super(new URL(ENDPOINT)); input = new Input(utf8(payload));
        }
        public void connect() { }
        public boolean usingProxy() { return false; }
        public void disconnect() { disconnects++; }
        public void setRequestMethod(String method) throws ProtocolException {
            if (failMethod) throw new ProtocolException("Injected method failure");
            super.setRequestMethod(method);
        }
        public void setFixedLengthStreamingMode(int length) {
            fixedLength = length; super.setFixedLengthStreamingMode(length);
        }
        public OutputStream getOutputStream() throws IOException {
            outputCalls++;
            if (failOutput) throw new IOException("Injected output acquisition failure");
            return output;
        }
        public int getResponseCode() throws IOException {
            if (failStatus) throw new IOException("Injected status failure");
            return status;
        }
        public InputStream getInputStream() throws IOException {
            inputCalls++;
            if (failInput) throw new IOException("Injected input acquisition failure");
            return input;
        }
        public long getDate() {
            if (failDate) throw new IllegalStateException("Injected date failure");
            return date;
        }
    }
    static void reset(Connection connection) {
        cases++;
        android.os.Build.VERSION.SDK_INT = 19;
        NetworkClient.calls = 0; NetworkClient.budget = 0; NetworkClient.target = null;
        NetworkClient.next = connection; NetworkClient.failOpen = false;
        android.util.Log.warnings = 0;
    }
    static String request(String document, String resource) throws Exception {
        WebResourceResponse response = LegacyTvSiteAdapter.resource(document, resource, AGENT);
        check(response != null && "application/json".equals(response.getMimeType()), "JSON response");
        return body(response);
    }
    static void transport(Connection c, String document) {
        check(NetworkClient.calls == 1, "One request only");
        check(ENDPOINT.equals(NetworkClient.target.toString()), "Fixed public endpoint");
        check(NetworkClient.budget == 10000, "Pass exactly 10 seconds to bounded client");
        check("POST".equals(c.getRequestMethod()), "Catalog uses POST");
        check(!c.getInstanceFollowRedirects(), "Never follow redirects");
        check(c.getDoOutput(), "POST output enabled");
        check(c.fixedLength == 0, "Declare zero-length body");
        check(c.outputCalls == 1 && c.output.bytes == 0 && c.output.closes == 1,
            "Open and close an empty POST body");
        check(AGENT.equals(c.getRequestProperty("User-Agent")), "Forward user agent");
        check(document.equals(c.getRequestProperty("Referer")), "Page referer");
        check("https://www.hebtv.com".equals(c.getRequestProperty("Origin")), "Official origin");
        check(c.disconnects == 1, "Disconnect exactly once");
    }
    static void rejected(String document, String resource) {
        reset(null);
        check(LegacyTvSiteAdapter.resource(document, resource, AGENT) == null,
            "Reject route: " + document + " -> " + resource);
        check(NetworkClient.calls == 0, "Rejected route must never touch transport");
    }
    static void routing() throws Exception {
        reset(null);
        check(LegacyTvSiteAdapter.supports(PAGE), "Allow official Hebei page");
        check(LegacyTvSiteAdapter.supports(PAGE.replace("https:", "http:")), "Allow HTTP page");
        check(LegacyTvSiteAdapter.supports(PAGE + "?tracking=1#live"), "Page query/fragment do not change route");
        String[] badPages = {null, "bad url", "file:///19/19js/st/xdszb/index.shtml",
            PAGE + "/", PAGE + ".evil", PAGE.replace("index.shtml", "other.shtml"),
            PAGE.replace("www.hebtv.com", "hebtv.com"),
            PAGE.replace("www.hebtv.com", "www.hebtv.com.evil.example")};
        for (String page : badPages) {
            check(!LegacyTvSiteAdapter.supports(page), "Unsupported document: " + page);
            rejected(page, RESOURCE);
        }
        String[] badResources = {null, "/__ntv_kitkat__/hebei-play-info",
            RESOURCE.replace("https:", "http:"), RESOURCE.replace("www.hebtv.com", "hebtv.com"),
            RESOURCE.replace("www.hebtv.com", "www.hebtv.com.evil.example"),
            RESOURCE.replace("www.hebtv.com", "www.hebtv.com:443"),
            RESOURCE.replace("www.hebtv.com", "user@www.hebtv.com"),
            RESOURCE + "/", RESOURCE + "?", RESOURCE + "?url=https://evil.example/",
            RESOURCE.replace("hebei-play-info", "cztv-play-info"), ENDPOINT};
        for (String resource : badResources) rejected(PAGE, resource);

        reset(null);
        check(request(PAGE, "bad url").contains("\"success\":false"), "Malformed resource fails closed");
        check(NetworkClient.calls == 0, "Malformed resource makes no network request");

        reset(null);
        String prelude = "<script>window.contractPrelude=true;</script>";
        WebResourceResponse shell = LegacyTvSiteAdapter.page(PAGE, prelude);
        check("text/html".equals(shell.getMimeType()), "Allowed page gets HTML shell");
        String html = body(shell);
        check(html.contains("window.__ntvLegacySiteShell=true"), "Shell marker");
        check(html.contains(prelude), "Prelude is present");
        check(LegacyTvSiteAdapter.page(PAGE, "") == null, "No shell without prelude");
        check(NetworkClient.calls == 0, "Shell does not fetch catalog itself");

        for (int api : new int[] {16, 18, 19}) {
            android.os.Build.VERSION.SDK_INT = api;
            check(LegacyTvSiteAdapter.supports(PAGE), "Production gate enables API " + api);
        }
        for (int api : new int[] {20, 21, 24, 35}) {
            android.os.Build.VERSION.SDK_INT = api;
            check(!LegacyTvSiteAdapter.supports(PAGE), "Production gate blocks support on API " + api);
            check(LegacyTvSiteAdapter.page(PAGE, prelude) == null, "Gate blocks shell on API " + api);
            check(LegacyTvSiteAdapter.resource(PAGE, RESOURCE, AGENT) == null,
                "Gate blocks API helper on API " + api);
            check(NetworkClient.calls == 0, "Disabled gate makes no network request");
        }
        android.os.Build.VERSION.SDK_INT = 19;
    }
    static void success() throws Exception {
        String fixture = "{\"code\":200,\"data\":[{\"title\":\"河北卫视\"}]}";
        Connection c = new Connection(fixture);
        reset(c);
        check(fixture.equals(request(PAGE, RESOURCE)), "Pass through UTF-8 catalog response");
        transport(c, PAGE);
        check(c.input.closes == 1, "Successful body is closed");
        check(android.util.Log.warnings == 0, "No warning on success");

        c = new Connection(fixture); c.date = 1791792000000L;
        reset(c);
        String document = PAGE + "?url=https://evil.example/catalog&catalogId=7";
        check(request(document, RESOURCE).contains("\"_ntvServerTimeMs\":1791792000000"),
            "Propagate available server date");
        transport(c, document);

        c = new Connection("{\"code\":403,\"message\":\"denied\"}");
        reset(c);
        check(request(PAGE, RESOURCE).equals("{\"code\":403,\"message\":\"denied\"}"),
            "Do not convert API denial into success or retry");
        transport(c, PAGE);

        c = new Connection("{}"); reset(c);
        String httpPage = PAGE.replace("https:", "http:");
        check("{}".equals(request(httpPage, RESOURCE.replace("https:", "http:"))),
            "Exact HTTP page/resource origin allowed");
        transport(c, httpPage);
    }
    static void httpFailures() throws Exception {
        for (int status : new int[] {301, 302, 307, 308, 403, 404, 429, 500, 503}) {
            Connection c = new Connection("{}"); c.status = status; reset(c);
            check(request(PAGE, RESOURCE).contains("\"success\":false"), "HTTP " + status + " fails closed");
            transport(c, PAGE);
            check(c.inputCalls == 0, "HTTP " + status + " body not consumed");
            check(android.util.Log.warnings == 1, "HTTP failure reported once");
        }
    }
    static String sizedObject(int bytes) {
        char[] content = new char[bytes - 8]; Arrays.fill(content, 'x');
        return "{\"x\":\"" + new String(content) + "\"}";
    }
    static void responseLimit() throws Exception {
        String exact = sizedObject(LIMIT);
        check(utf8(exact).length == LIMIT, "Boundary fixture is exactly 256 KiB");
        Connection c = new Connection(exact); reset(c);
        check(exact.equals(request(PAGE, RESOURCE)), "Allow exactly 256 KiB");
        transport(c, PAGE);
        check(c.input.closes == 1, "Boundary response closed");
        for (int size : new int[] {LIMIT + 1, LIMIT * 4}) {
            c = new Connection(sizedObject(size)); reset(c);
            check(request(PAGE, RESOURCE).contains("\"success\":false"), "Reject body over 256 KiB");
            transport(c, PAGE);
            check(c.input.closes == 1, "Oversized response closed");
            check(c.input.readBytes <= LIMIT + 4096, "Stop reading at cap plus one read block");
            check(android.util.Log.warnings == 1, "Oversize failure reported once");
        }
        // The ceiling is bytes, not Java characters: fewer than 256 Ki characters
        // can still exceed the transport cap when the upstream response is UTF-8.
        char[] multibyte = new char[LIMIT / 2]; Arrays.fill(multibyte, '\u4e2d');
        c = new Connection("{\"x\":\"" + new String(multibyte) + "\"}"); reset(c);
        check(request(PAGE, RESOURCE).contains("\"success\":false"), "Apply byte cap to UTF-8");
        check(c.input.closes == 1 && c.disconnects == 1, "Close oversized UTF-8 response");
    }
    static void cleanupFailures() throws Exception {
        for (String failure : new String[] {"method", "output", "outputClose", "status",
                "input", "read", "inputClose", "json", "date"}) {
            Connection c = new Connection("json".equals(failure) ? "not-an-object" : "{}");
            c.failMethod = "method".equals(failure);
            c.failOutput = "output".equals(failure);
            c.output.failClose = "outputClose".equals(failure);
            c.failStatus = "status".equals(failure);
            c.failInput = "input".equals(failure);
            c.input.failRead = "read".equals(failure);
            c.input.failClose = "inputClose".equals(failure);
            c.failDate = "date".equals(failure);
            reset(c);
            check(request(PAGE, RESOURCE).contains("\"success\":false"), failure + " fails closed");
            check(NetworkClient.calls == 1, failure + " must not retry");
            check(c.disconnects == 1, failure + " disconnects exactly once");
            check(android.util.Log.warnings == 1, failure + " reported once");
            if (Arrays.asList("read", "inputClose", "json", "date").contains(failure))
                check(c.input.closes == 1, failure + " closes obtained input stream");
        }
        reset(null); NetworkClient.failOpen = true;
        check(request(PAGE, RESOURCE).contains("\"success\":false"), "Open failure fails closed");
        check(NetworkClient.calls == 1, "Open failure must not retry");
    }
    static void guizhou() throws Exception {
        String origin="https://www.gzstv.com", resource=origin+"/__ntv_kitkat__/guizhou-play-info";
        for(String channel:new String[]{"ch01","ch02","ch03","ch04","ch05","ch06","ch13"}){
            String page=origin+"/tv/"+channel;
            check(LegacyTvSiteAdapter.supports(page),"Guizhou known channel");
            Connection c=new Connection("{\"stream_url\":\"https://media.example.org/a.m3u8?txTime=unchanged\"}");c.date=1700000000000L;reset(c);
            String value=request(page,resource);
            check(value.contains("unchanged"),"Preserve returned URL");check(value.contains("1700000000000"),"Propagate HTTP Date");
            check(NetworkClient.calls==1,"One metadata call");check(NetworkClient.budget==10000,"Bounded 10s");
            check(NetworkClient.target.toString().equals("https://api.gzstv.com/v1/tv/"+channel+"/?remote_address=&fields=description,title,stream_url,image,author"),"Fixed public Guizhou API");
            check("GET".equals(c.getRequestMethod()),"GET metadata");check(!c.getDoOutput()&&c.outputCalls==0,"No request body");
            check(!c.getInstanceFollowRedirects(),"No redirects");check(!c.getUseCaches(),"Disable local response cache");
            check("no-cache".equals(c.getRequestProperty("Cache-Control")),"Revalidate stale metadata");
            check(origin.equals(c.getRequestProperty("Origin")),"Official origin");check(page.equals(c.getRequestProperty("Referer")),"Referer");
            check(c.disconnects==1&&c.input.closes==1,"Cleanup response");
        }
        String page=origin+"/tv/ch01";
        for(String bad:new String[]{origin+"/tv/ch07",origin+"/tv/ch01evil","https://www.gzstv.com.evil/tv/ch01"})rejected(bad,resource);
        for(String bad:new String[]{resource+"?url=https://evil.example",resource+"?",resource+"/",resource.replace("https:","http:"),resource.replace("www.gzstv.com","www.gzstv.com.evil")})rejected(page,bad);
        for(int status:new int[]{302,401,403,500}){
            Connection c=new Connection("{}");c.status=status;reset(c);check(request(page,resource).contains("\"success\":false"),"Deny failed metadata");
            check(NetworkClient.calls==1&&c.disconnects==1,"Never retry/follow denial");
        }
        Connection large=new Connection(sizedObject(262145));reset(large);check(request(page,resource).contains("\"success\":false"),"Guizhou response size cap");check(large.disconnects==1&&large.input.closes==1,"Guizhou large response cleanup");
    }
    public static void main(String[] args) throws Exception {
        routing(); success(); httpFailures(); responseLimit(); cleanupFailures(); guizhou();
        System.out.println("PASS " + cases + " offline adapter cases / " + assertions + " assertions: "
            + "Hebei and Guizhou routing, compatibility gate, fixed public APIs, 10s client budget, "
            + "no redirects/retries, 256KiB byte cap, stream closure and disconnects");
        System.out.println("LIMITS: declaration/test-double coverage only; no Android JSON/runtime, "
            + "real timeout enforcement, upstream network, WebView, or playback proof.");
    }
}
''',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-jar", type=Path,
                        help="Real API19 android.jar for an additional Android API compile check")
    args = parser.parse_args()
    android_jar = args.android_jar
    if android_jar is None:
        for variable in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
            sdk = os.environ.get(variable)
            if sdk:
                candidate = Path(sdk) / "platforms/android-19/android.jar"
                if candidate.is_file():
                    android_jar = candidate
                    break
    if android_jar is not None and not android_jar.is_file():
        parser.error("Android platform jar does not exist: " + str(android_jar))
    java = executable("java")
    if not java:
        raise SystemExit("A local JDK is required: set JAVA_HOME or put java on PATH.")
    scratch = ROOT / ".codex-tmp"
    scratch.mkdir(exist_ok=True)
    # Avoid changing repository-wide ignores or accidentally publishing generated
    # stubs/classes, including in a partial checkout without its root .gitignore.
    ignore = scratch / ".gitignore"
    if not ignore.exists():
        ignore.write_text("*\n", encoding="utf-8")
    out_parent = scratch / "web-compat"
    out_parent.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="site-adapter-", dir=out_parent) as temp:
        out = Path(temp)
        sources = []
        for relative, content in SOURCES.items():
            path = out / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(str(path))
        production = [str(ROOT / "app/src/main/java/xiao/bu/tv" / name)
                      for name in ("LegacyTvSiteAdapter.java", "LegacyWebCompatibility.java")]
        sources.extend(production)
        compile_command = compiler(java)
        print("Compiler: " + " ".join(compile_command), flush=True)
        subprocess.run(compile_command + ["-encoding", "UTF-8", "-source", "8", "-target", "8",
                       "-Xlint:-options", "-d", str(out)] + sources,
                       check=True, timeout=60)
        subprocess.run([java, "-cp", str(out), "xiao.bu.tv.SiteAdapterTest"],
                       check=True, timeout=30)
        if android_jar is not None:
            api_out = out / "api19"
            api_out.mkdir()
            declaration = api_out / "NetworkClient.java"
            declaration.write_text("""package xiao.bu.tv;
final class NetworkClient {
    static java.net.HttpURLConnection openBounded(java.net.URL url, int timeout)
            throws java.io.IOException { throw new AssertionError("Compile only"); }
}
""", encoding="utf-8")
            # Keep host test-double classes off the platform compilation classpath.
            subprocess.run(compile_command + ["-encoding", "UTF-8", "-source", "8", "-target", "8",
                           "-Xlint:-options", "-bootclasspath", str(android_jar.resolve()),
                           "-classpath", str(api_out), "-sourcepath", str(api_out),
                           "-d", str(api_out), str(declaration)] + production,
                           check=True, timeout=60)
            print("PASS real API19 compile: production adapter + compatibility gate, "
                  "only NetworkClient declaration stub; runtime/playback remain unverified.", flush=True)
        else:
            print("SKIP real API19 compile: use --android-jar or an installed Android SDK "
                  "with ANDROID_HOME/ANDROID_SDK_ROOT.", flush=True)


if __name__ == "__main__":
    main()
