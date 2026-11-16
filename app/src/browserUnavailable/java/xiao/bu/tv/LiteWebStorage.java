package xiao.bu.tv;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
import org.json.*;

/** Best-effort persistent channel cache, bounded across every site, not just
 * each origin. Limits count UTF-16 code units (at most 4MiB of string payload). */
final class LiteWebStorage {
  private static final int ORIGIN_LIMIT=512*1024,TOTAL_LIMIT=2*1024*1024;
  static synchronized String perform(Context context,String origin,JSONObject request)throws Exception {
    SharedPreferences prefs=context.getSharedPreferences("lite-storage",0),access=context.getSharedPreferences("lite-storage-access",0);
    JSONObject values=new JSONObject(prefs.getString(origin,"{}"));String action=request.optString("action"),key=request.optString("key");
    long now=System.currentTimeMillis();
    if(action.equals("get")||action.equals("keys")){
      if(prefs.contains(origin)&&now-access.getLong(origin,0)>60000)access.edit().putLong(origin,now).apply();
      return action.equals("get")?(values.has(key)?JSONObject.quote(values.getString(key)):"null"):(values.names()==null?"[]":values.names().toString());
    }
    if(action.equals("clear")){prefs.edit().remove(origin).apply();access.edit().remove(origin).apply();return "null";}
    if(action.equals("set"))values.put(key,request.optString("value"));
    else if(action.equals("remove"))values.remove(key);
    else throw new IllegalArgumentException("Unknown storage operation");
    return replace(context,origin,values);
  }
  static synchronized String replace(Context context,String origin,JSONObject values)throws Exception{
    SharedPreferences prefs=context.getSharedPreferences("lite-storage",0),access=context.getSharedPreferences("lite-storage-access",0);long now=System.currentTimeMillis();
    String updated=values.toString();
    // Reject atomically: the old persisted value remains available to the page.
    if(updated.length()>ORIGIN_LIMIT)return "{\"error\":\"QuotaExceededError\"}";
    Map<String,?> existing=prefs.getAll();List<String> other=new ArrayList<>();long total=updated.length();
    for(Map.Entry<String,?> e:existing.entrySet())if(!e.getKey().equals(origin)&&e.getValue() instanceof String){other.add(e.getKey());total+=((String)e.getValue()).length();}
    Collections.sort(other,(a,b)->access.getLong(a,0)<access.getLong(b,0)?-1:access.getLong(a,0)==access.getLong(b,0)?0:1);
    SharedPreferences.Editor save=prefs.edit(),touch=access.edit();
    for(String oldest:other){if(total<=TOTAL_LIMIT)break;total-=((String)existing.get(oldest)).length();save.remove(oldest);touch.remove(oldest);}
    save.putString(origin,updated).apply();touch.putLong(origin,now).apply();return "null";
  }
}
