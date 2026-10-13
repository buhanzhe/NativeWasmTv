// Pinned core-js regression for API19 Shenzhen's Uint8Array.from dependency.
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert');
const source=fs.readFileSync(path.join(__dirname,'../../third_party/web-compat/core-js/legacy.min.js'),'utf8');
const names=['Int8Array','Uint8Array','Uint8ClampedArray','Int16Array','Uint16Array','Int32Array','Uint32Array','Float32Array','Float64Array'];
for(const symbolMode of ['native','missing']){
 const realm={setTimeout,clearTimeout};vm.createContext(realm);const run=code=>vm.runInContext(code,realm);
 const natives=names.map(name=>run(name));
 run("WebAssembly=undefined;MediaSource=undefined;delete Object.getPrototypeOf(Uint8Array).from;"+(symbolMode==='missing'?'Symbol=undefined;Reflect=undefined;Set=undefined;':''));
 assert.strictEqual(run('typeof Uint8Array.from'),'undefined');
 vm.runInContext(source,realm);
 for(const [i,name] of names.entries()){
  assert.strictEqual(run(name),natives[i],name+' native constructor identity');
  assert.strictEqual(run(`(function(){var a=${name}.from({0:3,1:7,length:2});return a instanceof ${name} && a.length===2 && a[0]===3 && a[1]===7;})()`),true,name+' result');
 }
 const checks=[
  ['integer coercion','Uint8Array.from([257,-1,NaN]).join(",")','1,255,0'],
  ['clamp ties to even','Uint8ClampedArray.from([0.5,1.5,2.5,300,-1,NaN]).join(",")','0,2,2,255,0,0'],
  ['float conversion','Float32Array.from([1.5,-2.25]).join(",")','1.5,-2.25'],
  ['signed conversion','Int8Array.from([255,128]).join(",")','-1,-128'],
  ['sparse arraylike','Uint8Array.from({0:8,2:4,length:3}).join(",")','8,0,4'],
  ['fractional length','Uint8Array.from({0:8,1:4,length:1.9}).join(",")','8'],
  ['negative length','Uint8Array.from({length:-1}).length',0],
  ['string source','Uint8Array.from("123").join(",")','1,2,3'],
  ['thisArg and index','Uint8Array.from([1,2],function(v,i){return v+i+this.offset;},{offset:5}).join(",")','6,8'],
  ['mapping before clamp','Uint8ClampedArray.from([300],function(v){return v/2;})[0]',150],
  ['Set iterable','Uint8Array.from(new Set([4,9])).join(",")','4,9'],
  ['custom iterable outranks length',`(function(){var o={length:8};o[Symbol.iterator]=function(){var i=0;return {next:function(){return i<2?{value:++i,done:false}:{done:true};}};};return Uint8Array.from(o).join(',');})()`,'1,2'],
  ['iterable collected before mapping',`(function(){var log=[],o={};o[Symbol.iterator]=function(){var i=0;return {next:function(){log.push('next');return i<2?{value:++i,done:false}:{done:true};}};};Uint8Array.from(o,function(v){log.push('map');return v;});return log.join(',');})()`,'next,next,next,map,map'],
  ['subclass constructor',`(function(){function Sub(n){var a=new Uint8Array(n);Object.setPrototypeOf(a,Sub.prototype);return a;}Object.setPrototypeOf(Sub,Uint8Array);Sub.prototype=Object.create(Uint8Array.prototype);var a=Sub.from([3,4]);return a instanceof Sub&&a instanceof Uint8Array&&a[1]===4;})()`,true],
  ['method nonenumerable','Object.getOwnPropertyDescriptor(Object.getPrototypeOf(Uint8Array),"from").enumerable',false]
 ];
 for(const [name,code,expected] of checks)assert.strictEqual(run(code),expected,symbolMode+': '+name);
 for(const code of ['Uint8Array.from(null)','Uint8Array.from(undefined)','Uint8Array.from([1],null)','Uint8Array.from.call({},[1])','Uint8Array.from.call(Array,[1])'])assert.throws(()=>run(code),/TypeError/,code);
 assert.throws(()=>run('Uint8Array.from([1],function(){throw Error("mapping sentinel");})'),/mapping sentinel/);
 assert.throws(()=>run('(function(){var o={};o[Symbol.iterator]=function(){return {next:function(){throw Error("iterator sentinel");}};};return Uint8Array.from(o);})()'),/iterator sentinel/);
 // Forced core-js installs may recreate the method where constructors cannot consume polyfilled Symbol.
 vm.runInContext(source,realm);assert.strictEqual(run('Uint8Array.from([3,4]).join(",")'),'3,4','repeat-install semantics');
 assert.strictEqual(run('typeof WebAssembly'),'undefined');assert.strictEqual(run('typeof MediaSource'),'undefined');
}
// A conformant native implementation must not be replaced.
const realm={setTimeout,clearTimeout};vm.createContext(realm);
const nativeFrom=vm.runInContext('Uint8Array.from',realm),nativeConstructor=vm.runInContext('Uint8Array',realm);
vm.runInContext(source,realm);
assert.strictEqual(vm.runInContext('Uint8Array.from',realm),nativeFrom);assert.strictEqual(vm.runInContext('Uint8Array',realm),nativeConstructor);
console.log('PASS TypedArray.from: nine types, native/polyfill Symbol, arraylike/iterables, mapping/thisArg/order, clamping, errors, subclass, native preservation, repeat-install semantics, no media/Wasm fabrication');
