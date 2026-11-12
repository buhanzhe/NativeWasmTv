const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync('app/src/main/assets/userscripts/ntv-live-recovery.user.js','utf8');
function setup(){
 let now=100000,queue=[],initial=[],calls=[];const video={played:{length:1,start:()=>0,end:()=>20}};
 const c={location:{hostname:'www.huya.com',pathname:'/123'},document:{getElementById:()=>video},TT_ROOM_DATA:{isPayRoom:0,isSecret:0,isOn:true},__host:()=>({headless:true,originalMediaPipeline:true}),__isActiveMseVideo:()=>true,setTimeout:(f,ms)=>{if(ms===250)queue.push(f);if(ms===12000)initial.push(f)},Date:{now:()=>now}};c.globalThis=c;vm.createContext(c);vm.runInContext(source,c);
 function H5Player(){}H5Player.prototype.startPlayer=function(info){this.player={info};calls.push(info)};H5Player.prototype.stop=function(reason){this.player=null;return reason};c.H5Player=H5Player;
 const owner=new c.H5Player(),info={roomId:123,signedSource:'original SDK selection'};owner.startPlayer(info);calls.length=0;
 return {c,owner,info,calls,video,flush(){const pending=queue;queue=[];pending.forEach(f=>f());},initial(){const pending=initial;initial=[];pending.forEach(f=>f());},tick(){now+=11000;}};
}
{const t=setup();assert.equal(t.owner.stop('FlvUrlUnavailable'),'FlvUrlUnavailable');assert.equal(t.calls.length,0);t.flush();assert.equal(t.calls.length,1);assert.equal(t.calls[0],t.info);}
for(const change of [t=>t.c.TT_ROOM_DATA.isPayRoom=1,t=>t.c.TT_ROOM_DATA.isSecret=1,t=>t.c.TT_ROOM_DATA.isOn=false,t=>t.owner.payCount403=1,t=>t.video.played.end=()=>5,t=>t.c.__isActiveMseVideo=()=>false,t=>t.owner.player.info={roomId:123}]){const t=setup();change(t);t.owner.stop('FlvUrlUnavailable');t.flush();assert.equal(t.calls.length,0);}
for(const reason of ['UserStop','ChannelChanged','VideoEnd',null]){const t=setup();t.owner.stop(reason);t.flush();assert.equal(t.calls.length,0);}
{const t=setup();t.owner.stop('FlvUrlUnavailable');t.c.location.pathname='/456';t.flush();assert.equal(t.calls.length,0);}
{const t=setup();t.owner.stop('FlvUrlUnavailable');t.owner.startPlayer({...t.info});t.calls.length=0;t.flush();assert.equal(t.calls.length,0);}
{const t=setup();for(let i=0;i<10;i++){t.owner.stop('FlvUrlUnavailable');t.flush();t.tick();}assert.equal(t.calls.length,8);}
console.log('PASS original SDK identity, real free-room and playback guards, manual stop, stale channel/version and bounded recovery');

{const t=setup();t.owner.stop('FlvUrlUnavailable');t.flush();t.tick();t.video.played.end=()=>1;t.owner.stop('FlvUrlUnavailable');t.flush();assert.equal(t.calls.length,2);}

{const t=setup();let publicStarts=0;t.owner.start=function(info){publicStarts++;this.startPlayer(info);};t.owner.stop('FlvUrlUnavailable');t.flush();assert.equal(publicStarts,1);assert.equal(t.calls[0],t.info);}

{const t=setup();t.video.played.length=0;t.c.__isActiveMseVideo=()=>false;t.initial();assert.equal(t.calls.length,1);assert.equal(t.calls[0],t.info);t.initial();assert.equal(t.calls.length,1);}
for(const change of [t=>t.owner.stop('UserStop'),t=>t.c.location.pathname='/456',t=>t.c.TT_ROOM_DATA.isPayRoom=1,t=>t.owner.payCount403=1,t=>t.owner.player.info={roomId:123},t=>t.c.__isActiveMseVideo=()=>true]){const t=setup();t.video.played.length=0;t.c.__isActiveMseVideo=()=>false;change(t);t.initial();assert.equal(t.calls.length,0);}
{const t=setup();t.c.__isActiveMseVideo=()=>false;t.initial();assert.equal(t.calls.length,0);}
console.log('PASS one original SDK initial retry only without active MSE or native played history; manual stop, ownership, room and permission guards');
