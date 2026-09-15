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
for(const dx of [60,-60]){
 const f=fixture();f.fire('touchstart',points());f.fire('touchmove',points(dx/2));f.fire('touchend',[],points(dx));
 assert.equal(f.sent.length,2, 'final touch sample must also scroll');
 assert(f.sent.every(x=>x.action==='scroll' && Math.sign(x.scrollX)===-Math.sign(dx)));
}
let f=fixture();f.fire('touchstart',points());f.fire('touchmove',points(0,-40));f.fire('touchend',[],points(0,-60));
assert(f.sent.length>0&&f.sent.every(x=>x.action==='scroll'&&x.scrollY>0));
f=fixture();f.fire('touchstart',points());f.fire('touchend',[],points());assert.equal(f.sent[0].action,'rightclick');
f=fixture();f.fire('touchstart',points());f.fire('touchmove',points(0,0,105));
assert.equal(f.sent.length,0, '5px spacing jitter must not zoom');
f.fire('touchmove',points(0,0,119));assert.equal(f.sent.length,0);
f.fire('touchmove',points(0,0,122));assert.equal(f.sent[0].action,'zoom');assert(f.sent[0].zoomFactor>1.22);
f.fire('touchmove',points(0,0,122.1));assert.equal(f.sent.length,2);assert(f.sent[1].zoomFactor>1);
f.fire('touchend',[],points(0,0,126));assert(f.sent.every(x=>x.action==='zoom'));assert.equal(f.sent.length,3);
for(const movement of [points(80),points(0,80),points(0,0,150)]){
 f=fixture(false);f.fire('touchstart',points());f.fire('touchmove',movement);f.fire('touchend',[],movement);assert.equal(f.sent.length,0);
}
f=fixture();f.fire('touchstart',points());f.fire('touchmove',points(70));f.fire('touchcancel',[],points(70));assert(f.sent.every(x=>x.action==='scroll'));
assert.equal(f.sent.length,1, 'cancellation must not generate a navigation command');
// Held network requests merge relative scale samples rather than dropping or summing them.
const frames=[],sent=[];let done;
const c={setTimeout:fn=>frames.push(fn),requestAnimationFrame:fn=>frames.push(fn)};c.window=c;
vm.runInNewContext(queueCode,c);
const q=new c.NtvPointerQueue((body,cb)=>{sent.push(body);done=cb});
q.push({action:'zoom',zoomFactor:1.1});frames.shift()();
q.push({action:'zoom',zoomFactor:1.2});q.push({action:'zoom',zoomFactor:.9});q.push({action:'webBack'});
done(null,{});frames.shift()();assert(Math.abs(sent[1].zoomFactor-1.08)<1e-9);
done(null,{});assert.equal(sent[2].action,'webBack');
console.log('PASS horizontal/vertical scrolling, final samples, right click, pinch threshold and continuous zoom, no channel/history changes, cancellation and queue ordering');
// A larger initial finger gap requires proportionally more spacing change.
f=fixture();f.fire('touchstart',points(0,0,200));f.fire('touchmove',points(0,0,222));assert.equal(f.sent.length,0);
f.fire('touchmove',points(0,0,226));assert.equal(f.sent[0].action,'zoom');
// Slight center drift while pinching must not lock the gesture into scrolling.
f=fixture();f.fire('touchstart',points());f.fire('touchmove',points(5,0,108));assert.equal(f.sent.length,0);
f.fire('touchmove',points(5,0,122));assert.equal(f.sent[0].action,'zoom');
