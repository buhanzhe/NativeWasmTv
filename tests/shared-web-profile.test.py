"""Real profile owner, CookieJar and preparse merge; only Android IPC/preferences replaced."""
from pathlib import Path
import os,subprocess,tempfile
root=Path(__file__).resolve().parents[1]
cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
def jar(group,name):return next(p for p in (cache/group/name).rglob('*.jar') if not p.name.endswith(('-sources.jar','-javadoc.jar')))
cp=os.pathsep.join(map(str,[jar('org.json','json'),jar('com.squareup.okhttp3','okhttp'),jar('com.squareup.okio','okio')]))
files={
'android/content/SharedPreferences.java':r'''package android.content;import java.util.*;public interface SharedPreferences{String getString(String k,String d);boolean getBoolean(String k,boolean d);long getLong(String k,long d);boolean contains(String k);Map<String,?> getAll();Editor edit();interface Editor{Editor putString(String k,String v);Editor putBoolean(String k,boolean v);Editor putLong(String k,long v);Editor remove(String k);void apply();}}''',
'android/content/Context.java':r'''package android.content;public abstract class Context{public static final int MODE_PRIVATE=0;public Context getApplicationContext(){return this;}public abstract SharedPreferences getSharedPreferences(String n,int m);public String getPackageName(){return "fixture";}public ContentResolver getContentResolver(){return new ContentResolver();}}''',
'android/content/ContentResolver.java':r'''package android.content;import android.net.Uri;import android.os.Bundle;public class ContentResolver{public Bundle call(Uri u,String m,String a,Bundle b){return null;}}''',
'android/net/Uri.java':'package android.net;public class Uri{public static Uri parse(String s){return new Uri();}}',
'android/os/Bundle.java':r'''package android.os;import java.util.*;public class Bundle extends HashMap<String,Object>{public void putString(String k,String v){put(k,v);}public String getString(String k){return (String)get(k);}public String getString(String k,String d){return containsKey(k)?getString(k):d;}public void putBoolean(String k,boolean v){put(k,v);}public boolean getBoolean(String k){return Boolean.TRUE.equals(get(k));}public void putByteArray(String k,byte[] v){put(k,v);}public byte[] getByteArray(String k){return (byte[])get(k);}public ArrayList<String> getStringArrayList(String k){return (ArrayList<String>)get(k);}}''',
'android/util/Base64.java':'package android.util;public class Base64{public static final int NO_WRAP=2;public static String encodeToString(byte[] b,int flags){return java.util.Base64.getEncoder().encodeToString(b);}}',
'xiao/bu/tv/AppLog.java':'package xiao.bu.tv;class AppLog{static void d(String a,String b){}}',
'xiao/bu/tv/Check.java':r'''package xiao.bu.tv;
import android.content.*;import android.os.Bundle;import java.util.*;import okhttp3.*;import okhttp3.Cookie;import org.json.*;
public class Check {
 static class Prefs implements SharedPreferences,SharedPreferences.Editor{Map<String,Object> values=new HashMap<>();public String getString(String k,String d){return (String)values.getOrDefault(k,d);}public boolean getBoolean(String k,boolean d){return (Boolean)values.getOrDefault(k,d);}public long getLong(String k,long d){return (Long)values.getOrDefault(k,d);}public boolean contains(String k){return values.containsKey(k);}public Map<String,?> getAll(){return new HashMap<>(values);}public Editor edit(){return this;}public Editor putString(String k,String v){values.put(k,v);return this;}public Editor putBoolean(String k,boolean v){values.put(k,v);return this;}public Editor putLong(String k,long v){values.put(k,v);return this;}public Editor remove(String k){values.remove(k);return this;}public void apply(){}}
 static class Ctx extends Context{Map<String,Prefs> files=new HashMap<>();public SharedPreferences getSharedPreferences(String n,int m){return files.computeIfAbsent(n,k->new Prefs());}}
 static void ok(boolean b,String why){if(!b)throw new AssertionError(why);}
 static Cookie cookie(HttpUrl u,String h){return Cookie.parse(u,h);}static void put(LiteCookies jar,HttpUrl u,String h){jar.saveFromResponse(u,Collections.singletonList(cookie(u,h)));}
 static Cookie named(LiteCookies jar,HttpUrl u,String n){for(Cookie c:jar.loadForRequest(u))if(c.name().equals(n))return c;return null;}
 static Bundle args(String url){Bundle b=new Bundle();b.putString("url",url);return b;}
 public static void main(String[] unused)throws Exception{
  Ctx context=new Ctx();HttpUrl page=HttpUrl.parse("https://site.example/account/watch");LiteCookies jar=new LiteCookies();
  put(jar,page,"sid=old; Domain=site.example; Path=/; HttpOnly; Secure; Max-Age=3600");put(jar,page,"remove=old; Path=/");put(jar,page,"path=secret; Path=/private/");
  long expiry=named(jar,page,"sid").expiresAt();jar.observe(page,"sid=new; js=visible; unknown=hidden","js=visible");Cookie updated=named(jar,page,"sid");
  ok(updated.value().equals("new")&&updated.httpOnly()&&updated.secure()&&updated.persistent()&&updated.expiresAt()==expiry&&!updated.hostOnly()&&updated.path().equals("/"),"Known cookie attributes changed");
  ok(named(jar,page,"remove")==null&&named(jar,page,"unknown").httpOnly()&&!named(jar,page,"js").httpOnly(),"Deletion or HttpOnly observation failed");
  ok(named(jar,HttpUrl.parse("https://other.example/"),"sid")==null&&named(jar,HttpUrl.parse("http://site.example/"),"sid")==null,"Domain/secure leaked");ok(named(jar,HttpUrl.parse("https://site.example/private/read"),"path")!=null,"Unobserved cookie path was deleted");
  put(jar,page,"unknown=; Path=/; Max-Age=0; HttpOnly");ok(named(jar,page,"unknown")==null&&jar.deletions(page).size()>0,"Authoritative logout did not remove an inferred cookie path or retain its deletion");
  put(jar,page,"same=root; Path=/");put(jar,page,"same=deep; Path=/account/");jar.observe(page,"same=deep; same=root","same=deep; same=root");List<Cookie> ordered=jar.loadForRequest(page);ok(ordered.get(0).value().equals("deep"),"Same-name path cookies were reordered or conflated");
  Prefs persisted=new Prefs();LiteCookies persistent=new LiteCookies(persisted);put(persistent,page,"gone=; Path=/; Max-Age=0; HttpOnly");ok(new LiteCookies(persisted).deletions(page).size()==1,"Logout deletion was lost after process restart");
  LiteCookies.PROFILE.initialize(context);put(LiteCookies.PROFILE,page,"a=initial; Path=/");put(LiteCookies.PROFILE,page,"conflict=initial; Path=/");
  LitePreparseProfile pending=new LitePreparseProfile();put(pending.cookies,page,"a=prepared; Path=/");put(pending.cookies,page,"conflict=prepared; Path=/");put(LiteCookies.PROFILE,page,"conflict=foreground; Path=/");
  String origin=SharedWebProfile.origin(page.toString());LiteWebStorage.perform(context,origin,new JSONObject().put("action","set").put("key","auth").put("value","active"));
  pending.storage(context,origin,new JSONObject().put("action","set").put("key","auth").put("value","prepared"));pending.storage(context,origin,new JSONObject().put("action","set").put("key","added").put("value","prepared"));
  ok(named(LiteCookies.PROFILE,page,"a").value().equals("initial"),"Background preparse changed foreground cookies");ok(context.getSharedPreferences("lite-storage",0).getString(origin,"").contains("active"),"Background preparse changed foreground storage");
  LiteWebStorage.perform(context,origin,new JSONObject().put("action","set").put("key","auth").put("value","new active"));pending.promote(context);
  ok(named(LiteCookies.PROFILE,page,"a").value().equals("prepared")&&named(LiteCookies.PROFILE,page,"conflict").value().equals("foreground"),"Promotion clobbered newer foreground cookies");JSONObject merged=new JSONObject(context.getSharedPreferences("lite-storage",0).getString(origin,"{}"));ok(merged.getString("auth").equals("new active")&&merged.getString("added").equals("prepared"),"Promotion clobbered newer storage");
  pending.storage(context,origin,new JSONObject().put("action","remove").put("key","auth"));ok(!new JSONObject(context.getSharedPreferences("lite-storage",0).getString(origin,"{}")).has("auth"),"Promoted storage remained isolated");
  put(pending.cookies,page,"a=live; Path=/");ok(named(LiteCookies.PROFILE,page,"a").value().equals("live"),"Promoted cookie jar remained isolated");
  Bundle ua=args(page.toString());ua.putString("config","windows:native");ua.putString("ua","first-browser");ua.putBoolean("pin",true);ok(SharedWebProfile.handle(context,"ua",ua).getString("ua").equals("first-browser"),"Initial UA failed");ua.putString("ua","other-browser");ok(SharedWebProfile.handle(context,"ua",ua).getString("ua").equals("first-browser"),"UA changed with the renderer");ua.putString("config","windows:128");ok(SharedWebProfile.handle(context,"ua",ua).getString("ua").equals("other-browser"),"Explicit UA ignored");
  Bundle open=SharedWebProfile.handle(context,"open",args(page.toString()));ok(open.getBoolean("ok"),"Owner open failed");JSONObject snapshot=new JSONObject(SharedWebProfile.unpack(open,"state"));ok(snapshot.getJSONArray("cookies").length()>0&&snapshot.getBoolean("storageKnown"),"Owner snapshot failed");
  Bundle signature=SharedWebProfile.handle(context,"signature",args(page.toString()));LiteWebStorage.perform(context,origin,new JSONObject().put("action","set").put("key","auth").put("value","changed"));ok(!signature.getString("signature").equals(SharedWebProfile.handle(context,"signature",args(page.toString())).getString("signature")),"Changed profile retained preparse identity");
  SharedWebProfile.handle(context,"lite",args(page.toString()));Bundle capture=args(page.toString());capture.putString("lease",open.getString("lease"));capture.putString("cookies","stale=bad");SharedWebProfile.pack(capture,"state",new JSONObject().put("storage",new JSONObject().put("bad","bad")).toString());ok(!SharedWebProfile.handle(context,"capture",capture).getBoolean("ok"),"Stale WebView could overwrite a new owner");
  String large=String.join("",Collections.nCopies(50000,"quote \\\" 中文 \\n"));Bundle compressed=new Bundle();SharedWebProfile.pack(compressed,"state",large);ok(large.equals(SharedWebProfile.unpack(compressed,"state")),"Compressed snapshot changed values");
  System.out.println("PASS cookie scopes/attributes/deletions, conservative unknown cookies, isolated preparse promotion/conflicts, storage deletion, UA pin/explicit change, stale owners, profile cache identity and compressed snapshots");
 }
}'''}
with tempfile.TemporaryDirectory() as tmp:
    paths=[]
    for name,content in files.items():
        p=Path(tmp)/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content,encoding='utf8');paths.append(str(p))
    sources=[root/'app/src/main/java/xiao/bu/tv/SharedWebProfile.java']+[root/'browser-core/litebrowser/src/main/java/xiao/bu/tv'/name for name in ['LiteCookies.java','LiteWebStorage.java','LitePreparseProfile.java']]
    jdk=Path(os.environ['JAVA_HOME'])/'bin'
    subprocess.run([str(jdk/'javac'),'-encoding','UTF-8','-cp',cp,'-d',tmp,*paths,*map(str,sources)],check=True)
    subprocess.run([str(jdk/'java'),'-cp',tmp+os.pathsep+cp,'xiao.bu.tv.Check'],check=True)
