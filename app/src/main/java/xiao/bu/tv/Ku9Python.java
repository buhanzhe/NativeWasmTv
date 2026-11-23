package xiao.bu.tv;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import okhttp3.HttpUrl;
import org.json.JSONObject;

final class Ku9Python {
    private static final int LIMIT = 2 * 1024 * 1024;

    static boolean matches(String value) {
        HttpUrl url = value == null ? null : HttpUrl.parse(value);
        return url != null && nameValid(name(url));
    }

    static String save(Context context, String name, byte[] bytes) throws IOException {
        if (!nameValid(name) || bytes.length == 0 || bytes.length > LIMIT)
            throw new IOException("请选择 2 MB 以内的 .py 文件");
        File directory = directory(context);
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
            throw new IOException("无法创建酷9 Python 脚本目录");
        File temp = File.createTempFile("script-", ".tmp", directory);
        try {
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(bytes);
                output.getFD().sync();
            }
            if (!temp.renameTo(new File(directory, name))) throw new IOException("无法保存 Python 脚本");
        } finally {
            temp.delete();
        }
        return new android.net.Uri.Builder().scheme("http").authority("A").appendPath(name).build().toString();
    }

    static String load(Context context, String source) throws IOException {
        HttpUrl url = HttpUrl.parse(source);
        if (url == null || !nameValid(name(url))) throw new IOException("酷9 Python 地址无效");
        if ("a".equals(url.host())) {
            File script = new File(directory(context), name(url));
            if (!script.isFile()) throw new IOException("请先上传 " + name(url));
            try (FileInputStream input = new FileInputStream(script)) {
                return Ku9HttpClient.readUtf8(input, LIMIT);
            }
        }
        return Ku9HttpClient.getText(url.newBuilder().query(null).fragment(null).build().toString(), null, LIMIT);
    }

    static JSONObject run(Context context, String source, String code, JSONObject item) throws Exception {
        HttpUrl url = HttpUrl.parse(source);
        JSONObject params = new JSONObject();
        for (String key : url.queryParameterNames()) params.put(key, url.queryParameter(key));
        item.put("params", params);
        return InstalledPlugins.python(context, new JSONObject().put("action", "ku9")
                .put("source", code).put("item", item).put("origin", source));
    }

    private static String name(HttpUrl url) {
        return url.pathSegments().get(url.pathSize() - 1);
    }

    private static boolean nameValid(String name) {
        return name != null && name.matches("[\\p{L}\\p{N}][\\p{L}\\p{N}._-]{0,79}\\.py");
    }

    private static File directory(Context context) {
        return new File(context.getFilesDir(), "ku9-python");
    }
}
