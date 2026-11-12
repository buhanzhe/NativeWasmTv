package xiao.bu.tv;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** App-owned site behavior, editable through the existing UserScript manager. */
final class LitePageScripts {
    private static final String SOURCES="web_view_user_scripts_v2",ENABLED="web_view_user_script_enabled",MIGRATED="builtin_site_userscripts_v4";
    private LitePageScripts() {}
    static void installDefaults(Context context,SharedPreferences prefs){
        if(prefs.getBoolean(MIGRATED,false))return;
        try{
            String saved=prefs.getString(SOURCES,"");JSONArray entries=saved.trim().isEmpty()?new JSONArray():new JSONArray(saved);
            String legacy=prefs.getString("web_view_user_script","");
            if(entries.length()==0&&!legacy.trim().isEmpty())entries.put(new JSONObject().put("name","已有脚本").put("enabled",true).put("source",legacy));
            int total=0;for(int i=0;i<entries.length();i++){JSONObject item=entries.optJSONObject(i);if(item!=null)total+=item.optString("source","").length();}
            boolean previous=prefs.getBoolean("builtin_site_userscripts_v1",false);
            boolean previousV3=prefs.getBoolean("builtin_site_userscripts_v3",false);
            boolean previousV2=prefs.getBoolean("builtin_site_userscripts_v2",false);
            for(String[] asset:new String[][]{{"ntv-player-compat.user.js","播放器站点兼容"},{"ntv-site-selection.user.js","直播站点选台与兼容"},{"ntv-media-reactivity.user.js","播放器对象响应化兼容"},{"ntv-tv-portal.user.js","电视页面组件精简"},{"ntv-live-recovery.user.js","免费直播连接恢复"}}){
                if(previousV3&&!asset[0].equals("ntv-live-recovery.user.js")&&!asset[0].equals("ntv-tv-portal.user.js"))continue;
                if(previousV2&&!asset[0].equals("ntv-tv-portal.user.js")&&!asset[0].equals("ntv-live-recovery.user.js"))continue;
                if(previous&&!asset[0].equals("ntv-media-reactivity.user.js")&&!asset[0].equals("ntv-tv-portal.user.js")&&!asset[0].equals("ntv-live-recovery.user.js"))continue;

                String id=asset[0].replace(".user.js","");JSONObject existing=null;
                for(int i=0;i<entries.length();i++){JSONObject item=entries.optJSONObject(i);if(item!=null&&id.equals(item.optString("id")))existing=item;}
                // Upgrade only the untouched bundled portal; preserve edits and deletions.
                boolean portal=asset[0].equals("ntv-tv-portal.user.js");
                boolean upgrade=existing!=null&&portal&&"dcbf81a798d5c751c0891f07cf33b71a57b986ccd970e93555c8664af25cd1eb".equals(digest(existing.optString("source","")));
                if(existing!=null&&!upgrade)continue;
                if(existing==null&&(entries.length()>=32||(previousV3&&portal)))continue;
                String source;
                try(InputStream in=context.getAssets().open("userscripts/"+asset[0]);ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);source=out.toString("UTF-8");
                }
                int oldLength=upgrade?existing.optString("source","").length():0;
                if(total-oldLength+source.length()>524288)continue;
                if(upgrade){existing.put("source",source).put("version","1.0.2");total+=source.length()-oldLength;continue;}
                entries.put(new JSONObject().put("id",id).put("name",asset[1]).put("enabled",true).put("version",asset[0].equals("ntv-live-recovery.user.js")?"1.0.4":portal?"1.0.2":"1.0.0").put("source",source));total+=source.length();
            }
            SharedPreferences.Editor edit=prefs.edit().putString(SOURCES,entries.toString()).putBoolean("builtin_site_userscripts_v1",true).putBoolean("builtin_site_userscripts_v2",true).putBoolean("builtin_site_userscripts_v3",true).putBoolean(MIGRATED,true);
            // Preserve an explicit master switch; fresh/default installations
            // retain the compatibility that was previously built into the core.
            if(!prefs.contains(ENABLED))edit.putBoolean(ENABLED,true);
            edit.apply();
        }catch(Exception failure){AppLog.w("LitePageScripts","Unable to install site userscripts",failure);}
    }
    private static String digest(String source)throws Exception{
        byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(source.getBytes("UTF-8"));
        StringBuilder key=new StringBuilder();for(byte b:hash)key.append(String.format(java.util.Locale.US,"%02x",b&255));return key.toString();
    }
    static List<String> forDocument(Context context,String page){
        SharedPreferences prefs=context.getSharedPreferences(MainActivity.PREFERENCES,0);
        return prefs.getBoolean(ENABLED,false)?WebPageScriptManager.compiledUserScripts(prefs.getString(SOURCES,"[]")):Collections.emptyList();
    }
    static String signature(Context context){
        SharedPreferences prefs=context.getSharedPreferences(MainActivity.PREFERENCES,0);
        if(!prefs.getBoolean(ENABLED,false))return "disabled";
        try{
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(prefs.getString(SOURCES,"[]").getBytes("UTF-8"));
            StringBuilder key=new StringBuilder();for(byte b:digest)key.append(String.format(java.util.Locale.US,"%02x",b&255));return key.toString();
        }catch(Exception ignored){return prefs.getString(SOURCES,"[]");}
    }
}
