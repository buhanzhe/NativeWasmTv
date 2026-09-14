// Run with: node tests/control-pages.test.js (no npm dependencies).
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../app/src/main/assets/control');
let passed = 0;

function browser(page) {
  const elements = new Map(), requests = [], timers = new Map(), events = {};
  let timerId = 0, domWrites = 0;
  function element(tag) {
    const node = { tagName: tag, value: '', style: {}, children: [], textContent: '', disabled: false,
      attributes: {}, setAttribute(key, value) { this.attributes[key] = value; },
      getAttribute(key) { return this.attributes[key]; },
      focus() {}, appendChild(child) { this.children.push(child); domWrites++; } };
    Object.defineProperty(node, 'innerHTML', { set() { node.children = []; domWrites++; } });
    if (tag === 'a') Object.defineProperty(node, 'href', { set(value) { node.hostname = new URL(value).hostname; } });
    return node;
  }
  class Xhr {
    constructor() { this.upload = {}; }
    open(method, url) { this.method = method; this.url = url; }
    setRequestHeader() {}
    send(body) { this.body = body; requests.push(this); }
    respond(data, status = 200) {
      this.status = status; this.responseText = JSON.stringify(data); this.readyState = 4;
      this.onreadystatechange();
    }
    abort() { this.aborted = true; this.onabort(); }
  }
  const context = vm.createContext({ console, XMLHttpRequest: Xhr,
    location: { hostname: '192.168.1.9', pathname: '/' + (page || 'index') + '.html',
      replace(url) { this.replaced = url; this.replaceCount = (this.replaceCount || 0) + 1; } },
    history: { length: 1 },
    document: { hidden: false, activeElement: null,
      getElementById(id) { if (!elements.has(id)) elements.set(id, element('div')); return elements.get(id); },
      querySelectorAll() { return []; }, createElement: element,
      addEventListener(name, fn) { events[name] = fn; } },
    setTimeout(fn, delay) { timers.set(++timerId, { fn, delay }); return timerId; },
    clearTimeout(id) { timers.delete(id); }, renderPageState() {} });
  context.window = { addEventListener(name, fn) { events[name] = fn; } };
  function load(file) { vm.runInContext(fs.readFileSync(path.join(root, file), 'utf8'), context, { filename: file }); }
  load('js/common.js');
  if (page) load('js/pages/' + page + '.js');
  return { context, requests, timers, events, elements,
    writes: () => domWrites,
    runTimers(delay) { for (const [id, timer] of [...timers]) if (timer.delay === delay) { timers.delete(id); timer.fn(); } } };
}

function test(name, fn) { fn(); passed++; console.log('PASS ' + name); }

test('GitHub setting preserves edits across polls and submits custom/empty prefixes', () => {
  const b = browser('advanced'), c = b.context;
  b.requests[0].respond({settings:{githubProxyBaseUrl:'https://mirror.example/'}});
  const input = b.elements.get('githubProxyUrl');
  assert.equal(input.value, 'https://mirror.example/');
  input.value = 'https://other.example/path/'; input.oninput();
  c.renderPageState();
  assert.equal(input.value, 'https://other.example/path/');
  c.saveGithubProxy();
  const save = b.requests.find(r => r.url === '/api/settings');
  assert.deepEqual(JSON.parse(save.body), {githubProxyBaseUrl:'https://other.example/path/'});
  save.respond({ok:true});
  input.value = ''; c.saveGithubProxy();
  const saves = b.requests.filter(r => r.url === '/api/settings');
  assert.deepEqual(JSON.parse(saves[1].body), {githubProxyBaseUrl:''});
});

