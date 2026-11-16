package xiao.bu.tv;
import android.content.Context;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
/** Uses the public application's TLS/DNS implementation, with no browser JNI. */
final class LiteHttp {
    static final String USER_AGENT="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36";
    private static OkHttpClient shared;
    private static Dns sharedDns;
    static synchronized OkHttpClient client(Context context,Dns dns) {
        LiteCookies.PROFILE.initialize(context);
        Dns resolver=dns==null?LegacySystemDns.create():dns;
        if(shared==null||sharedDns!=resolver){
            shared=NetworkClient.sharedClient().newBuilder().dns(resolver)
                    .cache(LiteEngine.httpCache(context)).cookieJar(LiteCookies.PROFILE)
                    .addInterceptor(chain->{okhttp3.Request r=chain.request();return chain.proceed(r.header("User-Agent")==null?r.newBuilder().header("User-Agent",USER_AGENT).build():r);}).build();
            sharedDns=resolver;
        }
        return shared;
    }
    private LiteHttp() {}
}
