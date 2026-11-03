package xiao.bu.tv;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Base64;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.ValueCallback;
import android.widget.Toast;

import java.util.Locale;
import java.util.WeakHashMap;
import java.io.ByteArrayOutputStream;

public final class ManagementActivity extends Activity {
    // Accessed only on the main thread. Closing a page must not end its cast session.
    private static final WeakHashMap<ManagementActivity, Boolean> openPages =
            new WeakHashMap<ManagementActivity, Boolean>();

    static void closeAll() {
        ManagementActivity[] pages = openPages.keySet().toArray(new ManagementActivity[0]);
        openPages.clear();
        for (ManagementActivity page : pages) {
            page.setResult(RESULT_CANCELED);
            page.finish();
        }
    }

    static final String EXTRA_URL = "management_url";

    private static final int FILE_CHOOSER_REQUEST = 4601;
    private static final int SCREENSHOT_PERMISSION_REQUEST = 4602;
    private boolean screenshotPermissionPending;
    private boolean screenshotBusy;
    private volatile String screenshotPreviewData;
    private WebView webView;
    private String managementUrl;
    private ValueCallback<Uri[]> filePathCallback;
    private ValueCallback<Uri> legacyFileCallback;
    private NativeDeviceBridge nativeDeviceBridge;

    private boolean clearHistoryAfterTakeover;
    private volatile boolean localPointerPage;
    private volatile boolean localPointerResumed;
    private final android.os.Handler recoveryHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private String currentPageUrl;
    private boolean pendingRendererRecovery;
    private int rendererRetries;
    private volatile boolean systemDark;
    private Boolean pageDark;
    private View managementPanel;
    private boolean sidebarDevice;

