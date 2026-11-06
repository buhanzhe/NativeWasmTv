// Run with: node tests/control-pages.test.js (no npm dependencies).
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../app/src/main/assets/control');
let passed = 0;

// The probe controls share polling with catalogue import; pending writes must
// survive old snapshots and repeated clicks must not restart a long full scan.

function browser(page) {
  const elements = new Map(), requests = [], timers = new Map(), events = {};
  let timerId = 0, domWrites = 0;
  function element(tag) {
    const node = { tagName: tag, value: '', style: {}, children: [], textContent: '', disabled: false, selectedIndex: -1, options: [], parentNode: {insertBefore() {}},
      attributes: {}, setAttribute(key, value) { this.attributes[key] = value; },
      getAttribute(key) { return this.attributes[key]; },
      removeAttribute(key) { delete this.attributes[key]; },
      focus() {}, appendChild(child) { this.children.push(child); domWrites++; },
      querySelector(selector) { this._queries = this._queries || new Map(); if (!this._queries.has(selector)) this._queries.set(selector, element('option')); return this._queries.get(selector); },
      querySelectorAll() { return []; },
      removeChild(child) { this.children = this.children.filter(node => node !== child); if (this._queries) for (const [key, value] of this._queries) if (value === child) this._queries.set(key, null); return child; } };
    Object.defineProperty(node, 'innerHTML', { set() { node.children = []; node.htmlWrites = (node.htmlWrites || 0) + 1; domWrites++; } });
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
      body: {getAttribute(name) { return name === "data-state-view" && page === "about" ? "system" : null; }},
      getElementById(id) { if (!elements.has(id)) elements.set(id, element('div')); return elements.get(id); },
      querySelectorAll() { return []; }, createElement: element,
      addEventListener(name, fn) { events[name] = fn; } },
    setTimeout(fn, delay) { timers.set(++timerId, { fn, delay }); return timerId; },
    clearTimeout(id) { timers.delete(id); }, renderPageState() {} });
  // Deadline tests must use a deterministic clock, not lose 1ms to host scheduling.
  vm.runInContext('Date.now = function () { return 1000000; };', context);
  context.window = { addEventListener(name, fn) { events[name] = fn; } };
  function load(file) { vm.runInContext(fs.readFileSync(path.join(root, file), 'utf8'), context, { filename: file }); }
  load('js/common.js');
  context.NtvDialogs = {confirm(message, done) { done(context.window.confirm ? context.window.confirm(message) : true); }};
  if (page) load('js/pages/' + page + '.js');
  return { context, requests, timers, events, elements,
    writes: () => domWrites,
    runTimers(delay) { for (const [id, timer] of [...timers]) if (timer.delay === delay) { timers.delete(id); timer.fn(); } } };
}

function test(name, fn) { fn(); passed++; console.log('PASS ' + name); }

test('system logs save the selected filter as a direct file download', () => {
  const html = fs.readFileSync(path.join(root, 'pages/system.html'), 'utf8');
  assert.match(html, /onclick="saveAppLogs\(\)">保存(?:日志文件)?<\/button>/);
  assert.ok(!html.includes('copyAppLogs'));
  const b = browser('system'), c = b.context;
  for (const filter of ['all', 'error', 'crash']) {
    c.document.getElementById('appLogFilter').value = filter;
    const requests = b.requests.length;
    c.saveAppLogs();
    assert.equal(c.location.href, '/api/logs?filter=' + filter + '&download=1');
    assert.equal(b.requests.length, requests, 'browser handles the attachment download');
  }
});

test('browser settings save core options without exposing rule details', () => {
  const html=fs.readFileSync(path.join(root,'pages/browser.html'),'utf8');
  assert.ok(html.indexOf('显示与加载') < html.indexOf('隐私与脚本'));
  const b=browser('browser'),c=b.context;
  b.requests[0].respond({settings:{webViewResolution:'720p',webViewPageScale:1,
    webViewUserAgent:'windows',webViewBrowserVersion:'128',webViewLoadImages:true,
    webViewAutoPlaySniffed:true,webViewAdBlock:true,webViewWebRtcEnabled:false,
    webViewCacheBytes:0}});
  assert.equal(b.elements.get('webViewBrowserVersion').value,'128');
  assert.equal(b.elements.get('webViewAdBlock').checked,true);
  assert.equal(b.elements.get('webViewPageCoverStyle').value,'off');
  assert.ok(!html.includes('webViewAutoCloseSniffed'));
  assert.match(html,/<option value="155">155\.0\.8059\.40<\/option>/);
  assert.equal(b.elements.get('webViewWebRtcEnabled').checked,false);
  b.elements.get('webViewBrowserVersion').value='138';
  b.elements.get('webViewWebRtcEnabled').checked=true;
  b.elements.get('webViewPageCoverStyle').value='status';
  c.saveWebViewSettings();
  const save=b.requests.find(r=>r.url==='/api/settings');
  assert.equal(JSON.parse(save.body).webViewBrowserVersion,'138');
  assert.equal(JSON.parse(save.body).webViewWebRtcEnabled,true);
  assert.equal(JSON.parse(save.body).webViewPageCover,true);
  assert.match(html,/aria-label="网页遮罩"/);
  assert.ok(!html.includes('id="webViewPageCover"'));
  assert.equal(JSON.parse(save.body).webViewPageCoverStyle,'status');
  for (const mode of ['black','off']) {
    b.elements.get('webViewPageCoverStyle').value=mode;
    c.saveWebViewSettings();
    const payload=JSON.parse(b.requests[b.requests.length-1].body);
    assert.equal(payload.webViewPageCover,mode!=='off');
    assert.equal(payload.webViewPageCoverStyle,mode==='black'?'black':'status');
  }
  assert.equal('webViewAutoCloseSniffed' in JSON.parse(save.body),false);
  assert.equal('webViewUserScript' in JSON.parse(save.body),false);
});

test('kernel selector is hidden in standard packages and selectable in browser packages', () => {
  const b=browser('browser'),c=b.context;
  b.requests[0].respond({settings:{liteBrowserSupported:false,webSourceEngine:'system'}});
  assert.equal(b.elements.get('webSourceEngineRow').hidden,true);
  assert.equal(b.elements.get('webSourceEngineRow').style.display,'none');
  c.state.settings.liteBrowserSupported=true;c.renderPageState();
  assert.equal(b.elements.get('webSourceEngineRow').hidden,false);
  assert.equal(b.elements.get('webSourceEngine').querySelector('[value="lite"]').disabled,false);
});

test('lite network multiplier is saved, preserved and hidden for the system engine', () => {
  const html=fs.readFileSync(path.join(root,'pages/browser.html'),'utf8');
  assert.match(html,/id="liteNetworkThreadRow"[^>]*hidden/);
  const b=browser('browser'),c=b.context;
  b.requests[0].respond({settings:{webSourceEngine:'system',liteNetworkThreadMultiplier:4,
    liteNetworkThreadCounts:{0:4,2:4,4:8,8:16}}});
  const row=b.elements.get('liteNetworkThreadRow'),select=b.elements.get('liteNetworkThreadMultiplier');
  assert.equal(row.hidden,true);assert.equal(row.style.display,'none');assert.equal(select.value,'4');
  b.elements.get('webSourceEngine').value='lite';c.updateLiteThreadSetting();
  assert.equal(row.hidden,false);assert.equal(select.disabled,false);
  assert.match(b.elements.get('liteNetworkThreadSummary').textContent,/8 个网络线程/);
  for(const multiplier of [0,2,4,8]){
    select.value=String(multiplier);c.saveWebViewSettings();
    const request=b.requests.at(-1),body=JSON.parse(request.body);
    assert.equal(request.url,'/api/settings');assert.equal(body.liteNetworkThreadMultiplier,multiplier);
    assert.equal(body.webSourceEngine,'lite');request.respond({ok:true});
  }
  b.elements.get('webSourceEngine').value='system';c.saveWebViewSettings();
  assert.equal(row.hidden,true);assert.equal(select.value,'8');
  assert.equal(JSON.parse(b.requests.at(-1).body).liteNetworkThreadMultiplier,8);
});

test('multiple named scripts live on an independent third-level page', () => {
  const html=fs.readFileSync(path.join(root,'pages/script.html'),'utf8');
  assert.match(html,/data-parent="\/pages\/browser\.html"/);
  const b=browser('script'),c=b.context;
  b.requests[0].respond({settings:{webViewUserScriptEnabled:true,webViewUserScripts:[
    {id:'video',name:'播放器优化',enabled:true,source:'// ==UserScript==\n// @match https://video.example/*\n// ==/UserScript==\nrunVideo();'},
    {id:'music',name:'音乐增强',enabled:false,source:'runMusic();'}
  ]}});
  assert.equal(b.elements.get('webViewUserScriptEnabled').checked,true);
  assert.equal(b.elements.get('scriptCountSummary').textContent,'2 个脚本 · 1 个启用');
  const rows=b.elements.get('userScriptList').children;
  assert.equal(rows.length,2);
  assert.equal(rows[0].children[0].children[0].children[0].textContent,'播放器优化');
  assert.equal(rows[1].children[0].children[0].children[0].textContent,'音乐增强');
  c.editUserScript('music');
  assert.equal(b.elements.get('webViewUserScriptName').value,'音乐增强');
  b.elements.get('webViewUserScriptName').value='音乐页面增强';
  b.elements.get('webViewUserScriptSource').value='console.log("saved")';
  b.elements.get('webViewUserScriptItemEnabled').checked=true;
  c.saveEditingScript();
  const save=b.requests.find(r=>r.url==='/api/settings');
  const payload=JSON.parse(save.body);
  assert.equal(payload.webViewUserScripts.length,2);
  assert.deepEqual(payload.webViewUserScripts[1],{
    id:'music',name:'音乐页面增强',enabled:true,source:'console.log("saved")',
    installUrl:'',version:''
  });
  save.respond({ok:true});
  assert.equal(b.elements.get('scriptCountSummary').textContent,'2 个脚本 · 2 个启用');
});

test('legacy single script derives its visible name from userscript metadata', () => {
  const b=browser('script');
  b.requests[0].respond({settings:{webViewUserScriptEnabled:true,
    webViewUserScript:'// ==UserScript==\n// @name 旧版脚本\n// ==/UserScript==\nrun();'}});
  const row=b.elements.get('userScriptList').children[0];
  assert.equal(row.children[0].children[0].children[0].textContent,'旧版脚本');
});

test('script page imports a named userscript from a supported HTTPS website for review', () => {
  const b=browser('script'),c=b.context;
  b.requests[0].respond({settings:{webViewUserScriptEnabled:true,webViewUserScripts:[]}});
  c.document.getElementById('scriptInstallUrl').value='https://greasyfork.org/zh-CN/scripts/123-demo';
  c.importUserScriptFromUrl();
  const imported=b.requests.find(r=>r.url==='/api/user-script/import');
  assert.deepEqual(JSON.parse(imported.body),{url:'https://greasyfork.org/zh-CN/scripts/123-demo'});
  imported.respond({ok:true,script:{name:'示例脚本',source:'// ==UserScript==\n// @name 示例脚本\n// ==/UserScript==\nrun();',
    installUrl:'https://update.greasyfork.org/scripts/123/demo.user.js',version:'2.1'},
    warnings:['脚本声明了 GM_* 权限，nTv 暂不提供这些 API']});
  assert.equal(b.elements.get('webViewUserScriptName').value,'示例脚本');
  assert.ok(b.elements.get('scriptImportInfo').textContent.includes('update.greasyfork.org'));
  assert.ok(b.elements.get('scriptCompatibilityWarning').textContent.includes('GM_*'));
  c.saveEditingScript();
  const save=b.requests.find(r=>r.url==='/api/settings');
  const script=JSON.parse(save.body).webViewUserScripts[0];
  assert.equal(script.name,'示例脚本');
  assert.equal(script.installUrl,'https://update.greasyfork.org/scripts/123/demo.user.js');
  assert.equal(script.version,'2.1');
});

test('unchanged script polling does not rebuild the list or overwrite a pending master switch', () => {
  const b=browser('script'), c=b.context;
  b.requests[0].respond({settings:{webViewUserScriptEnabled:false,webViewUserScripts:[
    {id:'one',name:'One',source:'window.one=1;',enabled:true}
  ]}});
  const writes=b.writes();
  for(let i=0;i<50;i++) c.renderPageState();
  assert.equal(b.writes(),writes);
  const toggle=b.elements.get('webViewUserScriptEnabled');
  toggle.checked=true; c.saveScriptMasterSwitch(); c.renderPageState();
  assert.equal(toggle.checked,true);
  b.requests.find(r=>r.url==='/api/settings').respond({ok:true});
  assert.equal(toggle.disabled,false);
});

