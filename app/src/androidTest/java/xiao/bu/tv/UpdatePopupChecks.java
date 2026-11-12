package xiao.bu.tv;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Real UI/worker policy checks with deterministic manifests; never downloads APKs. */
final class UpdatePopupChecks {
    interface Condition { boolean ready() throws Exception; }
    static Object field(Object value,String name)throws Exception {Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    static void await(Condition condition)throws Exception {long end=SystemClock.elapsedRealtime()+8000;while(SystemClock.elapsedRealtime()<end){if(condition.ready())return;SystemClock.sleep(25);}throw new AssertionError("Update policy timed out");}
    static boolean showing(Instrumentation test,AutoUpdater updater)throws Exception {
        boolean[] visible={false};test.runOnMainSync(()->{try{AlertDialog dialog=(AlertDialog)field(updater,"promptDialog");visible[0]=dialog!=null&&dialog.isShowing();}catch(Exception e){throw new RuntimeException(e);}});return visible[0];
    }
    static String manifest(int code)throws Exception {
        JSONObject data=new JSONObject().put("versionCode",code).put("versionName","fixture-"+code).put("releaseNotes","本地更新策略测试，不下载安装包。");
        for(String[] row:new String[][]{{"apkUrl","sha256","nTv.apk"},{"apk32Url","sha25632","nTv.apk"},{"apk64Url","sha25664","nTv64.apk"},{"apkBroUrl","sha256Bro","ntv_bro.apk"},{"apkX86Url","sha256X86","nTvX86.apk"}})
            data.put(row[0],"https://github.com/buhanzhe/NativeWasmTv/releases/download/fixture-not-a-release/"+row[2]).put(row[1],"0000000000000000000000000000000000000000000000000000000000000000");
        return data.toString();
    }
    static final class Reader implements AutoUpdater.ManifestReader {
        final AtomicInteger important=new AtomicInteger(),lite=new AtomicInteger();
        int importantCode=BuildConfig.VERSION_CODE,liteCode=BuildConfig.VERSION_CODE+1;
        boolean importantOffline;CountDownLatch holdLite;
        public String read(String url)throws IOException {
            try{
                if(url.endsWith("/version-lite.json")){
                    lite.incrementAndGet();if(holdLite!=null&&!holdLite.await(8,TimeUnit.SECONDS))throw new IOException("Fixture latch timed out");return manifest(liteCode);
                }
                check(url.endsWith("/version.json"),"Unexpected release feed");important.incrementAndGet();if(importantOffline)throw new IOException("Fixture offline");return manifest(importantCode);
            }catch(IOException e){throw e;}catch(Exception e){throw new IOException(e);}
        }
    }
    static JSONObject run(Instrumentation test,Activity host)throws Exception {
        SharedPreferences prefs=host.getSharedPreferences(MainActivity.PREFERENCES,0);boolean existed=prefs.contains(AutoUpdater.POPUP_PREFERENCE),old=prefs.getBoolean(AutoUpdater.POPUP_PREFERENCE,false);
        JSONObject result=new JSONObject();AutoUpdater active=null;CountDownLatch release=null;
        try {
            test.waitForIdleSync();SystemClock.sleep(200);
            prefs.edit().putBoolean(AutoUpdater.POPUP_PREFERENCE,false).commit();Reader r=new Reader();active=new AutoUpdater(host,r);AutoUpdater u=active;
            u.checkForUpdates();await(()->r.important.get()==1&&!(Boolean)field(u,"importantChecking"));test.waitForIdleSync();
            check(r.lite.get()==0&&!showing(test,u),"Disabled optional notices contacted lite feed or showed a dialog");result.put("offStillChecksVersionJson",true);test.runOnMainSync(u::destroy);

            prefs.edit().putBoolean(AutoUpdater.POPUP_PREFERENCE,true).commit();Reader enabled=new Reader();active=new AutoUpdater(host,enabled);AutoUpdater on=active;
            on.checkForUpdates();await(()->"available".equals(on.stateJson().optString("state"))&&!(Boolean)field(on,"liteChecking"));test.waitForIdleSync();
            check(enabled.important.get()==1&&enabled.lite.get()==1&&showing(test,on),"Enabled notices did not use version-lite.json");
            prefs.edit().putBoolean(AutoUpdater.POPUP_PREFERENCE,false).commit();test.runOnMainSync(()->on.popupPreferenceChanged(false));check(!showing(test,on),"Disabling did not dismiss optional notice");result.put("onChecksLatestLiteAndCanDisable",true);test.runOnMainSync(on::destroy);

            prefs.edit().putBoolean(AutoUpdater.POPUP_PREFERENCE,true).commit();Reader late=new Reader();release=late.holdLite=new CountDownLatch(1);active=new AutoUpdater(host,late);AutoUpdater inflight=active;
            inflight.checkForUpdates();await(()->late.lite.get()==1);prefs.edit().putBoolean(AutoUpdater.POPUP_PREFERENCE,false).commit();test.runOnMainSync(()->inflight.popupPreferenceChanged(false));release.countDown();release=null;
            await(()->!(Boolean)field(inflight,"liteChecking"));test.waitForIdleSync();check(!showing(test,inflight),"Stale lite response ignored the disabled preference");result.put("lateResponseRespectsOff",true);test.runOnMainSync(inflight::destroy);

            Reader required=new Reader();required.importantCode=BuildConfig.VERSION_CODE+1;active=new AutoUpdater(host,required);AutoUpdater force=active;
            force.checkForUpdates();await(()->showing(test,force));test.runOnMainSync(()->force.popupPreferenceChanged(false));
            check(required.important.get()==1&&required.lite.get()==0&&showing(test,force),"Preference suppressed the important-version update");result.put("versionJsonThresholdSurvivesOff",true);test.runOnMainSync(force::destroy);

            prefs.edit().putBoolean(AutoUpdater.POPUP_PREFERENCE,true).commit();Reader priority=new Reader();priority.importantCode=BuildConfig.VERSION_CODE+1;active=new AutoUpdater(host,priority);AutoUpdater first=active;
            first.checkForUpdates();await(()->showing(test,first));check(priority.lite.get()==0,"Optional feed raced the important threshold");result.put("importantFeedHasPriority",true);test.runOnMainSync(first::destroy);

            prefs.edit().putBoolean(AutoUpdater.POPUP_PREFERENCE,false).commit();Reader manual=new Reader();active=new AutoUpdater(host,manual);AutoUpdater explicit=active;
            explicit.checkLiteForUpdates();await(()->"available".equals(explicit.stateJson().optString("state"))&&!(Boolean)field(explicit,"liteChecking"));test.waitForIdleSync();
            check(manual.lite.get()==1&&manual.important.get()==0&&!showing(test,explicit),"Manual check was disabled or unexpectedly popped a dialog");result.put("manualCheckUnaffected",true);test.runOnMainSync(explicit::destroy);

            prefs.edit().putBoolean(AutoUpdater.POPUP_PREFERENCE,true).commit();Reader current=new Reader();current.liteCode=BuildConfig.VERSION_CODE-1;active=new AutoUpdater(host,current);AutoUpdater noUpdate=active;
            noUpdate.checkForUpdates();await(()->"current".equals(noUpdate.stateJson().optString("state")));test.waitForIdleSync();check(!showing(test,noUpdate),"Older lite manifest displayed an update");result.put("olderVersionDoesNotPrompt",true);test.runOnMainSync(noUpdate::destroy);

            Reader offline=new Reader();offline.importantOffline=true;active=new AutoUpdater(host,offline);AutoUpdater recovery=active;
            recovery.checkForUpdates();await(()->showing(test,recovery));check(offline.important.get()==1&&offline.lite.get()==1,"Important feed failure blocked optional checking");result.put("feedFailuresIndependent",true);test.runOnMainSync(recovery::destroy);

            Reader closing=new Reader();release=closing.holdLite=new CountDownLatch(1);active=new AutoUpdater(host,closing);AutoUpdater gone=active;
            gone.checkForUpdates();await(()->closing.lite.get()==1);test.runOnMainSync(gone::destroy);release.countDown();release=null;
            await(()->!(Boolean)field(gone,"liteChecking"));test.waitForIdleSync();check(!showing(test,gone),"Destroyed owner displayed a dialog");result.put("destroyedOwnerDoesNotPrompt",true);
            result.put("passed",true);return result;
        }finally{
            if(release!=null)release.countDown();if(active!=null){AutoUpdater last=active;test.runOnMainSync(last::destroy);}
            SharedPreferences.Editor restore=prefs.edit();if(existed)restore.putBoolean(AutoUpdater.POPUP_PREFERENCE,old);else restore.remove(AutoUpdater.POPUP_PREFERENCE);restore.commit();
            check(prefs.contains(AutoUpdater.POPUP_PREFERENCE)==existed&&prefs.getBoolean(AutoUpdater.POPUP_PREFERENCE,false)==old,"Update preference was not restored");
            result.put("preferenceRestored",true);
        }
    }
}
