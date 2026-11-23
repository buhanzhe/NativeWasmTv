package xiao.bu.tv;

import android.net.Uri;
import org.json.JSONObject;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** https://host/plugin.jar?sha256=HEX&...plugin configuration... */
final class JarChannelSource {
    final String url, sha, installed;
    final JSONObject config;
    static boolean matches(String value) {
        if(value==null)return false;
        Uri uri=Uri.parse(value);
        return uri.getPath()!=null && uri.getPath().endsWith(".jar")
                && ("https".equals(uri.getScheme()) || "ntv-plugin".equals(uri.getScheme()));
    }
    JarChannelSource(String source) throws Exception {
        if (!matches(source)) throw new IOException("不是 JAR 频道");
        Uri uri=Uri.parse(source);
        if (uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null)
            throw new IOException("JAR 频道需要 HTTPS .jar 地址");
        boolean local = "ntv-plugin".equals(uri.getScheme());
        String path=uri.getPath();
        installed=local && path.length()>5 ? path.substring(1,path.length()-4) : "";
        if (local && (!"local".equals(uri.getAuthority()) || !InstalledPlugins.isChannel(installed)))
            throw new IOException("未知的本地频道插件");
        url=uri.buildUpon().clearQuery().build().toString();
        config=new JSONObject();
        String digest=null, query=uri.getEncodedQuery();
        Set<String> seen=new HashSet<>();
        if (query!=null && !query.isEmpty()) for (String parameter:query.split("&",-1)) {
            int equals=parameter.indexOf('=');
            String key=Uri.decode(equals<0 ? parameter : parameter.substring(0,equals));
            String value=equals<0 ? "" : Uri.decode(parameter.substring(equals+1));
            if (!seen.add(key)) throw new IOException("JAR 配置参数重复: "+key);
            if ("sha256".equals(key)) digest=value;
            else config.put(key,value);
        }
        if (!local && (digest==null || !digest.matches("[0-9a-f]{64}")))
            throw new IOException("JAR 频道缺少有效 SHA-256");
        sha=digest;
    }
}
