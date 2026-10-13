const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert');
const source=fs.readFileSync(path.join(__dirname,'../../third_party/web-compat/core-js/legacy.min.js'),'utf8');
for(const mode of ['missing','illegal']){
 const realm={setTimeout,clearTimeout};vm.createContext(realm);
 vm.runInContext(mode==='illegal'?'DOMException=function DOMException(){throw new TypeError("Illegal constructor");};':'DOMException=undefined;',realm);
 vm.runInContext(source,realm);
 const result=vm.runInContext('(function(){var e=new DOMException("blocked","SecurityError"),d=new DOMException();return [e.name,e.message,e.code,e instanceof Error,e instanceof DOMException,String(e),DOMException.SECURITY_ERR,DOMException.prototype.SECURITY_ERR,d.name,d.message,d.code,Object.prototype.toString.call(e)];})()',realm);
 assert.deepStrictEqual(Array.from(result),['SecurityError','blocked',18,true,true,'SecurityError: blocked',18,18,'Error','',0,'[object DOMException]']);
 assert.strictEqual(vm.runInContext('"stack" in new DOMException(1,2)',realm),true);
 const ctor=realm.DOMException;vm.runInContext(source,realm);assert.strictEqual(realm.DOMException,ctor);
 assert.throws(()=>vm.runInContext('DOMException("x")',realm));
}
console.log('PASS missing and nonconstructible DOMException, real name/message/code/constants, Error semantics, site stack-check, constructor enforcement, idempotence');
