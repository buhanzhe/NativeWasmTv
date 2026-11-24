package xiao.bu.tv;

import android.content.Context;
import android.os.Build;
import dalvik.system.DexClassLoader;
import java.io.File;
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
        if (archive == null) throw new IOException("Python 运行时尚未安装");
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
