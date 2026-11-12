// Exercise the generic channel-owned UJS path without any site userscript.
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync('browser-core/litebrowser/src/main/assets/selection-flow.js','utf8');
function page(code,{ready=true,override=false}={}){
 const events=[],timers=[],errors=[],scripts=[];let executions=0,initializations=0;
 const config={selectionScript:code,selectionRequired:!!code};
 const node={parentElement:null,getAttribute(name){return name==='data-src'?'/selected.m3u8?token=original':null;}};
 const c={URL,document:{},location:{href:'https://fixture.example/page'},console:{error(e){errors.push(e);}},
  __host(raw){const q=JSON.parse(raw);if(q.op==='pageConfig')return config;events.push(q);},
  __init(){initializations++;if(override)this.__siteReady=()=>events.push({op:'app-handler'});},
  __runSelectionScript(value){scripts.push(value);executions++;this.__siteClick(true,ready,node);},
  setInterval(fn){timers.push(fn);return timers.length;},clearInterval(){}};
 vm.createContext(c);vm.runInContext(source,c);c.__init('https://fixture.example/page');
 return {c,events,timers,errors,scripts,ready(value){ready=value;},executions:()=>executions,initializations:()=>initializations};
}
let t=page('$("#selected").click()');t.c.__siteReady();assert.equal(t.executions(),1);assert.equal(t.initializations(),1);
assert.deepEqual(t.events,[{op:'selectionReady',target:'https://fixture.example/selected.m3u8?token=original'}]);assert.equal(t.c.__selectionIsPending(),false);
t=page('$$("#selected").click()',{ready:false});t.c.__siteReady();assert.equal(t.c.__selectionIsPending(),true);assert.equal(t.events.length,0);assert.equal(t.timers.length,1);
t.ready(true);t.timers[0]();assert.equal(t.executions(),2);assert.equal(t.events.length,1);t.timers[0]();assert.equal(t.events.length,1);
t=page('customRequestWithSideEffects()',{ready:false});t.c.__siteReady();assert.equal(t.executions(),1);assert.equal(t.timers.length,0);
t=page(' $$(".channels").filter(function() {return $(this).text().trim() === "经济生活";}).click();_tvFunc.fullscreenW("video");',{ready:false});t.c.__siteReady();assert.equal(t.timers.length,1);assert.match(t.scripts[0],/fullscreenW/);t.ready(true);t.timers[0]();assert.doesNotMatch(t.scripts[1],/fullscreenW/);assert.equal(t.events.length,1);
t=page('$$(".channels").click();sendPayment()',{ready:false});t.c.__siteReady();assert.equal(t.timers.length,0);
t=page('');t.c.__siteReady();assert.equal(t.executions(),0);assert.equal(t.c.__selectionIsPending(),false);
t=page('custom()',{override:true});t.c.__siteReady();assert.equal(t.executions(),0);assert.equal(t.events[0].op,'app-handler');
console.log('PASS generic channel UJS once, bounded declarative retry, real click readiness, exact selected URL, no-script behavior and app hook override');
