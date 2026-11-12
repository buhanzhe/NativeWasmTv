// Execute production DOM reads with a counted native boundary and mutations.
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const source=fs.readFileSync('browser-core/litebrowser/src/main/assets/browser.js','utf8');
let calls=0,revision=1;
const context={console,Map,treeVersion:0,structureVersion:0,mutationObservers:[],nodes:{},doc:{querySelectorAll:()=>[]},timers:{},taskCheckpoint:null,taskClock:null,taskContext:null,timerNow:()=>1,host:()=>null};
context.g=context;
context.__dom=(op,id,a)=>{
  if(['box','rect','style','rects'].includes(op)){calls++;let value={width:revision,top:Number(a)||0};return op==='rects'?[value]:value;}
  if(['set','unset','insert','append','remove','textSet','htmlSet'].includes(op))revision++;
  return null;
};
vm.createContext(context);
vm.runInContext(source.slice(source.indexOf('var layoutReads='),source.indexOf('function resolve(u)')),context);
for(const name of ['__cssDone','__imageDone'])vm.runInContext(source.match(new RegExp('g\\.'+name+'=function[^\\n]+'))[0],context);
vm.runInContext(source.match(/g\.__tick=function\(\)\{[\s\S]*?\n\};/)[0],context);
function read(op='box',id=1,a){return context.dom(op,id,a);}
let first=read();first.width=999;
assert.equal(read().width,1);assert.equal(calls,1);
read('style');read('style');assert.equal(calls,2);
let rects=read('rects');rects[0].width=999;assert.equal(read('rects')[0].width,1);
read('rect',1,0);let before=calls;read('rect',1,10);assert.equal(calls,before+1);
for(const mutation of ['set','unset','insert','append','remove','textSet','htmlSet']){
 before=calls;context.dom(mutation,1,'width','72px');assert.equal(read().width,revision);assert.equal(calls,before+1);
}
for(const resource of ['__cssDone','__imageDone','__tick']){
 read();before=calls;context[resource]('unused',1,1);read();assert.equal(calls,before+1,resource);
}
for(let i=0;i<300;i++)read('box',i);
assert.equal(vm.runInContext('layoutReads.size',context),256);
before=calls;read('box',1);assert.equal(calls,before+1);
console.log('PASS production layout snapshots, independent rects, DOM/style/text mutation invalidation, scroll keys, resource/timer invalidation and bounded retention');
