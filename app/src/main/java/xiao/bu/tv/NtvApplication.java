package xiao.bu.tv;

import android.app.Application;
import java.io.FileReader;
import java.io.IOException;

public final class NtvApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Do not load a second crash SDK/ANR watchdog in :crash_watchdog.
        if (isMainProcess()) CrashReporting.startIfAllowed(this);
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
