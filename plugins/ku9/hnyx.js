// 湖南 IPv6 KU9 源：沿用 id / dur / offset 参数，上传文件名仍为 hnyx.js。
// 首次以 HEAD 校准可读分片，随后缓存连续窗口；不下载完整 TS 做探测。
// 版本 2026-10-23；仅改善序号/窗口问题，不能提高源站本身的可用带宽。
function main(item) {
    var base = 'http://[2409:8c6a:b021:3901::28]/c1.cdn.hunancatv.com/live/';
    var began = Date.now(), probes = 0, memo = {}, state = null, key = '';
    var maxDistance = 1024, maxProbes = 32, budgetMs = 12000;
    var id, dur, offset;

    function object(value) {
        if (typeof value === 'string') {
            try { return JSON.parse(value); } catch (ignored) { return null; }
        }
        return value && typeof value === 'object' ? value : null;
    }
    function header(headers, name) {
        headers = object(headers) || {};
        for (var k in headers) {
            if (Object.prototype.hasOwnProperty.call(headers, k) && k.toLowerCase() === name) {
                return String(headers[k] || '');
            }
        }
        return '';
    }
    function address(seq) { return base + id + '-' + seq + '-1-hls.ts'; }
    function log(text) {
        try { if (typeof ku9.log === 'function') ku9.log('hnyx: ' + text); } catch (ignored) {}
    }
    function error(text) { throw new Error(text); }
    function cached() {
        try { return object(ku9.getCache(key)); } catch (ignored) { return null; }
    }
    function save(value) {
        try { ku9.setCache(key, value, 10 * 60 * 1000); } catch (ignored) {}
    }
    function valid(value) {
        return value && value.version === 1 && value.id === id && value.dur === dur
            && value.offset === offset && isFinite(value.edge) && value.edge >= 3
            && Math.floor(value.edge) === value.edge && isFinite(value.first)
            && Math.floor(value.first) === value.first
            && value.first >= 0 && value.first <= value.edge - 3
            && value.edge - value.first <= 16 && isFinite(value.stamp)
            && isFinite(value.advancedAt) && value.advancedAt <= value.stamp
            && isFinite(value.nextCheck) && value.nextCheck >= value.stamp
            && value.nextCheck - value.stamp <= dur * 1000
            && Date.now() >= value.stamp && Date.now() - value.stamp < 120000;
    }
    function request(seq, method) {
        if (probes >= maxProbes || Date.now() - began >= budgetMs) {
            error('分片校准达到本次探测上限，请稍后重试');
        }
        probes++;
        var headers = method === 'HEAD' ? {} : {
            'Range': 'bytes=0-187', 'X-Ku9-Response-Limit': '188',
            'Accept-Encoding': 'identity'
        };
        var result = object(ku9.request(address(seq), method, headers, '', true));
        if (!result) error('分片探测没有返回有效响应');
        return result;
    }
    function probe(seq) {
        if (Object.prototype.hasOwnProperty.call(memo, seq)) return memo[seq];
        if (seq < 0 || Math.floor(seq) !== seq) return { exists: false };
        var response = request(seq, 'HEAD');
        var code = Number(response.code);
        if (code === 405 || code === 501) {
            response = request(seq, 'GET');
            code = Number(response.code);
        }
        var date = Date.parse(header(response.headers, 'date'));
        var modified = Date.parse(header(response.headers, 'last-modified'));
        var value = { exists: false, date: date, modified: modified };
        if (code === 200 || code === 206) {
            var type = header(response.headers, 'content-type').toLowerCase();
            var length = Number(header(response.headers, 'content-length'));
            if (type && !/(video\/|octet-stream|mpeg)/.test(type)) {
                error('分片接口返回了非视频内容');
            }
            if (length > 0 && length < 188) error('分片内容不完整');
            value.exists = true;
        } else if (code === 404 || code === 410) {
            // Missing files are the only responses that justify sequence search.
        } else if (code === 401 || code === 403) {
            error('分片服务器拒绝访问（HTTP ' + code + '）');
        } else {
            error(code ? '分片服务器暂不可用（HTTP ' + code + '）'
                : '无法连接 IPv6 分片服务器，请检查网络');
        }
        memo[seq] = value;
        return value;
    }
    function clockDelta(result) {
        if (!isFinite(result.date)) return 0;
        var delta = result.date - Date.now();
        // Ignore ordinary request/CDN clock jitter; correct severely wrong clocks.
        return Math.abs(delta) > 120000 ? delta : 0;
    }
    function edgeNear(guess) {
        var value = probe(guess), low, high, step;
        if (value.exists) {
            low = guess;
            for (step = 1; step <= maxDistance; step *= 2) {
                if (probe(guess + step).exists) low = guess + step;
                else { high = guess + step; break; }
            }
        } else {
            high = guess;
            for (step = 1; step <= maxDistance; step *= 2) {
                if (probe(guess - step).exists) { low = guess - step; break; }
                high = guess - step;
            }
        }
        if (low === undefined || high === undefined) {
            error('附近没有可确认的直播边界，请更新 dur / offset 或检查源站');
        }
        while (high - low > 1) {
            var middle = Math.floor((low + high) / 2);
            if (probe(middle).exists) low = middle;
            else high = middle;
        }
        return low;
    }
    function calibrate() {
        var guess = Math.floor(Date.now() / 1000 / dur - offset) - 2;
        var first = probe(guess), delta = clockDelta(first);
        if (delta) guess = Math.floor((Date.now() + delta) / 1000 / dur - offset) - 2;
        var edge = edgeNear(guess), run = 0, found = false;
        // Verify the initial contiguous run, leaving the newest file as a reserve.
        for (var seq = edge; seq >= Math.max(0, edge - 12); seq--) {
            if (probe(seq).exists) run++; else run = 0;
            if (run === 4) { edge = seq + 3; found = true; break; }
        }
        if (!found) error('源站未提供连续可读分片，请稍后重试');
        var now = Date.now();
        var result = { version: 1, id: id, dur: dur, offset: offset,
            edge: edge, first: edge - 3, stamp: now, advancedAt: now,
            nextCheck: now + dur * 800 };
        log('校准完成 id=' + id + ' edge=' + edge + ' requests=' + probes);
        return result;
    }
    function playlist(value) {
        var end = value.edge - 1;
        var first = Math.max(value.first, end - 7);
        var text = '#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:'
            + Math.ceil(dur) + '\n#EXT-X-MEDIA-SEQUENCE:' + first + '\n';
        for (var seq = first; seq <= end; seq++) {
            text += '#EXTINF:' + dur + ',\n' + address(seq) + '\n';
        }
        return id === 'CCTV_4K' ? { m3u8: text, player: 1 } : { m3u8: text };
    }

    try {
        item = item || {};
        var url = String(item.url || '');
        id = String(ku9.getQuery(url, 'id') || item.id || '');
        dur = Number(ku9.getQuery(url, 'dur') || item.dur);
        offset = Number(ku9.getQuery(url, 'offset') || item.offset);
        if (!/^[A-Za-z0-9_-]+$/.test(id) || !isFinite(dur) || dur < 1 || dur > 60
                || !isFinite(offset) || Math.floor(offset) !== offset || offset < 0) {
            error('请提供有效的 id、dur 和 offset 参数');
        }
        if (typeof ku9.request !== 'function') error('此脚本需要 ku9.request 接口');
        key = 'ntv.hnyx.window.v1.' + id + '.' + dur + '.' + offset;
        var saved = cached();
        if (valid(saved)) state = saved;
        if (!state) {
            state = calibrate();
            save(state);
            return playlist(state);
        }
        if (Date.now() < state.nextCheck) return playlist(state);
        if (Date.now() - state.advancedAt > Math.max(60000, dur * 6000)) {
            var rebuilt = calibrate();
            if (rebuilt.edge < state.edge) error('直播分片暂未前进，保留已确认窗口');
            state = rebuilt;
        } else {
            // Publish/cache one snapshot only after the entire append succeeds.
            // A failed second probe must not expose an uncommitted sequence.
            var updated = object(JSON.stringify(state));
            var advanced = false;
            // Only append verified consecutive files; never skip over a 404.
            for (var n = 0; n < 3; n++) {
                if (!probe(updated.edge + 1).exists) break;
                updated.edge++;
                updated.first = Math.max(updated.first, updated.edge - 8);
                updated.advancedAt = Date.now();
                advanced = true;
            }
            updated.stamp = Date.now();
            updated.nextCheck = updated.stamp + (advanced ? dur * 800 : Math.min(3000, dur * 350));
            state = updated;
        }
        save(state);
        return playlist(state);
    } catch (failure) {
        var reason = String(failure && failure.message || failure);
        log(reason);
        // A transient probe failure must not erase a valid rolling window.
        if (valid(state) && Date.now() - state.advancedAt < 120000) return playlist(state);
        return { url: '', error: '湖南源：' + reason };
    }
}
