package xiao.bu.tv;

import java.util.LinkedHashMap;
import java.util.Map;

/** Wall-clock milestones and overlapping worker costs, frozen at the first frame. */
final class ChannelStartupTiming {
    private int request = -1;
    private long started, resolveStarted, resolved, finished, mediaReady, firstData, webReady;
    private long proxyStarted, proxyReady, opened, prepared, releaseStarted, released, pluginMs, selected;
    private long demuxOpened, streamsFound, componentsOpened;
    private boolean decrypted, progressiveDecryption;
    private long failedAt = -1;
    private final Map<String, Long> costs = new LinkedHashMap<String, Long>();

    synchronized void begin(int id, long now) {
        request = id; started = now;
        resolveStarted = resolved = finished = mediaReady = firstData = webReady = -1;
        proxyStarted = proxyReady = opened = prepared = releaseStarted = released = -1;
        demuxOpened = streamsFound = componentsOpened = -1;
        selected = -1;
        decrypted = progressiveDecryption = false; failedAt = -1; pluginMs = 0; costs.clear();
    }
    synchronized void plugin(int id, long duration) {
        if (id == request) pluginMs = Math.max(0, duration);
    }
    synchronized void stage(int id, String stage, long now) {
        if (id != request) return;
        // Background release can finish after the first frame; other startup fields freeze.
        if ("release-begin".equals(stage) && releaseStarted < 0) releaseStarted = now;
        if ("release-end".equals(stage) && released < 0) released = now;
        if ("load-failed".equals(stage)) failedAt = now;
        if ("load-ready".equals(stage) || "load-retry".equals(stage)) failedAt = -1;
        if (finished >= 0) return;
        if ("resolve-begin".equals(stage) && resolveStarted < 0) resolveStarted = now;
        if ("url-selected".equals(stage) && selected < 0) selected = now;
        if ("web-page-ready".equals(stage) && webReady < 0) webReady = now;
        if ("url-resolved".equals(stage) && resolved < 0) resolved = now;
        if ("proxy-reset-begin".equals(stage) && proxyStarted < 0) proxyStarted = now;
        if ("proxy-ready".equals(stage) && proxyReady < 0) proxyReady = now;
        if (("segments-ready".equals(stage) || "media-ready".equals(stage)) && mediaReady < 0) mediaReady = now;
        if ("first-data".equals(stage) && firstData < 0) firstData = now;
        if ("player-open-requested".equals(stage) && opened < 0) opened = now;
        if ("player-demux-opened".equals(stage) && demuxOpened < 0) demuxOpened = now;
        if ("player-streams-found".equals(stage) && streamsFound < 0) streamsFound = now;
        if ("player-components-opened".equals(stage) && componentsOpened < 0) componentsOpened = now;
        if ("player-prepared".equals(stage) && prepared < 0) prepared = now;
        if ("load-ready".equals(stage)) finished = now;
    }
    synchronized void cost(int id, String name, long duration) {
        if (id != request || duration < 0) return;
        // Progressive delivery can render before the first download completes.
        // Fill that one missing cost without accumulating later playback traffic.
        if (finished >= 0 && !("下载".equals(name) && firstData >= 0 && !costs.containsKey("下载"))) return;
        Long old = costs.get(name); costs.put(name, (old == null ? 0 : old) + duration);
    }
    synchronized void segment(int id, long downloadMs, long decryptMs, boolean encrypted) {
        cost(id, "下载", downloadMs);
        if (id != request || finished >= 0 || progressiveDecryption || !encrypted || decryptMs < 0) return;
        decrypted = true; cost(id, "解密", decryptMs);
    }
    synchronized void progressiveDecrypt(int id, long durationMs) {
        if (id != request || finished >= 0 || durationMs < 0) return;
        progressiveDecryption = decrypted = true;
        cost(id, "解密", durationMs);
    }
    private String interval(long from, long to) {
        return from < 0 ? "--" : to < 0 ? "进行中" : Math.max(0, to - from) + "ms";
    }
    private String sum(String name) {
        Long value = costs.get(name); return value == null ? "--" : value + "ms";
    }
    synchronized boolean finished(int id) {
        return id == request && finished >= 0;
    }
    private String playbackTiming() {
        return " 准备:" + interval(opened, prepared)
                + " 出帧:" + interval(prepared, finished)
                + " 加载:" + (mediaReady >= 0 ? interval(mediaReady, finished)
                        : firstData >= 0 ? "边下边播" : "--")
                + " 总计:" + interval(started, finished);
    }
    synchronized String display(int id) {
        if (id != request) return "加载耗时:--";
        if (failedAt >= 0) return "加载失败 · 总计:" + Math.max(0, failedAt - started) + "ms";
        return "解析:" + interval(resolveStarted, selected >= 0 ? selected : resolved >= 0 ? resolved : webReady)
                + " 下载:" + sum("下载")
                + (decrypted ? " 解密:" + sum("解密") : "")
                + playbackTiming();
    }
    synchronized String format(int id) {
        if (id != request) return "耗时:--";
        if (failedAt >= 0) return "加载失败 · 总计:" + Math.max(0, failedAt - started) + "ms";
        String first = (pluginMs > 0 ? "插件:" + pluginMs + "ms " : "")
                + "前置:" + (resolveStarted < 0 ? "--" : Math.max(0, resolveStarted - started - pluginMs) + "ms")
                + " 解析:" + interval(resolveStarted, selected >= 0 ? selected : resolved >= 0 ? resolved : webReady)
                + (selected >= 0 ? " 待应用:" + interval(selected, resolved) : "") + " 清单Σ:" + sum("清单")
                + (costs.containsKey("清单处理") ? " 清单处理Σ:" + sum("清单处理") : "")
                + " 下载:" + (costs.containsKey("下载") ? sum("下载") : firstData >= 0 ? "进行中" : "--") + (decrypted ? " 解密:" + sum("解密") : "");
        String second = "代理:" + interval(proxyStarted, proxyReady)
                + (releaseStarted >= 0 ? " 释放:" + interval(releaseStarted, released) : "")
                + (costs.containsKey("密钥") ? " 密钥Σ:" + sum("密钥") : "")
                + (costs.containsKey("调度") ? " 调度Σ:" + sum("调度") : "")
                + (costs.containsKey("失败请求") ? " 失败请求Σ:" + sum("失败请求") : "")
                + (costs.containsKey("重试") ? " 重试Σ:" + sum("重试") : "")
                + (costs.containsKey("探测") ? " 探测Σ:" + sum("探测") : "")
                + (demuxOpened >= 0 ? " 输入:" + interval(opened, demuxOpened)
                        + " 流探测:" + interval(demuxOpened, streamsFound)
                        + " 组件:" + interval(streamsFound, componentsOpened)
                        + " 回调:" + interval(componentsOpened, prepared) : "")
                + playbackTiming();
        return first + "\n" + second;
    }
}
