package xiao.bu.tv;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.os.SystemClock;
import java.util.ArrayDeque;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.tencent.bugly.crashreport.CrashReport;
import com.tencent.bugly.crashreport.BuglyLog;

/** Main-process crash reporting, enabled automatically on supported Android versions. */
final class CrashReporting {
    private static final String TAG = "NtvCrashReporting";
    private static volatile boolean started;
    private static final ArrayDeque<PendingError> pending=new ArrayDeque<>();
    private static boolean flushScheduled;
    private static final class PendingError {
        final Throwable error;final Thread origin;final long created=SystemClock.elapsedRealtime();
        PendingError(Throwable error,Thread origin){this.error=error;this.origin=origin;}
    }
    private static final class Deferred {
        static final ScheduledThreadPoolExecutor worker=new ScheduledThreadPoolExecutor(1,task->{
            Thread thread=new Thread(task,"player-error-sdk-wait");thread.setPriority(Thread.MIN_PRIORITY);thread.setDaemon(true);return thread;});
        static{worker.setKeepAliveTime(30,TimeUnit.SECONDS);worker.allowCoreThreadTimeOut(true);}
    }

    private CrashReporting() {}
    static boolean enabled(){return started;}

    static synchronized void postPlayerError(Throwable error,Thread origin){
        if(!started)return;
        try{
            if(Sdk.ready()){Sdk.postPlayerError(error,origin);return;}
            if(pending.size()==8)pending.removeFirst();pending.addLast(new PendingError(error,origin));
            if(!flushScheduled){flushScheduled=true;Deferred.worker.schedule(CrashReporting::flush,1,TimeUnit.SECONDS);}
        }
        catch(RuntimeException|LinkageError failure){Log.w(TAG,"Unable to submit player error",failure);}
    }
    private static synchronized void flush(){
        flushScheduled=false;
        long now=SystemClock.elapsedRealtime();
        int expired=0;while(!pending.isEmpty()&&now-pending.peekFirst().created>=60000){pending.removeFirst();expired++;}
        if(expired>0)Log.w(TAG,"Player reports expired while Bugly module initialization was unavailable: "+expired);
        try{
            if(Sdk.ready())while(!pending.isEmpty()){
                PendingError event=pending.removeFirst();
                Sdk.postPlayerError(event.error,event.origin);
            }
            else if(!pending.isEmpty()){flushScheduled=true;Deferred.worker.schedule(CrashReporting::flush,1,TimeUnit.SECONDS);}
        }catch(RuntimeException|LinkageError failure){pending.clear();Log.w(TAG,"Unable to flush player errors",failure);}
    }

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
            Log.i(TAG, "Bugly crash reporting initialization requested");
        } catch (RuntimeException | LinkageError error) {
            Log.w(TAG, "Bugly initialization failed; playback remains available", error);
        }
    }

    private static final class Sdk {
        static boolean ready(){return com.tencent.bugly.CrashModule.getInstance().hasInitialized();}
        static void postPlayerError(Throwable error,Thread origin){
            // A caught Java error goes to Bugly's Errors tab, not the crash count.
            // This SDK does not invoke CrashHandleCallback for caught errors.
            // Carry bounded details in its official log attachment with an ID
            // on every report; shared logs from other channels remain identifiable.
            if(error instanceof PlayerErrorReports.PlaybackFailure){
                PlayerErrorReports.PlaybackFailure report=(PlayerErrorReports.PlaybackFailure)error;
                BuglyLog.e("nTvPlayer","[nTvPlayer:"+report.id+"] "+report.details);
            }
            CrashReport.postCatchedException(error,origin);
            Log.i(TAG,"Player error queued for Bugly Errors");
        }
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
            strategy.setBuglyLogUpload(true);
            strategy.setEnableCatchAnrTrace(false);
            strategy.setEnableRecordAnrMainStack(false);
            strategy.setAppReportDelay(20000L);
            CrashReport.initCrashReport(context, "80f289fe97", false, strategy);
            BuglyLog.setCache(16*1024);
            CrashReport.setIsDevelopmentDevice(context, BuildConfig.DEBUG);
        }
    }
}