test('browser playlist downloads use the configured GitHub accelerator', () => {
  const b = browser('channels'), c = b.context;
  c.state = {settings:{githubProxyBaseUrl:'https://mirror.example/proxy/'}};
  const raw = 'https://raw.githubusercontent.com/a/b/main/list.m3u?x=a+b';
  assert.equal(c.githubProxySourceUrl(raw), 'https://mirror.example/proxy/' + raw);
  assert.equal(c.githubProxySourceUrl('https://gh-proxy.com/' + raw), 'https://mirror.example/proxy/' + raw);
  assert.equal(c.githubProxySourceUrl('https://mirror.example/proxy/' + raw), 'https://mirror.example/proxy/' + raw);
  assert.equal(c.githubProxySourceUrl('http://192.168.1.8/live.m3u8'), 'http://192.168.1.8/live.m3u8');
});

test('channel source format errors remain visible and block requests', () => {
  const b = browser('channels'), c = b.context;
  const bad = 'https://example.com/channels.txt#genre#,';
  c.playlistSources = [{id:'bad', name:'错误源', location:bad, enabled:true}];
  c.mergeAndPushPlaylistSources();
  assert.equal(b.elements.get('playlistFormatErrors').style.display, 'block');
  const message = b.elements.get('playlistFormatErrorItems').children[0].textContent;
  assert.ok(message.includes(bad));
  assert.ok(message.includes('分组标记'));
  assert.equal(c.mergeBusy, false);
  assert.equal(b.requests.filter(r => r.url.includes('/api/playlist/')).length, 0);
});

test('playlist validation preserves supported streams, signed URLs and relative M3U paths', () => {
  const c = browser('channels').context;
  c.URL = URL;
  const source = {name:'测试',location:'https://example.com/lists/channels.txt'};
  const text = '分组,#genre#\n直播,https://example.com/live.m3u8?token=a%2Bb%2F&x=1,2\n网页,webview://https://example.com/\n实时,rtsp://192.168.0.1:8554/live\n推流,rtmp://example.com/live';
  const parsed = c.parsePlaylistOnPhone(text, source);
  assert.equal(parsed.entries.length, 4);
  assert.equal(parsed.entries[0].url, 'https://example.com/live.m3u8?token=a%2Bb%2F&x=1,2');
  const m3u = c.parsePlaylistOnPhone('#EXTM3U\n#EXTINF:-1,频道\n../live.m3u8', source);
  assert.equal(m3u.entries[0].url, 'https://example.com/live.m3u8');
  assert.equal(c.playlistAddressProblem('file:///storage/频道 列表.txt', true), '');
});

test('playlist validation reports original line and does not silently ignore broken rows', () => {
  const c = browser('channels').context;
  c.URL = URL;
  const source = {name:'本地测试',location:'https://example.com/channels.txt'};
  for (const bad of ['坏频道,https://example.com/list.txt#genre#,',
    '坏频道,[视频](https://example.com/live)', '坏频道,https://example.com/a b',
    '坏频道,https://example.com/%GG', '坏频道,http://example.com/1.m3u8?mode=1&$8M FHD',
    '这一行漏了逗号', '#EXTINF:-1,缺少地址']) {
    assert.throws(() => c.parsePlaylistOnPhone('分组,#genre#\n' + bad, source), error => {
      assert.equal(error.playlistFormat, true);
      assert.ok(error.message.includes('第 2 行'));
      assert.ok(error.message.includes(bad));
      return true;
    });
  }
});

test('PHP TXT channel lists accept empty CSV columns without changing URL parameters', () => {
  const c = browser('channels').context;
  c.URL = URL;
  const source = {name:'PHP 频道列表',location:'http://example.com/apk/112.php'};
  const parsed = c.parsePlaylistOnPhone('港台,#genre#,\r\r\n频道,,http://example.com/live.php?id=中文&x=1,2\r\r\n广东,#genre#\n频道二,http://example.com/b.m3u8', source);
  assert.equal(parsed.entries.length, 2);
  assert.equal(parsed.entries[0].group, '港台');
  assert.equal(parsed.entries[0].url, 'http://example.com/live.php?id=中文&x=1,2');
  assert.equal(parsed.entries[1].group, '广东');
  assert.throws(() => c.parsePlaylistOnPhone('坏频道,http://example.com/live.php#genre#,', source),
    error => error.playlistFormat === true);
});

