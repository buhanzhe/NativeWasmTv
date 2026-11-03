package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.WebView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Verify real WebView document-start timing, frame coverage and actual HTTP hints. */
public final class DesktopProfileInstrumentation extends Instrumentation {
    private WebSourceView source;
    private WebView web;
    private Bundle args;
    private final StringBuilder report = new StringBuilder();
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments);args=arguments;start(); }
    @Override public void onStart() {
        int code=-1;
        try {
            String oldUa="Mozilla/5.0 (Linux; U; Android 4.1; phone) AppleWebKit/534.30 (KHTML, like Gecko) Version/4.0 Mobile Safari/534.30";
            String legacyDesktop=DesktopWebProfile.userAgent("windows",oldUa);
            check(legacyDesktop.contains("AppleWebKit/534.30")&&!legacyDesktop.contains("Chrome/")
                    &&!legacyDesktop.contains("Android")&&!legacyDesktop.contains("Mobile"),"Legacy engine version fabricated");
            check(!DesktopWebProfile.userAgent("windows",null).contains("Chrome/"),"Unknown engine version fabricated");
            check(DesktopWebProfile.userAgent("windows",oldUa,"118")
                    .contains("Chrome/118.0.5993.117"),"Chrome 118 release identity");
            check(DesktopWebProfile.userAgent("macos",oldUa,"128")
                    .contains("Chrome/128.0.6613.137"),"Chrome 128 release identity");
            check(DesktopWebProfile.userAgent("windows",oldUa,"155")
                    .contains("Chrome/155.0.8059.40"),"Explicit modern Windows version missing");
            check(DesktopWebProfile.ipadUserAgent(oldUa,"138")
                    .contains("CriOS/138.0.7204.183"),"Chrome 138 iPad release identity");
            MainActivity activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(2500);
            main(() -> {
                // Invalidate asynchronous native-channel startup before taking over
                // this test Activity; a late result must not cover the test WebView.
                Field request=MainActivity.class.getDeclaredField("playRequestId");
                request.setAccessible(true);request.setInt(activity,request.getInt(activity)+1);
                Method release=MainActivity.class.getDeclaredMethod("releasePlayer");
                release.setAccessible(true);release.invoke(activity);
                source=(WebSourceView)field(activity,"webSourceView");
                source.setListener(null);
            });
            if (!args.containsKey("only") && !args.containsKey("url")) {
            for (String mode:new String[]{"windows","macos"}) {
                for(String resolution:new String[]{"720p","1080p","2k"}) {
                    open("http://127.0.0.1:18890/",mode,resolution,1f);
                    DesktopWebProfile profile=(DesktopWebProfile)field(source,"desktopProfile");
                    check(profile.hasDocumentStartProtection(),"Device lacks document-start support");
                    for(int i=0;i<50;i++) {
                        if(json("({ready:!!window.requestHeaders&&!!window.workerProfile&&window.childProfiles&&childProfiles.length===2})").optBoolean("ready"))break;
                        SystemClock.sleep(100);
                    }
                    JSONObject result=json("({first:firstProfile,children:childProfiles,worker:window.workerProfile,hints:window.hints||{},headers:window.requestHeaders||{}})");
                    check(((android.view.View)field(source,"loadingOverlay")).getVisibility()
                            == android.view.View.GONE,"Loading overlay remained after ready");
                    note(mode+" "+resolution+" "+result);
                    int width="2k".equals(resolution)?2560:"1080p".equals(resolution)?1920:1280;
                    verify(result.getJSONObject("first"),mode,width,1);
                    JSONArray children=result.getJSONArray("children");check(children.length()==2,"Frames not loaded");
                    for(int i=0;i<children.length();i++)verify(children.getJSONObject(i),mode,width,1);
                    JSONObject headers=result.getJSONObject("headers");
                    check(headers.getString("user-agent").equals(result.getJSONObject("first").getString("ua")),"HTTP/JS UA mismatch");
                    check(result.getJSONObject("worker").getString("ua").equals(headers.getString("user-agent")),"Worker UA mismatch");
                    if(result.getJSONObject("worker").optString("platform").contains("Linux"))
                        note("LIMITATION: Worker platform still identifies the Android/Linux engine");
                    check(!headers.toString().contains("Android"),"HTTP leaked Android identity");
                    if(headers.toString().contains("xiao.bu.tv")) {
                        note("LIMITATION: engine still sends X-Requested-With app package despite empty origin allow-list");
                    }
                    if(headers.has("sec-ch-ua-platform")) {
                        check(headers.getString("sec-ch-ua-platform").contains("macos".equals(mode)?"macOS":"Windows"),"HTTP platform hints");
                        check(headers.optString("sec-ch-ua-mobile").equals("?0"),"HTTP mobile hints");
                    }
                }
            }
            open("http://127.0.0.1:18890/","windows","1080p",1.25f);
            verify(json("firstProfile"),"windows",1920,1.25);
            verify(json("profileSnapshot()"),"windows",1920,1.25);
            open("http://127.0.0.1:18890/","ipad","1080p",1f);
            JSONObject ipad=json("profileSnapshot()");
            check(ipad.getString("ua").contains("iPad")
                    && ipad.getString("platform").equals("MacIntel")
                    && ipad.getInt("touch")==5,"iPad identity "+ipad);
            check(ipad.getString("gpu").contains("Apple GPU"),"iPad WebGL model "+ipad);
            open("http://127.0.0.1:18890/","native","720p",1f);
            JSONObject nativeProfile=json("firstProfile");
            check(nativeProfile.getString("ua").contains("Android"),"Native mode must remain native");
            check(!nativeProfile.optBoolean("profile"),"Native mode must not install spoofing");
            }
            if("true".equals(args.getString("public"))) {
                for(String site:args.containsKey("only") ? new String[]{args.getString("only")}
                        : new String[]{"browserinfo","monitor-info"}) {
                    open("https://toolwa.com/"+site+"/","windows","1080p",1f);
                    SystemClock.sleep(6000);
                    JSONObject publicResult=json("({title:document.title,text:document.body.innerText.slice(0,12000)})");
                    note("public "+site+" "+publicResult);
                    check(publicResult.getString("title").contains("工具哇"),"Public page not loaded");
                    check(publicResult.getString("text").contains("Windows"),"Public page did not detect desktop OS");
                    if(site.equals("monitor-info")) {
                        check(publicResult.getString("text").contains("1920 × 1080"),"Public monitor size");
                        if(publicResult.getString("text").contains("粗略指针（手指触摸）"))
                            note("LIMITATION: engine CSS pointer media queries still describe a touchscreen; no matchMedia-only disguise applied");
                    } else {
                        check(publicResult.getString("text").contains("Desktop"),"Public page did not detect desktop device");
                    }
                    main(() -> {source.setVisibility(android.view.View.VISIBLE);web.setVisibility(android.view.View.VISIBLE);web.invalidate();});
                    SystemClock.sleep(600);
                    screenshot("desktop-"+site+".png");
                }
            }
            if (args.containsKey("url")) {
                open(args.getString("url"),"windows","1080p",1f);
                SystemClock.sleep(12000);
                note("EXTERNAL inspection "+json("({url:location.href,title:document.title,text:document.body.innerText.slice(0,26000),frames:Array.from(document.querySelectorAll('iframe')).map(function(f){return f.src;})})"));
                screenshot("desktop-checker.png");
                note("External inspection complete; the website's score must be reviewed separately");
            } else note("PASS desktop profile");
        } catch(Throwable error) {code=1;note("FAIL "+Log.getStackTraceString(error));}
        Bundle result=new Bundle();result.putString("stream",report.toString());finish(code,result);
    }
    private void open(String url,String mode,String resolution,float scale) throws Exception {
        main(() -> {source.applyConfiguration(resolution,true,mode,scale);source.open(900009,url);web=(WebView)field(source,"webView");});
        SystemClock.sleep(700);
        for(int i=0;i<150;i++) {
            if(json("({ready:document.readyState,url:location.href})").optString("ready").equals("complete"))break;
            SystemClock.sleep(200);
        }
        SystemClock.sleep(500);
    }
    private void verify(JSONObject p,String mode,int width,double scale)throws Exception {
        check(p.getString("platform").equals("macos".equals(mode)?"MacIntel":"Win32"),"platform "+p);
        check(!p.getString("ua").contains("Android"),"UA leaked Android");
        check(p.getInt("width")==width&&p.getInt("height")==width*9/16,"screen "+p);
        check(p.getInt("availWidth")==width&&p.getInt("availHeight")==width*9/16,"available screen");
        check(p.getDouble("dpr")==scale,"physical density leaked");
        check(p.getInt("touch")==0&&p.getInt("cores")==8,"hardware profile");
        check(p.getString("orientation").equals("landscape-primary"),"phone orientation leaked");
        check(p.getString("battery").equals("undefined"),"battery exposed");
        check(p.getBoolean("gl")&&p.getBoolean("nativeGl"),"WebGL rendering/native getParameter broken");
        check(p.getBoolean("debugExtension")&&p.getBoolean("debugListed"),"WebGL model unavailable");
        check(!p.getString("gpu").isEmpty()&&!p.getString("gpu").contains("Adreno"),
                "Native Android GPU exposed");
        check(p.getBoolean("nativeUa"),"Redundant JavaScript UA override");
    }
    private JSONObject json(String expression)throws Exception {
        String[] result={null};CountDownLatch latch=new CountDownLatch(1);
        main(() -> web.evaluateJavascript("JSON.stringify("+expression+")",v->{result[0]=v;latch.countDown();}));
        check(latch.await(5,TimeUnit.SECONDS),"JS timeout");
        return new JSONObject(String.valueOf(new JSONTokener(result[0]).nextValue()));
    }
    private void screenshot(String name)throws Exception {
        android.graphics.Bitmap b=getUiAutomation().takeScreenshot();
        java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null),name));
        try{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{out.close();b.recycle();}
    }
    private static Object field(Object target,String name)throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private interface Work{void run()throws Exception;}
    private void main(Work work)throws Exception {Exception[] err={null};runOnMainSync(()->{try{work.run();}catch(Exception e){err[0]=e;}});if(err[0]!=null)throw err[0];}
    private void note(String text){report.append(text).append('\n');Log.i("DesktopProfileTest",text);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
