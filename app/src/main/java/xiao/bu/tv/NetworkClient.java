package xiao.bu.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.OkUrlFactory;

/** Shared HTTP stack with connection reuse and a user-selectable DNS resolver. */
final class NetworkClient {
    static final String DNS_ALI = "ali";
    static final String DNS_TENCENT = "tencent";
    static final String DNS_114 = "114";
    static final String DNS_BAIDU = "baidu";
    static final String DNS_SYSTEM = "system";
    static final String DEFAULT_DNS = DNS_ALI;
    private static final String PREF_DNS = "network_dns";

    private static Context context;
    private static OkHttpClient client;
    private static OkUrlFactory factory;

    private NetworkClient() {
    }

    static synchronized void initialize(Context value) {
        if (context == null && value != null) {
            context = value.getApplicationContext();
        }
    }

    static synchronized String getDnsMode() {
        if (context == null) {
            return DEFAULT_DNS;
        }
        return sanitizeDnsMode(context.getSharedPreferences(
                MainActivity.PREFERENCES, Context.MODE_PRIVATE)
                .getString(PREF_DNS, DEFAULT_DNS));
    }

    static synchronized void setDnsMode(String value) {
        String mode = sanitizeDnsMode(value);
        if (context == null) {
            throw new IllegalStateException("Network client is not initialized");
        }
        context.getSharedPreferences(MainActivity.PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(PREF_DNS, mode).apply();
        if (client != null) {
            client.connectionPool().evictAll();
            client.dispatcher().cancelAll();
        }
        client = null;
        factory = null;
    }

    static String sanitizeDnsMode(String value) {
        if (DNS_TENCENT.equals(value) || DNS_114.equals(value)
                || DNS_BAIDU.equals(value) || DNS_SYSTEM.equals(value)) {
            return value;
        }
        return DNS_ALI;
    }

    static HttpURLConnection open(URL url) throws IOException {
        return factory().open(url);
    }

    private static synchronized OkUrlFactory factory() {
        if (factory == null) {
            Dns dns = DNS_SYSTEM.equals(getDnsMode())
                    ? Dns.SYSTEM : new PublicDns(getDnsMode());
            OkHttpClient.Builder builder = new OkHttpClient.Builder()
                    .dns(dns)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .writeTimeout(20, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .connectionPool(new ConnectionPool(8, 5, TimeUnit.MINUTES));
            if (TlsCompat.socketFactory() != null && TlsCompat.trustManager() != null) {
                builder.sslSocketFactory(TlsCompat.socketFactory(), TlsCompat.trustManager());
            }
            if (TlsCompat.hostnameVerifier() != null) {
                builder.hostnameVerifier(TlsCompat.hostnameVerifier());
            }
            client = builder.build();
            factory = new OkUrlFactory(client);
        }
        return factory;
    }
}
