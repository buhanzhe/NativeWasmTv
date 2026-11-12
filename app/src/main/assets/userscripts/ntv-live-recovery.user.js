// ==UserScript==
// @name 直播播放器启动与连接恢复
// @version 1.0.4
// @match *://www.huya.com/*
// @match *://www.sxtvs.com/*
// @match *://live.snrtv.com/*
// @run-at document-start
// @grant none
// ==/UserScript==
(function(g){
'use strict';
if(typeof __host!=='function'||!['www.sxtvs.com','live.snrtv.com'].includes(location.hostname))return;
var config=__host(JSON.stringify({op:'pageConfig'}))||{};if(!config.headless)return;
var descriptor=Object.getOwnPropertyDescriptor(g,'DPlayer'),value=g.DPlayer,wrapped=new WeakMap();
function observe(original){
 if(typeof original!=='function')return original;if(wrapped.has(original))return wrapped.get(original);
 var proxy=new Proxy(original,{construct:function(target,args,newTarget){
  var options=args[0],defer=options&&options.autoplay===true&&(!options.video||!options.video.url);
  if(!defer)return Reflect.construct(target,args,newTarget);
  var player;options.autoplay=false;
  try{player=Reflect.construct(target,args,newTarget);}finally{options.autoplay=true;}
  if(player.options)player.options.autoplay=true;
  // The schedule poll calls switchVideo even when the selected URL has not
  // changed. DPlayer pauses first; retaining the same native source then has
  // no new canplay event to restart it. Preserve real schedule/channel changes.
  if(typeof player.switchVideo==='function'){
   var change=player.switchVideo;
   player.switchVideo=new Proxy(change,{apply:function(fn,owner,values){
    var next=values[0],video=owner.video;
    if(owner===player&&next&&video&&next.url&&!values[1]&&(!next.type||next.type==='auto')){
     try{if(new URL(next.url,location.href).href===video.src)return;}catch(ignore){}
    }
    return Reflect.apply(fn,owner,values);
   }});
  }
  var video=player.video,blocked=false;
  if(video&&typeof video.addEventListener==='function'){
   function input(event){if(event.isTrusted)blocked=true;}
   document.addEventListener('keydown',input,true);document.addEventListener('mousedown',input,true);
   video.addEventListener('loadstart',function(){
    document.removeEventListener('keydown',input,true);document.removeEventListener('mousedown',input,true);
    if(!blocked&&player.video===video&&video.getAttribute('src'))player.play();
   },{once:true});
  }
  return player;
 }});wrapped.set(original,proxy);wrapped.set(proxy,proxy);return proxy;
}
if(!descriptor||descriptor.configurable){
 Object.defineProperty(g,'DPlayer',{configurable:true,enumerable:true,get:function(){return value;},set:function(next){
  if(descriptor&&descriptor.set)descriptor.set.call(g,next);
  value=observe(descriptor&&descriptor.get?descriptor.get.call(g):next);
 }});value=observe(value);
}
})(globalThis);
(function(g){
'use strict';
if(typeof __host!=='function'||location.hostname!=='www.huya.com')return;
var config=__host(JSON.stringify({op:'pageConfig'}))||{},route=location.pathname.match(/^\/(\d+)\/?$/);
if(!config.headless||!config.originalMediaPipeline||!route)return;
var room=route[1],wrapped=new WeakSet(),states=new WeakMap(),initialRetries=0;
function freeRoom(owner){var data=g.TT_ROOM_DATA,state=states.get(owner);return !(state&&state.permissionDenied)&&!!data&&Number(data.isPayRoom)===0&&Number(data.isSecret)===0&&data.isOn===true&&!(Number(owner.payCount403)>0);}
function jsonObject(text,pattern){var match=pattern.exec(text);if(!match)return null;var start=match.index+match[0].length;while(/\s/.test(text.charAt(start)))start++;if(text.charAt(start)!=='{')return null;var depth=0,string=false,escape=false;for(var i=start;i<text.length;i++){var c=text.charAt(i);if(string){if(escape)escape=false;else if(c==='\\')escape=true;else if(c==='"')string=false;}else if(c==='"')string=true;else if(c==='{')depth++;else if(c==='}'&&--depth===0)return JSON.parse(text.slice(start,i+1));}return null;}
// Refresh only the current public page data after playback has started. One
// bounded request per 90 seconds; the SDK keeps its own selected stream/quality.
function renewal(owner,state){
 if(state.tokenTimer)return;state.tokenTimer=true;
 setTimeout(function(){state.tokenTimer=false;
  if(states.get(owner)!==state||!freeRoom(owner)||!owner.player||owner.player.info!==state.info||location.pathname.replace(/\/$/,'')!=='/'+room)return;
  var selected=state.info,version=state.version;
  fetch(location.href,{cache:'no-store',credentials:'same-origin',headers:{'Cache-Control':'no-cache'}}).then(function(response){if(response.status!==200)throw Error('Page refresh failed');return response.text();}).then(function(text){
   if(text.length>2097152)throw Error('Page refresh limit');
   var data=jsonObject(text,/\bvar\s+TT_ROOM_DATA\s*=\s*/),root=/\bvar\s+hyPlayerConfig\s*=/.exec(text);
   if(!data||String(data.profileRoom)!==room)throw Error('Room changed');
   if(Number(data.isPayRoom)!==0||Number(data.isSecret)!==0||data.isOn!==true){state.permissionDenied=true;throw Error('Room permissions changed');}
   var stream=root&&jsonObject(text.slice(root.index),/\bstream\s*:\s*/),matches=[];
   if(!stream||!Array.isArray(stream.data))throw Error('Stream data missing');
   stream.data.forEach(function(group){(group.gameStreamInfoList||[]).forEach(function(row){
    var same=false;try{var current=new URL(state.info.sFlvUrl),fresh=new URL(row.sFlvUrl);same=current.hostname===fresh.hostname&&current.pathname===fresh.pathname;}catch(ignore){}
    if(String(row.lPresenterUid)===String(state.info.presenterUid)&&row.sStreamName===state.info.sStreamName&&(row.sCdnType===state.info.lineType||same)&&typeof row.sFlvAntiCode==='string'&&row.sFlvAntiCode)matches.push(row);
   });});
   if(matches.length!==1||states.get(owner)!==state||state.version!==version||state.info!==selected||!owner.player||owner.player.info!==selected||location.pathname.replace(/\/$/,'')!=='/'+room||!freeRoom(owner))throw Error('Selected stream changed');
   state.info.sFlvAntiCode=matches[0].sFlvAntiCode;
   __host(JSON.stringify({op:'log',value:'UserScript Huya selected stream refreshed from original free-room page'}));
   renewal(owner,state);
  }).catch(function(error){__host(JSON.stringify({op:'log',value:'UserScript Huya page refresh: '+String(error.message)}));renewal(owner,state);});
 },90000);
}
function observe(constructor){
 var prototype=constructor&&constructor.prototype;if(!prototype)return;
 var start=prototype.startPlayer,stop=prototype.stop;
 if(typeof start!=='function'||typeof stop!=='function'||wrapped.has(start))return;
 var begin=new Proxy(start,{apply:function(target,owner,args){
  var previous=states.get(owner),info=args[0];
  if(info&&String(info.roomId)===room){var state=previous||{attempts:[],last:0};if(state.info!==info)state.confirmed=false;state.info=info;state.version=(state.version||0)+1;states.set(owner,state);renewal(owner,state);
   var initialVersion=state.version;
   setTimeout(function(){
    if(initialRetries||states.get(owner)!==state||state.version!==initialVersion||state.confirmed||!freeRoom(owner)||!owner.player||owner.player.info!==state.info||location.pathname.replace(/\/$/,'')!=='/'+room)return;
    var video=document.getElementById('hy-video'),played=0;
    if(video&&video.played)for(var i=0;i<video.played.length;i++)played+=video.played.end(i)-video.played.start(i);
    if(played>0||video&&typeof g.__isActiveMseVideo==='function'&&g.__isActiveMseVideo(video))return;
    initialRetries++;
    try{if(typeof owner.start==='function')owner.start(state.info);else owner.startPlayer(state.info);__host(JSON.stringify({op:'log',value:'UserScript Huya original free-room SDK initial startup retry'}));}catch(ignore){}
   },12000);
  }
  return Reflect.apply(target,owner,args);
 }});
 var end=new Proxy(stop,{apply:function(target,owner,args){
  var state=states.get(owner),retry=false,version=state&&state.version;
  try{
   var active=owner.player,video=document.getElementById('hy-video');
   var played=0;if(video&&video.played)for(var i=0;i<video.played.length;i++)played+=video.played.end(i)-video.played.start(i);
   if(state&&active&&active.info===state.info&&played>=10)state.confirmed=true;
   retry=args[0]==='FlvUrlUnavailable'&&freeRoom(owner)&&state&&active&&active.info===state.info&&String(state.info.roomId)===room&&state.confirmed&&typeof g.__isActiveMseVideo==='function'&&g.__isActiveMseVideo(video);
  }catch(ignore){}
  if(state)version=++state.version;
  var result=Reflect.apply(target,owner,args);
  if(retry){
   var now=Date.now();state.attempts=state.attempts.filter(function(t){return now-t<600000;});
   if(now-state.last>=1000&&state.attempts.length<8){
    state.last=now;state.attempts.push(now);
    setTimeout(function(){
     if(states.get(owner)!==state||state.version!==version||!freeRoom(owner)||location.pathname.replace(/\/$/,'')!=='/'+room)return;
     try{if(typeof owner.start==='function')owner.start(state.info);else owner.startPlayer(state.info);__host(JSON.stringify({op:'log',value:'UserScript Huya original free-room SDK reconnect'}));}catch(ignore){}
    },250);
   }
  }
  return result;
 }});
 wrapped.add(begin);prototype.startPlayer=begin;prototype.stop=end;
}
['H5Player','HYPlayer'].forEach(function(name){var descriptor=Object.getOwnPropertyDescriptor(g,name),value=g[name];
 if(descriptor&&!descriptor.configurable){observe(value);return;}
 try{Object.defineProperty(g,name,{configurable:true,enumerable:true,get:function(){return value;},set:function(next){value=next;observe(next);}});observe(value);}catch(ignore){}
});
})(globalThis);
