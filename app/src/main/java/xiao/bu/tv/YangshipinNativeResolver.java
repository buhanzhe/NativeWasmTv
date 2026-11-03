package xiao.bu.tv;

import com.bu.cc.tv.NativeYspSigner;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URLEncoder;

/** The website's auth/token/live exchange, without a browser or JavaScript bridge. */
final class YangshipinNativeResolver {
    private static final String API = "https://player-api.yangshipin.cn/v1/player/";
    private static final String TICKET_KEY = "6a70e9ac007ccc384c28a88dfd2211decb66494bca83bd6178f85732b2eacc9594e4e5ab720ec3a43e87b3c72b86192ff0dfea8fa8f47794d8792668438c";
    interface Cancellation { boolean cancelled(); }
    interface Transport {
        JSONObject request(String url, String method, JSONObject headers, String body, int timeout) throws Exception;
    }
    interface Signer {
        String random(String guid, String timestamp);
        String sign(String guid, String token, String input);
    }
    static final class Result {
        final String url;
        final long clockOffsetMs;
        Result(String url, long offset) { this.url = url; clockOffsetMs = offset; }
    }
    private final Transport http;
    private final Signer signer;
    YangshipinNativeResolver() {
        this((url, method, headers, body, timeout) -> new JSONObject(Ku9HttpClient.requestJson(
                url, method, headers.toString(), body, false, 1024 * 1024, timeout)), new Signer() {
            public String random(String guid, String timestamp) { return NativeYspSigner.tokenRnd(guid, timestamp); }
            public String sign(String guid, String token, String input) { return NativeYspSigner.signature(guid, token, input); }
        });
    }
    YangshipinNativeResolver(Transport transport, Signer signer) { http = transport; this.signer = signer; }