test('a malformed imported source cannot cause a partial catalog replacement', () => {
  const b = browser('channels'), c = b.context;
  c.URL = URL;
  c.playlistSources = [
    {id:'good',name:'正确源',location:'https://example.com/good.txt',enabled:true},
    {id:'bad',name:'错误源',location:'https://example.com/bad.txt',enabled:true}];
  const badLine = '频道,https://example.com/live#genre#,';
  c.requestPlaylistText = (source, done) => done(null,
    source.id === 'good' ? '频道,https://example.com/live.m3u8' : badLine);
  c.mergeAndPushPlaylistSources();
  assert.equal(c.mergeBusy, false);
  assert.equal(b.elements.get('mergeButton').disabled, false);
  assert.ok(b.elements.get('mergeDetail').textContent.includes('电视频道未更新'));
  assert.ok(b.elements.get('playlistFormatErrorItems').children[0].textContent.includes(badLine));
  assert.equal(b.requests.filter(r => r.url === '/api/playlist/merge').length, 0);
});

test('APK upload always targets the current web server, regardless of takeover state', () => {
  const b = browser('system'), c = b.context;
  c.state = { isTelevision: false, takeoverReceiverUrl: 'http://192.168.49.1:9966',
    lastTakeoverReceiverUrl: 'http://192.168.1.99:9966' };
  const file = { name: 'sample 1.apk', size: 1234 };
  c.uploadApk(file); c.uploadApk(file);
  const uploads = b.requests.filter(r => r.method === 'POST');
  assert.equal(uploads.length, 1);
  assert.equal(uploads[0].url, '/api/apk/upload?name=sample%201.apk');
  assert.equal(uploads[0].body, file);
  uploads[0].respond({ ok: true, label: 'Sample' });
  assert.equal(c.apkUploadActive, false);
  assert.equal(b.elements.get('apkTransferButton').disabled, false);
});

test('100 overlapping refreshes produce one active request and one trailing refresh', () => {
  const b = browser();
  let rendered = 0;
  b.context.renderPageState = () => rendered++;
  for (let i = 0; i < 100; i++) b.context.refresh();
  assert.equal(b.requests.length, 1);
  b.requests[0].respond({ revision: 1 });
  assert.equal(b.requests.length, 2);
  b.requests[1].respond({ revision: 2 });
  assert.equal(b.context.state.revision, 2);
  assert.equal(rendered, 2);
});

test('hidden and restored pages abort obsolete reads and resume with fresh state', () => {
  const b = browser();
  b.context.startPage();
  b.context.document.hidden = true; b.events.visibilitychange();
  assert.equal(b.requests[0].aborted, true);
  b.context.refresh(); assert.equal(b.requests.length, 1);
  assert.equal(b.elements.has('message'), false, 'intentional abort must not show connection error');
  b.context.document.hidden = false; b.events.visibilitychange();
  assert.equal(b.requests.length, 2);
  b.requests[0].respond({ revision: 'stale' });
  assert.equal(b.context.state, null);
  b.requests[1].respond({ revision: 'fresh' });
  assert.equal(b.context.state.revision, 'fresh');
  b.events.pagehide(); b.events.pageshow({ persisted: true });
  assert.equal(b.requests.length, 3);
});

test('failed state requests release the flight and allow queued work to recover', () => {
  const b = browser(); b.context.refresh(); b.context.refresh();
  b.requests[0].respond({ message: 'busy' }, 503);
  assert.equal(b.requests.length, 2);
  b.requests[1].respond({ ok: true });
  assert.equal(b.context.state.ok, true);
});

