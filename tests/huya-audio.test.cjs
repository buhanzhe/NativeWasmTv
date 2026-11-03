const fs=require('fs'),vm=require('vm'),assert=require('assert/strict');
const source=fs.readFileSync('app/src/main/assets/web-tv-playback.user.js','utf8');
function method(name){const start=source.indexOf('  function '+name+'(');let end=source.indexOf('{',start)+1,depth=1;while(depth){depth+=(source[end]==='{')-(source[end]==='}');end++;}return source.slice(start,end);}
function fixture(){
 const el={muted:true,paused:false,readyState:4,currentTime:1,volume:1,tagName:'VIDEO',hasAttribute:()=>false};
 const c={auto:true,stopped:false,userPaused:false,lastGesture:0,handoff:()=>false,d:{hidden:false},calls:0,Date:{now:()=>1000}};
 c.w={location:{hostname:'m.huya.com'},__ntvTabMuted:false,videoPlayer:{getVolume:()=>1,player:{autoMutedPlay:true,setMuted:v=>{el.muted=v;c.calls++;}}}};
 vm.createContext(c);vm.runInContext(method('restoreHuyaAudio')+'\n'+method('audioChoice'),c);return {c,el};
}
let {c,el}=fixture();c.restoreHuyaAudio(el);assert.equal(el.muted,false);assert.equal(c.calls,1);el.muted=true;c.restoreHuyaAudio(el);assert.equal(c.calls,1,'manual mute after recovery must survive');
assert.equal(c.w.videoPlayer.player.autoMutedPlay,false);
c.w.videoPlayer.player.autoMutedPlay=true;c.restoreHuyaAudio(el);assert.equal(c.calls,2,'SDK reconnect auto-mute must be reconciled');
for(const change of [c=>c.auto=false,c=>c.stopped=true,c=>c.userPaused=true,c=>c.handoff=()=>true,c=>c.d.hidden=true,c=>c.w.__ntvTabMuted=true,c=>c.w.location.hostname='huya.com.example.test',c=>c.w.videoPlayer.getVolume=()=>0,c=>c.w.videoPlayer.player.autoMutedPlay=false]){
 ({c,el}=fixture());change(c);c.restoreHuyaAudio(el);assert.equal(c.calls,0);
}
({c,el}=fixture());el.hasAttribute=()=>true;c.restoreHuyaAudio(el);assert.equal(c.calls,0);
({c,el}=fixture());c.lastGesture=900;c.audioChoice({target:el});c.restoreHuyaAudio(el);assert.equal(c.calls,0,'recent manual audio choice must survive initial recovery');
({c,el}=fixture());el.currentTime=0;c.restoreHuyaAudio(el);assert.equal(c.calls,0);
console.log('PASS Huya SDK auto-mute recovery, once-per-video, manual/tab/zero-volume mute, autoplay-off, handoff, background and hostname boundaries');
