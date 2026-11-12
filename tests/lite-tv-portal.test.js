const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync('app/src/main/assets/userscripts/ntv-tv-portal.user.js','utf8');
const tv={id:'selected-tv',type:'2711/iframeTv',path:'/2711/iframeTv/index.vue',visible:true,props:{selectedChannel:2,channels:[{id:1},{id:2}],requireLogin:true}};
const original={key:'current',password:'',visibilityType:7,config:{open:true,preview:false,expires:'original'},content:JSON.stringify({id:'root',type:'2711/webContainer',props:{access:'unchanged'},child:[{id:'webheaderContainer',child:[{id:'unrelated-header'}],headerStyle:{height:100}},{id:'webfooterContainer',child:[{id:'unrelated-footer'}],footerStyle:{height:100}},{id:'parent',child:[tv,{id:'news',type:'2711/articles',child:[{}]}]}]})};
function create(headless=true,hash='#/current?pkey='){
 const c={URL,ArrayBuffer,TextEncoder,TextDecoder,location:{href:'https://www.zyrb.com.cn/'+hash,hash,origin:'https://www.zyrb.com.cn'},__host:()=>({headless})};c.g=c;c.globalThis=c;vm.createContext(c);
 vm.runInContext(`const blobs=new WeakMap();class Blob{constructor(parts,options={}){this.type=options.type||'';blobs.set(this,new TextEncoder().encode(parts.join('')));}};globalThis.Blob=Blob;globalThis.__blobSnapshot=b=>blobs.has(b)?{bytes:blobs.get(b),type:b.type}:null;
 class XHR{};Object.defineProperties(XHR.prototype,{responseText:{configurable:true,get(){return this.text}},response:{configurable:true,get(){return this.binary||JSON.parse(this.text)}}});globalThis.XMLHttpRequest=XHR;`,c);c.responseFactory=()=>({status:200,url:'https://www.zyrb.com.cn/datahub/current.json',json:async()=>structuredClone(original),text:async()=>JSON.stringify(original),clone(){return c.responseFactory();}});c.fetch=async()=>c.responseFactory();vm.runInContext(source,c);return c;
}
function read(c,data=original,path='/datahub/current.json',status=200){c.input=JSON.stringify(data);c.path=path;c.status=status;return JSON.parse(vm.runInContext(`var q=new XMLHttpRequest();q.readyState=4;q.status=status;q.responseURL=new URL(path,location.href).href;q.text=input;q.responseText`,c));}
const c=create(),slim=read(c),tree=JSON.parse(slim.content);
assert.deepEqual(slim.config,original.config);assert.equal(slim.visibilityType,7);assert.deepEqual(tree.props,{access:'unchanged'});
assert.deepEqual(tree.child[0].child,[]);assert.deepEqual(tree.child[0].headerStyle,{height:100});assert.deepEqual(tree.child[1].child,[]);
assert.deepEqual(tree.child[2].child,[tv]);assert.deepEqual(JSON.parse(vm.runInContext('JSON.stringify(q.response)',c)),slim);
assert.deepEqual(read(create(false)),original);assert.deepEqual(read(create(),'string'), 'string');
assert.deepEqual(read(create(),original,'https://other.example/datahub/current.json'),original);
assert.deepEqual(read(create(),original,'/datahub/other.json'),original);
assert.deepEqual(read(create(),{...original,password:'protected'}),{...original,password:'protected'});
const multiple={...original,content:JSON.stringify({child:[tv,{...tv,id:'another-tv'}]})};assert.deepEqual(read(create(),multiple),multiple);
console.log('PASS selected portal only: preserves TV/channel/permission data and structural styles; leaves protected, ambiguous, cross-origin and rendered pages unchanged');

function child(){
 const timers=[],requests=[],calls=[];let success,paused=false,selected='1';
 const url='https://cdn.example/live/selected.m3u8?sign=first&t=69000001';
 const video={currentTime:20,get paused(){return paused}},player={currentSrc:()=>url,src(value){calls.push(value);return this;},poster(){}};
 const c={URL,location:{href:'https://www.zyrb.com.cn/tvradio_dvw/tv_detail_dvw.html?tv_id=1',pathname:'/tvradio_dvw/tv_detail_dvw.html',hash:''},XMLHttpRequest:function(){},document:{querySelector:s=>s==='video'?video:{getAttribute:()=>selected}},tv_id:'1',myTvPlayer:player,$:{ajax(options){success=options.success;}},setTimeout(fn,ms){timers.push({fn,ms});},__host(raw){const o=JSON.parse(raw);if(o.op==='pageConfig')return {headless:true};if(o.op==='nativeHlsRefresh'){requests.push(o);return true;}}};
 c.getTvDetail=function(id){assert.equal(id,'1');c.$.ajax({success(data){player.src([{src:data}]);player.poster('original');}});};c.globalThis=c;vm.createContext(c);vm.runInContext(source,c);
 return {c,timers,requests,calls,complete:next=>success(next),pause:()=>paused=true,select:id=>selected=id};
}
{
 const t=child(),ajax=t.c.$.ajax,src=t.c.myTvPlayer.src;assert.equal(t.timers[0].ms,90000);assert.equal(t.requests.length,0);t.timers.shift().fn();assert.equal(t.c.$.ajax,ajax);t.complete('https://cdn.example/live/selected.m3u8?sign=second&t=69000002');assert.equal(t.requests.length,1);assert.equal(t.calls.length,0);assert.equal(t.c.myTvPlayer.src,src);assert.equal(t.requests[0].previous,'https://cdn.example/live/selected.m3u8?sign=first&t=69000001');
 const paused=child();paused.pause();paused.timers.shift().fn();assert.equal(paused.requests.length,0);
 const changed=child();changed.timers.shift().fn();changed.select('2');changed.complete('https://cdn.example/live/selected.m3u8?sign=second&t=69000002');assert.equal(changed.requests.length,0);assert.equal(changed.calls.length,0);
 const another=child();another.timers.shift().fn();another.complete('https://cdn.example/live/another.m3u8?sign=second&t=69000002');assert.equal(another.requests.length,0);assert.equal(another.calls.length,0);
 console.log('PASS original selected-channel function refresh: delayed, current-owner only, native owned transport request, no DOM source/decoder replacement or manual-pause override');
}

(async()=>{
 for(const kind of ['blob','arraybuffer']){
  const c=create();read(c);c.kind=kind;
  const parsed=JSON.parse(vm.runInContext(`q.binary=kind==='blob'?new Blob([input],{type:'application/json'}):new TextEncoder().encode(input).buffer;var result=q.response;var bytes=kind==='blob'?__blobSnapshot(result).bytes:result;new TextDecoder().decode(bytes)`,c));
  assert.deepEqual(parsed,slim);if(kind==='blob')assert.equal(vm.runInContext('result.type',c),'application/json');
 }
 const c=create();const response=await c.fetch('/datahub/current.json');
 assert.deepEqual(JSON.parse(JSON.stringify(await response.json())),slim);assert.deepEqual(JSON.parse(await response.text()),slim);assert.deepEqual(JSON.parse(JSON.stringify(await response.clone().json())),slim);
 console.log('PASS Blob/ArrayBuffer XHR and Fetch JSON/text/clone preserve response representation and selected TV');
})().catch(error=>{console.error(error);process.exitCode=1;});
