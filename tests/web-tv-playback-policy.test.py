"""Compile the real provider/engine policy; check domain boundaries and manual mode."""
from pathlib import Path
import os, subprocess, tempfile, sys
from urllib.parse import urlsplit

root = Path(__file__).resolve().parents[1]
source = root / 'app/src/main/java/xiao/bu/tv/WebTvPlaybackPolicy.java'
with tempfile.TemporaryDirectory() as folder:
    tmp = Path(folder)
    files = {
        'android/content/Context.java': 'package android.content; public class Context { public android.content.res.AssetManager getAssets(){return null;} }',
        'android/content/res/AssetManager.java': 'package android.content.res; public class AssetManager { public java.io.InputStream open(String s) throws java.io.IOException {return null;} }',
        'xiao/bu/tv/AppLog.java': 'package xiao.bu.tv; public class AppLog {public static int w(String t,String m,Throwable e){return 0;}}',
        'xiao/bu/tv/PolicyCheck.java': '''package xiao.bu.tv;
public class PolicyCheck {
 static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
 public static void main(String[] args){
  for(String u:new String[]{"https://www.youtube.com/watch?v=FQvIbwJts94","https://m.youtube.com/watch?v=abc",
      "https://youtu.be/abc?si=123","https://www.youtube.com/embed/abc","https://www.youtube-nocookie.com/embed/abc",
      "https://youtube.com/live/abc","https://youtube.com/shorts/abc","webview://https://www.youtube.com/watch?v=abc"}) {
   check(WebTvPlaybackPolicy.youtubePlayer(u),"YouTube watch route: "+u);
   check(WebTvPlaybackPolicy.requiresWebView(u,true,true),"YouTube requires original WebView: "+u);
   check(WebTvPlaybackPolicy.webPlayerOnly(u),"YouTube retains SDK: "+u);
   check(!WebTvPlaybackPolicy.autoTakeoverAllowed(u),"YouTube must not hand off signed fragments: "+u);
   check(!WebTvPlaybackPolicy.autoTakeoverAllowed("https://accounts.google.com/",u),"Keep YouTube channel policy during navigation: "+u);
  }
  for(String u:new String[]{"https://youtube.com/","https://youtube.com/results?search_query=abc",
      "https://youtube.com.evil.test/watch?v=abc","https://notyoutube.com/watch?v=abc",
      "https://youtube.com@evil.test/watch?v=abc","https://station.test/?url=https://youtube.com/watch?v=abc",
      "https://youtube.com/watchlater","ftp://youtube.com/watch?v=abc",null,"bad url"})
   check(!WebTvPlaybackPolicy.youtubePlayer(u),"YouTube domain/path boundary: "+u);
  for(String u:args){check(WebTvPlaybackPolicy.requiresWebView(u,true,true),"Huya list room must use original renderer: "+u);check(!WebTvPlaybackPolicy.autoTakeoverAllowed(u),"Huya list must retain SDK playback: "+u);}
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
    rooms=[]
    if len(sys.argv)>1:
        for line in Path(sys.argv[1]).read_text(encoding='utf-8-sig').splitlines():
            if ',webview://' in line:
                url=line.split(',webview://',1)[1]
                if urlsplit(url).hostname=='www.huya.com': rooms.append(url)
    subprocess.run([str(jdk/'java.exe'),'-cp',folder,'xiao.bu.tv.PolicyCheck',*rooms],check=True)
    if rooms: print(f'PASS supplied playlist: {len(rooms)} Huya entries retain original web rendering')
