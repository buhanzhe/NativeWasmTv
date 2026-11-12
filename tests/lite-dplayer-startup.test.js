const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const code=fs.readFileSync('app/src/main/assets/userscripts/ntv-live-recovery.user.js','utf8');
function setup(headless=true,hostname='www.sxtvs.com'){
 const calls=[],inputs=new Map();let assigned,sets=0;
 const c={URL,location:{hostname,href:'http://'+hostname+'/'},document:{addEventListener:(n,f)=>inputs.set(n,f),removeEventListener:n=>inputs.delete(n)},__host:()=>({headless}),setTimeout(){}};c.globalThis=c;
 Object.defineProperty(c,'DPlayer',{configurable:true,get:()=>assigned,set:v=>{assigned=v;sets++;}});
 vm.createContext(c);vm.runInContext(code,c);
 function Player(options){this.options=options;this.video={src:options.video.url,listeners:{},getAttribute(n){return this.src;},addEventListener(n,fn){this.listeners[n]=fn;}};if(options.autoplay)this.play();}
 Player.prototype.play=function(){calls.push(this.video.src);};c.DPlayer=Player;
 Player.prototype.switchVideo=function(next){calls.push('switch');this.video.src=next.url;};
 return {c,calls,inputs,get sets(){return sets;}};
}
{const t=setup(),video={url:'',quality:'unchanged'},options={autoplay:true,video},p=new t.c.DPlayer(options);assert.equal(t.sets,1);assert.equal(t.calls.length,0);assert.equal(options.autoplay,true);assert.equal(options.video,video);p.video.src='https://fixture.test/selected.m3u8';p.video.listeners.loadstart();assert.deepEqual(t.calls,['https://fixture.test/selected.m3u8']);assert.equal(t.inputs.size,0);}
{const t=setup(),p=new t.c.DPlayer({autoplay:false,video:{url:''}});assert.equal(t.calls.length,0);assert.equal(p.options.autoplay,false);assert.equal(p.video.listeners.loadstart,undefined);}
{const t=setup();new t.c.DPlayer({autoplay:true,video:{url:'https://fixture.test/exact.m3u8'}});assert.deepEqual(t.calls,['https://fixture.test/exact.m3u8']);}
{const t=setup(),p=new t.c.DPlayer({autoplay:true,video:{url:''}});t.inputs.get('keydown')({isTrusted:true});p.video.src='https://fixture.test/selected.m3u8';p.video.listeners.loadstart();assert.equal(t.calls.length,0);}
{const t=setup(),p=new t.c.DPlayer({autoplay:true,video:{url:''}}),old=p.video;p.video={};old.src='https://fixture.test/selected.m3u8';old.listeners.loadstart();assert.equal(t.calls.length,0);}
for(const t of [setup(false),setup(true,'other.example')]){new t.c.DPlayer({autoplay:true,video:{url:''}});assert.deepEqual(t.calls,['']);}
{const t=setup(),p=new t.c.DPlayer({autoplay:true,video:{url:''}});p.switchVideo({url:'http://fixture.test/live.m3u8'});p.switchVideo({url:'http://fixture.test/live.m3u8'});assert.deepEqual(t.calls,['switch']);p.switchVideo({url:'http://fixture.test/schedule.m3u8'});assert.deepEqual(t.calls,['switch','switch'],'schedule and selected channel changes remain authoritative');p.switchVideo({url:'http://fixture.test/schedule.m3u8'},{id:'new-danmaku'});assert.equal(t.calls.length,3);}
console.log('PASS original DPlayer constructor and source preserved, empty-source autoplay deferred, explicit autoplay-off, trusted input, stale element and existing constructor setter respected');
