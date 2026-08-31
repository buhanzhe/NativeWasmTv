package xiao.bu.tv;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.webkit.WebView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Collects diagnostics for the local management page without touching playback. */
final class SystemInfoProvider {
    private static final String TAG = "SystemInfoProvider";
    private static final long CACHE_MS = 15000L;
    private final Context context;
    private long cachedAt;
    private String cachedJson;
    private String cachedWebViewIdentity;
    private String cachedWebViewArchitectures;

    SystemInfoProvider(Context context) {
        this.context = context.getApplicationContext();
    }

    synchronized JSONObject snapshot(String webViewUserAgent) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (cachedJson != null && now - cachedAt < CACHE_MS) {
            try {
                return new JSONObject(cachedJson);
            } catch (JSONException ignored) {
            }
        }
        JSONObject result = collect(webViewUserAgent);
        cachedAt = now;
        cachedJson = result.toString();
        return result;
    }

    private JSONObject collect(String webViewUserAgent) {
        JSONObject result = new JSONObject();
        try {
            PackageInfo webView = currentWebViewPackage(context);
            String webViewName = webView == null ? "系统内置 WebView"
                    : applicationLabel(context, webView.packageName);
            String webViewVersion = webView == null
                    ? legacyWebViewVersion(webViewUserAgent)
                    : safe(webView.versionName, "未知版本");
            result.put("android", "Android " + safe(Build.VERSION.RELEASE, "未知")
                    + " · API " + Build.VERSION.SDK_INT + "（"
                    + apiName(Build.VERSION.SDK_INT) + "）");
            result.put("androidRelease", safe(Build.VERSION.RELEASE, "未知"));
            result.put("apiLevel", Build.VERSION.SDK_INT);
            result.put("apiName", apiName(Build.VERSION.SDK_INT));
            result.put("device", joinDeviceName());
            result.put("manufacturer", safe(Build.MANUFACTURER, "未知"));
            result.put("model", safe(Build.MODEL, "未知"));
            result.put("webView", webViewName + " · " + webViewVersion);
            result.put("webViewPackage", webView == null ? "android" : webView.packageName);
            result.put("webViewVersion", webViewVersion);
            result.put("webViewArchitectures", webViewArchitectures(webView));
            result.put("cpuName", readCpuName());
            result.put("cpuFrequencies", readCpuFrequencies());
            result.put("cpuArchitectures", join(supportedAbis(), "、"));
            result.put("cpuCores", Math.max(1, Runtime.getRuntime().availableProcessors()));
            result.put("memory", formatMemory(readTotalMemoryKb()));
            result.put("lanIpv4", join(networkAddresses(false), "、"));
            result.put("publicIpv6", join(networkAddresses(true), "、"));
            result.put("app", BuildConfig.VERSION_NAME + " · versionCode "
                    + BuildConfig.VERSION_CODE);
        } catch (JSONException error) {
            Log.w(TAG, "Unable to build system information", error);
        }
        return result;
    }

    private static String joinDeviceName() {
        String manufacturer = safe(Build.MANUFACTURER, "");
        String model = safe(Build.MODEL, "未知设备");
        if (manufacturer.length() == 0
                || model.toLowerCase(Locale.US).startsWith(
                        manufacturer.toLowerCase(Locale.US))) {
            return model;
        }
        return manufacturer + " " + model;
    }

    private static PackageInfo currentWebViewPackage(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PackageInfo current = CurrentWebViewPackage.read();
            if (current != null) {
                return current;
            }
        }
        PackageManager manager = context.getPackageManager();
        String[] candidates = new String[] {
                "com.google.android.webview", "com.android.webview",
                "com.android.chrome", "com.google.android.webview.beta",
                "com.sec.android.app.sbrowser"
        };
        for (String packageName : candidates) {
            try {
                PackageInfo info = manager.getPackageInfo(packageName, 0);
                if (info != null && info.applicationInfo != null
                        && info.applicationInfo.enabled) {
                    return info;
                }
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return null;
    }

    @TargetApi(Build.VERSION_CODES.O)
    private static final class CurrentWebViewPackage {
        static PackageInfo read() {
            return WebView.getCurrentWebViewPackage();
        }
    }

    private static String applicationLabel(Context context, String packageName) {
        try {
            PackageManager manager = context.getPackageManager();
            CharSequence label = manager.getApplicationLabel(
                    manager.getApplicationInfo(packageName, 0));
            return label == null || label.length() == 0 ? packageName : label.toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            return packageName;
        }
    }

    private static String legacyWebViewVersion(String userAgent) {
        if (userAgent != null) {
            String[] markers = new String[] { "Chrome/", "Version/", "AppleWebKit/" };
            for (String marker : markers) {
                int start = userAgent.indexOf(marker);
                if (start >= 0) {
                    start += marker.length();
                    int end = start;
                    while (end < userAgent.length()) {
                        char value = userAgent.charAt(end);
                        if (!(value == '.' || (value >= '0' && value <= '9'))) {
                            break;
                        }
                        end++;
                    }
                    if (end > start) {
                        return marker.substring(0, marker.length() - 1) + " "
                                + userAgent.substring(start, end);
                    }
                }
            }
        }
        return "随系统 Android " + safe(Build.VERSION.RELEASE, "未知");
    }

    private static List<String> supportedAbis() {
        LinkedHashSet<String> values = new LinkedHashSet<String>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Collections.addAll(values, SupportedAbis.read());
        } else {
            values.add(safe(Build.CPU_ABI, "未知"));
            if (Build.CPU_ABI2 != null && Build.CPU_ABI2.length() > 0
                    && !"unknown".equalsIgnoreCase(Build.CPU_ABI2)) {
                values.add(Build.CPU_ABI2);
            }
        }
        values.remove("");
        return new ArrayList<String>(values);
    }

    private static int ipv4Preference(String address) {
        if (address != null && address.startsWith("192.")) {
            return 0;
        }
        if (address != null && address.startsWith("10.")) {
            return 1;
        }
        if (address != null && address.startsWith("172.")) {
            return 2;
        }
        return 3;
    }

    /** Reads the ABIs actually packaged with the active WebView provider. */
    private String webViewArchitectures(PackageInfo webView) {
        if (webView == null || webView.applicationInfo == null) {
            return "系统内置";
        }
        String identity = safe(webView.packageName, "android") + ":"
                + safe(webView.versionName, "");
        if (!identity.equals(cachedWebViewIdentity) || cachedWebViewArchitectures == null) {
            cachedWebViewIdentity = identity;
            cachedWebViewArchitectures = formatNativeArchitectures(
                    nativeArchitectures(webView.applicationInfo));
        }
        return cachedWebViewArchitectures;
    }

    private static List<String> nativeArchitectures(ApplicationInfo applicationInfo) {
        LinkedHashSet<String> values = new LinkedHashSet<String>();
        addNativeArchitectures(applicationInfo.sourceDir, values);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            String[] splitSourceDirs = SplitSourceDirs.read(applicationInfo);
            if (splitSourceDirs != null) {
                for (String sourceDir : splitSourceDirs) {
                    addNativeArchitectures(sourceDir, values);
                }
            }
        }
        return new ArrayList<String>(values);
    }

    private static void addNativeArchitectures(String apkPath, LinkedHashSet<String> values) {
        if (apkPath == null || apkPath.length() == 0) {
            return;
        }
        ZipFile apk = null;
        try {
            apk = new ZipFile(apkPath);
            Enumeration<? extends ZipEntry> entries = apk.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.startsWith("lib/") || !name.endsWith(".so")) {
                    continue;
                }
                int separator = name.indexOf('/', 4);
                if (separator > 4) {
                    values.add(name.substring(4, separator));
                }
            }
        } catch (IOException error) {
            Log.w(TAG, "Unable to inspect native libraries in " + apkPath, error);
        } finally {
            if (apk != null) {
                try {
                    apk.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String formatNativeArchitectures(List<String> architectures) {
        if (architectures == null || architectures.isEmpty()) {
            return "未包含 SO";
        }
        boolean has32Bit = false;
        boolean has64Bit = false;
        for (String architecture : architectures) {
            if (architecture != null && architecture.contains("64")) {
                has64Bit = true;
            } else {
                has32Bit = true;
            }
        }
        String type = has32Bit && has64Bit ? "多架构"
                : has64Bit ? "64 位" : "32 位";
        return join(architectures, "、") + " · " + type;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static final class SplitSourceDirs {
        static String[] read(ApplicationInfo applicationInfo) {
            return applicationInfo.splitSourceDirs;
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static final class SupportedAbis {
        static String[] read() {
            return Build.SUPPORTED_ABIS == null ? new String[0] : Build.SUPPORTED_ABIS;
        }
    }

    private static String readCpuName() {
        String manufacturer = firstValidCpuValue(
                readBuildField("SOC_MANUFACTURER"),
                readSystemProperty("ro.soc.manufacturer"),
                readTextFile("/sys/devices/soc0/manufacturer"),
                readTextFile("/sys/devices/system/soc/soc0/manufacturer"));
        String socModel = firstValidCpuValue(
                readBuildField("SOC_MODEL"),
                readSystemProperty("ro.soc.model"),
                readSystemProperty("ro.hardware.chipname"),
                readSystemProperty("ro.mediatek.platform"),
                readTextFile("/sys/devices/soc0/soc_id"),
                readTextFile("/sys/devices/system/soc/soc0/soc_id"),
                readTextFile("/sys/devices/soc0/machine"));
        String socName = joinCpuIdentity(manufacturer, socModel);
        if (socName.length() > 0) {
            return socName;
        }

        String hardware = "";
        String model = "";
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/cpuinfo"));
            String line;
            while ((line = reader.readLine()) != null) {
                int separator = line.indexOf(':');
                if (separator <= 0) {
                    continue;
                }
                String key = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                if (("Hardware".equalsIgnoreCase(key)
                        || "chip name".equalsIgnoreCase(key))
                        && isValidCpuValue(value)) {
                    hardware = value;
                } else if (("model name".equalsIgnoreCase(key)
                        || "Processor".equalsIgnoreCase(key)) && model.length() == 0
                        && isValidCpuValue(value)) {
                    model = value;
                }
            }
        } catch (IOException error) {
            Log.w(TAG, "Unable to read CPU information", error);
        } finally {
            closeQuietly(reader);
        }
        String cpuInfoName = firstValidCpuValue(hardware, model);
        if (cpuInfoName.length() > 0) {
            return cpuInfoName;
        }
        return firstValidCpuValue(
                readSystemProperty("ro.board.platform"),
                readSystemProperty("ro.hardware"),
                safe(Build.HARDWARE, ""), safe(Build.BOARD, ""));
    }

    private static String joinCpuIdentity(String manufacturer, String model) {
        if (!isValidCpuValue(manufacturer)) {
            return isValidCpuValue(model) ? model.trim() : "";
        }
        if (!isValidCpuValue(model)) {
            return manufacturer.trim();
        }
        String vendor = manufacturer.trim();
        String chipset = model.trim();
        if (chipset.toLowerCase(Locale.US).contains(vendor.toLowerCase(Locale.US))) {
            return chipset;
        }
        return vendor + " " + chipset;
    }

    private static String firstValidCpuValue(String... values) {
        if (values != null) {
            for (String value : values) {
                if (isValidCpuValue(value)) {
                    return value.trim();
                }
            }
        }
        return "";
    }

    private static boolean isValidCpuValue(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim();
        if (normalized.length() == 0 || "0".equals(normalized)
                || "unknown".equalsIgnoreCase(normalized)
                || "null".equalsIgnoreCase(normalized)
                || normalized.matches("[0-9]+")) {
            return false;
        }
        return true;
    }

    private static String readBuildField(String fieldName) {
        try {
            Field field = Build.class.getField(fieldName);
            Object value = field.get(null);
            return value == null ? "" : value.toString().trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String readSystemProperty(String key) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Method get = properties.getMethod("get", String.class, String.class);
            Object value = get.invoke(null, key, "");
            String result = value == null ? "" : value.toString().trim();
            if (result.length() > 0) {
                return result;
            }
        } catch (Exception ignored) {
        }
        Process process = null;
        BufferedReader reader = null;
        try {
            process = Runtime.getRuntime().exec(new String[] { "getprop", key });
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String value = reader.readLine();
            return value == null ? "" : value.trim();
        } catch (Exception ignored) {
            return "";
        } finally {
            closeQuietly(reader);
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String readTextFile(String path) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(path));
            String value = reader.readLine();
            return value == null ? "" : value.trim();
        } catch (Exception ignored) {
            return "";
        } finally {
            closeQuietly(reader);
        }
    }

    private static String readCpuFrequencies() {
        LinkedHashSet<Long> frequencies = new LinkedHashSet<Long>();
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        for (int index = 0; index < cores; index++) {
            long kilohertz = readLongFile("/sys/devices/system/cpu/cpu" + index
                    + "/cpufreq/cpuinfo_max_freq");
            if (kilohertz <= 0L) {
                kilohertz = readLongFile("/sys/devices/system/cpu/cpu" + index
                        + "/cpufreq/scaling_max_freq");
            }
            if (kilohertz > 0L) {
                frequencies.add(kilohertz);
            }
        }
        if (frequencies.isEmpty()) {
            return "主频未知";
        }
        ArrayList<Long> sorted = new ArrayList<Long>(frequencies);
        Collections.sort(sorted);
        StringBuilder value = new StringBuilder();
        for (Long kilohertz : sorted) {
            if (value.length() > 0) {
                value.append(" / ");
            }
            if (kilohertz >= 1000000L) {
                value.append(String.format(Locale.US, "%.2f GHz", kilohertz / 1000000d));
            } else {
                value.append(String.format(Locale.US, "%.0f MHz", kilohertz / 1000d));
            }
        }
        return value.toString();
    }

    private static long readLongFile(String path) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(path));
            String value = reader.readLine();
            return value == null ? 0L : Long.parseLong(value.trim());
        } catch (Exception ignored) {
            return 0L;
        } finally {
            closeQuietly(reader);
        }
    }

    private static long readTotalMemoryKb() {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/meminfo"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("MemTotal:")) {
                    continue;
                }
                String digits = line.replaceAll("[^0-9]", "");
                return digits.length() == 0 ? 0L : Long.parseLong(digits);
            }
        } catch (Exception error) {
            Log.w(TAG, "Unable to read memory information", error);
        } finally {
            closeQuietly(reader);
        }
        return 0L;
    }

    private static String formatMemory(long kilobytes) {
        if (kilobytes <= 0L) {
            return "未知";
        }
        return String.format(Locale.US, "%.1f GB", kilobytes / 1048576d);
    }

    private static List<String> networkAddresses(boolean publicIpv6) {
        LinkedHashSet<String> values = new LinkedHashSet<String>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return new ArrayList<String>(values);
            }
            for (NetworkInterface network : Collections.list(interfaces)) {
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (publicIpv6) {
                        if (isPublicIpv6(address)) {
                            values.add(withoutScope(address.getHostAddress()));
                        }
                    } else if (!(address instanceof Inet6Address)
                            && !address.isLoopbackAddress()
                            && address.isSiteLocalAddress()) {
                        values.add(address.getHostAddress());
                    }
                }
            }
        } catch (SocketException error) {
            Log.w(TAG, "Unable to inspect network interfaces", error);
        }
        ArrayList<String> result = new ArrayList<String>(values);
        if (!publicIpv6) {
            Collections.sort(result, new Comparator<String>() {
                @Override
                public int compare(String left, String right) {
                    return ipv4Preference(left) - ipv4Preference(right);
                }
            });
        }
        return result;
    }

    private static boolean isPublicIpv6(InetAddress address) {
        if (!(address instanceof Inet6Address) || address.isAnyLocalAddress()
                || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) != 0xfc;
    }

    private static String withoutScope(String address) {
        int separator = address == null ? -1 : address.indexOf('%');
        return separator < 0 ? safe(address, "") : address.substring(0, separator);
    }

    private static String apiName(int api) {
        switch (api) {
            case 14:
            case 15: return "Ice Cream Sandwich";
            case 16:
            case 17:
            case 18: return "Jelly Bean";
            case 19:
            case 20: return "KitKat";
            case 21:
            case 22: return "Lollipop";
            case 23: return "Marshmallow";
            case 24:
            case 25: return "Nougat";
            case 26:
            case 27: return "Oreo";
            case 28: return "Pie";
            case 29: return "Android 10";
            case 30: return "Android 11";
            case 31:
            case 32: return "Android 12";
            case 33: return "Android 13";
            case 34: return "Android 14";
            case 35: return "Android 15";
            case 36: return "Android 16";
            default: return api < 14 ? "旧版 Android" : "未来版本";
        }
    }

    private static String join(List<String> values, String separator) {
        if (values == null || values.isEmpty()) {
            return "无";
        }
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (value == null || value.length() == 0) {
                continue;
            }
            if (result.length() > 0) {
                result.append(separator);
            }
            result.append(value);
        }
        return result.length() == 0 ? "无" : result.toString();
    }

    private static String safe(String value, String fallback) {
        return value == null || value.trim().length() == 0 ? fallback : value.trim();
    }

    private static void closeQuietly(BufferedReader reader) {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException ignored) {
            }
        }
    }
}
