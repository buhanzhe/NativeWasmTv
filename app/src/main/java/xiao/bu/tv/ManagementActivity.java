package xiao.bu.tv;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
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
import android.view.Gravity;
import android.widget.FrameLayout;
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
import java.util.WeakHashMap;

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

    static boolean showTakeoverControls(String baseUrl) {
        for (ManagementActivity page : openPages.keySet()) {
            if (page.isFinishing() || page.webView == null || !page.isLocalControlPage(baseUrl)) continue;
            page.takeoverMode = true;
            page.clearHistoryAfterTakeover = true;
            page.webView.loadUrl(flyMousePageUrl(baseUrl));
            return true;
        }
        return false;
    }

    static final String EXTRA_URL = "management_url";
    static final String EXTRA_TAKEOVER = "takeover_mode";
    private static final int FILE_CHOOSER_REQUEST = 4601;
    private static final int SCREENSHOT_PERMISSION_REQUEST = 4602;
    private boolean screenshotPermissionPending;
    private boolean screenshotBusy;
    private WebView webView;
    private String managementUrl;
    private ValueCallback<Uri[]> filePathCallback;
    private ValueCallback<Uri> legacyFileCallback;
    private NativeDeviceBridge nativeDeviceBridge;
    private boolean takeoverMode;
    private boolean clearHistoryAfterTakeover;
    private volatile boolean localPointerPage;
    private volatile boolean localPointerResumed;
    private final android.os.Handler recoveryHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private String currentPageUrl;
    private boolean pendingRendererRecovery;
    private int rendererRetries;
    private volatile boolean systemDark;
    private boolean sidebarDevice;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sidebarDevice = usesSidebar(this);
        setRequestedOrientation(sidebarDevice
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        openPages.put(this, Boolean.TRUE);
        takeoverMode = getIntent().getBooleanExtra(EXTRA_TAKEOVER, false);
        applySystemUiVisibility();
        managementUrl = getIntent().getStringExtra(EXTRA_URL);
        if (managementUrl == null || managementUrl.length() == 0) {
            finish();
            return;
        }
        createManagementWebView(takeoverMode ? flyMousePageUrl(managementUrl) : managementUrl);
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

    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        MainActivity owner = CastKeepAliveService.localInputOwner();
        if (owner != null && owner.dispatchCastVolumeKey(event)) return true;
        return super.dispatchKeyEvent(event);
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
        webView = new WebView(this);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setBackgroundColor(systemDark ? Color.rgb(17, 20, 25) : Color.rgb(247, 247, 248));
        // Several Android TV/tablet WebView implementations render a black frame when
        // a hardware-decoded Surface is paused underneath. The local control page is
        // lightweight, so software composition is more reliable here.
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
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
                if (!sidebarDevice) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
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
        setContentView(panel);
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

    private String localMultimediaTarget;
    private boolean localMultimediaPreserveAudio;

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
        if(localMultimediaTarget != null) {
            String target=localMultimediaTarget;localMultimediaTarget=null;
            Uri[] selected=Build.VERSION.SDK_INT>=16?ModernResultParser.resultUris(data):null;
            Uri uri=selected!=null && selected.length>0?selected[0]:data==null?null:data.getData();
            String error="";
            try {
                if(resultCode!=RESULT_OK || uri==null)throw new java.io.IOException("已取消选择文件");
                if(Build.VERSION.SDK_INT>=19 && (data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)!=0)
                    try {getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);} catch(SecurityException ignored) {}
                MainActivity owner=CastKeepAliveService.localInputOwner();
                if(owner==null)throw new java.io.IOException("播放器未就绪，请重新打开应用");
                String name="本地多媒体";
                try(android.database.Cursor cursor=getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)) {
                    if(cursor!=null && cursor.moveToFirst())name=cursor.getString(0);
                }
                owner.openLocalMultimedia(target,uri,name,localMultimediaPreserveAudio);
                android.util.Log.i("ManagementActivity","Local media URI opened without upload");
            } catch(Exception failure) {error=failure.getMessage();}
            notifyLocalMultimedia(error);
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
            notifyFileChooser("文件回传失败，请重新打开投屏页面后重试");
        }
    }

    private void notifyLocalMultimedia(String error) {
        if(webView==null)return;
        String script="window.multimediaLocalResult&&window.multimediaLocalResult("+org.json.JSONObject.quote(error==null?"无法打开文件":error)+")";
        if(Build.VERSION.SDK_INT>=19)webView.evaluateJavascript(script,null);
        else webView.loadUrl("javascript:"+script);
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
            webView.evaluateJavascript("(function(){return typeof window.mediaDismissSheet==='function' && window.mediaDismissSheet();})()",
                    new ValueCallback<String>() {
                        @Override public void onReceiveValue(String value) {
                            if (!"true".equals(value)) navigateBack();
                        }
                    });
            return;
        }
        if (webView != null) {
            webView.loadUrl("javascript:(function(){if(!(typeof window.mediaDismissSheet==='function' && window.mediaDismissSheet()))NtvDevice.navigateBackAfterSheet();})()");
            return;
        }
        navigateBack();
    }

    private void navigateBack() {
        MainActivity owner=CastKeepAliveService.localInputOwner();
        if(owner!=null && owner.backFromMultimedia())return;
        if(owner!=null && owner.returnToRetainedWebPage()) {
            if (!owner.isLocalCastPointerActive()) finish();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
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

    private final class NativeDeviceBridge implements SensorEventListener {
        @JavascriptInterface
        public boolean returnFromSniffedResource() {
            MainActivity owner = CastKeepAliveService.localInputOwner();
            if (owner == null || !owner.hasRetainedWebPlayback() || !isLocalControlPage(currentPageUrl)) return false;
            runOnUiThread(() -> {
                if (owner.returnToRetainedWebPage() && !owner.isLocalCastPointerActive()) finish();
            });
            return true;
        }

        @JavascriptInterface
        public boolean returnFromMultimedia() {
            MainActivity owner=CastKeepAliveService.localInputOwner();
            if(owner==null || !owner.hasActiveMultimedia() || !isLocalControlPage(currentPageUrl))return false;
            runOnUiThread(() -> owner.backFromMultimedia());return true;
        }
        @JavascriptInterface
        public void chooseLocalMultimedia(final String target, final boolean preserveAudio) {
            runOnUiThread(() -> {
                if(webView==null || isFinishing() || !isLocalControlPage(webView.getUrl())
                        || !"/pages/media.html".equals(Uri.parse(webView.getUrl()).getPath()))return;
                if(localMultimediaTarget!=null)return;
                localMultimediaTarget=target;localMultimediaPreserveAudio=preserveAudio;
                try {
                    Intent intent=fileChooserIntent(new String[]{"image/*","video/*","audio/*"});
                    intent.putExtra(Intent.EXTRA_LOCAL_ONLY,true);
                    startActivityForResult(intent,FILE_CHOOSER_REQUEST);
                } catch(RuntimeException failure) {
                    localMultimediaTarget=null;notifyLocalMultimedia("无法打开系统文件选择器");
                }
            });
        }

        @JavascriptInterface
        public void setKeyboardLandscape(final boolean landscape) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (webView == null || isFinishing() || sidebarDevice
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
        public boolean isSystemDark() {
            return systemDark;
        }
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
                    final String url = Uri.parse(managementUrl).buildUpon()
                            .path(VideoScreenshot.PATH).clearQuery().fragment(null).build().toString();
                    Toast.makeText(ManagementActivity.this, "正在截取视频画面…", Toast.LENGTH_SHORT).show();
                    new Thread(new Runnable() {
                        @Override public void run() {
                            try {
                                final byte[] image = VideoScreenshot.download(url);
                                ScreenshotGallery.save(getApplicationContext(), image);
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        screenshotBusy = false;
                                        Toast.makeText(ManagementActivity.this, "截图已保存到相册 Pictures/nTv",
                                                Toast.LENGTH_LONG).show();
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
    }

    private void cancelLocalPointer() {
        MainActivity target = CastKeepAliveService.localInputOwner();
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
