package xiao.bu.tv;

import android.content.Context;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import okhttp3.Cache;
import okhttp3.Dns;
import org.json.JSONObject;

/** Public compile bridge for editions without the optional browser runtime. */
public final class LiteEngine implements Closeable {
    public interface DocumentScripts { List<String> scripts(Context context,String page); }
    public interface PlaybackTransport extends Closeable { String url(); }
    public interface Listener {
        void frame(JSONObject packet);
        void message(String message);
        void navigate(String url);
        void media(String url,Map<String,String> headers);
        default void temporaryMedia(String url,Map<String,String> headers) {}
        default void mediaControl(String source,boolean virtualMse,boolean paused) {}
        default void textInput(JSONObject state) {}
    }
    private static LiteHostPolicy policy=new LiteHostPolicy(){};
    private static Cache cache;
    public static void installDocumentScripts(DocumentScripts scripts) {}
    public static void installHostPolicy(LiteHostPolicy value) { policy=value==null?new LiteHostPolicy(){}:value; }
    public static boolean keepsOriginalMediaPipeline(String page) { return policy.preserveMediaPipeline(page); }
    public static boolean sameHlsSessionSource(String first,String second) { return first!=null&&first.equals(second); }
    public static String defaultUserAgent(String page) { return LiteHttp.USER_AGENT; }
    static synchronized Cache httpCache(Context context) {
        if(cache==null)cache=new Cache(new File(context.getCacheDir(),"lite-http"),32L*1024*1024);
        return cache;
    }
    public static int networkThreadCount(int multiplier) {
        if(multiplier!=0&&multiplier!=2&&multiplier!=4&&multiplier!=8)throw new IllegalArgumentException("Unsupported network thread multiplier");
        int base=Math.max(2,Math.min(8,Runtime.getRuntime().availableProcessors()));
        return multiplier==0?Math.max(4,base):Math.min(32,base*multiplier);
    }
    private static UnsupportedOperationException unavailable() { return new UnsupportedOperationException("This edition does not bundle the lightweight browser"); }
    public LiteEngine(Context context,Listener listener) { throw unavailable(); }
    public LiteEngine(Context context,Listener listener,Dns dns) { throw unavailable(); }
    public static LiteEngine withNetworkThreadMultiplier(Context c,Listener l,Dns dns,int n) { throw unavailable(); }
    static LiteEngine forPreparse(Context c,Listener l,Dns dns,int n) { throw unavailable(); }
    static LiteEngine forHealthProbe(Context c,Listener l,Dns dns) { throw unavailable(); }
    public void setAuditMemoryMiB(int value) { throw unavailable(); }
    public void setHeadless(boolean value) { throw unavailable(); }
    public void setRendering(boolean value) { throw unavailable(); }
    public void loadImages(boolean value) { throw unavailable(); }
    public void userAgentOverride(String value) { throw unavailable(); }
    public String userAgent() { return LiteHttp.USER_AGENT; }
    public void load(String url) { throw unavailable(); }
    public void load(String url,String script) { throw unavailable(); }
    public void evaluate(String script) { throw unavailable(); }
    public void traceRequests(boolean value) { throw unavailable(); }
    public void viewport(int width,int height,int scroll) { throw unavailable(); }
    public int viewportWidth() { return 0; }
    public int viewportHeight() { return 0; }
    public void renderPage() { throw unavailable(); }
    public void parkPreparse() { throw unavailable(); }
    public void promotePreparse() { throw unavailable(); }
    public boolean keepsMediaSession(String source) { return false; }
    public PlaybackTransport playbackTransport(String source,Map<String,String> headers)throws IOException { throw unavailable(); }
    public void directPlaybackTime(String source,double time,int width,int height) { throw unavailable(); }
    public void playbackTime(double time,int width,int height) { throw unavailable(); }
    public void temporaryPlayback(double time,double duration,boolean ended,boolean error) { throw unavailable(); }
    public JSONObject startupTimings() { return new JSONObject(); }
    public JSONObject runtimeBusySnapshot() { return new JSONObject(); }
    public LiteStartupState startupState() { return new LiteStartupState(0,0,0,0,false,false,false); }
    public long upstreamDownloadedBytes() { return 0; }
    public void close() {}
}