test('userscript reimport updates the same entry even at capacity and cannot replace an active edit', () => {
  const b=browser('script'), c=b.context;
  const scripts=Array.from({length:32},(_,i)=>({id:'s'+i,name:'Script '+i,source:'old();',enabled:false,
    installUrl:'https://scripts.example/'+i+'.user.js'}));
  b.requests[0].respond({settings:{webViewUserScripts:scripts}});
  c.document.getElementById('scriptInstallUrl').value='https://scripts.example/0.user.js';
  c.importUserScriptFromUrl(); c.importUserScriptFromUrl(); c.editUserScript('s1');
  assert.equal(b.requests.filter(r=>r.url==='/api/user-script/import').length,1);
  assert.equal(c.editingScriptId,'');
  b.requests.find(r=>r.url==='/api/user-script/import').respond({script:{name:'Updated',source:'updated();',
    installUrl:'https://scripts.example/0.user.js'}});
  assert.equal(c.editingScriptId,'s0');
  assert.equal(b.elements.get('webViewUserScriptItemEnabled').checked,false);
  c.saveEditingScript(); c.closeScriptEditor(); c.editUserScript('s1');
  assert.equal(c.editingScriptId,'s0');
  const save=b.requests.find(r=>r.url==='/api/settings');
  const payload=JSON.parse(save.body).webViewUserScripts;
  assert.equal(payload.length,32); assert.equal(payload[0].id,'s0');
  assert.equal(payload[0].source,'updated();');
  save.respond({ok:true});
  assert.equal(c.editingScriptId,'');
});

test('local script entry reads UTF-8 for review, does not execute, and saves only on confirmation', () => {
  const html=fs.readFileSync(path.join(root,'pages/script.html'),'utf8');
  assert.match(html,/id="scriptLocalFile" type="file"/);
  const b=browser('script'), c=b.context;
  b.requests[0].respond({settings:{webViewUserScripts:[]}});
  let reader;
  c.FileReader=function(){ reader=this; this.readAsText=(file,encoding)=>assert.equal(encoding,'UTF-8'); };
  const source='// ==UserScript==\n// @name 本地脚本\n// ==/UserScript==\nwindow.executed=true;';
  const input={value:'selected',files:[{name:'test.user.js',size:source.length}]};
  c.importUserScriptFromFile(input);
  assert.equal(input.value,'');
  assert.equal(b.elements.get('importScriptButton').disabled,true);
  c.createUserScript(); assert.equal(c.editingScriptId,'');
  reader.result=source; reader.onload();
  const req=b.requests.find(r=>r.url==='/api/user-script/import');
  assert.deepEqual(JSON.parse(req.body),{source,fileName:'test.user.js'});
  assert.equal(c.window.executed,undefined);
  req.respond({script:{name:'本地脚本',source,version:'1.2'},warnings:['GM_* 暂不支持']});
  assert.equal(b.requests.filter(r=>r.url==='/api/settings').length,0);
  assert.equal(b.elements.get('webViewUserScriptName').value,'本地脚本');
  assert.match(b.elements.get('scriptImportInfo').textContent,/test.user.js.*1.2/);
  assert.equal(c.editorDirty,true);
  c.saveEditingScript();
  const payload=JSON.parse(b.requests.find(r=>r.url==='/api/settings').body).webViewUserScripts[0];
  assert.equal(payload.source,source); assert.equal(payload.installUrl,'');
});

test('local script failures release busy state and allow retry without stale read callbacks', () => {
  for (const failure of ['error','abort','timeout','throw','empty','server']) {
    const b=browser('script'),c=b.context;
    b.requests[0].respond({settings:{webViewUserScripts:[]}});
    let reader;
    c.FileReader=function(){reader=this; this.readAsText=()=>{if(failure==='throw')throw Error();}; this.abort=()=>this.onabort();};
    const input={files:[{name:'script.js',size:12}]};
    c.importUserScriptFromFile(input);
    if(failure==='error')reader.onerror();
    if(failure==='abort')reader.onabort();
    if(failure==='timeout')b.runTimers(15000);
    if(failure==='empty') {reader.result='';reader.onload();}
    if(failure==='server') {reader.result='invalid';reader.onload();b.requests.find(r=>r.url==='/api/user-script/import').respond({ok:false,message:'无效元数据'});}
    assert.equal(c.scriptImportBusy,false,failure);
    assert.equal(b.elements.get('importLocalScriptButton').disabled,false);
    const count=b.requests.length;
    reader.result='late'; reader.onload(); assert.equal(b.requests.length,count);
    c.importUserScriptFromFile(input);
    if(failure!=='throw')assert.equal(c.scriptImportBusy,true);
  }
});

test('local script import rejects oversized/wrong files, capacity and active edits', () => {
  const b=browser('script'),c=b.context;
  b.requests[0].respond({settings:{webViewUserScripts:[]}});
  c.FileReader=function(){throw Error('Must not read');};
  for(const file of [null,{name:'page.html',size:1},{name:'big.js',size:262145}]) {
    c.importUserScriptFromFile({files:file?[file]:[]});
    assert.equal(c.scriptImportBusy,false);
  }
  c.userScripts=Array.from({length:32},(_,i)=>({id:'s'+i,source:'old'}));
  c.importUserScriptFromFile({files:[{name:'test.js',size:1}]});
  assert.equal(c.scriptImportBusy,false);
  c.userScripts=[]; c.createUserScript(); const id=c.editingScriptId;
  c.importUserScriptFromFile({files:[{name:'test.js',size:1}]});
  c.document.getElementById('scriptInstallUrl').value='https://test.example/script.js';
  c.importUserScriptFromUrl();
  assert.equal(c.editingScriptId,id);
  assert.equal(b.requests.filter(r=>r.url==='/api/user-script/import').length,0);
});

test('finite media downloads directly without recorder navigation; live streams keep the recorder flow', () => {
  const b=browser('media'), c=b.context, downloads=[];
  // The harness link href setter only extracts hostname; use plain anchor nodes here.
  c.document.createElement=tag=>({click(){downloads.push({href:this.href,download:this.download});}});
  c.document.body={appendChild(){},removeChild(){}};
  c.mediaState={fileDownloadAvailable:true,sourceKey:'41:2:7'};
  c.openVideoRecorderPage();
  assert.equal(downloads.length,1);
  assert.equal(downloads[0].href,'/api/media/download?sourceKey=41%3A2%3A7');
  assert.equal(c.location.href,undefined);
  c.window.NtvNavigation={go(url){c.location.href=url;}};
  c.mediaState={fileDownloadAvailable:false,sourceKey:'42:2:8'};
  c.openVideoRecorderPage();
  assert.match(c.location.href,/^\/video-recorder\.html\?/);
  const css=fs.readFileSync(path.join(root,'css/media.css'),'utf8');
  assert.doesNotMatch(css,/67\.5vh/);
});

test('player engine switch saves independently, locks IJK controls and restores them on return', () => {
  const b=browser('advanced'),c=b.context;
  b.requests[0].respond({settings:{decodeMode:'software',hardwareDecoder:'auto',hdrMode:'bt709'}});
  const select=b.elements.get('playerEngine');
  assert.equal(select.value,'ijk');assert.equal(select.disabled,false);
  select.value='system';c.savePlayerEngine();c.savePlayerEngine();
  let saves=b.requests.filter(r=>r.url==='/api/settings');
  assert.equal(saves.length,1);assert.deepEqual(JSON.parse(saves[0].body),{playerEngine:'system'});
  c.renderPageState();assert.equal(select.value,'system');assert.equal(select.disabled,true);
  saves[0].respond({ok:true});
  assert.equal(c.state.settings.playerEngine,'system');assert.equal(select.disabled,false);
  for(const id of ['decoder','hardwareDecoder','hdrMode','rtspTransport'])assert.equal(b.elements.get(id).disabled,true);
  assert.equal(b.elements.get('systemPlayerHint').style.display,'');
  assert.equal(b.elements.get('decoder').value,'software');assert.equal(b.elements.get('hdrMode').value,'bt709');
  select.value='ijk';c.savePlayerEngine();saves=b.requests.filter(r=>r.url==='/api/settings');
  assert.deepEqual(JSON.parse(saves[1].body),{playerEngine:'ijk'});saves[1].respond({ok:true});
  for(const id of ['decoder','hardwareDecoder','hdrMode','rtspTransport'])assert.equal(b.elements.get(id).disabled,false);
  assert.equal(b.elements.get('systemPlayerHint').style.display,'none');
});

test('failed player engine switch returns to the saved engine', () => {
  const b=browser('advanced'),c=b.context;
  b.requests[0].respond({settings:{playerEngine:'system'}});
  const select=b.elements.get('playerEngine');select.value='ijk';c.savePlayerEngine();
  b.requests.find(r=>r.url==='/api/settings').respond({error:'保存失败'},400);
  assert.equal(select.value,'system');assert.equal(select.disabled,false);
  assert.equal(b.elements.get('decoder').disabled,true);
  assert.equal(c.state.settings.playerEngine,'system');
});

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
  assert.deepEqual(JSON.parse(save.body), {githubProxyBaseUrl:'https://other.example/path/',githubProxyEnabled:true});
  save.respond({ok:true});
  input.value = ''; c.saveGithubProxy();
  const saves = b.requests.filter(r => r.url === '/api/settings');
  assert.deepEqual(JSON.parse(saves[1].body), {githubProxyBaseUrl:'',githubProxyEnabled:true});
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

test('GitHub direct mode preserves custom acceleration and survives state polls', () => {
  const b = browser('advanced'), c = b.context;
  b.requests[0].respond({settings:{githubProxyBaseUrl:'https://mirror.example/',githubProxyEnabled:true}});
  const mode=b.elements.get('githubConnectionMode'), input=b.elements.get('githubProxyUrl');
  mode.value='direct';c.changeGithubConnectionMode();c.renderPageState();
  assert.equal(mode.value,'direct');assert.equal(input.disabled,true);
  c.saveGithubProxy();
  const save=b.requests.find(r=>r.url==='/api/settings');
  assert.deepEqual(JSON.parse(save.body),{githubProxyEnabled:false});
  save.respond({ok:true});
  assert.equal(c.state.settings.githubProxyEnabled,false);
  assert.equal(input.value,'https://mirror.example/');
  const channels=browser('channels').context;
  channels.state={settings:{githubProxyEnabled:false,githubProxyBaseUrl:'https://mirror.example/'}};
  const raw='https://raw.githubusercontent.com/a/b/main/list.txt?token=a%2Bb&x=1,2';
  for(const prefix of ['', 'https://mirror.example/', 'https://gh-proxy.com/', 'https://gh-proxy.org/', 'https://ghfile.geekertao.top/', 'https://github-proxy.memory-echoes.cn/', 'https://github.tbap.top/'])
    assert.equal(channels.githubProxySourceUrl(prefix+raw),raw);
  assert.equal(channels.githubProxySourceUrl('https://cdn.example/stream'), 'https://cdn.example/stream');
});

function localFileFixture() {
  const b=browser('channels'),c=b.context, stored=new Map();
  c.sessionStorage={setItem:(k,v)=>stored.set(k,v),getItem:k=>stored.get(k)};
  b.requests[0].respond({settings:{playlistSources:[{id:'local',name:'频道源 1',location:'',enabled:true}]}});
  c.playlistFileTarget='local';
  c.renderPageState(); // The system picker is open during a state poll.
  let reader;
  c.FileReader=class { constructor(){ reader=this; } readAsArrayBuffer(file){this.file=file;this.readyState=1;} abort(){this.readyState=2;this.onabort();} };
  c.playlistFileSelected({files:[{name:'JoyPage.txt',size:200}]});
  return {b,c,reader,stored};
}
test('local playlist upload retains its target through polls and saves the draft', () => {
  const {b,c,reader}=localFileFixture();
  assert.equal(c.playlistFileBusy,true);
  const source=c.playlistSources[0];
  c.renderPageState(); assert.equal(c.playlistSources[0],source);
  assert.equal(source._status,'正在读取 JoyPage.txt');
  reader.result=new Uint8Array([65,66]).buffer;reader.onload();
  const upload=b.requests.find(r=>r.url.startsWith('/api/playlist/upload'));
  assert.equal(upload.body,reader.result);
  assert.equal(upload.timeout,60000);
  c.renderPageState();assert.equal(c.playlistSources[0],source);
  c.mergeAndPushPlaylistSources();assert.equal(b.requests.filter(r=>r.url==='/api/playlist/merge').length,0);
  upload.respond({ok:true,location:'file:///saved/local.playlist',name:'JoyPage.txt'});
  assert.equal(c.playlistFileBusy,false);
  c.renderPageState();
  assert.equal(c.playlistSources[0].location,'file:///saved/local.playlist');
  assert.equal(c.playlistSources[0].name,'JoyPage');
  assert.equal(c.playlistSources[0]._kind,'good');
  upload.onerror();assert.equal(c.playlistSources[0]._kind,'good');
});
test('uploaded local playlists refresh through the TV source API and reach the merge', () => {
  const {b,c,reader}=localFileFixture();
  reader.result=new ArrayBuffer(2);reader.onload();
  const location='file:///data/user/0/xiao.bu.tv/files/imported-playlists/local.playlist';
  b.requests.find(r=>r.url.startsWith('/api/playlist/upload'))
    .respond({ok:true,location,name:'本地.txt'});
  c.mergeAndPushPlaylistSources();
  const read=b.requests.find(r=>r.url.startsWith('/api/playlist/source'));
  assert.equal(decodeURIComponent(read.url.split('location=')[1]),location);
  read.readyState=4;read.status=200;
  read.responseText='本地频道,#genre#\n测试频道,http://example.com/live.m3u8';
  read.onreadystatechange();
  const merge=b.requests.find(r=>r.url==='/api/playlist/merge');
  assert.ok(merge,'local read must reach the final merge');
  const payload=JSON.parse(merge.body);
  assert.equal(payload.sources[0].location,location);
  assert.match(payload.sourcePlaylists[0].playlist,/测试频道/);
  assert.equal(b.requests.some(r=>r.url.startsWith('file:')),false);
});