test('legacy WebKit visibility events also pause and resume polling', () => {
  const b = browser(); b.context.startPage();
  b.context.document.webkitHidden = true; b.events.webkitvisibilitychange();
  assert.equal(b.context.pageActive, false);
  assert.equal(b.requests[0].aborted, true);
  b.context.document.webkitHidden = false; b.events.webkitvisibilitychange();
  assert.equal(b.context.pageActive, true);
  assert.equal(b.requests.length, 2);
});

test('APK and takeover share strict decimal IPv4 and port handling', () => {
  const { context: c } = browser();
  assert.equal(c.normalizeReceiverAddress('67'), 'http://192.168.1.67:9966');
  c.state = { managementUrl: 'http://192.168.49.1:9966' };
  assert.equal(c.normalizeReceiverAddress('67'), 'http://192.168.49.67:9966');
  assert.equal(c.normalizeReceiverAddress('  HTTPS://192.168.049.067:1234/index.html '), 'https://192.168.49.67:1234');
  for (const input of ['0', '255', '192.168.1.256', '192.168.1.1:0', '192.168.1.1:65536', '127.1', 'https://user@192.168.1.1'])
    assert.throws(() => c.normalizeReceiverAddress(input), undefined, input);
});

test('takeover progress shares state requests, pauses while hidden, and recovers after failure', () => {
  const b = browser('cast'), c = b.context;
  c.beginTakeoverProgress('Connecting', 'Discovery');
  assert.equal(b.requests.length, 1, 'progress must share initial state request');
  b.requests[0].respond({ takeoverProgress: { active: true, percent: 20 } });
  b.requests[1].respond({ takeoverProgress: { active: true, percent: 30 } });
  c.document.hidden = true; b.events.visibilitychange();
  b.runTimers(300); assert.equal(b.requests.length, 2);
  c.document.hidden = false; b.events.visibilitychange();
  assert.equal(b.requests.length, 3);
  b.requests[2].respond({ message: 'busy' }, 503);
  b.runTimers(300); assert.equal(b.requests.length, 4);
  b.requests[3].respond({ takeoverProgress: { active: false, percent: 100 } });
  assert.equal(c.takeoverProgressClosing, true);
  b.runTimers(650); assert.equal(c.takeoverProgressVisible, false);
});

test('successful claim updates state and replaces the connection form with flymouse', () => {
  const b = browser('cast'), c = b.context;
  b.requests[0].respond({ canInitiateTakeover: true, takeoverReceiverUrl: '' });
  b.elements.get('remoteCatalogUrl').value = '67';
  c.saveRemoteCatalogUrl();
  b.requests.find(r => r.url === '/api/takeover').respond({ ok: true, receiverUrl: 'http://192.168.1.67:9966' });
  assert.equal(c.state.takeoverReceiverUrl, 'http://192.168.1.67:9966');
  assert.equal(c.location.replaced, '/pages/flymouse.html');
  c.openTakeoverControls();
  assert.equal(c.location.replaceCount, 1);
});

test('permission continuation opens controls only after the claim actually completes', () => {
  const b = browser('cast'), c = b.context;
  b.requests[0].respond({ canInitiateTakeover: true, takeoverReceiverUrl: '' });
  b.elements.get('remoteCatalogUrl').value = '67';
  c.saveRemoteCatalogUrl();
  b.requests.find(r => r.url === '/api/takeover').respond({ ok: true, pending: true });
  assert.equal(c.location.replaced, undefined);
  c.state.takeoverReceiverUrl = 'http://192.168.1.67:9966';
  c.state.takeoverProgress = { active: false, percent: 100 };
  c.afterStateRefresh();
  assert.equal(c.location.replaced, '/pages/flymouse.html');
});

