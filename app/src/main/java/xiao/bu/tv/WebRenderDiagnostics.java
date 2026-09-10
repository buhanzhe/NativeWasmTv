package xiao.bu.tv;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.WebView;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/** Small pre-crash snapshots: no URLs, page contents, screenshots or frame-loop work. */
final class WebRenderDiagnostics {
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;
    private String lastState = "";
    private boolean environmentRecorded;
    private final Runnable sample = new Runnable() {
        @Override public void run() {
            if (!running) return;
            Runtime runtime = Runtime.getRuntime();
            String memory = "t=" + System.currentTimeMillis()
                    + " up=" + SystemClock.elapsedRealtime()
                    + " javaKB=" + (runtime.totalMemory() - runtime.freeMemory()) / 1024
                    + " maxKB=" + runtime.maxMemory() / 1024
                    + " nativeKB=" + Debug.getNativeHeapAllocatedSize() / 1024;
            // /proc/self/status is small; avoid expensive PSS/smaps scans or GC.
            try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/status"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("VmSize:") || line.startsWith("VmRSS:")) {
                        memory += " " + line.trim().replaceAll("\\s+", " ");
                    }
                }
            } catch (IOException | SecurityException ignored) {
                memory += " proc=unavailable";
            }
            CrashReporting.putDiagnostic(context, "web_memory", memory);
            Log.i("WebRenderDiagnostics", memory);
            handler.postDelayed(this, 30000L);
        }
    };

    WebRenderDiagnostics(Context context) {
        this.context = context.getApplicationContext();
    }

    void update(WebView view, boolean casting, int fps, int width, int height) {
        if (!environmentRecorded) {
            String provider = "system";
            if (Build.VERSION.SDK_INT >= 26) {
                try { provider = Api26.provider(); }
                catch (RuntimeException | LinkageError ignored) { provider = "unavailable"; }
            }
            String bits = BuildConfig.CJS_PLUGIN_ABI;
            if (Build.VERSION.SDK_INT >= 23) bits = Api23.is64Bit() ? "64" : "32";
            CrashReporting.putDiagnostic(context, "web_environment",
                    "sdk=" + Build.VERSION.SDK_INT + " process=" + bits + " provider=" + provider);
            environmentRecorded = true;
        }
        String state = "active viewport=" + width + "x" + height
                + " layer=" + view.getLayerType() + " hw=" + view.isHardwareAccelerated()
                + " cast=" + casting + " fps=" + fps;
        if (!state.equals(lastState)) {
            lastState = state;
            CrashReporting.putDiagnostic(context, "web_render", state);
            Log.i("WebRenderDiagnostics", state);
        }
        if (!running) {
            running = true;
            handler.post(sample);
        }
    }

    void stop(String reason) {
        running = false;
        handler.removeCallbacks(sample);
        lastState = reason;
        CrashReporting.putDiagnostic(context, "web_render", reason);
    }

    @TargetApi(23)
    private static final class Api23 {
        static boolean is64Bit() { return android.os.Process.is64Bit(); }
    }

    @TargetApi(26)
    private static final class Api26 {
        static String provider() {
            PackageInfo info = WebView.getCurrentWebViewPackage();
            return info == null ? "unknown" : info.packageName + "/" + info.versionName;
        }
    }
}
