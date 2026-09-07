package xiao.bu.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Locale;
import java.util.Map;

/**
 * Generic loader for the separately published C/JS compatibility plugin.
 *
 * Initialization deliberately performs no I/O. The active manifest, JavaScript bundle and
 * native libraries are touched only when a provider needs them or the user requests an update.
 */
public final class CjsPluginRuntime {
    public static final int HOST_PROTOCOL = 1;
    public static final String DEFAULT_MANIFEST_URL =
            "https://raw.githubusercontent.com/TvWasm/cjs/main/plugin.json";

    private static final String TAG = "CjsPlugin";
    private static final String PREFS = "cjs_plugin";
    private static final String PREF_URL = "manifest_url";
    private static final String PREF_VERSION = "active_version";
    private static final String PREF_PENDING_VERSION = "pending_version";
    private static final String PLUGIN_DIR = "cjs-plugin";
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;
    private static final int MAX_SCRIPT_BYTES = 512 * 1024;
    private static final int MAX_NATIVE_BYTES = 8 * 1024 * 1024;
    private static final String PUBLIC_KEY_DER_BASE64 =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAoobFrR8hHh907HkYVIFV"
            + "TnkCmWuHGSm3Y7staBkIxTcXwV+25GPyfjeFtJMjl7Lxl4TeQIaopn0nzUJzj0t"
            + "xyojNHfkKK/6a4TzxXu6Av9tNGxjemUXC759w1ew9CPlcQuAjrHxHFIRDzhduq5"
            + "GV2EJ8daE34W4uMC+ABoki+VVMF/sDb1PFexJb4crcBRoSYNHHQuC9QjvJTrgn"
            + "UuCUpJrigF6+z8VrykGyqpIywubnWJl2fJlTuacraJJwyIejEdWVKNuaDoxBbtU"
            + "hY5OVlT2TpR8VMi3bn/aMATETriEiE4mZgWP4pwEF1/YrNoWvRhX5fC71atijrg"
            + "BgdTEiVQIDAQAB";

    private static Context context;
    private static JSONObject scripts;
    private static String scriptsVersion;
    private static boolean nativeLibrariesLoaded;

    private CjsPluginRuntime() {
    }

    public static void initialize(Context value) {
        if (context == null && value != null) {
            context = value.getApplicationContext();
        }
    }

    private static Context requireContext() {
        if (context == null) {
            throw new IllegalStateException("CJS plugin runtime is not initialized");
        }
        return context;
    }

    private static SharedPreferences preferences() {
        return requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getManifestUrl() {
        return preferences().getString(PREF_URL, DEFAULT_MANIFEST_URL);
    }

    public static void setManifestUrl(String url) {
        String value = url == null ? "" : url.trim();
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            throw new IllegalArgumentException("插件地址仅支持 HTTP 或 HTTPS");
        }
        preferences().edit().putString(PREF_URL, value).apply();
    }

    public static boolean isInstalled() {
        SharedPreferences prefs = preferences();
        String pending = prefs.getString(PREF_PENDING_VERSION, "");
        if (!nativeLibrariesLoaded && pending.length() > 0
                && completeDirectory(versionDirectory(pending))) {
            prefs.edit().putString(PREF_VERSION, pending).remove(PREF_PENDING_VERSION).commit();
        }
        String version = prefs.getString(PREF_VERSION, "");
        if (version.length() == 0) {
            return false;
        }
        return completeDirectory(versionDirectory(version));
    }

    private static boolean completeDirectory(File directory) {
        return new File(directory, "runtime.json").isFile()
                && new File(directory, "libcctv_h5e.so").isFile()
                && new File(directory, "libcmg_decrypt.so").isFile()
                && new File(directory, "libysp_keygen.so").isFile();
    }

    public static JSONObject statusJson() throws JSONException {
        boolean installed = isInstalled();
        String version = preferences().getString(PREF_VERSION, "");
        String pending = preferences().getString(PREF_PENDING_VERSION, "");
        return new JSONObject()
                .put("installed", installed)
                .put("version", version)
                .put("pendingVersion", pending)
                .put("abi", currentAbi())
                .put("manifestUrl", getManifestUrl());
    }

