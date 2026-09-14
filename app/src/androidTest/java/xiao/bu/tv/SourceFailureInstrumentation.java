package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Deterministic failure callbacks; does not depend on a live provider failing. */
public final class SourceFailureInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private static Field field(String name) throws Exception {
        Field f = MainActivity.class.getDeclaredField(name); f.setAccessible(true); return f;
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            final MainActivity activity = (MainActivity) startActivitySync(new Intent(
                    getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(() -> {
                try {
                    Method fail = MainActivity.class.getDeclaredMethod("handleUnavailableSource", String.class, String.class);
                    fail.setAccessible(true);
                    field("currentGroupIndex").setInt(activity, 1);
                    field("currentChannelIndex").setInt(activity, 9);
                    field("currentSourceIndex").setInt(activity, 0);
                    field("triedCustomSources").setInt(activity, 1);
                    field("autoSwitchSource").setBoolean(activity, false);
                    fail.invoke(activity, "解析失败", "java.net.SocketTimeoutException: timeout");
                    if (field("currentSourceIndex").getInt(activity) != 0) throw new AssertionError("Network error switched source");
                    fail.invoke(activity, "解析失败", "频道资源不可用");
                    if (field("currentSourceIndex").getInt(activity) != 1) throw new AssertionError("Unavailable source did not switch");
                    fail.invoke(activity, "解析失败", "频道资源不可用");
                    if (field("currentSourceIndex").getInt(activity) != 1) throw new AssertionError("Exhausted sources looped");
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            result.putString("stream", "PASS network stays; unavailable switches; exhausted sources stop\n");
            finish(-1, result);
        } catch (Throwable e) { result.putString("stream", "FAIL " + e); finish(0, result); }
    }
}
