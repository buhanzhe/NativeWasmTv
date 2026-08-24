package xiao.bu.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class ManagementActivity extends Activity {
    static final String EXTRA_URL = "management_url";
    private WebView webView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        });
        setContentView(webView);
        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null || url.length() == 0) {
            finish();
            return;
        }
        try {
            webView.loadDataWithBaseURL(url, readControlHtml(),
                    "text/html", "UTF-8", url);
        } catch (IOException error) {
            // The loopback URL remains a safe fallback if the bundled resource cannot
            // be read on an unusual vendor build.
            webView.loadUrl(url);
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
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
