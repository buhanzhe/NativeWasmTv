package xiao.bu.tv.jar;

import android.content.Context;
import android.view.View;
import org.json.JSONObject;

/** API 1. create/update/dispose and Host callbacks run on the Android main thread. */
public interface ChannelPlugin {
    interface Host {
        void play(String url);
        void status(String message);
        void configureBrowser(android.webkit.WebView webView);
    }
    interface BrowserHost extends Host {
        void chooseFiles(android.webkit.ValueCallback<android.net.Uri[]> callback,String accept,boolean multiple);
    }
    interface DirectoryHost extends BrowserHost {
        void chooseDirectory(android.webkit.ValueCallback<android.net.Uri[]> callback);
    }
    /** Native View plugins share the application's TLS, DNS and connection pool. */
    interface NativeHost extends DirectoryHost {
        // The value is the host's okhttp3.OkHttpClient; keep baseline API-1 builds transport-free.
        Object httpClient();
    }
    /** Optional access to the host's fullscreen preference. */
    interface FullscreenHost extends NativeHost {
        boolean isFullscreenEnabled();
        void setFullscreenEnabled(boolean enabled);
    }
    /** Optional native navigation and foreground lifecycle, independent of Activities. */
    interface NativePage {
        boolean onBack();
        void setActive(boolean active);
    }
    View create(Context context, ClassLoader resources, JSONObject config, Host host) throws Exception;
    void update(long positionMs, boolean playing);
    void dispose();
}
