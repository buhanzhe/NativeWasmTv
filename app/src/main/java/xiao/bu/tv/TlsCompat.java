package xiao.bu.tv;

import android.os.Build;
import android.util.Log;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Enables modern TLS protocols and intentionally accepts every HTTPS certificate. */
final class TlsCompat {
    private static final String TAG = "TlsCompat";
    private static boolean installed;
    private static SSLSocketFactory installedSocketFactory;
    private static X509TrustManager installedTrustManager;
    private static HostnameVerifier installedHostnameVerifier;

    private TlsCompat() {
    }

    static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;

        // HttpURLConnection pools HTTPS connections by default. A slightly larger pool keeps
        // playlist and segment handshakes from repeatedly competing on Android 4.x.
        System.setProperty("http.keepAlive", "true");
        System.setProperty("http.maxConnections", "8");

        try {
            SSLContext context = SSLContext.getInstance("TLS");
            installedTrustManager = new TrustAllManager();
            context.init(null, new TrustManager[] {installedTrustManager}, new SecureRandom());
            SSLSocketFactory socketFactory = context.getSocketFactory();
            // Pre-Lollipop TLS providers lack GCM suites required by current stream CDNs.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                socketFactory = new LegacyTlsSocket.Factory(installedTrustManager);
            }
            installedSocketFactory = socketFactory;
            installedHostnameVerifier = new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            };
            HttpsURLConnection.setDefaultSSLSocketFactory(installedSocketFactory);
            HttpsURLConnection.setDefaultHostnameVerifier(installedHostnameVerifier);
            Log.w(TAG, "HTTPS certificate and hostname verification disabled for Android "
                    + Build.VERSION.RELEASE);
        } catch (Exception error) {
            Log.e(TAG, "Unable to install trust-all HTTPS compatibility", error);
        }
    }

    static synchronized SSLSocketFactory socketFactory() {
        install();
        return installedSocketFactory;
    }

    static synchronized X509TrustManager trustManager() {
        install();
        return installedTrustManager;
    }

    static synchronized HostnameVerifier hostnameVerifier() {
        install();
        return installedHostnameVerifier;
    }

    private static final class TrustAllManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

}
