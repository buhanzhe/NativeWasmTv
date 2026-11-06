package xiao.bu.tv;

import android.app.Activity;
import xiao.bu.tv.AppLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Pattern;

/** Locates local scripts and maintains the short-lived online script cache. */
final class Ku9ScriptLoader {
    private static final String TAG = "Ku9ScriptLoader";
    private static final String HNYX_SCRIPT = "hnyx.js";
    private static final String SCRIPT_DIRECTORY = "js";
    private static final int MAX_SCRIPT_BYTES = 2 * 1024 * 1024;
    private static final long ONLINE_CACHE_MS = 30L * 60L * 1000L;
    private static final Pattern SAFE_NAME = Pattern.compile(
            "[\\p{L}\\p{N}\\p{M}_.-]+\\.js", Pattern.CASE_INSENSITIVE);

    private final Activity activity;

    Ku9ScriptLoader(Activity activity) {
        this.activity = activity;
        preferredDirectory(activity);
    }

    static SavedScript saveUserScript(Activity activity, String fileName, byte[] body)
            throws IOException {
        String safeName = fileName == null ? "" : fileName.trim();
        if (!isSafeScriptName(safeName)) {
            throw new IOException("只支持名称安全的 .js 文件");
        }
        if (HNYX_SCRIPT.equalsIgnoreCase(safeName)) {
            safeName = HNYX_SCRIPT;
        }
        if (body == null || body.length == 0) {
            throw new IOException("JS 文件内容为空");
        }
        if (body.length > MAX_SCRIPT_BYTES) {
            throw new IOException("JS 文件不能超过 2 MB");
        }
        File directory = preferredDirectory(activity);
        if (directory == null) {
            throw new IOException("无法创建 Ku9 JS 目录");
        }
        File target = new File(directory, safeName);
        boolean replaced = target.isFile();
        writeUserScript(target, body);
        File readable = findUserScript(activity, safeName);
        if (readable == null || readable.length() != body.length
                || readFile(readable).length() == 0) {
            throw new IOException("JS 文件保存后校验失败");
        }
        return new SavedScript(safeName, replaced, readable.getAbsolutePath());
    }

