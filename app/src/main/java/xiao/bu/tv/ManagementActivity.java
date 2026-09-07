package xiao.bu.tv;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.ValueCallback;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class ManagementActivity extends Activity {
    static final String EXTRA_URL = "management_url";
    static final String EXTRA_TAKEOVER = "takeover_mode";
    private static final int FILE_CHOOSER_REQUEST = 4601;
    private static final int SCREENSHOT_SAVE_REQUEST = 4602;
    private byte[] pendingScreenshot;
    private boolean screenshotBusy;
    private WebView webView;
    private String managementUrl;
    private ValueCallback<Uri[]> filePathCallback;
    private ValueCallback<Uri> legacyFileCallback;
    private NativeDeviceBridge nativeDeviceBridge;
    private boolean takeoverMode;
    private volatile boolean localPointerPage;
    private volatile boolean localPointerResumed;
    private final android.os.Handler recoveryHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private String currentPageUrl;
    private boolean pendingRendererRecovery;
    private int rendererRetries;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        takeoverMode = getIntent().getBooleanExtra(EXTRA_TAKEOVER, false);
        applySystemUiVisibility();
        managementUrl = getIntent().getStringExtra(EXTRA_URL);
        if (managementUrl == null || managementUrl.length() == 0) {
            finish();
            return;
        }
        createManagementWebView(takeoverMode ? flyMousePageUrl(managementUrl) : managementUrl);
    }

    private static String flyMousePageUrl(String baseUrl) {
        return Uri.parse(baseUrl).buildUpon()
                .path("/pages/flymouse.html")
                .clearQuery()
                .fragment(null)
                .build()
                .toString();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void createManagementWebView(String urlToLoad) {
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(247, 247, 248));
        // Several Android TV/tablet WebView implementations render a black frame when
        // a hardware-decoded Surface is paused underneath. The local control page is
        // lightweight, so software composition is more reliable here.
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        nativeDeviceBridge = new NativeDeviceBridge();
        webView.addJavascriptInterface(nativeDeviceBridge, "NtvDevice");
        webView.setWebChromeClient(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                ? new ModernFileChooserClient() : new LegacyFileChooserClient());
        WebViewRecovery.attach(webView, new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (view != webView) return;
                if (isLocalControlPage(url)) currentPageUrl = url;
                cancelLocalPointer();
                // This bridge is only for our bundled touchpad, never a media website.
                localPointerPage = isLocalControlPage(url)
                        && "/pages/flymouse.html".equals(Uri.parse(url).getPath());
                updatePointerDrawing(CastKeepAliveService.localInputOwner());
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = Uri.parse(url);
                if (isLocalControlPage(url)) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (RuntimeException error) {
                    Toast.makeText(ManagementActivity.this,
                            "无法打开外部链接", Toast.LENGTH_SHORT).show();
                }
                return true;
            }

        }, this::onRendererGone);
        setContentView(webView);
        webView.loadUrl(urlToLoad);
    }

    private void updatePointerDrawing(MainActivity owner) {
        if (webView == null) return;
        // Software WebView drawing synchronously stalls the shared renderer.
        // Only our modern, local virtual-page controller has no local decoder
        // Surface underneath. Keep legacy and remote-TV fallback intact.
        boolean localCastPointer = Build.VERSION.SDK_INT >= 26 && localPointerPage
                && owner != null && owner.ownsLocalPointerPage(managementUrl)
                && owner.isLocalCastPointerActive();
        int layer = localCastPointer ? View.LAYER_TYPE_NONE : View.LAYER_TYPE_SOFTWARE;
        if (webView.getLayerType() != layer) webView.setLayerType(layer, null);
    }

    private void onRendererGone(WebView failed, boolean crashed) {
        if (failed != webView) return;
        final MainActivity pointerOwner = localPointerPage
                ? CastKeepAliveService.localInputOwner() : null;
        localPointerPage = false;
        webView = null;
        if (nativeDeviceBridge != null) nativeDeviceBridge.stopSensors();
        nativeDeviceBridge = null;
        // These callbacks belong to the dead renderer; never call back into it.
        filePathCallback = null;
        legacyFileCallback = null;
        showRendererRecoveryPanel();
        // Do not dispatch events into another WebView while Chromium is still
        // notifying the views sharing this dead renderer.
        if (pointerOwner != null) recoveryHandler.post(new Runnable() {
            @Override public void run() {
                if (pointerOwner.ownsLocalPointerPage(managementUrl)) {
                    try { pointerOwner.handleLocalPointer(new org.json.JSONObject().put("action", "cancel")); }
                    catch (Exception ignored) { }
                }
            }
        });
        pendingRendererRecovery = rendererRetries++ < 1;
        if (pendingRendererRecovery) {
            recoveryHandler.postDelayed(this::recoverManagementPage, 750L);
        }
    }

    private void showRendererRecoveryPanel() {
        android.widget.LinearLayout panel = new android.widget.LinearLayout(this);
        panel.setOrientation(android.widget.LinearLayout.VERTICAL);
        panel.setGravity(android.view.Gravity.CENTER);
        panel.setBackgroundColor(Color.rgb(247, 247, 248));
        android.widget.TextView message = new android.widget.TextView(this);
        message.setText("网页渲染进程已退出，应用仍在运行。\n可重新打开管理界面。");
        message.setTextColor(Color.DKGRAY);
        message.setTextSize(18);
        message.setGravity(android.view.Gravity.CENTER);
        panel.addView(message);
        android.widget.Button retry = new android.widget.Button(this);
        retry.setText("重新打开管理界面");
        retry.setOnClickListener(view -> {
            rendererRetries = 0;
            pendingRendererRecovery = true;
            recoverManagementPage();
        });
        panel.addView(retry);
        setContentView(panel);
    }

    private void recoverManagementPage() {
        if (!pendingRendererRecovery || !localPointerResumed || isFinishing() || webView != null) return;
        pendingRendererRecovery = false;
        createManagementWebView(isLocalControlPage(currentPageUrl) ? currentPageUrl : managementUrl);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applySystemUiVisibility();
        }
    }

    private void applySystemUiVisibility() {
        int flags = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private Intent playlistFileIntent() {
        Intent intent = new Intent(Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT
                ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "text/plain",
                    "application/vnd.apple.mpegurl",
                    "application/x-mpegurl",
                    "audio/mpegurl",
                    "audio/x-mpegurl",
                    "application/octet-stream"
            });
        }
        return intent;
    }

    private void launchFileChooser() {
        try {
            startActivityForResult(Intent.createChooser(
                    playlistFileIntent(), "选择频道源文件"), FILE_CHOOSER_REQUEST);
        } catch (ActivityNotFoundException error) {
            cancelFileChooser();
            Toast.makeText(this, "系统中没有可用的文件管理器",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void cancelFileChooser() {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        if (legacyFileCallback != null) {
            legacyFileCallback.onReceiveValue(null);
            legacyFileCallback = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SCREENSHOT_SAVE_REQUEST) {
            final byte[] image = pendingScreenshot;
            pendingScreenshot = null;
            final Uri destination = data == null ? null : data.getData();
            if (resultCode != RESULT_OK || image == null || destination == null) {
                screenshotBusy = false;
                return;
            }
            new Thread(new Runnable() {
                @Override public void run() {
                    String message = "截屏已保存";
                    try {
                        OutputStream output = getContentResolver().openOutputStream(destination);
                        if (output == null) throw new IOException("无法打开保存位置");
                        try { output.write(image); } finally { output.close(); }
                    } catch (Exception error) {
                        message = "截屏保存失败：" + error.getMessage();
                    }
                    final String result = message;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            screenshotBusy = false;
                            Toast.makeText(ManagementActivity.this, result, Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }, "save-video-screenshot").start();
            return;
        }
        if (requestCode != FILE_CHOOSER_REQUEST) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(resultCode == RESULT_OK
                    ? ModernResultParser.resultUris(data) : null);
            filePathCallback = null;
        }
        if (legacyFileCallback != null) {
            legacyFileCallback.onReceiveValue(resultCode == RESULT_OK && data != null
                    ? data.getData() : null);
            legacyFileCallback = null;
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private final class ModernFileChooserClient extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                FileChooserParams params) {
            cancelFileChooser();
            filePathCallback = callback;
            launchFileChooser();
            return true;
        }

    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private static final class ModernResultParser {
        static Uri[] resultUris(Intent data) {
            if (data == null) {
                return null;
            }
            ClipData clips = data.getClipData();
            if (clips != null && clips.getItemCount() > 0) {
                Uri[] values = new Uri[clips.getItemCount()];
                for (int index = 0; index < clips.getItemCount(); index++) {
                    values[index] = clips.getItemAt(index).getUri();
                }
                return values;
            }
            Uri value = data.getData();
            return value == null ? null : new Uri[]{value};
        }
    }

    @SuppressWarnings("unused")
    private final class LegacyFileChooserClient extends WebChromeClient {
        public void openFileChooser(ValueCallback<Uri> callback) {
            openFileChooser(callback, "*/*");
        }

        public void openFileChooser(ValueCallback<Uri> callback, String acceptType) {
            openFileChooser(callback, acceptType, "");
        }

        public void openFileChooser(ValueCallback<Uri> callback, String acceptType,
                String capture) {
            cancelFileChooser();
            legacyFileCallback = callback;
            launchFileChooser();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        finishOrConfirmTakeover();
    }

    private void finishOrConfirmTakeover() {
        if (takeoverMode) setResult(RESULT_OK);
        finish();
    }

    private final class NativeDeviceBridge implements SensorEventListener {
        private final SensorManager sensorManager = (SensorManager)
                getSystemService(SENSOR_SERVICE);
        private final Sensor gyroscope = sensorManager == null ? null
                : sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        private boolean listening;
        private long lastHapticTickAt;

        @JavascriptInterface
        public String sendPointer(String body) {
            final MainActivity target = CastKeepAliveService.localInputOwner();
            if (!localPointerPage || !localPointerResumed || target == null
                    || !target.ownsLocalPointerPage(managementUrl)) return "";
            if (body == null || body.length() > 4096) {
                return "{\"ok\":false,\"message\":\"飞鼠指令过长\"}";
            }
            final org.json.JSONObject request;
            try { request = new org.json.JSONObject(body); }
            catch (org.json.JSONException error) {
                return "{\"ok\":false,\"message\":\"飞鼠指令无效\"}";
            }
            // The JavaScript bridge already runs away from the UI thread. Motion
            // is coalesced there by MainActivity and only one VSYNC task reaches
            // the main looper; button boundaries still flush motion in order.
            try {
                target.handleLocalPointer(request);
            } catch (final Exception error) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!isFinishing()) Toast.makeText(ManagementActivity.this,
                                error.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            }
            return "{\"ok\":true,\"transport\":\"local\"}";
        }

        @JavascriptInterface
        public void navigateBack() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (webView != null && !isFinishing() && isLocalControlPage(webView.getUrl())) {
                        onBackPressed();
                    }
                }
            });
        }

        @JavascriptInterface
        public void saveVideoScreenshot() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (webView == null || isFinishing() || screenshotBusy
                            || !isLocalControlPage(webView.getUrl())
                            || !"/video-recorder.html".equals(Uri.parse(webView.getUrl()).getPath())) {
                        return;
                    }
                    if (Build.VERSION.SDK_INT < 19) {
                        Toast.makeText(ManagementActivity.this, "请使用手机浏览器保存截屏",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    screenshotBusy = true;
                    final String url = Uri.parse(managementUrl).buildUpon()
                            .path(VideoScreenshot.PATH).clearQuery().fragment(null).build().toString();
                    Toast.makeText(ManagementActivity.this, "正在截取视频画面…", Toast.LENGTH_SHORT).show();
                    new Thread(new Runnable() {
                        @Override public void run() {
                            try {
                                final byte[] image = VideoScreenshot.download(url);
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        if (isFinishing() || webView == null) {
                                            screenshotBusy = false;
                                            return;
                                        }
                                        pendingScreenshot = image;
                                        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                                                .addCategory(Intent.CATEGORY_OPENABLE).setType("image/png")
                                                .putExtra(Intent.EXTRA_TITLE, "nTv-" + new SimpleDateFormat(
                                                        "yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".png");
                                        try {
                                            startActivityForResult(intent, SCREENSHOT_SAVE_REQUEST);
                                        } catch (RuntimeException error) {
                                            pendingScreenshot = null;
                                            screenshotBusy = false;
                                            Toast.makeText(ManagementActivity.this,
                                                    "系统没有文件保存组件，请使用手机浏览器截屏", Toast.LENGTH_LONG).show();
                                        }
                                    }
                                });
                            } catch (final IOException error) {
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        screenshotBusy = false;
                                        Toast.makeText(ManagementActivity.this,
                                                error.getMessage(), Toast.LENGTH_LONG).show();
                                    }
                                });
                            }
                        }
                    }, "capture-video-screenshot").start();
                }
            });
        }

        @JavascriptInterface
        public boolean hasGyroscope() {
            return gyroscope != null;
        }

        @JavascriptInterface
        public void startGyroscope() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (!listening && sensorManager != null && gyroscope != null) {
                        listening = sensorManager.registerListener(NativeDeviceBridge.this,
                                gyroscope, SensorManager.SENSOR_DELAY_GAME);
                    }
                }
            });
        }

        @JavascriptInterface
        public void stopGyroscope() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    stopSensors();
                }
            });
        }

        @JavascriptInterface
        public void vibrate(int durationMillis) {
            final int safeDuration = Math.max(1, Math.min(100, durationMillis));
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                    if (vibrator == null || !vibrator.hasVibrator()) {
                        return;
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(safeDuration,
                                VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        vibrator.vibrate(safeDuration);
                    }
                }
            });
        }

        @JavascriptInterface
        public void hapticTick() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - lastHapticTickAt < 18L) return;
                    lastHapticTickAt = now;
                    Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                    if (vibrator == null || !vibrator.hasVibrator()) return;
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            // Let the device map a short detent to its linear motor.
                            vibrator.vibrate(VibrationEffect.createPredefined(
                                    VibrationEffect.EFFECT_TICK));
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(VibrationEffect.createOneShot(7L, 72));
                        } else if (!webView.performHapticFeedback(
                                HapticFeedbackConstants.CLOCK_TICK)) {
                            vibrator.vibrate(7L);
                        }
                    } catch (RuntimeException error) {
                        webView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                }
            });
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (!listening || event == null || event.values.length < 3
                    || webView == null) {
                return;
            }
            final String script = String.format(Locale.US,
                    "window.__ntvNativeGyroscope&&window.__ntvNativeGyroscope(%.7f,%.7f,%.7f)",
                    event.values[0], event.values[1], event.values[2]);
            webView.evaluateJavascript(script, null);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }

        void stopSensors() {
            if (sensorManager != null && listening) {
                sensorManager.unregisterListener(this);
            }
            listening = false;
        }
    }

    private boolean isLocalControlPage(String url) {
        if (!isWebPage(url) || managementUrl == null) {
            return false;
        }
        Uri base = Uri.parse(managementUrl);
        Uri current = Uri.parse(url);
        return equalsIgnoreCase(base.getScheme(), current.getScheme())
                && equalsIgnoreCase(base.getHost(), current.getHost())
                && effectivePort(base) == effectivePort(current);
    }

    private static int effectivePort(Uri uri) {
        if (uri == null) {
            return -1;
        }
        int port = uri.getPort();
        if (port >= 0) {
            return port;
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean equalsIgnoreCase(String first, String second) {
        return first == null ? second == null : second != null
                && first.equalsIgnoreCase(second);
    }

    private static boolean isWebPage(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(java.util.Locale.US);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    @Override
    protected void onResume() {
        super.onResume();
        localPointerResumed = true;
        recoverManagementPage();
        applySystemUiVisibility();
        if (webView != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.evaluateJavascript(
                    "window.resumeRemoteControl&&window.resumeRemoteControl()", null);
        }
    }

    @Override
    protected void onPause() {
        localPointerResumed = false;
        cancelLocalPointer();
        if (webView != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.evaluateJavascript(
                    "window.suspendRemoteControl&&window.suspendRemoteControl()", null);
        }
        if (nativeDeviceBridge != null) {
            nativeDeviceBridge.stopSensors();
        }
        super.onPause();
    }

    private void cancelLocalPointer() {
        MainActivity target = CastKeepAliveService.localInputOwner();
        if (!localPointerPage || target == null || !target.ownsLocalPointerPage(managementUrl)) return;
        try { target.handleLocalPointer(new org.json.JSONObject().put("action", "cancel")); }
        catch (Exception ignored) { }
    }

    @Override
    protected void onDestroy() {
        recoveryHandler.removeCallbacksAndMessages(null);
        pendingRendererRecovery = false;
        cancelLocalPointer();
        localPointerPage = false;
        pendingScreenshot = null;
        cancelFileChooser();
        if (nativeDeviceBridge != null) {
            nativeDeviceBridge.stopSensors();
            nativeDeviceBridge = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
