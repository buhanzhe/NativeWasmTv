const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync(process.argv[2]||'app/src/main/assets/userscripts/ntv-live-recovery.user.js','utf8');
function setup(change=()=>{}){
 let queue=[],calls=[],resolveResponse;const data={profileRoom:123,isPayRoom:0,isSecret:0,isOn:true},row={lPresenterUid:42,sStreamName:'selected',sCdnType:'TX',sFlvUrl:'https://cdn.example/live',sFlvAntiCode:'fresh'};change(data,row);
 const body='var TT_ROOM_DATA = '+JSON.stringify(data)+'; var hyPlayerConfig = { html5:1,stream: '+JSON.stringify({data:[{gameStreamInfoList:[row]}]})+'};';
 const video={played:{length:1,start:()=>0,end:()=>20}};
 const c={URL,location:{hostname:'www.huya.com',pathname:'/123',href:'https://www.huya.com/123'},document:{getElementById:()=>video},TT_ROOM_DATA:{...data,isPayRoom:0,isSecret:0,isOn:true},__host:()=>({headless:true,originalMediaPipeline:true}),__isActiveMseVideo:()=>true,setTimeout:(f,ms)=>queue.push({f,ms}),fetch:()=>new Promise(resolve=>resolveResponse=()=>resolve({status:200,text:async()=>body}))};c.globalThis=c;vm.createContext(c);vm.runInContext(source,c);
 function H5Player(){}H5Player.prototype.startPlayer=function(info){this.player={info};calls.push(info)};H5Player.prototype.stop=function(reason){this.player=null;return reason};c.H5Player=H5Player;
 const owner=new c.H5Player(),info={roomId:123,presenterUid:42,sStreamName:'selected',sFlvUrl:'https://cdn.example/live',lineType:'TX',sFlvAntiCode:'old',iBitRate:4000};owner.startPlayer(info);calls.length=0;
 return {c,owner,info,calls,begin(){const t=queue.find(x=>x.ms===90000);assert.ok(t);queue=queue.filter(x=>x!==t);t.f();},async finish(){resolveResponse();for(let i=0;i<12;i++)await Promise.resolve();},async flush(){const pending=queue;queue=[];pending.filter(x=>x.ms===250).forEach(x=>x.f());for(let i=0;i<5;i++)await Promise.resolve();}};
}
(async()=>{
 const good=setup();good.begin();await good.finish();assert.equal(good.info.sFlvAntiCode,'fresh');assert.equal(good.info.iBitRate,4000);assert.equal(good.calls.length,0);
 for(const change of [(d,r)=>d.profileRoom=456,(d,r)=>d.isPayRoom=1,(d,r)=>d.isSecret=1,(d,r)=>d.isOn=false,(d,r)=>r.lPresenterUid=99,(d,r)=>r.sStreamName='other',(d,r)=>{r.sCdnType='AL';r.sFlvUrl='https://other.example/live';}]){const t=setup(change);t.begin();await t.finish();assert.equal(t.info.sFlvAntiCode,'old');}
 for(const change of [t=>t.c.location.pathname='/456',t=>t.owner.stop('UserStop'),t=>t.owner.startPlayer({...t.info})]){const t=setup();t.begin();change(t);await t.finish();assert.equal(t.info.sFlvAntiCode,'old');}
 const cancel=setup();cancel.owner.stop('FlvUrlUnavailable');cancel.owner.stop('UserStop');await cancel.flush();assert.equal(cancel.calls.length,0);
 console.log('PASS original-page strict JSON, matching room/presenter/stream/CDN, unchanged quality, permission and stale response guards, no autoplay, pending-recovery manual cancellation');
})().catch(e=>{console.error(e);process.exitCode=1;});
