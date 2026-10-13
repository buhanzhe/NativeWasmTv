// Regression: API19 TCPlayer calls the ES2017 standard descriptor operation.
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert');
const source=fs.readFileSync(path.join(__dirname,'../../third_party/web-compat/core-js/legacy.min.js'),'utf8');
for(const symbolMode of ['native','missing']){
 const realm={setTimeout,clearTimeout};vm.createContext(realm);
 vm.runInContext('WebAssembly=undefined;MediaSource=undefined;delete Object.getOwnPropertyDescriptors;'+(symbolMode==='missing'?'Symbol=undefined;Reflect=undefined;':''),realm);
 vm.runInContext(source,realm);
 const run=code=>vm.runInContext(code,realm);
 assert.strictEqual(run(`(function(){
  var hits=0,s=Symbol('own'),o=Object.create({inherited:3}),get=function(){hits++;return 4;},set=function(v){hits+=v;};
  Object.defineProperty(o,'hidden',{value:7,writable:false,enumerable:false,configurable:true});
  Object.defineProperty(o,'accessor',{get:get,set:set,enumerable:true,configurable:false});
  Object.defineProperty(o,'__proto__',{value:9,enumerable:true,writable:true,configurable:true});
  o[s]=8;var d=Object.getOwnPropertyDescriptors(o);
  return hits===0 && !Object.prototype.hasOwnProperty.call(d,'inherited') &&
   d.hidden.value===7 && d.hidden.writable===false && d.hidden.enumerable===false && d.hidden.configurable===true &&
   d.accessor.get===get && d.accessor.set===set && d.accessor.enumerable===true && d.accessor.configurable===false &&
   !('value' in d.accessor) && Object.getPrototypeOf(d)===Object.prototype &&
   Object.prototype.hasOwnProperty.call(d,'__proto__') && d.__proto__.value===9 &&
   d[s].value===8 && Object.getOwnPropertySymbols(d)[0]===s;
 })()`),true,symbolMode);
 assert.strictEqual(run("Object.getOwnPropertyDescriptors('ab')[1].value"),'b');
 assert.strictEqual(run('Object.keys(Object.getOwnPropertyDescriptors(7)).length'),0);
 assert.throws(()=>run('Object.getOwnPropertyDescriptors(null)'),/TypeError/);
 assert.throws(()=>run('Object.getOwnPropertyDescriptors(undefined)'),/TypeError/);
 assert.strictEqual(run("Object.getOwnPropertyDescriptor(Object,'getOwnPropertyDescriptors').enumerable"),false);
 const fn=run('Object.getOwnPropertyDescriptors');vm.runInContext(source,realm);assert.strictEqual(run('Object.getOwnPropertyDescriptors'),fn);
 assert.strictEqual(run('typeof WebAssembly'), 'undefined');assert.strictEqual(run('typeof MediaSource'),'undefined');
}
const realm={setTimeout,clearTimeout};vm.createContext(realm);const native=vm.runInContext('Object.getOwnPropertyDescriptors',realm);vm.runInContext(source,realm);assert.strictEqual(vm.runInContext('Object.getOwnPropertyDescriptors',realm),native);
console.log('PASS descriptors: own keys, symbols/native+polyfill, nonenumerables, getter nonexecution, flags, __proto__, primitive coercion, null errors, idempotence, native preservation, no media/Wasm fabrication');
