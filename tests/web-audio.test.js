const fs=require('fs'),vm=require('vm'),assert=require('assert');
const script=fs.readFileSync('app/src/main/res/raw/web_audio_compat.js','utf8');
function setup(host,video,mse){const handlers={};function Media(){};Media.prototype.canPlayType=function(){return 'maybe'};const c={location:{hostname:host,pathname:'/column/tv/434'},HTMLMediaElement:Media,Hls:mse===null?undefined:{isSupported:()=>mse},document:{addEventListener:(n,f)=>(handlers[n]||(handlers[n]=[])).push(f),querySelectorAll:()=>video?[video]:[]}};c.window=c;vm.createContext(c);return{c,handlers,run:()=>vm.runInContext(script,c),emit:(n,target)=>(handlers[n]||[]).forEach(f=>f({target}))};}
function video(){return{tagName:'VIDEO',volume:0,muted:false,paused:true,readyState:4};}
let v=video(),s=setup('live.jstv.com',v,true);s.run();s.run();s.emit('playing',v);assert.equal(v.volume,.6);v.volume=0;s.emit('playing',v);assert.equal(v.volume,0);assert.equal(s.handlers.playing.length,1);
v=video();s=setup('live.jstv.com',v,true);s.run();s.emit('mousedown');s.emit('playing',v);assert.equal(v.volume,0);
v=video();v.muted=true;s=setup('live.jstv.com',v,true);s.run();s.emit('playing',v);assert.equal(v.volume,0);
v=video();s=setup('live.jstv.com',v,true);s.c.__ntvMediaPause=function(){};s.run();s.emit('playing',v);assert.equal(v.volume,0);
for(const mse of [true,false,null]){s=setup('www.xjtvs.com.cn',null,mse);s.run();const fn=s.c.HTMLMediaElement.prototype.canPlayType;s.run();assert.equal(fn,s.c.HTMLMediaElement.prototype.canPlayType);assert.equal(fn('application/x-mpegURL'),mse?'':'maybe');assert.equal(fn('video/mp4'),'maybe');}
v=video();s=setup('example.com',v,true);s.run();s.emit('playing',v);assert.equal(v.volume,.6);assert.equal(s.c.HTMLMediaElement.prototype.canPlayType('application/x-mpegURL'),'maybe');
v=video();v.tagName='AUDIO';s=setup('music.example.com',v,true);s.run();s.emit('playing',v);assert.equal(v.volume,.6);
v=video();v.volume=.25;s=setup('example.com',v,true);s.run();s.emit('playing',v);assert.equal(v.volume,.25);
v=video();v.paused=false;s=setup('example.com',v,true);s.run();assert.equal(v.volume,.6);
for(const host of ['example.com','music.example.com']){
 v=video();s=setup(host,v,true);s.run();s.emit('mousedown');s.emit('playing',v);assert.equal(v.volume,0);
 v=video();v.muted=true;s=setup(host,v,true);s.run();s.emit('playing',v);assert.equal(v.volume,0);
 v=video();s=setup(host,v,true);s.c.__ntvMediaPause=function(){};s.run();s.emit('playing',v);assert.equal(v.volume,0);
}
console.log('PASS all-site video/audio initial volume, existing playback, nonzero volume, manual mute, interaction, multimedia pause, site-only HLS/MSE fallback and idempotence');

v=video();s=setup('live.jstv.com',v,true);s.c.__ntvMediaPause=function(){};s.run();s.emit('playing',v);
delete s.c.__ntvMediaPause;s.emit('playing',v);assert.equal(v.volume,.6);
console.log('PASS volume recovery deferred until returning to retained page');
