package xiao.bu.tv;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

/** Wi-Fi power policy is scoped to an active cast, not normal channel playback. */
final class CastNetworkLease {
    private WifiManager.WifiLock lock;
    void acquire(Context context) {
        if (lock != null) return;
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return;
            int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            lock = wifi.createWifiLock(mode, "nTv:interactive-cast");
            lock.setReferenceCounted(false);
            lock.acquire();
        } catch (RuntimeException error) {
            lock = null;
            Log.w("CastNetworkLease", "Wi-Fi latency lock unavailable", error);
        }
    }
    void release() {
        if (lock == null) return;
        try { if (lock.isHeld()) lock.release(); }
        catch (RuntimeException error) { Log.w("CastNetworkLease", "Wi-Fi lock release", error); }
        finally { lock = null; }
    }
}
