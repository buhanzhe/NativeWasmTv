package xiao.bu.tv;

import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Enables modern TLS protocols and intentionally accepts every HTTPS certificate. */
final class TlsCompat {
    private static final String TAG = "TlsCompat";
    private static boolean installed;

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
            context.init(null, new TrustManager[] {new TrustAllManager()}, new SecureRandom());
            SSLSocketFactory socketFactory = context.getSocketFactory();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN
                    && Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                socketFactory = new ModernTlsSocketFactory(socketFactory);
            }
            HttpsURLConnection.setDefaultSSLSocketFactory(socketFactory);
            HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            });
            Log.w(TAG, "HTTPS certificate and hostname verification disabled for Android "
                    + Build.VERSION.RELEASE);
        } catch (Exception error) {
            Log.e(TAG, "Unable to install trust-all HTTPS compatibility", error);
        }
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

    private static final class ModernTlsSocketFactory extends SSLSocketFactory {
        private static final String[] MODERN_PROTOCOLS =
                new String[] {"TLSv1.2", "TLSv1.1", "TLSv1"};
        // Android 4.4 supports TLS 1.2 but leaves it and several ECDHE suites disabled by
        // default. gh-proxy.com currently negotiates from this modern/common subset.
        private static final String[] MODERN_CIPHER_SUITES = new String[] {
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
                "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
                "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
                "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
                "TLS_DHE_RSA_WITH_AES_128_CBC_SHA",
                "TLS_DHE_RSA_WITH_AES_256_CBC_SHA",
                "TLS_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_RSA_WITH_AES_128_CBC_SHA256",
                "TLS_RSA_WITH_AES_256_CBC_SHA256",
                "TLS_RSA_WITH_AES_128_CBC_SHA",
                "TLS_RSA_WITH_AES_256_CBC_SHA"
        };
        private final SSLSocketFactory delegate;

        ModernTlsSocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket socket, String host, int port, boolean autoClose)
                throws IOException {
            return enableModernTls(delegate.createSocket(socket, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return enableModernTls(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port,
                InetAddress localAddress, int localPort) throws IOException {
            return enableModernTls(delegate.createSocket(host, port, localAddress, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return enableModernTls(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port,
                InetAddress localAddress, int localPort) throws IOException {
            return enableModernTls(delegate.createSocket(
                    address, port, localAddress, localPort));
        }

        private static Socket enableModernTls(Socket socket) {
            if (!(socket instanceof SSLSocket)) {
                return socket;
            }
            SSLSocket sslSocket = (SSLSocket) socket;
            String[] supported = sslSocket.getSupportedProtocols();
            List<String> enabled = new ArrayList<String>(MODERN_PROTOCOLS.length);
            for (String protocol : MODERN_PROTOCOLS) {
                if (contains(supported, protocol)) {
                    enabled.add(protocol);
                }
            }
            if (!enabled.isEmpty()) {
                sslSocket.setEnabledProtocols(enabled.toArray(new String[enabled.size()]));
            }
            String[] supportedCipherSuites = sslSocket.getSupportedCipherSuites();
            List<String> enabledCipherSuites = new ArrayList<String>(
                    MODERN_CIPHER_SUITES.length);
            for (String cipherSuite : MODERN_CIPHER_SUITES) {
                if (contains(supportedCipherSuites, cipherSuite)) {
                    enabledCipherSuites.add(cipherSuite);
                }
            }
            // Preserve vendor-enabled suites as fallbacks for the existing stream hosts.
            for (String cipherSuite : sslSocket.getEnabledCipherSuites()) {
                if (!enabledCipherSuites.contains(cipherSuite)) {
                    enabledCipherSuites.add(cipherSuite);
                }
            }
            if (!enabledCipherSuites.isEmpty()) {
                sslSocket.setEnabledCipherSuites(enabledCipherSuites.toArray(
                        new String[enabledCipherSuites.size()]));
            }
            return sslSocket;
        }

        private static boolean contains(String[] values, String target) {
            for (String value : values) {
                if (target.equals(value)) {
                    return true;
                }
            }
            return false;
        }
    }
}
