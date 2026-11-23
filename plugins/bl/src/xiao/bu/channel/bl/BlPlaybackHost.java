package xiao.bu.channel.bl;

import android.os.Bundle;
import java.lang.reflect.Method;
import xiao.bu.tv.jar.ChannelPlugin;

/** Binds the optional diagnostics interface without linking it on older hosts. */
final class BlPlaybackHost {
    private final ChannelPlugin.Host host;
    private final Method report, debugEnabled, setDebugEnabled;

    private BlPlaybackHost(ChannelPlugin.Host host, Class<?> api) throws Exception {
        this.host = host;
        report = api.getMethod("reportPlaybackDiagnostics", Object.class, Bundle.class);
        debugEnabled = api.getMethod("isPlaybackDebugEnabled");
        setDebugEnabled = api.getMethod("setPlaybackDebugEnabled", boolean.class);
    }

    static BlPlaybackHost connect(ChannelPlugin.Host host) {
        try {
            Class<?> api = Class.forName("xiao.bu.tv.jar.ChannelPlugin$PlaybackHost", false,
                    ChannelPlugin.class.getClassLoader());
            return api.isInstance(host) ? new BlPlaybackHost(host, api) : null;
        } catch (Exception | LinkageError unavailable) {
            return null;
        }
    }

    boolean debugEnabled() { return Boolean.TRUE.equals(call(debugEnabled)); }
    void setDebugEnabled(boolean enabled) { call(setDebugEnabled, enabled); }
    void report(Object session, Bundle diagnostics) { call(report, session, diagnostics); }

    private Object call(Method method, Object... arguments) {
        try { return method.invoke(host, arguments); }
        catch (Exception | LinkageError unavailable) { return null; }
    }
}
