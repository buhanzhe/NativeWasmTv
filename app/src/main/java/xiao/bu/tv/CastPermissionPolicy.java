package xiao.bu.tv;

/** Platform and target-SDK permission contracts; no new framework references on old TVs. */
final class CastPermissionPolicy {
    static String directPermission(int sdk, int target) {
        if (sdk < 23) return "";
        return sdk >= 33 && target >= 33
                ? "android.permission.NEARBY_WIFI_DEVICES"
                : "android.permission.ACCESS_FINE_LOCATION";
    }

    static boolean requiresLocalNetworkPermission(int sdk, int target) {
        return sdk >= 37 && target >= 37;
    }
}