    private static void writeUserScript(File target, byte[] body) throws IOException {
        File directory = target.getParentFile();
        String fileName = target.getName();
        boolean replaced = target.isFile();
        File temporary = new File(directory, "." + fileName + ".uploading");
        File backup = new File(directory, "." + fileName + ".backup");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("无法准备 JS 临时文件");
        }
        if (backup.exists() && !backup.delete()) {
            throw new IOException("无法准备 JS 备份文件");
        }
        try {
            FileOutputStream output = new FileOutputStream(temporary);
            try {
                output.write(body);
                output.flush();
            } finally {
                output.close();
            }
            if (replaced && !target.renameTo(backup)) {
                throw new IOException("无法备份同名 JS 文件");
            }
            if (!temporary.renameTo(target)) {
                if (replaced && !backup.renameTo(target)) {
                    AppLog.e(TAG, "Unable to restore user script backup " + backup);
                }
                throw new IOException("无法保存 JS 文件");
            }
            if (backup.exists() && !backup.delete()) {
                AppLog.w(TAG, "Unable to remove user script backup " + backup);
            }
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                AppLog.w(TAG, "Unable to remove temporary user script " + temporary);
            }
        }
    }

    static boolean isSource(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.trim().toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false;
        }
        try {
            String path = URI.create(lower).getPath();
            return isScriptPath(path);
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static boolean isScriptPath(String path) {
        if (path == null) return false;
        return path.toLowerCase(Locale.US).endsWith(".js");
    }

    String load(String sourceUrl) throws IOException {
        return load(sourceUrl, true);
    }

    String load(String sourceUrl, boolean legacySyntax) throws IOException {
        URI uri;
        try {
            uri = URI.create(sourceUrl);
        } catch (RuntimeException error) {
            throw new IOException("酷9脚本地址格式错误", error);
        }
        String path = uri.getPath();
        if (!isScriptPath(path)) {
            throw new IOException("不是有效的酷9脚本地址");
        }
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        if (!isSafeScriptName(fileName)) {
            throw new IOException("酷9脚本文件名不安全");
        }

        String script;
        // Placeholder hosts (usually "A") use local scripts; real hosts load online
        // scripts regardless of their directory.
        String host = uri.getHost();
        boolean localPlaceholder = host != null && host.indexOf('.') < 0
                && host.indexOf(':') < 0;
        if (localPlaceholder) {
            script = readLocal(fileName);
        } else {
            String scriptUrl = GithubProxy.apply(activity, withoutQuery(uri));
            script = loadOnlineCache(scriptUrl);
        }
        return legacySyntax && HNYX_SCRIPT.equalsIgnoreCase(fileName) ? toLegacySyntax(script) : script;
    }

    static boolean isSafeScriptName(String fileName) {
        return fileName != null && SAFE_NAME.matcher(fileName).matches();
    }

    private String readLocal(String fileName) throws IOException {
        File script = findUserScript(activity, fileName);
        if (script == null || !script.isFile()) {
            throw new IOException("本地缺少 " + fileName + "；请将文件放入 "
                    + directoryHint());
        }
        return readFile(script);
    }

    private String loadOnlineCache(String url) throws IOException {
        File directory = new File(activity.getCacheDir(), "ku9-scripts");
        File file = new File(directory, md5(url) + ".js");
        long age = System.currentTimeMillis() - file.lastModified();
        if (file.isFile() && age >= 0 && age < ONLINE_CACHE_MS) {
            try {
                AppLog.i(TAG,"Online script cache hit ageMs="+age);
                return readFile(file);
            } catch (IOException error) {
                AppLog.w(TAG, "Unable to read script cache " + file, error);
            }
        }
        try {
            String script = Ku9HttpClient.getText(url, null, MAX_SCRIPT_BYTES);
            writeCache(directory, file, script);
            return script;
        } catch (IOException networkError) {
            if (file.isFile()) {
                try {
                    AppLog.w(TAG, "Refresh failed; using stale script cache " + url,
                            networkError);
                    return readFile(file);
                } catch (IOException cacheError) {
                    AppLog.w(TAG, "Unable to read stale script cache " + file, cacheError);
                }
            }
            throw networkError;
        }
    }

    private static File internalDirectory(Activity activity) {
        return new File(activity.getFilesDir(), SCRIPT_DIRECTORY);
    }

    private static File preferredDirectory(Activity activity) {
        File external = ensureDirectory(externalDirectory(activity));
        return external == null ? ensureDirectory(internalDirectory(activity)) : external;
    }

    private static File externalDirectory(Activity activity) {
        File root = externalFilesRoot(activity);
        return root == null ? null : new File(root, SCRIPT_DIRECTORY);
    }

    private static File externalFilesRoot(Activity activity) {
        try {
            return activity.getExternalFilesDir(null);
        } catch (RuntimeException error) {
            AppLog.w(TAG, "Unable to resolve external user script directory", error);
            return null;
        }
    }

    private static File ensureDirectory(File directory) {
        if (directory == null) {
            return null;
        }
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            AppLog.w(TAG, "Unable to create user script directory " + directory);
            return null;
        }
        AppLog.i(TAG, "User script directory: " + directory.getAbsolutePath());
        return directory;
    }

    static final class SavedScript {
        final String name;
        final boolean replaced;
        final String path;

        SavedScript(String name, boolean replaced, String path) {
            this.name = name;
            this.replaced = replaced;
            this.path = path;
        }
    }

    private String directoryHint() {
        File external = externalDirectory(activity);
        return external == null ? internalDirectory(activity).getAbsolutePath()
                : external.getAbsolutePath();
    }

    private static File findScript(File directory, String fileName) {
        if (directory == null || !directory.isDirectory()) {
            return null;
        }
        File exact = new File(directory, fileName);
        if (exact.isFile()) {
            return exact;
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return null;
        }
        for (File file : files) {
            if (file.isFile() && fileName.equalsIgnoreCase(file.getName())) {
                return file;
            }
        }
        return null;
    }

    private static File findUserScript(Activity activity, String fileName) {
        File script = findScript(externalDirectory(activity), fileName);
        return script == null ? findScript(internalDirectory(activity), fileName) : script;
    }

    private static String readFile(File file) throws IOException {
        FileInputStream input = new FileInputStream(file);
        try {
            return Ku9HttpClient.readUtf8(input, MAX_SCRIPT_BYTES);
        } finally {
            input.close();
        }
    }

    private static synchronized void writeCache(File directory, File file, String content) {
        if (ensureDirectory(directory) == null) {
            return;
        }
        try {
            writeUserScript(file, content.getBytes("UTF-8"));
            File[] files=directory.listFiles();
            if(files!=null) {
                java.util.Arrays.sort(files,(a,b)->a.lastModified()<b.lastModified()?-1:a.lastModified()==b.lastModified()?0:1);
                long bytes=0;int count=0;for(File cached:files)if(cached.getName().endsWith(".js")){bytes+=cached.length();count++;}
                for(File cached:files)if((count>64||bytes>8*1024*1024L)&&!cached.equals(file)&&cached.getName().endsWith(".js")) {
                    long size=cached.length();if(cached.delete()){count--;bytes-=size;}
                }
            }
        } catch (IOException error) {
            AppLog.w(TAG, "Unable to write script cache " + file, error);
        }
    }

    private static String withoutQuery(URI uri) throws IOException {
        try {
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, null)
                    .toString();
        } catch (Exception error) {
            throw new IOException("酷9脚本地址格式错误", error);
        }
    }

    private static String toLegacySyntax(String script) {
        return script
                .replaceAll("\\bconst\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=", "var $1 =")
                .replaceAll("\\blet\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=", "var $1 =");
    }

    private static String md5(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(value.getBytes("UTF-8"));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                result.append(String.format(Locale.US, "%02x", item & 0xff));
            }
            return result.toString();
        } catch (Exception error) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
