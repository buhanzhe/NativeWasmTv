package xiao.bu.tv;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class YangshipinApiPayload {
    private static final String APP_ID = "519748109";
    private static final String APP_VERSION = "V1.0.0";
    private static final String PLATFORM = "5910204";
    private static final String AUTH_SALT = "n@7QKk%YeSjfw%22";
    private static final String LIVE_SALT = "0f$IVHi9Qno?G";
    private static final byte[] CKEY_KEY = hexBytes("48e5918a74ae21c972b90cce8af6c8be");
    private static final byte[] CKEY_IV = hexBytes("9a7e7d23610266b1d9fbf98581384d92");
    private static final char[] RANDOM_CHARS =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    final String guid;
    final String ticketRandom;
    final String requestId;
    final String liveRandom;
    final String authBody;
    final String liveBody;

    private YangshipinApiPayload(String guid, String ticketRandom, String requestId,
            String liveRandom, String authBody, String liveBody) {
        this.guid = guid;
        this.ticketRandom = ticketRandom;
        this.requestId = requestId;
        this.liveRandom = liveRandom;
        this.authBody = authBody;
        this.liveBody = liveBody;
    }

    static YangshipinApiPayload create(Channel channel) throws Exception {
        return create(channel, System.currentTimeMillis());
    }

    static YangshipinApiPayload create(Channel channel, long serverTimeMs) throws Exception {
        return create(channel, serverTimeMs, "fhd");
    }

    static YangshipinApiPayload create(Channel channel, long serverTimeMs,
            String definition) throws Exception {
        String guid = createGuid(serverTimeMs);
        String authRandom = randomText(10);
        String liveRandom = randomText(10);
        String ticketRandom = randomText(10);

        TreeMap<String, String> auth = new TreeMap<String, String>();
        auth.put("appid", "ysp_pc");
        auth.put("guid", guid);
        auth.put("pid", channel.yangshipinPid);
        auth.put("rand_str", authRandom);
        String authSignature = md5(joinForSignature(auth) + AUTH_SALT);
        String authBody = "pid=" + encode(channel.yangshipinPid)
                + "&guid=" + encode(guid)
                + "&appid=ysp_pc"
                + "&rand_str=" + encode(authRandom)
                + "&signature=" + authSignature;

        String liveBody = createLiveBody(channel, guid, liveRandom,
                System.currentTimeMillis() / 1000L, definition);
        String requestId = "999999" + randomText(10) + serverTimeMs;
        return new YangshipinApiPayload(guid, ticketRandom, requestId, liveRandom,
                authBody, liveBody);
    }

    static String createLiveBody(Channel channel, String guid, String liveRandom,
            long serverSeconds) throws Exception {
        return createLiveBody(channel, guid, liveRandom, serverSeconds, "fhd");
    }

    static String createLiveBody(Channel channel, String guid, String liveRandom,
            long serverSeconds, String definition) throws Exception {
        TreeMap<String, String> live = new TreeMap<String, String>();
        live.put("cnlid", channel.yangshipinStreamId);
        live.put("livepid", channel.yangshipinPid);
        live.put("stream", "2");
        live.put("guid", guid);
        live.put("cKey", createCKey(channel.yangshipinStreamId, guid, serverSeconds));
        live.put("adjust", "1");
        live.put("sphttps", "1");
        live.put("platform", PLATFORM);
        live.put("cmd", "2");
        live.put("encryptVer", "8.1");
        live.put("dtype", "1");
        live.put("devid", "devid");
        live.put("otype", "ojson");
        live.put("appVer", APP_VERSION);
        live.put("app_version", APP_VERSION);
        live.put("channel", "ysp_tx");
        live.put("defn", sanitizeDefinition(definition));
        live.put("rand_str", liveRandom);
        String liveSignature = md5(joinForSignature(live) + LIVE_SALT);

        JSONObject json = new JSONObject();
        putAll(json, live);
        json.put("adjust", 1);
        json.put("signature", liveSignature);
        return json.toString();
    }

    private static String sanitizeDefinition(String definition) {
        if ("shd".equals(definition) || "sd".equals(definition)) {
            return definition;
        }
        return "fhd";
    }

    static String createSdkInput(String liveBody) throws Exception {
        JSONObject json = new JSONObject(liveBody);
        String[] order = new String[] {
                "adjust", "app_version", "appVer", "channel", "cKey", "cmd",
                "cnlid", "defn", "devid", "dtype", "encryptVer", "guid",
                "livepid", "otype", "platform", "sphttps", "stream"
        };
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < order.length; index++) {
            if (index > 0) {
                value.append('&');
            }
            value.append(order[index]).append('=').append(json.optString(order[index], ""));
        }
        return md5(value.toString());
    }

    private static void putAll(JSONObject target, TreeMap<String, String> values)
            throws JSONException {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            target.put(entry.getKey(), entry.getValue());
        }
    }

    private static String createCKey(String channelId, String guid, long seconds)
            throws Exception {
        String base = "|" + channelId + "|" + seconds
                + "|mg3c3b04ba|" + APP_VERSION + "|" + guid
                + "|" + PLATFORM
                + "|https://www.yangshipin.c"
                + "|mozilla/5.0 (windows nt ||Mozilla|Netscape|Win32|";
        int hash = 0;
        for (int index = 0; index < base.length(); index++) {
            hash = hash * 31 + base.charAt(index);
        }
        byte[] plain = ("|" + hash + base).getBytes("UTF-8");
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(CKEY_KEY, "AES"),
                new IvParameterSpec(CKEY_IV));
        return "--01" + toHex(cipher.doFinal(plain), true);
    }

    private static String createGuid(long timeMs) {
        String prefix = Long.toString(timeMs, 36);
        StringBuilder value = new StringBuilder(20);
        if (prefix.length() > 8) {
            prefix = prefix.substring(prefix.length() - 8);
        }
        value.append(prefix).append('_');
        while (value.length() < 20) {
            value.append(RANDOM_CHARS[RANDOM.nextInt(36)]);
        }
        return value.substring(0, 20);
    }

    private static String randomText(int length) {
        StringBuilder value = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            value.append(RANDOM_CHARS[RANDOM.nextInt(RANDOM_CHARS.length)]);
        }
        return value.toString();
    }

    private static String joinForSignature(TreeMap<String, String> values) {
        StringBuilder joined = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (joined.length() > 0) {
                joined.append('&');
            }
            joined.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return joined.toString();
    }

    private static String md5(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        return toHex(digest.digest(text.getBytes("UTF-8")), false);
    }

    private static String encode(String value) throws UnsupportedEncodingException {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static byte[] hexBytes(String text) {
        byte[] output = new byte[text.length() / 2];
        for (int index = 0; index < output.length; index++) {
            output[index] = (byte) Integer.parseInt(text.substring(index * 2, index * 2 + 2), 16);
        }
        return output;
    }

    private static String toHex(byte[] bytes, boolean upperCase) {
        final char[] digits = (upperCase ? "0123456789ABCDEF" : "0123456789abcdef").toCharArray();
        char[] output = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            output[index * 2] = digits[value >>> 4];
            output[index * 2 + 1] = digits[value & 0x0f];
        }
        return new String(output);
    }
}
