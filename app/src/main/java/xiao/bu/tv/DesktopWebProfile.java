package xiao.bu.tv;

import android.os.Build;
import xiao.bu.tv.AppLog;
import android.webkit.WebView;

import androidx.webkit.ScriptHandler;
import androidx.webkit.UserAgentMetadata;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Desktop identity for web channels only; not for management or Ku9 script WebViews. */
final class DesktopWebProfile {
    private static final String TAG = "DesktopWebProfile";
    private static final Pattern CHROME_VERSION = Pattern.compile("Chrome/([0-9.]+)");
    private static String scriptSource;
    private final WebView view;
    private ScriptHandler handler;
    private String script = "";
    private boolean documentStart;
    private UserAgentMetadata originalMetadata;
    private String metadataMode;
    private boolean nativeMetadata;
    private String configurationKey;

    DesktopWebProfile(WebView view) {
        this.view = view;
    }

    static boolean isDesktop(String mode) {
        return "windows".equals(mode) || "macos".equals(mode);
    }

    static boolean isSpoofed(String mode) {
        return !"native".equals(mode);
    }

    static String version(String nativeUa) {
        Matcher match = CHROME_VERSION.matcher(nativeUa == null ? "" : nativeUa);
        return match.find() ? match.group(1) : "";
    }

    static String selectedVersion(String nativeUa, String versionMode) {
        if ("118".equals(versionMode)) return "118.0.5993.117";
        if ("128".equals(versionMode)) return "128.0.6613.137";
        if ("138".equals(versionMode)) return "138.0.7204.183";
        if ("155".equals(versionMode)) return "155.0.8059.40";
        return version(nativeUa);
    }

    static String userAgent(String mode, String nativeUa) {
        return userAgent(mode, nativeUa, "native");
    }

    static String userAgent(String mode, String nativeUa, String versionMode) {
        String version = selectedVersion(nativeUa, versionMode);
        String prefix = "Mozilla/5.0 (" + ("macos".equals(mode)
                ? "Macintosh; Intel Mac OS X 10_15_7" : "Windows NT 10.0; Win64; x64") + ")";
        if (version.isEmpty()) {
            // Pre-Chromium Android WebKit must not claim to be Chrome 120.
            int engine = nativeUa == null ? -1 : nativeUa.indexOf(" AppleWebKit/");
            return prefix + (engine < 0 ? "" : nativeUa.substring(engine).replace("Mobile ", ""));
        }
        return prefix + " AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                + version + " Safari/537.36";
    }

    static String ipadUserAgent(String nativeUa, String versionMode) {
        String full = selectedVersion(nativeUa, versionMode);
        if (full.length() == 0) {
            return "Mozilla/5.0 (iPad; CPU OS 17_1 like Mac OS X) "
                    + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 "
                    + "Mobile/15E148 Safari/604.1";
        }
        return "Mozilla/5.0 (iPad; CPU OS 17_1 like Mac OS X) "
                + "AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/" + full
                + " Mobile/15E148 Safari/604.1";
    }

