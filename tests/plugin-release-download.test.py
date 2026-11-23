"""Check release transfer retries against deterministic HTTP response bodies."""
import os
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get("ANDROID_HOME", "D:/android/sdk")) / "platforms/android-34/android.jar"
JAVA = Path(os.environ["JAVA_HOME"]) / "bin" if "JAVA_HOME" in os.environ else None
CACHE = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
OKIO = next(CACHE.glob("com.squareup.okio/okio/1.15.0/*/okio-1.15.0.jar"))

TRANSPORT = r"""
package xiao.bu.tv;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.*;
import okio.ByteString;

final class GithubProxy {
    static final String SHORT = "https://short.test/plugin.jar";
    static final String WRONG = "https://wrong.test/plugin.jar";
    static final List<String> accepted = new ArrayList<>(), rejected = new ArrayList<>();

    static String[] candidates(String url) { return new String[]{SHORT, WRONG, SHORT, url}; }
    static void succeeded(String url) { accepted.add(url); }
    static void failed(String url) { rejected.add(url); }
}

final class AppLog {
    static void i(String tag, String message) { }
    static void w(String tag, String message) { }
    static void w(String tag, String message, Throwable error) { }
}

final class NetworkClient {
    static final Map<String, byte[]> responses = new HashMap<>();
    static final List<String> tried = new ArrayList<>();
    static final List<String> closed = new ArrayList<>();
    static boolean failNext;

    static HttpURLConnection openBounded(URL url, int timeout, boolean http1) {
        if (!http1 || timeout <= 0 || timeout > 5000) throw new AssertionError("Unbounded plugin transport");
        String address = url.toString();
        tried.add(address);
        byte[] data = failNext ? null : responses.get(address);
        failNext = false;
        return new HttpURLConnection(url) {
            public void connect() { }
            public boolean usingProxy() { return false; }
            public void disconnect() { closed.add(address); }
            public int getResponseCode() { return data == null ? 503 : 200; }
            public int getContentLength() { return -1; }
            public InputStream getInputStream() { return new ByteArrayInputStream(data); }
        };
    }
}

public final class ReleaseTransferCheck {
    public static void main(String[] args) throws Exception {
        File staging = new File(args[0]);
        String official = "https://github.com/buhanzhe/ntv-plugin/releases/download/v1.0.0/tvbox.jar";
        byte[] correct = {1, 2, 3, 4};
        String sha = ByteString.of(correct).sha256().hex();
        NetworkClient.responses.put(GithubProxy.SHORT, new byte[]{1, 2, 3});
        NetworkClient.responses.put(GithubProxy.WRONG, new byte[]{1, 2, 3, 5});
        NetworkClient.responses.put(official, correct);
        RadioUiCatalog.downloadVerifiedRelease(official, staging, correct.length, sha, 5000);
        List<String> expected = Arrays.asList(GithubProxy.SHORT, GithubProxy.WRONG, official);
        if (!NetworkClient.tried.equals(expected) || !NetworkClient.closed.equals(expected)
                || !GithubProxy.rejected.equals(expected.subList(0, 2))
                || !GithubProxy.accepted.equals(Collections.singletonList(official))
                || !Arrays.equals(correct, Files.readAllBytes(staging.toPath()))) {
            throw new AssertionError("Failed mirrors must leave the verified package and free their connections");
        }

        NetworkClient.tried.clear();
        NetworkClient.failNext = true;
        byte[] catalog = RadioUiCatalog.downloadDirect(official, correct.length, 5000);
        if (!Arrays.equals(correct, catalog) || !NetworkClient.tried.equals(Arrays.asList(official, official))) {
            throw new AssertionError("Release catalog must retry its trusted origin");
        }
        System.out.println("PASS release transfers: short and corrupt mirror retries, digest validation and trusted catalog origin");
    }
}
"""


def tool(name):
    return str(JAVA / (name + (".exe" if os.name == "nt" else ""))) if JAVA else name


def main():
    with tempfile.TemporaryDirectory() as directory:
        workspace = Path(directory)
        java = workspace / "ReleaseTransferCheck.java"
        java.write_text(TRANSPORT, encoding="utf-8")
        clock = workspace / "SystemClock.java"
        clock.write_text("package android.os; public final class SystemClock {"
                         "public static long elapsedRealtime() { return System.nanoTime() / 1000000L; }}",
                         encoding="utf-8")
        classpath = os.pathsep.join(map(str, (workspace, SDK, OKIO)))
        catalog = ROOT / "app/src/main/java/xiao/bu/tv/RadioUiCatalog.java"
        subprocess.run([tool("javac"), "-encoding", "UTF-8", "-cp", classpath, "-d", str(workspace),
                        str(clock), str(java), str(catalog)], check=True)
        subprocess.run([tool("java"), "-cp", classpath, "xiao.bu.tv.ReleaseTransferCheck",
                        str(workspace / "staging.jar")], check=True)


if __name__ == "__main__":
    main()
