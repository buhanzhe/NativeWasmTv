package xiao.bu.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.net.MalformedURLException;
import java.net.URL;

/** Applies the user-selected accelerator to GitHub URLs. */
final class GithubProxy {
    static final String MODE_GH_PROXY = "gh_proxy";
    static final String MODE_LEGACY = "legacy";
    static final String MODE_DIRECT = "direct";
    static final String MODE_CUSTOM = "custom";

    private static final String PREFERENCES = "tv_player";
    private static final String MODE_KEY = "github_proxy_mode";
    private static final String CUSTOM_PREFIX_KEY = "github_proxy_custom_prefix";
    private static final String LEGACY_HTTP_PREFIX = "http://gh.3w.pm/";
    private static final String HTTPS_PREFIX = "https://gh-proxy.com/";
    private static final String COMPAT_HTTPS_PREFIX = "https://gh-proxy.org/";

    private GithubProxy() {
    }

    static String apply(Context context, String url) {
        String githubUrl = unwrap(context, url == null ? "" : url.trim());
        if (!isGithubUrl(githubUrl)) {
            return githubUrl;
        }
        String mode = getMode(context);
        if (MODE_DIRECT.equals(mode)) {
            return githubUrl;
        }
        String prefix = MODE_LEGACY.equals(mode) ? LEGACY_HTTP_PREFIX
                : MODE_CUSTOM.equals(mode) ? getCustomPrefix(context) : HTTPS_PREFIX;
        return applyPrefix(prefix, githubUrl);
    }

    static String getMode(Context context) {
        String saved = preferences(context).getString(MODE_KEY, "");
        return isSupportedMode(saved) ? saved : defaultMode();
    }

    static String getCustomPrefix(Context context) {
        return normalizePrefix(preferences(context).getString(CUSTOM_PREFIX_KEY, ""));
    }

    static String getEffectivePrefix(Context context) {
        String mode = getMode(context);
        if (MODE_DIRECT.equals(mode)) {
            return "";
        }
        if (MODE_LEGACY.equals(mode)) {
            return LEGACY_HTTP_PREFIX;
        }
        if (MODE_CUSTOM.equals(mode)) {
            return getCustomPrefix(context);
        }
        return HTTPS_PREFIX;
    }

    static void save(Context context, String mode, String customPrefix) {
        if (!isSupportedMode(mode)) {
            throw new IllegalArgumentException("不支持的 GitHub 加速方式");
        }
        String custom = normalizePrefix(customPrefix);
        if (MODE_CUSTOM.equals(mode) && custom.length() == 0) {
            throw new IllegalArgumentException("请填写自定义 GitHub 加速地址");
        }
        preferences(context).edit().putString(MODE_KEY, mode)
                .putString(CUSTOM_PREFIX_KEY, custom).apply();
    }

    static String defaultMode() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN
                ? MODE_GH_PROXY : MODE_LEGACY;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFERENCES, Context.MODE_PRIVATE);
    }

    private static boolean isSupportedMode(String mode) {
        return MODE_GH_PROXY.equals(mode) || MODE_LEGACY.equals(mode)
                || MODE_DIRECT.equals(mode) || MODE_CUSTOM.equals(mode);
    }

    private static String unwrap(Context context, String url) {
        String value = url;
        String custom = getCustomPrefix(context);
        for (int pass = 0; pass < 3; pass++) {
            String unwrapped = unwrapPrefix(value, HTTPS_PREFIX);
            if (unwrapped.equals(value)) {
                unwrapped = unwrapPrefix(value, LEGACY_HTTP_PREFIX);
            }
            if (unwrapped.equals(value)) {
                unwrapped = unwrapPrefix(value, COMPAT_HTTPS_PREFIX);
            }
            if (unwrapped.equals(value)) {
                unwrapped = unwrapCustom(value, custom);
            }
            if (unwrapped.equals(value)) {
                break;
            }
            value = unwrapped;
        }
        return value;
    }

    private static String unwrapPrefix(String value, String prefix) {
        return prefix.length() > 0 && value.startsWith(prefix)
                ? value.substring(prefix.length()) : value;
    }

    private static String unwrapCustom(String value, String template) {
        int marker = template.indexOf("{url}");
        if (marker < 0) {
            return unwrapPrefix(value, template);
        }
        String before = template.substring(0, marker);
        String after = template.substring(marker + "{url}".length());
        if (!value.startsWith(before) || !value.endsWith(after)
                || value.length() < before.length() + after.length()) {
            return value;
        }
        return value.substring(before.length(), value.length() - after.length());
    }

    private static String applyPrefix(String prefix, String githubUrl) {
        if (prefix.indexOf("{url}") >= 0) {
            return prefix.replace("{url}", githubUrl);
        }
        return prefix + githubUrl;
    }

    private static String normalizePrefix(String value) {
        String prefix = value == null ? "" : value.trim();
        if (prefix.length() == 0) {
            return "";
        }
        try {
            URL url = new URL(prefix.replace("{url}", "https://github.com/"));
            String protocol = url.getProtocol();
            if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
                return "";
            }
        } catch (MalformedURLException error) {
            return "";
        }
        return prefix.indexOf("{url}") >= 0 || prefix.endsWith("/")
                ? prefix : prefix + "/";
    }

    private static boolean isGithubUrl(String value) {
        try {
            String host = new URL(value).getHost();
            return "github.com".equalsIgnoreCase(host)
                    || "raw.githubusercontent.com".equalsIgnoreCase(host)
                    || host.toLowerCase().endsWith(".githubusercontent.com");
        } catch (MalformedURLException error) {
            return false;
        }
    }
}
