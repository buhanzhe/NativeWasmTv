package xiao.bu.tv;

import android.os.SystemClock;
import android.app.Instrumentation;
import android.os.Bundle;
import android.util.Log;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/** Local, offline decode regression. Copy Apple's short AC3/EAC3 fixtures first. */
public final class DolbyPlaybackInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle results = new Bundle();
        try {
            play("sample.ac3", "ac3");
            play("sample.ec3", "eac3");
            results.putString("stream", "PASS: AC3 and EAC3 decoded to PCM; audio clock advanced.\n");
            finish(-1, results);
        } catch (Throwable error) {
            results.putString("stream", "FAIL: " + Log.getStackTraceString(error));
            finish(1, results);
        }
    }
    private static void assertTrue(String message, boolean condition) {
        if (!condition) throw new AssertionError(message);
    }
    private static void assertNull(String error) { if (error != null) throw new AssertionError(error); }

    private void play(final String file, String codec) throws Exception {
        final IjkMediaPlayer[] player = new IjkMediaPlayer[1];
        final CountDownLatch prepared = new CountDownLatch(1);
        final CountDownLatch rendered = new CountDownLatch(1);
        final AtomicReference<String> failure = new AtomicReference<String>();
        runOnMainSync(new Runnable() {
            @Override public void run() {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 21) DolbyAudioOutput.initialize(getTargetContext());
                    IjkMediaPlayer value = player[0] = new IjkMediaPlayer();
                    value.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "ntv-audio-passthrough", 1);
                    value.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "vn", 1);
                    value.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0);
                    value.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0);
                    value.setOnPreparedListener(mp -> { prepared.countDown(); mp.start(); });
                    value.setOnInfoListener((mp, what, extra) -> {
                        if (what == 10002) rendered.countDown(); // MEDIA_INFO_AUDIO_RENDERING_START
                        return false;
                    });
                    value.setOnErrorListener((mp, what, extra) -> {
                        failure.set(what + "/" + extra); prepared.countDown(); rendered.countDown(); return true;
                    });
                    value.setDataSource("/sdcard/Download/ntv-dolby/" + file);
                    value.prepareAsync();
                } catch (Exception error) { failure.set(error.toString()); prepared.countDown(); }
            }
        });
        try {
            assertTrue("prepare timeout " + failure.get(), prepared.await(10, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertTrue("No decoded audio " + failure.get(), rendered.await(8, TimeUnit.SECONDS));
            assertNull(failure.get());
            SystemClock.sleep(700);
            long start = player[0].getCurrentPosition();
            SystemClock.sleep(700);
            long end = player[0].getCurrentPosition();
            assertTrue("Audio clock stalled " + start + " -> " + end, end > start + 200);
            String decoder = player[0].getMediaInfo().mAudioDecoder;
            String implementation = player[0].getMediaInfo().mAudioDecoderImpl;
            assertTrue(implementation, implementation != null && implementation.contains(codec));
            assertTrue("No HDMI: must be software PCM: " + decoder, "avcodec".equals(decoder));
            Log.i("nTvDolbyTest", file + " decoded " + decoder + "/" + implementation + " position=" + end);
        } finally {
            runOnMainSync(() -> { if (player[0] != null) player[0].release(); });
        }
    }
}
