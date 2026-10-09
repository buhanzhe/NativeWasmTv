// Run: node tools/web-compat/test.cjs [path/to/acorn]
const fs=require('fs'),vm=require('vm'),assert=require('assert'),path=require('path');
const root=path.resolve(__dirname,'../..'),script=fs.readFileSync(path.join(root,'app/src/main/assets/control/userscripts/kitkat-tv.user.js'),'utf8');
if(process.argv[2])require(path.resolve(process.argv[2])).parse(script,{ecmaVersion:5});
const calls=[];
function realm(host='static.hntv.tv',modern=false){
  function Element(){};function NodeList(){};function Event(){};
  const events={},document={createElement:()=>modern?{noModule:false}:{},createEvent:()=>({initCustomEvent(){}}),addEventListener:(n,f)=>events[n]=f};
  const c={console,setTimeout,clearTimeout,Element,NodeList,Event,document,location:{hostname:host,pathname:'/',search:''},performance:{now:()=>1},addEventListener:(n,f)=>events[n]=f,
    XMLHttpRequest:function(){this.open=(method,url)=>calls.push([method,url]);this.setRequestHeader=()=>{};this.getAllResponseHeaders=()=>'';this.send=()=>{this.status=200;this.responseText='{"ok":true}';setTimeout(()=>this.onload(),0);};}};
  c.window=c;c.self=c;vm.createContext(c);
  vm.runInContext("Promise=undefined;URL=undefined;URLSearchParams=undefined;fetch=undefined;Map=undefined;Set=undefined;WeakMap=undefined;WeakSet=undefined;Symbol=undefined;Reflect=undefined;delete Object.assign;delete Object.entries;delete Object.values;delete Array.from;delete Array.prototype.includes;delete Array.prototype.find;delete String.prototype.includes;delete String.prototype.startsWith;delete String.prototype.endsWith;delete String.prototype.repeat;",c);
  vm.runInContext(script,c);return c;
}
(async()=>{
  const c=realm();assert(c.__ntvTvCompatibility);assert.equal(typeof c.fetch,'function');assert.equal(typeof c.URLSearchParams,'function');
  assert.equal(typeof c.NodeList.prototype.forEach,'function');
  const evaluate=code=>vm.runInContext(code,c);
  assert.equal(evaluate('Array.from(new Set([1,2,1])).join(",")'),'1,2');
  assert.equal(evaluate('new Map([[NaN,3]]).get(NaN)'),3);
  assert.equal(evaluate('(function(){var k=Object.freeze({}),m=new WeakMap();m.set(k,7);return m.get(k);})()'),7);
  assert.equal(evaluate('(function(){var s=Symbol("key"),o={};o[s]=9;return Object.getOwnPropertySymbols(o)[0]===s&&Object.keys(o).length===0&&Reflect.ownKeys(o).length===1;})()'),true);
  assert.equal(evaluate('(function(){var x={},s=Symbol("copy");x[s]=4;return Object.assign({},x)[s];})()'),4);
  assert.equal(evaluate('new URL("../live?a=1", "https://example.org/a/b").href'),'https://example.org/live?a=1');
  assert.equal(evaluate("new URLSearchParams('a=one&a=two&s=%2B+x').getAll('a').join(',')"),'one,two');
  assert.equal(evaluate("new URLSearchParams('s=%2B+x').get('s')"),'+ x');
  assert.equal(evaluate("Array.from({0:'x',1:'y',length:2},function(v,i){return v+i;}).join(',')"),'x0,y1');
  assert(evaluate('[NaN].includes(NaN)'));assert(!evaluate('[1].includes(1,Infinity)'));assert.equal(evaluate("'ab'.repeat(5)"),'ababababab');
  assert.throws(()=>evaluate("'x'.repeat(-1)"));assert.throws(()=>evaluate("'x'.startsWith(/x/)"));
  const result=await c.fetch('/fixture').then(r=>r.json());assert.equal(result.ok,true);assert.deepEqual(calls,[['GET','/fixture']]);
  assert.equal(await evaluate('Promise.resolve(6).finally(function(){}).then(function(n){return n;})'),6);
  const settled=await evaluate('Promise.allSettled([Promise.resolve(1),Promise.reject("bad")])');
  assert.equal(settled[0].value,1);assert.equal(settled[1].reason,'bad');
  const marker=c.__ntvTvCompatibility,fetch=c.fetch,params=c.URLSearchParams;vm.runInContext(script,c);
  assert.equal(c.__ntvTvCompatibility,marker);assert.equal(c.fetch,fetch);assert.equal(c.URLSearchParams,params);
  assert.equal(c.__ntvMd5('abc'),'900150983cd24fb0d6963f7d28e17f72');
  assert.equal(realm('example.org').__ntvTvCompatibility,undefined);assert.equal(realm('static.hntv.tv',true).__ntvTvCompatibility,undefined);
  assert.equal(c.MediaSource,undefined);assert.equal(c.WebAssembly,undefined);
  console.log('PASS ES5 bundle, missing APIs, Set/Map/WeakMap/Symbol/Reflect, iterable Array.from, Promise, fetch, URL, errors, idempotence, site scope and native media capability preservation');
})().catch(e=>{console.error(e);process.exitCode=1;});
