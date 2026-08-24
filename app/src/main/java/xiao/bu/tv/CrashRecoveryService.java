package xiao.bu.tv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

public final class CrashRecoveryService extends Service {
    static final String ACTION_ARM = "xiao.bu.tv.action.ARM_CRASH_RECOVERY";
    static final String ACTION_DISARM = "xiao.bu.tv.action.DISARM_CRASH_RECOVERY";
    static final String EXTRA_RECOVERED = "crash_recovered";

    private static final String TAG = "CrashRecovery";
    private static final String PREFS = "crash_recovery";
    private static final String LAST_RECOVERY_AT = "last_recovery_at";
    private static final String RECOVERY_COUNT = "recovery_count";
    private static final long RECOVERY_WINDOW_MS = 60000L;
    private static final int MAX_RECOVERIES_PER_WINDOW = 3;
    private static final long BINDER_DEATH_GRACE_MS = 1200L;
    private static final long PROCESS_STOP_GRACE_MS = 400L;

    private final IBinder binder = new Binder();
    private Handler handler;
    private boolean armed;
    private boolean clientBound;
    private final Runnable recoverAfterClientDeath = new Runnable() {
        @Override
        public void run() {
            if (armed && !clientBound) {
                scheduleActivityRecovery();
            }
        }
    };
    private final Runnable stopUnusedWatchdogProcess = new Runnable() {
        @Override
        public void run() {
            if (!armed && !clientBound) {
                Process.killProcess(Process.myPid());
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            armed = false;
            stopSelf(startId);
            handler.removeCallbacks(stopUnusedWatchdogProcess);
            handler.postDelayed(stopUnusedWatchdogProcess, PROCESS_STOP_GRACE_MS);
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_DISARM.equals(action)) {
            armed = false;
            handler.removeCallbacks(recoverAfterClientDeath);
            if (!clientBound) {
                stopSelf();
                handler.removeCallbacks(stopUnusedWatchdogProcess);
                handler.postDelayed(stopUnusedWatchdogProcess, PROCESS_STOP_GRACE_MS);
            }
            return START_NOT_STICKY;
        }
        armed = true;
        handler.removeCallbacks(stopUnusedWatchdogProcess);
        cancelPendingRecovery();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        armed = true;
        clientBound = true;
        handler.removeCallbacks(stopUnusedWatchdogProcess);
        handler.removeCallbacks(recoverAfterClientDeath);
        return binder;
    }

    @Override
    public void onRebind(Intent intent) {
        armed = true;
        clientBound = true;
        handler.removeCallbacks(stopUnusedWatchdogProcess);
        handler.removeCallbacks(recoverAfterClientDeath);
        super.onRebind(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        clientBound = false;
        handler.removeCallbacks(recoverAfterClientDeath);
        if (armed) {
            handler.postDelayed(recoverAfterClientDeath, BINDER_DEATH_GRACE_MS);
        } else {
            stopSelf();
            handler.removeCallbacks(stopUnusedWatchdogProcess);
            handler.postDelayed(stopUnusedWatchdogProcess, PROCESS_STOP_GRACE_MS);
        }
        return true;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Removing the task is an explicit user action, not a crash.
        armed = false;
        handler.removeCallbacks(recoverAfterClientDeath);
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    private void scheduleActivityRecovery() {
        long now = System.currentTimeMillis();
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        long lastAt = preferences.getLong(LAST_RECOVERY_AT, 0L);
        int count = now - lastAt >= 0L && now - lastAt < RECOVERY_WINDOW_MS
                ? preferences.getInt(RECOVERY_COUNT, 0) + 1 : 1;
        preferences.edit().putLong(LAST_RECOVERY_AT, now)
                .putInt(RECOVERY_COUNT, count).apply();
        if (count > MAX_RECOVERIES_PER_WINDOW) {
            Log.e(TAG, "Crash recovery stopped to avoid a restart loop count=" + count);
            armed = false;
            stopSelf();
            return;
        }

        Intent launch = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_RECOVERED, true);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pending = PendingIntent.getActivity(this, 8091, launch, pendingFlags);
        try {
            // This is immediate on older TV systems. Newer Android versions may defer
            // background activity launches, so keep the alarm below as a fallback.
            pending.send();
        } catch (PendingIntent.CanceledException error) {
            Log.w(TAG, "Immediate crash recovery launch was cancelled", error);
        }
        AlarmManager alarms = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (alarms != null) {
            alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 5000L, pending);
            Log.w(TAG, "Scheduled app recovery after main process exit count=" + count);
        }
        armed = false;
        stopSelf();
    }

    private void cancelPendingRecovery() {
        int flags = PendingIntent.FLAG_NO_CREATE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pending = PendingIntent.getActivity(this, 8091,
                new Intent(this, MainActivity.class), flags);
        if (pending == null) {
            return;
        }
        AlarmManager alarms = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (alarms != null) {
            alarms.cancel(pending);
        }
        pending.cancel();
    }
}
