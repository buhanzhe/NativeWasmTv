package xiao.bu.tv;

import android.content.Context;
import android.os.Build;
import dalvik.system.DexClassLoader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONObject;
import xiao.bu.tv.jar.ChannelPlugin;
import xiao.bu.tv.jar.PythonPlugin;

final class InstalledPlugins {
    static final int LIMIT = 32 * 1024 * 1024;
    private static final String PYTHON_ENTRY = "xiao.bu.python.RuntimePlugin";
    private static PythonPlugin python;
    private static String pythonDigest;

    static synchronized JSONObject status(Context context) throws Exception {
        JSONObject result = new JSONObject().put("ok", true);
        for (String id : new String[]{"tvbus", "bl", "tvbox", "file-browser", "folia", "folia-full", "python"}) {
            JSONObject plugin = new JSONObject();
            if ("tvbus".equals(id)) {
                TvbusBundle.Installed tvbus = TvbusBundle.installed(context);
                if (tvbus != null) plugin.put("version", tvbus.version);
            } else {
                File jar = installed(context, id);
                if (jar != null) plugin.put("version", metadata(jar, id).optString("version", "1"));
            }
            result.put(id, plugin.put("installed", plugin.has("version")));
        }
        return result.put("pythonRestart", python != null && !pythonDigest.equals(digest(context, "python")));
    }

    static synchronized String install(Context context, String id, byte[] bytes) throws Exception {
        if ("tvbus".equals(id)) return TvbusBundle.install(context, bytes);
        if (bytes.length == 0 || bytes.length > LIMIT) throw new IOException("插件大小无效");
        File staging = stagingFile(context);
        try {
            try (FileOutputStream out = new FileOutputStream(staging)) {
                out.write(bytes);
                out.getFD().sync();
            }
            return install(context, id, staging, null);
        } finally {
            staging.delete();
        }
    }

    static File stagingFile(Context context) throws IOException {
        return File.createTempFile("plugin-", ".tmp", directory(context));
    }

    static synchronized String install(Context context, String id, File staging, String expected) throws Exception {
        long size = staging.length();
        if (size == 0 || size > LIMIT) throw new IOException("插件大小无效");
        String sha = sha256(staging);
        if (expected != null && !sha.equals(expected)) throw new IOException("插件 SHA-256 校验失败");
        if ("tvbus".equals(id)) {
            try (InputStream input = new FileInputStream(staging)) {
                return TvbusBundle.install(context, RadioUiCatalog.read(input, TvbusBundle.LIMIT));
            }
        }
        entryFor(id);
        JSONObject info = metadata(staging, id);
        File jar = new File(directory(context), sha + ".jar");
        if (!jar.exists() && (!staging.setReadOnly() || !staging.renameTo(jar)))
            throw new IOException("无法保存插件");
        Class<?> type = loader(context, jar).loadClass(info.getString("entry"));
        Class<?> contract = "python".equals(id) ? PythonPlugin.class : ChannelPlugin.class;
        if (!contract.isAssignableFrom(type)) throw new IOException("插件入口类型不匹配");
        type.getConstructor();
        if (!context.getSharedPreferences("installed-plugins", 0).edit().putString(id, sha).commit())
            throw new IOException("无法激活插件");
        return info.optString("version", "1");
    }

