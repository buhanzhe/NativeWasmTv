package xiao.bu.tv;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/** Fixture server on 19981: covered.mp3 (24s/APIC), plain-audio.mp3 (24s/no APIC), video.mp4. */
public final class AudioArtworkInstrumentation extends Instrumentation {
    private MainActivity activity;
    private String base;
    private Object field(String name) throws Exception { return field(activity, name); }
    private Object field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private void set(String name, Object value) throws Exception {
        Field f = MainActivity.class.getDeclaredField(name); f.setAccessible(true); f.set(activity, value);
    }
    private Object uncheckedField(String name) {
        try { return field(name); } catch (Exception error) { throw new RuntimeException(error); }
    }
    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private void play(String url, boolean audio, boolean cover) throws Exception {
        final int request = (Integer) field("playRequestId") + 1;
        Method start = MainActivity.class.getDeclaredMethod("startIjkPlayer", Channel.class, String.class, boolean.class, boolean.class);
        start.setAccessible(true);
        runOnMainSync(() -> {
            try {
                set("playRequestId", request);
                start.invoke(activity, new Channel("1", audio ? "唱片测试" : "视频测试", "", url, null, null), url, false, true);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        long deadline = SystemClock.elapsedRealtime() + 18000;
        while (SystemClock.elapsedRealtime() < deadline && !(Boolean) field("prepared")) SystemClock.sleep(100);
        check((Boolean) field("prepared"), "Not prepared: " + url + " status="
                + ((android.widget.TextView) field("statusText")).getText());
        runOnMainSync(() -> {}); // onPrepared publishes the view state in the same UI callback.
        check((Boolean) field("audioOnlyPlayback") == audio, "Wrong audio/video classification: " + url);
        AudioArtworkView view = (AudioArtworkView) field("audioArtwork");
        if (cover) {
            while (SystemClock.elapsedRealtime() < deadline && field(view, "cover") == null) SystemClock.sleep(100);
            check(field(view, "cover") != null, "APIC cover not decoded");
        } else check(field(view, "cover") == null, "Previous cover leaked into new channel");
        check(view.getVisibility() == (audio ? View.VISIBLE : View.GONE), "Wrong artwork visibility");
        IjkMediaPlayer player = (IjkMediaPlayer) field("player");
        long from = player.getCurrentPosition();
        long clockDeadline = SystemClock.elapsedRealtime() + 5000;
        while (SystemClock.elapsedRealtime() < clockDeadline && player.getCurrentPosition() <= from + 200) SystemClock.sleep(100);
        check(player.getCurrentPosition() > from + 200, "Playback clock did not advance: " + url
                + " position=" + player.getCurrentPosition() + " playing=" + player.isPlaying()
                + " request=" + field("playRequestId") + "/" + request);
        if (audio) {
            check((Integer) field("playbackReadyRequestId") == request, "Audio still waiting for video frame");
            long animationDeadline = SystemClock.elapsedRealtime() + 2500;
            while (!(Boolean) field(view, "playing") && SystemClock.elapsedRealtime() < animationDeadline) SystemClock.sleep(100);
            float angle = ((android.view.View) field(view, "recordView")).getRotation(); SystemClock.sleep(160);
            check(((android.view.View) field(view, "recordView")).getRotation() > angle, "Record not rotating");
            runOnMainSync(player::pause);
            long pauseDeadline = SystemClock.elapsedRealtime() + 2500;
            while ((Boolean) field(view, "playing") && SystemClock.elapsedRealtime() < pauseDeadline) SystemClock.sleep(100);
            check(!(Boolean) field(view, "playing"), "Playback pause did not stop artwork");
            float stopped = ((android.view.View) field(view, "recordView")).getRotation(); SystemClock.sleep(160);
            check(((android.view.View) field(view, "recordView")).getRotation() == stopped, "Paused record kept rotating");
            runOnMainSync(player::start);
            if (player.getDuration() > 10000) {
                Method state = MainActivity.class.getDeclaredMethod("buildMediaStateJson", boolean.class);
                state.setAccessible(true);
                org.json.JSONObject media = new org.json.JSONObject((String) state.invoke(activity, false));
                check(media.getBoolean("seekable") && media.getBoolean("audioOnly"), "MP3 controller cannot seek");
                if (cover) {
                    check(media.getString("artworkKey").length() > 0, "Controller artwork missing");
                    LocalControlServer server = (LocalControlServer) field("controlServer");
                    byte[] png = VideoScreenshot.download("http://127.0.0.1:" + server.getPort()
                            + "/api/media/artwork?key=" + java.net.URLEncoder.encode(media.getString("artworkKey"), "UTF-8"));
                    check(android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length) != null,
                            "Controller cover endpoint is not an image");
                } else check(media.getString("artworkKey").isEmpty(), "Stale controller artwork");
                Method control = MainActivity.class.getDeclaredMethod("handleMediaControl", org.json.JSONObject.class);
                control.setAccessible(true);
                control.invoke(activity, new org.json.JSONObject().put("action", "seek").put("positionMs", 12000));
                long seekDeadline = SystemClock.elapsedRealtime() + 4000;
                while (player.getCurrentPosition() < 11500 && SystemClock.elapsedRealtime() < seekDeadline) SystemClock.sleep(100);
                check(player.getCurrentPosition() >= 11500, "Controller MP3 seek did not advance position");
                runOnMainSync(() -> {
                    ((View) uncheckedField("managementPanel")).setVisibility(View.GONE);
                    ((View) uncheckedField("channelListPanel")).setVisibility(View.GONE);
                    activity.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,
                            android.view.KeyEvent.KEYCODE_DPAD_LEFT));
                    activity.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,
                            android.view.KeyEvent.KEYCODE_DPAD_LEFT));
                });
                seekDeadline = SystemClock.elapsedRealtime() + 4000;
                while (player.getCurrentPosition() > 7000 && SystemClock.elapsedRealtime() < seekDeadline) SystemClock.sleep(100);
                check(player.getCurrentPosition() < 7000, "Remote left key did not rewind MP3");
                check(((View) field("playbackSeekOverlay")).getVisibility() == View.VISIBLE, "TV progress overlay hidden");
            }
        }
    }
    @Override public void onCreate(Bundle args) {
        base = args == null ? null : args.getString("base");
        if (base == null) base = "http://127.0.0.1:19981/";
        super.onCreate(args); start();
    }
    @Override public void onStart() {
        Bundle output = new Bundle(); int status = -1;
        try {
            check(Id3Artwork.read(new ByteArrayInputStream(new byte[10])) == null, "Non-ID3 accepted");
            check(Id3Artwork.read(new ByteArrayInputStream(new byte[] {'I','D','3',3,0,0,127,127,127,127})) == null, "Oversize tag accepted");
            activity = (MainActivity) startActivitySync(new android.content.Intent(getTargetContext(), MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(1200);
            play(base + "covered.mp3", true, true);
            play(base + "video.mp4", false, false);
            play(base + "plain-audio.mp3", true, false);
            java.net.HttpURLConnection radio = NetworkClient.open(new java.net.URL("http://lhttp.qtfm.cn/live/4804/64k.mp3"));
            radio.setConnectTimeout(4000); radio.setReadTimeout(4000);
            try {
                output.putString("radioHttp", "HTTP " + radio.getResponseCode() + " " + radio.getContentType());
            } finally { radio.disconnect(); }
            play("http://lhttp.qtfm.cn/live/4804/64k.mp3", true, false);
            output.putString("stream", "PASS APIC cover, MP4, plain MP3, live radio, rotation/pause, controller cover endpoint, controller/remote MP3 seek and stale-cover cleanup\n");
        } catch (Throwable error) { status = 0; output.putString("stream", output.getString("radioHttp") + "\n" + android.util.Log.getStackTraceString(error)); }
        if (activity != null) {
            try {
                AudioArtworkView view = (AudioArtworkView) field("audioArtwork");
                runOnMainSync(() -> {
                    android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(view.getWidth(), view.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
                    view.draw(new android.graphics.Canvas(image));
                    try (java.io.FileOutputStream file = getTargetContext().openFileOutput("record.png", 0)) { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, file); }
                    catch (Exception e) { throw new RuntimeException(e); }
                    image.recycle();
                });
            } catch (Exception e) { output.putString("captureError", e.toString()); }
        }
        finish(status, output);
    }
}
