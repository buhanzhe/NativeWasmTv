package xiao.bu.tv;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;
import android.webkit.WebView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    synchronized JSONObject snapshot() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (cachedJson != null && now - cachedAt < CACHE_MS) {
            try {
                return new JSONObject(cachedJson);
            } catch (JSONException ignored) {
            }
        }
        JSONObject result = collect();
        cachedAt = now;
        cachedJson = result.toString();
        return result;
    }

    private JSONObject collect() {
        JSONObject result = new JSONObject();
        try {
            PackageInfo webView = currentWebViewPackage(context);
            String webViewName = webView == null ? legacyWebViewName()
                    : applicationLabel(context, webView.packageName);
            String webViewVersion = webView == null ? ""
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
            result.put("webView", webViewVersion.length() == 0 ? webViewName
                    : webViewName + " · " + webViewVersion);
            result.put("webViewPackage", webView == null ? "android" : webView.packageName);
            result.put("webViewVersion", webViewVersion);
            result.put("webViewArchitectures", webViewArchitectures(webView));
            CpuCoreDetails coreDetails = readCpuCoreDetails();
            result.put("cpuName", readCpuName());
            result.put("cpuCoreNames", coreDetails.summary);
            result.put("cpuFrequencies", readCpuFrequencies());
            result.put("cpuArchitectures", join(supportedAbis(), "、"));
            result.put("cpuCores", Math.max(coreDetails.count,
                    Math.max(1, Runtime.getRuntime().availableProcessors())));
            result.put("memory", formatMemory(readTotalMemoryKb()));
            result.put("storage", formatStorage(readStorage()));
            result.put("lanIpv4", join(networkAddresses(false), "、"));
            result.put("publicIpv6", join(networkAddresses(true), "、"));
            result.put("networkTransport", activeNetworkTransport(context));
            result.put("app", BuildConfig.VERSION_NAME + " · versionCode "
                    + BuildConfig.VERSION_CODE);
        } catch (JSONException error) {
            Log.w(TAG, "Unable to build system information", error);
        }
        return result;
    }

    static String activeNetworkTransport(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return "unknown";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                NetworkCapabilities capabilities = manager.getNetworkCapabilities(
                        manager.getActiveNetwork());
                if (capabilities != null) {
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        return "ethernet";
                    }
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        return "wifi";
                    }
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        return "cellular";
                    }
                }
            }
            NetworkInfo active = manager.getActiveNetworkInfo();
            if (active == null || !active.isConnected()) return "none";
            if (active.getType() == ConnectivityManager.TYPE_ETHERNET) return "ethernet";
            if (active.getType() == ConnectivityManager.TYPE_WIFI) return "wifi";
            if (active.getType() == ConnectivityManager.TYPE_MOBILE) return "cellular";
        } catch (RuntimeException error) {
            Log.d(TAG, "Unable to inspect active network", error);
        }
        return "other";
    }

    /** Best-effort SoftAP detection. It is only used to avoid an unnecessary P2P
     * handover; an unknown result safely leaves the existing LAN fallback intact. */
    static boolean isWifiHotspotActive(Context context) {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                Method method = wifi.getClass().getMethod("isWifiApEnabled");
                Object value = method.invoke(wifi);
                if (value instanceof Boolean && (Boolean) value) return true;
            }
        } catch (Exception ignored) {
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return false;
            for (NetworkInterface network : Collections.list(interfaces)) {
                if (!network.isUp() || network.isLoopback()) continue;
                String name = safe(network.getName(), "").toLowerCase(Locale.US);
                if ("ap0".equals(name) || "ap1".equals(name)
                        || name.startsWith("softap") || name.startsWith("swlan")) {
                    return true;
                }
            }
        } catch (SocketException ignored) {
        }
        return false;
    }

    /** True only when the receiver IP belongs to the current router-facing Wi-Fi
     * link. A concurrently active SoftAP is deliberately excluded. */
    static boolean isPeerOnActiveWifi(Context context, String peerUrl) {
        if (!"wifi".equals(activeNetworkTransport(context))) return false;
        try {
            InetAddress peer = InetAddress.getByName(new URL(peerUrl).getHost());
            ConnectivityManager manager = (ConnectivityManager) context
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return true;
            }
            LinkProperties properties = manager.getLinkProperties(manager.getActiveNetwork());
            if (properties == null) return false;
            for (LinkAddress link : properties.getLinkAddresses()) {
                InetAddress local = link.getAddress();
                if (local != null && local.getAddress().length == peer.getAddress().length
                        && sameSubnet(local.getAddress(), peer.getAddress(),
                                link.getPrefixLength())) {
                    return true;
                }
            }
        } catch (Exception error) {
            Log.d(TAG, "Unable to match receiver to active Wi-Fi", error);
        }
        return false;
    }

    private static boolean sameSubnet(byte[] left, byte[] right, int prefixLength) {
        if (left.length != right.length || prefixLength < 0
                || prefixLength > left.length * 8) return false;
        int bytes = prefixLength / 8;
        int bits = prefixLength % 8;
        for (int index = 0; index < bytes; index++) {
            if (left[index] != right[index]) return false;
        }
        if (bits == 0) return true;
        int mask = 0xff << (8 - bits);
        return (left[bytes] & mask) == (right[bytes] & mask);
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
        // Android 4.4's Chromium WebView is bundled with the framework. An
        // installed browser APK is not its provider and a custom UA is not
        // reliable system information.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return null;
        }
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

    private static String legacyWebViewName() {
        return "系统内置 WebView（Android "
                + safe(Build.VERSION.RELEASE, "未知") + "）";
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
            String architectures = Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP
                    ? safe(Build.CPU_ABI, "") : join(supportedAbis(), "、");
            return architectures.length() == 0 ? "系统内置"
                    : architectures + "（系统内置）";
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

    private static final class CpuCoreDetails {
        final int count;
        final String summary;

        CpuCoreDetails(int count, String summary) {
            this.count = count;
            this.summary = summary;
        }
    }

    /** Best-effort physical core identity. ARM normally exposes an implementer/part
     * pair instead of a model name, so translate the common values and group equal
     * cores. Unknown values are still useful diagnostics and remain visible as hex. */
    private static CpuCoreDetails readCpuCoreDetails() {
        LinkedHashMap<String, Integer> names = new LinkedHashMap<String, Integer>();
        BufferedReader reader = null;
        int count = 0;
        boolean core = false;
        String implementer = "";
        String part = "";
        String model = "";
        try {
            reader = new BufferedReader(new FileReader("/proc/cpuinfo"));
            String line;
            while ((line = reader.readLine()) != null) {
                int separator = line.indexOf(':');
                if (separator <= 0) continue;
                String key = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                if ("processor".equals(key) && value.matches("[0-9]+")) {
                    if (core) addCpuCoreName(names, implementer, part, model);
                    core = true;
                    count++;
                    implementer = "";
                    part = "";
                    model = "";
                } else if (core && "CPU implementer".equalsIgnoreCase(key)) {
                    implementer = value;
                } else if (core && "CPU part".equalsIgnoreCase(key)) {
                    part = value;
                } else if (core && ("model name".equalsIgnoreCase(key)
                        || "cpu model".equalsIgnoreCase(key))) {
                    model = value;
                }
            }
            if (core) addCpuCoreName(names, implementer, part, model);
        } catch (IOException error) {
            Log.d(TAG, "Unable to read CPU core names", error);
        } finally {
            closeQuietly(reader);
        }
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<String, Integer> entry : names.entrySet()) {
            if (summary.length() > 0) summary.append(" + ");
            if (names.size() > 1 || entry.getValue() > 1) {
                summary.append(entry.getValue()).append('×').append(' ');
            }
            summary.append(entry.getKey());
        }
        return new CpuCoreDetails(count, summary.toString());
    }

    private static void addCpuCoreName(Map<String, Integer> names,
            String implementer, String part, String model) {
        String name = isValidCpuValue(model) ? model.trim().replaceAll("\\s+", " ")
                : cpuPartName(implementer, part);
        if (name.length() == 0) return;
        Integer previous = names.get(name);
        names.put(name, previous == null ? 1 : previous + 1);
    }

    private static String cpuPartName(String implementer, String part) {
        String vendor = normalizeHex(implementer);
        String core = normalizeHex(part);
        if ("0x41".equals(vendor)) {
            if ("0xd01".equals(core)) return "ARM Cortex-A32";
            if ("0xd02".equals(core)) return "ARM Cortex-A34";
            if ("0xd03".equals(core)) return "ARM Cortex-A53";
            if ("0xd04".equals(core)) return "ARM Cortex-A35";
            if ("0xd05".equals(core)) return "ARM Cortex-A55";
            if ("0xd06".equals(core)) return "ARM Cortex-A65";
            if ("0xd07".equals(core)) return "ARM Cortex-A57";
            if ("0xd08".equals(core)) return "ARM Cortex-A72";
            if ("0xd09".equals(core)) return "ARM Cortex-A73";
            if ("0xd0a".equals(core)) return "ARM Cortex-A75";
            if ("0xd0b".equals(core)) return "ARM Cortex-A76";
            if ("0xd0c".equals(core)) return "ARM Neoverse N1";
            if ("0xd0d".equals(core)) return "ARM Cortex-A77";
            if ("0xd40".equals(core)) return "ARM Neoverse V1";
            if ("0xd41".equals(core)) return "ARM Cortex-A78";
            if ("0xd42".equals(core)) return "ARM Cortex-A78AE";
            if ("0xd44".equals(core)) return "ARM Cortex-X1";
            if ("0xd46".equals(core)) return "ARM Cortex-A510";
            if ("0xd47".equals(core)) return "ARM Cortex-A710";
            if ("0xd48".equals(core)) return "ARM Cortex-X2";
            if ("0xd49".equals(core)) return "ARM Neoverse N2";
            if ("0xd4b".equals(core)) return "ARM Cortex-A78C";
            if ("0xd4d".equals(core)) return "ARM Cortex-A715";
            if ("0xd4e".equals(core)) return "ARM Cortex-X3";
            if ("0xd80".equals(core)) return "ARM Cortex-A520";
            if ("0xd81".equals(core)) return "ARM Cortex-A720";
            if ("0xd82".equals(core)) return "ARM Cortex-X4";
            return core.length() == 0 ? "ARM 核心" : "ARM " + core;
        }
        if ("0x51".equals(vendor)) {
            if ("0x800".equals(core)) return "Qualcomm Kryo 280 Gold";
            if ("0x801".equals(core)) return "Qualcomm Kryo 280 Silver";
            return core.length() == 0 ? "Qualcomm 核心" : "Qualcomm " + core;
        }
        if ("0x53".equals(vendor)) {
            if ("0x001".equals(core) || "0x1".equals(core)) return "Samsung Mongoose M1";
            if ("0x002".equals(core) || "0x2".equals(core)) return "Samsung Mongoose M2";
            if ("0x003".equals(core) || "0x3".equals(core)) return "Samsung Mongoose M3";
            if ("0x004".equals(core) || "0x4".equals(core)) return "Samsung Mongoose M4";
            if ("0x005".equals(core) || "0x5".equals(core)) return "Samsung Mongoose M5";
            return core.length() == 0 ? "Samsung 核心" : "Samsung " + core;
        }
        if (vendor.length() == 0) return "";
        return core.length() == 0 ? vendor : vendor + " " + core;
    }

    private static String normalizeHex(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.US);
        if (!normalized.startsWith("0x")) return normalized;
        try {
            return "0x" + Long.toHexString(Long.parseLong(normalized.substring(2), 16));
        } catch (NumberFormatException ignored) {
            return normalized;
        }
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
            // Empty is a valid missing property, particularly ro.soc.* on ICS.
            // Forking getprop adds no information and can leave a pre-exec child
            // blocked on old vendor runtimes, holding this snapshot lock forever.
            return value == null ? "" : value.toString().trim();
        } catch (Exception ignored) {
            // Diagnostics are best-effort. Build fields, sysfs and /proc remain
            // available when the hidden property API is inaccessible.
            return "";
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

    private static final class StorageInfo {
        final long totalBytes;
        final long availableBytes;

        StorageInfo(long totalBytes, long availableBytes) {
            this.totalBytes = totalBytes;
            this.availableBytes = availableBytes;
        }
    }

    private static StorageInfo readStorage() {
        try {
            StatFs stats = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                return StorageStats18.read(stats);
            }
            long blockSize = stats.getBlockSize();
            return new StorageInfo(blockSize * stats.getBlockCount(),
                    blockSize * stats.getAvailableBlocks());
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to read storage information", error);
            return new StorageInfo(0L, 0L);
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    private static final class StorageStats18 {
        static StorageInfo read(StatFs stats) {
            return new StorageInfo(stats.getTotalBytes(), stats.getAvailableBytes());
        }
    }

    private static String formatStorage(StorageInfo storage) {
        if (storage == null || storage.totalBytes <= 0L) return "未知";
        String total = formatBytes(storage.totalBytes);
        if (storage.availableBytes <= 0L) return total;
        return total + "（可用 " + formatBytes(storage.availableBytes) + "）";
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1073741824L) {
            return String.format(Locale.US, "%.1f GB", bytes / 1073741824d);
        }
        if (bytes >= 1048576L) {
            return String.format(Locale.US, "%.0f MB", bytes / 1048576d);
        }
        return String.format(Locale.US, "%.0f KB", bytes / 1024d);
    }

    private String connectedWifiIpv4() {
        try {
            ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo network = connectivity == null ? null : connectivity.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            if (network != null && !network.isConnected()) return "";
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            android.net.wifi.WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
            int ip = info == null ? 0 : info.getIpAddress();
            if (ip != 0) return (ip & 255) + "." + (ip >>> 8 & 255) + "."
                    + (ip >>> 16 & 255) + "." + (ip >>> 24 & 255);
        } catch (RuntimeException unavailable) {
            // Some systems restrict Wi-Fi information; interface inspection still works.
        }
        return "";
    }

    private static boolean isWifiInterface(String name) {
        String value = name == null ? "" : name.toLowerCase(Locale.US);
        return value.startsWith("wlan") || value.startsWith("wifi")
                || value.startsWith("tiwlan") || value.startsWith("ath") || value.matches("ra[0-9]+");
    }

    private List<String> networkAddresses(boolean publicIpv6) {
        LinkedHashSet<String> values = new LinkedHashSet<String>();
        final LinkedHashSet<String> wifiAddresses = new LinkedHashSet<String>();
        final String wifiIp = publicIpv6 ? "" : connectedWifiIpv4();
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
                        if (isWifiInterface(network.getName())) wifiAddresses.add(address.getHostAddress());
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
                    int leftNetwork = left.equals(wifiIp) ? 0 : wifiAddresses.contains(left) ? 1 : 2;
                    int rightNetwork = right.equals(wifiIp) ? 0 : wifiAddresses.contains(right) ? 1 : 2;
                    if (leftNetwork != rightNetwork) return leftNetwork - rightNetwork;
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
