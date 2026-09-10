package xiao.bu.tv;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.tencent.bugly.crashreport.CrashReport;

/** Main-process crash reporting, enabled automatically on supported Android versions. */
final class CrashReporting {
    private static final String TAG = "NtvCrashReporting";
    private static volatile boolean started;

    private CrashReporting() {}

    static void putDiagnostic(Context context, String key, String value) {
        if (!started) return;
        try {
            Sdk.putDiagnostic(context.getApplicationContext(), key, value);
        } catch (RuntimeException | LinkageError error) {
            Log.w(TAG, "Unable to attach crash diagnostic", error);
        }
    }

    static synchronized void startIfAllowed(Context context) {
        if (started || Build.VERSION.SDK_INT < 15) return;
        try {
            // Separate class keeps the SDK out of the API 14 verification path.
            Sdk.start(context.getApplicationContext());
            started = true;
            Log.i(TAG, "Bugly crash reporting initialized");
        } catch (RuntimeException | LinkageError error) {
            Log.w(TAG, "Bugly initialization failed; playback remains available", error);
        }
    }

    private static final class Sdk {
        static void putDiagnostic(Context context, String key, String value) {
            CrashReport.putUserData(context, key,
                    value.length() > 200 ? value.substring(0, 200) : value);
        }

        static void start(Context context) {
            // Classic Bugly only needs the public App ID. App Key stays off-device.
            CrashReport.setCollectPrivacyInfo(context, false);
            CrashReport.UserStrategy strategy = new CrashReport.UserStrategy(context);
            strategy.setAppVersion(BuildConfig.VERSION_NAME);
            strategy.setAppChannel(BuildConfig.FLAVOR + "-" + BuildConfig.BUILD_TYPE);
            strategy.setEnableNativeCrashMonitor(true);
            strategy.setEnableANRCrashMonitor(true);
            strategy.setEnableUserInfo(false);
            strategy.setBuglyLogUpload(false);
            strategy.setEnableCatchAnrTrace(false);
            strategy.setEnableRecordAnrMainStack(false);
            strategy.setAppReportDelay(20000L);
            CrashReport.initCrashReport(context, "80f289fe97", false, strategy);
            CrashReport.setIsDevelopmentDevice(context, BuildConfig.DEBUG);
        }
    }
}
