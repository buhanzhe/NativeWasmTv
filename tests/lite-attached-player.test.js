const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync('browser-core/litebrowser/src/main/assets/player-observer.js','utf8');
function page({pending=false,headless=true,original=false}={}){
 const events=[],nodes=[];let autos=0;
 const context={URL,WeakMap,WeakSet,Map,Set,Node:{prototype:{}},location:{href:'https://fixture.example/live'},document:{querySelectorAll(){return nodes;}},console:{log(){}},__selectionIsPending:()=>pending,__auto(){autos++;},__host(raw){let q=JSON.parse(raw);if(q.op==='pageConfig')return {headless,originalMediaPipeline:original};events.push(q);}};
 context.globalThis=context;vm.createContext(context);vm.runInContext(source,context);
 const node={isConnected:true},chosen={src:'/selected.m3u8?original=token',type:'application/x-mpegURL'};
 const player={currentSource:()=>chosen,currentSources:()=>[chosen],options:()=>({sources:[]}),el:()=>node};node.player=player;nodes.push(node);
 return {context,events,node,player,chosen,auto(){context.__auto();},autos:()=>autos};
}
let t=page();t.auto();assert.equal(t.events.length,1);assert.equal(t.events[0].value,'https://fixture.example/selected.m3u8?original=token');t.auto();assert.equal(t.events.length,1);assert.equal(t.autos(),2);
t.chosen.src='/next.m3u8';t.auto();assert.equal(t.events.length,2);
for(let mode of ['pending','headless','original']){t=page({[mode]:mode==='headless'?false:true});t.auto();assert.equal(t.events.length,0);}
t=page();t.node.isConnected=false;t.auto();assert.equal(t.events.length,0);
t=page();t.player.el=()=>({contains:()=>false});t.auto();assert.equal(t.events.length,0);
t=page();t.player.currentSources=()=>[{src:'/other-channel.m3u8'}];t.auto();assert.equal(t.events.length,0);
t=page();t.player.currentSources=()=>[t.chosen,{src:'/prefetch.m3u8'}];t.auto();assert.equal(t.events.length,0);
t=page();t.player.isDisposed=()=>true;t.auto();assert.equal(t.events.length,0);
console.log('PASS private SDK discovery on its owned element, exact current source, changes/deduplication and selection/lifetime guards');
