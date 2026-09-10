package xiao.bu.tv;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Active only while app audio is actually captured and a TV owns playback. */
@TargetApi(29)
final class CastAudioRoute {
    private final Context context;
    private final AudioManager audio;
    private final SharedPreferences preferences;
    private final RemoteCatalogClient client;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor commands = new ThreadPoolExecutor(0, 1, 5,
            TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(4),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private MediaSession session;
    private volatile String receiver = "";
    private volatile int generation;
    private long lastKeyAt;

    CastAudioRoute(Context context, RemoteCatalogClient client) {
        this.context = context.getApplicationContext();
        this.client = client;
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        preferences = context.getSharedPreferences("cast_audio_route", Context.MODE_PRIVATE);
        restoreVolume(); // Recover a volume lease left by a killed process.
    }

    void update(String target, boolean audioActive) {
        if (!audioActive || target == null || target.length() == 0) {
            receiver = "";
            generation++;
            commands.getQueue().clear();
            if (session != null) { session.release(); session = null; }
            restoreVolume();
            return;
        }
        if (target.equals(receiver) && session != null) return;
        if (audio == null) return;
        if (!target.equals(receiver)) { generation++; commands.getQueue().clear(); }
        receiver = target;
        try {
            if (!preferences.contains("previousVolume")) {
                // Persist before muting so a crash cannot leave the phone silent forever.
                if (!preferences.edit().putInt("previousVolume",
                        audio.getStreamVolume(AudioManager.STREAM_MUSIC)).commit()) {
                    throw new IllegalStateException("Unable to save media volume");
                }
            }
            // Mute the physical media output, never the player/WebView track: track
            // mute would also silence the PCM copied into playback capture.
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            if (session == null) {
                session = new MediaSession(context, "nTv cast audio");
                session.setPlaybackToRemote(new VolumeProvider(
                        VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, 50) {
                    @Override public void onAdjustVolume(final int direction) {
                        main.post(new Runnable() { @Override public void run() {
                            adjust(direction);
                        }});
                    }
                });
                session.setPlaybackState(new PlaybackState.Builder()
                        .setState(PlaybackState.STATE_PLAYING, 0, 1f).build());
                session.setActive(true);
            }
        } catch (RuntimeException error) {
            Log.w("CastAudioRoute", "Unable to route cast volume", error);
            update("", false);
        }
    }

    boolean dispatch(KeyEvent event) {
        int key = event.getKeyCode();
        if (receiver.length() == 0 || session == null
                || (key != KeyEvent.KEYCODE_VOLUME_UP && key != KeyEvent.KEYCODE_VOLUME_DOWN)) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            long now = SystemClock.elapsedRealtime();
            if (event.getRepeatCount() == 0 || now - lastKeyAt >= 100) {
                lastKeyAt = now;
                adjust(key == KeyEvent.KEYCODE_VOLUME_UP ? 1 : -1);
            }
        }
        return true; // Consume both down and up; never change the muted local stream.
    }

    private void adjust(final int direction) {
        final String target = receiver;
        final int token = generation;
        if (target.length() == 0 || (direction != 1 && direction != -1)) return;
        commands.execute(new Runnable() { @Override public void run() {
            if (token != generation || !target.equals(receiver)) return;
            try {
                client.adjustReceiverVolume(target, direction);
            } catch (Exception error) {
                Log.w("CastAudioRoute", "Unable to change TV volume", error);
            }
        }});
    }

    private void restoreVolume() {
        if (audio == null || !preferences.contains("previousVolume")) return;
        try {
            // Respect a user change made through system settings during the cast.
            if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                        preferences.getInt("previousVolume", 0), 0);
            }
            preferences.edit().remove("previousVolume").commit();
        } catch (RuntimeException error) {
            Log.w("CastAudioRoute", "Unable to restore media volume", error);
        }
    }
}
