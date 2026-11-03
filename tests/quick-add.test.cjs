const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../app/src/main/assets/control');
const source = fs.readFileSync(path.join(root, 'js/quick-add.js'), 'utf8');
const elements = new Map();
function element() { return { setAttribute() {}, appendChild() {}, focus() {}, querySelectorAll() { return []; } }; }
const context = { window: {}, document: { body: element(), createElement: element,
  getElementById(id) { if (!elements.has(id)) elements.set(id, element()); return elements.get(id); } } };
vm.runInNewContext(source, context);
const extract = text => Array.from(context.window.quickAddExtractUrls(text));
assert.deepEqual(extract('分享：https://example.com/live.m3u，去看看 https://example.com/live.m3u'), ['https://example.com/live.m3u']);
assert.deepEqual(extract('看这个 (https://example.com/a_(b))。'), ['https://example.com/a_(b)']);
assert.deepEqual(extract('https://example.com/a?token=a%2Bb&x=1#frag\nhttp://192.168.1.9:9966/a.mp3'), ['https://example.com/a?token=a%2Bb&x=1#frag', 'http://192.168.1.9:9966/a.mp3']);
assert.deepEqual(extract('没有地址 javascript:alert(1) file:///data/private'), []);
assert.deepEqual(extract('TVB明珠台,tvbus://8NLd2KqJ344hKdRuGDAYc83JBWr6yihTGB8GhoswrKDXE3KGA6\n另一个 sop://broker.example:80/123'), ['tvbus://8NLd2KqJ344hKdRuGDAYc83JBWr6yihTGB8GhoswrKDXE3KGA6', 'sop://broker.example:80/123']);
assert.match(fs.readFileSync(path.join(root, 'index.html'), 'utf8'), /src="\/js\/quick-add\.js/);
for (const page of fs.readdirSync(path.join(root, 'pages')).filter(n => n.endsWith('.html'))) {
  assert.doesNotMatch(fs.readFileSync(path.join(root, 'pages', page), 'utf8'), /src="\/js\/quick-add\.js/);
}
console.log('PASS quick add: share text, deduplication, punctuation, signed URLs, scheme filtering, home page only');
