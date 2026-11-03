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
    static final String DEFAULT_DNS = DNS_SYSTEM;
    private static final String PREF_DNS = "network_dns";

    private static Context context;
    private static OkHttpClient client;
    private static OkUrlFactory factory;
    private static OkUrlFactory http1Factory;
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>
            http1Positions = new java.util.concurrent.ConcurrentHashMap<>();

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
        // Saving unrelated settings must not cancel active playback requests.
        if (mode.equals(getDnsMode())) return;
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
        if (DNS_ALI.equals(value) || DNS_TENCENT.equals(value) || DNS_114.equals(value)
                || DNS_BAIDU.equals(value) || DNS_SYSTEM.equals(value)) {
            return value;
        }
        return DEFAULT_DNS;
    }

    static HttpURLConnection open(URL url) throws IOException {
        String github = GithubProxy.githubSource(url.toString());
        if (github != null) {
            if (!GithubProxy.isEnabled()) return factory().open(new URL(github));
            return new GithubConnection(url, target -> factory().open(target));
        }
        return factory().open(url);
    }

    static HttpURLConnection openBounded(URL url, int timeoutMs) throws IOException {
        return new OkUrlFactory(sharedClient().newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false).build()).open(url);
    }

    static void invalidateDns(String hostname) {
        Dns dns = sharedClient().dns();
        if (dns instanceof ProcessDns) ((ProcessDns) dns).invalidate(hostname);
    }

    /** Bound the whole component request, including redirects and internal connection retries. */
    static HttpURLConnection openComponent(URL url, boolean nativeBinary) throws IOException {
        return openComponent(url, false, nativeBinary ? 30000 : 8000);
    }

    static HttpURLConnection openComponent(URL url, boolean http1, int timeoutMs) throws IOException {
        if (timeoutMs <= 0) throw new java.net.SocketTimeoutException("Plugin download timeout");
        OkHttpClient.Builder builder=sharedClient().newBuilder().retryOnConnectionFailure(false);
        if(http1) builder.protocols(java.util.Collections.singletonList(okhttp3.Protocol.HTTP_1_1));
        final OkHttpClient transport=builder.build();
        final long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        GithubConnection.Opener opener=target -> {
            long remaining=deadline-System.nanoTime();
            if(remaining<=0) throw new java.net.SocketTimeoutException("Plugin download timeout");
            return new OkUrlFactory(transport.newBuilder().callTimeout(remaining,TimeUnit.NANOSECONDS)
                    .build()).open(target);
        };
        String github=GithubProxy.githubSource(url.toString());
        if(github!=null) {
            if(!GithubProxy.isEnabled()) return opener.open(new URL(github));
            return new GithubConnection(url,opener);
        }
        return opener.open(url);
    }

    /** Shared OkHttp client for callers that need response headers or streaming bodies. */
    static synchronized OkHttpClient sharedClient() {
        factory();
        return client;
    }

    static synchronized HttpURLConnection openHttp1(URL url) throws IOException {
        return openHttp1(url, 0);
    }

    /** Python-style short HTTP/1 connections must not stay pinned to one CDN address. */
    static synchronized HttpURLConnection openHttp1(URL url, int timeoutMs) throws IOException {
        factory();
        if (http1Factory == null) {
            final Dns cachedDns = client.dns();
            http1Positions.clear();
            final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>
                    positions = http1Positions;
            Dns rotatingDns = hostname -> {
                java.util.List<java.net.InetAddress> addresses = cachedDns.lookup(hostname);
                java.util.concurrent.atomic.AtomicInteger position = positions.get(hostname);
                if (position == null) {
                    java.util.concurrent.atomic.AtomicInteger candidate = new java.util.concurrent.atomic.AtomicInteger();
                    position = positions.putIfAbsent(hostname, candidate);
                    if (position == null) position = candidate;
                }
                java.util.List<java.net.InetAddress> ordered = new java.util.ArrayList<>(addresses);
                java.util.Collections.rotate(ordered, -(position.get() & Integer.MAX_VALUE) % ordered.size());
                return ordered;
            };
            http1Factory = new OkUrlFactory(client.newBuilder().dns(rotatingDns)
                    .protocols(java.util.Collections.singletonList(okhttp3.Protocol.HTTP_1_1)).build());
        }
        final OkUrlFactory transport = http1Factory;
        final long deadline = timeoutMs > 0 ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs) : 0;
        GithubConnection.Opener opener = target -> {
            if (deadline == 0) return transport.open(target);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new java.net.SocketTimeoutException("HTTP request timeout");
            return new OkUrlFactory(transport.client().newBuilder()
                    .callTimeout(remaining, TimeUnit.NANOSECONDS).build()).open(target);
        };
        String github = GithubProxy.githubSource(url.toString());
        if (github != null) {
            if (!GithubProxy.isEnabled()) return opener.open(new URL(github));
            return new GithubConnection(url, opener);
        }
        return new GithubConnection(url, opener, target -> {
            String host = target.getHost();
            if (transport.client().dns().lookup(host).size() <= 1) return false;
            java.util.concurrent.atomic.AtomicInteger position = http1Positions.get(host);
            if (position == null) return false;
            position.incrementAndGet();
            return true;
        });
    }

    private static synchronized OkUrlFactory factory() {
        if (factory == null) {
            http1Factory = null;
            Dns dns = DNS_SYSTEM.equals(getDnsMode())
                    ? LegacySystemDns.create() : new PublicDns(getDnsMode());
            OkHttpClient.Builder builder = new OkHttpClient.Builder()
                    .dns(new ProcessDns(dns))
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
