const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const script = fs.readFileSync(path.join(__dirname, '../plugins/ku9/hnyx.js'), 'utf8');
const NOW = 1792715200000, DUR = 10.0066, OFFSET = 175789332;
const guess = (now) => Math.floor(now / 1000 / DUR - OFFSET) - 2;
function fixture(options = {}) {
  const f = { now: NOW, serverNow: NOW, edge: guess(NOW) - 20, cache: new Map(), calls: [], logs: [], ...options };
  f.reply = (seq, method) => {
    if (f.code) return { code: f.code, headers: {} };
    const exists = seq <= f.edge && seq >= f.edge - 300 && !(f.holes || []).includes(seq);
    const headers = { Date: new Date(f.serverNow).toUTCString(), 'Content-Type': exists ? 'video/mp2t' : 'text/html', 'Content-Length': exists ? '4000000' : '169' };
    if (exists) headers['Last-Modified'] = new Date(f.serverNow - (f.edge - seq) * DUR * 1000).toUTCString();
    if (exists && method === 'HEAD' && f.noHead) return { code: 405, headers };
    return { code: exists ? 200 : 404, headers, body: '' };
  };
  f.run = (id = 'hnws4K', args = '') => {
    const scope = { now: () => f.now, ku9: {
      getQuery: (url, name) => new URL(url).searchParams.get(name) || '',
      getCache: (key) => f.cache.get(key) || null,
      setCache: (key, value, ttl) => { assert.equal(ttl, 600000); f.cache.set(key, JSON.stringify(value)); },
      log: (text) => f.logs.push(text),
      request: (url, method, headers, body, follow) => {
        assert.equal(follow, true); assert.equal(body, '');
        const match = url.match(/\/(?:[A-Za-z0-9_]+)-(\d+)-1-hls\.ts$/); assert(match, url);
        f.calls.push({ seq: Number(match[1]), method, headers });
        if (method !== 'HEAD') { assert.equal(method, 'GET'); assert.equal(headers.Range, 'bytes=0-187'); assert.equal(headers['X-Ku9-Response-Limit'], '188'); }
        const response = f.reply(Number(match[1]), method);
        f.now += f.requestMs || 0;
        return response;
      }
    }};
    vm.createContext(scope); vm.runInContext('Date.now=function(){return now();};', scope);
    vm.runInContext(script, scope);
    return JSON.parse(JSON.stringify(scope.main({ url: `http://A/ku9/js/hnyx.js?id=${id}&dur=${DUR}&offset=${OFFSET}${args}` })));
  };
  return f;
}
function sequences(result) {
  assert(result.m3u8, JSON.stringify(result));
  return [...result.m3u8.matchAll(/-(\d+)-1-hls\.ts/g)].map(m => Number(m[1]));
}
const f = fixture(); const first = f.run();
assert.deepEqual(sequences(first), [f.edge - 3, f.edge - 2, f.edge - 1]);
assert(f.calls.length <= 16); assert(f.calls.every(c => c.method === 'HEAD'));
let count = f.calls.length; f.now += 5000; f.serverNow += 5000;
assert.deepEqual(f.run(), first); assert.equal(f.calls.length, count, 'unchanged window probes/writes unnecessarily');
f.now += 5000; f.serverNow += 5000; f.edge++;
let grown = f.run(); assert.equal(sequences(grown).at(-1), f.edge - 1);
assert.equal(sequences(grown)[0], sequences(first)[0]);
f.now += 10000; f.serverNow += 10000; count = f.calls.length;
assert.deepEqual(f.run(), grown); assert.equal(f.calls.length - count, 1, '404 refresh should keep window and stop');
f.now += 5000; f.serverNow += 5000; f.code = 503;
assert.deepEqual(f.run(), grown, 'transient error erased known playable window');
delete f.code;
for (let i = 0; i < 12; i++) {
  f.now += 10000; f.serverNow += 10000; f.edge++;
  grown = f.run(); const seq = sequences(grown);
  assert(seq.length <= 8); for (let j = 1; j < seq.length; j++) assert.equal(seq[j], seq[j - 1] + 1);
  assert.equal(seq.at(-1), f.edge - 1);
}
for (const id of ['CCTV_4K', 'CCTV16_4K', 'dfws4K', 'gdws4K', 'hnws4K', 'jsws4K', 'scws4K', 'sdws4K', 'szws4K', 'zjws4K']) {
  const c = fixture(); const r = c.run(id); assert.equal(sequences(r).at(-1), c.edge - 1);
  assert.equal(r.player, id === 'CCTV_4K' ? 1 : undefined);
}
const clock = fixture({ now: NOW - 4 * 3600000 });
assert.equal(sequences(clock.run()).at(-1), clock.edge - 1, 'wrong device clock not calibrated');
const behind = fixture({ edge: guess(NOW) + 130 });
assert.equal(sequences(behind.run()).at(-1), behind.edge - 1, 'old offset did not search forwards');
const gaps = fixture(); gaps.holes = [gaps.edge - 2];
assert.equal(sequences(gaps.run()).at(-1), gaps.edge - 4, 'initial window included missing file');
const denied = fixture({ code: 403 }); assert(denied.run().error.includes('403')); assert.equal(denied.calls.length, 1);
const unavailable = fixture({ code: 503 }); assert(unavailable.run().error.includes('503')); assert.equal(unavailable.calls.length, 1);
const empty = fixture({ edge: guess(NOW) - 2000 }); assert(empty.run().error); assert(empty.calls.length <= 12);
const slow = fixture({ requestMs: 1200 }); assert(slow.run().error); assert(slow.calls.length <= 10, 'wall-time budget ignored');
const noHead = fixture({ noHead: true }); assert.equal(sequences(noHead.run()).at(-1), noHead.edge - 1);
assert(noHead.calls.some(c => c.method === 'GET'));
const bad = fixture(); assert(bad.run('../bad').error); assert.equal(bad.calls.length, 0);
const html = fixture(); html.reply = () => ({ code: 200, headers: { 'Content-Type': 'text/html' } }); assert(html.run().error);
const corrupt = fixture(); corrupt.cache.set(`ntv.hnyx.window.v1.hnws4K.${DUR}.${OFFSET}`, JSON.stringify({ version: 1, id: 'hnws4K', dur: DUR, offset: OFFSET, edge: -1 }));
assert(sequences(corrupt.run()).length === 3);
const partial = fixture(); const before = partial.run();
const oldCache = [...partial.cache.values()][0], reply = partial.reply;
partial.now += 10000; partial.serverNow += 10000; partial.edge++;
partial.reply = (seq, method) => seq === partial.edge + 1 ? { code: 503, headers: {} } : reply(seq, method);
assert.deepEqual(partial.run(), before, 'partial probe failure published an uncommitted sequence');
assert.equal([...partial.cache.values()][0], oldCache, 'partial failure changed cached snapshot');
partial.reply = reply;
assert.equal(sequences(partial.run()).at(-1), partial.edge - 1, 'recovery did not commit confirmed sequence');
console.log('PASS verified boundaries, ten IDs, wrong clocks, forward/backward offsets, contiguous 8-segment window, cache reuse, 404 hold, transient errors, HTTP denials, HEAD fallback and bounded probes');
