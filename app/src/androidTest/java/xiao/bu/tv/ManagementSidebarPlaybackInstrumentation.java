package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;

/** Actual MainActivity touch path with a local, temporary video; no catalog changes. */
public final class ManagementSidebarPlaybackInstrumentation extends Instrumentation {
    private MainActivity main;
    private ManagementActivity menu;
    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private void tap(float x, float y) {
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(time, time + 80, MotionEvent.ACTION_UP, x, y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN); up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        sendPointerSync(down); SystemClock.sleep(80); sendPointerSync(up);
        down.recycle(); up.recycle();
    }
    private void screenshot(String name) throws Exception {
        Bitmap image = getUiAutomation().takeScreenshot();
        if (image == null) throw new AssertionError("No screenshot");
        try (FileOutputStream out = new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null), name))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        image.recycle();
    }
    @Override public void onStart() {
        Bundle result = new Bundle(); int status = -1;
        try {
            main = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(1200);
            final File videoFile = new File(getTargetContext().getCacheDir(), "sidebar-test.mp4");
            try (java.io.InputStream input = new java.net.URL("http://127.0.0.1:39966/multimedia-aac.mp4").openStream();
                    FileOutputStream output = new FileOutputStream(videoFile)) {
                byte[] buffer = new byte[16384]; int read;
                while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            }
            runOnMainSync(() -> {
                main.suspendForMultimedia(false);
                main.resumeSentMultimedia(videoFile, "侧栏播放测试", false, 1000);
            });
            SystemClock.sleep(1000);
            Object player = field(main, "player");
            if (player == null) throw new AssertionError("No video player");
            tv.danmaku.ijk.media.player.IjkMediaPlayer video = (tv.danmaku.ijk.media.player.IjkMediaPlayer) player;
            long deadline = SystemClock.elapsedRealtime() + 15000;
            while (!video.isPlaying() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(200);
            if (!video.isPlaying()) throw new AssertionError("Test video not playing");
            screenshot("sidebar-before.png");
            long position = video.getCurrentPosition();
            View root = main.getWindow().getDecorView();
            ActivityMonitor monitor = addMonitor(ManagementActivity.class.getName(), null, false);
            setInTouchMode(true);
            tap(root.getWidth() - 30, root.getHeight() / 2f);
            menu = (ManagementActivity) waitForMonitorWithTimeout(monitor, 4000);
            removeMonitor(monitor);
            if (menu == null) throw new AssertionError("Right tap did not open menu");
            SystemClock.sleep(2200);
            WebView web = (WebView) field(menu, "webView");
            final int[] geometry = new int[4];
            runOnMainSync(() -> {
                View parent = (View) web.getParent();
                geometry[0] = parent.getWidth(); geometry[1] = parent.getHeight();
                geometry[2] = web.getLeft();
                geometry[3] = Math.round(web.getLeft() + web.getWidth() * web.getScaleX());
            });
            if (geometry[0] <= geometry[1] || geometry[2] <= 0 || geometry[3] != geometry[0])
                throw new AssertionError("Not a right sidebar: " + java.util.Arrays.toString(geometry));
            if (field(main, "player") != player) throw new AssertionError("Menu recreated playback");
            if (!video.isPlaying() || video.getCurrentPosition() <= position)
                throw new AssertionError("Playback stopped under menu");
            if (web.getScaleX() != 1f || web.getScaleY() != 1f || web.getHeight() != geometry[1])
                throw new AssertionError("Menu is a scaled bitmap");
            screenshot("sidebar-open.png");
            tap(30, geometry[1] / 2f);
            SystemClock.sleep(600);
            if (!menu.isFinishing()) throw new AssertionError("Outside tap did not close");
            screenshot("sidebar-closed.png");
            result.putString("stream", "PASS real right tap, right sidebar " + java.util.Arrays.toString(geometry)
                    + ", same player, outside closes; screenshots saved\n");
        } catch (Throwable error) {
            status = 0; result.putString("stream", android.util.Log.getStackTraceString(error));
        } finally {
            runOnMainSync(() -> { if (menu != null) menu.finish(); if (main != null) main.finish(); });
        }
        finish(status, result);
    }
}