    /** Downloads and activates a complete plugin transactionally. Call from a worker thread. */
    public static synchronized String installOrUpdate() throws Exception {
        JSONObject envelope = new JSONObject(new String(
                download(getManifestUrl(), MAX_MANIFEST_BYTES), "UTF-8"));
        if (envelope.optInt("protocol", 0) != HOST_PROTOCOL) {
            throw new IOException("插件清单协议不受支持");
        }
        String payloadBase64 = envelope.optString("payload", "");
        String signatureBase64 = envelope.optString("signature", "");
        byte[] payload = Base64.decode(payloadBase64, Base64.DEFAULT);
        verifySignature(payload, Base64.decode(signatureBase64, Base64.DEFAULT));
        JSONObject manifest = new JSONObject(new String(payload, "UTF-8"));
        int min = manifest.optInt("minHostProtocol", HOST_PROTOCOL);
        int max = manifest.optInt("maxHostProtocol", HOST_PROTOCOL);
        if (HOST_PROTOCOL < min || HOST_PROTOCOL > max) {
            throw new IOException("插件与当前应用协议不兼容");
        }
        String version = safeName(manifest.optString("version", ""));
        if (version.length() == 0) {
            throw new IOException("插件版本为空");
        }
        String abi = currentAbi();
        String currentVersion = preferences().getString(PREF_VERSION, "");
        if (version.equals(currentVersion) && isInstalled()) {
            return version;
        }
        JSONArray files = manifest.optJSONArray("files");
        if (files == null) {
            throw new IOException("插件清单缺少文件列表");
        }
        File root = new File(requireContext().getFilesDir(), PLUGIN_DIR);
        File staging = new File(root, ".staging-" + System.currentTimeMillis());
        deleteRecursively(staging);
        if (!staging.mkdirs()) {
            throw new IOException("无法创建插件临时目录");
        }
        boolean hasScripts = false;
        int nativeCount = 0;
        try {
            for (int index = 0; index < files.length(); index++) {
                JSONObject item = files.getJSONObject(index);
                String itemAbi = item.optString("abi", "all");
                if (!"all".equals(itemAbi) && !abi.equals(itemAbi)) {
                    continue;
                }
                String name = safeName(item.getString("name"));
                if (name.length() == 0 || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
                    throw new IOException("插件文件名无效");
                }
                boolean nativeFile = name.endsWith(".so");
                int limit = nativeFile ? MAX_NATIVE_BYTES : MAX_SCRIPT_BYTES;
                byte[] body = download(item.getString("url"), limit);
                verifySha256(body, item.getString("sha256"), name);
                writeAndSync(new File(staging, name), body);
                if ("runtime.json".equals(name)) hasScripts = true;
                if (nativeFile) nativeCount++;
            }
            if (!hasScripts || nativeCount != 3) {
                throw new IOException("插件内容不完整");
            }
            writeAndSync(new File(staging, "manifest.payload"), payload);
            File target = versionDirectory(version);
            deleteRecursively(target);
            if (!staging.renameTo(target)) {
                throw new IOException("无法启用新插件");
            }
            if (nativeLibrariesLoaded) {
                preferences().edit().putString(PREF_PENDING_VERSION, version).commit();
            } else {
                preferences().edit().putString(PREF_VERSION, version)
                        .remove(PREF_PENDING_VERSION).commit();
                scripts = null;
                scriptsVersion = null;
                pruneOldVersions(root, version);
            }
            return version;
        } finally {
            deleteRecursively(staging);
        }
    }

    public static synchronized void loadNativeLibrary(String fileName) {
        if (!isInstalled()) {
            throw new UnsatisfiedLinkError("CJS plugin is not installed");
        }
        File file = new File(activeDirectory(), safeName(fileName));
        if (!file.isFile()) {
            throw new UnsatisfiedLinkError("CJS native module is missing: " + fileName);
        }
        System.load(file.getAbsolutePath());
        nativeLibrariesLoaded = true;
    }

