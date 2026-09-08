package xiao.bu.tv;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.security.Principal;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import javax.net.ssl.HandshakeCompletedEvent;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

/** Small TLS 1.2 client for API 14/15. TCP/DNS/timeouts remain owned by OkHttp. */
final class LegacyTlsSocket extends SSLSocket {
    private static final String[] CIPHERS = {
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384", "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA", "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
        "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA", "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA"
    };
    private static final class Library {
        static { System.loadLibrary("ntvtls"); }
        static void load() { }
    }

    static final class Factory extends SSLSocketFactory {
        private final X509TrustManager trust;
        Factory(X509TrustManager trust) { this.trust = trust; }
        @Override public String[] getDefaultCipherSuites() { return CIPHERS.clone(); }
        @Override public String[] getSupportedCipherSuites() { return CIPHERS.clone(); }
        @Override public Socket createSocket(Socket socket, String host, int port, boolean close) throws IOException {
            return new LegacyTlsSocket(socket, host, close, trust);
        }
        @Override public Socket createSocket(String host, int port) throws IOException {
            return createSocket(new Socket(host, port), host, port, true);
        }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
            return createSocket(new Socket(host, port, local, localPort), host, port, true);
        }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException {
            return createSocket(new Socket(host, port), host.getHostName(), port, true);
        }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException {
            return createSocket(new Socket(host, port, local, localPort), host.getHostName(), port, true);
        }
    }

    private final Socket transport;
    private final String host;
    private final boolean autoClose;
    private final X509TrustManager trust;
    private final Object engine = new Object();
    private final ArrayList<HandshakeCompletedListener> listeners = new ArrayList<HandshakeCompletedListener>();
    private final InputStream wireIn;
    private final OutputStream wireOut;
    private volatile boolean closed;
    private long handle;
    private Session session;
    private String[] ciphers = CIPHERS.clone();

    private LegacyTlsSocket(Socket transport, String host, boolean autoClose, X509TrustManager trust) throws IOException {
        this.transport = transport; this.host = host; this.autoClose = autoClose; this.trust = trust;
        wireIn = transport.getInputStream(); wireOut = transport.getOutputStream();
    }

    // JNI callbacks use a reusable 16 KiB array, never a callback per byte.
    private int transportRead(byte[] bytes, int length) throws IOException { return wireIn.read(bytes, 0, length); }
    private void transportWrite(byte[] bytes, int length) throws IOException { wireOut.write(bytes, 0, length); }
    private native long nativeCreate(String host, String[] suites) throws IOException;
    private native byte[][] nativeHandshake(long pointer) throws IOException;
    private native String nativeCipher(long pointer);
    private native int nativeRead(long pointer, byte[] data, int offset, int length) throws IOException;
    private native void nativeWrite(long pointer, byte[] data, int offset, int length) throws IOException;
    private native void nativeFree(long pointer);

    @Override public void startHandshake() throws IOException {
        synchronized (engine) {
            if (closed) throw new SocketException("TLS socket closed");
            if (session != null) return;
            long started = android.os.SystemClock.elapsedRealtime();
            try {
                Library.load();
                handle = nativeCreate(host, ciphers);
                if (handle == 0) throw new SSLException("Unable to allocate TLS client");
                byte[][] encoded = nativeHandshake(handle);
                X509Certificate[] chain = new X509Certificate[encoded.length];
                CertificateFactory certificates = CertificateFactory.getInstance("X.509");
                for (int i = 0; i < chain.length; i++) {
                    chain[i] = (X509Certificate) certificates.generateCertificate(new ByteArrayInputStream(encoded[i]));
                }
                trust.checkServerTrusted(chain, chain[0].getPublicKey().getAlgorithm());
                session = new Session(chain, nativeCipher(handle).replace('-', '_'));
                android.util.Log.i("LegacyTls", "TLS 1.2 host=" + host + " handshakeMs="
                        + (android.os.SystemClock.elapsedRealtime() - started));
            } catch (Exception error) {
                close();
                if (error instanceof IOException) throw (IOException) error;
                throw new SSLException(error);
            } catch (LinkageError error) {
                close();
                throw new SSLException("Missing or incompatible legacy TLS library", error);
            }
            for (HandshakeCompletedListener listener : new ArrayList<HandshakeCompletedListener>(listeners)) {
                listener.handshakeCompleted(new HandshakeCompletedEvent(this, session));
            }
        }
    }

    private static void bounds(byte[] data, int offset, int length) {
        if (data == null) throw new NullPointerException("data");
        if (offset < 0 || length < 0 || offset > data.length - length) throw new IndexOutOfBoundsException();
    }

    @Override public InputStream getInputStream() {
        return new InputStream() {
            @Override public int read() throws IOException {
                byte[] b = new byte[1]; return read(b, 0, 1) < 0 ? -1 : b[0] & 255;
            }
            @Override public int read(byte[] b, int offset, int length) throws IOException {
                bounds(b, offset, length); if (length == 0) return 0;
                synchronized (engine) { startHandshake(); return nativeRead(handle, b, offset, length); }
            }
            @Override public void close() throws IOException { LegacyTlsSocket.this.close(); }
        };
    }

    @Override public OutputStream getOutputStream() {
        return new OutputStream() {
            @Override public void write(int b) throws IOException { write(new byte[] {(byte)b}, 0, 1); }
            @Override public void write(byte[] b, int offset, int length) throws IOException {
                bounds(b, offset, length); if (length == 0) return;
                synchronized (engine) { startHandshake(); nativeWrite(handle, b, offset, length); }
            }
            @Override public void close() throws IOException { LegacyTlsSocket.this.close(); }
        };
    }

    @Override public void close() throws IOException {
        closed = true;
        try {
            // Unblock TCP before taking the lock held by an in-flight TLS read.
            if (autoClose) transport.close();
        } finally {
            synchronized (engine) {
                if (handle != 0) { nativeFree(handle); handle = 0; }
            }
        }
    }

    @Override protected void finalize() throws Throwable {
        try { close(); } finally { super.finalize(); }
    }

    @Override public SSLSession getSession() {
        try { startHandshake(); return session; }
        catch (IOException error) { return new Session(new X509Certificate[0], "SSL_NULL_WITH_NULL_NULL"); }
    }
    @Override public String[] getSupportedCipherSuites() { return CIPHERS.clone(); }
    @Override public String[] getEnabledCipherSuites() { return ciphers.clone(); }
    @Override public void setEnabledCipherSuites(String[] value) {
        if (value == null || value.length == 0) throw new IllegalArgumentException("No cipher suites");
        for (String suite : value) if (!Arrays.asList(CIPHERS).contains(suite)) throw new IllegalArgumentException(suite);
        synchronized (engine) { if (handle != 0) throw new IllegalStateException("Handshake already started"); ciphers = value.clone(); }
    }
    @Override public String[] getSupportedProtocols() { return new String[] {"TLSv1.2"}; }
    @Override public String[] getEnabledProtocols() { return getSupportedProtocols(); }
    @Override public void setEnabledProtocols(String[] value) {
        if (value == null || value.length != 1 || !"TLSv1.2".equals(value[0])) throw new IllegalArgumentException("TLSv1.2 required");
    }
    @Override public void addHandshakeCompletedListener(HandshakeCompletedListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener");
        synchronized (engine) { listeners.add(listener); }
    }
    @Override public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) {
        synchronized (engine) { if (!listeners.remove(listener)) throw new IllegalArgumentException("listener"); }
    }
    @Override public void setUseClientMode(boolean mode) { if (!mode) throw new IllegalArgumentException("Client only"); }
    @Override public boolean getUseClientMode() { return true; }
    @Override public void setNeedClientAuth(boolean need) { if (need) throw new IllegalArgumentException("No client credentials"); }
    @Override public boolean getNeedClientAuth() { return false; }
    @Override public void setWantClientAuth(boolean want) { if (want) throw new IllegalArgumentException("No client credentials"); }
    @Override public boolean getWantClientAuth() { return false; }
    @Override public void setEnableSessionCreation(boolean flag) { if (!flag) throw new IllegalArgumentException("New session required"); }
    @Override public boolean getEnableSessionCreation() { return true; }
    @Override public boolean isClosed() { return closed || transport.isClosed(); }
    @Override public boolean isConnected() { return transport.isConnected(); }
    @Override public boolean isInputShutdown() { return transport.isInputShutdown(); }
    @Override public boolean isOutputShutdown() { return transport.isOutputShutdown(); }
    @Override public InetAddress getInetAddress() { return transport.getInetAddress(); }
    @Override public InetAddress getLocalAddress() { return transport.getLocalAddress(); }
    @Override public int getPort() { return transport.getPort(); }
    @Override public int getLocalPort() { return transport.getLocalPort(); }
    @Override public SocketAddress getRemoteSocketAddress() { return transport.getRemoteSocketAddress(); }
    @Override public SocketAddress getLocalSocketAddress() { return transport.getLocalSocketAddress(); }
    @Override public void setSoTimeout(int timeout) throws SocketException { transport.setSoTimeout(timeout); }
    @Override public int getSoTimeout() throws SocketException { return transport.getSoTimeout(); }
    @Override public void setTcpNoDelay(boolean on) throws SocketException { transport.setTcpNoDelay(on); }
    @Override public boolean getTcpNoDelay() throws SocketException { return transport.getTcpNoDelay(); }

    private final class Session implements SSLSession {
        private final X509Certificate[] chain;
        private final String cipher;
        private final long created = System.currentTimeMillis();
        Session(X509Certificate[] chain, String cipher) { this.chain = chain; this.cipher = cipher; }
        @Override public String getCipherSuite() { return cipher; }
        @Override public String getProtocol() { return chain.length == 0 ? "NONE" : "TLSv1.2"; }
        @Override public String getPeerHost() { return host; }
        @Override public int getPeerPort() { return transport.getPort(); }
        @Override public int getPacketBufferSize() { return 18432; }
        @Override public int getApplicationBufferSize() { return 16384; }
        @Override public Certificate[] getPeerCertificates() throws SSLPeerUnverifiedException {
            if (chain.length == 0) throw new SSLPeerUnverifiedException("No authenticated peer");
            return chain.clone();
        }
        @Override public javax.security.cert.X509Certificate[] getPeerCertificateChain() throws SSLPeerUnverifiedException {
            getPeerCertificates();
            try {
                javax.security.cert.X509Certificate[] result = new javax.security.cert.X509Certificate[chain.length];
                for (int i = 0; i < result.length; i++) result[i] = javax.security.cert.X509Certificate.getInstance(chain[i].getEncoded());
                return result;
            } catch (Exception error) { throw new SSLPeerUnverifiedException(error.toString()); }
        }
        @Override public Principal getPeerPrincipal() throws SSLPeerUnverifiedException { getPeerCertificates(); return chain[0].getSubjectX500Principal(); }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public Principal getLocalPrincipal() { return null; }
        @Override public byte[] getId() { return new byte[0]; }
        @Override public SSLSessionContext getSessionContext() { return null; }
        @Override public long getCreationTime() { return created; }
        @Override public long getLastAccessedTime() { return created; }
        @Override public boolean isValid() { return chain.length > 0 && !closed; }
        @Override public void invalidate() { }
        @Override public void putValue(String name, Object value) { throw new UnsupportedOperationException(); }
        @Override public Object getValue(String name) { return null; }
        @Override public void removeValue(String name) { }
        @Override public String[] getValueNames() { return new String[0]; }
    }
}
