package xiao.bu.tv;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebBackForwardList;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.WebHistoryItem;
import android.webkit.ValueCallback;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class ManagementActivity extends Activity {
    static final String EXTRA_URL = "management_url";
    private static final int FILE_CHOOSER_REQUEST = 4601;
    private WebView webView;
    private boolean clearInitialHistory = true;
    private String managementUrl;
    private ValueCallback<Uri[]> filePathCallback;
    private ValueCallback<Uri> legacyFileCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applySystemUiVisibility();
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
        webView.setWebChromeClient(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                ? new ModernFileChooserClient() : new LegacyFileChooserClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = Uri.parse(url);
                if ("127.0.0.1".equals(uri.getHost())) {
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

            @Override
            public void onPageFinished(WebView view, String url) {
                if (clearInitialHistory && isWebPage(url)) {
                    clearInitialHistory = false;
                    view.clearHistory();
                }
            }
        });
        setContentView(webView);
        managementUrl = getIntent().getStringExtra(EXTRA_URL);
        if (managementUrl == null || managementUrl.length() == 0) {
            finish();
            return;
        }
        try {
            webView.loadDataWithBaseURL(managementUrl, readControlHtml(),
                    "text/html", "UTF-8", managementUrl);
        } catch (IOException error) {
            // The loopback URL remains a safe fallback if the bundled resource cannot
            // be read on an unusual vendor build.
            webView.loadUrl(managementUrl);
        }
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

    private String readControlHtml() throws IOException {
        InputStream input = getResources().openRawResource(R.raw.control);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        } finally {
            input.close();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        String currentUrl = webView.getUrl();
        // The management home is the terminal page. Never reopen a secondary menu
        // from its forward/back list after the user has already returned home.
        if (isManagementHome(currentUrl)) {
            finish();
            return;
        }
        if (goBackToPreviousLocalPage()) {
            return;
        }
        // A directly opened #section has no previous item. Collapse it to the home
        // document in-place so pressing Back does not reload or briefly show white.
        if (hasFragment(currentUrl)) {
            webView.loadUrl("javascript:window.handleManagementBack"
                    + "&&window.handleManagementBack()");
            return;
        }
        finish();
    }

    private boolean goBackToPreviousLocalPage() {
        WebBackForwardList history = webView.copyBackForwardList();
        if (history == null || history.getCurrentIndex() <= 0) {
            return false;
        }
        WebHistoryItem current = history.getItemAtIndex(history.getCurrentIndex());
        String currentUrl = current == null ? null : current.getUrl();
        for (int index = history.getCurrentIndex() - 1; index >= 0; index--) {
            WebHistoryItem item = history.getItemAtIndex(index);
            String candidate = item == null ? null : item.getUrl();
            if (!isLocalControlPage(candidate) || sameUrl(candidate, currentUrl)) {
                continue;
            }
            webView.goBackOrForward(index - history.getCurrentIndex());
            return true;
        }
        return false;
    }

    private boolean isManagementHome(String url) {
        if (!isLocalControlPage(url) || hasFragment(url)) {
            return false;
        }
        Uri base = Uri.parse(managementUrl);
        Uri current = Uri.parse(url);
        return safePath(base).equals(safePath(current));
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

    private static boolean hasFragment(String url) {
        return url != null && Uri.parse(url).getFragment() != null
                && Uri.parse(url).getFragment().length() > 0;
    }

    private static String safePath(Uri uri) {
        String path = uri == null ? null : uri.getPath();
        return path == null || path.length() == 0 ? "/" : path;
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

    private static boolean sameUrl(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }

    private static boolean isWebPage(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(java.util.Locale.US);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    @Override
    protected void onDestroy() {
        cancelFileChooser();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