    Result resolve(Channel channel, String definition, long offset, long deadline, Cancellation cancel) throws Exception {
        long time = System.currentTimeMillis() + (offset == Long.MIN_VALUE ? 0 : offset);
        YangshipinApiPayload payload = YangshipinApiPayload.create(channel, time, definition);
        JSONObject auth = request(API + "auth", "POST", headers(payload, false), payload.authBody, deadline, cancel);
        JSONObject data = auth.optJSONObject("data");
        if (auth.optInt("code", -1) != 0 || data == null || data.optString("token").length() == 0
                || data.optLong("ts") <= 0) throw new IOException("央视频认证未返回 token/时间");
        long seconds = data.getLong("ts");
        long correctedOffset = seconds * 1000L - System.currentTimeMillis();
        // A badly set device clock must not keep its old identity for SDK signing.
        if (Math.abs(seconds * 1000L - time) > 60000L) {
            payload = YangshipinApiPayload.create(channel, seconds * 1000L, definition);
            auth = request(API + "auth", "POST", headers(payload, false), payload.authBody, deadline, cancel);
            data = auth.optJSONObject("data");
            if (auth.optInt("code", -1) != 0 || data == null || data.optString("token").length() == 0
                    || data.optLong("ts") <= 0) throw new IOException("央视频校时认证失败");
            seconds = data.getLong("ts");
            correctedOffset = seconds * 1000L - System.currentTimeMillis();
        }
        String playerToken = data.getString("token");
        check(deadline, cancel);
        String timestamp = String.valueOf(seconds * 1000L);
        String random = signer.random(payload.guid, timestamp);
        if (random == null || random.length() == 0) throw new IOException("央视频签名随机数为空");
        String tokenUrl = "https://h5access.yangshipin.cn/web/open/token?yspappid=519748109&guid="
                + encode(payload.guid) + "&vappid=59306155&vsecret=b42702bf7309a179d102f3d51b1add2fda0bc7ada64cb801"
                + "&raw=1&version=v1&ts=" + timestamp + "&rnd=" + encode(random);
        JSONObject token = request(tokenUrl, "GET", headers(payload, false), null, deadline, cancel);
        JSONObject tokenData = token.optJSONObject("data");
        if (token.optInt("ret", -1) != 0 || tokenData == null || tokenData.optString("token").length() == 0)
            throw new IOException("央视频 SDK token 为空");
        String body = YangshipinApiPayload.createLiveBody(channel, payload.guid, payload.liveRandom, seconds, definition);
        String sdkInput = YangshipinApiPayload.createSdkInput(body);
        String fullInput = sdkInput + "-" + payload.guid + "-1-" + payload.requestId;
        check(deadline, cancel);
        String signature = signer.sign(payload.guid, tokenData.getString("token"), fullInput);
        if (signature == null || signature.length() == 0) throw new IOException("央视频 SDK 签名为空");
        JSONObject liveHeaders = headers(payload, true);
        liveHeaders.put("yspplayertoken", playerToken);
        liveHeaders.put("yspticket", ticket(channel.yangshipinPid, seconds, payload));
        liveHeaders.put("seqid", "1");
        liveHeaders.put("request-id", payload.requestId);
        liveHeaders.put("yspsdkinput", sdkInput);
        liveHeaders.put("yspsdksign", signature + "-" + fullInput);
        JSONObject live = request(API + "get_live_info", "POST", liveHeaders, body, deadline, cancel);
        data = live.optJSONObject("data");
        String url = data == null ? "" : data.optString("playurl");
        if (live.optInt("code", -1) != 0 || !validUrl(url))
            throw new IOException("央视频接口未返回可用线路 code=" + live.optInt("code", -1));
        String extended = data.optString("extended_param");
        if (extended.length() > 0 && !url.contains(extended)) url += extended;
        check(deadline, cancel);
        return new Result(url, correctedOffset);
    }
    private JSONObject request(String url, String method, JSONObject headers, String body,
            long deadline, Cancellation cancel) throws Exception {
        for (int attempt = 0; ; attempt++) {
            check(deadline, cancel);
            int remaining = (int)Math.min(8000L, deadline - android.os.SystemClock.elapsedRealtime());
            JSONObject response = http.request(url, method, headers, body, remaining);
            check(deadline, cancel);
            int code = response.optInt("code");
            if (code == 0 && attempt == 0) { NetworkClient.invalidateDns(URI.create(url).getHost()); continue; }
            if (code != 200) throw new IOException("央视频 HTTP " + code);
            return new JSONObject(response.getString("body"));
        }
    }
    private static void check(long deadline, Cancellation cancel) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || cancel.cancelled()) throw new InterruptedIOException("央视频请求已取消");
        if (android.os.SystemClock.elapsedRealtime() >= deadline) throw new InterruptedIOException("央视频解析超时");
    }
    static boolean validUrl(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            return ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && uri.getUserInfo() == null
                    && host != null && (host.equals("ysp.cctv.cn") || host.endsWith(".ysp.cctv.cn"))
                    && uri.getPath().contains(".m3u8");
        } catch (RuntimeException error) { return false; }
    }
    private static JSONObject headers(YangshipinApiPayload payload, boolean json) throws Exception {
        JSONObject h = new JSONObject();
        h.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36");
        h.put("Origin", "https://www.yangshipin.cn");
        h.put("Referer", "https://www.yangshipin.cn/");
        h.put("Accept", "application/json, text/plain, */*");
        h.put("Content-Type", json ? "application/json;charset=UTF-8" : "application/x-www-form-urlencoded;charset=UTF-8");
        h.put("yspappid", "519748109");
        h.put("Cookie", "guid=" + payload.guid + "; versionName=99.99.99; versionCode=999999; vplatform=109; platformVersion=Chrome; deviceModel=148; newLogin=1; pc_version=1.1.16; ysp_uinfo_pc=; nseqId=1; nrequest-id=" + payload.requestId);
        return h;
    }
    private static String ticket(String pid, long seconds, YangshipinApiPayload p) {
        String plain = pid + "&" + seconds + "&" + p.guid + "&519748109&" + p.ticketRandom;
        StringBuilder value = new StringBuilder(plain.length() * 2);
        for (int i = 0; i < plain.length(); i++) {
            // The official script's parseInt('') ^ byte also acts as XOR with zero.
            int key = i * 2 + 2 <= TICKET_KEY.length() ? Integer.parseInt(TICKET_KEY.substring(i * 2, i * 2 + 2), 16) : 0;
            int b = plain.charAt(i) ^ key;
            if (b < 16) value.append('0');
            value.append(Integer.toHexString(b));
        }
        return value.toString();
    }
    private static String encode(String value) throws Exception { return URLEncoder.encode(value, "UTF-8"); }
}
