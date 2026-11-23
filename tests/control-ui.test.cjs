// Run: node tests/control-ui.test.cjs. Tests the actual shared UI bundle without a browser dependency.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync('app/src/main/assets/control/js/ui.js', 'utf8');
const listeners = {}, timers = [], overlays = [];
let document;
function node(tag) {
  const el = {tagName: tag.toUpperCase(), className: '', style: {}, attributes: {}, children: [], textContent: '', disabled: false,
    appendChild(child) { this.children.push(child); child.parentNode = this; return child; },
    removeChild(child) { this.children.splice(this.children.indexOf(child), 1); child.parentNode = null; },
    setAttribute(k,v) { this.attributes[k] = String(v); },
    getAttribute(k) { return this.attributes[k] || null; },
    focus() { document.activeElement = this; },
    addEventListener() {},
    getBoundingClientRect() { return {left:180, right:300, top:100, bottom:144}; },
    dispatchEvent(event) { if (event.type === 'change' && this.onchange) this.onchange(event); },
    querySelectorAll(selector) { const result=[]; function visit(parent) { for(const child of parent.children||[]) { if(child.tagName==='BUTTON' && (!selector.includes(':disabled') || !child.disabled)) result.push(child); visit(child); } } visit(this); return result; }
  };
  Object.defineProperty(el, 'innerHTML', {set() { el.children = []; }});
  Object.defineProperty(el, 'firstChild', {get() { return el.children[0]; }});
  Object.defineProperty(el, 'offsetHeight', {get() { return Math.min(parseInt(el.style.maxHeight)||1000, el.children.length*44+14); }});
  return el;
}
document = {activeElement: null, createElement: node, createTextNode(text) {return {textContent:text};},
  querySelector() {return null;}, addEventListener(name, fn) {(listeners[name]||(listeners[name]=[])).push(fn);},
  createEvent() {return {initEvent(type) {this.type=type;}};},
  documentElement: {clientWidth:320, clientHeight:640, contains(target) { function contains(el) {return el===target || (el.children||[]).some(contains);} return contains(document.body);}}
};
document.body=node('body');
const context={document, innerWidth:320, innerHeight:640,
  setTimeout(fn) {timers.push(fn);}, addEventListener() {},
  NtvNavigation: {overlayOpen(name, close) {overlays.push({name, close});}, overlayClosed(name) {const i=overlays.findIndex(x=>x.name===name);if(i>=0)overlays.splice(i,1);}}
};
context.window=context; vm.createContext(context);vm.runInContext(source, context);
function flush() {while(timers.length)timers.shift()();}
function layer(cls) {return document.body.children.find(x=>x.className===cls);}
function key(value) {for(const fn of listeners.keydown||[])fn({key:value, preventDefault(){}, stopPropagation(){}});}
function select() {
  const el=node('select');el.options=[{value:'a',textContent:'甲'},{value:'b',textContent:'乙'},{value:'c',textContent:'禁用',disabled:true}];el.options.forEach(option=>option.parentNode=el);el.selectedIndex=0;
  Object.defineProperty(el,'value',{get(){return this.options[this.selectedIndex].value;}});document.body.appendChild(el);el.focus();return el;
}
const el=select();let changes=0;el.onchange=()=>changes++;
context.NtvMicroSelect.open(el);
let menu=layer('micro-select-layer').children[0];
assert.equal(menu.getAttribute('role'),'listbox');assert.equal(menu.children[0].getAttribute('aria-selected'),'true');assert.equal(menu.children[2].disabled,true);
assert.ok(parseFloat(menu.style.left)>=12);assert.ok(parseFloat(menu.style.left)+parseFloat(menu.style.width)<=308);
menu.children[1].onclick();assert.equal(el.value,'b');assert.equal(changes,1);assert.equal(el.getAttribute('aria-expanded'),'false');flush();assert.equal(layer('micro-select-layer'),undefined);
context.NtvMicroSelect.open(el);menu=layer('micro-select-layer').children[0];menu.children[1].onclick();flush();assert.equal(changes,1,'Selecting the current value must not restart playback');
context.NtvMicroSelect.open(el);key('Escape');flush();assert.equal(layer('micro-select-layer'),undefined);assert.equal(document.activeElement,el);
context.NtvMicroSelect.open(el);overlays.at(-1).close();flush();assert.equal(layer('micro-select-layer'),undefined,'Browser back closes the overlay');assert.equal(changes,1);
el.disabled=true;context.NtvMicroSelect.open(el);assert.equal(layer('micro-select-layer'),undefined);el.disabled=false;
let results=[];
context.NtvDialogs.confirm('取消不提交',ok=>results.push(ok));let card=layer('micro-confirm-layer').children[0];card.querySelectorAll('button')[0].onclick();flush();assert.deepEqual(results,[false]);
context.NtvDialogs.confirm('仅确认后执行',ok=>results.push(ok));card=layer('micro-confirm-layer').children[0];card.querySelectorAll('button')[1].onclick();flush();assert.deepEqual(results,[false,true]);
context.NtvDialogs.confirm('返回取消',ok=>results.push(ok));overlays.at(-1).close();flush();assert.deepEqual(results,[false,true,false]);assert.equal(overlays.length,0);
console.log('PASS shared UI: selected and disabled options, single change delivery, viewport bounds, Escape/back, focus restoration and async confirmation');

