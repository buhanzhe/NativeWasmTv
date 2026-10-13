const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert');
const deps=path.resolve(process.argv[2]||path.join(__dirname,'transpile'));
const resolve=name=>require.resolve(name,{paths:[deps]});
const context={Babel:require(resolve('@babel/standalone')),acorn:require(resolve('acorn')),console,Date,JSON};
vm.createContext(context);vm.runInContext(fs.readFileSync(path.join(__dirname,'transpile-preflight.js'),'utf8'),context);vm.runInContext(fs.readFileSync(path.join(__dirname,'transpile-runtime.js'),'utf8'),context);
function transform(s,m=false){return JSON.parse(context.ntvTransform(s,'https://fixture.invalid/a.js',m));}
assert.strictEqual(transform('var answer=42;').changed,false);
const modern=transform('const add=(x=2)=>x+1; class Value {get(){return add();}} async function go(){const result=await Promise.resolve(new Value().get());window.proof=result===3&&[...new Set([1,1])].length===1;} go();');
assert.strictEqual(modern.module,false);context.window={};vm.runInContext(modern.code,context);
const esm=transform("import {x} from './b.js';export const value=x;import('./c.js');console.log(import.meta.url);",true);
assert(esm.code.includes('System.register'));assert(!esm.code.includes('import.meta'));
transform("import('./c.js').then(x=>window.dynamic=x);",false);
assert.throws(()=>transform('const = ;'));
setTimeout(()=>{assert.strictEqual(context.window.proof,true);console.log('PASS ES5 validation, async/classes/iteration behavior, module output, import.meta and malformed input');},20);
