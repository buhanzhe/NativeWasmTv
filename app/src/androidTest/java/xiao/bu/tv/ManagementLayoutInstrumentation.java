package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.SystemClock;
import android.webkit.WebView;
import android.view.View;
import java.lang.reflect.Field;

/** Uses the local test HTTP server via adb reverse; does not open playback. */
public final class ManagementLayoutInstrumentation extends Instrumentation {
    private ManagementActivity activity;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int status = -1;
        try {
            for (String[] accepted : new String[][] {{"image/*,video/*"}, {"image/*", "video/*"}, {" image/* , video/* "}}) {
                Intent chooser = ManagementActivity.fileChooserIntent(accepted);
                if (!"*/*".equals(chooser.getType()) || !java.util.Arrays.equals(
                        new String[]{"image/*", "video/*"}, chooser.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)))
                    throw new AssertionError("Invalid combined media MIME types");
            }
            if (!"image/png".equals(ManagementActivity.fileChooserIntent(new String[]{".png"}).getType()))
                throw new AssertionError("File extension filter lost");
            if (!"application/vnd.android.package-archive".equals(ManagementActivity.fileChooserIntent(new String[]{"application/vnd.android.package-archive"}).getType()))
                throw new AssertionError("APK filter changed");
            // The 600 dp boundary, independent of current/default orientation.
            for (int smallest : new int[] { 360, 599, 600, 720 }) {
                for (int orientation : new int[] { 1, 2 }) {
                    android.content.res.Configuration config = new android.content.res.Configuration(
                            getTargetContext().getResources().getConfiguration());
                    config.smallestScreenWidthDp = smallest;
                    config.orientation = orientation;
                    android.content.Context configured = getTargetContext().createConfigurationContext(config);
                    if (ManagementActivity.isTablet(configured) != (smallest >= 600))
                        throw new AssertionError("Tablet threshold failed: " + smallest + "/" + orientation);
                }
            }
            for (int rotation = 0; rotation < 4; rotation++) {
                boolean quarter = (rotation & 1) != 0;
                if (!ManagementActivity.hasLandscapeNaturalOrientation(quarter ? 720 : 1280,
                        quarter ? 1280 : 720, rotation)) throw new AssertionError("Landscape device rotated");
                if (ManagementActivity.hasLandscapeNaturalOrientation(quarter ? 1920 : 1080,
                        quarter ? 1080 : 1920, rotation)) throw new AssertionError("Rotated phone became tablet");
            }
            java.lang.reflect.Method sniff = WebSourceView.class.getDeclaredMethod("normalizeMediaPlaylist", String.class);
            sniff.setAccessible(true);
            for (String url : new String[] {"https://example.com/music.MP3?token=a+b", "https://example.com/music.m4a",
                    "https://example.com/clip.mp4", "https://example.com/live.m3u8"}) {
                if (!url.equals(sniff.invoke(null, url))) throw new AssertionError("Media URL lost: " + url);
            }
            for (String url : new String[] {"https://example.com/segment.ts", "https://example.com/segment.m4s",
                    "https://example.com/page?file=music.mp3", "file:///music.mp3", "blob:https://example.com/clip"}) {
                if (sniff.invoke(null, url) != null) throw new AssertionError("Non-resource admitted: " + url);
            }
            activity = (ManagementActivity) startActivitySync(new Intent(getTargetContext(), ManagementActivity.class)
                    .putExtra(ManagementActivity.EXTRA_URL, "http://127.0.0.1:39966/index.html")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(1200);
            Field field = ManagementActivity.class.getDeclaredField("webView"); field.setAccessible(true);
            final WebView web = (WebView) field.get(activity);
            final int[] size = new int[6];
            runOnMainSync(() -> {
                View parent = (View) web.getParent();
                size[0] = web.getWidth(); size[1] = parent.getHeight();
                size[2] = Math.round(web.getLeft() + web.getWidth() * web.getScaleX());
                size[3] = parent.getWidth(); size[4] = web.getHeight();
                size[5] = Math.round(web.getWidth() * web.getScaleX());
            });
            Field deviceField = ManagementActivity.class.getDeclaredField("sidebarDevice");
            deviceField.setAccessible(true);
            boolean tablet = deviceField.getBoolean(activity);
            if (tablet) {
                if (size[3] <= size[1]) throw new AssertionError("Tablet forced portrait");
                if (size[0] != size[5] || size[4] != size[1] || Math.abs(size[5] - size[1] * 9f / 16f) > 2 || size[2] != size[3]
                        || web.getScaleX() != 1f || web.getScaleY() != 1f)
                    throw new AssertionError("Tablet portrait pane not aligned right: " + java.util.Arrays.toString(size));
            } else if (!tablet) {
                if (activity.getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                    throw new AssertionError("Phone not portrait");
                if (size[0] != size[3] || size[3] >= size[1]) throw new AssertionError("Phone layout not full portrait");
            }
            if (tablet) {
                final float[] touch = {-1f, -1f};
                runOnMainSync(() -> {
                    web.setOnTouchListener((view, event) -> {
                        touch[0] = event.getX(); touch[1] = event.getY(); return true;
                    });
                    View parent = (View) web.getParent();
                    long now = SystemClock.uptimeMillis();
                    float x = web.getLeft() + 100 * web.getScaleX(), y = 100 * web.getScaleY();
                    android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now,
                            android.view.MotionEvent.ACTION_DOWN, x, y, 0);
                    android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now + 50,
                            android.view.MotionEvent.ACTION_UP, x, y, 0);
                    parent.dispatchTouchEvent(down); parent.dispatchTouchEvent(up);
                    down.recycle(); up.recycle(); web.setOnTouchListener(null);
                });
                if (Math.abs(touch[0] - 100) > 1 || Math.abs(touch[1] - 100) > 1 || activity.isFinishing())
                    throw new AssertionError("Scaled web touch mapping failed: " + java.util.Arrays.toString(touch));
                runOnMainSync(() -> {
                    View parent = (View) web.getParent();
                    long now = SystemClock.uptimeMillis();
                    android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now,
                            android.view.MotionEvent.ACTION_DOWN, 20, size[1] / 2f, 0);
                    android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now + 50,
                            android.view.MotionEvent.ACTION_UP, 20, size[1] / 2f, 0);
                    parent.dispatchTouchEvent(down); parent.dispatchTouchEvent(up);
                    down.recycle(); up.recycle();
                });
                waitForIdleSync();
                if (!activity.isFinishing()) throw new AssertionError("Outside tap did not close management page");
            }
            result.putString("stream", "PASS " + (tablet ? "tablet" : "phone") + " webWidth=" + size[0]
                    + " renderHeight=" + size[4] + " visualWidth=" + size[5] + " parent=" + size[3] + "x" + size[1] + " right=" + size[2] + "\n");
        } catch (Throwable error) { status = 0; result.putString("stream", android.util.Log.getStackTraceString(error)); }
        finally { if (activity != null) runOnMainSync(() -> activity.finish()); }
        finish(status, result);
    }
}