    static void refreshSystemUi() {
        for (ManagementActivity page : openPages.keySet().toArray(new ManagementActivity[0])) {
            if (page != null && !page.isFinishing()) page.applySystemUiVisibility();
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sidebarDevice = usesSidebar(this);
        applyRequestedOrientation();
        openPages.put(this, Boolean.TRUE);
        applySystemUiVisibility();
        managementUrl = getIntent().getStringExtra(EXTRA_URL);
        if (managementUrl == null || managementUrl.length() == 0) {
            finish();
            return;
        }
        createManagementWebView(managementUrl);
    }

    public static boolean isTablet(Context context) {
        Configuration config = context.getResources().getConfiguration();
        return config.smallestScreenWidthDp >= 600;
    }

    static boolean usesSidebar(Context context) {
        if (isTablet(context)) return true;
        android.view.WindowManager manager = (android.view.WindowManager)
                context.getSystemService(Context.WINDOW_SERVICE);
        if (manager == null) return false;
        android.view.Display display = manager.getDefaultDisplay();
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        display.getMetrics(metrics);
        return hasLandscapeNaturalOrientation(metrics.widthPixels, metrics.heightPixels,
                display.getRotation());
    }

    static boolean hasLandscapeNaturalOrientation(int width, int height, int rotation) {
        // Some landscape tablets/TVs report only 480 dp. Do not force these into
        // the phone's portrait window. Undo rotation so a rotated phone stays a phone.
        boolean quarterTurn = rotation == android.view.Surface.ROTATION_90
                || rotation == android.view.Surface.ROTATION_270;
        return quarterTurn ? height > width : width > height;
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
        readSystemTheme();
        try {
            webView = WebViewAvailability.create(() -> new WebView(this));
        } catch (WebViewAvailability.UnavailableException error) {
            pendingRendererRecovery = false;
            android.util.Log.e("ManagementActivity", "WebView provider unavailable", error);
            android.widget.LinearLayout panel = new android.widget.LinearLayout(this);
            panel.setOrientation(android.widget.LinearLayout.VERTICAL);
            panel.setGravity(Gravity.CENTER);
            panel.setBackgroundColor(Color.rgb(247, 247, 248));
            android.widget.TextView message = new android.widget.TextView(this);
            message.setText(WebViewAvailability.MESSAGE + "\n也可在手机浏览器打开：\n" + managementUrl);
            message.setTextColor(Color.DKGRAY);
            message.setTextSize(18);
            message.setGravity(Gravity.CENTER);
            panel.addView(message);
            android.widget.Button back = new android.widget.Button(this);
            back.setText("返回");
            back.setOnClickListener(view -> finish());
            panel.addView(back);
            managementPanel = panel;
            setContentView(panel);
            return;
        }
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setBackgroundColor(systemDark ? Color.rgb(17, 17, 19) : Color.rgb(247, 247, 248));
        // Retain the decoder-overlay workaround on TVs/tablets and legacy WebViews.
        // Phones show an opaque page: use the normal GPU compositor
        // there so scrolling does not repaint the entire WebView in software.
        webView.setLayerType(Build.VERSION.SDK_INT >= 21 && !sidebarDevice
                ? View.LAYER_TYPE_NONE : View.LAYER_TYPE_SOFTWARE, null);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        nativeDeviceBridge = new NativeDeviceBridge();
        webView.addJavascriptInterface(nativeDeviceBridge, "NtvDevice");
        webView.setWebChromeClient(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                ? new ModernFileChooserClient() : new LegacyFileChooserClient());
        WebViewRecovery.attach(webView, new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (view == webView) refreshPageTheme();
                if (view == webView && clearHistoryAfterTakeover
                        && flyMousePageUrl(managementUrl).equals(url)) {
                    clearHistoryAfterTakeover = false;
                    view.clearHistory();
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (view != webView) return;
                if (!sidebarDevice && !MultiWindowCompat.isInMultiWindowMode(
                        ManagementActivity.this)) {
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                }
                if (isLocalControlPage(url)) currentPageUrl = url;
                cancelLocalPointer();
                // This bridge is only for our bundled touchpad, never a media website.
                localPointerPage = isLocalControlPage(url)
                        && "/pages/flymouse.html".equals(Uri.parse(url).getPath());
                updatePointerDrawing(LocalPlayerRegistry.localInputOwner());
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
        final View outside = new View(this);
        outside.setContentDescription("关闭管理网页");
        outside.setOnClickListener(view -> finish());
        FrameLayout panel = new FrameLayout(this) {
            private boolean sidebar;
            private int paneLeft;

            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = View.MeasureSpec.getSize(widthSpec), height = View.MeasureSpec.getSize(heightSpec);
                sidebar = sidebarDevice && width > height;
                if (sidebar) {
                    // Keep the 720:1280 portrait proportions, but rasterize text at
                    // the final screen resolution. Scaling a software WebView layer
                    // blurs glyphs, particularly when a 4K display enlarges that layer.
                    int paneWidth = Math.round(height * 720f / 1280f);
                    paneLeft = width - paneWidth;
                    setMeasuredDimension(width, height);
                    webView.measure(View.MeasureSpec.makeMeasureSpec(paneWidth, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                    outside.measure(View.MeasureSpec.makeMeasureSpec(paneLeft, View.MeasureSpec.EXACTLY), heightSpec);
                } else {
                    paneLeft = 0;
                    super.onMeasure(widthSpec, heightSpec);
                }
                outside.setVisibility(sidebar ? View.VISIBLE : View.GONE);
            }

            @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                if (sidebar) {
                    outside.layout(0, 0, paneLeft, bottom - top);
                    webView.layout(paneLeft, 0, right - left, bottom - top);
                } else {
                    super.onLayout(changed, left, top, right, bottom);
                }
            }
        };
        panel.addView(outside, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        panel.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.RIGHT));
        if (!sidebarDevice) {
            panel.setBackgroundColor(systemDark ? Color.rgb(17, 17, 19) : Color.rgb(247, 247, 248));
            panel.setFitsSystemWindows(true);
        }
        managementPanel = panel;
        setContentView(panel);
        webView.loadUrl(urlToLoad);
    }

    private void updatePointerDrawing(MainActivity owner) {
        // Local management uses software composition above the native decoder.
    }

    private void onRendererGone(WebView failed, boolean crashed) {
        if (failed != webView) return;
        final MainActivity pointerOwner = localPointerPage
                ? LocalPlayerRegistry.localInputOwner() : null;
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
        managementPanel = panel;
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
        if (!sidebarDevice && !FullscreenPolicy.isTelevision(this) && Build.VERSION.SDK_INT >= 21) {
            // Keep phone status icons visible on the management page, with its
            // background extending behind them. The root reserves system insets.
            readSystemTheme();
            boolean dark = pageDark != null ? pageDark.booleanValue() : systemDark;
            int background = dark ? Color.rgb(17, 17, 19) : Color.rgb(247, 247, 248);
            if (managementPanel != null) managementPanel.setBackgroundColor(background);
            if (webView != null) webView.setBackgroundColor(background);
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
                    | android.view.WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN
                    | android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(background));
            getWindow().setStatusBarColor(Build.VERSION.SDK_INT >= 23 || dark
                    ? Color.TRANSPARENT : Color.rgb(48, 48, 48));
            getWindow().setNavigationBarColor(Build.VERSION.SDK_INT >= 26 || dark
                    ? background : Color.rgb(48, 48, 48));
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            if (Build.VERSION.SDK_INT >= 23 && !dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26 && !dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(flags);
            return;
        }
        if (MultiWindowCompat.isInMultiWindowMode(this)) {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            return;
        }
        getWindow().clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(FullscreenPolicy.flags(this, false));
    }

    private void launchFileChooser(String[] accepted) {
        try {
            Intent chooser = fileChooserIntent(accepted);
            if (chooser.resolveActivity(getPackageManager()) == null)
                chooser.setAction(Intent.ACTION_GET_CONTENT);
            startActivityForResult(Intent.createChooser(chooser, "选择文件"), FILE_CHOOSER_REQUEST);
        } catch (RuntimeException error) {
            cancelFileChooser();
            notifyFileChooser("无法打开文件选择器，请检查系统文件管理器");
            android.util.Log.w("ManagementActivity", "File chooser launch failed", error);
            Toast.makeText(this, "系统中没有可用的文件管理器",
                    Toast.LENGTH_LONG).show();
        }
    }

    static Intent fileChooserIntent(String[] accepted) {
        java.util.LinkedHashSet<String> types = new java.util.LinkedHashSet<>();
        if (accepted != null) for (String value : accepted) {
            if (value == null) continue;
            // Chromium may return one comma-separated entry, rather than one entry
            // per MIME type. A comma is invalid in Intent.setType().
            for (String part : value.split(",")) {
                String mime = part.trim().toLowerCase(Locale.US);
                if (mime.startsWith(".")) mime = android.webkit.MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(mime.substring(1));
                if (mime != null && mime.matches("[a-z0-9!#$&^_.+*\\-]+/[a-z0-9!#$&^_.+*\\-]+")) types.add(mime);
            }
        }
        Intent chooser = new Intent(Build.VERSION.SDK_INT >= 19 ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_GET_CONTENT);
        chooser.addCategory(Intent.CATEGORY_OPENABLE);
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        chooser.setType(types.size() == 1 ? types.iterator().next() : "*/*");
        if (Build.VERSION.SDK_INT >= 19 && types.size() > 1)
            chooser.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[types.size()]));
        return chooser;
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
        if (requestCode != FILE_CHOOSER_REQUEST) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        Uri[] values = null;
        if (resultCode == RESULT_OK && data != null) {
            values = Build.VERSION.SDK_INT >= 16 ? ModernResultParser.resultUris(data)
                    : data.getData() == null ? null : new Uri[] { data.getData() };
        }
        boolean delivered = values != null && values.length > 0 && values[0] != null;
        android.util.Log.i("ManagementActivity", "File chooser result=" + resultCode
                + " hasFile=" + delivered + " callback="
                + (filePathCallback != null || legacyFileCallback != null));
        notifyFileChooser(delivered ? "" : resultCode == RESULT_OK
                ? "选择器未返回可读取的文件，请换用系统文件管理器" : "已取消选择文件");
        ValueCallback<Uri[]> modern = filePathCallback;
        ValueCallback<Uri> legacy = legacyFileCallback;
        filePathCallback = null;
        legacyFileCallback = null;
        try {
            if (modern != null) modern.onReceiveValue(delivered ? values : null);
            if (legacy != null) legacy.onReceiveValue(delivered ? values[0] : null);
        } catch (RuntimeException error) {
            android.util.Log.w("ManagementActivity", "File chooser callback failed", error);
            notifyFileChooser("文件回传失败，请重新打开管理页面后重试");
        }
    }

