"""Real migration preserves owner scripts, explicit switches and edits/deletions."""
from pathlib import Path
import os, subprocess, tempfile, base64, zlib
root=Path(__file__).resolve().parents[1]
jar=next(p for p in (Path.home()/'.gradle/caches/modules-2/files-2.1/org.json/json').rglob('*.jar') if not p.name.endswith(('-sources.jar','-javadoc.jar')))
java=r'''package xiao.bu.tv;
import android.content.*;import org.json.*;import java.nio.file.*;
class Check {
 static void ok(boolean b){if(!b)throw new AssertionError();}
 static JSONArray entries(SharedPreferences p)throws Exception{return new JSONArray(p.getString("web_view_user_scripts_v2","[]"));}
 public static void main(String[] args)throws Exception{
  Context c=new Context(args[0]);SharedPreferences p=c.prefs;
  LitePageScripts.installDefaults(c,p);ok(entries(p).length()==5);ok(p.getBoolean("web_view_user_script_enabled",false));
  for(int i=0;i<5;i++){JSONObject script=entries(p).getJSONObject(i);ok(script.getString("source").contains("@run-at document-start"));ok(script.getBoolean("enabled"));ok(!script.getString("id").isEmpty());}
  String first=p.getString("web_view_user_scripts_v2","");LitePageScripts.installDefaults(c,p);ok(first.equals(p.getString("web_view_user_scripts_v2","")));
  String signature=LitePageScripts.signature(c);p.edit().putString("web_view_user_scripts_v2","[]").apply();
  LitePageScripts.installDefaults(c,p);ok(entries(p).length()==0);ok(!signature.equals(LitePageScripts.signature(c)));
  p.edit().putBoolean("web_view_user_script_enabled",false).apply();ok(LitePageScripts.signature(c).equals("disabled"));ok(LitePageScripts.forDocument(c,"https://example.org").isEmpty());
  c=new Context(args[0]);p=c.prefs;p.edit().putBoolean("web_view_user_script_enabled",false).putString("web_view_user_script","window.owner=1").apply();
  LitePageScripts.installDefaults(c,p);ok(!p.getBoolean("web_view_user_script_enabled",true));ok(entries(p).getJSONObject(0).getString("source").equals("window.owner=1"));ok(entries(p).length()==6);
  c=new Context(args[0]);p=c.prefs;p.edit().putBoolean("builtin_site_userscripts_v1",true).putBoolean("web_view_user_script_enabled",false).putString("web_view_user_scripts_v2","[]").apply();
  LitePageScripts.installDefaults(c,p);ok(entries(p).length()==3&&entries(p).getJSONObject(0).getString("id").equals("ntv-media-reactivity"));ok(!p.getBoolean("web_view_user_script_enabled",true));
  String upgraded=p.getString("web_view_user_scripts_v2","");LitePageScripts.installDefaults(c,p);ok(upgraded.equals(p.getString("web_view_user_scripts_v2","")));
  c=new Context(args[0]);p=c.prefs;p.edit().putBoolean("builtin_site_userscripts_v2",true).putBoolean("web_view_user_script_enabled",false).putString("web_view_user_scripts_v2","[]").apply();
  LitePageScripts.installDefaults(c,p);ok(entries(p).length()==2&&entries(p).getJSONObject(0).getString("id").equals("ntv-tv-portal"));ok(!p.getBoolean("web_view_user_script_enabled",true));
  for(boolean edited:new boolean[]{false,true}){
   c=new Context(args[0]);p=c.prefs;String old=new String(Files.readAllBytes(Paths.get(args[1])),"UTF-8");if(edited)old+="\n// owner edit";
   JSONArray prior=new JSONArray().put(new JSONObject().put("id","ntv-tv-portal").put("source",old).put("enabled",false));
   p.edit().putBoolean("builtin_site_userscripts_v3",true).putBoolean("web_view_user_script_enabled",false).putString("web_view_user_scripts_v2",prior.toString()).apply();
   LitePageScripts.installDefaults(c,p);ok(entries(p).length()==2);JSONObject portal=entries(p).getJSONObject(0);ok(!portal.getBoolean("enabled"));ok(!p.getBoolean("web_view_user_script_enabled",true));ok(edited?portal.getString("source").equals(old):portal.getString("source").contains("@version 1.0.2"));
  }
  c=new Context(args[0]);p=c.prefs;p.edit().putBoolean("builtin_site_userscripts_v3",true).putString("web_view_user_scripts_v2","[]").apply();LitePageScripts.installDefaults(c,p);ok(entries(p).length()==1&&entries(p).getJSONObject(0).getString("id").equals("ntv-live-recovery"));
  c=new Context(args[0]);p=c.prefs;JSONArray full=new JSONArray();for(int i=0;i<32;i++)full.put(new JSONObject().put("source","owner"+i));
  p.edit().putString("web_view_user_scripts_v2",full.toString()).apply();LitePageScripts.installDefaults(c,p);ok(entries(p).length()==32);ok(entries(p).getJSONObject(31).getString("source").equals("owner31"));
  c=new Context(args[0]);p=c.prefs;p.edit().putString("web_view_user_scripts_v2","broken owner data").apply();LitePageScripts.installDefaults(c,p);
  ok(p.getString("web_view_user_scripts_v2","").equals("broken owner data"));ok(!p.contains("builtin_site_userscripts_v1"));
  System.out.println("PASS default script install, once-only migration, explicit master-off, legacy owner script, edited/deleted scripts, full quota and malformed-data preservation");
 }
}'''
with tempfile.TemporaryDirectory() as folder:
    p=Path(folder)
    sources={
        'android/content/SharedPreferences.java':'''package android.content;import java.util.*;public class SharedPreferences {
         final Map<String,Object> data=new HashMap<>();public boolean getBoolean(String k,boolean d){Object v=data.get(k);return v==null?d:(Boolean)v;}public String getString(String k,String d){Object v=data.get(k);return v==null?d:(String)v;}public boolean contains(String k){return data.containsKey(k);}public Editor edit(){return new Editor();}
         public class Editor {public Editor putString(String k,String v){data.put(k,v);return this;}public Editor putBoolean(String k,boolean v){data.put(k,v);return this;}public void apply(){}}}''',
        'android/content/Context.java':'''package android.content;public class Context {
         public final SharedPreferences prefs=new SharedPreferences();private final String root;public Context(String root){this.root=root;}public Assets getAssets(){return new Assets();}public SharedPreferences getSharedPreferences(String n,int m){return prefs;}
         public class Assets {public java.io.InputStream open(String path)throws java.io.IOException{return new java.io.FileInputStream(new java.io.File(root,path));}}}''',
        'xiao/bu/tv/MainActivity.java':'package xiao.bu.tv;class MainActivity {static final String PREFERENCES="test";}',
        'xiao/bu/tv/AppLog.java':'package xiao.bu.tv;class AppLog {static void w(String t,String m,Exception e){}}',
        'xiao/bu/tv/WebPageScriptManager.java':'package xiao.bu.tv;class WebPageScriptManager {static java.util.List<String> compiledUserScripts(String s){return java.util.Collections.singletonList(s);}}',
        'xiao/bu/tv/Check.java':java,
    }
    for name, text in sources.items():
        f=p/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(text,encoding='utf8')
    prior=p/'portal-v1.user.js';prior.write_bytes(zlib.decompress(base64.b64decode('eJyFVUuPHDUQvo80/8Ejoul2duLeIE4zMgsEJAQbNko2gLS7RJ7u6m5nPXZju7czTI/EAfE45cKVQ7jAiQNSuCTwa4BNTvkLyO7HzEAUTu2ux/dVlavKUYQovWtA34k1Lyylw0EUobckWwC6/P7xs5++fv7o8fMffrx88tWfT367/PWPy1++fPH022e/P3n+zcO/f37019OHL55+1zhdgDZcSXSd7JP9RrRgNs7R1WkUVVVFvljqOYnVgsQyutoY6FJeYxYlKi4XIO01Y5m2jSrTTFoklQT/T2m0G2eYljK2XMkww6vhICgNIGM1j20w8x4fAhTI5oCU5hmXTKDjj1GsFoWSIC1iMkHcGmRAQGwhQXHOpASBEmYZQUdSLBG7UDzxYAtVSstlhkqpQTBnXyhtmUAVTzKwBnHpyXJlbGBQDiwRYAwqBFvOWXyOFioBMhxcMI1iJVOeUbssQKXo3j3nQykNuoyCg0YWfnDn6CPikpIZT5fhShXToGAZ3PAAwRrjqSyFmA0HPA1HDWxdtwfSxVDXo4x8evPwfWuL2/B5CcZiDbbUctbEo1VpgQoVM8dOcmZy4q8ujD577TQKT06ra2d7ODyYnh7UV3CEZ47Oe+0CxSzOgUqo0CfAzm+yIsSz4aBLqy/1LZZB+CDXE80qvLJ6uRoOEE+diGhgyfKOZRZGlL5R105mLLOlGVH6+v5+S4g0q2bDAXKspRae8+7twxbCFEoauHv7cLJJSkPq4y61IE1HjOgm50ZS105bMJu7CSCmENyGQRRgUqgixCNKfdIn18/2AnLfKBm8JJxCwwVXpaG+GiQD66Ly3J1qPO5ORLOKUuoK0SJtNGBKYTtU15VdxzQ+QdMYwYHvkoJpA6HDmbaxuDtyXnXtO/ocllvxt8KCGVMpndR1C+2lsZIWpB1tOF6SptUAdIt62xNPfPuAofvOvG+Ac4AilCoBvHLRuVPP7H4co5rfh9gGde0E5IIbPhdAKU2ZMF27obbrkUtyy5+481ZpxuPoNAoPpjzVbAHHF/hKRCwYG/bGeDze9ndXv+PfSwmXCTw4SsMg6tCiAL9J9/GqzXVvb9ZFpxKYrdvwGi6eONgK5m4oQd9Q0jIuQXd59vpUKbutx+Px21qzJeHGfxu4OOciwV01jnzJCDOGZzJcrSfOZrLyRtOTs7UbQh/L6H+h+sL6cXYqDZJuDMmCFaG7RkxSLizo8B2lBDCJu+Q7JyJAZjY/eGVwnfG6X2TI1c2RG8EX1DeM67Rm5ThZXbf1HlF6/SV96dZ4Bgn9D69r0Mmq7dDpv1arQ8ZrPGmG7lWDtvFpmfC0PbgImpk3zcxPVppVbhxb2GnzWfe12oz4Ovb7lmdSacCrrbTW6+HgJOh22jE8sMGk/w3OSKr0eyzON8/hOSzxyq8MMP61VLorRgb2qJK3tCpA2+W7vT7cfR1IoZVVrgYTB+ZLvwGr660fN/Epz0rN5mIzy1v6DPwe6Z+2zYPhbysDa0HTXYfZ7i/tU+sLs/OS2JybSQNEYiaEF2A8W8/atBNIuYQu7VcmO9lQu6nxo7PGYSbUnIljhzsbDv4BM+NN5Q==')))
    jdk=Path(os.environ['JAVA_HOME'])/'bin'
    subprocess.run([str(jdk/'javac'),'--release','8','-encoding','UTF-8','-cp',str(jar),'-d',folder,str(root/'app/src/main/java/xiao/bu/tv/LitePageScripts.java'),*map(str,p.rglob('*.java'))],check=True)
    subprocess.run([str(jdk/'java'),'-cp',folder+os.pathsep+str(jar),'xiao.bu.tv.Check',str(root/'app/src/main/assets'),str(prior)],check=True)
