"""Real preference-backed memory survives navigation, source reorder and process recreation."""
from pathlib import Path
import os, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory() as folder:
    out=Path(folder)
    files={
      'android/content/SharedPreferences.java':'''package android.content;public interface SharedPreferences {String getString(String k,String d);Editor edit();interface Editor {Editor putString(String k,String v);void apply();}}''',
      'android/net/Uri.java':'''package android.net;public class Uri {public static String encode(String s){try{return java.net.URLEncoder.encode(s,"UTF-8");}catch(Exception e){throw new RuntimeException(e);}}}''',
      'xiao/bu/tv/ChannelPlaybackMemory.java':(root/'app/src/main/java/xiao/bu/tv/ChannelPlaybackMemory.java').read_text(encoding='utf8'),
      'xiao/bu/tv/WebSiteProfile.java':(root/'app/src/main/java/xiao/bu/tv/WebSiteProfile.java').read_text(encoding='utf8'),
      'xiao/bu/tv/Check.java':'''package xiao.bu.tv;import java.util.*;import android.content.SharedPreferences;
class Channel {String[] urls;Channel(String... v){urls=v;}int sourceCount(){return urls.length;}String sourceUrl(int i){return urls[i];}}
public class Check {
 static class Prefs implements SharedPreferences,SharedPreferences.Editor {Map<String,String> data=new HashMap<>();int writes;public String getString(String k,String d){return data.containsKey(k)?data.get(k):d;}public Editor edit(){return this;}public Editor putString(String k,String v){data.put(k,v);return this;}public void apply(){writes++;}}
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] args){Prefs p=new Prefs();ChannelPlaybackMemory m=new ChannelPlaybackMemory(p);Channel a=new Channel("one","two"),b=new Channel("other");
 check(m.source("A",a)==0&&m.decode("A","hardware").equals("hardware"),"unset channel uses defaults");
 m.rememberSource("A","two");m.rememberDecode("A","software");
 check(m.source("B",b)==0&&m.decode("B","hardware").equals("hardware"),"separate channel does not inherit previous software/line");
 check(m.source("A",a)==1&&m.decode("A","hardware").equals("software"),"return to A restores explicit line 2 and software");
 m=new ChannelPlaybackMemory(p);check(m.source("A",a)==1&&m.decode("A","auto").equals("software"),"process recreation restores persisted choices");
 check(m.source("A",new Channel("two","one"))==0,"source identity survives playlist reorder");
 check(m.source("A",new Channel("one"))==0,"removed source falls back to valid first line");
 m.rememberDecode("A","corrupt");check(m.decode("A","auto").equals("auto"),"invalid stored decoder falls back");
 check(m.userAgent("A","auto").equals("windows")&&m.engine("A","system",true).equals("system"),"legacy default becomes Windows");
 m.rememberUserAgent("A","ipad");m.rememberEngine("A","lite");
 m.rememberUserAgent("B","windows");m.rememberEngine("B","system");
 m=new ChannelPlaybackMemory(p);
 check(m.userAgent("A","auto").equals("ipad")&&m.engine("A","system",true).equals("lite"),"web choices survive recreation");
 check(m.userAgent("B","auto").equals("windows")&&m.engine("B","lite",true).equals("system"),"different channel web choices stay isolated");
 check(m.engine("A","lite",false).equals("system"),"standard APK cannot restore remembered Lite");
 for(String mode:new String[]{"windows","macos","ipad","native"}){m.rememberUserAgent("A",mode);check(m.userAgent("A","windows").equals(mode),"explicit UA modes are remembered");}
 m.rememberUserAgent("A","auto");int migration=p.writes;check(m.userAgent("A","ipad").equals("windows")&&p.writes==migration+1,"legacy saved auto migrates to explicit Windows");
 m.userAgent("A","ipad");check(p.writes==migration+1,"migration is written once");
 m.rememberUserAgent("A","corrupt");m.rememberEngine("A","corrupt");
 check(m.userAgent("A","auto").equals("windows")&&m.engine("A","system",true).equals("system"),"invalid web choices fall back");
 int writes=p.writes;for(int i=0;i<500;i++){m.source("A",a);m.decode("B","hardware");m.userAgent("A","auto");m.engine("A","system",true);}check(writes==p.writes,"reads never write preferences");
 System.out.println("PASS channel memory: defaults, isolation, source identity/removal, decoder restore, persistence and read-only lookup");}
}'''}
    for name,body in files.items():
        p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf8')
    jdk=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
    subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',str(out),*[str(out/name) for name in files]],check=True)
    subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'xiao.bu.tv.Check'],check=True)
