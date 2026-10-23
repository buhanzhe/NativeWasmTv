package xiao.bu.tv;

/** Per-player colour output policy; no dependency on newer Android APIs. */
final class HdrMode {
    static final String MAPPING = "mapping";
    static final String HARDWARE = "hardware";
    static final String BT709 = "bt709";
    static final String HARDWARE_SDR = "hardware_sdr";
    static final String PASSTHROUGH = "passthrough";
    static final String DEFAULT = HARDWARE;

    static String sanitize(String value) {
        // The former passthrough selection uses the same output policy as auto.
        if(HARDWARE_SDR.equals(value))return MAPPING;
        return MAPPING.equals(value) || BT709.equals(value)
                ? value : DEFAULT;
    }

    static int nativeValue(String value) {
        String mode=sanitize(value);
        return MAPPING.equals(mode)?0:BT709.equals(mode)?2:1;
    }
}
