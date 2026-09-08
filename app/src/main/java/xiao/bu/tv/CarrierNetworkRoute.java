package xiao.bu.tv;

import android.annotation.TargetApi;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/** Keeps carrier IPTV traffic on the SIM network when Wi-Fi is the default route. */
final class CarrierNetworkRoute implements Closeable {
    private final Api21Route api21;

    CarrierNetworkRoute(Context context) {
        api21 = Build.VERSION.SDK_INT >= 21 && context != null
                ? new Api21Route(context.getApplicationContext()) : null;
    }

    static boolean isCarrierIptvUrl(String sourceUrl) {
        if (sourceUrl == null) {
            return false;
        }
        String value = sourceUrl.trim().toLowerCase(java.util.Locale.US);
        return (value.startsWith("http://") || value.startsWith("https://"))
                && value.contains("/pltv/")
                && (value.contains("fmt=ts2hls") || value.contains("tenantid="));
    }

    void prepare() {
        if (api21 != null) {
            api21.prepare();
        }
    }

    HttpURLConnection openConnection(String sourceUrl, boolean preferCellular)
            throws IOException {
        if (preferCellular && api21 != null) {
            HttpURLConnection connection = api21.openConnection(sourceUrl);
            if (connection != null) {
                return connection;
            }
        }
        return NetworkClient.open(new URL(sourceUrl));
    }

    @Override
    public void close() {
        if (api21 != null) {
            api21.close();
        }
    }

    /** Isolated so Android 4.x never verifies references to android.net.Network. */
    @TargetApi(21)
    private static final class Api21Route implements Closeable {
        private static final String TAG = "CarrierNetworkRoute";
        private static final long NETWORK_WAIT_MS = 4000L;

        private final Object lock = new Object();
        private final ConnectivityManager connectivity;
        private ConnectivityManager.NetworkCallback callback;
        private volatile Network cellularNetwork;
        private volatile boolean requested;
        private volatile boolean waitExpired;
        private volatile boolean closed;

        Api21Route(Context context) {
            connectivity = (ConnectivityManager) context
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
        }

        void prepare() {
            if (connectivity == null || closed || activeNetworkIsCellular()) {
                return;
            }
            synchronized (lock) {
                if (requested || closed) {
                    return;
                }
                requested = true;
                callback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        cellularNetwork = network;
                        waitExpired = false;
                        synchronized (lock) {
                            lock.notifyAll();
                        }
                        Log.i(TAG, "Carrier IPTV cellular route available");
                    }

                    @Override
                    public void onLost(Network network) {
                        if (network.equals(cellularNetwork)) {
                            cellularNetwork = null;
                        }
                    }

                    @Override
                    public void onUnavailable() {
                        waitExpired = true;
                        synchronized (lock) {
                            lock.notifyAll();
                        }
                        Log.i(TAG, "Carrier IPTV cellular route unavailable; using default network");
                    }
                };
                try {
                    NetworkRequest request = new NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .build();
                    connectivity.requestNetwork(request, callback);
                } catch (RuntimeException error) {
                    Log.w(TAG, "Unable to request cellular route", error);
                    requested = false;
                    waitExpired = true;
                    callback = null;
                }
            }
        }

        HttpURLConnection openConnection(String sourceUrl) throws IOException {
            if (activeNetworkIsCellular()) {
                return null;
            }
            prepare();
            Network network = awaitCellularNetwork();
            return network == null ? null
                    : (HttpURLConnection) network.openConnection(new URL(sourceUrl));
        }

        private Network awaitCellularNetwork() {
            long deadline = SystemClock.elapsedRealtime() + NETWORK_WAIT_MS;
            synchronized (lock) {
                while (!closed && cellularNetwork == null && requested && !waitExpired) {
                    long remaining = deadline - SystemClock.elapsedRealtime();
                    if (remaining <= 0L) {
                        waitExpired = true;
                        Log.i(TAG, "Carrier IPTV cellular route timed out; using default network");
                        break;
                    }
                    try {
                        lock.wait(remaining);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                return cellularNetwork;
            }
        }

        @SuppressWarnings("deprecation")
        private boolean activeNetworkIsCellular() {
            try {
                NetworkInfo active = connectivity.getActiveNetworkInfo();
                return active != null && active.isConnected()
                        && active.getType() == ConnectivityManager.TYPE_MOBILE;
            } catch (RuntimeException error) {
                return false;
            }
        }

        @Override
        public void close() {
            closed = true;
            synchronized (lock) {
                lock.notifyAll();
            }
            ConnectivityManager.NetworkCallback activeCallback = callback;
            callback = null;
            cellularNetwork = null;
            if (connectivity != null && activeCallback != null) {
                try {
                    connectivity.unregisterNetworkCallback(activeCallback);
                } catch (RuntimeException ignored) {
                    // The platform may already have released an unavailable request.
                }
            }
        }
    }
}
