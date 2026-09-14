package xiao.bu.tv;

import android.content.Context;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;

/** API 21+ transport controls used by car head units and Bluetooth remotes. */
final class ChannelMediaSession {
    private final MediaSession session;

    ChannelMediaSession(Context context, final Runnable previous, final Runnable next) {
        session = new MediaSession(context, "nTv channels");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onSkipToPrevious() { previous.run(); }
            @Override public void onSkipToNext() { next.run(); }
        }, new Handler(Looper.getMainLooper()));
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SKIP_TO_NEXT)
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build());
    }

    void setActive(boolean active) { session.setActive(active); }
    void release() { session.release(); }
}
