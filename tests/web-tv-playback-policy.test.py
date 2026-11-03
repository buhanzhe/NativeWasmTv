"""Compile the real provider/engine policy; check domain boundaries and manual mode."""
from pathlib import Path
import os, subprocess, tempfile

root = Path(__file__).resolve().parents[1]
source = root / 'app/src/main/java/xiao/bu/tv/WebTvPlaybackPolicy.java'
with tempfile.TemporaryDirectory() as folder:
    tmp = Path(folder)
    files = {
        'android/content/Context.java': 'package android.content; public class Context { public android.content.res.AssetManager getAssets(){return null;} }',
        'android/content/res/AssetManager.java': 'package android.content.res; public class AssetManager { public java.io.InputStream open(String s) throws java.io.IOException {return null;} }',
        'android/util/Log.java': 'package android.util; public class Log {public static int w(String t,String m,Throwable e){return 0;}}',
        'xiao/bu/tv/PolicyCheck.java': '''package xiao.bu.tv;
public class PolicyCheck {
 static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
 public static void main(String[] args){
  for(String u:new String[]{"https://www.gdtv.cn/tvChannelDetail/43","https://m.gdtv.cn/tvChannelDetail/43?ujs=YWJj","https://gdtv.cn/","https://TV.GDTV.CN/"}) {
   check(WebTvPlaybackPolicy.webPlayerOnly(u),"Guangdong original web player: "+u);
   check(!WebTvPlaybackPolicy.requiresWebView(u,true,true),"Guangdong may use persistent Lite HLS session: "+u);
  }
  for(String u:new String[]{"https://gdtv.cn.evil.test/","https://notgdtv.cn/","https://station.test/?next=https://www.gdtv.cn/","https://gdtv.cn@station.test/",null,"bad url"})
   check(!WebTvPlaybackPolicy.webPlayerOnly(u),"domain boundary: "+u);
  for(String u:new String[]{"https://www.huya.com/11342412","https://huya.com/30631741/","https://m.huya.com/11342412?from=tv","https://WWW.HUYA.COM/30631741"})
   check(WebTvPlaybackPolicy.requiresWebView(u,true,true),"Huya Worker room requires existing WebView: "+u);
  for(String u:new String[]{"https://huya.com.evil.test/11342412","https://nothuya.com/11342412","https://huya.com@evil.test/11342412","https://example.test/?url=https://www.huya.com/11342412","https://www.huya.com/","https://www.huya.com/video/11342412",null,"bad url"})
   check(!WebTvPlaybackPolicy.requiresWebView(u,true,true),"Unrelated page must keep selected Lite engine: "+u);
  String other="https://www.sxrtv.com/live";
  check(!WebTvPlaybackPolicy.requiresWebView(other,true,true),"other sites retain Lite sniffing");
  check(WebTvPlaybackPolicy.requiresWebView(other,false,true),"manual play needs original web renderer");
  check(WebTvPlaybackPolicy.requiresWebView(other,true,false),"disabled native takeover preserves web playback");
  System.out.println("PASS Guangdong exact/subdomain routing, lookalike URLs, Lite sniffing and manual WebView playback");
 }
}'''
    }
    for name, body in files.items():
        file = tmp / name; file.parent.mkdir(parents=True, exist_ok=True); file.write_text(body, encoding='utf8')
    jdk = Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14')) / 'bin'
    subprocess.run([str(jdk/'javac.exe'),'--release','8','-encoding','UTF-8','-d',folder,str(source),*map(str,tmp.rglob('*.java'))],check=True)
    subprocess.run([str(jdk/'java.exe'),'-cp',folder,'xiao.bu.tv.PolicyCheck'],check=True)
