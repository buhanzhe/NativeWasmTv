package xiao.bu.tv;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/** Build with -PcjsV5Assets=<absolute tests/fixtures>; tests the full M3U and real HTTPS radio. */
public final class SomaFmInstrumentation extends Instrumentation {
    private MainActivity activity;
    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    private static void check(boolean condition, String text) { if (!condition) throw new AssertionError(text); }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int code = -1;
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream input = getContext().getAssets().open("somafm.m3u")) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
            }
            Method parse = PlaylistManager.class.getDeclaredMethod("parse", byte[].class); parse.setAccessible(true);
            ChannelCatalog.Group[] groups = (ChannelCatalog.Group[]) parse.invoke(null, (Object) bytes.toByteArray());
            check(groups.length == 1 && groups[0].channels.length == 10, "M3U channel count");
            for (Channel channel : groups[0].channels) {
                check(channel.epgId.startsWith("somafm."), "Lost tvg-id");
                check(channel.logoUrl.startsWith("https://somafm.com/logos/400/"), "Lost tvg-logo");
                check(channel.url.endsWith("-128-mp3"), "Changed extensionless URL");
            }
            activity = (MainActivity) startActivitySync(new android.content.Intent(getTargetContext(), MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(1200);
            // PNG and JPG radio logos, both through the TLS proxy used by production playback.
            for (int index : new int[] {0, 3}) {
                Channel channel = groups[0].channels[index];
                HttpStreamResolver.Result resolved = HttpStreamResolver.resolve(channel.url);
                check(resolved.directMedia, "audio/mpeg was not recognized without a suffix");
                Field request = MainActivity.class.getDeclaredField("playRequestId"); request.setAccessible(true);
                Method start = MainActivity.class.getDeclaredMethod("startIjkPlayer", Channel.class, String.class, boolean.class, boolean.class);
                start.setAccessible(true);
                runOnMainSync(() -> {
                    try {
                        request.setInt(activity, request.getInt(activity) + 1);
                        start.invoke(activity, channel, resolved.url, false, resolved.directMedia);
                    } catch (Exception error) { throw new RuntimeException(error); }
                });
                long deadline = SystemClock.elapsedRealtime() + 25000;
                while (!(Boolean) field(activity, "prepared") && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
                check((Boolean) field(activity, "prepared"), channel.name + " not prepared");
                check((Boolean) field(activity, "audioOnlyPlayback"), channel.name + " not audio");
                AudioArtworkView artwork = (AudioArtworkView) field(activity, "audioArtwork");
                while (field(artwork, "cover") == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
                check(field(artwork, "cover") != null, channel.name + " logo fallback not decoded");
                IjkMediaPlayer player = (IjkMediaPlayer) field(activity, "player");
                long from = player.getCurrentPosition();
                long clockDeadline = SystemClock.elapsedRealtime() + 7000;
                while (player.getCurrentPosition() <= from + 200 && SystemClock.elapsedRealtime() < clockDeadline) SystemClock.sleep(100);
                check(player.getCurrentPosition() > from + 200, channel.name + " audio clock did not advance");
            }
            result.putString("stream", "PASS all 10 M3U entries; Groove Salad and Lush HTTPS playback, audio detection, PNG/JPG logo fallback\n");
        } catch (Throwable error) { code = 0; result.putString("stream", android.util.Log.getStackTraceString(error)); }
        finish(code, result);
    }
}
