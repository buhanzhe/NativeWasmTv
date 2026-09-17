package xiao.bu.tv;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** First episode only: network samples, never a claim of successful decoding. */
final class VodProbe implements Runnable {
    final String id = UUID.randomUUID().toString();
    private final TvBoxService.Transport transport;
    private final String address;
    private volatile HttpURLConnection connection;
    private volatile boolean cancelled, running = true;
    private String status = "checking", stage = "manifest", error = "";
    private long checkedAt, bytes;
    private final long started = System.currentTimeMillis();
    private int requests;
    private URL effectiveUrl;

    VodProbe(String address, TvBoxService.Transport transport) { this.address = address; this.transport = transport; }
    void start() { new Thread(this, "vod-media-probe").start(); }
    boolean running() { return running; }
    void cancel() { cancelled = true; HttpURLConnection c = connection; if (c != null) c.disconnect(); }
    synchronized JSONObject state() throws Exception {
        return new JSONObject().put("ok", true).put("task", id).put("running", running).put("status", status)
                .put("stage", stage).put("code", error).put("checkedAt", checkedAt).put("bytes", bytes)
                .put("ms", (running ? System.currentTimeMillis() : checkedAt) - started)
                .put("scope", "仅首集：清单、首段相关密钥、最多64KB媒体样本；未验证电视解码或其他剧集");
    }
    private synchronized void stage(String value) { stage = value; }
    private byte[] read(URL url, int limit, boolean sample) throws Exception {
        if (cancelled || ++requests > 8 || System.currentTimeMillis() - started > 25000) throw new IOException("stopped");
        TvBoxService.httpUrl(url.toString());
        HttpURLConnection c = transport.open(url); connection = c;
        c.setConnectTimeout(5000); c.setReadTimeout(5000); c.setRequestProperty("User-Agent", "nTv-VOD/1.0");
        if (sample) c.setRequestProperty("Range", "bytes=0-" + (limit - 1));
        VodDeadline budget = new VodDeadline(c, Math.max(1, Math.min(8000, 25000 - (System.currentTimeMillis() - started))));
        try {
            if (cancelled) throw new IOException("stopped");
            int response = c.getResponseCode();
            budget.check();
            effectiveUrl = c.getURL();
            if (response != 200 && !(sample && response == 206)) throw new IOException("HTTP");
            InputStream in = c.getInputStream();
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[4096];
                while (true) {
                    if (cancelled || System.currentTimeMillis() - started > 25000) throw new IOException("stopped");
                    int count = in.read(buffer, 0, Math.min(buffer.length, limit + 1 - out.size()));
                    budget.check();
                    if (count < 0) break;
                    out.write(buffer, 0, count);
                    if (out.size() > limit) { if (!sample) throw new IOException("oversize"); break; }
                    if (sample && out.size() == limit) break;
                }
                byte[] data = out.toByteArray();
                if (data.length == 0) throw new IOException("empty");
                synchronized (this) { bytes += data.length; }
                return data;
            } finally { in.close(); }
        } catch (Exception e) { budget.check(); throw e;
        } finally { budget.close(); c.disconnect(); connection = null; }
    }
    public void run() {
        try {
            URL url = TvBoxService.httpUrl(address);
            if (url.getPath().toLowerCase(java.util.Locale.US).endsWith(".mp4")) {
                stage("sample"); read(url, 65536, true);
            } else {
                boolean found = false;
                for (int depth = 0; depth < 4; depth++) {
                    stage("manifest");
                    String manifest = new String(read(url, 512 * 1024, false), "UTF-8").replaceFirst("^\\uFEFF", "").trim();
                    url = effectiveUrl;
                    if (!manifest.startsWith("#EXTM3U")) throw new IOException("not HLS");
                    String key = null, init = null, segment = null; boolean master = false;
                    for (String raw : manifest.split("\\r?\\n")) {
                        String line = raw.trim();
                        if (line.startsWith("#EXT-X-STREAM-INF:")) master = true;
                        if (line.startsWith("#EXT-X-KEY:")) {
                            if (line.contains("METHOD=NONE")) key = null;
                            else if (line.contains("METHOD=AES-128") && (!line.contains("KEYFORMAT=") || line.contains("KEYFORMAT=\"identity\""))) key = uri(line);
                            else throw new IOException("unsupported encryption");
                            if (!line.contains("METHOD=NONE") && key == null) throw new IOException("missing key");
                        }
                        if (line.startsWith("#EXT-X-MAP:")) init = uri(line);
                        if (!line.isEmpty() && !line.startsWith("#")) { segment = line; break; }
                    }
                    if (segment == null) throw new IOException("no segment");
                    if (master) { url = new URL(url, segment); continue; }
                    if (key != null) { stage("key"); if (read(new URL(url, key), 16, false).length != 16) throw new IOException("bad key"); }
                    if (init != null) { stage("init"); read(new URL(url, init), 65536, true); }
                    stage("sample"); read(new URL(url, segment), 65536, true); found = true; break;
                }
                if (!found) throw new IOException("nested HLS limit");
            }
            synchronized (this) { status = cancelled ? "cancelled" : "sample_ok"; }
        } catch (Exception e) {
            synchronized (this) {
                status = cancelled ? "cancelled" : "failed";
                try { error = new JSONObject(VodErrors.json("probe", e)).optString("code"); } catch (Exception ignored) { error = "IO"; }
            }
        } finally { synchronized (this) { checkedAt = System.currentTimeMillis(); running = false; } }
    }
    private static String uri(String line) {
        Matcher matcher = Pattern.compile("(?:^|[:,])URI=\"([^\"]+)\"").matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }
}
