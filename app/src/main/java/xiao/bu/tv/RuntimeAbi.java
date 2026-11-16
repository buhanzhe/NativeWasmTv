package xiao.bu.tv;

/** Select plugins for the running process, including 32-bit processes on 64-bit devices. */
final class RuntimeAbi {
    private RuntimeAbi() {}
    static String current() {
        if (!"universal".equals(BuildConfig.CJS_PLUGIN_ABI)) return BuildConfig.CJS_PLUGIN_ABI;
        String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.US);
        if (arch.equals("aarch64") || arch.equals("arm64")) return "arm64-v8a";
        if (arch.startsWith("arm")) return "armeabi-v7a";
        if (arch.equals("x86") || arch.matches("i[3-6]86")) return "x86";
        throw new IllegalStateException("Unsupported process architecture: " + arch);
    }
}
