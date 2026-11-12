package xiao.bu.tv;

import android.app.Application;
import java.io.FileReader;
import java.io.IOException;

public final class NtvApplication extends Application {
    private int startedActivities;
    boolean hasVisibleActivities() { return startedActivities > 0; }
    private void notifyVisibility() {
        MainActivity owner = LocalPlayerRegistry.localInputOwner();
        if (owner != null) owner.reconcilePlaybackVisibility();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) trimCaches();
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        trimCaches();
    }

    private static void trimCaches() {
        AlbumArtLoader.trimMemory();
        WebPageScriptManager.trimMemory();
        WebAdBlocker.trimMemory();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        LiteEngine.installDocumentScripts(LitePageScripts::forDocument);
        LiteEngine.installHostPolicy(new WebSiteCompatibility());
        // Do not load a second crash SDK/ANR watchdog in :crash_watchdog.
        if (isMainProcess()) {
            AppLog.initialize(this);
            registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
                public void onActivityStarted(android.app.Activity activity) { startedActivities++; notifyVisibility(); }
                public void onActivityStopped(android.app.Activity activity) { startedActivities=Math.max(0,startedActivities-1); notifyVisibility(); }
                public void onActivityCreated(android.app.Activity activity, android.os.Bundle state) { }
                public void onActivityResumed(android.app.Activity activity) { }
                public void onActivityPaused(android.app.Activity activity) { }
                public void onActivitySaveInstanceState(android.app.Activity activity, android.os.Bundle state) { }
                public void onActivityDestroyed(android.app.Activity activity) { }
            });
            GithubProxy.initialize(this);
            HardwareDecoderInventory.warm(this);
            CrashReporting.startIfAllowed(this);
            ReceivedApkCleanup.initialize(this);
        }
    }

    private boolean isMainProcess() {
        try (FileReader reader = new FileReader("/proc/self/cmdline")) {
            StringBuilder name = new StringBuilder();
            int value;
            while (name.length() < 256 && (value = reader.read()) > 0) {
                name.append((char) value);
            }
            return getPackageName().equals(name.toString());
        } catch (IOException | SecurityException ignored) {
            // Fail closed if the process cannot be identified.
            return false;
        }
    }

}
