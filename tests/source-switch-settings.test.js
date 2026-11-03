const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const script = fs.readFileSync('app/src/main/assets/control/js/pages/playback.js', 'utf8');
const html = fs.readFileSync('app/src/main/assets/control/pages/playback.html', 'utf8');
const nodes = {}, calls = [], messages = [];
let fail = false, refreshed = false;
const context = vm.createContext({
  state: {settings: {}}, uiScaleEditing:false,
  document: {getElementById(id) { return nodes[id] || (nodes[id] = {style:{},querySelector(){return null;},appendChild(){}}); }},
  renderSiteQualities() {}, syncLegacySelectButtons() {},
  refresh() { refreshed = true; }, toast(message) { messages.push(message); },
  api(path, body, done) { calls.push({path, body}); done(fail ? Error('offline') : null); }
});
vm.runInContext(script.slice(script.indexOf('function saveAutoSwitchSource()'),
  script.indexOf('function saveDebugInfo()')), context);
vm.runInContext(script.slice(script.indexOf('function renderPageState()'),
  script.indexOf('\nfunction renderSiteQualities()')), context);
context.renderPageState();
const select = nodes.autoSwitchSourceSeconds;
assert.equal(select.value, '0', 'Default is off');
context.state.settings = {autoSwitchSource: true};
context.renderPageState();
assert.equal(select.value, '5', 'Old enabled setting remains usable');
for (const seconds of [30,25,20,15,10,5,0]) {
  select.value = String(seconds);
  context.saveAutoSwitchSource();
  assert.equal(calls.at(-1).path, '/api/settings');
  assert.equal(calls.at(-1).body.autoSwitchSourceSeconds, seconds);
  context.renderPageState();
  assert.equal(select.value, String(seconds));
  assert.equal(context.state.settings.autoSwitchSource, seconds > 0);
}
fail = true; select.value = '10'; context.saveAutoSwitchSource();
assert(refreshed, 'Reload saved selection after failure');
assert.equal(context.state.settings.autoSwitchSourceSeconds, 0);
assert.equal(messages.at(-1), 'offline');
const options = html.match(/<select\s+id="autoSwitchSourceSeconds"[\s\S]*?<\/select>/)[0];
assert.deepEqual(Array.from(options.matchAll(/<option value="(\d+)"/g), m => m[1]), ['0','5','10','15','20','25','30']);
console.log('PASS off/5s through 30s selector, legacy state, API payloads, saved selection and failure recovery');
