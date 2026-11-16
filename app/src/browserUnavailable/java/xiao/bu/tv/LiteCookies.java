package xiao.bu.tv;
import java.util.*;
import okhttp3.*;
import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;

/** Bounded browser profile. Only persistent cookies survive process restarts. */
final class LiteCookies implements CookieJar {
  static final LiteCookies PROFILE=new LiteCookies();
  private final List<Cookie> cookies=new ArrayList<>();
  private final Set<String> inferred=new HashSet<>();
  private final Map<String,Cookie> deleted=new LinkedHashMap<>();
  private SharedPreferences store;
  private Map<String,String> baseline;private final Map<String,Cookie> staged=new LinkedHashMap<>();private LiteCookies promotedTo;
  LiteCookies() {}
  static LiteCookies snapshot(){LiteCookies copy=new LiteCookies();synchronized(PROFILE){PROFILE.expire();copy.cookies.addAll(PROFILE.cookies);copy.inferred.addAll(PROFILE.inferred);copy.deleted.putAll(PROFILE.deleted);copy.baseline=new HashMap<>();for(Cookie c:copy.cookies)copy.baseline.put(id(c),c.toString());}return copy;}
  LiteCookies(SharedPreferences store){this.store=store;restore();}
  synchronized void initialize(Context context){if(store==null){store=context.getApplicationContext().getSharedPreferences("lite-cookie-profile",Context.MODE_PRIVATE);restore();}}
  private void restore(){
    try{
      String saved=store.getString("cookies","");if(saved.length()>2*1024*1024)return;
      JSONArray list=new JSONArray(saved);
      for(int i=0;i<Math.min(512,list.length());i++){
        JSONArray item=list.optJSONArray(i);if(item==null||item.length()!=2||item.optString(1).length()>4096)continue;
        HttpUrl origin=HttpUrl.parse(item.optString(0));Cookie cookie=origin==null?null:Cookie.parse(origin,item.optString(1));
        if(cookie==null||!cookie.persistent()||cookie.expiresAt()<=System.currentTimeMillis())continue;
        int count=0;for(Cookie old:cookies)if(old.domain().equals(cookie.domain()))count++;
        if(count<64)cookies.add(cookie);
      }
      JSONArray gone=new JSONArray(store.getString("deleted","[]"));for(int i=0;i<Math.min(128,gone.length());i++){JSONArray row=gone.optJSONArray(i);if(row==null)continue;HttpUrl url=HttpUrl.parse(row.optString(0));Cookie cookie=url==null?null:Cookie.parse(url,row.optString(1));if(cookie!=null&&cookie.expiresAt()<=System.currentTimeMillis()&&cookie.toString().length()<=4096)deleted.put(id(cookie),cookie);}
    }catch(Exception ignored){/* A damaged profile cannot stop a page loading. */}
  }
  private void persist(){
    if(store==null)return;
    List<JSONArray> entries=new ArrayList<>();int bytes=0;
    for(int i=cookies.size()-1;i>=0;i--){Cookie cookie=cookies.get(i);if(!cookie.persistent())continue;
      HttpUrl origin=new HttpUrl.Builder().scheme("https").host(cookie.domain()).build();
      JSONArray item=new JSONArray().put(origin.toString()).put(cookie.toString());int size=item.toString().length()+1;
      if(bytes+size>1024*1024)continue;bytes+=size;entries.add(item);
    }
    Collections.reverse(entries);JSONArray list=new JSONArray();for(JSONArray item:entries)list.put(item);
    String value=list.toString(),previous="";try{previous=store.getString("cookies","");}catch(ClassCastException ignored){}
    JSONArray gone=new JSONArray();for(Cookie c:deleted.values())gone.put(new JSONArray().put(new HttpUrl.Builder().scheme("https").host(c.domain()).build().toString()).put(c.toString()));
    String removed=gone.toString();if(!value.equals(previous)||!removed.equals(store.getString("deleted","[]")))store.edit().putString("cookies",value).putString("deleted",removed).apply();
  }
  private void expire(){long now=System.currentTimeMillis();for(Iterator<Cookie> i=cookies.iterator();i.hasNext();){Cookie c=i.next();if(c.expiresAt()<now){i.remove();inferred.remove(id(c));}}}
  public synchronized void saveFromResponse(HttpUrl url,List<Cookie> values){save(url,values,false);}
  private synchronized void save(HttpUrl url,List<Cookie> values,boolean observation){
    if(promotedTo!=null){promotedTo.saveFromResponse(url,values);return;}
    if(baseline!=null)for(Cookie c:values)if(c.toString().length()<=4096&&staged.size()<512)staged.put(id(c),c);
    expire();for(Cookie value:values){if(value.toString().length()>4096)continue;int domain=0;
      if(!observation){
        for(Iterator<Cookie> i=cookies.iterator();i.hasNext();){Cookie old=i.next();if(inferred.contains(id(old))&&old.name().equals(value.name())&&old.domain().equals(value.domain())&&pathCovers(value.path(),old.path())){i.remove();inferred.remove(id(old));}}
        inferred.remove(id(value));
      }
      if(value.expiresAt()<=System.currentTimeMillis()){deleted.put(id(value),copy(value).value("").expiresAt(0).build());if(deleted.size()>128)deleted.remove(deleted.keySet().iterator().next());}
      else deleted.remove(id(value));
      for(Iterator<Cookie> i=cookies.iterator();i.hasNext();){Cookie old=i.next();if(old.name().equals(value.name())&&old.domain().equals(value.domain())&&old.path().equals(value.path()))i.remove();else if(old.domain().equals(value.domain()))domain++;}
      if(value.expiresAt()>System.currentTimeMillis()){
        if(domain>=64)for(Iterator<Cookie> i=cookies.iterator();i.hasNext();)if(i.next().domain().equals(value.domain())){i.remove();break;}
        if(cookies.size()>=512)cookies.remove(0);cookies.add(value);
      }
    }
    persist();
  }
  synchronized List<Cookie> forHost(HttpUrl page){expire();List<Cookie> result=new ArrayList<>();for(Cookie cookie:cookies){String host=page.host(),domain=cookie.domain();if(host.equals(domain)||!cookie.hostOnly()&&host.endsWith("."+domain))result.add(cookie);}return result;}
  synchronized List<Cookie> deletions(HttpUrl page){List<Cookie> result=new ArrayList<>();for(Cookie c:deleted.values())if(page.host().equals(c.domain())||!c.hostOnly()&&page.host().endsWith("."+c.domain()))result.add(c);return result;}
  private static boolean pathCovers(String path,String actual){return actual.equals(path)||actual.startsWith(path)&&(path.endsWith("/")||actual.length()>path.length()&&actual.charAt(path.length())=='/');}
  synchronized void observe(HttpUrl page,String header,String visible){
    Set<String> newlyInferred=new HashSet<>();
    Map<String,String> actual=pairs(header),script=pairs(visible);List<Cookie> changes=new ArrayList<>();Set<String> known=new HashSet<>();
    List<Cookie> matching=loadForRequest(page);Map<String,Integer> counts=new HashMap<>();for(Cookie c:matching)counts.put(c.name(),counts.containsKey(c.name())?counts.get(c.name())+1:1);
    for(Cookie old:matching){
      known.add(old.name());String value=actual.get(old.name());if(value!=null&&counts.get(old.name())>1)continue;
      if(value==null)changes.add(copy(old).expiresAt(0).build());
      else if(!value.equals(old.value()))changes.add(copy(old).value(value).build());
    }
    for(Map.Entry<String,String> entry:actual.entrySet())if(!known.contains(entry.getKey())){
      Cookie parsed=Cookie.parse(page,entry.getKey()+"="+entry.getValue());if(parsed==null)continue;
      // WebView exposes name/value only. Keep unknown cookies scoped to the
      // observed host/default path and session; never invent a broad domain/expiry.
      Cookie.Builder builder=copy(parsed);if(page.isHttps())builder.secure();
      if(!entry.getValue().equals(script.get(entry.getKey())))builder.httpOnly();Cookie added=builder.build();changes.add(added);newlyInferred.add(id(added));
    }
    save(page,changes,true);inferred.addAll(newlyInferred);if(inferred.size()>512){Set<String> live=new HashSet<>();for(Cookie c:cookies)live.add(id(c));inferred.retainAll(live);}
  }
  private static Cookie.Builder copy(Cookie c){Cookie.Builder b=new Cookie.Builder().name(c.name()).value(c.value()).path(c.path());if(c.hostOnly())b.hostOnlyDomain(c.domain());else b.domain(c.domain());if(c.persistent())b.expiresAt(c.expiresAt());if(c.secure())b.secure();if(c.httpOnly())b.httpOnly();return b;}
  private static Map<String,String> pairs(String raw){Map<String,String> values=new LinkedHashMap<>();if(raw!=null&&raw.length()<=131072)for(String pair:raw.split(";")){int at=pair.indexOf('=');if(at>0&&!values.containsKey(pair.substring(0,at).trim()))values.put(pair.substring(0,at).trim(),pair.substring(at+1).trim());}return values;}
  synchronized void promote(){
    if(baseline==null||promotedTo!=null)return;
    synchronized(PROFILE){for(Cookie c:staged.values()){
      String current=null;for(Cookie old:PROFILE.cookies)if(id(old).equals(id(c))){current=old.toString();break;}
      if((current==null?baseline.get(id(c))==null:current.equals(baseline.get(id(c)))))PROFILE.saveFromResponse(new HttpUrl.Builder().scheme("https").host(c.domain()).build(),Collections.singletonList(c));
    }}promotedTo=PROFILE;staged.clear();baseline=null;
  }
  private static String id(Cookie c){return c.name()+"\n"+c.domain()+"\n"+c.path();}
  public synchronized List<Cookie> loadForRequest(HttpUrl url){if(promotedTo!=null)return promotedTo.loadForRequest(url);expire();List<Cookie> result=new ArrayList<>();for(Cookie value:cookies)if(value.matches(url))result.add(value);Collections.sort(result,(a,b)->b.path().length()-a.path().length());return result;}
}
