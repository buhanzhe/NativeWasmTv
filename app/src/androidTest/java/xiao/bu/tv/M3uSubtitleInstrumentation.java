package xiao.bu.tv;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.JSONObject;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/** Local fixture: 30-second subtitle-video.mp4, zh.srt and en.vtt on a Range-capable server. */
public final class M3uSubtitleInstrumentation extends Instrumentation {
    private MainActivity activity;
    private String base;
    private Object field(String name) throws Exception {
        Field f = MainActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(activity);
    }
    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private void command(String action, String key, Object value) throws Exception {
        Method m = MainActivity.class.getDeclaredMethod("handleMediaControl", JSONObject.class);
        m.setAccessible(true); m.invoke(activity, new JSONObject().put("action", action).put(key, value));
    }
    private JSONObject media() throws Exception {
        Method m = MainActivity.class.getDeclaredMethod("buildMediaStateJson", boolean.class);
        m.setAccessible(true); return new JSONObject((String) m.invoke(activity, true));
    }
    private void text(String expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 6000;
        String[] actual = {""};
        do {
            runOnMainSync(() -> actual[0] = ((TextView) activity.findViewById(R.id.subtitle_text)).getText().toString());
            if (actual[0].equals(expected)) return;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Expected caption " + expected + ", got " + actual[0]);
    }
    private void play(Channel channel) throws Exception {
        Method start = MainActivity.class.getDeclaredMethod("startIjkPlayer", Channel.class, String.class, boolean.class, boolean.class);
        start.setAccessible(true);
        runOnMainSync(() -> {
            try {
                Field id = MainActivity.class.getDeclaredField("playRequestId"); id.setAccessible(true);
                id.set(activity, id.getInt(activity) + 1);
                start.invoke(activity, channel, channel.url, false, true);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        long deadline = SystemClock.elapsedRealtime() + 15000;
        while (!(Boolean) field("prepared") && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
        runOnMainSync(() -> {});
        check((Boolean) field("prepared"), "Video failed to prepare");
    }
    @Override public void onCreate(Bundle args) {
        base = args.getString("base"); super.onCreate(args); start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle(); int status = -1;
        try {
            String m3u = "#EXTM3U\n#EXTINF:-1 subtitles=\"zh.srt\",Subtitle test\n"
                    + "#EXTVLCOPT:sub-file=en.vtt\n" + base + "subtitle-video.mp4\n"
                    + "#EXTINF:-1,Plain\n" + base + "subtitle-video.mp4?plain=1\n";
            Method parse = PlaylistManager.class.getDeclaredMethod("parse", byte[].class, String.class);
            parse.setAccessible(true);
            ChannelCatalog.Group[] groups = (ChannelCatalog.Group[]) parse.invoke(null, m3u.getBytes("UTF-8"), base + "list.m3u");
            Channel channel = groups[0].channels[0];
            check(channel.subtitleUrls.length == 2 && channel.subtitleUrls[0].equals(base + "zh.srt"), "M3U subtitle parsing failed");
            check(groups[0].channels[1].subtitleUrls.length == 0, "Subtitle leaked into next channel");
            check(channel.asFavorite("test", 0).withCatalogSource(1).withLogo("logo")
                    .withAdditionalUrl(base + "second.mp4").subtitleUrls.length == 2, "Channel copy dropped subtitles");
            // Isolated test application: verify persistence without touching the user's catalog.
            ChannelCatalogStore store = new ChannelCatalogStore(getTargetContext());
            check(store.replace(groups), "Catalog save failed"); store.close();
            store = new ChannelCatalogStore(getTargetContext());
            check(store.load()[0].channels[0].subtitleUrls.length == 2, "Catalog reload dropped subtitles"); store.close();
            activity = (MainActivity) startActivitySync(new android.content.Intent(getTargetContext(), MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(1200);
            play(channel);
            text("SRT first");
            check(media().getJSONArray("subtitleTracks").length() >= 2, "Missing controller subtitle tracks");
            command("seek", "positionMs", 12000); text("SRT after seek");
            command("subtitleEnabled", "enabled", false); text("");
            command("subtitleEnabled", "enabled", true); text("SRT after seek");
            command("subtitleTrack", "index", HlsMediaTracks.SUBTITLE_BASE + 1); text("WebVTT track");
            command("subtitleStyle", "sizePercent", 125);
            check(media().getJSONObject("subtitleStyle").getInt("sizePercent") == 125, "Existing subtitle style not reused");
            play(groups[0].channels[1]); text("");
            check(media().getJSONArray("subtitleTracks").length() == 0, "Stale external track after channel change");
            play(groups[0].channels[1].withSubtitles(base + "missing.srt"));
            IjkMediaPlayer player = (IjkMediaPlayer) field("player");
            long from = player.getCurrentPosition(); SystemClock.sleep(1600);
            check(player.isPlaying() && player.getCurrentPosition() > from + 500, "Bad subtitle interrupted video");
            result.putString("stream", "PASS M3U parse, relative URLs, favorite copies, database reload, SRT/VTT, seek sync, subtitle toggle/style, channel cleanup, failed subtitle leaves playback running\n");
        } catch (Throwable error) {
            status = 0; result.putString("stream", android.util.Log.getStackTraceString(error));
        }
        finish(status, result);
    }
}
