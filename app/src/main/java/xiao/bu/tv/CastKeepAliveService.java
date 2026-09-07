package xiao.bu.tv;

import android.app.Activity;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/** Same-process foreground lease for the existing renderer, encoder and LAN session. */
public final class CastKeepAliveService extends Service {
    private static final String TAG = "CastKeepAlive";
    private static final String CHANNEL = "cast_connection";
    private static final String STOP = "xiao.bu.tv.STOP_BACKGROUND_CAST";
    static final String OPEN_MANAGEMENT = "xiao.bu.tv.OPEN_CAST_MANAGEMENT";
    private static final int NOTIFICATION_ID = 9967;
    private static final CastBackgroundLease LEASE = new CastBackgroundLease();
    private static final Set<Activity> STARTED = new HashSet<Activity>();
    private static WeakReference<MainActivity> owner = new WeakReference<MainActivity>(null);
    private static volatile CastKeepAliveService instance;
    private static boolean projectionActive;
    private static boolean registered;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private boolean backgroundLocks;

    // All lifecycle/session writes run on the main thread. Only stateJson is read by HTTP.
    static void attach(MainActivity activity) {
        owner = new WeakReference<MainActivity>(activity);
        if (registered) return;
        registered = true;
        activity.getApplication().registerActivityLifecycleCallbacks(
                new Application.ActivityLifecycleCallbacks() {
                    @Override public void onActivityStarted(Activity activity) {
                        STARTED.add(activity);
                        foregroundChanged();
                    }
                    @Override public void onActivityStopped(Activity activity) {
                        STARTED.remove(activity);
                        foregroundChanged();
                    }
                    @Override public void onActivityDestroyed(Activity activity) {
                        if (STARTED.remove(activity)) foregroundChanged();
                    }
                    @Override public void onActivityCreated(Activity a, Bundle b) { }
                    @Override public void onActivityResumed(Activity a) { }
                    @Override public void onActivityPaused(Activity a) { }
                    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
                });
    }

    private static void foregroundChanged() {
        LEASE.setForeground(!STARTED.isEmpty(), SystemClock.elapsedRealtime());
        if (instance != null) instance.refresh();
    }

    static void setActive(MainActivity activity, boolean active) {
        if (Build.VERSION.SDK_INT < 21 || owner.get() != activity) return;
        boolean changed = active != LEASE.isActive();
        LEASE.setActive(active, SystemClock.elapsedRealtime());
        if (!changed) return;
        Intent service = new Intent(activity, CastKeepAliveService.class);
        if (!active) {
            if (instance != null) {
                instance.releaseLocks();
                instance.stopForeground(true);
            }
            activity.stopService(service);
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(service);
            else activity.startService(service);
        } catch (RuntimeException error) {
            LEASE.setActive(false, SystemClock.elapsedRealtime());
            Log.e(TAG, "Cannot protect cast in background", error);
            // Do not claim a protected session if the OS rejected the service.
            activity.stopBackgroundCast("无法启动后台投送服务，请保持应用在前台后重试");
        }
    }

    static void detach(MainActivity activity) {
        if (owner.get() != activity) return;
        setActive(activity, false);
        owner.clear();
    }

    static JSONObject stateJson() throws JSONException {
        return new JSONObject().put("active", LEASE.isActive() && instance != null)
                .put("background", LEASE.isBackground())
                .put("remainingMs", -1L)
                .put("graceMs", 0L)
                .put("timeoutEnabled", false);
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "投屏与互联",
                    NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .createNotificationChannel(channel);
        }
        // Meet startForegroundService's deadline even if the session was just stopped.
        promote();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            stopSession("已停止投屏与接管");
        } else if (!LEASE.isActive() || owner.get() == null) {
            stopSelf();
        } else {
            promote();
            refresh();
        }
        // A dead UI/encoder cannot be reconstructed by restarting this service alone.
        return START_NOT_STICKY;
    }

    static void setProjectionActive(boolean active) {
        projectionActive = active;
        if (instance != null && LEASE.isActive()) instance.promote();
    }

    static void deliverAudioConsent(int resultCode, Intent data) {
        MainActivity activity = owner.get();
        if (activity != null) activity.onCastAudioConsentResult(resultCode, data);
    }

    static MainActivity localInputOwner() {
        return owner.get();
    }

    private void promote() {
        if (Build.VERSION.SDK_INT >= 29) {
            int types = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            if (projectionActive) types |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            startForeground(NOTIFICATION_ID, notification(), types);
        } else startForeground(NOTIFICATION_ID, notification());
    }

    private Notification notification() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        // Reuse MainActivity's startActivityForResult flow, so Back still exits takeover.
        Intent manage = new Intent(this, MainActivity.class).setAction(OPEN_MANAGEMENT)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 0, manage, flags);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, CastKeepAliveService.class).setAction(STOP), flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("投屏与互联正在运行")
                .setContentText(LEASE.isBackground()
                        ? "屏幕关闭或应用在后台时继续投送"
                        : "网页投送与控制连接已保护")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(android.R.drawable.ic_media_pause, "停止投送", stop).build();
    }

    private void refresh() {
        if (!LEASE.isActive()) {
            releaseLocks();
            return;
        }
        acquireLocks();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIFICATION_ID, notification());
    }

    private void acquireLocks() {
        if (backgroundLocks) return;
        backgroundLocks = true;
        try {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nTv:cast-session");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifi != null) {
                int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifiLock = wifi.createWifiLock(mode, "nTv:cast-session");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Background power lock unavailable", error);
        }
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        wakeLock = null;
        wifiLock = null;
        backgroundLocks = false;
    }

    private void stopSession(String reason) {
        Log.i(TAG, reason);
        MainActivity activity = owner.get();
        // First close the lease: cleanup can synchronously report intermediate cast states.
        LEASE.setActive(false, SystemClock.elapsedRealtime());
        try {
            if (activity != null) activity.stopBackgroundCast(reason);
        } finally {
            releaseLocks();
            stopForeground(true);
            stopSelf();
        }
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        stopSession("已关闭应用，停止投送");
    }

    @Override public void onDestroy() {
        releaseLocks();
        stopForeground(true);
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
