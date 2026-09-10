package xiao.bu.tv;

/** Native package selection uses the app process ABI, never the device's preferred ABI. */
final class CjsNativeProfile {
    static String select(String abi, int sdk) {
        if ("arm64-v8a".equals(abi) && sdk >= 21) return "arm64";
        if ("armeabi-v7a".equals(abi) && sdk >= 14)
            return sdk >= 19 ? "armv7-perf" : "armv7-base";
        throw new IllegalArgumentException("Unsupported CJS process ABI/API: " + abi + "/" + sdk);
    }
    static int minSdk(String profile) {
        if ("armv7-base".equals(profile)) return 14;
        if ("armv7-perf".equals(profile)) return 19;
        if ("arm64".equals(profile)) return 21;
        throw new IllegalArgumentException("Unknown CJS profile: " + profile);
    }
}