test('disconnecting or a failed claim never navigates to flymouse', () => {
  for (const connected of [false, true]) {
    const b = browser('cast'), c = b.context;
    b.requests[0].respond({ canInitiateTakeover: true, takeoverReceiverUrl: connected ? 'http://192.168.1.67:9966' : '' });
    b.elements.get('remoteCatalogUrl').value = '67';
    c.saveRemoteCatalogUrl();
    b.requests.find(r => r.url === '/api/takeover').respond(connected ? { ok: true, receiverUrl: '' } : { message: 'offline' }, connected ? 200 : 503);
    assert.equal(c.location.replaced, undefined);
    assert.equal(c.takeoverNavigationPending, false);
  }
});

test('Direct is opt-in and cannot be toggled during takeover', () => {
  const b = browser('cast'), c = b.context;
  b.requests[0].respond({ settings: {}, cast: {} });
  const toggle = b.elements.get('wifiDirectExperimental');
  assert.equal(toggle.checked, false);
  toggle.checked = true;
  c.saveWifiDirectSetting();
  assert.equal(toggle.disabled, true);
  const save = b.requests.find(r => r.url === '/api/settings');
  assert.deepEqual(JSON.parse(save.body), { wifiDirectExperimental: true });
  save.respond({ ok: true });
  c.state = { settings: {wifiDirectExperimental:true}, takeoverReceiverUrl:'http://192.168.1.8:9966' };
  c.renderCastState();
  assert.equal(toggle.checked, true);
  assert.equal(toggle.disabled, true);
  c.state.takeoverReceiverUrl = '';
  c.renderCastState();
  assert.equal(toggle.disabled, false);
});

test('H265 defaults to TCP and an explicit UDP choice survives saving', () => {
  const b = browser('cast'), c = b.context;
  b.requests[0].respond({ settings: { webCastCodec: 'h265' }, cast: {} });
  assert.equal(b.elements.get('castTransport').value, 'tcp');
  b.elements.get('castTransport').value = 'udp';
  c.saveWebCastSettings();
  const save = b.requests.find(r => r.url === '/api/settings');
  assert.equal(JSON.parse(save.body).webCastTransport, 'udp');
  assert.equal(JSON.parse(save.body).webCastCodec, 'h265');
  save.respond({ ok: true });
  c.renderCastState();
  assert.equal(b.elements.get('castTransport').value, 'udp');
});

test('media sniffed resources preserve unchanged rows and send the exact selected URL once', () => {
  const b = browser('media'), c = b.context;
  const url = 'https://example.com/master.m3u8?a=1&token=<test>';
  c.renderMediaSources({ webPage: true, sniffedResources: [{ url }] });
  const list = b.elements.get('mediaSniffedList'), button = list.children[0];
  assert.equal(button.children[1].textContent, 'https://example.com/master.m3u8');
  assert.equal(button.children[1].title, url);
  assert.equal(button.children[0].textContent, '资源 1 · 视频 · M3U8');
  const writes = b.writes();
  c.renderMediaSources({ webPage: true, sniffedResources: [{ url }] });
  assert.equal(b.writes(), writes);
  button.onclick();
  button.onclick();
  const commands = b.requests.filter(r => r.url === '/api/control');
  assert.equal(commands.length, 1);
  assert.deepEqual(JSON.parse(commands[0].body), { action: 'playSniffed', url });
  commands[0].respond({ ok: true });
  assert.equal(button.disabled, false);
  c.renderMediaSources({ webPage: true, sniffedResources: [] });
  assert.equal(b.elements.get('mediaSniffedButton').hidden, true);
  assert.equal(list.children.length, 0);
});