test('file read/upload failures and missing callbacks always end loading and allow retry', () => {
  for(const failure of ['read', 'readTimeout', 'send', 'timeout', 'network', 'abort', 'badJson', 'missingPath', 'http']) {
    const {b,c,reader}=localFileFixture();
    if(failure==='read')reader.onerror();
    else if(failure==='readTimeout')b.runTimers(30000);
    else {
      if(failure==='send')c.XMLHttpRequest=class {open(){throw new Error('read permission denied')}};
      reader.result=new ArrayBuffer(2);reader.onload();
      if(failure!=='send') {
        const upload=b.requests.find(r=>r.url.startsWith('/api/playlist/upload'));
        if(failure==='timeout')b.runTimers(60000);
        else if(failure==='network')upload.onerror();
        else if(failure==='abort')upload.onabort();
        else if(failure==='badJson'){upload.readyState=4;upload.status=200;upload.responseText='no';upload.onreadystatechange();}
        else if(failure==='missingPath')upload.respond({ok:true});
        else upload.respond({ok:false,message:'磁盘已满'},500);
      }
    }
    assert.equal(c.playlistFileBusy,false,failure);
    assert.equal(c.playlistSources[0]._kind,'bad',failure);
    assert.equal(c.playlistSources[0].location,'',failure);
    assert.ok(b.elements.get('message').textContent, failure);
  }
});
test('cached source failures fall back once and timeouts terminate refresh', () => {
  const b=browser('channels'),c=b.context;
  c.state={settings:{githubProxyEnabled:false}};
  let calls=0,error;
  c.requestPlaylistText({location:'https://gh-proxy.com/https://raw.githubusercontent.com/a/b/main/list.txt'},e=>{calls++;error=e;});
  const cached=b.requests[b.requests.length-1];
  assert.ok(cached.url.startsWith('/api/playlist/source'));
  cached.ontimeout();cached.onerror();
  const request=b.requests[b.requests.length-1];
  assert.equal(request.url,'https://raw.githubusercontent.com/a/b/main/list.txt');
  request.ontimeout();request.onerror();
  assert.equal(calls,1);assert.ok(error.message.includes('超时'));
});

test('GitHub defaults to org, retries com on failure and reuses the healthy route', () => {
  const b=browser('channels'),c=b.context, raw='https://raw.githubusercontent.com/a/b/main/list.txt?token=a%2Bb';
  c.state={settings:{}};
  assert.equal(c.githubProxySourceUrl(raw),'https://gh-proxy.org/'+raw);
  let calls=0,result;
  c.requestPlaylistText({location:'https://gh-proxy.com/'+raw}, (error,text)=>{assert.equal(error,null);calls++;result=text;});
  const cached=b.requests[b.requests.length-1];
  assert.ok(cached.url.startsWith('/api/playlist/source'));cached.ontimeout();
  const first=b.requests[b.requests.length-1];
  assert.equal(first.url,'https://gh-proxy.org/'+raw);
  first.readyState=4;first.status=500;first.onreadystatechange();first.onerror();
  const second=b.requests[b.requests.length-1];
  assert.equal(second.url,'https://gh-proxy.com/'+raw);
  second.readyState=4;second.status=200;second.responseText='频道,http://example.com/live';second.onreadystatechange();
  assert.equal(calls,1);assert.ok(result.includes('频道'));
  assert.equal(c.githubProxySourceUrl(raw),'https://gh-proxy.com/'+raw);
  assert.equal(b.requests.filter(r=>r.url.startsWith('/api/playlist/source')).length,1);
  c.state.settings.githubProxyEnabled=false;
  assert.equal(c.githubProxySourceUrl(raw),raw);
});
test('native cache and all browser accelerators fail exactly once', () => {
  const b=browser('channels'),c=b.context;
  c.state={settings:{}};let calls=0;
  c.requestPlaylistText({location:'https://raw.githubusercontent.com/a/b/main/list.txt'}, ()=>calls++);
  const cached=b.requests[b.requests.length-1];
  assert.ok(cached.url.startsWith('/api/playlist/source'));cached.ontimeout();
  for(const prefix of ['https://gh-proxy.org/','https://gh-proxy.com/',
    'https://ghfile.geekertao.top/','https://github-proxy.memory-echoes.cn/','https://github.tbap.top/']) {
    const request=b.requests[b.requests.length-1];
    assert.ok(request.url.startsWith(prefix));request.ontimeout();request.onerror();
  }
  const fallbacks=b.requests.filter(r=>r.url.startsWith('/api/playlist/source'));
  assert.equal(fallbacks.length,1);assert.equal(calls,1);
});

test('source reads bound native waiting and abort a stuck route without late completion', () => {
  const b=browser('channels'), c=b.context;
  c.state={settings:{githubProxyEnabled:false}};
  let calls=0, receivedError;
  c.requestPlaylistText({location:'https://example.com/list.txt'}, (error)=>{calls++;receivedError=error;});
  const cached=b.requests[b.requests.length-1];
  assert.equal(cached.timeout,25000);
  cached.ontimeout();
  const phone=b.requests[b.requests.length-1];
  assert.equal(phone.url,'https://example.com/list.txt');
  b.runTimers(25000);
  assert.equal(calls,1);assert.ok(receivedError);assert.equal(phone.aborted,true);
  phone.readyState=4;phone.status=200;phone.responseText='迟到频道,http://example.com/live';
  phone.onreadystatechange();cached.onerror();
  assert.equal(calls,1);
});

test('slow PHP sources remain pending past old timeout and succeed once', () => {
  const b=browser('channels'),c=b.context;let calls=0,result;
  c.requestPlaylistText({location:'https://example.com/slow.php'},(error,text)=>{
    assert.equal(error,null);calls++;result=text;
  });
  const cached=b.requests[b.requests.length-1];
  assert.equal(cached.timeout,25000);
  b.runTimers(8000);b.runTimers(30000);assert.equal(calls,0);
  cached.readyState=4;cached.status=200;cached.responseText='频道,http://example.com/live';
  cached.onreadystatechange();cached.onerror();b.runTimers(25000);
  assert.equal(calls,1);assert.equal(result,cached.responseText);
});

test('phone fallback preserves server error and GitHub keeps short mirror budget', () => {
  const b=browser('channels'),c=b.context;let error;
  c.requestPlaylistText({location:'https://example.com/slow.php'},e=>{error=e;});
  const cached=b.requests[b.requests.length-1];
  cached.readyState=4;cached.status=500;cached.responseText=JSON.stringify({message:'upstream timeout'});
  cached.onreadystatechange();const phone=b.requests[b.requests.length-1];
  assert.ok(phone.timeout>24000 && phone.timeout<=25000);phone.onerror();
  assert.ok(error.message.includes('upstream timeout'));assert.ok(error.message.includes('跨域'));
  c.state={settings:{githubProxyEnabled:false}};
  c.requestPlaylistText({location:'https://raw.githubusercontent.com/a/b/main/list.txt'},()=>{});
  assert.equal(b.requests[b.requests.length-1].timeout,8000);
});

test('all source routes share 25 seconds and do not retry after deadline', () => {
  const b=browser('channels'),c=b.context;let calls=0,error;
  c.testNow=0;vm.runInContext('Date.now = function () { return testNow; };',c);
  c.requestPlaylistText({location:'https://example.com/slow.php'},e=>{calls++;error=e;});
  const cached=b.requests[b.requests.length-1];
  c.testNow=18000;cached.readyState=4;cached.status=500;cached.onreadystatechange();
  const phone=b.requests[b.requests.length-1];assert.equal(phone.timeout,7000);
  c.testNow=25000;phone.ontimeout();
  assert.equal(calls,1);assert.ok(error.message.includes('25'));
  const count=b.requests.length;cached.onerror();phone.onerror();b.runTimers(25000);
  assert.equal(b.requests.length,count);assert.equal(calls,1);
});

test('two source reads run concurrently and preserve configured order after reverse completion', () => {
  const b=browser('channels'), c=b.context, pending=new Map(), started=[];
  c.playlistSources=['a','b','c'].map(id=>({id,name:id,location:'https://example.com/'+id,enabled:true}));
  c.requestPlaylistText=(source,done)=>{started.push(source.id);pending.set(source.id,done);};
  c.mergeAndPushPlaylistSources();
  assert.deepEqual(started,['a','b']);
  pending.get('b')(null,'合集,#genre#\n共享,http://example.com/b');
  assert.deepEqual(started,['a','b','c']);
  pending.get('c')(null,'合集,#genre#\n后续,http://example.com/c');
  assert.equal(b.requests.filter(r=>r.url==='/api/playlist/merge').length,0);
  pending.get('a')(null,'合集,#genre#\n共享,http://example.com/a');
  const merges=b.requests.filter(r=>r.url==='/api/playlist/merge');
  assert.equal(merges.length,1);
  const payload=JSON.parse(merges[0].body);
  assert.deepEqual(payload.sourcePlaylists.map(s=>s.id),['a','b','c']);
  assert.ok(payload.playlist.indexOf('http://example.com/a')<payload.playlist.indexOf('http://example.com/b'));
  assert.ok(payload.playlist.indexOf('http://example.com/b')<payload.playlist.indexOf('http://example.com/c'));
});

test('a failed source does not block later reads or repeat the final merge', () => {
  const b=browser('channels'),c=b.context,pending=new Map();
  c.playlistSources=['a','b','c'].map(id=>({id,name:id,location:'https://example.com/'+id,enabled:true}));
  c.requestPlaylistText=(source,done)=>pending.set(source.id,done);
  c.mergeAndPushPlaylistSources();
  pending.get('a')(null,'频道甲,http://example.com/a');
  assert.ok(pending.has('c'));
  pending.get('c')(null,'频道丙,http://example.com/c');
  pending.get('b')(new Error('slow source timeout'));
  const merges=b.requests.filter(r=>r.url==='/api/playlist/merge');
  assert.equal(merges.length,1);
  assert.deepEqual(JSON.parse(merges[0].body).sourcePlaylists.map(s=>s.id),['a','c']);
  assert.deepEqual(JSON.parse(merges[0].body).failedSourceIds,['b']);
});

test('slow device reads race phone direct fetch without waiting the full deadline', () => {
  const b=browser('channels'),c=b.context;let calls=0,result;
  c.requestPlaylistText({location:'https://sub.ottiptv.cc/douyuyqk.m3u'},(e,t)=>{assert.equal(e,null);calls++;result=t;});
  const cached=b.requests[b.requests.length-1];b.runTimers(5000);
  const phone=b.requests[b.requests.length-1];assert.equal(phone.url,'https://sub.ottiptv.cc/douyuyqk.m3u');
  phone.readyState=4;phone.status=200;phone.responseText='channel,http://test/live';phone.onreadystatechange();
  assert.equal(calls,1);assert.equal(result,phone.responseText);assert.equal(cached.aborted,true);
});

test('phone CORS failure does not cancel a still-working device request', () => {
  const b=browser('channels'),c=b.context;let calls=0;
  c.requestPlaylistText({location:'https://sub.ottiptv.cc/yylunbo.m3u'},e=>{assert.equal(e,null);calls++;});
  const cached=b.requests[b.requests.length-1];b.runTimers(5000);b.requests[b.requests.length-1].onerror();assert.equal(calls,0);
  cached.readyState=4;cached.status=200;cached.responseText='channel,http://test/live';cached.onreadystatechange();assert.equal(calls,1);
});

test('browser-first merge downloads directly with no receiver fetch on success', () => {
  const b=browser('channels'),c=b.context;let calls=0;
  const before=b.requests.length;
  c.requestPlaylistText({location:'https://sub.ottiptv.cc/huyayqk.m3u'},(e,t)=>{assert.equal(e,null);assert.equal(t,'#EXTM3U');calls++;},true);
  const phone=b.requests[b.requests.length-1];assert.equal(phone.url,'https://sub.ottiptv.cc/huyayqk.m3u');
  phone.readyState=4;phone.status=200;phone.responseText='#EXTM3U';phone.onreadystatechange();
  assert.equal(calls,1);assert.equal(b.requests.length,before+1);
});

test('regional TXT preserves both web channels and all three explicit-port HLS channels', () => {
  const c=browser('channels').context;c.URL=URL;
  const file=fs.readFileSync(path.resolve(__dirname,'fixtures/regional-channels.txt'),'utf8');
  const parsed=c.parsePlaylistOnPhone(file,{name:'地区频道.txt',location:'file:///regional.txt'});
  assert.equal(parsed.warningCount,0);assert.equal(parsed.entries.length,5);
  const expected=['定陶综艺频道','定陶综合频道','菏泽三套','菏泽二套','菏泽一套'];
  assert.deepEqual(Array.from(parsed.entries,e=>e.name),expected);
  const merged=c.mergePhoneEntries([parsed]);assert.equal(merged.channels.length,5);
  const rebuilt=c.buildMergedM3u(merged).text;
  const again=c.parsePlaylistOnPhone(rebuilt,{name:'regional.m3u',location:'file:///regional.m3u'});
  assert.equal(again.entries.length,5);
  assert.deepEqual(Array.from(again.entries,e=>e.url),Array.from(parsed.entries,e=>e.url));
  assert.equal(merged.channels.filter(ch=>ch.urls[0].includes(':1935/live/live')).length,3);
});

