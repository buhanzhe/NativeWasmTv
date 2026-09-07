package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.WebView;
import android.widget.FrameLayout;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Test the shrunken APK's reflective WebView callbacks, JNI and real JS bridges. */
public final class ReleaseSmokeInstrumentation extends Instrumentation {
    private final StringBuilder report = new StringBuilder();
    private Ku9ScriptResolver resolver;
    private File script;
    private ManagementActivity management;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        int code = -1;
        try {
            MainActivity activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(1800);
            runOnMainSync(() -> {
                try {
                    Field request = MainActivity.class.getDeclaredField("playRequestId");
                    request.setAccessible(true); request.setInt(activity, request.getInt(activity) + 1);
                    Method release = MainActivity.class.getDeclaredMethod("releasePlayer");
                    release.setAccessible(true); release.invoke(activity);
                    resolver = new Ku9ScriptResolver(activity, (FrameLayout) activity.findViewById(android.R.id.content));
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            String rnd = com.bu.cc.tv.NativeYspSigner.tokenRnd("00000000000000000000000000000000", "1700000000000");
            check(rnd != null && !rnd.isEmpty(), "Native signer failed");
            note("PASS JNI signer loaded and returned a result");
            Class<?> legacy = Class.forName("xiao.bu.tv.ManagementActivity$LegacyFileChooserClient");
            int callbacks = 0;
            for (Method method : legacy.getDeclaredMethods()) if (method.getName().equals("openFileChooser")) callbacks++;
            check(callbacks == 3, "Legacy file chooser callbacks stripped");
            note("PASS all three Android 4.x file chooser callbacks retained");

            if (Build.VERSION.SDK_INT >= 21) {
                String name = "ntv-smoke-" + UUID.randomUUID() + ".js";
                String js = "var CryptoJS=require('crypto'),JSEncrypt=require('jsencrypt');"
                        + "function main(item){var uri=ku9.Uri('https://example.com:8443/a/b.m3u8?x=1#live');"
                        + "if(ku9.md5('abc')!=='900150983cd24fb0d6963f7d28e17f72'"
                        + "||ku9.sha256('abc')!==CryptoJS.SHA256('abc').toString()"
                        + "||ku9.decodeBase64(ku9.encodeBase64('酷9'))!=='酷9'"
                        + "||typeof JSEncrypt!=='function'||uri.Host!=='example.com'||uri.Port!==8443"
                        + "||uri.Params.x!=='1'||item.id!==ku9.getQuery(item.url,'id'))"
                        + "throw new Error('compatibility bridge');"
                        + "return 'http://127.0.0.1:9966/smoke.mp4?id='+item.id;}";
                script = new File(Ku9ScriptLoader.saveUserScript(activity, name, js.getBytes("UTF-8")).path);
                for (String id : new String[]{"first", "second", "third"}) {
                    String resolved = resolve("http://placeholder/ku9/js/" + name + "?id=" + id, true);
                    check(resolved.equals("http://127.0.0.1:9966/smoke.mp4?id=" + id), "Wrong Ku9 result " + resolved);
                }
                note("PASS common Ku9 require/crypto/RSA/URI/query bridge, three consecutive resolutions");
            } else {
                String reason = resolve("http://A/ku9/js/ntv-smoke-unsupported.js?id=test", false);
                check(reason.contains("5.0"), "Legacy Ku9 must fail clearly: " + reason);
                note("PASS Android < 5.0 Ku9 capability guard");
            }
            runOnMainSync(() -> resolver.destroy());
            management = (ManagementActivity) startActivitySync(new Intent(getTargetContext(), ManagementActivity.class)
                    .putExtra("management_url", "http://127.0.0.1:9966/").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(2500);
            Field field = ManagementActivity.class.getDeclaredField("webView"); field.setAccessible(true);
            WebView web = (WebView) field.get(management);
            String[] value = {null}; CountDownLatch done = new CountDownLatch(1);
            runOnMainSync(() -> web.evaluateJavascript("JSON.stringify({loaded:document.body.innerText.length>100,"
                    + "gyro:typeof NtvDevice.hasGyroscope(),back:typeof NtvDevice.navigateBack,"
                    + "capture:typeof NtvDevice.saveVideoScreenshot})", result -> {value[0]=result; done.countDown();}));
            check(done.await(5, TimeUnit.SECONDS), "Management JS timeout");
            check(value[0].contains("boolean") && value[0].contains("function") && value[0].contains("true"),
                    "Management bridge failed: " + value[0]);
            note("PASS embedded management page and actual NtvDevice JS bridge: " + value[0]);
        } catch (Throwable error) { code=1; note("FAIL " + Log.getStackTraceString(error)); }
        finally {
            runOnMainSync(() -> {if(resolver!=null)resolver.destroy();if(management!=null)management.finish();});
            if (script != null && !script.delete()) note("Cleanup failed for test fixture " + script);
        }
        Bundle result = new Bundle(); result.putString("stream",report.toString()); finish(code,result);
    }
    private String resolve(String url, boolean success) throws Exception {
        String[] result={null}, failure={null}; CountDownLatch done=new CountDownLatch(1);
        runOnMainSync(() -> resolver.resolve(1,"Release smoke",url,new Ku9ScriptResolver.Callback(){
            @Override public void onResolved(int id, Ku9ScriptResolver.Result value){result[0]=value.url;done.countDown();}
            @Override public void onFailed(int id,String reason){failure[0]=reason;done.countDown();}
        }));
        check(done.await(15,TimeUnit.SECONDS),"Ku9 timeout");
        check(success ? failure[0]==null && result[0]!=null : failure[0]!=null,"Unexpected Ku9 result " + failure[0]);
        return success ? result[0] : failure[0];
    }
    private void note(String text){report.append(text).append('\n');Log.i("ReleaseSmokeTest",text);}
    private static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
}
