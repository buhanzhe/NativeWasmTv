package xiao.bu.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.util.Log;
import java.lang.reflect.Field;

/** Shell-only integration checks; route mode does not simulate audio capture. */
public final class CastAudioBitrateProbeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        try {
            MainActivity owner = CastKeepAliveService.localInputOwner();
            String mode = intent.getStringExtra("mode");
            if ("route".equals(mode)) {
                Field f = MainActivity.class.getDeclaredField("castAudioRoute");
                f.setAccessible(true);
                CastAudioRoute route = (CastAudioRoute) f.get(owner);
                if (route == null) {
                    route = new CastAudioRoute(owner, new RemoteCatalogClient());
                    f.set(owner, route);
                }
                route.update(intent.getStringExtra("url"), intent.getBooleanExtra("active", false));
            } else if ("receiver".equals(mode)) {
                Field f = MainActivity.class.getDeclaredField("remoteCatalogUrl");
                f.setAccessible(true);
                f.set(owner, intent.getStringExtra("url"));
            }
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if ("setVolume".equals(mode)) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, intent.getIntExtra("volume", 0), 0);
            }
            Object stats = CastLatencyProbeReceiver.invoke(owner, "collectPlaybackStreamStats",
                    new Class<?>[] {float.class}, 0f);
            Log.i("CastAudioBitrateProbe", "volume=" + audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    + " videoBitrate=" + CastLatencyProbeReceiver.field(stats, "videoBitrate")
                    + " audioBitrate=" + CastLatencyProbeReceiver.field(stats, "audioBitrate"));
            setResultCode(1);
        } catch (Exception error) {
            Log.e("CastAudioBitrateProbe", "Probe failed", error);
            setResultCode(-1);
        }
    }
}