test('source merge allows slow legacy database writes and reports real XHR timeouts', () => {
  const b=browser('channels'),c=b.context;
  c.playlistSources=[{id:'a',name:'a',location:'https://example.com/a.m3u',enabled:true}];
  c.requestPlaylistText=(s,done)=>done(null,'频道,http://example.com/live');
  c.mergeAndPushPlaylistSources();const merge=b.requests.find(r=>r.url==='/api/playlist/merge');
  assert.equal(merge.timeout,60000);
  let error;c.api('/api/test',null,e=>error=e);const request=b.requests.at(-1);
  request.readyState=4;request.status=0;request.responseText='';request.onreadystatechange();assert.equal(error,undefined);
  request.ontimeout();assert.equal(error.message,'请求超时');
});

test('capable receiver accepts structured JSON then asynchronously reports the import result', () => {
  const b=browser('channels'),c=b.context;c.state={settings:{structuredPlaylistImport:true}};
  c.playlistSources=[{id:'a',name:'a',location:'https://example.com/a.m3u',enabled:true}];
  c.requestPlaylistText=(s,done)=>done(null,'测试,#genre#\n频道,http://example.com/live');
  c.mergeAndPushPlaylistSources();const merge=b.requests.find(r=>r.url==='/api/playlist/merge');
  const body=JSON.parse(merge.body);assert.equal(body.async,true);assert.equal(body.playlist,'#EXTM3U\n');
  assert.equal(body.sourcePlaylists[0].channels[0].name,'频道');
  assert.deepEqual(body.sourcePlaylists[0].channels[0].urls,['http://example.com/live']);
  merge.readyState=4;merge.status=200;merge.responseText=JSON.stringify({ok:true,jobId:'j1',state:'working'});merge.onreadystatechange();
  let poll=b.requests.at(-1);assert.equal(poll.url,'/api/playlist/merge/status?id=j1');
  poll.readyState=4;poll.status=200;poll.responseText=JSON.stringify({ok:true,state:'working'});poll.onreadystatechange();
  assert.equal(c.mergeBusy,true);b.runTimers(1000);poll=b.requests.at(-1);
  poll.readyState=4;poll.status=200;poll.responseText=JSON.stringify({ok:true,state:'done',result:{ok:true,groupCount:1,channelCount:1,message:'导入完成'}});poll.onreadystatechange();
  assert.equal(c.mergeBusy,false);
});

test('browser CORS failure falls back once without losing the shared deadline', () => {
  const b=browser('channels'),c=b.context;let calls=0;
  c.testNow=0;c.Date={now:()=>c.testNow};
  c.requestPlaylistText({location:'https://sub.ottiptv.cc/douyuyqk.m3u'},e=>{assert.equal(e,null);calls++;},true);
  const phone=b.requests[b.requests.length-1];c.testNow=8000;phone.onerror();
  const device=b.requests[b.requests.length-1];assert.ok(device.url.startsWith('/api/playlist/source?'));
  assert.ok(device.timeout<=17000);device.readyState=4;device.status=200;device.responseText='#EXTM3U';device.onreadystatechange();
  phone.onerror();assert.equal(calls,1);
});

