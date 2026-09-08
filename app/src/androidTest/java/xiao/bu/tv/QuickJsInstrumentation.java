package xiao.bu.tv;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.IOException;

public final class QuickJsInstrumentation extends Instrumentation {
    private boolean testTls;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        testTls = args != null && "true".equals(args.getString("tls"));
        start();
    }

    private static final class Host implements NativeQuickJs.Host {
        String result;
        long cancelAt = Long.MAX_VALUE;
        @Override public boolean isCancelled() { return SystemClock.elapsedRealtime() >= cancelAt; }
        @Override public String invoke(int operation, String[] args) {
            if (operation == 5) result = args[0];
            return operation == 0 ? args[0] : null;
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    @Override public void onStart() {
        Bundle results = new Bundle();
        try {
            if (testTls) {
                results.putString("stream", LegacyTlsTest.run(getTargetContext()));
                finish(-1, results);
                return;
            }
            Host host = new Host();
            NativeQuickJs.execute("NtvCjsBridge.complete(NtvCjsBridge.get('中文😀\\u0000', '{}'))", host);
            check("中文😀\u0000".equals(host.result), "UTF-8/JNI bridge roundtrip");
            host = new Host();
            NativeQuickJs.execute("Promise.resolve(40n+2n).then(x=>NtvCjsBridge.complete(String(x)))", host);
            check("42".equals(host.result), "Promise job queue and BigInt");
            boolean failed = false;
            try { NativeQuickJs.execute("var = ;", new Host()); } catch (IOException expected) { failed = true; }
            check(failed, "Syntax error was not reported");
            host = new Host(); host.cancelAt = SystemClock.elapsedRealtime() + 150;
            long start = SystemClock.elapsedRealtime(); failed = false;
            try { NativeQuickJs.execute("while(true){}", host); } catch (IOException expected) { failed = true; }
            check(failed && SystemClock.elapsedRealtime() - start < 3000, "Loop cancellation");
            failed = false;
            try { NativeQuickJs.execute("var a=[];while(true)a.push('x'.repeat(100000));", new Host()); }
            catch (IOException expected) { failed = true; }
            check(failed, "Heap exhaustion was not contained");
            start = SystemClock.elapsedRealtime();
            for (int i = 0; i < 50; i++) {
                host = new Host(); NativeQuickJs.execute("NtvCjsBridge.complete(String(6*7))", host);
                check("42".equals(host.result), "Repeated runtime lifecycle");
            }
            results.putString("stream", "PASS QuickJS: Unicode/NUL, bridge, Promise, BigInt, syntax error, cancellation, heap limit, 50 runtimes in "
                    + (SystemClock.elapsedRealtime() - start) + " ms\n");
            finish(-1, results);
        } catch (Throwable error) {
            results.putString("stream", "FAIL QuickJS: " + android.util.Log.getStackTraceString(error));
            finish(0, results);
        }
    }
}
