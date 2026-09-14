package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.webkit.WebView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Opt-in exhaustive channel regression. Fixture assets and reports never ship in the app. */
public final class ChannelMatrixInstrumentation extends Instrumentation {
    private Bundle args;
    private MainActivity activity;
    private ChannelCatalog.Group[] originalGroups;
    private int originalGroup, originalChannel;
    private Map<String, ?> preferences;
    private File report;
    @Override public void onCreate(Bundle value) { super.onCreate(value); args=value==null?new Bundle():value; start(); }
    private Field field(String name) throws Exception { Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f; }
    private void invoke(String name) throws Exception { Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(activity); }
    private void write(JSONObject row) throws Exception {
        FileOutputStream f=new FileOutputStream(report,true);
        try { f.write((row.toString()+"\n").getBytes("UTF-8")); f.flush(); } finally { f.close(); }
        android.util.Log.i("ChannelMatrix",row.toString());
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(1500); // Let startup's last-channel restore finish before selecting fixtures.
            preferences=activity.getSharedPreferences(MainActivity.PREFERENCES,0).getAll();
            String reportName=args.getString("report", "matrix-results.jsonl");
            if (!reportName.matches("[a-zA-Z0-9_-]+\\.jsonl")) throw new IllegalArgumentException("report name");
            report=new File(activity.getExternalFilesDir(null),reportName);
            String selected=","+args.getString("ids", "")+",";
            JSONArray cases=new JSONArray(Ku9HttpClient.readUtf8(getContext().getAssets().open("matrix.json"),4*1024*1024));
            int start=Integer.parseInt(args.getString("start","0")),end=Integer.parseInt(args.getString("end",String.valueOf(cases.length())));
            if(start==0 && report.exists()) report.delete();
            if ("1".equals(args.getString("contract")))
                write(new JSONObject().put("contract", Ku9CjsContractTest.run(activity)
                        + Ku9PageResultTest.run(this, activity)));
            runOnMainSync(()->{try {
                originalGroups=ChannelCatalog.GROUPS;
                originalGroup=field("currentGroupIndex").getInt(activity);originalChannel=field("currentChannelIndex").getInt(activity);
                field("autoSwitchSource").setBoolean(activity,false);
            }catch(Exception e){throw new RuntimeException(e);}});
            for(String site:new String[]{"tv.cctv.com","yangshipin.cn","tv.gxtv.cn"}) {
                try {if(!CjsPluginRuntime.isInstalled(site)) CjsPluginRuntime.installOrUpdate(site);}
                catch(Exception e){write(new JSONObject().put("environment","plugin").put("site",site).put("error",e.toString()));}
            }
            for(int i=start;i<Math.min(end,cases.length());i++) {
                if (selected.length()>2 && !selected.contains(","+i+",")) continue;
                JSONObject row=cases.getJSONObject(i);String url=row.getString("url");File temporary=null;
                write(new JSONObject().put("phase","start").put("id",i).put("url",url));
                try {
                    if(row.has("script")) {
                        String name=row.getString("script");
                        temporary=new File(Ku9ScriptLoader.saveUserScript(activity,name,
                                Ku9HttpClient.readUtf8(getContext().getAssets().open(name),2*1024*1024).getBytes("UTF-8")).path);
                        int query=url.indexOf('?');url="http://matrix/ku9/js/"+name+(query<0?"":url.substring(query));
                        row.put("scriptMode","frozen repository bytes through local Ku9 loader");
                    }
                    final String source=url;final String title="MATRIX_"+i+" "+row.getString("name");
                    final Channel channel=new Channel("1",title,"matrix",source,null,null);
                    long started=SystemClock.elapsedRealtime();final Throwable[] error={null};
                    runOnMainSync(()->{try {
                        ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("MATRIX",ChannelCatalog.SOURCE_CUSTOM,new Channel[]{channel})};
                        field("currentGroupIndex").setInt(activity,0);field("currentChannelIndex").setInt(activity,0);field("currentSourceIndex").setInt(activity,0);
                        Method m=MainActivity.class.getDeclaredMethod("switchChannel",int.class);m.setAccessible(true);m.invoke(activity,0);
                    }catch(Throwable e){error[0]=e;}});
                    if(error[0]!=null)throw new Exception(error[0]);
                    String status="NO_PLAYBACK_OBSERVED";JSONObject media=null;String lastText="";int pageProgress=0;
                    long limit=Long.parseLong(args.getString("timeoutMs", "web".equals(row.getString("kind"))?"12000":"20000"));
                    while(SystemClock.elapsedRealtime()-started<limit) {
                        SystemClock.sleep(400);final JSONObject[] snap={null};final WebView[] browser={null};final String[] text={""};
                        runOnMainSync(()->{try {
                            Method m=MainActivity.class.getDeclaredMethod("buildLocalMediaState",boolean.class);m.setAccessible(true);snap[0]=(JSONObject)m.invoke(activity,false);
                            TextView view=(TextView)field("statusText").get(activity);text[0]=view==null?"":view.getText().toString();
                            WebSourceView web=(WebSourceView)field("webSourceView").get(activity);
                            Field f=WebSourceView.class.getDeclaredField("webView");f.setAccessible(true);if(web!=null&&web.isPageVisible())browser[0]=(WebView)f.get(web);
                        }catch(Exception e){text[0]=e.toString();}});
                        media=snap[0];lastText=text[0];
                        if(media!=null&&title.equals(media.optString("name"))&&media.optBoolean("playing")&&media.optDouble("outputFps",0)>0) {status="NATIVE_VIDEO_FRAMES";break;}
                        if(browser[0]!=null) {
                            final int[] p={0};runOnMainSync(()->p[0]=browser[0].getProgress());pageProgress=p[0];
                            if(Build.VERSION.SDK_INT>=19) {
                                final String[] v={null};CountDownLatch done=new CountDownLatch(1);
                                runOnMainSync(()->browser[0].evaluateJavascript("JSON.stringify((function(){var a=document.querySelectorAll('video,audio'),r=[];for(var i=0;i<a.length;i++)r.push({time:a[i].currentTime,paused:a[i].paused,ready:a[i].readyState,width:a[i].videoWidth||0});return r;})())",value->{v[0]=value;done.countDown();}));
                                if(done.await(1,TimeUnit.SECONDS)&&v[0]!=null) {
                                    row.put("webMedia",v[0]);
                                    Object parsed=new org.json.JSONTokener(v[0]).nextValue();JSONArray videos=new JSONArray(String.valueOf(parsed));
                                    for(int k=0;k<videos.length();k++) {JSONObject video=videos.getJSONObject(k);if(!video.optBoolean("paused",true)&&video.optDouble("time",0)>0.1&&video.optInt("ready",0)>=2)status="WEB_MEDIA_PLAYING";}
                                    if("WEB_MEDIA_PLAYING".equals(status))break;
                                }
                            }
                            if(pageProgress==100&&SystemClock.elapsedRealtime()-started>4000&&row.getString("origins").contains("JoyPage")) {status="PAGE_LOADED_PLAYBACK_NOT_VERIFIED";break;}
                        }
                        if(lastText.contains("设备性能太弱")||lastText.contains("Android 4.2 以下")) {status="DEVICE_BROWSER_UNSUPPORTED";break;}
                        if(lastText.contains("解析失败")||lastText.contains("脚本执行失败")||lastText.contains("脚本结果无效")||lastText.contains("没有返回")||lastText.contains("格式错误")||lastText.contains("网络没有 IPv6")) {status="RESOLVE_FAILED";break;}
                    }
                    row.put("status",status).put("elapsedMs",SystemClock.elapsedRealtime()-started).put("statusText",lastText).put("pageProgress",pageProgress);
                    if(media!=null)row.put("media",media);
                } catch(Throwable e) {row.put("status","TEST_ERROR").put("error",android.util.Log.getStackTraceString(e));}
                finally {if(temporary!=null)temporary.delete();}
                row.put("android",Build.VERSION.RELEASE);write(row);
            }
            result.putString("stream","MATRIX_DONE "+report.getAbsolutePath()+"\n");
        }catch(Throwable e){result.putString("stream","MATRIX_ERROR "+android.util.Log.getStackTraceString(e));}
        finally {runOnMainSync(()->{try {
            if(activity!=null){invoke("closeWebSource");invoke("releasePlayer");
                Ku9ScriptResolver ku9=(Ku9ScriptResolver)field("ku9ScriptResolver").get(activity);if(ku9!=null)ku9.cancel();
                CjsSiteResolver cjs=(CjsSiteResolver)field("cjsSiteResolver").get(activity);if(cjs!=null)cjs.cancel();
                if(originalGroups!=null){ChannelCatalog.GROUPS=originalGroups;field("currentGroupIndex").setInt(activity,originalGroup);field("currentChannelIndex").setInt(activity,originalChannel);}
                if(preferences!=null){SharedPreferences.Editor edit=activity.getSharedPreferences(MainActivity.PREFERENCES,0).edit().clear();
                    for(Map.Entry<String,?> e:preferences.entrySet()){Object v=e.getValue();String k=e.getKey();if(v instanceof String)edit.putString(k,(String)v);else if(v instanceof Boolean)edit.putBoolean(k,(Boolean)v);else if(v instanceof Integer)edit.putInt(k,(Integer)v);else if(v instanceof Long)edit.putLong(k,(Long)v);else if(v instanceof Float)edit.putFloat(k,(Float)v);else if(v instanceof java.util.Set)edit.putStringSet(k,(java.util.Set<String>)v);}edit.commit();}
            }
        }catch(Exception e){android.util.Log.e("ChannelMatrix","cleanup",e);}});}
        finish(-1,result);
    }
}