const root='app/src/main/assets/control';
for(const name of ['index.html',...fs.readdirSync(root+'/pages').filter(x=>x.endsWith('.html')).map(x=>'pages/'+x)]) {
 const html=fs.readFileSync(root+'/'+name,'utf8');
 assert.doesNotMatch(html,/device-api|mock\.js|192\.168\.0\.106/,'Production pages must use their own origin');
 assert.equal((html.match(/src="\/js\/ui\.js/g)||[]).length,1,'Load the shared UI once');
}
assert.doesNotMatch(fs.readFileSync(root+'/js/pages/playback.js','utf8'),/legacySelectPicker|setupLegacyDateTimeSelects/);
console.log('PASS production pages: same-origin APIs, one shared UI bundle and no obsolete picker');

const pluginNodes = new Map(), pluginRequests = [], pluginMessages = [];
const catalogEntry = {id: 'python', name: 'Python', kind: 'python', action: 'install', current: false};
let savedCatalog = false;
context.document.getElementById = id => {
  if (!pluginNodes.has(id)) pluginNodes.set(id, node('div'));
  return pluginNodes.get(id);
};
context.rememberManagementNavigation = () => {};
context.setConnectionStatus = () => {};
context.toast = (message, error) => pluginMessages.push({message, error});
context.api = (path, _body, done) => {
  if (path.includes('action=catalog')) done(null, {release: 'v1.0.0', cached: savedCatalog, plugins: [catalogEntry]});
  else done(null, {tvbus: {}, python: {}, bl: {}, tvbox: {}});
};
context.XMLHttpRequest = function () {
  this.open = (_method, path) => { this.path = path; };
  this.setRequestHeader = () => {};
  this.send = () => { pluginRequests.push(this); };
};
vm.runInContext(fs.readFileSync(root+'/js/pages/plugins.js','utf8'), context);
const installButton = pluginNodes.get('pluginCatalog').children[0].children[1];
installButton.onclick();
layer('micro-confirm-layer').children[0].querySelectorAll('button')[1].onclick();
flush();
assert.equal(pluginRequests.length, 1);
assert.equal(installButton.textContent, '安装中…');
assert.equal(installButton.disabled, true);
assert.equal(pluginNodes.get('refreshPluginCatalog').disabled, true);
pluginRequests[0].ontimeout();
assert.equal(installButton.textContent, '安装');
assert.equal(installButton.disabled, false);
assert.equal(pluginNodes.get('refreshPluginCatalog').disabled, false);
assert.deepEqual(pluginMessages.at(-1), {message: '插件操作超时', error: true});

installButton.onclick();
layer('micro-confirm-layer').children[0].querySelectorAll('button')[1].onclick();
flush();
assert.equal(pluginRequests.length, 2);
assert.match(pluginRequests[1].path, /action=install-release&type=python&name=v1\.0\.0$/);
catalogEntry.current = true;
pluginRequests[1].status = 200;
pluginRequests[1].responseText = '{"ok":true}';
pluginRequests[1].onload();
assert.equal(pluginNodes.get('pluginCatalog').children[0].children[1].textContent, '已安装');
assert.equal(pluginNodes.get('refreshPluginCatalog').disabled, false);
savedCatalog = true;
context.refreshCatalog(true);
assert.equal(pluginNodes.get('pluginCatalogStatus').textContent, '已保存的发布版本 v1.0.0');
console.log('PASS plugin install shows pending, releases controls after timeout and refreshes the installed status');

assert.equal(pluginNodes.get('openFiles').disabled, true, 'Older hosts without file-browser status remain usable');
context.api = (_path, _body, done) => done(null, {tvbus: {}, python: {}, bl: {}, tvbox: {}, 'file-browser': {installed: true, version: '3'}});
context.refreshPlugins();
assert.equal(pluginNodes.get('openFiles').disabled, false);
assert.equal(pluginNodes.get('file-browserStatus').textContent, '已安装 · 3');
context.openPlugin('file-browser');
assert.match(pluginRequests.at(-1).path, /action=open&type=file-browser&name=$/);
pluginRequests.at(-1).status = 200;
pluginRequests.at(-1).responseText = '{"ok":true,"url":"ntv-plugin://local/file-browser.jar"}';
pluginRequests.at(-1).onload();
assert.equal(pluginNodes.get('pluginChannelUrl').value, 'ntv-plugin://local/file-browser.jar');
assert.equal(pluginNodes.get('pluginAddress').hidden, false);
console.log('PASS file browser status and direct open, with compatibility for older hosts');
