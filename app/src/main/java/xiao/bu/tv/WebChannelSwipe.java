package xiao.bu.tv;

import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Slides a captured frame while the one attached WebView keeps running in place. */
final class WebChannelSwipe {
    interface Host {
        boolean ready(int request);
        void timedOut();
        default void revealing(int request) {}
    }

    private final View view;
    private final Host host;
    private int stage, generation, request = -1, polls;
    private View outgoing;
    private float x, y;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (stage != 2) return;
            if (request >= 0 && host.ready(request)) { reveal(); return; }
            // This fallback exposes the page, never reports playback success.
            if (++polls >= 150) { cancel(); host.timedOut(); return; }
            view.postDelayed(this, 200L);
        }
    };

    WebChannelSwipe(View view, Host host) { this.view = view; this.host = host; }
    boolean active() { return stage != 0; }

    int reserve() {
        cancel();
        generation++;
        stage = 4; // Own the committed gesture while its outgoing frame is captured.
        return generation;
    }

    boolean reserved(int token) { return stage == 4 && generation == token; }

    void start(float directionX, float directionY, Runnable navigate) {
        start(directionX, directionY, null, navigate);
    }

    void start(float directionX, float directionY, View snapshot, Runnable navigate) {
        if (active() && stage != 4) cancel();
        outgoing = snapshot == null ? view : snapshot;
        stage = 1; request = -1; polls = 0; x = directionX; y = directionY;
        final int token = generation;
        outgoing.animate().translationX(x * view.getWidth()).translationY(y * view.getHeight())
                .setDuration(180L).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    if (token != generation || stage != 1) return;
                    stage = 2;
                    if (outgoing == view) {
                        view.setTranslationX(-x * view.getWidth());
                        view.setTranslationY(-y * view.getHeight());
                    }
                    navigate.run();
                    if (stage == 2) view.postDelayed(poll, 200L);
                }).start();
    }

    void bind(int id) {
        if (stage != 2) return;
        if (request >= 0 && request != id) { cancel(); return; }
        request = id;
    }

    void nativeReady(int id) {
        if (stage == 2 && request == id) cancel();
    }

    private void reveal() {
        stage = 3;
        host.revealing(request);
        if (outgoing != view) { cancel(); return; }
        final int token = generation;
        view.animate().translationX(0f).translationY(0f).setDuration(200L)
                .setInterpolator(new DecelerateInterpolator()).withEndAction(() -> {
                    if (token == generation && stage == 3) cancel();
                }).start();
    }

    void cancel() {
        if (stage == 0) return;
        generation++; stage = 0; request = -1;
        view.removeCallbacks(poll);
        view.animate().withEndAction(null).cancel();
        if (outgoing != null && outgoing != view) outgoing.animate().withEndAction(null).cancel();
        outgoing = null;
        view.setTranslationX(0f); view.setTranslationY(0f);
    }
}
