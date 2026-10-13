// Standard globalThis regression from a main-document CGTN script on API19.
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert');
const source=fs.readFileSync(path.join(__dirname,'../../third_party/web-compat/core-js/legacy.min.js'),'utf8');
for(const mode of ['missing','existing-correct']){
 const realm={setTimeout,clearTimeout};vm.createContext(realm);
 vm.runInContext('WebAssembly=undefined;MediaSource=undefined;'+(mode==='missing'?'delete this.globalThis;':''),realm);
 const before=vm.runInContext('Object.getOwnPropertyDescriptor(this,"globalThis")',realm);
 vm.runInContext(source,realm);
 const run=code=>vm.runInContext(code,realm);
 assert.strictEqual(run('globalThis === this'),true,mode);
 assert.strictEqual(run('(function(){"use strict";return globalThis;})() === this'),true,mode);
 assert.strictEqual(run('Object.getOwnPropertyDescriptor(this,"globalThis").enumerable'),false,mode);
 assert.strictEqual(run('Object.getOwnPropertyDescriptor(this,"globalThis").writable'),true,mode);
 assert.strictEqual(run('Object.getOwnPropertyDescriptor(this,"globalThis").configurable'),true,mode);
 const value=run('globalThis'),descriptor=run('Object.getOwnPropertyDescriptor(this,"globalThis")');
 vm.runInContext(source,realm);assert.strictEqual(run('globalThis'),value,mode);
 assert.deepStrictEqual(run('Object.getOwnPropertyDescriptor(this,"globalThis")'),descriptor,mode);
 if(before)assert.deepStrictEqual(descriptor,before,mode);
 assert.strictEqual(run('typeof WebAssembly'),'undefined');assert.strictEqual(run('typeof MediaSource'),'undefined');
 if(mode==='missing'){
  assert.strictEqual(run('delete this.globalThis'),true);vm.runInContext(source,realm);
  assert.strictEqual(run('globalThis === this'),true,'reinstall after configurable delete');
  assert.strictEqual(run('globalThis=17;this.globalThis'),17,'writable standard property');
 }
}
console.log('PASS globalThis: missing/correct-existing global, strict function access, nonenumerable writable configurable descriptor, repeated-load identity, configurable delete/reinstall, writable assignment, no media/Wasm fabrication');