test('channel sources and web resources use distinct visibility, labels and exact actions', () => {
  const b = browser('media'), c = b.context;
  c.renderMediaSources({ sourceCount: 1, sniffedResources: [{ url: 'https://old/video.mp4' }] });
  assert.equal(b.elements.get('mediaSniffedButton').hidden, true);
  c.renderMediaSources({ sourceCount: 3, sourceIndex: 1, sourceKey: '7:2:9' });
  assert.equal(b.elements.get('mediaSniffedButton').hidden, false);
  assert.equal(b.elements.get('mediaSniffedLabel').textContent, '线路');
  const rows = b.elements.get('mediaSniffedList').children;
  assert.equal(rows.length, 3);
  assert.equal(rows[1].className, 'media-sniffed-item selected');
  rows[1].onclick();
  assert.equal(b.requests.filter(r => r.method === 'POST').length, 0);
  rows[2].onclick(); rows[0].onclick();
  const commands = b.requests.filter(r => r.url === '/api/media/control');
  assert.equal(commands.length, 1);
  assert.deepEqual(JSON.parse(commands[0].body), { action: 'source', index: 2, sourceKey: '7:2:9' });
  commands[0].respond({ ok: true });
  c.renderMediaSources({ webPage: true, sourceCount: 3, sniffedResources: [] });
  assert.equal(b.elements.get('mediaSniffedButton').hidden, false);
  assert.equal(b.elements.get('mediaSniffedLabel').textContent, '线路');
  assert.equal(b.elements.get('mediaSniffedList').children.length, 3);
  const url = 'https://example.com/music.mp3?token=a+b';
  c.renderMediaSources({ webPage: true, sniffedResources: [{ url }] });
  assert.equal(b.elements.get('mediaSniffedLabel').textContent, '资源');
  assert.equal(b.elements.get('mediaSniffedButton').hidden, false);
  b.elements.get('mediaSniffedList').children[0].onclick();
  assert.deepEqual(JSON.parse(b.requests.find(r => r.url === '/api/control').body), { action: 'playSniffed', url });
});

test('update check is silent; installation is an explicit system-page action', () => {
  const b = browser('system'), c = b.context;
  c.state = { update: {state:'available',architectureUpgrade:true} };
  c.renderAppUpdate();
  assert.equal(b.elements.get('appUpdateAction').textContent, '升级到 64 位');
  assert.equal(b.requests.filter(r => r.method === 'POST').length, 0);
  let notice = '';
  c.window.confirm = message => { notice = message; return false; };
  c.appUpdateClick();
  assert.match(notice, /更多内存/);
  assert.equal(b.requests.filter(r => r.method === 'POST').length, 0);
  c.window.confirm = () => true;
  c.appUpdateClick(); c.appUpdateClick();
  assert.equal(b.requests.filter(r => r.url === '/api/update/install').length, 1);
  b.requests.find(r => r.url === '/api/update/install').respond({ok:true,update:{state:'downloading',message:'正在下载更新 20%'}});
  assert.equal(b.elements.get('appUpdateButton').disabled, true);
  assert.equal(b.elements.get('appUpdateAction').textContent, '下载中…');
});

test('multimedia upload retains the selected file until the browser finishes reading it', () => {
  const b = browser(), c = b.context;
  c.setInterval = () => 0;
  c.state = {canInitiateTakeover:true,takeoverReceiverUrl:"http://192.168.1.8:9966"};
  c.normalizeReceiverAddress = value => value;
  c.document.getElementById('remoteCatalogUrl').value = 'http://192.168.1.8:9966';
  c.startPage=()=>{};
  vm.runInContext(fs.readFileSync(path.join(root, 'js/pages/multimedia.js'), 'utf8'), c);
  const file = { name: 'sample.mp4', size: 12345 };
  const input = { files: [file], value: 'selected.mp4' };
  c.sendMultimediaFile(input);
  const upload = b.requests.find(r => r.url.startsWith('/api/multimedia/upload?'));
  assert.equal(upload.body, file);
  assert.equal(input.value, 'selected.mp4');
  upload.status = 200; upload.responseText = '{"ok":true}'; upload.onload();
  assert.equal(input.value, '');
  assert.equal(c.multimediaUpload, null);
  c.sendMultimediaFile(input);
  const failedUpload = b.requests.filter(r => r.url.startsWith('/api/multimedia/upload?')).pop();
  failedUpload.ontimeout();
  assert.equal(c.multimediaUpload, null);
  assert.match(b.elements.get('multimediaStatus').textContent, /超时/);
  c.pollMultimedia();
  b.requests.filter(r => r.url === '/api/multimedia/control').pop().respond({ok:true,active:false,message:''});
  assert.match(b.elements.get('multimediaStatus').textContent, /超时/);
});