    private void notifyFileChooser(String message) {
        if (webView == null) return;
        String script = "window.multimediaChooserResult&&window.multimediaChooserResult("
                + org.json.JSONObject.quote(message) + ")";
        if (Build.VERSION.SDK_INT >= 19) webView.evaluateJavascript(script, null);
        else webView.loadUrl("javascript:" + script);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private final class ModernFileChooserClient extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                FileChooserParams params) {
            cancelFileChooser();
            filePathCallback = callback;
            launchFileChooser(params == null ? null : params.getAcceptTypes());
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
            launchFileChooser(acceptType == null ? null : acceptType.split(","));
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && Build.VERSION.SDK_INT >= 19) {
            final String backPageUrl = webView.getUrl();
            webView.evaluateJavascript("(function(){return window.NtvNavigation ? NtvNavigation.back(true) : null;})()",
                    new ValueCallback<String>() {
                        @Override public void onReceiveValue(String value) {
                            if (isFinishing() || webView == null
                                    || !android.text.TextUtils.equals(backPageUrl, webView.getUrl())) return;
                            if ("false".equals(value)) finish();
                            else if (!"true".equals(value)) navigateBack();
                        }
                    });
            return;
        }
        if (webView != null) {
            webView.loadUrl("javascript:(function(){var n=window.NtvNavigation;"
                    + "if(!n)NtvDevice.navigateBackAfterSheet();"
                    + "else if(!n.back(true))NtvDevice.closeManagement();})()");
            return;
        }
        navigateBack();
    }

    private void navigateBack() {
        // Fallback for unloaded/legacy pages. Never forward management Back to TV playback.
        if (webView != null && isLocalControlPage(webView.getUrl())) {
            String currentPath = Uri.parse(webView.getUrl()).getPath();
            if ("/".equals(currentPath) || "/index.html".equals(currentPath)) {
                finish();
                return;
            }
            android.webkit.WebBackForwardList history = webView.copyBackForwardList();
            int previous = history.getCurrentIndex() - 1;
            if (previous >= 0) {
                String url = history.getItemAtIndex(previous).getUrl();
                String path = Uri.parse(url).getPath();
                if (isLocalControlPage(url) && ("/".equals(path)
                        || (path != null && path.endsWith(".html") && ControlSite.contains(path)))) {
                    webView.goBack();
                    return;
                }
            }
            String home = Uri.parse(managementUrl).buildUpon().path("/index.html")
                    .clearQuery().fragment(null).build().toString();
            webView.loadUrl("javascript:location.replace(" + org.json.JSONObject.quote(home) + ")");
            return;
        }
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != SCREENSHOT_PERMISSION_REQUEST) return;
        screenshotPermissionPending = false;
        if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            nativeDeviceBridge.saveVideoScreenshot();
        } else {
            Toast.makeText(this, "未允许存储权限，无法保存截图到相册", Toast.LENGTH_LONG).show();
        }
    }

    /** A small display-only copy; the image saved to the gallery remains source resolution. */
    private static ScreenshotPreview createScreenshotPreview(byte[] image) {
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(image, 0, image.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (Math.max(bounds.outWidth / options.inSampleSize,
                    bounds.outHeight / options.inSampleSize) > 960) {
                options.inSampleSize *= 2;
            }
            bitmap = BitmapFactory.decodeByteArray(image, 0, image.length, options);
            if (bitmap == null) return null;
            ByteArrayOutputStream output = new ByteArrayOutputStream(96 * 1024);
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 78, output)) return null;
            return new ScreenshotPreview("data:image/jpeg;base64,"
                    + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP),
                    bounds.outWidth, bounds.outHeight);
        } catch (RuntimeException ignored) {
            return null;
        } catch (OutOfMemoryError ignored) {
            return null;
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    private static final class ScreenshotPreview {
        final String dataUri;
        final int width;
        final int height;

        ScreenshotPreview(String dataUri, int width, int height) {
            this.dataUri = dataUri;
            this.width = width;
            this.height = height;
        }
    }

    private final class NativeDeviceBridge implements SensorEventListener {
        @JavascriptInterface
        public String consumeScreenshotPreview() {
            String preview = screenshotPreviewData;
            screenshotPreviewData = null;
            return preview == null ? "" : preview;
        }

        @JavascriptInterface
        public boolean returnFromSniffedResource() {
            MainActivity owner = LocalPlayerRegistry.localInputOwner();
            if (owner == null || !owner.hasRetainedWebPlayback() || !isLocalControlPage(currentPageUrl)) return false;
            runOnUiThread(() -> {
                if (owner.returnToRetainedWebPage() && owner.isFinishing()) finish();
            });
            return true;
        }

        @JavascriptInterface
        public boolean returnFromMultimedia() {
            MainActivity owner=LocalPlayerRegistry.localInputOwner();
            if(owner==null || !owner.hasActiveMultimedia() || !isLocalControlPage(currentPageUrl))return false;
            runOnUiThread(() -> owner.backFromMultimedia());return true;
        }

        @JavascriptInterface
        public void setKeyboardLandscape(final boolean landscape) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (webView == null || isFinishing() || sidebarDevice
                            || MultiWindowCompat.isInMultiWindowMode(ManagementActivity.this)
                            || !isLocalControlPage(webView.getUrl())
                            || !"/pages/flymouse.html".equals(Uri.parse(webView.getUrl()).getPath())) return;
                    setRequestedOrientation(landscape
                            ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                }
            });
        }
        @JavascriptInterface
        public void navigateBackAfterSheet() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (webView != null && !isFinishing() && isLocalControlPage(webView.getUrl())) ManagementActivity.this.navigateBack();
                }
            });
        }
        @JavascriptInterface
        public void closeManagement() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (webView == null || !isLocalControlPage(webView.getUrl())) return;
                    String path = Uri.parse(webView.getUrl()).getPath();
                    if ("/".equals(path) || "/index.html".equals(path)) finish();
                }
            });
        }
        @JavascriptInterface
        public boolean isSystemDark() {
            return systemDark;
        }
        @JavascriptInterface
        public void setPageDark(final boolean dark) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (webView == null || isFinishing() || !isLocalControlPage(webView.getUrl())) return;
                    pageDark = Boolean.valueOf(dark);
                    applySystemUiVisibility();
                }
            });
        }
        private final SensorManager sensorManager = (SensorManager)
                getSystemService(SENSOR_SERVICE);
        private final Sensor gyroscope = sensorManager == null ? null
                : sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        private boolean listening;
        private long lastHapticTickAt;

        @JavascriptInterface
        public String sendPointer(String body) {
            final MainActivity target = LocalPlayerRegistry.localInputOwner();
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
                org.json.JSONObject response = new org.json.JSONObject(
                        target.handleLocalPointer(request));
                response.put("transport", "local");
                return response.toString();
            } catch (final Exception error) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!isFinishing()) Toast.makeText(ManagementActivity.this,
                                error.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            }
            return "{\"ok\":false,\"transport\":\"local\",\"message\":\"飞鼠操作失败\"}";
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
                            || !("/video-recorder.html".equals(Uri.parse(webView.getUrl()).getPath())
                                || "/pages/media.html".equals(Uri.parse(webView.getUrl()).getPath()))) {
                        return;
                    }
                    if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28
                            && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        if (!screenshotPermissionPending) {
                            screenshotPermissionPending = true;
                            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                                    SCREENSHOT_PERMISSION_REQUEST);
                        }
                        return;
                    }
                    screenshotBusy = true;
                    screenshotPreviewData = null;
                    final String url = Uri.parse(managementUrl).buildUpon()
                            .path(VideoScreenshot.PATH).clearQuery().fragment(null).build().toString();
                    Toast.makeText(ManagementActivity.this, "正在截取视频画面…", Toast.LENGTH_SHORT).show();
                    new Thread(new Runnable() {
                        @Override public void run() {
                            try {
                                final byte[] image = VideoScreenshot.download(url);
                                ScreenshotGallery.save(getApplicationContext(), image);
                                final ScreenshotPreview preview = createScreenshotPreview(image);
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        screenshotBusy = false;
                                        Toast.makeText(ManagementActivity.this,
                                                preview == null
                                                        ? "原图已保存到相册，当前设备无法生成回显"
                                                        : "原图已保存到相册 Pictures/nTv",
                                                Toast.LENGTH_LONG).show();
                                        if (preview != null && webView != null && !isFinishing()
                                                && isLocalControlPage(webView.getUrl())
                                                && "/pages/media.html".equals(
                                                        Uri.parse(webView.getUrl()).getPath())) {
                                            screenshotPreviewData = preview.dataUri;
                                            String script = "window.mediaNativeScreenshotReady&&"
                                                    + "window.mediaNativeScreenshotReady("
                                                    + preview.width + "," + preview.height + ")";
                                            if (Build.VERSION.SDK_INT >= 19)
                                                webView.evaluateJavascript(script, null);
                                            else webView.loadUrl("javascript:" + script);
                                        }
                                    }
                                });
                            } catch (final Exception error) {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                webView.evaluateJavascript(script, null);
            } else {
                webView.loadUrl("javascript:" + script);
            }
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
        readSystemTheme();
        refreshPageTheme();
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

    private void readSystemTheme() {
        systemDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private void refreshPageTheme() {
        if (webView == null || !isLocalControlPage(webView.getUrl())) return;
        String script = "window.refreshSystemTheme&&window.refreshSystemTheme()";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) webView.evaluateJavascript(script, null);
        else webView.loadUrl("javascript:" + script);
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        readSystemTheme();
        refreshPageTheme();
        applySystemUiVisibility();
    }

    @TargetApi(Build.VERSION_CODES.N)
    @Override
    public void onMultiWindowModeChanged(boolean inMultiWindowMode,
            Configuration configuration) {
        super.onMultiWindowModeChanged(inMultiWindowMode, configuration);
        applyRequestedOrientation();
        applySystemUiVisibility();
    }

    private void applyRequestedOrientation() {
        if (MultiWindowCompat.isInMultiWindowMode(this)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            return;
        }
        // A translucent side panel must inherit the player's orientation. A
        // sensor-landscape request can flip 180 degrees while the fixed-landscape
        // player stays underneath, then flip back as soon as this panel closes.
        int orientation = sidebarDevice ? ActivityInfo.SCREEN_ORIENTATION_BEHIND
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        if (getRequestedOrientation() != orientation) setRequestedOrientation(orientation);
    }

    private void cancelLocalPointer() {
        MainActivity target = LocalPlayerRegistry.localInputOwner();
        if (!localPointerPage || target == null || !target.ownsLocalPointerPage(managementUrl)) return;
        try { target.handleLocalPointer(new org.json.JSONObject().put("action", "cancel")); }
        catch (Exception ignored) { }
    }

    @Override
    protected void onDestroy() {
        openPages.remove(this);
        recoveryHandler.removeCallbacksAndMessages(null);
        pendingRendererRecovery = false;
        cancelLocalPointer();
        localPointerPage = false;
        cancelFileChooser();
        if (nativeDeviceBridge != null) {
            nativeDeviceBridge.stopSensors();
            nativeDeviceBridge = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.onPause();
            // Detach the window before freeing Chromium's drawing resources.
            // Older GPU drivers cannot safely keep drawing a destroyed WebView.
            android.view.ViewParent parent = webView.getParent();
            if (parent instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) parent).removeView(webView);
            }
            webView.setWebChromeClient(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
