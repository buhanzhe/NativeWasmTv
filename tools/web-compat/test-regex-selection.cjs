const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert');
const deps=path.join(__dirname,'transpile/node_modules');
const Babel=require(path.join(deps,'@babel/standalone')),acorn=require(path.join(deps,'acorn'));
let calls=[];
const context={Babel:{transform(source,options){calls.push({full:!!options.presets});return Babel.transform(source,options);}},acorn,Date,JSON};
vm.createContext(context);vm.runInContext(fs.readFileSync(path.join(__dirname,'transpile-preflight.js'),'utf8'),context);vm.runInContext(fs.readFileSync(path.join(__dirname,'transpile-runtime.js'),'utf8'),context);
function transform(source){calls=[];const result=JSON.parse(context.ntvTransform(source,'https://fixture.invalid/a.js',false));acorn.parse(result.code,{ecmaVersion:5});return result;}
for(const regex of ['/a+/gi','/[a-z\\/]+/','/(?:a|b)\\d/','/\\[.*\\]/']){
 const source='const f=x=>'+regex+'.test(x); window.result=f("a1");';
 const result=transform(source);assert(result.changed);assert(calls.every(c=>!c.full),'ES5 regex should keep selective transforms');
 const a={window:{}},b={window:{}};vm.runInNewContext(source,a);vm.runInNewContext(result.code,b);assert.strictEqual(a.window.result,b.window.result);
}
for(const regex of ['/a.b/s','/\\u{1F600}/u','/(?<word>a)/']){
 const result=transform('const f=x=>'+regex+'.test(x); window.result=f("a\\nb");');
 assert(calls.some(c=>c.full),'Modern regex must use complete transform');
 const original={window:{}},compiled={window:{}};
 vm.runInNewContext('const f=x=>'+regex+'.test(x); window.result=f("a\\nb");',original);
 vm.runInNewContext(result.code,compiled);assert.strictEqual(original.window.result,compiled.window.result);
}
assert.strictEqual(transform('var f=/abc/g;').changed,false);
assert.throws(()=>transform('const broken = /(/;'));
console.log('PASS selective ES5 regex, full modern regex transforms, matching semantics, original ES5 pass-through, malformed input');
// Acorn's ES5 grammar alone is not a Chromium 30 compatibility proof.
const block=transform('"use strict";const flag=true;if(flag){function inside(){return 42;}window.result=inside();}');
assert(!/\{function inside\(/.test(block.code));
const blockContext={window:{}};vm.runInNewContext(block.code,blockContext);assert.strictEqual(blockContext.window.result,42);
console.log('PASS strict block declarations lowered to function expressions');