    public static synchronized String script(String name, Map<String, String> values)
            throws IOException, JSONException {
        JSONObject bundle = scriptBundle();
        String result = bundle.getString(name);
        if (values != null) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }
        }
        return result;
    }

    private static JSONObject scriptBundle() throws IOException, JSONException {
        String version = preferences().getString(PREF_VERSION, "");
        if (scripts != null && version.equals(scriptsVersion)) {
            return scripts;
        }
        byte[] data = readFile(new File(activeDirectory(), "runtime.json"), MAX_SCRIPT_BYTES);
        JSONObject root = new JSONObject(new String(data, "UTF-8"));
        if (root.optInt("protocol", 0) != HOST_PROTOCOL) {
            throw new IOException("JS 插件协议不兼容");
        }
        scripts = root.getJSONObject("scripts");
        scriptsVersion = version;
        return scripts;
    }

    private static File activeDirectory() {
        return versionDirectory(preferences().getString(PREF_VERSION, ""));
    }

    private static File versionDirectory(String version) {
        return new File(new File(requireContext().getFilesDir(), PLUGIN_DIR), safeName(version));
    }

    private static String currentAbi() {
        String abi = Build.CPU_ABI == null ? "" : Build.CPU_ABI.toLowerCase(Locale.US);
        return abi.contains("arm64") ? "arm64-v8a" : "armeabi-v7a";
    }

    private static byte[] download(String originalUrl, int maxBytes) throws IOException {
        String url = originalUrl;
        if (url.contains("github.com/") || url.contains("raw.githubusercontent.com/")) {
            url = GithubProxy.apply(url);
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept-Encoding", "identity");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("插件下载失败：HTTP " + status);
            }
            int length = connection.getContentLength();
            if (length > maxBytes) throw new IOException("插件文件过大");
            InputStream input = connection.getInputStream();
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream(
                        length > 0 ? Math.min(length, maxBytes) : 16384);
                byte[] buffer = new byte[32 * 1024];
                int total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > maxBytes) throw new IOException("插件文件过大");
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            } finally {
                input.close();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void verifySignature(byte[] payload, byte[] signed) throws Exception {
        byte[] der = Base64.decode(PUBLIC_KEY_DER_BASE64, Base64.DEFAULT);
        PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(key);
        verifier.update(payload);
        if (!verifier.verify(signed)) throw new SecurityException("插件签名校验失败");
    }

    private static void verifySha256(byte[] data, String expected, String name) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String actual = hex(digest.digest(data));
        if (!actual.equalsIgnoreCase(expected)) {
            throw new SecurityException("插件哈希校验失败：" + name);
        }
    }

    private static String hex(byte[] data) {
        StringBuilder value = new StringBuilder(data.length * 2);
        for (byte item : data) value.append(String.format(Locale.US, "%02x", item & 0xff));
        return value.toString();
    }

    private static String safeName(String value) {
        if (value == null) return "";
        return value.replaceAll("[^A-Za-z0-9._-]", "");
    }

    private static void writeAndSync(File file, byte[] data) throws IOException {
        FileOutputStream output = new FileOutputStream(file);
        try {
            output.write(data);
            output.flush();
            output.getFD().sync();
        } finally {
            output.close();
        }
    }

    private static byte[] readFile(File file, int maxBytes) throws IOException {
        if (!file.isFile() || file.length() > maxBytes) throw new IOException("插件文件不可用");
        FileInputStream input = new FileInputStream(file);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) file.length());
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static void pruneOldVersions(File root, String active) {
        File[] children = root.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory() && !active.equals(child.getName())
                    && !child.getName().startsWith(".staging-")) deleteRecursively(child);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        if (!file.delete()) Log.w(TAG, "Unable to delete " + file);
    }
}
