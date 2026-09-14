package xiao.bu.tv;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.Locale;

/** On-demand progress controls; no timer runs while hidden. */
final class PlaybackSeekOverlay extends LinearLayout {
    interface Playback {
        long duration();
        long position();
        void seek(long position);
    }
    private final Playback playback;
    private final SeekBar slider;
    private final TextView time;
    private long target = -1L;
    private boolean dragging;
    private final Runnable hide = () -> dismiss();
    private final Runnable commit = new Runnable() {
        @Override public void run() {
            if (target >= 0L && playback.duration() > 0L) playback.seek(target);
            target = -1L;
            removeCallbacks(hide);
            postDelayed(hide, 4000L);
        }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (getVisibility() != VISIBLE) return;
            if (playback.duration() <= 0L) { dismiss(); return; }
            if (!dragging && target < 0L) update(playback.position());
            postDelayed(this, 500L);
        }
    };
    PlaybackSeekOverlay(Context context, Playback playback) {
        super(context);
        this.playback = playback;
        setOrientation(VERTICAL);
        int pad = dp(16);
        setPadding(pad, pad / 2, pad, pad / 2);
        setBackgroundColor(0xE6000000);
        time = new TextView(context);
        time.setTextColor(Color.WHITE);
        time.setTextSize(16);
        time.setGravity(Gravity.CENTER);
        addView(time, new LayoutParams(-1, -2));
        slider = new SeekBar(context);
        slider.setMax(10000);
        slider.setFocusable(false);
        addView(slider, new LayoutParams(-1, dp(40)));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) {
                dragging = true; removeCallbacks(hide); removeCallbacks(commit);
                target = playback.position();
            }
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                if (user) { target = (long)(playback.duration() * (progress / 10000d)); update(target); }
            }
            @Override public void onStopTrackingTouch(SeekBar bar) { dragging = false; commit.run(); }
        });
        setVisibility(GONE);
    }
    void attach(FrameLayout root) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        params.setMargins(dp(24), 0, dp(24), dp(24));
        root.addView(this, params);
    }
    void showProgress(long position) {
        if (playback.duration() <= 0L) return;
        setVisibility(VISIBLE); bringToFront(); update(position);
        removeCallbacks(tick); postDelayed(tick, 500L);
        removeCallbacks(hide); postDelayed(hide, 4000L);
    }
    void step(long delta) {
        long current = target >= 0L ? target : playback.position();
        target = Math.max(0L, Math.min(Math.max(0L, playback.duration() - 1L), current + delta));
        showProgress(target);
        removeCallbacks(commit); postDelayed(commit, 250L);
    }
    void dismiss() {
        removeCallbacks(tick); removeCallbacks(commit); removeCallbacks(hide);
        target = -1L; dragging = false; setVisibility(GONE);
    }
    private void update(long position) {
        long duration = playback.duration();
        time.setText(format(position) + " / " + format(duration) + "    左右快退 / 快进");
        if (!dragging) slider.setProgress(duration > 0 ? (int)Math.min(10000d, Math.max(0d, position * 10000d / duration)) : 0);
    }
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + .5f); }
    private static String format(long ms) {
        long seconds = Math.max(0L, ms) / 1000L;
        return seconds >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60);
    }
}
