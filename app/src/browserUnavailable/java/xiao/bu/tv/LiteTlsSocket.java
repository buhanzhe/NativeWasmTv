package xiao.bu.tv;
import javax.net.ssl.SSLSocketFactory;
/** Type marker retained by the resource bridge; standard editions use TlsCompat. */
final class LiteTlsSocket {
    abstract static class Factory extends SSLSocketFactory {}
    private LiteTlsSocket() {}
}
