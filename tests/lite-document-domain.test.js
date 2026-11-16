// Origin-keyed document.domain assignments must not abort original bootstrap
// code or change the origins used by cookies, requests and frame boundaries.
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync('browser-core/litebrowser/src/main/assets/browser.js','utf8');
const context={Node:{prototype:{}},documentNodes:new Set(),defineDOMProperties:Object.defineProperties,
  add(){},remove(){},legacyEvent(){},DOMException,
  location:{hostname:'www.example.test',origin:'https://www.example.test',href:'https://www.example.test/live'}};
context.g=context;vm.createContext(context);
const declaration=source.match(/Object\.defineProperty\(g,'originAgentCluster',.*?\);/)[0];
vm.runInContext(declaration,context);
vm.runInContext(source.slice(source.indexOf('function newDocument('),source.indexOf('doc=newDocument(')),context);
vm.runInContext('var doc=newDocument(function(){throw Error("Unexpected DOM traversal");});',context);
assert.equal(context.originAgentCluster,true);
vm.runInContext('var reached=false;doc.domain="example.test";reached=true;',context);
assert.equal(context.reached,true);
assert.equal(vm.runInContext('doc.domain',context),'www.example.test');
assert.equal(context.location.origin,'https://www.example.test');
vm.runInContext('doc.domain="unrelated.invalid";',context);
assert.equal(vm.runInContext('doc.domain',context),'www.example.test');
assert.equal(vm.runInContext('Object.getOwnPropertyDescriptor(g,"originAgentCluster").set',context),undefined);
vm.runInContext('var conversions=0;doc.domain={toString:function(){conversions++;return "example.test";}};',context);
assert.equal(context.conversions,1);
vm.runInContext('var detached=newDocument(function(){throw Error("Unexpected DOM traversal");});',context);
assert.throws(()=>vm.runInContext('detached.domain="example.test";',context),e=>e.name==='SecurityError');
context.location.origin='null';
assert.throws(()=>vm.runInContext('doc.domain="example.test";',context),e=>e.name==='SecurityError');
console.log('PASS original bootstrap continues after origin-keyed domain assignment; origin stays fixed, with opaque/detached restrictions');
