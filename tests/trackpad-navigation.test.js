const fs=require('fs'),vm=require('vm'),assert=require('assert/strict');
const queueCode=fs.readFileSync('app/src/main/assets/control/js/pointer-queue.js','utf8');
const pageCode=fs.readFileSync('app/src/main/assets/control/js/pages/flymouse.js','utf8');
function fixture(web=true){
 const handlers={},sent=[];let time=100;
 const pad={clientWidth:360,clientHeight:440,addEventListener:(name,fn)=>handlers[name]=fn};
 const c={state:{current:{webPageActive:web}},pointerScale:1,ntvVibrate(){},setTimeout(){return 1},clearTimeout(){},
  document:{getElementById:()=>pad,addEventListener(){}},pointerMove(){},pointerButtonAction(){},
  pointerAction:action=>sent.push({action}),pointerQueue:{push:b=>sent.push(b),cancelScroll(){}},
  performance:{now:()=>time},addEventListener(){}};
 c.window=c;vm.createContext(c);vm.runInContext(queueCode,c);
 vm.runInContext(pageCode.slice(pageCode.indexOf('function setupTouchpad()'),pageCode.indexOf('function renderPageState()'))+'\nsetupTouchpad();',c);
 return{c,sent,fire(name,touches,changed=[]){time+=16;handlers[name]({touches,changedTouches:changed,preventDefault(){}})}};
}
function points(dx=0,dy=0,gap=100){return[{identifier:1,clientX:180-gap/2+dx,clientY:150+dy},{identifier:2,clientX:180+gap/2+dx,clientY:150+dy}]}
for(const [dx,action] of [[60,'webBack'],[-60,'webForward']]){
 const f=fixture();f.fire('touchstart',points());f.fire('touchmove',points(dx/2));f.fire('touchend',[],points(dx));
 assert(f.sent.every(x=>x.action==='webSwipe'));
 assert.equal(f.sent[f.sent.length-1].direction,action==='webBack'?-1:1);
 assert.equal(f.sent[f.sent.length-1].end,true);
}
let f=fixture();f.fire('touchstart',points());f.fire('touchmove',points(0,-40));f.fire('touchend',[],points(0,-60));
assert(f.sent.length>0&&f.sent.every(x=>x.action==='scroll'&&x.scrollY>0));
f=fixture();f.fire('touchstart',points());f.fire('touchend',[],points());assert.equal(f.sent[0].action,'rightclick');
f=fixture();f.fire('touchstart',points());f.fire('touchmove',points(0,0,105));
assert.equal(f.sent[0].action,'zoom');assert(f.sent[0].zoomFactor>1.05);
f.fire('touchmove',points(0,0,105.1));assert.equal(f.sent.length,2);assert(f.sent[1].zoomFactor>1);
f.fire('touchend',[],points(0,0,110));assert(f.sent.every(x=>x.action==='zoom'));assert.equal(f.sent.length,3);
for(const movement of [points(80),points(0,80),points(0,0,150)]){
 f=fixture(false);f.fire('touchstart',points());f.fire('touchmove',movement);f.fire('touchend',[],movement);assert.equal(f.sent.length,0);
}
f=fixture();f.fire('touchstart',points());f.fire('touchmove',points(70));f.fire('touchcancel',[],points(70));assert.equal(f.sent[f.sent.length-1].direction,0);assert.equal(f.sent[f.sent.length-1].end,true);
// Held network requests merge relative scale samples rather than dropping or summing them.
const frames=[],sent=[];let done;
const c={setTimeout:fn=>frames.push(fn),requestAnimationFrame:fn=>frames.push(fn)};c.window=c;
vm.runInNewContext(queueCode,c);
const q=new c.NtvPointerQueue((body,cb)=>{sent.push(body);done=cb});
q.push({action:'zoom',zoomFactor:1.1});frames.shift()();
q.push({action:'zoom',zoomFactor:1.2});q.push({action:'zoom',zoomFactor:.9});q.push({action:'webBack'});
done(null,{});frames.shift()();assert(Math.abs(sent[1].zoomFactor-1.08)<1e-9);
done(null,{});assert.equal(sent[2].action,'webBack');
console.log('PASS history directions/threshold, final touch sample, vertical scroll, right click, live fractional pinch, no channel/source changes, cancellation and zoom queue ordering');

const probe=fs.readFileSync('app/src/main/res/raw/web_horizontal_scroll_probe.js','utf8');
function scrollProbe(overflow,width,client,rootWidth=500){
 const root={clientWidth:500,scrollWidth:rootWidth,overflow:'visible'};
 const el={tagName:'DIV',clientWidth:client,scrollWidth:width,overflow,parentElement:root};
 const body={overflow:'visible'};
 const doc={scrollingElement:root,body,elementFromPoint:()=>el,defaultView:{getComputedStyle:e=>({overflowX:e.overflow})}};
 return vm.runInNewContext(probe+'(10,10)',{document:doc});
}
assert.equal(scrollProbe('auto',800,200),true);
assert.equal(scrollProbe('scroll',800,200),true);
assert.equal(scrollProbe('hidden',800,200),false);
assert.equal(scrollProbe('visible',800,200),false);
assert.equal(scrollProbe('auto',200,200),false);
assert.equal(scrollProbe('visible',200,200,900),true);
console.log('PASS horizontal overflow detection for nested scrollers, page overflow, hidden overflow and no overflow');
