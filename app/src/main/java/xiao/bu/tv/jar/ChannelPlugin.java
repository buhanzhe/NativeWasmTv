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
    /** Optional shared playback HUD. A new session identity resets the host's traffic samples. */
    interface PlaybackHost extends FullscreenHost {
        boolean isPlaybackDebugEnabled();
        void setPlaybackDebugEnabled(boolean enabled);
        // Main-thread snapshot. downloadedBytes counts upstream media reads for this session.
        // Pass null when the plugin leaves playback.
        void reportPlaybackDiagnostics(Object session, android.os.Bundle diagnostics);
    }
    interface VodHost extends NativeHost {
        void playVod(String url, JSONObject headers);
        JSONObject python(JSONObject request) throws Exception;
        void javascript(String source, ScriptHost host) throws Exception;
    }
    interface PlayerUiHost extends VodHost {
        PlayerUi createPlayerUi(PlayerControls playback);
    }
    interface PlayerControls {
        boolean playing();
        boolean completed();
        boolean loading();
        String status();
        String title();
        long duration();
        long position();
        void toggle();
        void seek(long position);
        float speed();
        void speed(float value);
    }
    interface PlayerUi {
        View view();
        void show();
        void hide();
        boolean visible();
        boolean surfaceGesture(android.view.MotionEvent event);
        void dispose();
    }
    interface ScriptHost {
        String invoke(int operation, String[] arguments) throws Exception;
        boolean isCancelled();
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