test('back dismisses the media sheet and leaves the controller available', () => {
  const b = browser('media'), c = b.context;
  c.mediaOpenSettings();
  assert.equal(c.mediaDismissSheet(), true);
  assert.equal(b.elements.get('mediaSettingsBackdrop').getAttribute('aria-hidden'), 'true');
  assert.equal(c.mediaDismissSheet(), false);
});

test('multimedia polling has one in-flight request and resumes after hiding', () => {
  const b = browser(), c = b.context;
  c.state={canInitiateTakeover:true,takeoverReceiverUrl:"http://192.168.0.114:9966"};
  c.setInterval = () => 0;
  c.startPage=()=>{};
  vm.runInContext(fs.readFileSync(path.join(root, 'js/pages/multimedia.js'), 'utf8'), c);
  for (let i = 0; i < 100; i++) c.pollMultimedia();
  assert.equal(b.requests.length, 1);
  c.document.hidden = true;
  b.events.visibilitychange();
  assert.equal(b.requests[0].aborted, true);
  assert.equal(c.multimediaPollFlight, null);
  c.document.hidden = false;
  b.events.visibilitychange();
  assert.equal(b.requests.length, 2);
  b.requests[1].respond({ok:true,active:false});
  c.pollMultimedia();
  assert.equal(b.requests.length, 3);
});

test('local multimedia selection uses native URI bridge without uploading bytes', () => {
  const b=browser(), c=b.context;
  c.setInterval=()=>0;
  c.normalizeReceiverAddress=value=>value;
  let chosen;
  c.NtvDevice=c.window.NtvDevice={chooseLocalMultimedia:(target,audio)=>{chosen={target,audio};}};
  c.startPage=()=>{};
  vm.runInContext(fs.readFileSync(path.join(root,'js/pages/multimedia.js'),'utf8'),c);
  c.state={canInitiateTakeover:true,takeoverReceiverUrl:'http://192.168.0.114:9966'};
  c.document.getElementById('multimediaPreserveAudio').checked=true;
  assert.equal(c.beginMultimediaChoice(),false);
  assert.deepEqual(chosen,{target:'http://192.168.0.114:9966',audio:true});
  assert.equal(b.requests.filter(r=>r.url.includes('/upload')).length,0);
  assert.equal(c.multimediaChoosing,true);
  c.multimediaLocalResult('cancelled');
  assert.equal(c.multimediaChoosing,false);
  assert.equal(c.document.getElementById('multimediaStatus').textContent,'cancelled');
});

test('multimedia uses the connected TV and back returns to content before navigation', () => {
  const b=browser(), c=b.context;
  c.setInterval=()=>0;c.normalizeReceiverAddress=v=>v;
  c.state={canInitiateTakeover:true,takeoverReceiverUrl:'http://192.168.0.114:9966'};
  let target, returned=0;
  c.NtvDevice=c.window.NtvDevice={chooseLocalMultimedia:(v)=>{target=v;},returnFromMultimedia:()=>{returned++;return true;}};
  c.startPage=()=>{};
  vm.runInContext(fs.readFileSync(path.join(root,'js/pages/multimedia.js'),'utf8'),c);
  c.document.getElementById('remoteCatalogUrl').value='http://wrong.example:9966';
  assert.equal(c.beginMultimediaChoice(),false);
  assert.equal(target,'http://192.168.0.114:9966');
  c.goBack();assert.equal(returned,1);assert.equal(c.location.replaced,undefined);
});