    static String sha256(File file) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[16384];
            for (int count; (count = in.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        StringBuilder value = new StringBuilder(64);
        for (byte b : digest.digest()) value.append(String.format(java.util.Locale.US, "%02x", b & 255));
        return value.toString();
    }

    static File installed(Context context, String id) throws IOException {
        entryFor(id);
        String sha = digest(context, id);
        if (sha.isEmpty()) return null;
        if (!sha.matches("[0-9a-f]{64}")) throw new IOException("插件记录无效");
        File jar = new File(directory(context), sha + ".jar");
        if (!jar.isFile()) throw new IOException("插件文件缺失，请重新安装");
        return jar;
    }

    static JSONObject metadata(File jar, String id) throws Exception {
        boolean py = "python".equals(id);
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry entry = zip.getEntry(py ? "python-plugin.json" : "channel-plugin.json");
            ZipEntry dex = zip.getEntry("classes.dex");
            if (entry == null || dex == null || dex.getSize() < 112) throw new IOException("插件包格式不正确");
            JSONObject info;
            try (InputStream stream = zip.getInputStream(entry)) {
                info = new JSONObject(new String(RadioUiCatalog.read(stream, 4096), "UTF-8"));
            }
            String name = info.getString("entry");
            if (info.getInt("api") != 1 || info.getInt("minSdk") < 14
                    || info.getInt("minSdk") > Build.VERSION.SDK_INT
                    || (id.isEmpty() ? !name.startsWith("xiao.bu.channel.") : !entryFor(id).equals(name)))
                throw new IOException("插件类型或 Android 版本不兼容");
            if (py && (info.optJSONArray("abis") == null
                    || !info.getJSONArray("abis").toString().contains("\"" + BuildConfig.CJS_PLUGIN_ABI + "\"")
                    || zip.getEntry("runtime/lib/libntvpython.so") == null))
                throw new IOException("Python 插件不支持当前架构 " + BuildConfig.CJS_PLUGIN_ABI);
            return info;
        }
    }

    static DexClassLoader loader(Context context, File jar) throws IOException {
        if (!jar.setReadOnly()) throw new IOException("插件必须只读");
        File dex = new File(directory(context), "dex");
        if (!dex.isDirectory() && !dex.mkdirs() && !dex.isDirectory()) throw new IOException("无法创建 DEX 目录");
        return new DexClassLoader(jar.getAbsolutePath(), dex.getAbsolutePath(), null,
                ChannelPlugin.class.getClassLoader());
    }

    static JSONObject python(Context context, JSONObject request) throws Exception {
        return pythonRuntime(context).invoke(request);
    }

    private static synchronized PythonPlugin pythonRuntime(Context context) throws Exception {
        String sha = digest(context, "python");
        if (python != null) {
            if (!sha.equals(pythonDigest)) throw new IOException("请重启 nTv 以启用新 Python 插件");
            return python;
        }
        File archive = installed(context, "python");
        if (archive == null) throw new IOException("请先在管理网页安装 Python 插件");
        metadata(archive, "python");
        PythonPlugin runtime = loader(context, archive).loadClass(PYTHON_ENTRY)
                .asSubclass(PythonPlugin.class).getConstructor().newInstance();
        runtime.initialize(context.getApplicationContext(), archive);
        pythonDigest = sha;
        return python = runtime;
    }

    private static String entryFor(String id) throws IOException {
        if ("bl".equals(id)) return "xiao.bu.channel.bl.BlPlugin";
        if ("tvbox".equals(id)) return "xiao.bu.channel.tvbox.TvBoxPlugin";
        if ("file-browser".equals(id)) return "xiao.bu.channel.files.FileBrowserPlugin";
        if ("folia".equals(id)) return "xiao.bu.channel.FoliaChannel";
        if ("folia-full".equals(id)) return "xiao.bu.channel.FoliaFull";
        if ("python".equals(id)) return PYTHON_ENTRY;
        throw new IOException("未知插件类型");
    }

    static boolean isChannel(String id) {
        return "bl".equals(id) || "tvbox".equals(id) || "file-browser".equals(id)
                || "folia".equals(id) || "folia-full".equals(id);
    }

    static String digest(Context context, String id) {
        return context.getSharedPreferences("installed-plugins", 0).getString(id, "");
    }

    private static File directory(Context context) throws IOException {
        File directory = new File(context.getFilesDir(), "installed-plugins");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
            throw new IOException("无法创建插件目录");
        return directory;
    }
}
