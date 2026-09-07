package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.FrameLayout;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Runs the public Ku9 script contract against the minified release APK. */
public final class Ku9CompatibilityInstrumentation extends Instrumentation {
    private Ku9ScriptResolver resolver;
    private File script;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        start();
    }

    @Override public void onStart() {
        int code = -1;
        String message;
        try {
            MainActivity activity = (MainActivity) startActivitySync(new Intent(
                    getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(1200L);
            runOnMainSync(() -> resolver = new Ku9ScriptResolver(activity,
                    (FrameLayout) activity.findViewById(android.R.id.content)));
            String name = "ku9-release-" + UUID.randomUUID() + ".js";
            String source = "var CryptoJS=require('crypto'),JSEncrypt=require('jsencrypt');"
                    + "module.exports.main=function(item){var u=ku9.Uri('https://example.com:8443/live.m3u8?x=1');"
                    + "if(CryptoJS.MD5('abc').toString()!==ku9.md5('abc')"
                    + "||CryptoJS.SHA256('abc').toString()!==ku9.sha256('abc')"
                    + "||ku9.decodeBase64(ku9.encodeBase64('酷9'))!=='酷9'"
                    + "||typeof JSEncrypt!=='function'||u.Port!==8443||u.Params.x!=='1'||item.id!=='cctv1')"
                    + "throw new Error('Ku9 contract mismatch');return {url:'http://127.0.0.1/live.m3u8'};};";
            script = new File(Ku9ScriptLoader.saveUserScript(activity, name,
                    source.getBytes("UTF-8")).path);
            String resolved = resolve("http://local/ku9/js/" + name + "?id=cctv1");
            if (!"http://127.0.0.1/live.m3u8".equals(resolved)) {
                throw new AssertionError("Unexpected Ku9 URL: " + resolved);
            }
            code = 0;
            message = "PASS minified release Ku9 CommonJS/crypto/RSA/URI/query contract";
        } catch (Throwable error) {
            message = "FAIL " + android.util.Log.getStackTraceString(error);
        } finally {
            runOnMainSync(() -> {
                if (resolver != null) resolver.destroy();
            });
            if (script != null && !script.delete()) {
                android.util.Log.w("Ku9ReleaseTest", "Unable to delete " + script);
            }
        }
        Bundle result = new Bundle();
        result.putString("stream", message + "\n");
        finish(code, result);
    }

    private String resolve(String url) throws Exception {
        String[] result = {null};
        String[] failure = {null};
        CountDownLatch done = new CountDownLatch(1);
        runOnMainSync(() -> resolver.resolve(1, "CCTV-1", url,
                new Ku9ScriptResolver.Callback() {
                    @Override public void onResolved(int id, Ku9ScriptResolver.Result value) {
                        result[0] = value.url;
                        done.countDown();
                    }

                    @Override public void onFailed(int id, String reason) {
                        failure[0] = reason;
                        done.countDown();
                    }
                }));
        if (!done.await(15L, TimeUnit.SECONDS)) {
            throw new AssertionError("Ku9 resolution timed out");
        }
        if (failure[0] != null) {
            throw new AssertionError(failure[0]);
        }
        return result[0];
    }
}