test('multimedia entry requires a connected sender and closes on disconnect', () => {
  const b=browser('media'), c=b.context;
  c.setInterval=()=>0;c.startPage=()=>{};
  vm.runInContext(fs.readFileSync(path.join(root,'js/pages/multimedia.js'),'utf8'),c);
  c.state={canInitiateTakeover:true,takeoverReceiverUrl:''};
  c.renderMultimediaEntry();assert.equal(c.document.getElementById('mediaMultimediaButton').hidden,true);
  c.state.takeoverReceiverUrl='http://192.168.0.114:9966';
  c.renderMultimediaEntry();assert.equal(c.document.getElementById('mediaMultimediaButton').hidden,false);
  c.mediaOpenMultimedia();assert.equal(c.mediaDismissSheet(),true);
  c.mediaOpenMultimedia();c.state.takeoverReceiverUrl='';c.renderMultimediaEntry();
  assert.equal(c.document.getElementById('mediaMultimediaBackdrop').getAttribute('aria-hidden'),'true');
  assert.equal(c.beginMultimediaChoice(),false);
  c.state={canInitiateTakeover:false,takeoverReceiverUrl:'http://192.168.0.114:9966'};
  c.renderMultimediaEntry();assert.equal(c.document.getElementById('mediaMultimediaButton').hidden,true);
});

test('back closes a sniffed player through the native bridge before page navigation', () => {
  const b=browser(), c=b.context;let returned=0;
  c.NtvDevice=c.window.NtvDevice={returnFromSniffedResource:()=>{returned++;return true;}};
  c.goBack();assert.equal(returned,1);assert.equal(c.location.replaced,undefined);
});

test('external media controller back restores the page without navigating away', () => {
  const b=browser(), c=b.context;
  c.mediaState={canReturnToWeb:true};
  c.goBack();let request=b.requests[b.requests.length-1];
  assert.equal(request.url,'/api/control');
  assert.equal(JSON.parse(request.body).action,'returnToWeb');
  request.respond({ok:true,returned:true});
  assert.equal(c.mediaState.canReturnToWeb,false);assert.equal(c.location.replaced,undefined);
});

test('volume previews survive polling and commit once to the media receiver route', () => {
  const b=browser('media'), c=b.context;
  c.mediaControllerOpen=true;
  c.mediaState={volume:6,volumeMax:15,volumeAvailable:true};
  c.mediaRenderVolume();
  const input=c.document.getElementById('mediaVolume');
  assert.equal(input.value,'6');
  assert.equal(c.document.getElementById('mediaVolumeValue').textContent,'40%');
  const before=b.requests.length;
  input.value='9';c.mediaVolumePreview(input);
  input.value='12';c.mediaVolumePreview(input);
  c.mediaState.volume=7;c.mediaRenderVolume();
  assert.equal(input.value,'12');assert.equal(b.requests.length,before);
  c.mediaVolumeCommit(input);
  assert.equal(b.requests.length,before+1);
  const request=b.requests[b.requests.length-1];
  assert.equal(request.url,'/api/media/control');
  assert.deepEqual(JSON.parse(request.body),{action:'volume',volume:12});
  c.mediaState.volumeAvailable=false;c.mediaRenderVolume();
  assert.equal(input.disabled,true);
  assert.equal(c.document.getElementById('mediaVolumeValue').textContent,'--');
  c.mediaVolumeCommit(input);assert.equal(b.requests.length,before+1);
});

test('cancelled volume gesture restores receiver state including mute', () => {
  const b=browser('media'), c=b.context;
  c.mediaState={volume:0,volumeMax:25,volumeAvailable:true};
  c.mediaRenderVolume();const input=c.document.getElementById('mediaVolume');
  assert.equal(input.getAttribute('aria-valuetext'),'静音');
  input.value='20';c.mediaVolumePreview(input);c.mediaVolumeCancel();
  assert.equal(input.value,'0');assert.equal(c.mediaVolumeEditing,false);
});

console.log('All ' + passed + ' control-page regression scenarios passed.');