test('invalid source addresses show a toast before any request', () => {
  const b = browser('channels'), c = b.context;
  const bad = 'https://example.com/channels.txt#genre#,';
  c.playlistSources = [{id:'bad', name:'错误源', location:bad, enabled:true}];
  c.mergeAndPushPlaylistSources();
  const message = b.elements.get('message').textContent;
  assert.equal(b.elements.has('playlistFormatErrors'), false);
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

test('RTP and UDP multicast survive TXT, M3U and legacy browser import', () => {
  const c = browser('channels').context;
  const source = {name:'河南联通',location:'https://example.com/unicom.txt'};
  const addresses = ['rtp://225.1.2.47:10276', 'RTP://225.1.2.54:11380',
    'udp://@239.1.2.3:1234', 'udp://239.1.2.4:1234?localaddr=192.168.1.2',
    'rtp://@[ff15::1234]:5004', 'rtp://192.168.1.1@239.1.2.3:1234'];
  function HttpOnlyURL(value, base) {
    if (/^(rtp|udp):/i.test(value)) throw new Error('Unsupported protocol');
    return new URL(value, base);
  }
  for (const urlApi of [URL, undefined, HttpOnlyURL]) {
    c.URL = urlApi;
    const text = '央视,#genre#\n' + addresses.map((url, i) => '频道' + i + ',,' + url).join('\n');
    const parsed = c.parsePlaylistOnPhone(text, source);
    assert.equal(parsed.warningCount, 0);
    assert.deepEqual(Array.from(parsed.entries, row => row.url), addresses);
    assert.ok(parsed.entries.every(row => row.group === '央视'));
    const local = c.parsePlaylistOnPhone('#EXTM3U\n#EXTINF:-1,CCTV1\n' + addresses[0], {name:'本地',location:'file:///channels.m3u'});
    assert.equal(local.entries[0].url, addresses[0]);
    for (const address of addresses) {
      assert.equal(c.playlistAddressProblem(address, true), '', 'A direct multicast source is accepted');
      let playlist;
      c.requestPlaylistText({name:'单频道',location:address}, (error, text) => { assert.ifError(error); playlist=text; });
      const direct = c.parsePlaylistOnPhone(playlist, {location:address});
      assert.equal(direct.entries.length, 1);
      assert.equal(direct.entries[0].url, address);
      assert.equal(direct.entries[0].name, '单频道');
    }
  }
  c.URL = URL;
  assert.notEqual(c.playlistAddressProblem('rtp://:10276', false), '');
  assert.notEqual(c.playlistAddressProblem('rtp://225.1.2.47:70000', false), '');
  assert.notEqual(c.playlistAddressProblem('udp://239.1.2.3:0', false), '');
  assert.notEqual(c.playlistAddressProblem('rtp://999.1.2.3:1234', true), '');
  assert.notEqual(c.playlistAddressProblem('rtp://', true), '');
  c.epgUrls = [addresses[0]];
  assert.throws(() => c.cleanEpgUrls(), /组播地址不能作为节目单地址/);
});

test('direct multicast sources merge and push as channels without HTTP playlist reads', () => {
  const b = browser('channels'), c = b.context;
  c.state = {settings:{structuredPlaylistImport:true}};
  const urls = ['rtp://225.1.2.47:10276', 'udp://@239.1.2.3:1234'];
  c.playlistSources = urls.map((location, i) => ({id:'multicast'+i, name:'组播'+i, location, enabled:true}));
  c.mergeAndPushPlaylistSources();
  const merges = b.requests.filter(r => r.url === '/api/playlist/merge');
  assert.equal(merges.length, 1);
  const payload = JSON.parse(merges[0].body);
  assert.deepEqual(payload.sources.map(row => row.location), urls);
  assert.deepEqual(payload.sourcePlaylists.map(row => row.channels[0].urls[0]), urls);
  assert.equal(b.requests.filter(r => /playlist\/source|^(rtp|udp):/.test(r.url)).length, 0);
});

test('carrier RTSP SMIL URLs retain their empty query in TXT, M3U and direct-source imports', () => {
  const b = browser('channels'), c = b.context;
  const address = 'rtsp://10.254.216.130/PLTV/88888888/224/3221225621/10000100000000060000000000009742_0.smil?';
  function HttpOnlyURL(value, base) {
    if (/^rtsp:/i.test(value)) throw new Error('Unsupported protocol');
    return new URL(value, base);
  }
  for (const urlApi of [URL, undefined, HttpOnlyURL]) {
    c.URL = urlApi;
    for (const text of ['CCTV-1,' + address, '#EXTM3U\n#EXTINF:-1,CCTV-1\n' + address]) {
      const parsed = c.parsePlaylistOnPhone(text, {location:'file:///channels.txt'});
      assert.equal(parsed.warningCount, 0);
      assert.equal(parsed.entries[0].url, address);
    }
    assert.equal(c.playlistAddressProblem(address, true), '');
    let playlist;
    c.requestPlaylistText({name:'CCTV-1',location:address}, (error,text) => {assert.ifError(error);playlist=text;});
    assert.equal(c.parsePlaylistOnPhone(playlist, {location:address}).entries[0].url, address);
  }
  c.state = {settings:{structuredPlaylistImport:true}};
  c.playlistSources = [{id:'rtsp',name:'CCTV-1',location:address,enabled:true}];
  c.mergeAndPushPlaylistSources();
  const payload = JSON.parse(b.requests.find(r => r.url === '/api/playlist/merge').body);
  assert.equal(payload.sourcePlaylists[0].channels[0].urls[0], address);
  assert.equal(b.requests.filter(r => /playlist\/source|^rtsp:/.test(r.url)).length, 0);
  for (const invalid of ['rtsp://','rtsp://:554/live','rtsp://10.0.0.1:70000/live','rtsp://10.0.0.1:0/live'])
    assert.notEqual(c.playlistAddressProblem(invalid, true), '');
  c.epgUrls = [address];
  assert.throws(() => c.cleanEpgUrls(), /节目单地址/);
});

test('playlist validation skips broken rows and keeps their original line in the warning', () => {
  const c = browser('channels').context;
  c.URL = URL;
  const source = {name:'本地测试',location:'https://example.com/channels.txt'};
  for (const bad of ['坏频道,https://example.com/list.txt#genre#,',
    '坏频道,[视频](https://example.com/live)', '坏频道,https://example.com/a b',
    '坏频道,https://example.com/%GG', '坏频道,http://example.com/1.m3u8?mode=1&$8M FHD',
    '这一行漏了逗号', '#EXTINF:-1,缺少地址']) {
    const parsed = c.parsePlaylistOnPhone('分组,#genre#\n' + bad + '\n#EXTINF:-1,有效频道\nhttps://example.com/good.m3u8', source);
    assert.equal(parsed.entries.length, 1);
    assert.equal(parsed.entries[0].name, '有效频道');
    assert.equal(parsed.warningCount, 1);
    assert.ok(parsed.firstWarning.includes('第 2 行'));
    assert.ok(parsed.firstWarning.includes(bad));
  }
  const parsed = c.parsePlaylistOnPhone('#EXTM3U\n#EXTINF:-1,坏频道\nhttps://example.com/a b\n#EXTINF:-1,好频道\nhttps://example.com/good.m3u8\n#EXTINF:-1,末尾缺失', source);
  assert.equal(parsed.entries.length, 1);
  assert.equal(parsed.entries[0].name, '好频道');
  assert.equal(parsed.warningCount, 2);
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
  assert.equal(c.parsePlaylistOnPhone('坏频道,http://example.com/live.php#genre#,', source).warningCount, 1);
});

test('malformed programs and sources do not block valid channels or repeat a pending merge', () => {
  const b = browser('channels'), c = b.context;
  c.URL = URL;
  c.playlistSources = [
    {id:'good',name:'正确源',location:'https://example.com/good.txt',enabled:true},
    {id:'bad',name:'错误源',location:'https://example.com/bad.txt',enabled:true}];
  const badLine = '坏频道,https://example.com/live#genre#,';
  c.requestPlaylistText = (source, done) => done(null,
    source.id === 'good' ? '好频道,https://example.com/live.m3u8\n' + badLine + '\n另一个频道,https://example.com/next.m3u8' : badLine);
  c.mergeAndPushPlaylistSources();
  assert.equal(c.mergeBusy, true);
  assert.equal(b.elements.get('mergeButton').disabled, true);
  c.mergeAndPushPlaylistSources();
  const requests = b.requests.filter(r => r.url === '/api/playlist/merge');
  assert.equal(requests.length, 1);
  const playlist = JSON.parse(requests[0].body).playlist;
  assert.ok(playlist.includes('live.m3u8'));
  assert.ok(playlist.includes('next.m3u8'));
  assert.ok(!playlist.includes('坏频道'));
  requests[0].respond({ok:true});
  b.requests.find(r => r.url === '/api/settings').respond({ok:true});
  assert.equal(c.mergeBusy, false);
  assert.equal(b.elements.get('mergeButton').disabled, false);
  assert.ok(b.elements.get('message').textContent.includes('已跳过 2 条格式错误'));
  assert.ok(b.elements.get('message').textContent.includes(badLine));
  assert.equal(b.elements.has('playlistFormatErrors'), false);
});

test('all-invalid playlists preserve the existing television list and show the bad entry', () => {
  const b = browser('channels'), c = b.context;
  c.URL = URL;
  c.playlistSources = [{id:'bad', name:'错误源',location:'https://example.com/bad.txt',enabled:true}];
  c.requestPlaylistText = (source, done) => done(null, '坏频道,https://example.com/a b');
  c.mergeAndPushPlaylistSources();
  assert.equal(b.requests.filter(r => r.url === '/api/playlist/merge').length, 0);
  assert.ok(b.elements.get('message').textContent.includes('已保留电视频道列表'));
  assert.ok(b.elements.get('message').textContent.includes('坏频道'));
  assert.equal(c.mergeBusy, false);
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

test('media sniffed resources preserve unchanged rows and send the exact selected URL once', () => {
  const b = browser('media'), c = b.context;
  const url = 'https://example.com/master.m3u8?a=1&token=<test>';
  c.renderMediaSources({ webPage: true, sniffedResources: [{ url }] });
  const list = b.elements.get('mediaSniffedList'), button = list.children[0];
  assert.equal(button.children[1].textContent, 'https://example.com/master.m3u8');
  assert.equal(button.children[1].title, url);
  assert.equal(button.children[0].textContent, '资源 1 · 待探测 · M3U8');
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

test('active browser tab changes controller title and resources, and resource actions carry page identity', () => {
  const b=browser('media'), c=b.context;
  const page={available:false,webPage:true,webPageVisible:true,sourceCount:0,name:'Page A',webPageKey:'1:2',
    sniffedResources:[{url:'https://example.com/shared.mp4',pageKey:'1:2'}]};
  c.renderMediaController(page);
  assert.equal(b.elements.get('mediaTitle').textContent,'Page A');
  assert.equal(b.elements.get('mediaStatus').textContent,'正在浏览网页');
  assert.equal(b.elements.get('mediaLiveBadge').textContent,'网页');
  const oldButton=b.elements.get('mediaSniffedList').children[0];
  c.renderMediaController({...page,name:'Page B',webPageKey:'2:3',sniffedResources:[]});
  assert.equal(b.elements.get('mediaTitle').textContent,'Page B');
  assert.equal(b.elements.get('mediaSniffedList').children.length,0);
  assert.equal(b.elements.get('mediaSniffedButton').hidden,true);
  oldButton.onclick();
  const command=b.requests.find(r=>r.url==='/api/control');
  assert.deepEqual(JSON.parse(command.body),{action:'playSniffed',url:'https://example.com/shared.mp4',pageKey:'1:2'});
  command.respond({ok:true});
  c.renderMediaController(page);
  assert.equal(b.elements.get('mediaSniffedList').children.length,1);
  assert.equal(b.elements.get('mediaTitle').textContent,'Page A');
});

test('100 title/state updates reuse the controller and unchanged track options', () => {
  const b=browser('media'), c=b.context;
  const original=c.buildMediaController; let builds=0;
  c.buildMediaController=function(){builds++;return original();};
  const state={name:'Page',webPageVisible:true,webPage:true,available:false,
    audioTracks:[{index:1,label:'中文'},{index:2,label:'English'}],selectedAudioTrack:1};
  c.renderMediaController(state);
  const audio=b.elements.get('mediaAudio'), first=audio.children[0], writes=audio.htmlWrites;
  for(let i=0;i<100;i++) c.renderMediaController({...state,name:'Page '+i,webPageKey:String(i),
    prepared:i%2===0,selectedAudioTrack:i%2+1});
  assert.equal(builds,1);
  assert.equal(audio.children[0],first);
  assert.equal(audio.value,'2');
  assert.equal(audio.htmlWrites,writes);
  assert.equal(b.elements.get('mediaControllerBody').htmlWrites,1);
  c.renderMediaController({...state,audioTracks:[{index:1,label:'新音轨'}]});
  assert.equal(builds,1);
  assert.equal(audio.children.length,1);
  assert.equal(audio.children[0].textContent,'新音轨');
  c.mediaControllerBuilt=false; // Error recovery removed the body.
  c.renderMediaController(state);
  assert.equal(builds,2);
});

test('same-title tabs invalidate preview identity and hide late previous-tab screenshots', () => {
  const b=browser('media'), c=b.context;
  c.mediaAnalyzePreview=()=>({top:0,bottom:0,invalidGreen:false});
  c.mediaConstrainBackdrop=()=>{};
  c.mediaState={name:'Same title',group:'网页',webPageVisible:true,webPageKey:'1:2'};
  c.mediaUpdatePreview(true);
  const image=b.elements.get('mediaBackdropImage'), previousLoad=image.onload;
  const first=c.mediaPreviewKey;
  c.mediaState={...c.mediaState,webPageKey:'2:3'};
  c.mediaUpdatePreview(false);
  previousLoad();
  assert.equal(image.hidden,true);
  c.mediaUpdatePreview(true);
  assert.notEqual(c.mediaPreviewKey,first);
});

test('sniffed metadata updates display type, duration, dimensions and bitrate without changing playback URL', () => {
  const b=browser('media'),c=b.context,url='https://example.com/video.mp4?token=keep';
  const render=resource=>{c.renderMediaSources({webPage:true,sniffedResources:[{url,...resource}]});return b.elements.get('mediaSniffedList').children[0];};
  let row=render({probeStatus:'pending'});
  assert.ok(row.children[2].textContent.includes('后台探测'));
  row=render({probeStatus:'ready',type:'video',durationMs:3661000,width:1920,height:1080,bitrate:3500000});
  assert.equal(row.children[0].textContent,'资源 1 · 视频 · MP4');
  assert.equal(row.children[2].textContent,'时长 1:01:01 · 1920×1080 · 3.50 Mbps');
  assert.equal(row.children[1].title,url);
  row=render({probeStatus:'ready',type:'audio',durationMs:65000,bitrate:192000});
  assert.equal(row.children[2].textContent,'时长 1:05 · 192 kbps');
  assert.ok(row.children[0].textContent.includes('音乐'));
  row=render({probeStatus:'ready',type:'live',width:1280,height:720,bitrate:2000000});
  assert.equal(row.children[2].textContent,'1280×720 · 2.00 Mbps');
  row=render({probeStatus:'unavailable',type:'unknown'});
  assert.ok(row.children[0].textContent.includes('类型未知'));
  assert.ok(!row.children[2].textContent.includes('0 kbps'));
  row=render({probeStatus:'unavailable',type:'unknown',probeError:'HTTP 404：资源不存在或链接已失效'});
  assert.ok(row.children[2].textContent.includes('HTTP 404'));
  assert.ok(!row.children[2].textContent.includes('可尝试播放'));
  c.renderMediaSources({webPage:true,sniffedResources:[]});
  assert.equal(b.elements.get('mediaSniffedList').children.length,0);
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

test('current channel source shows live resolution bitrate and frame rate', () => {
  const b = browser('media'), c = b.context;
  c.renderMediaSources({
    sourceCount: 2, sourceIndex: 1, sourceKey: '12:3:4', prepared: true,
    currentSourceStats: { width: 1920, height: 1080, bitrate: 5520000, frameRate: 25.1 }
  });
  let rows = b.elements.get('mediaSniffedList').children;
  assert.equal(rows[0].children[1].textContent, '点击播放此线路');
  assert.equal(rows[1].children[1].textContent, '1920×1080 · 5.5 Mbps · 25fps');
  c.renderMediaSources({
    sourceCount: 2, sourceIndex: 1, sourceKey: '12:3:4', prepared: true,
    currentSourceStats: { width: 1280, height: 720, bitrate: 2300000, frameRate: 50, sourceFrameRate: true }
  });
  rows = b.elements.get('mediaSniffedList').children;
  assert.equal(rows[1].children[1].textContent, '1280×720 · 2.3 Mbps · 50fps（源）');
});

test('about retains silent checks and explicit confirmed installation', () => {
  const b = browser('about'), c = b.context;
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

test('back dismisses the media sheet and leaves the controller available', () => {
  const b = browser('media'), c = b.context;
  c.mediaOpenSettings();
  assert.equal(c.mediaDismissSheet(), true);
  assert.equal(b.elements.get('mediaSettingsBackdrop').getAttribute('aria-hidden'), 'true');
  assert.equal(c.mediaDismissSheet(), false);
});

test('management back never invokes player bridges or exit commands', () => {
  const b=browser(), c=b.context;let returned=0;
  c.NtvDevice=c.window.NtvDevice={returnFromSniffedResource:()=>{returned++;return true;},
    returnFromMultimedia:()=>{returned++;return true;}};
  c.mediaState={canReturnToWeb:false,backExitsApp:true};
  c.goBack();assert.equal(returned,0);assert.equal(c.location.replaced,'/index.html');
  assert.equal(b.requests.length,0);
});

test('page buttons delegate navigation to the shared module', () => {
  const b=browser(),c=b.context, calls=[];
  c.window.NtvNavigation={init(){calls.push('init');},go(url){calls.push(url);},back(native){calls.push(native);}};
  c.rememberManagementNavigation(); c.navigateTo('/pages/media.html'); c.goBack();
  assert.deepEqual(calls,['init','/pages/media.html',false]);
  assert.equal(b.requests.length,0);
});

test('direct subpages and unrelated browser history fall back to first-level menu', () => {
  for (const mode of ['external','direct','new-tab','storage-blocked']) {
    const b=browser(),c=b.context;
    c.location.pathname='/pages/script.html';c.location.href='http://192.168.1.9:9966/pages/script.html';
    c.history.length=mode==='new-tab'?1:9;
    c.history.replaceState=value=>{c.history.state=value;};
    c.history.back=()=>{throw Error('Must not navigate unrelated history');};
    c.document.referrer=mode==='external'?'https://example.com/':mode==='new-tab'?'http://192.168.1.9:9966/index.html':'';
    c.sessionStorage={getItem:()=>{
      if(mode==='storage-blocked')throw Error('Storage denied');
      return JSON.stringify({url:'http://192.168.1.9:9966/index.html',hasMenu:true});
    },setItem(){}};
    c.rememberManagementNavigation(); c.goBack();
    assert.equal(c.location.replaced,'/index.html',mode);
    assert.equal(b.requests.length,0);
  }
});

test('volume follows the finger in real time and release commits the latest value', () => {
  const b=browser('media'), c=b.context;
  c.mediaControllerOpen=true;
  c.mediaState={volume:6,volumeMax:15,volumeAvailable:true};
  c.mediaRenderVolume();
  const input=c.document.getElementById('mediaVolume');
  assert.equal(input.value,'6');
  assert.equal(c.document.getElementById('mediaVolumeValue').textContent,'40%');
  const before=b.requests.length;
  input.value='9';c.mediaVolumePreview(input);
  assert.equal(b.requests.length,before+1);
  assert.deepEqual(JSON.parse(b.requests[b.requests.length-1].body),{action:'volume',volume:9});
  input.value='12';c.mediaVolumePreview(input);
  c.mediaState.volume=7;c.mediaRenderVolume();
  assert.equal(input.value,'12');assert.equal(b.requests.length,before+1);
  c.mediaVolumeCommit(input);
  assert.equal(b.requests.length,before+1);
  b.requests[b.requests.length-1].respond({volume:9,volumeMax:15,volumeAvailable:true});
  assert.equal(b.requests.length,before+2);
  const request=b.requests[b.requests.length-1];
  assert.equal(request.url,'/api/media/control');
  assert.deepEqual(JSON.parse(request.body),{action:'volume',volume:12});
  request.respond({volume:12,volumeMax:15,volumeAvailable:true});
  assert.equal(c.mediaLevels.volume.editing,false);
  c.mediaState.volumeAvailable=false;c.mediaRenderVolume();
  assert.equal(input.disabled,true);
  assert.equal(c.document.getElementById('mediaVolumeValue').textContent,'--');
  c.mediaVolumeCommit(input);assert.equal(b.requests.length,before+2);
});

test('cancelled volume gesture restores receiver state including mute', () => {
  const b=browser('media'), c=b.context;
  c.mediaState={volume:0,volumeMax:25,volumeAvailable:true};
  c.mediaRenderVolume();const input=c.document.getElementById('mediaVolume');
  assert.equal(input.getAttribute('aria-valuetext'),'静音');
  input.value='20';c.mediaVolumePreview(input);c.mediaVolumeCancel();
  assert.equal(input.value,'0');assert.equal(c.mediaLevels.volume.editing,true);
  let request=b.requests[b.requests.length-1];
  assert.deepEqual(JSON.parse(request.body),{action:'volume',volume:20});
  request.respond({volume:20,volumeMax:25,volumeAvailable:true});
  request=b.requests[b.requests.length-1];
  assert.deepEqual(JSON.parse(request.body),{action:'volume',volume:0});
  request.respond({volume:0,volumeMax:25,volumeAvailable:true});
  assert.equal(input.value,'0');assert.equal(c.mediaLevels.volume.editing,false);
});

test('brightness defaults to 50 percent, coalesces drag and keeps the release value across polls', () => {
  const b=browser('media'), c=b.context;
  c.mediaControllerOpen=true;
  c.mediaState={brightnessAvailable:true,volume:6,volumeMax:15,volumeAvailable:true};
  const level=c.mediaLevels.brightness, input=c.document.getElementById('mediaBrightness');
  level.render();
  assert.equal(input.value,'50');
  assert.equal(c.document.getElementById('mediaBrightnessValue').textContent,'50%');
  input.value='70';level.preview(input);
  const first=b.requests[b.requests.length-1];
  assert.deepEqual(JSON.parse(first.body),{action:'brightness',brightness:70});
  input.value='85';level.preview(input);level.commit(input);
  c.mediaState.brightness=40;level.render();assert.equal(input.value,'85');
  first.respond({brightness:70,brightnessAvailable:true});
  const final=b.requests[b.requests.length-1];
  assert.deepEqual(JSON.parse(final.body),{action:'brightness',brightness:85});
  final.respond({brightness:85,brightnessAvailable:true});
  assert.equal(level.editing,false);assert.equal(input.value,'85');assert.equal(c.mediaState.volume,6);
});

test('brightness cancellation restores its start and closed-session replies cannot alter a new drag', () => {
  const b=browser('media'),c=b.context;c.mediaControllerOpen=true;
  c.mediaState={brightness:50,brightnessAvailable:true};
  const level=c.mediaLevels.brightness,input=c.document.getElementById('mediaBrightness');
  level.render();input.value='90';level.preview(input);level.cancel();
  const first=b.requests[b.requests.length-1];first.respond({brightness:90});
  const restore=b.requests[b.requests.length-1];assert.equal(JSON.parse(restore.body).brightness,50);
  restore.respond({brightness:50});assert.equal(input.value,'50');
  level.sentAt=0;input.value='65';level.preview(input);
  const stale=b.requests[b.requests.length-1];
  c.setMediaControllerActive(false);assert.equal(stale.aborted,true);
  c.mediaControllerOpen=true;c.mediaControllerGeneration++;
  input.value='80';level.preview(input);const current=b.requests[b.requests.length-1];
  stale.respond({brightness:65});assert.equal(level.sending,true);assert.equal(level.request,current);
  level.commit(input);current.respond({brightness:80});
  b.requests[b.requests.length-1].respond({brightness:80});assert.equal(input.value,'80');
});

test('screenshot entry follows current receiver capability across channel changes', () => {
  const b = browser('media'), c = b.context;
  const state = {available:true, prepared:true, lowResource:true};
  c.renderMediaController({...state, screenshotAvailable:true});
  const button = b.elements.get('mediaScreenshot');
  assert.equal(button.hidden, false);
  assert.equal(button.disabled, false);
  c.renderMediaController({...state, audioOnly:true, screenshotAvailable:false});
  assert.equal(button.hidden, true);
  c.mediaControllerOpen = true;
  const requests = b.requests.length;
  c.mediaCaptureScreenshot();
  assert.equal(b.requests.length, requests);
  c.renderMediaController({...state, screenshotAvailable:false});
  assert.equal(button.hidden, true);
  c.renderMediaController(state); // capability has not arrived yet
  assert.equal(button.hidden, true);
  c.renderMediaController({...state, screenshotAvailable:true});
  assert.equal(button.hidden, false);
  c.mediaShotBusy = true;
  c.renderMediaController({...state, screenshotAvailable:true});
  assert.equal(button.hidden, false);
  assert.equal(button.disabled, true);
});

test('media cover refreshes after async artwork arrival and clears on a new audio/video session', () => {
  const b = browser('media'), c = b.context;
  c.mediaState = { audioOnly:true, prepared:true, artworkKey:'' };
  c.mediaUpdateArtwork();
  const image = b.elements.get('mediaArtwork');
  assert.equal(image.hidden, true);
  c.mediaState.artworkKey = 'session:cover1'; c.mediaUpdateArtwork();
  assert.equal(image.src, '/api/media/artwork?key=session%3Acover1');
  image.onload(); assert.equal(image.hidden, false);
  const loaded = image.onload;
  c.mediaState.artworkKey = ''; c.mediaUpdateArtwork(); loaded();
  assert.equal(image.hidden, true);
  c.mediaState = { audioOnly:false, prepared:true }; c.mediaUpdateArtwork();
  assert.equal(image.hidden, true);
  c.mediaState = { audioOnly:true, lowResource:true };
  c.mediaUpdatePreview(true);
  assert.equal(b.elements.get('mediaBackdropImage').hidden, true);
});

test('SomaFM radio M3U retains all 10 streams and artwork through phone merge', () => {
  const c = browser('channels').context;
  const text = fs.readFileSync(path.join(__dirname, 'fixtures/somafm.m3u'), 'utf8');
  const source = {location: 'http://example.test/radio.m3u', name: 'SomaFM'};
  const parsed = c.parsePlaylistOnPhone(text, source);
  assert.equal(parsed.entries.length, 10);
  assert.equal(parsed.warningCount, 0);
  assert.equal(parsed.entries[0].name, 'Groove Salad');
  assert.equal(parsed.entries[0].epgId, 'somafm.groovesalad');
  assert.equal(parsed.entries[0].url, 'https://ice5.somafm.com/groovesalad-128-mp3');
  assert.equal(parsed.entries[3].logoUrl, 'https://somafm.com/logos/400/lush400.jpg');
  const merged = c.mergePhoneEntries([parsed, parsed]);
  assert.equal(merged.channels.length, 10);
  const roundTrip = c.parsePlaylistOnPhone(c.buildMergedM3u(merged).text, source);
  assert.equal(roundTrip.warningCount, 0);
  assert.deepEqual(JSON.parse(JSON.stringify(roundTrip.entries)), JSON.parse(JSON.stringify(parsed.entries)));
  for (const item of roundTrip.entries) {
    assert.equal(item.group, 'SomaFM MP3');
    assert.equal(item.radio, true);
    assert.ok(item.logoUrl.startsWith('https://somafm.com/logos/400/'));
  }
});

test('M3U sidecars survive phone merge and invalid subtitles do not reject videos', () => {
  const b = browser('channels'), c = b.context;
  c.URL = URL;
  const source = {location:'https://example.test/list/tv.m3u', name:'subtitle test'};
  const text = '#EXTM3U\n#EXTINF:-1 subtitles="zh.srt",Movie\n#EXTVLCOPT:sub-file="en.vtt"\nhttps://example.test/movie.mp4\n#EXTINF:-1,Plain\nhttps://example.test/plain.mp4\n#EXTINF:-1,Bad subtitle\n#EXTVLCOPT:sub-file=javascript:alert(1)\nhttps://example.test/ok.mp4\n';
  const parsed = c.parsePlaylistOnPhone(text, source);
  assert.equal(parsed.entries.length, 3);
  assert.equal(parsed.warningCount, 1);
  assert.deepEqual(Array.from(parsed.entries[0].subtitleUrls), ['https://example.test/list/zh.srt','https://example.test/list/en.vtt']);
  assert.equal(parsed.entries[1].subtitleUrls.length, 0);
  assert.equal(parsed.entries[2].subtitleUrls.length, 0);
  const merged = c.mergePhoneEntries([parsed, parsed]);
  assert.equal(merged.channels[0].subtitleUrls.length, 2);
  const round = c.parsePlaylistOnPhone(c.buildMergedM3u(merged).text, source);
  assert.deepEqual(Array.from(round.entries[0].subtitleUrls), Array.from(parsed.entries[0].subtitleUrls));
});

test('web media cover follows current page and clears stale load callbacks', () => {
  const b=browser('media'),c=b.context;
  c.mediaState={webPageVisible:true,webPageKey:'A',webArtworkUrl:'https://music.test/one.png'};
  c.mediaUpdateArtwork(); const image=b.elements.get('mediaArtwork'),oldLoad=image.onload,oldError=image.onerror;
  assert.equal(image.src,'https://music.test/one.png');
  c.mediaState={webPageVisible:true,webPageKey:'B',webArtworkUrl:'https://music.test/two.png'};
  c.mediaUpdateArtwork(); oldLoad(); assert.equal(image.hidden,true);
  image.onload(); oldError(); assert.equal(image.hidden,false);
  c.mediaState.webArtworkUrl='javascript:alert(1)'; c.mediaUpdateArtwork(); assert.equal(image.src,'');
  assert.equal(image.hidden,true);
  c.mediaState={audioOnly:true,prepared:true,artworkKey:'native',webPageVisible:false,webArtworkUrl:'https://old.test/art.png'};
  c.mediaUpdateArtwork(); assert.equal(image.src,'/api/media/artwork?key=native');
});

test('web playback command uses explicit action and page/media identity', () => {
  const b=browser('media'),c=b.context;
  c.mediaControllerOpen=true;
  c.mediaState={webPageVisible:true,webPageKey:'B:2',webMediaToken:'doc:7',playing:true};
  c.mediaCommand('toggle');
  let body=JSON.parse(b.requests[b.requests.length-1].body);
  assert.equal(body.action,'pause'); assert.equal(body.webPageKey,'B:2'); assert.equal(body.webMediaToken,'doc:7');
  c.mediaState.playing=false; c.mediaCommand('toggle');
  assert.equal(JSON.parse(b.requests[b.requests.length-1].body).action,'play');
  c.mediaState.webPageVisible=false; c.mediaCommand('toggle');
  body=JSON.parse(b.requests[b.requests.length-1].body);
  assert.equal(body.action,'toggle'); assert.equal('webPageKey' in body,false);
});

test('playback position switch defaults off and saves independently', () => {
  const b=browser('playback'),c=b.context;b.requests[0].respond({settings:{}});
  const input=b.elements.get('showPlaybackPosition');assert.equal(input.checked,false);
  c.state.settings.showPlaybackPosition=true;c.renderPageState();assert.equal(input.checked,true);
  c.savePlaybackPosition();const save=b.requests.find(r=>r.url==='/api/settings');
  assert.deepEqual(JSON.parse(save.body),{showPlaybackPosition:true});save.respond({ok:true});
  assert.equal(c.state.settings.showDateTime,undefined);
});
test('playback position switch rolls back on failed save', () => {
  const b=browser('playback'),c=b.context;b.requests[0].respond({settings:{}});
  const input=b.elements.get('showPlaybackPosition');input.checked=true;c.savePlaybackPosition();
  b.requests.find(r=>r.url==='/api/settings').respond({error:'failed'},500);assert.equal(input.checked,false);
});
test('UI scale presets and custom slider preserve choices and only save on release', () => {
  const b=browser('playback'),c=b.context;
  b.requests[0].respond({settings:{uiScaleMode:'slightly_small'}});
  const select=b.elements.get('uiScaleMode'),slider=b.elements.get('uiScalePercent'),controls=b.elements.get('uiScaleCustomControls');
  assert.equal(select.value,'slightly_small');assert.equal(controls.style.display,'none');
  select.value='custom';c.uiScaleModeChanged();assert.equal(controls.style.display,'block');
  b.requests.find(r=>r.url==='/api/settings').respond({ok:true});
  const before=b.requests.length;slider.value='50';c.uiScalePreview();assert.equal(b.requests.length,before);
  assert.equal(b.elements.get('uiScalePercentValue').textContent,'50%');
  c.renderPageState();assert.equal(slider.value,'50');assert.equal(select.value,'custom');
  c.saveDisplay();assert.equal(JSON.parse(b.requests[b.requests.length-1].body).uiScaleMode,'50');
  b.requests[b.requests.length-1].respond({ok:true});c.state.settings.uiScaleMode='50';c.renderPageState();
  assert.equal(select.value,'custom');assert.equal(slider.value,'50');
  for(const mode of ['small','slightly_small','standard','large','extra_large','extra_extra_large']) {
    select.value=mode;c.uiScaleModeChanged();assert.equal(controls.style.display,'none');
    assert.equal(JSON.parse(b.requests[b.requests.length-1].body).uiScaleMode,mode);
    b.requests[b.requests.length-1].respond({ok:true});
  }
  select.value='custom';
  for(const value of ['', '0', '9', '301', '50.5', 'NaN']) {
    const before=b.requests.length;slider.value=value;c.saveDisplay();assert.equal(b.requests.length,before,value);
  }
});
test('fill mode saves and remains selected alongside existing aspect modes', () => {
  const html=fs.readFileSync(path.join(root,'pages/playback.html'),'utf8');
  for(const mode of ['fit','stretch','fill','16:9','4:3']) assert.ok(html.includes('value="'+mode+'"'));
  const b=browser('playback'),c=b.context;b.requests[0].respond({settings:{videoScaleMode:'fill'}});
  assert.equal(b.elements.get('videoScale').value,'fill');c.saveDisplay();
  const save=b.requests.find(r=>r.url==='/api/settings');assert.equal(JSON.parse(save.body).videoScaleMode,'fill');
  save.respond({ok:true});c.state.settings.videoScaleMode='4:3';c.renderPageState();
  assert.equal(b.elements.get('videoScale').value,'4:3');
});
test('deleting the last custom source can refresh an empty catalogue and retry after failure', () => {
  const b=browser('channels'),c=b.context;
  c.playlistSources=[{id:'last',name:'last',location:'https://example.com/list.m3u',enabled:true}];
  let saved=0,reads=0;c.saveChannelDraft=()=>{saved++;};
  c.requestPlaylistText=()=>{reads++;};
  c.removePlaylistSource(0);assert.equal(c.playlistSources.length,0);assert.equal(saved,1);
  c.mergeAndPushPlaylistSources();
  let request=b.requests.filter(r=>r.url==='/api/settings').pop();
  assert.deepEqual(JSON.parse(request.body).playlistSources,[]);assert.equal(reads,0);
  assert.equal(c.mergeBusy,true);
  request.onerror();assert.equal(c.mergeBusy,false);assert.equal(b.elements.get('mergeButton').disabled,false);
  c.mergeAndPushPlaylistSources();request=b.requests.filter(r=>r.url==='/api/settings').pop();
  request.respond({ok:true});assert.equal(c.mergeBusy,false);assert.equal(c.channelBaseSources,'[]');
});
test('phone merge preserves DIYP catchup templates separately for each live line', () => {
  const b=browser('channels'),c=b.context;
  const template='http://10.1.1.1:1111/CCTV/CCTV-1/catchup?playseek=${(b)yyyyMMddHHmmss}-${(e)yyyyMMddHHmmss}';
  const raw='#EXTM3U catchup-days="5"\n#KODIPROP:inputstream=inputstream.ffmpegdirect\n'
    +'#EXTINF:-1 catchup="default" catchup-source="'+template+'" group-title="CCTV",CCTV-1\nhttp://10.1.1.1:1111/CCTV/CCTV-1\n'
    +'#EXTINF:-1 catchup="append" catchup-source="?start={utc}" group-title="CCTV",CCTV-1\nhttp://example.com/second\n';
  const parsed=c.parsePlaylistOnPhone(raw.replace(/\\n/g,'\n'),{id:'diyp',location:'http://example.com/list.m3u'});
  assert.equal(parsed.entries.length,2);
  const merged=c.mergePhoneEntries([parsed]);assert.equal(merged.channels.length,1);
  const rebuilt=c.buildMergedM3u(merged).text;
  const restored=c.parsePlaylistOnPhone(rebuilt,{id:'roundtrip',location:'http://example.com/merged.m3u'});
  assert.equal(restored.entries[0].catchup['catchup-source'],template);
  assert.equal(restored.entries[0].catchup['catchup-days'],'5');
  assert.equal(restored.entries[1].catchup.catchup,'append');
  assert.equal(restored.entries[1].catchup['catchup-source'],'?start={utc}');
});



test('group polling retains a clean open editor until the user closes it', () => {
  const b=browser('groups'), c=b.context;
  let closed=0, rendered=0;
  c.closeGroupChannels=()=>{ closed++; c.channelEditor=null; };
  c.renderPlaylistGroupSettings=()=>rendered++;
  c.state={settings:{playlistGroups:[{id:'group:new',name:'Updated',channelCount:1}]}};
  c.groupSignature='old'; c.channelEditor={dirty:false,fullscreen:false};
  for(let i=0;i<50;i++) c.renderPageState();
  assert.equal(closed,0); assert.equal(rendered,0);
  c.channelEditor=null; c.renderPageState();
  assert.equal(closed,1); assert.equal(rendered,1);
  c.renderPageState(); assert.equal(rendered,1);
});



test('save and reload share one busy interval and restore enabled group controls', () => {
  const b=browser('groups'), c=b.context;
  const controls=[{disabled:false},{disabled:true}];
  c.document.getElementById('playlistGroupSettings').querySelectorAll=()=>controls;
  c.setGroupBusy(true); c.setGroupBusy(true); c.setGroupBusy(false);
  assert.equal(controls[0].disabled,false); assert.equal(controls[1].disabled,true);
  c.setGroupBusy(false); assert.equal(controls[0].disabled,false);
});
test('live media shows the common A/V buffer and commits a source-bound forward adjustment', () => {
  const b=browser('media'),c=b.context;
  c.mediaControllerOpen=true;
  c.renderMediaController({available:true,prepared:true,playing:true,durationMs:0,sourceKey:'live:1',
    liveBufferMs:6500,liveBufferSeekMaxMs:6250,liveBufferSeekable:true,audioOnly:true});
  const input=b.elements.get('mediaProgress');
  assert.equal(input.max,'6250');assert.equal(input.disabled,false);
  assert.equal(b.elements.get('mediaDuration').textContent,'缓冲 6.5 秒');
  assert.equal(b.elements.get('mediaProgressSection').hidden,false);
  input.value='3000';c.mediaProgressPreview(input);
  assert.equal(b.elements.get('mediaPosition').textContent,'前进 3.0 秒');
  c.mediaSeekCommit(input);
  assert.deepEqual(JSON.parse(b.requests.find(r=>r.url==='/api/media/control').body),
    {action:'liveSeek',advanceMs:3000,sourceKey:'live:1'});
});
test('live drag holds its range across polling and rejects a channel switch', () => {
  const b=browser('media'),c=b.context,state={available:true,prepared:true,playing:true,durationMs:0,
    sourceKey:'live:1',liveBufferMs:6500,liveBufferSeekMaxMs:6250,liveBufferSeekable:true,audioOnly:true};
  c.mediaControllerOpen=true;c.renderMediaController(state);
  const input=b.elements.get('mediaProgress');input.value='3000';c.mediaProgressPreview(input);
  c.renderMediaController({...state,liveBufferMs:3500,liveBufferSeekMaxMs:3250});
  assert.equal(input.max,'6250');assert.equal(input.value,'3000');
  c.renderMediaController({...state,sourceKey:'live:2'});c.mediaSeekCommit(input);
  assert.equal(b.requests.filter(r=>r.url==='/api/media/control').length,0);
});
test('unsupported live buffers remain read-only and pending adjustment cannot be submitted twice', () => {
  const b=browser('media'),c=b.context,state={available:true,prepared:true,playing:true,durationMs:0,
    sourceKey:'live:1',liveBufferMs:6500,liveBufferSeekMaxMs:6250,audioOnly:true};
  c.mediaControllerOpen=true;c.renderMediaController(state);
  assert.equal(b.elements.get('mediaProgress').disabled,true);
  assert.equal(b.elements.get('mediaLiveEdge').disabled,true);
  c.mediaJumpBufferedEdge();assert.equal(b.requests.filter(r=>r.url==='/api/media/control').length,0);
  c.renderMediaController({...state,liveBufferSeekable:true,liveBufferAdjusting:true});
  c.mediaJumpBufferedEdge();assert.equal(b.requests.filter(r=>r.url==='/api/media/control').length,0);
});
test('VOD media keeps its absolute seek timeline and hides the live buffer actions', () => {
  const b=browser('media'),c=b.context;c.mediaControllerOpen=true;
  c.renderMediaController({available:true,prepared:true,playing:true,durationMs:60000,positionMs:12000,
    sourceKey:'vod:1',seekable:true,audioOnly:true});
  const input=b.elements.get('mediaProgress');assert.equal(input.value,'12000');assert.equal(input.max,'60000');
  assert.equal(b.elements.get('mediaLiveBufferActions').hidden,true);
  input.value='25000';c.mediaProgressPreview(input);c.mediaSeekCommit(input);
  assert.deepEqual(JSON.parse(b.requests.find(r=>r.url==='/api/media/control').body),{action:'seek',positionMs:25000,sourceKey:'vod:1'});
});
test('live buffer commit coalesces rapid duplicate button clicks before the response', () => {
  const b=browser('media'),c=b.context;c.mediaControllerOpen=true;
  c.renderMediaController({available:true,prepared:true,playing:true,durationMs:0,sourceKey:'live:1',
    liveBufferMs:6500,liveBufferSeekMaxMs:6250,liveBufferSeekable:true,audioOnly:true});
  for(let i=0;i<100;i++)c.mediaJumpBufferedEdge();
  assert.equal(b.requests.filter(r=>r.url==='/api/media/control').length,1);
});
test('an old seek completion cannot interrupt a new drag or controller session', () => {
  const b=browser('media'),c=b.context,state={available:true,prepared:true,playing:true,durationMs:60000,
    positionMs:12000,sourceKey:'vod:1',seekable:true,audioOnly:true};
  c.mediaControllerOpen=true;c.renderMediaController(state);
  const input=b.elements.get('mediaProgress');input.value='25000';c.mediaProgressPreview(input);c.mediaSeekCommit(input);
  input.value='30000';c.mediaProgressPreview(input);b.runTimers(350);
  c.renderMediaController({...state,positionMs:14000});
  assert.equal(c.mediaSeeking,true);assert.equal(input.value,'30000');
});
test('slider blur never rebuilds a DOM subtree synchronously during removal', () => {
  const b=browser('media'),c=b.context;c.mediaControllerOpen=true;c.mediaState={};
  let paints=0;c.renderMediaController=()=>paints++;
  c.mediaSeeking=true;c.mediaProgressCancel();assert.equal(paints,0);assert.equal(c.mediaSeeking,false);
  b.runTimers(0);assert.equal(paints,1);
  c.mediaProgressCancel();c.mediaControllerGeneration++;b.runTimers(0);assert.equal(paints,1);
  c.mediaProgressCancel();c.mediaSeeking=true;c.mediaSeekRevision++;b.runTimers(0);assert.equal(paints,1);
});
test('failed live adjustment releases pending controls so the user can retry', () => {
  const b=browser('media'),c=b.context;c.mediaControllerOpen=true;
  c.renderMediaController({available:true,prepared:true,playing:true,durationMs:0,sourceKey:'live:1',
    liveBufferMs:6500,liveBufferSeekMaxMs:6250,liveBufferSeekable:true,audioOnly:true});
  c.mediaJumpBufferedEdge();assert.equal(c.mediaSeekPending,true);
  b.requests.find(r=>r.url==='/api/media/control').respond({error:'temporary failure'},500);
  assert.equal(c.mediaSeekPending,false);assert.equal(b.elements.get('mediaProgress').disabled,false);
  c.mediaJumpBufferedEdge();assert.equal(b.requests.filter(r=>r.url==='/api/media/control').length,2);
});
test('an obsolete controller response cannot unlock a newer live adjustment', () => {
  const b=browser('media'),c=b.context;c.mediaControllerOpen=true;
  const state={available:true,prepared:true,playing:true,durationMs:0,sourceKey:'live:1',
    liveBufferMs:6500,liveBufferSeekMaxMs:6250,liveBufferSeekable:true,audioOnly:true};
  c.renderMediaController(state);c.mediaJumpBufferedEdge();const old=b.requests.find(r=>r.url==='/api/media/control');
  c.setMediaControllerActive(false);c.setMediaControllerActive(true);c.renderMediaController(state);c.mediaJumpBufferedEdge();
  old.respond(state);assert.equal(c.mediaSeekPending,true);assert.equal(b.elements.get('mediaProgress').disabled,true);
});
test('a newer control command cannot leave the live request latched busy', () => {
  const b=browser('media'),c=b.context;c.mediaControllerOpen=true;
  const state={available:true,prepared:true,playing:true,durationMs:0,sourceKey:'live:1',
    liveBufferMs:6500,liveBufferSeekMaxMs:6250,liveBufferSeekable:true,audioOnly:true};
  c.renderMediaController(state);c.mediaJumpBufferedEdge();const old=b.requests.find(r=>r.url==='/api/media/control');
  c.mediaCommand('favorite');old.respond(state);assert.equal(c.mediaSeekPending,false);
});


test('startup preferences preserve custom duration drafts, validate input and save URLs',()=>{
 const b=browser('personalization'),c=b.context;
 b.requests[0].respond({settings:{personalBackground:{mode:'bing',displaySeconds:1}}});
 const get=id=>b.elements.get(id);
 get('backgroundDuration').value='custom';c.changeBackgroundDuration();
 get('backgroundCustomSeconds').value='17';c.renderPageState();
 assert.equal(get('backgroundDuration').value,'custom');assert.equal(get('backgroundCustomSeconds').value,'17');
 c.saveBackgroundDuration();assert.equal(JSON.parse(b.requests.at(-1).body).backgroundDisplaySeconds,17);
 b.requests.at(-1).respond({ok:true});
 get('backgroundDuration').value='custom';get('backgroundCustomSeconds').value='0';
 let count=b.requests.length;c.saveBackgroundDuration();assert.equal(b.requests.length,count);
 get('backgroundCustomSeconds').value='1.5';c.saveBackgroundDuration();assert.equal(b.requests.length,count);
 get('backgroundImageUrl').value='file:///private/image';c.saveBackgroundUrl();assert.equal(b.requests.length,count);
 get('backgroundImageUrl').value='https://example.com/image.jpg';get('backgroundImageUrl').oninput();c.renderPageState();assert.equal(get('backgroundImageUrl').value,'https://example.com/image.jpg');c.saveBackgroundUrl();assert.equal(JSON.parse(b.requests.at(-1).body).backgroundImageUrl,'https://example.com/image.jpg');
});
test('system IP copy supports LAN WebKit, multiple IPv6 addresses and secure clipboard', () => {
  const b=browser('system'), c=b.context;
  const inputs=[];let copied='',focusRestored=false;
  c.navigator={};c.window.isSecureContext=false;
  c.document.activeElement={focus(){focusRestored=true;}};
  c.document.body.appendChild=input=>inputs.push(input);
  c.document.body.removeChild=input=>inputs.splice(inputs.indexOf(input),1);
  c.document.createElement=()=>({style:{},setAttribute(){},select(){},setSelectionRange(){}});
  c.document.execCommand=command=>{assert.equal(command,'copy');copied=inputs[0].value;return true;};
  const field=id=>c.document.getElementById(id);
  field('sysIpv4').textContent='192.168.0.106';c.copySystemAddress('sysIpv4');
  assert.equal(copied,'192.168.0.106');assert.equal(inputs.length,0);assert.equal(focusRestored,true);
  field('sysIpv6').textContent='2408::1、2408::2';c.copySystemAddress('sysIpv6');
  assert.equal(copied,'2408::1\n2408::2');assert.equal(inputs.length,0);
  field('sysIpv4').textContent='无';c.copySystemAddress('sysIpv4');
  assert.equal(copied,'2408::1\n2408::2');assert.equal(field('message').textContent,'暂无可复制的IP地址');
  field('sysIpv4').textContent='192.168.0.106';c.document.execCommand=()=>{throw Error('unsupported');};c.copySystemAddress('sysIpv4');
  assert.equal(inputs.length,0);assert.equal(field('message').textContent,'复制失败，请长按地址复制');
  c.window.isSecureContext=true;c.navigator.clipboard={writeText(value){copied=value;return {then(resolve){resolve();}};}};
  let prevented=false;c.systemAddressKey({key:'Enter',preventDefault(){prevented=true;}},'sysIpv4');
  assert.equal(prevented,true);assert.equal(copied,'192.168.0.106');assert.equal(field('message').textContent,'IP地址已复制');
});
test('splash URL drafts stay visible across polling and failed saves, and commit only on Use', () => {
 const b=browser('personalization'),c=b.context,get=id=>c.document.getElementById(id);
 const settings={personalBackground:{mode:'off',imageUrl:'',displaySeconds:1,customAvailable:false}};
 c.state={settings};c.renderPageState();
 const posts=()=>b.requests.filter(r=>r.method==='POST');
 get('personalBackground').value='url';c.saveBackground();
 assert.equal(posts().length,0,'Opening the URL editor must not activate an empty server URL');
 assert.equal(get('backgroundUrlTools').hidden,false);
 get('backgroundImageUrl').value='https://example.com/splash.jpg?token=a%2Bb&x=1';get('backgroundImageUrl').oninput();
 c.renderPageState();assert.equal(get('personalBackground').value,'url');assert.equal(get('backgroundUrlTools').hidden,false);
 c.saveBackgroundUrl();let save=posts().at(-1);
 assert.deepEqual(JSON.parse(save.body),{backgroundImageUrl:'https://example.com/splash.jpg?token=a%2Bb&x=1'});
 save.respond({message:'temporary failure'},500);
 assert.equal(get('personalBackground').value,'url');assert.equal(get('backgroundUrlTools').hidden,false);assert.ok(get('backgroundImageUrl').value.includes('token=a%2Bb'));
 c.saveBackgroundUrl();save=posts().at(-1);save.respond({ok:true});
 assert.equal(c.backgroundModeDraft,null);assert.equal(settings.personalBackground.mode,'url');assert.equal(get('backgroundImageUrl')._dirty,false);
 assert.equal(settings.personalBackground.imageUrl,'https://example.com/splash.jpg?token=a%2Bb&x=1');
});
test('splash local-image first use is editable without a premature device save', () => {
 const b=browser('personalization'),c=b.context,get=id=>c.document.getElementById(id);
 c.state={settings:{personalBackground:{mode:'off',customAvailable:false}}};c.renderPageState();
 get('personalBackground').value='custom';c.saveBackground();
 assert.equal(b.requests.filter(r=>r.method==='POST').length,0);assert.equal(get('backgroundUploadTools').hidden,false);
 c.renderPageState();assert.equal(get('personalBackground').value,'custom');assert.equal(get('backgroundUploadTrigger').disabled,false);
 get('personalBackground').value='off';c.saveBackground();assert.equal(JSON.parse(b.requests.at(-1).body).personalBackground,'off');
});
test('Bing daily updates can be enabled and disabled without changing the current wallpaper', () => {
 const b=browser('personalization'),c=b.context,get=id=>c.document.getElementById(id);
 c.state={settings:{personalBackground:{mode:'bing',bingChoice:'',bingAutoUpdate:true,imageAvailable:true,displaySeconds:1}}};c.renderPageState();
 assert.equal(get('bingAutoButton').checked,true);get('bingAutoButton').checked=false;c.toggleBingAutoUpdate();
 let request=b.requests.filter(r=>r.method==='POST').at(-1);assert.deepEqual(JSON.parse(request.body),{bingAutoUpdate:false});request.respond({ok:true});
 assert.equal(c.state.settings.personalBackground.mode,'bing');assert.equal(c.state.settings.personalBackground.bingAutoUpdate,false);assert.equal(get('bingAutoButton').checked,false);
 get('bingAutoButton').checked=true;c.toggleBingAutoUpdate();request=b.requests.filter(r=>r.method==='POST').at(-1);assert.deepEqual(JSON.parse(request.body),{bingAutoUpdate:true});request.respond({message:'offline'},500);
 assert.equal(get('bingAutoButton').checked,false,'Failed save restores the persisted toggle');
});
test('older Bing API freezes the current cached image and restores the toggle on failure', () => {
 const b=browser('personalization'),c=b.context,get=id=>c.document.getElementById(id);
 c.state={settings:{personalBackground:{mode:'bing',bingChoice:'',imageAvailable:true,revision:7}}};c.renderPageState();
 get('bingAutoButton').checked=false;c.toggleBingAutoUpdate();
 let read=b.requests.at(-1);assert.equal(read.method,'GET');assert.equal(read.url,'/api/background/image?v=7');read.onerror();assert.equal(get('bingAutoButton').checked,true);
 get('bingAutoButton').checked=false;c.toggleBingAutoUpdate();read=b.requests.at(-1);read.status=200;read.response=new Uint8Array([1,2,3]).buffer;read.onload();
 const upload=b.requests.at(-1);assert.equal(upload.url,'/api/background/upload');assert.equal(upload.body,read.response);upload.respond({ok:true});
 assert.equal(c.state.settings.personalBackground.mode,'custom');assert.equal(c.state.settings.personalBackground.customAvailable,true);assert.equal(c.state.settings.personalBackground.bingAutoUpdate,false);
});
test('group password toggle remains on when older personalization snapshots omit its value', () => {
 const b=browser('personalization'),c=b.context,get=id=>c.document.getElementById(id);
 c.state={settings:{personalBackground:{mode:'off'}}};c.renderPageState();
 const legacyRead=b.requests.find(r=>r.url==='/api/state?view=advanced');assert.ok(legacyRead);
 get('groupPasswordRequired').checked=true;c.saveGroupPasswordRequired();const save=b.requests.filter(r=>r.method==='POST').at(-1);
 assert.deepEqual(JSON.parse(save.body),{groupPasswordRequired:true});save.respond({ok:true});
 b.requests.filter(r=>r.url==='/api/state?view=advanced').at(-1).respond({settings:{groupPasswordRequired:true}});
 legacyRead.respond({settings:{groupPasswordRequired:false}});
 assert.equal(get('groupPasswordRequired').checked,true,'A stale fallback response must not undo a completed save');
 c.state={settings:{personalBackground:{mode:'off'}}};c.renderPageState();assert.equal(get('groupPasswordRequired').checked,true);
 get('groupPasswordRequired').checked=false;c.saveGroupPasswordRequired();b.requests.filter(r=>r.method==='POST').at(-1).respond({message:'offline'},500);
 assert.equal(get('groupPasswordRequired').checked,true);assert.equal(get('groupPasswordRequired').disabled,false);
});
test('personalization reads the legacy group setting and accepts local images larger than 8 MB', () => {
 const b=browser('personalization'),c=b.context,get=id=>c.document.getElementById(id);
 c.state={settings:{personalBackground:{mode:'off'}}};c.renderPageState();
 b.requests.find(r=>r.url==='/api/state?view=advanced').respond({settings:{groupPasswordRequired:true}});
 assert.equal(get('groupPasswordRequired').checked,true);
 const file={name:'large.jpg',size:24*1024*1024};get('backgroundFile').files=[file];c.uploadBackground();
 const upload=b.requests.at(-1);assert.equal(upload.method,'POST');assert.equal(upload.url,'/api/background/upload');assert.equal(upload.body,file);assert.equal(upload.timeout,0);
});


test('group password save verifies receiver state and rejects unsupported or ignored settings', () => {
 for(const response of [{settings:{groupPasswordRequired:false}},{settings:{}}]){
  const b=browser('personalization'),c=b.context,input=c.document.getElementById('groupPasswordRequired');
  c.state={settings:{groupPasswordRequired:false,personalBackground:{mode:'off'}}};c.renderPageState();
  input.checked=true;c.saveGroupPasswordRequired();
  b.requests.filter(r=>r.method==='POST').at(-1).respond({ok:true});
  assert.equal(input.disabled,true,'Keep save pending until receiver confirms');
  b.requests.filter(r=>r.url==='/api/state?view=advanced').at(-1).respond(response);
  assert.equal(input.checked,false);assert.equal(input.disabled,false);
  assert.match(c.document.getElementById('message').textContent,/未生效|更新 APK/);
 }
});

test('a state poll started before group password save cannot overwrite its confirmed value', () => {
 const b=browser('personalization'),c=b.context,input=c.document.getElementById('groupPasswordRequired');
 c.state={settings:{groupPasswordRequired:false,personalBackground:{mode:'off'}}};c.renderPageState();
 c.refresh();const stale=b.requests.filter(r=>r.url==='/api/state?view=personalization').at(-1);
 input.checked=true;c.saveGroupPasswordRequired();b.requests.filter(r=>r.method==='POST').at(-1).respond({ok:true});
 b.requests.filter(r=>r.url==='/api/state?view=advanced').at(-1).respond({settings:{groupPasswordRequired:true}});
 stale.respond({settings:{groupPasswordRequired:false,personalBackground:{mode:'off'}}});
 assert.equal(input.checked,true,'Pre-save state must not reverse verified toggle');
 b.requests.filter(r=>r.url==='/api/state?view=personalization').at(-1).respond({settings:{groupPasswordRequired:true,personalBackground:{mode:'off'}}});
 assert.equal(input.checked,true);
});


test('fullscreen startup loads, saves independently, resists stale polls and rolls back on failure', () => {
 const b=browser('advanced'),c=b.context,get=id=>c.document.getElementById(id);
 c.state={settings:{fullscreenStartup:false}};c.renderPageState();assert.equal(get('fullscreenStartup').checked,false);assert.equal(get('fullscreenStartup').disabled,false);
 c.refresh();const stale=b.requests.at(-1);
 get('fullscreenStartup').checked=true;c.saveFullscreenStartup();const save=b.requests.at(-1);
 assert.deepEqual(JSON.parse(save.body),{fullscreenStartup:true});assert.equal(get('fullscreenStartup').disabled,true);
 c.renderPageState();assert.equal(get('fullscreenStartup').checked,true,'Polling cannot undo pending edit');
 save.respond({ok:true});stale.respond({settings:{fullscreenStartup:false}});assert.equal(get('fullscreenStartup').checked,true);
 b.requests.at(-1).respond({settings:{fullscreenStartup:true}});assert.equal(get('fullscreenStartup').checked,true);
 get('fullscreenStartup').checked=false;c.saveFullscreenStartup();b.requests.at(-1).respond({message:'failed'},500);
 assert.equal(get('fullscreenStartup').checked,true);assert.equal(get('fullscreenStartup').disabled,false);
});

test('old APIs cannot silently accept an unsupported fullscreen setting', () => {
 const b=browser('advanced'),c=b.context;
 c.state={settings:{}};c.renderPageState();assert.equal(c.document.getElementById('fullscreenStartup').disabled,true);
 const count=b.requests.length;c.saveFullscreenStartup();assert.equal(b.requests.length,count);
});

test('channel memory saves source and decode without changing domain browser overrides',()=>{
 const b=browser('personalization'),c=b.context,get=id=>c.document.getElementById(id);
 b.requests[0].respond({settings:{rememberChannelSource:true,rememberChannelDecode:true,rememberChannelUserAgent:true,liteBrowserSupported:false,personalBackground:{mode:'off'}}});
 assert.equal(get('webEngineMemoryRow').hidden,true);assert.equal(get('rememberChannelWebEngine').disabled,true);
 get('rememberChannelUserAgent').checked=false;c.saveMemory();
 const save=b.requests.at(-1),patch=JSON.parse(save.body);
 assert.equal('rememberChannelUserAgent' in patch,false);assert.equal('rememberChannelWebEngine' in patch,false);
 assert.equal(patch.rememberChannelSource,true);assert.equal(patch.rememberChannelDecode,true);
 save.respond({ok:true});assert.equal(get('rememberChannelUserAgent').checked,true);
 c.state.settings.liteBrowserSupported=true;c.state.settings.rememberChannelWebEngine=true;c.renderPageState();
 assert.equal(get('webEngineMemoryRow').hidden,false);assert.equal(get('rememberChannelWebEngine').disabled,false);
 get('rememberChannelWebEngine').checked=false;c.saveMemory();
 assert.equal('rememberChannelWebEngine' in JSON.parse(b.requests.at(-1).body),false);
 b.requests.at(-1).respond({message:'failed'},500);
 assert.equal(get('rememberChannelWebEngine').checked,true);assert.equal(get('rememberChannelWebEngine').disabled,false);
});


test('neighbor probe switch preserves pending choice and full scan submits once',()=>{
 const b=browser('channels'),c=b.context,get=id=>c.document.getElementById(id);
 b.requests[0].respond({settings:{neighborProbeEnabled:true,channelProbe:{good:2,bad:1,unknown:3}}});
 assert.equal(get('neighborProbeEnabled').checked,true);
 assert.equal(get('channelProbeStatus').textContent,'失效 1 项');
 get('neighborProbeEnabled').checked=false;c.saveNeighborProbe();const save=b.requests.at(-1);
 assert.equal(JSON.parse(save.body).neighborProbeEnabled,false);assert.equal(get('neighborProbeEnabled').disabled,true);
 c.renderPageState();assert.equal(get('neighborProbeEnabled').checked,false);
 save.respond({ok:false,message:'offline'},500);assert.equal(get('neighborProbeEnabled').checked,true);assert.equal(get('neighborProbeEnabled').disabled,false);
 const count=b.requests.length;c.probeAllChannels();const scan=b.requests.at(-1);c.probeAllChannels();
 assert.equal(b.requests.length,count+1);assert.equal(JSON.parse(scan.body).probeAllChannels,true);
 scan.respond({ok:true});c.state.settings.channelProbe={running:true,completed:4,total:8};c.renderPageState();
 assert.equal(get('probeAllButton').disabled,true);assert.match(get('channelProbeStatus').textContent,/4 \/ 8/);
 const active=b.requests.length;c.probeAllChannels();assert.equal(b.requests.length,active);
 c.state.settings.channelProbe={running:false,good:7,bad:1};c.renderPageState();assert.equal(get('probeAllButton').disabled,false);
 const html=fs.readFileSync(path.join(root,'pages/channels.html'),'utf8');assert.ok(html.indexOf('id="probeAllButton"')<html.indexOf('id="neighborProbeEnabled"'));
});
