package xiao.bu.tv;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import okhttp3.HttpUrl;

/** Embedding-app compatibility; no website code is packaged inside litecore.jar. */
public final class WindowsSiteConfiguration {
  private WindowsSiteConfiguration() {}
  public static void install() throws IOException {
    Map<String,List<String>> byHost=new HashMap<>();
    for(String name:new String[]{"ntv-player-compat.user.js","ntv-site-selection.user.js","ntv-media-reactivity.user.js","ntv-tv-portal.user.js","ntv-live-recovery.user.js"}){
      String source;
      try(InputStream in=WindowsSiteConfiguration.class.getResourceAsStream("/userscripts/"+name)){
        if(in==null)throw new IOException("Missing application userscript: "+name);
        source=new String(in.readAllBytes(),StandardCharsets.UTF_8);
      }
      String prefix="// @match *://";
      for(String line:source.split("\n"))if(line.startsWith(prefix)){
        String host=line.substring(prefix.length()).trim();
        if(!host.endsWith("/*"))throw new IOException("Unsupported bundled userscript match: "+host);
        byHost.computeIfAbsent(host.substring(0,host.length()-2),k->new ArrayList<>()).add(source);
      }
    }
    WindowsCoreSession.installHostPolicy(new WebSiteCompatibility());
    WindowsCoreSession.installDocumentScripts(page->{HttpUrl url=HttpUrl.parse(page);return url==null?Collections.emptyList():byHost.getOrDefault(url.host(),Collections.emptyList());});
  }
}
