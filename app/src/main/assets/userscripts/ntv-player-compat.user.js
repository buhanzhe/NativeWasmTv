// ==UserScript==
// @name 播放器站点兼容
// @version 1.0.0
// @match *://ady.dongyingnews.cn/*
// @match *://api2019.gxtv.cn/*
// @match *://apphhplushttps.sxrtv.com/*
// @match *://arabic.cgtn.com/*
// @match *://bb-share.wifiwx.com/*
// @match *://bb-www.wifiwx.com/*
// @match *://bk.zzyxxw.com/*
// @match *://cjy.jptnews.cn/*
// @match *://cnzz.com/*
// @match *://dcs.conac.cn/*
// @match *://dyhhplus.sxrtv.com/*
// @match *://espanol.cgtn.com/*
// @match *://francais.cgtn.com/*
// @match *://frontendyc.0515yc.cn/*
// @match *://h5.qyrb.cn/*
// @match *://hm.baidu.com/*
// @match *://iapp.aianqiu.cn/*
// @match *://iapp.fcrmt.cn/*
// @match *://iapp.grxrmtzx.com/*
// @match *://iapp.laoshanrongmei.cn/*
// @match *://iapp.tzrmt.com/*
// @match *://iapp.wstvs.com/*
// @match *://iapp.zichuanrongmei.cn/*
// @match *://idclive.hljtv.com/*
// @match *://jkwg.ahbztv.com/*
// @match *://live.wifiwx.com/*
// @match *://m-jingzhou.cjyun.org/*
// @match *://m-live.cctvnews.cctv.com/*
// @match *://m-suizhou.cjyun.org/*
// @match *://m.gdtv.cn/*
// @match *://m.hbdangyangwang.cn/*
// @match *://m.miguvideo.com/*
// @match *://m.snrtv.com/*
// @match *://mapi.ngcz.tv/*
// @match *://news.cctv.com/*
// @match *://o.alicdn.com/*
// @match *://r.img.cctvpic.com/*
// @match *://russian.cgtn.com/*
// @match *://s1.hdslb.com/*
// @match *://static.yft.ndoe.cn/*
// @match *://tc.hnntv.cn/*
// @match *://tv.cctv.com/*
// @match *://tv.gxtv.cn/*
// @match *://wap.zzyxxw.com/*
// @match *://web.guangdianyun.tv/*
// @match *://www.0515yc.cn/*
// @match *://www.aba-news.com.cn/*
// @match *://www.ahbztv.com/*
// @match *://www.bilibili.com/*
// @match *://www.btime.com/*
// @match *://www.cctv.com/*
// @match *://www.cditv.cn/*
// @match *://www.cgtn.com/*
// @match *://www.csztv.com/*
// @match *://www.cztv.com/*
// @match *://www.dbw.cn/*
// @match *://www.gdtv.cn/*
// @match *://www.gzstv.com/*
// @match *://www.hbnews.net/*
// @match *://www.hkstv.tv/*
// @match *://www.hnntv.cn/*
// @match *://www.iyb983.cn/*
// @match *://www.jlntv.cn/*
// @match *://www.jnnews.tv/*
// @match *://www.jxntv.cn/*
// @match *://www.lcxw.cn/*
// @match *://www.mgtv.com/*
// @match *://www.ngcz.tv/*
// @match *://www.nmtv.cn/*
// @match *://www.ntjoy.com/*
// @match *://www.sgmsw.cn/*
// @match *://www.sxtygdy.com/*
// @match *://www.yangshipin.cn/*
// @match *://xiangyang.cjyun.org/*
// @match *://yangshipin.cn/*
// @match *://zqh5.xjrb.com/*
// @run-at document-start
// @grant none
// ==/UserScript==
(function(g){
'use strict';
var api=g.__ntvPlayerObserver;if(!api)return;var pageConfig=__host(JSON.stringify({op:'pageConfig'}))||{};if(pageConfig.originalMediaPipeline)return;
var source=api.source,probe=api.probe,instance=api.instance,wrap=api.wrap,methods=api.methods,observedLive=new WeakSet(),interval=g.setInterval,clear=g.clearInterval,observe=g.__observePlayers;
function observeSelectedLive(player,kind){
 if(kind!=='H5Player'||typeof interval!=='function'||observedLive.has(player))return;
 var page;try{page=new URL(location.href);}catch(ignore){return;}if(page.hostname!=='m.miguvideo.com'||!/^\/m\/liveDetail\/\d+\/?$/.test(page.pathname))return;
 observedLive.add(player);var count=0,last='',timer=interval(function(){try{if(typeof g.__selectionIsPending==='function'&&g.__selectionIsPending()){if(++count>=90&&typeof clear==='function')clear(timer);return;}var current=new URL(location.href),match=current.pathname.match(/^\/m\/liveDetail\/(\d+)\/?$/),info=player.playerInfo,normal=info&&info.normalPlayerInfo;
  if(match&&info&&String(info.cid)===match[1]&&info.playType==='live'&&normal&&typeof normal.url==='string'&&/^https?:\/\//i.test(normal.url)&&normal.url!==last){last=normal.url;probe(last,'H5Player selected live');}
 }catch(ignore){}if(++count>=90&&typeof clear==='function')clear(timer);},1000);
}
function joinDbwLiveEdge(player,kind){
 if(kind!=='Player'||location.hostname!=='www.dbw.cn')return;
 var attempts=0;function install(){try{
  var plugin=typeof player.getPlugin==='function'?player.getPlugin('hls'):player.plugins&&player.plugins.hls,core=plugin&&plugin.hls,load=core&&core._loadSegment;
  if(attempts===0)console.log('DBW original HLS observer: plugin='+!!plugin+', core='+!!core);
  if(!core||typeof load!=='function'){if(++attempts<30)setTimeout(install,100);return;}
  if(typeof core.on==='function')core.on('error',function(error){console.error('DBW original HLS error: '+String(error&&(error.errorMessage||error.message||error.msg)||error));});
  if(methods.has(load))return;var joined=false;
  var wrappedLoad=new Proxy(load,{apply:function(target,receiver,args){
   var list=receiver&&receiver._playlist,source=receiver&&receiver.config&&receiver.config.url;
   if(!joined&&receiver===core&&receiver.isLive&&list&&!list.hadSegmentLoaded&&list.nbSegments>=3&&list.nbSegments<=200&&typeof list.setNextSegmentByIndex==='function'){
    var url=new URL(String(source),location.href);
    if(url.hostname==='idclive.hljtv.com'&&/^https?:$/.test(url.protocol)){
     // The original manifest retains an expired oldest entry. Select an
     // existing near-live fragment through the SDK; retain its URLs and MSE.
     list.setNextSegmentByIndex(list.nbSegments-1);joined=true;console.log('DBW selected original live-edge fragment');
    }
   }
   return Reflect.apply(target,receiver,args);
  }});methods.add(wrappedLoad);core._loadSegment=wrappedLoad;
 }catch(ignore){}}install();
}
var taiyuanTimer=0;
// Wuxi's original ESM entry keeps Hls private to its imported namespace. Read
// that already evaluated namespace through the normal module cache; do not
// rewrite the entry, disable MSE, or construct a replacement SDK. Its real
// loadSource call remains responsible for choosing and signing the source.
var wuxiModules=new Set();
if(pageConfig.headless&&typeof g.__moduleEval==='function'){
 var evaluateModule=g.__moduleEval;
 g.__moduleEval=new Proxy(evaluateModule,{apply:function(target,receiver,args){
  var result=Reflect.apply(target,receiver,args);
  // Bootstrap still has about:blank. Check the real initialized page when
  // its original entry executes, rather than skipping the observer too early.
  if(location.hostname!=='bb-share.wifiwx.com'||!/^\/wxbb\/share\/live\/$/.test(location.pathname))return result;
  try{var owner=new URL(String(args[1])),match=String(args[0]).match(/import\s*\{\s*H\s+as\s+[$\w]+\s*\}\s*from\s*["'](\.\/hls\.[a-z0-9]+\.js)["']/);
   if(owner.hostname==='bb-www.wifiwx.com'&&/^\/t\/1\/5\/assets\/live\.[a-z0-9]+\.js$/.test(owner.pathname)&&match){
    var dependency=new URL(match[1],owner.href).href;
    if(!wuxiModules.has(dependency)){wuxiModules.add(dependency);g.__IntrinsicPromise.resolve(result).then(function(){return import(dependency);}).then(function(namespace){
     var Hls=namespace.H,prototype=Hls&&Hls.prototype;if(typeof Hls!=='function'||!Hls.Events||!prototype||typeof prototype.loadSource!=='function'||typeof prototype.attachMedia!=='function')return;
     var descriptor=Object.getOwnPropertyDescriptor(prototype,'loadSource');if(!descriptor||!descriptor.writable||methods.has(descriptor.value))return;
     var observed=new Proxy(descriptor.value,{apply:function(load,player,values){var returned=Reflect.apply(load,player,values);if(values.length)probe(values[0],'Wuxi original ESM Hls.loadSource');return returned;}});
     methods.add(observed);descriptor.value=observed;Object.defineProperty(prototype,'loadSource',descriptor);
    }).catch(function(){console.log('模块播放器观察未就绪，保留原网页流程');});}
   }
  }catch(ignore){}return result;
 }});
}
// This room's Video.js module is private to webpack. Read its public selected
// source from the actual DOM player; never execute private factories or scan
// the room catalogue. Route, rendered room number and SDK configuration agree.
function wuxiMobileSource(assignedNode,assignedPlayer){try{
 if(!pageConfig.headless||location.hostname!=='live.wifiwx.com'||!/^\/m2\/?$/.test(location.pathname))return '';
 var page=new URL(location.href),route=new URL(page.hash.slice(1),page.origin),id=route.searchParams.get('id'),outer=page.searchParams.get('id');
 if(route.pathname!=='/pkgA/pages/live/live'||!/^\d{1,15}$/.test(id||'')||outer!==null&&outer!==id)return '';
 var root=document.getElementById('live-page-wrap');if(!root||!new RegExp('房间号[:：]'+id+'(?:[^0-9]|$)').test(root.textContent.replace(/\s+/g,'')))return '';
 var nodes=root.querySelectorAll('video,video-js,.video-js');if(nodes.length>16)return '';
 var players=[],candidates=[];Array.from(nodes).forEach(function(node){
  var player=node.player;if(!player||players.indexOf(player)>=0||typeof player.currentSource!=='function'||typeof player.options!=='function'||typeof player.el!=='function')return;
  players.push(player);if(player.isDisposed&&player.isDisposed())return;
  var early=node===assignedNode&&player===assignedPlayer&&player.tag===node&&node.isConnected&&root.querySelectorAll('video').length===1;
  if(assignedNode&&!early)return;
  if(!root.contains(player.el())&&!early)return;
  var selected=player.currentSource(),options=player.options(),sources=options&&options.sources;
  // Video.js assigns its real tag before inserting its wrapper and filling
  // currentSource. The constructor's sole source already came from the room
  // API; at that precise assignment boundary control layout need not finish.
  if(early&&(!selected||!selected.src)&&Array.isArray(sources)&&sources.length===1)selected=sources[0];
  if(!selected||!Array.isArray(sources)||sources.length!==1||!sources[0]||selected.src!==sources[0].src||!/^application\/(?:x-mpegurl|vnd\.apple\.mpegurl)$/i.test(selected.type||''))return;
  var url=new URL(selected.src,location.href);if(/^https?:$/.test(url.protocol))candidates.push(url.href);
 });return players.length===1&&candidates.length===1?candidates[0]:'';
 }catch(ignore){return '';}}
g.__wuxiMobileResolvedSource=function(){return wuxiMobileSource(null,null);};
g.__bindWuxiMobilePlayer=function(node){
 if(!pageConfig.headless||location.hostname!=='live.wifiwx.com'||!/^\/m2\/?$/.test(location.pathname)||node.tagName!=='VIDEO'||Object.getOwnPropertyDescriptor(node,'player'))return;
 Object.defineProperty(node,'player',{configurable:true,enumerable:true,set:function(player){
  // Restore the SDK's ordinary writable data property before observing it.
  Object.defineProperty(node,'player',{value:player,writable:true,configurable:true,enumerable:true});
  try{if(!player||player.tag!==node)return;
   var url=wuxiMobileSource(node,player);if(url&&url!==lastWuxiMobileSource){lastWuxiMobileSource=url;probe(url,'Wuxi original room Video.js constructor source');}
  }catch(ignore){}
 }});
};
var lastMgtvSource='',mgtvTimer=0,mgtvAttempts=0,yanbianTimer=0,yanbianAttempts=0,lastYanbianSource='',huaibeiTimer=0,huaibeiAttempts=0,lastHuaibeiSource='',jiningTimer=0,jiningAttempts=0,lastJiningSource='',wuxiMobileTimer=0,wuxiMobileAttempts=0,lastWuxiMobileSource='';
function selectedWuxiMobile(){try{var url=g.__wuxiMobileResolvedSource();if(url&&url!==lastWuxiMobileSource){lastWuxiMobileSource=url;probe(url,'Wuxi original room Video.js selected source');}}catch(ignore){}}
function selectedYanbian(){try{var url=g.__yanbianResolvedSource&&g.__yanbianResolvedSource();if(url&&url!==lastYanbianSource){lastYanbianSource=url;probe(url,'Yanbian selected SDK live source');}}catch(ignore){}}
function selectedHuaibei(){try{var url=g.__huaibeiResolvedSource&&g.__huaibeiResolvedSource();if(url&&url!==lastHuaibeiSource){lastHuaibeiSource=url;probe(url,'Huaibei selected SDK live source');}}catch(ignore){}}
function selectedJining(){try{var url=g.__jiningResolvedSource&&g.__jiningResolvedSource();if(url&&url!==lastJiningSource){lastJiningSource=url;probe(url,'Jining selected SDK live source');}}catch(ignore){}}
function selectedMgtv(){try{
 if(new URL(location.href).hostname!=='www.mgtv.com')return;
 var player=g.mgtvLivePlayer,hls=g.hhls,kernel=player&&player.liveKernel,url=hls&&hls.url;
 if(pageConfig.selectionRequired){url=typeof g.__selectedMgtvSource==='function'?g.__selectedMgtvSource(player,hls):'';if(!url)return;}
 if(typeof g.__selectionIsPending==='function'&&g.__selectionIsPending())return;
 // The original live loader has already resolved this selected source. Match
 // both independently maintained SDK fields; never derive a CDN/auth URL.
 if(player&&player.isLive===true&&kernel&&typeof url==='string'&&url===kernel.src&&url!==lastMgtvSource&&/^https?:\/\//i.test(url)&&/\.m3u8(?:[?#]|$)/i.test(url)){
  lastMgtvSource=url;probe(url,'MGTV selected live HLS');
 }
 }catch(ignore){}}
g.__observePlayers=function(){observe();selectedMgtv();selectedYanbian();selectedHuaibei();selectedJining();selectedWuxiMobile();
 if(!taiyuanTimer&&location.hostname==='www.sxtygdy.com'&&pageConfig.selectionRequired){var taiyuanAttempts=0;taiyuanTimer=interval(function(){var player=g.cmcPlayer;if(player&&typeof player.playerPlay==='function'){instance(player,'CmcMediaPlayer');clear(taiyuanTimer);}else if(++taiyuanAttempts>=180)clear(taiyuanTimer);},100);}
 if(!mgtvTimer&&location.hostname==='www.mgtv.com')mgtvTimer=interval(function(){selectedMgtv();if(++mgtvAttempts>=180)clear(mgtvTimer);},1000);
 if(!yanbianTimer&&location.hostname==='www.iyb983.cn'&&/^\/fuesf\/TV\/\d+\/?$/.test(location.pathname))yanbianTimer=interval(function(){selectedYanbian();if(++yanbianAttempts>=90)clear(yanbianTimer);},1000);
 if(!huaibeiTimer&&location.hostname==='www.hbnews.net'&&/^\/fuesf\/TV\/?$/.test(location.pathname))huaibeiTimer=interval(function(){selectedHuaibei();if(++huaibeiAttempts>=90)clear(huaibeiTimer);},1000);
 if(!jiningTimer&&location.hostname==='www.jnnews.tv'&&/^\/fuesf\/TV\/?$/.test(location.pathname))jiningTimer=interval(function(){selectedJining();if(++jiningAttempts>=90)clear(jiningTimer);},1000);
 if(!wuxiMobileTimer&&pageConfig.headless&&location.hostname==='live.wifiwx.com'&&/^\/m2\/?$/.test(location.pathname))wuxiMobileTimer=interval(function(){selectedWuxiMobile();if(++wuxiMobileAttempts>=90)clear(wuxiMobileTimer);},1000);
};
// The live loader resolves its source asynchronously, after script execution.
// A declarative channel-click page retains its existing selection pipeline;
// do not treat the loader's previous channel as the newly selected one.

g.__onPlayerInstance=function(player,kind){if(typeof g.__configureTaiyuanPlayer==='function')g.__configureTaiyuanPlayer(player,kind);joinDbwLiveEdge(player,kind);observeSelectedLive(player,kind);};
g.__onPlayerSource=function(receiver,kind,name,args){if(kind==='cyberplayer'&&name==='setup'&&typeof g.__selectedNgczSource==='function')g.__selectedNgczSource(args[0]);if(name==='playerPlay'&&typeof g.__selectedTaiyuanSource==='function')g.__selectedTaiyuanSource(receiver,args[0]);};
g.__onPlayerOptions=function(kind,args){try{var options=args[0];if(kind==='Player'&&location.hostname==='www.dbw.cn'&&options&&typeof options==='object'&&new URL(String(options.url),location.href).hostname==='idclive.hljtv.com')options.hls=Object.assign({},options.hls,{loadTimeout:30000});}catch(ignore){}};
})(globalThis);
