const assert = require('assert');
const fs = require('fs');
const vm = require('vm');
const code = fs.readFileSync('app/src/main/assets/control/js/theme.js', 'utf8');
function page(saved, systemDark, legacy, native) {
  const attributes = {}, events = {}, storage = { value: saved };
  const query = { matches: systemDark, addListener(fn) { this.changed = fn; } };
  const select = { value: '' };
  const window = { addEventListener(name, fn) { events[name] = fn; } };
  if (native) window.NtvDevice = native;
  if (!legacy) window.matchMedia = () => query;
  vm.runInNewContext(code, { window,
    localStorage: { getItem() { return storage.value; }, setItem(key,value) { storage.value=value; } },
    document: { documentElement: { style: {}, setAttribute(k,v) { attributes[k]=v; } },
      getElementById() { return select; }, addEventListener(name,fn) { events[name]=fn; } } });
  return { window, attributes, query, storage, select, events };
}
const old = page(null, false, true);
old.window.setPageTheme('dark');
assert.equal(old.attributes['data-theme'], 'dark');
assert.equal(page(old.storage.value, false, true).attributes['data-theme'], 'dark');
const modern = page('system', true, false);
assert.equal(modern.attributes['data-theme'], 'dark');
modern.query.matches=false; modern.query.changed();
assert.equal(modern.attributes['data-theme'], 'light');
modern.window.setPageTheme('dark'); modern.query.changed();
assert.equal(modern.attributes['data-theme'], 'dark');
modern.events.storage({key:'ntv-theme',newValue:'light'});
assert.equal(modern.select.value, 'light');
console.log('PASS manual legacy theme, persistence, system changes and cross-page synchronization');
let nativeDark = true;
const embedded = page('system', false, false, { isSystemDark() { return nativeDark; } });
assert.equal(embedded.attributes['data-theme'], 'dark');
nativeDark = false; embedded.window.refreshSystemTheme();
assert.equal(embedded.attributes['data-theme'], 'light');
embedded.window.setPageTheme('dark'); embedded.window.refreshSystemTheme();
assert.equal(embedded.attributes['data-theme'], 'dark');
embedded.window.setPageTheme('light'); nativeDark = true; embedded.window.refreshSystemTheme();
assert.equal(embedded.attributes['data-theme'], 'light');
embedded.window.setPageTheme('system');
assert.equal(embedded.attributes['data-theme'], 'dark');
assert.equal(page('system', true, false, { isSystemDark() { throw Error('unavailable'); } }).attributes['data-theme'], 'dark');
console.log('PASS native system theme overrides fixed WebView query; manual choice wins; browser fallback retained');
