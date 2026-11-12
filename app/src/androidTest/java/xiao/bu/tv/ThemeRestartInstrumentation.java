package xiao.bu.tv;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.View;
import java.io.*;
import java.util.*;
import org.json.*;

/** Save in one process, restore the actual lazy panel in a fresh process. */
public final class ThemeRestartInstrumentation extends WebTaskNavigationInstrumentation {
    private Bundle args;
    private volatile Activity destroyedActivity;
    public void onCreate(Bundle args){this.args=args;start();}
    public void callActivityOnDestroy(Activity activity){super.callActivityOnDestroy(activity);destroyedActivity=activity;}
    private File backup(){return new File(getTargetContext().getFilesDir(),"theme-restart-check.json");}
    private static JSONObject encode(Map<String,?> values)throws Exception{
        JSONObject json=new JSONObject();
        for(Map.Entry<String,?> entry:values.entrySet()){
            Object value=entry.getValue();String type=value instanceof Set?"set":value instanceof Boolean?"boolean":value instanceof Integer?"int":value instanceof Long?"long":value instanceof Float?"float":"string";
            json.put(entry.getKey(),new JSONObject().put("type",type).put("value",value instanceof Set?new JSONArray((Set<?>)value):String.valueOf(value)));
        }
        return json;
    }
    private static Map<String,Object> decode(JSONObject json)throws Exception{
        Map<String,Object> values=new HashMap<>();Iterator<String> keys=json.keys();
        while(keys.hasNext()){
            String key=keys.next();JSONObject item=json.getJSONObject(key);String type=item.getString("type"),text=item.optString("value");Object value=text;
            if(type.equals("boolean"))value=Boolean.parseBoolean(text);else if(type.equals("int"))value=Integer.parseInt(text);else if(type.equals("long"))value=Long.parseLong(text);else if(type.equals("float"))value=Float.parseFloat(text);
            else if(type.equals("set")){Set<String> set=new HashSet<>();JSONArray list=item.getJSONArray("value");for(int i=0;i<list.length();i++)set.add(list.getString(i));value=set;}
            values.put(key,value);
        }
        return values;
    }
    private JSONObject readBackup()throws Exception{try(FileInputStream in=new FileInputStream(backup());ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] block=new byte[4096];int n;while((n=in.read(block))!=-1)out.write(block,0,n);return new JSONObject(new String(out.toByteArray(),"UTF-8"));}}
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private void restoreBackup(JSONObject report)throws Exception{
        JSONObject saved=readBackup();SharedPreferences prefs=getTargetContext().getSharedPreferences(MainActivity.PREFERENCES,0),theme=getTargetContext().getSharedPreferences("tv_ui",0);
        Map<String,Object> values=decode(saved.getJSONObject("prefs")),themes=decode(saved.getJSONObject("theme"));restore(prefs,values);restore(theme,themes);
        report.put("preferencesRestored",values.equals(prefs.getAll())).put("themePreferenceRestored",themes.equals(theme.getAll()));
        check(values.equals(prefs.getAll())&&themes.equals(theme.getAll()),"Test preference restoration failed");check(backup().delete(),"Backup cleanup failed");
    }
    private MainActivity launch(String phase)throws Exception{
        ActivityMonitor monitor=addMonitor(MainActivity.class.getName(),null,false);Bundle status=new Bundle();status.putString("phase",phase);sendStatus(0,status);
        MainActivity next=(MainActivity)monitor.waitForActivityWithTimeout(20000);removeMonitor(monitor);check(next!=null,"Activity was not launched");waitForIdleSync();return next;
    }
    private void verifyPanel(String id,JSONObject report,String prefix)throws Exception{
        long deadline=SystemClock.elapsedRealtime()+5000;
        while(!id.equals(new JSONObject(TvUiPlugin.status(activity)).getString("selected"))&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(20);
        check(id.equals(new JSONObject(TvUiPlugin.status(activity)).getString("selected")),"Saved theme did not load");SystemClock.sleep(150);waitForIdleSync();
        report.put(prefix+"LazyBeforeOpen",field(activity,"channelPanelInitialized"));
        main(()->call("showChannelBar",new Class<?>[]{String.class,String.class},"主题弹窗测试","等待频道载入"));waitForIdleSync();
        if(!id.equals("builtin")){
            Object chrome=field(activity,"tvUiChrome");check(chrome!=null,"Channel popup still uses classic theme before channel list opens");
            Object card=field(activity,"channelBar");check(card!=field(chrome,"originalCard"),"Channel popup is the classic card");
            check(field(card,"this$0")==TvUiPlugin.provider(),"Channel popup renderer belongs to another theme");
        }
        report.put(prefix+"CardBeforePanelPassed",true);screenshot("theme-"+id+"-"+prefix+"-card.png");
        main(()->call("openChannelList"));waitForIdleSync();
        Object chrome=field(activity,"tvUiChrome");
        if(!id.equals("builtin")){
            check(chrome!=null,"Saved theme renderer was not applied after lazy panel initialization");
            check((Integer)field(activity,"appliedTvUiRevision")==TvUiPlugin.revision(),"Saved theme notification was lost before panel initialization");
            check(field(activity,"channelListPanel")!=field(chrome,"originalPanel"),"Panel stayed on default layout");
        }
        check(TvUiPlugin.failure().isEmpty(),"Theme renderer rejected: "+TvUiPlugin.failure());report.put(prefix+"Passed",true);
        screenshot("theme-"+id+"-"+prefix+".png");
    }
    private void screenshot(String name)throws Exception{
        android.graphics.Bitmap[] image={null};
        if(Build.VERSION.SDK_INT>=18)image[0]=getUiAutomation().takeScreenshot();
        else main(()->{View root=activity.getWindow().getDecorView();image[0]=android.graphics.Bitmap.createBitmap(root.getWidth(),root.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);root.draw(new android.graphics.Canvas(image[0]));});
        if(image[0]!=null)try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),name))){image[0].compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{image[0].recycle();}
    }
    public void onStart(){Bundle result=new Bundle();JSONObject report=new JSONObject();int code=-1;boolean prepare="prepare".equals(args.getString("action")),prepared=false;
        try{
            String id=args.getString("id","ui3");report.put("id",id).put("sdk",Build.VERSION.SDK_INT).put("pid",android.os.Process.myPid());
            if(prepare){
                check(!backup().exists(),"Unfinished preference backup exists");Context context=getTargetContext();SharedPreferences prefs=context.getSharedPreferences(MainActivity.PREFERENCES,0),theme=context.getSharedPreferences("tv_ui",0);
                JSONObject saved=new JSONObject().put("prefs",encode(prefs.getAll())).put("theme",encode(theme.getAll())).put("pid",android.os.Process.myPid());
                try(FileOutputStream out=new FileOutputStream(backup())){out.write(saved.toString().getBytes("UTF-8"));}
                prefs.edit().putBoolean("auto_update_channel_list",false).putBoolean("neighbor_probe_enabled",false).putBoolean("player_volume_balance",false).putString("last_channel_snapshot_v2",new JSONObject().put("groupTitle","主题恢复测试").put("catalogSource",ChannelCatalog.SOURCE_CUSTOM).put("number","1").put("name","启动等待").put("urls",new JSONArray().put("http://A/ku9/js/__theme_restart_bootstrap__.js")).put("sourceIndex",0).toString()).commit();
                TvUiPlugin.select(context,id);check(id.equals(theme.getString("selected","")),"Theme selection not persisted");prepared=true;report.put("saved",true);
            }else if("cleanup".equals(args.getString("action"))){restoreBackup(report);}
            else{
                check(TvUiPlugin.current()==null,"Verify requires a fresh target process");JSONObject saved=readBackup();check(saved.getInt("pid")!=android.os.Process.myPid(),"Theme not tested across process restart");report.put("savePid",saved.getInt("pid"));
                activity=launch("await_main");verifyPanel(id,report,"cold");
                Activity previous=activity;main(()->previous.finish());waitForIdleSync();if(Build.VERSION.SDK_INT>=17){long deadline=SystemClock.elapsedRealtime()+5000;while(!previous.isDestroyed()&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(20);check(previous.isDestroyed(),"Old Activity did not exit");}else check(previous.isFinishing(),"Old Activity did not exit");
                activity=launch("await_reopen");verifyPanel(id,report,"reopen");
            }
            report.put("passed",true);
        }catch(Throwable failure){code=0;try{report.put("passed",false).put("failure",android.util.Log.getStackTraceString(failure));}catch(Exception ignored){}}
        finally{
            try{if(activity!=null){main(()->activity.finish());long end=SystemClock.elapsedRealtime()+5000;while(destroyedActivity!=activity&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(20);check(destroyedActivity==activity,"Activity cleanup did not finish");waitForIdleSync();}if(!prepared&&backup().exists())restoreBackup(report);}catch(Exception error){code=0;try{report.put("passed",false).put("cleanupFailure",String.valueOf(error));}catch(Exception ignored){}}
            try{File folder=getTargetContext().getExternalFilesDir(null);folder.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(folder,"theme-restart-result.json"))){out.write(report.toString(2).getBytes("UTF-8"));}}catch(Exception ignored){}
            result.putString("stream",report.toString());finish(code,result);
        }
    }
}
