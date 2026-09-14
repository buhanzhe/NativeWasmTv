package xiao.bu.tv;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;

/** Device-side rendering check: gesture feedback must never move the hit point. */
public final class CursorFeedbackInstrumentation extends Instrumentation {
    private FlyMouseCursorView cursor;
    private Bitmap bitmap;
    private Canvas canvas;
    private void requireCompatibleTarget() throws Exception {
        if ((getTargetContext().getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            throw new IllegalStateException("CursorFeedbackInstrumentation requires the matching "
                    + "debug app APK. Release methods are obfuscated; install both "
                    + "arm32Debug and arm32DebugAndroidTest from the same build.");
        }
        // Check the runtime class, not the test APK's compile-time BuildConfig.
        FlyMouseCursorView.class.getDeclaredMethod("resetPosition");
        FlyMouseCursorView.class.getDeclaredMethod("moveBy", float.class, float.class);
        FlyMouseCursorView.class.getDeclaredMethod("pulseClick");
        FlyMouseCursorView.class.getDeclaredMethod("cursorX");
        FlyMouseCursorView.class.getDeclaredMethod("cursorY");
        FlyMouseCursorView.class.getDeclaredField("visualScale");
    }
    private void onMain(Runnable action) {
        final Throwable[] failure = {null};
        // runOnMainSync does not propagate a main-thread exception to onStart.
        // Catch it inside the dispatched task, then report it on the test thread.
        runOnMainSync(() -> {
            try { action.run(); } catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] != null) throw new RuntimeException("Cursor test UI action failed", failure[0]);
    }
    private float scale() throws Exception {
        Field field = FlyMouseCursorView.class.getDeclaredField("visualScale");
        field.setAccessible(true);
        return field.getFloat(cursor);
    }
    private void draw() {
        onMain(() -> { canvas.drawColor(0xff263849); cursor.draw(canvas); });
    }
    private void save(String name) throws Exception {
        File file = new File(getTargetContext().getExternalFilesDir(null), name + ".png");
        try (FileOutputStream stream = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
    }
    private void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private Object field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }
    private String checkLiveMotion() throws Exception {
        MainActivity activity = (MainActivity) startActivitySync(new android.content.Intent(
                getTargetContext(), MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            SystemClock.sleep(1800);
            WebSourceView source = (WebSourceView) field(activity, "webSourceView");
            android.webkit.WebView web = (android.webkit.WebView) field(source, "webView");
            FlyMouseCursorView liveCursor = (FlyMouseCursorView) field(activity, "flyMouseCursor");
            android.view.View root = (android.view.View) field(activity, "root");
            Field enabled = MainActivity.class.getDeclaredField("flyMouseEnabled");
            enabled.setAccessible(true);
            enabled.setBoolean(activity, true);
            onMain(() -> {
                source.setListener(null);
                web.stopLoading();
                web.loadDataWithBaseURL("https://cursor-test.invalid/", "<body style='background:#263849'><input value='drag and click'><p>Cursor fixture</p></body>", "text/html", "utf-8", null);
                liveCursor.setVisibility(android.view.View.VISIBLE);
                liveCursor.resetPosition();
            });
            SystemClock.sleep(1000);
            final int[] layouts = {0};
            android.view.ViewTreeObserver.OnGlobalLayoutListener listener = () -> layouts[0]++;
            onMain(() -> root.getViewTreeObserver().addOnGlobalLayoutListener(listener));
            int[] counts = new int[2];
            for (int phase = 0; phase < 2; phase++) {
                final boolean oldLayerBehaviour = phase == 0;
                onMain(() -> { liveCursor.resetPosition(); layouts[0] = 0; });
                long started = SystemClock.uptimeMillis();
                for (int i = 0; i < 240; i++) {
                    double angle = i * Math.PI / 30;
                    double nextAngle = (i + 1) * Math.PI / 30;
                    double radius = Math.min(liveCursor.getWidth(), liveCursor.getHeight()) * .30;
                    activity.handleLocalPointer(new org.json.JSONObject().put("action", "move")
                            .put("dx", radius * (Math.cos(nextAngle) - Math.cos(angle)))
                            .put("dy", radius * (Math.sin(nextAngle) - Math.sin(angle))));
                    // Reproduce the old redundant layer update against the same page.
                    if (oldLayerBehaviour) onMain(() -> liveCursor.bringToFront());
                    SystemClock.sleep(Math.max(1, started + (i + 1) * 17 - SystemClock.uptimeMillis()));
                }
                SystemClock.sleep(100);
                counts[phase] = layouts[0];
            }
            onMain(() -> root.getViewTreeObserver().removeOnGlobalLayoutListener(listener));
            check(counts[1] < counts[0] / 4, "Motion still relayouts the page: " + java.util.Arrays.toString(counts));
            return "live 240-sample layer A/B layout passes=" + java.util.Arrays.toString(counts);
        } finally {
            onMain(() -> activity.finish());
        }
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = -1;
        try {
            requireCompatibleTarget();
            onMain(() -> {
                cursor = new FlyMouseCursorView(getTargetContext());
                cursor.layout(0, 0, 640, 400);
                bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888);
                canvas = new Canvas(bitmap);
                cursor.resetPosition();
            });
            draw(); save("cursor-normal");
            for (int i = 0; i < 8; i++) {
                SystemClock.sleep(32);
                onMain(() -> cursor.moveBy(1, 0)); draw();
            }
            check(scale() == 1f, "Slow movement enlarged cursor");
            Object cachedBitmap = field(cursor, "cursorBitmap");
            for (int i = 0; i < 16; i++) {
                final float dx = (i % 2 == 0 ? 48f : -48f)
                        * getTargetContext().getResources().getDisplayMetrics().density;
                SystemClock.sleep(32);
                onMain(() -> cursor.moveBy(dx, 0)); draw();
                check(field(cursor, "cursorBitmap") == cachedBitmap,
                        "Motion rebuilt the pointer texture");
            }
            check(scale() > 1.8f, "Fast sustained movement did not enlarge cursor");
            save("cursor-fast");
            float x = cursor.cursorX(), y = cursor.cursorY();
            onMain(() -> cursor.pulseClick());
            SystemClock.sleep(60); draw(); save("cursor-click");
            check(cursor.cursorX() == x && cursor.cursorY() == y, "Feedback changed hit point");
            SystemClock.sleep(250); draw();
            SystemClock.sleep(310); draw();
            check(scale() == 1f, "Cursor did not shrink after stopping");
            save("cursor-restored");
            onMain(() -> cursor.setDrawSuppressed(true));
            for (int i = 0; i < 20; i++) {
                onMain(() -> cursor.moveBy(2, -1)); draw();
            }
            check(((Long) field(cursor, "feedbackDueAt")) == 0L,
                    "Hidden phone cursor scheduled visual frames");
            onMain(() -> cursor.setDrawSuppressed(false));
            draw();
            String live = checkLiveMotion();
            result.putString("stream", "PASS slow/fast motion, cached texture reuse, click feedback, fixed hit point, idle restoration, hidden cursor; " + live + "; PNGs in app external files\n");
        } catch (Throwable error) {
            code = 0; result.putString("stream", android.util.Log.getStackTraceString(error));
        }
        finish(code, result);
    }
}
