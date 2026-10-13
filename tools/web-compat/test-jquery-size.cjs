const fs=require('fs'),vm=require('vm'),assert=require('assert');
const src=fs.readFileSync(__dirname+'/jquery-size-compat.js','utf8');
function jq(version){function J(){}J.fn={jquery:version};return J;}
function setup(lib,ready='loading',marker={}){const de={},we={},c={jQuery:lib,__ntvTvCompatibility:marker,document:{readyState:ready,addEventListener:(n,f,capture)=>{(de[n]||(de[n]=[])).push({f,capture});}},addEventListener:(n,f)=>{(we[n]||(we[n]=[])).push(f)}};c.window=c;vm.createContext(c);vm.runInContext(src,c);return {c,de,we,fireD(n,target){for(const x of de[n]||[])x.f({target});},fireW(n){for(const f of we[n]||[])f();}};}
for(const version of ['3.0.0','3.1.1','3.2.1','3.3.1','3.4.1','3.5.1','3.6.0','3.7.1','3.0.0-rc1']){const j=jq(version),r=setup(j);assert.strictEqual(j.fn.size.call({length:0}),0);assert.strictEqual(j.fn.size.call({length:12}),12);let n=2;const collection={get length(){return n;}};assert.strictEqual(j.fn.size.call(collection),2);n=9;assert.strictEqual(j.fn.size.call(collection),9);const installed=j.fn.size;r.fireD('DOMContentLoaded');r.fireW('load');r.fireD('load',{nodeName:'SCRIPT'});assert.strictEqual(j.fn.size,installed);vm.runInContext(src,r.c);assert.strictEqual(r.de.load.length,1);assert.strictEqual(Object.getOwnPropertyDescriptor(j.fn,'size').enumerable,false);}
for(const version of ['1.12.4','2.2.4','4.0.0','3.fake','3','']){const j=jq(version);setup(j);assert(!('size' in j.fn));}
for(const val of [()=>11,undefined,null,0]){const j=jq('3.7.1');j.fn.size=val;setup(j);assert.strictEqual(j.fn.size,val);}
{const j=jq('3.7.1'),ancestor={size:()=>19};Object.setPrototypeOf(j.fn,ancestor);setup(j);assert(!Object.hasOwn(j.fn,'size'));assert.strictEqual(j.fn.size(),19);}
{const j=jq('3.7.1');Object.freeze(j.fn);setup(j);assert(!('size' in j.fn));}
for(const event of ['DOMContentLoaded','window-load','external-load']){const r=setup();r.c.jQuery=jq('3.7.1');if(event==='window-load')r.fireW('load');else r.fireD(event==='external-load'?'load':event,{nodeName:'script'});assert.strictEqual(r.c.jQuery.fn.size.call({length:7}),7);}
{const r=setup(undefined,'complete');r.c.jQuery=jq('3.5.1');r.fireD('load',{nodeName:'IMG'});assert(!r.c.jQuery.fn.size);r.fireD('load',{nodeName:'SCRIPT'});assert(r.c.jQuery.fn.size);const first=r.c.jQuery;r.c.jQuery=jq('3.7.1');r.fireD('load',{nodeName:'SCRIPT'});assert(r.c.jQuery.fn.size);assert(first.fn.size);assert.strictEqual(r.de.load[0].capture,true);}
{const j=jq('3.7.1'),r=setup(j,'loading',null);assert(!j.fn.size);assert.deepStrictEqual(r.de,{});}
// Document the intentional no-interception boundary: late inline evaluation
// has no load event. No setter, polling, eval hook, or fake completion is added.
{const r=setup(undefined,'complete');r.c.jQuery=jq('3.7.1');assert(!r.c.jQuery.fn.size);}
assert(!/setTimeout|setInterval|MutationObserver|defineProperty\(w/.test(src));
console.log('PASS generic jQuery size: jQuery 3.0–3.7, delayed external loads, document/window events, replacement libraries, count semantics, idempotence, existing/inherited properties, frozen prototypes, excluded versions and inline boundary');

{const j=jq('3.7.1'),r=setup(j,'complete',Object.freeze({}));assert(!j.fn.size);assert.deepStrictEqual(r.de,{});}
console.log('PASS nonextensible compatibility marker: fail closed without listeners or prototype mutation');
