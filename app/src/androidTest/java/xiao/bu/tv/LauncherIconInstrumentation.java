package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;

/** PackageManager resource smoke check; never starts playback or changes settings. */
public final class LauncherIconInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            PackageManager pm = getTargetContext().getPackageManager();
            ApplicationInfo app = getTargetContext().getApplicationInfo();
            Drawable icon = pm.getApplicationIcon(app);
            if (icon == null) throw new AssertionError("Missing launcher icon");
            String kind = icon.getClass().getSimpleName();
            if (Build.VERSION.SDK_INT >= 26) {
                if (!"AdaptiveIconDrawable".equals(kind)) throw new AssertionError(kind);
                Drawable foreground = (Drawable)icon.getClass().getMethod("getForeground").invoke(icon);
                if (!"VectorDrawable".equals(foreground.getClass().getSimpleName())) {
                    throw new AssertionError("Foreground is not a vector");
                }
            }
            if (Build.VERSION.SDK_INT < 21) {
                Drawable fallback = getTargetContext().getResources().getDrawable(R.drawable.tv_banner);
                if (!"BitmapDrawable".equals(fallback.getClass().getSimpleName())
                        || fallback.getIntrinsicWidth() != fallback.getIntrinsicHeight()) {
                    throw new AssertionError("Old launcher fallback is not the square PNG icon");
                }
            }
            if (Build.VERSION.SDK_INT >= 21) {
                Drawable banner = pm.getActivityBanner(new ComponentName(getTargetContext(), MainActivity.class));
                if (banner == null || Math.abs(banner.getIntrinsicWidth() * 9 - banner.getIntrinsicHeight() * 16) > 16) {
                    throw new AssertionError("Missing or non-16:9 TV banner");
                }
                if (!"VectorDrawable".equals(banner.getClass().getSimpleName())) {
                    throw new AssertionError("TV banner is not a self-contained vector");
                }
                Bitmap preview = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
                banner.setBounds(0, 0, 640, 360);
                banner.draw(new Canvas(preview));
                for (int[] corner : new int[][]{{0,0},{639,0},{0,359},{639,359}}) {
                    if (preview.getPixel(corner[0], corner[1]) != 0xff246fdc) {
                        throw new AssertionError("Vector banner background is missing or transparent");
                    }
                }
                FileOutputStream file = new FileOutputStream(new File(
                        getTargetContext().getExternalFilesDir(null), "tv-banner-check.png"));
                try { preview.compress(Bitmap.CompressFormat.PNG, 100, file); }
                finally { file.close(); preview.recycle(); }
            }
            Bitmap bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
            icon.setBounds(0, 0, 256, 256);
            icon.draw(new Canvas(bitmap));
            File image = new File(getTargetContext().getExternalFilesDir(null), "launcher-icon-check.png");
            FileOutputStream output = new FileOutputStream(image);
            try { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); }
            finally { output.close(); bitmap.recycle(); }
            result.putString("stream", "PASS API " + Build.VERSION.SDK_INT + ": " + kind
                    + "; launcher resources load and draw; " + image + "\n");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", android.util.Log.getStackTraceString(error));
            finish(0, result);
        }
    }
}
