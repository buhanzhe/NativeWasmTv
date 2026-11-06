"""Execute real domain persistence and validation, with only SharedPreferences replaced."""
from pathlib import Path
import os, subprocess, tempfile

root=Path(__file__).resolve().parents[1]
cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
def jar(group,artifact):
    return next(p for p in (cache/group/artifact).rglob('*.jar') if not p.name.endswith(('-sources.jar','-javadoc.jar')))
classpath=os.pathsep.join(map(str,[jar('org.json','json'),jar('com.squareup.okhttp3','okhttp'),jar('com.squareup.okio','okio')]))
files={
 'android/content/SharedPreferences.java':'''package android.content;public interface SharedPreferences {
 String getString(String k,String d);boolean getBoolean(String k,boolean d);float getFloat(String k,float d);Editor edit();
 interface Editor {Editor putString(String k,String v);void apply();}}''',
 'xiao/bu/tv/WebDomainSettings.java':(root/'app/src/main/java/xiao/bu/tv/WebDomainSettings.java').read_text('utf8'),
 'xiao/bu/tv/Check.java':r'''package xiao.bu.tv;
import java.util.*;import org.json.*;import android.content.SharedPreferences;
class Check {
 static class Prefs implements SharedPreferences,SharedPreferences.Editor {
  Map<String,Object> data=new HashMap<>();int writes;
  public String getString(String k,String d){return data.containsKey(k)?(String)data.get(k):d;}
  public boolean getBoolean(String k,boolean d){return data.containsKey(k)?(Boolean)data.get(k):d;}
  public float getFloat(String k,float d){return data.containsKey(k)?(Float)data.get(k):d;}
  public Editor edit(){return this;}public Editor putString(String k,String v){data.put(k,v);return this;}public void apply(){writes++;}
 }
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 public static void main(String[]args)throws Exception {
  Prefs p=new Prefs();p.data.put("web_view_resolution","1080p");p.data.put("web_view_auto_play",true);
  String a=WebDomainSettings.key("webview://https://EXAMPLE.com:8443/a?token=1"),b=WebDomainSettings.key("https://other.test/");
  check(a.equals(WebDomainSettings.key("http://example.com/b")),"paths, ports, scheme and case share host");
  check(!a.equals(WebDomainSettings.key("https://sub.example.com/a")),"subdomains independent");
  check(WebDomainSettings.key("rtsp://example.com/a")==null,"native stream has no web domain");
  JSONObject defaults=WebDomainSettings.defaults(p,"system","auto");String original=defaults.toString();
  check(defaults.getString("webViewUserAgent").equals("windows"),"legacy default is explicit Windows");
  JSONObject patch=new JSONObject().put("webPreferenceScope","domain").put("webPreferenceDomain",a)
   .put("webSourceEngine","lite").put("webViewUserAgent","ipad").put("webViewResolution","4k")
   .put("webViewPageCover",true).put("webViewPageCoverStyle","black").put("webViewMinimalMode",true)
   .put("webViewBrowserVersion","138").put("webViewPageScale",1.5).put("webViewLoadImages",false)
   .put("webViewAutoPlaySniffed",false).put("webViewAutoPlay",false);
  WebDomainSettings.save(p,a,patch,true);
  JSONObject effective=WebDomainSettings.effective(p,a,defaults,true);
  for(String key:JSONObject.getNames(patch))if(!key.startsWith("webPreference"))check(effective.get(key).equals(patch.get(key)),"saved field "+key);
  check(defaults.toString().equals(original),"effective read never mutates defaults");
  check(WebDomainSettings.effective(p,b,defaults,true).toString().equals(original),"another host retains global values");
  check(WebDomainSettings.effective(p,null,defaults,true).toString().equals(original),"native channel restores defaults");
  check(p.getString("web_view_resolution","").equals("1080p")&&p.getBoolean("web_view_auto_play",false),"domain save never changes global prefs");
  WebDomainSettings.save(p,a,new JSONObject().put("webViewPageScale",2),true);
  effective=WebDomainSettings.effective(p,a,defaults,false);
  check(effective.getString("webSourceEngine").equals("system")&&effective.getString("webViewUserAgent").equals("ipad"),"standard APK retains saved Lite but uses system");
  check(effective.getDouble("webViewPageScale")==2&&effective.getString("webViewResolution").equals("4k"),"partial save preserves other fields");
  int writes=p.writes;
  for(JSONObject invalid:new JSONObject[]{new JSONObject().put("webSourceEngine","bad"),new JSONObject().put("webViewUserAgent","bad"),new JSONObject().put("webViewUserAgent","auto"),
    new JSONObject().put("webViewPageScale",0.4),new JSONObject().put("webViewAutoPlay","false"),new JSONObject().put("decodeMode","software"),
    new JSONObject().put("webViewResolution","2k").put("webViewBrowserVersion","bad")}) {
   try{WebDomainSettings.save(p,a,invalid,true);throw new AssertionError("Accepted invalid patch");}catch(JSONException expected){}
  }
  try{WebDomainSettings.save(p,a,new JSONObject().put("webSourceEngine","lite"),false);throw new AssertionError();}catch(JSONException expected){}
  check(p.writes==writes,"invalid patch is atomic and does not write");
  check(WebDomainSettings.effective(p,a,defaults,true).getString("webViewResolution").equals("4k"),"failed patch preserves old profile");
  for(int i=0;i<100;i++)WebDomainSettings.effective(p,a,defaults,true);
  check(p.writes==writes,"channel navigation reads never write");
  p.data.put("web_domain_settings_other.test","{\"webViewUserAgent\":\"auto\",\"webViewResolution\":\"2k\"}");
  JSONObject migrated=WebDomainSettings.effective(p,b,defaults,true);
  check(migrated.getString("webViewUserAgent").equals("windows")&&migrated.getString("webViewResolution").equals("2k"),"legacy domain migration preserves its other settings");
  check(new JSONObject(p.getString("web_domain_settings_other.test","")).getString("webViewUserAgent").equals("windows"),"domain migration persists");
  System.out.println("PASS all domain web options, merge, global isolation, host boundaries, native fallback, package fallback, atomic validation and read-only navigation");
 }
}'''}
jdk=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
with tempfile.TemporaryDirectory() as folder:
    out=Path(folder)
    for name,body in files.items():
        p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,'utf8')
    subprocess.run([str(jdk/'javac.exe'),'--release','8','-encoding','UTF-8','-cp',classpath,'-d',folder,*map(str,out.rglob('*.java'))],check=True)
    subprocess.run([str(jdk/'java.exe'),'-cp',folder+os.pathsep+classpath,'xiao.bu.tv.Check'],check=True)