    void update(String mode, String nativeUa, String versionMode,
            int width, int height, float scale) {
        if (mode == null) mode = "native";
        String key = mode + ':' + versionMode + ':' + nativeUa + ':' + width + ':' + height + ':' + scale;
        if (key.equals(configurationKey)) return;
        configurationKey = key;
        String metadataKey = mode + ":" + versionMode;
        if (!metadataKey.equals(metadataMode)) {
            nativeMetadata = false;
            if (Build.VERSION.SDK_INT >= 19) {
                try {
                    nativeMetadata = applyMetadata(mode, nativeUa, versionMode);
                } catch (RuntimeException | LinkageError error) {
                    AppLog.w(TAG, "Native UA metadata unavailable", error);
                }
            }
            metadataMode = metadataKey;
        }
        String next = "";
        if (isSpoofed(mode)) {
            try {
                JSONObject config = new JSONObject();
                config.put("mac", "macos".equals(mode));
                config.put("ipad", "ipad".equals(mode));
                config.put("nativeMetadata", nativeMetadata);
                config.put("width", width);
                config.put("height", height);
                config.put("scale", scale);
                if ("ipad".equals(mode)) {
                    config.put("gpuVendor", "Apple Inc.");
                    config.put("gpuRenderer", "Apple GPU");
                } else if ("macos".equals(mode)) {
                    config.put("gpuVendor", "Google Inc. (Apple)");
                    config.put("gpuRenderer",
                            "ANGLE (Apple, ANGLE Metal Renderer: Apple M1, Unspecified Version)");
                } else {
                    config.put("gpuVendor", "Google Inc. (Intel)");
                    config.put("gpuRenderer", "ANGLE (Intel, Intel(R) UHD Graphics 630 "
                            + "Direct3D11 vs_5_0 ps_5_0, D3D11)");
                }
                next = "(" + source() + ")(" + config.toString() + ");";
            } catch (Exception error) {
                AppLog.w(TAG, "Cannot load desktop identity", error);
            }
        }
        if (next.equals(script)) return;
        script = next;
        documentStart = false;
        if (Build.VERSION.SDK_INT >= 19) {
            try {
                if (handler != null) {
                    handler.remove();
                    handler = null;
                }
                if (!script.isEmpty()
                        && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    handler = WebViewCompat.addDocumentStartJavaScript(view, script,
                            Collections.singleton("*"));
                    documentStart = true;
                }
                if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                    WebSettingsCompat.setRequestedWithHeaderOriginAllowList(view.getSettings(),
                            Collections.<String>emptySet());
                }
            } catch (RuntimeException | LinkageError error) {
                AppLog.w(TAG, "Desktop identity feature unavailable", error);
            }
        }
        if (!script.isEmpty() && !documentStart) {
            AppLog.w(TAG, "Legacy WebView: document-start privacy unavailable; UA-only before load");
        }
    }

    void dispose() {
        if (handler != null) {
            try { handler.remove(); } catch (RuntimeException | LinkageError ignored) { }
            handler = null;
        }
        documentStart = false;
    }

    // Only modern engines expose this feature. Set HTTP Client Hints as well as JS:
    // overriding navigator alone still sends Android/model/ARM hints to servers.
    private boolean applyMetadata(String mode, String nativeUa, String versionMode) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return false;
        if (originalMetadata == null) {
            originalMetadata = WebSettingsCompat.getUserAgentMetadata(view.getSettings());
        }
        if (!isSpoofed(mode)) {
            WebSettingsCompat.setUserAgentMetadata(view.getSettings(), originalMetadata);
            return true;
        }
        String full = selectedVersion(nativeUa, versionMode);
        if (full.isEmpty()) return false;
        String major = full.split("\\.")[0];
        boolean ipad = "ipad".equals(mode);
        UserAgentMetadata metadata = new UserAgentMetadata.Builder()
                .setBrandVersionList(ipad ? Collections.singletonList(
                        new UserAgentMetadata.BrandVersion.Builder().setBrand("Google Chrome")
                                .setMajorVersion(major).setFullVersion(full).build())
                        : Arrays.asList(
                        new UserAgentMetadata.BrandVersion.Builder().setBrand("Chromium")
                                .setMajorVersion(major).setFullVersion(full).build(),
                        new UserAgentMetadata.BrandVersion.Builder().setBrand("Google Chrome")
                                .setMajorVersion(major).setFullVersion(full).build()))
                .setFullVersion(full).setPlatform(ipad ? "iOS"
                        : "macos".equals(mode) ? "macOS" : "Windows")
                .setPlatformVersion(ipad ? "17.1" : "macos".equals(mode) ? "10.15.7" : "10.0.0")
                .setArchitecture(ipad ? "arm" : "x86").setBitness(64)
                .setModel(ipad ? "iPad" : "")
                .setMobile(ipad).setWow64(false).build();
        WebSettingsCompat.setUserAgentMetadata(view.getSettings(), metadata);
        return true;
    }

    void applyToCurrentDocument() {
        if (script.isEmpty()) return;
        if (Build.VERSION.SDK_INT >= 19) view.evaluateJavascript(script, null);
        else view.loadUrl("javascript:" + script);
    }

    boolean hasDocumentStartProtection() { return documentStart; }

    private String source() throws Exception {
        if (scriptSource == null) {
            InputStream input = view.getResources().openRawResource(R.raw.desktop_web_profile);
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                scriptSource = bytes.toString("UTF-8");
            } finally {
                input.close();
            }
        }
        return scriptSource;
    }
}
