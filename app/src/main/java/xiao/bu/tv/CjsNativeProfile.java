package xiao.bu.tv;

import java.io.IOException;
import org.json.JSONObject;

/** Protocol 5 selects by APK ABI and device API, never the device's maximum ABI. */
final class CjsNativeProfile {
    static String select(String abi, int api) {
        if ("arm64-v8a".equals(abi) && api >= 21) return "arm64";
        if ("armeabi-v7a".equals(abi) && api >= 14) return api >= 19 ? "armv7-perf" : "armv7-base";
        throw new IllegalArgumentException("不支持的插件运行架构或系统版本");
    }

    static boolean accepts(JSONObject file, String abi, int api) throws Exception {
        String entryAbi = file.getString("abi");
        if ("all".equals(entryAbi)) {
            if (!"runtime.json".equals(file.optString("name")) || file.has("profile"))
                throw new IOException("公共插件文件声明无效");
            return true;
        }
        String selected = select(abi, api);
        if (!selected.equals(file.optString("profile"))) return false;
        int minimum = "arm64".equals(selected) ? 21 : "armv7-perf".equals(selected) ? 19 : 14;
        if (!abi.equals(entryAbi) || file.optInt("minSdk", -1) != minimum || api < minimum)
            throw new IOException("插件 profile/API/ABI 声明不匹配");
        return true;
    }
}
