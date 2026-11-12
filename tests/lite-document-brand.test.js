// Use the shipping document and a real Vue observer: host objects must not be
// traversed as arbitrary application data or acquire reactive getters.
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync('browser-core/litebrowser/src/main/assets/browser.js','utf8');
const context={Node:{prototype:{}},documentNodes:new Set(),defineDOMProperties:Object.defineProperties,add(){},remove(){},legacyEvent(){}};context.g=context;
vm.createContext(context);vm.runInContext(source.slice(source.indexOf('function newDocument('),source.indexOf('doc=newDocument(')),context);
vm.runInContext('var doc=newDocument(function(){throw Error("Unexpected DOM traversal");});',context);
vm.runInContext(fs.readFileSync('browser-core/litebrowser/src/androidTest/assets/vue-2.6.14.min.js','utf8'),context);
vm.runInContext('var observed=Vue.observable({platform:doc,ordinary:{value:1}});',context);
assert.equal(vm.runInContext('Object.prototype.toString.call(doc)',context),'[object HTMLDocument]');
assert.equal(vm.runInContext('!!observed.__ob__ && !!observed.ordinary.__ob__',context),true);
assert.equal(vm.runInContext('Object.prototype.hasOwnProperty.call(doc,"__ob__")',context),false);
vm.runInContext('doc.contentType="application/xml";',context);
assert.equal(vm.runInContext('Object.prototype.toString.call(doc)',context),'[object XMLDocument]');
console.log('PASS real Vue2 observes application state without traversing/rewriting the shipping HTML/XML document');
