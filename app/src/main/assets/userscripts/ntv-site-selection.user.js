// ==UserScript==
// @name 直播站点选台与兼容
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
/* Site profiles use DOM actions, never hardcoded stream URLs or fake auth results. */
(function(g){
'use strict';
var host=function(o){return __host(JSON.stringify(o));},profiles=[
 {name:'bilibili-vod',hosts:['www.bilibili.com'],play:[]},
 {name:'ngcz',hosts:['www.ngcz.tv'],play:[]},
 {name:'taiyuan',hosts:['www.sxtygdy.com'],play:[]},
 {name:'cctv-original',hosts:['tv.cctv.com','news.cctv.com'],play:[]},
 {name:'yangshipin-original',hosts:['yangshipin.cn','www.yangshipin.cn'],play:[]},
 {name:'cctv-news',hosts:['m-live.cctvnews.cctv.com'],play:['.custom-prism-player-ready .custom-play-btn','.custom-prism-player-ready .custom-prism-play-control']},
 {name:'mgtv',hosts:['www.mgtv.com'],play:[]},
 {name:'shanxi',hosts:['apphhplushttps.sxrtv.com'],play:[]},
 {name:'hainan',hosts:['tc.hnntv.cn'],play:[]},
 {name:'guangxi',hosts:['tv.gxtv.cn'],play:['.prism-big-play-btn','.vjs-big-play-button','.rainbowplayer-play']},
 {name:'shaoguan-entry',hosts:['www.sgmsw.cn'],play:['.vjs-big-play-button'],selection:function(url){var parsed=new URL(url);return /^\/mobile\/tv\/?$/.test(parsed.pathname)?channelEntryScript('shaoguan'):'';}},
 {name:'yunxiao-entry',hosts:['bk.zzyxxw.com','wap.zzyxxw.com'],play:['.vjs-big-play-button'],selection:function(url){return new URL(url).pathname==='/'?channelEntryScript('yunxiao'):'';}},
 {name:'migu',hosts:['m.miguvideo.com'],play:['.player-start-btn']},
 {name:'yft',hosts:['static.yft.ndoe.cn'],play:['.vjs-big-play-button']},
 {name:'liaocheng',hosts:['www.lcxw.cn'],play:[],selection:function(url){var parsed=new URL(url),target=parsed.searchParams.get('tag');if(!/^\/kands\/?$/.test(parsed.pathname)||target===null||target==='0')return '';return "(function(){var target="+JSON.stringify(target)+",tries=0;function choose(){var button=document.getElementById('logo'+target);if(button&&typeof initVideoPlayer==='function'&&typeof DPlayer==='function'){button.click();return;}if(++tries<80)setTimeout(choose,100);}choose();})()";}},
 {name:'cztv',hosts:['www.cztv.com'],play:[]},
 {name:'cgtn',hosts:['www.cgtn.com','arabic.cgtn.com','espanol.cgtn.com','francais.cgtn.com','russian.cgtn.com'],play:['.vjs-big-play-button','.video-play','.play-button','.jw-icon-display','.jw-display-icon-display']},
 {name:'nmtv',hosts:['www.nmtv.cn'],play:['.vjs-big-play-button','.video-js']},
 {name:'yancheng-live',hosts:['www.0515yc.cn'],play:[]},
 {name:'jilin',hosts:['www.jlntv.cn'],play:['.vjs-big-play-button']},
 {name:'native-videojs',hosts:['www.csztv.com','www.ntjoy.com','zqh5.xjrb.com','h5.qyrb.cn','www.hkstv.tv','www.gzstv.com','www.aba-news.com.cn','www.hnntv.cn'],play:['.vjs-big-play-button']},
 {name:'yanbian',hosts:['www.iyb983.cn'],play:['.xgplayer-start']},
 {name:'huaibei',hosts:['www.hbnews.net'],play:['.xgplayer-start']},
 {name:'jining',hosts:['www.jnnews.tv'],play:['.xgplayer-start']},
 {name:'wuxi-mobile',hosts:['live.wifiwx.com'],play:[]},
 {name:'bozhou',hosts:['www.ahbztv.com'],play:['.vjs-big-play-button']},
 {name:'guangdianyun',hosts:['web.guangdianyun.tv'],play:['.prism-big-play-btn','.vjs-big-play-button']},
 {name:'gdtv',hosts:['www.gdtv.cn','m.gdtv.cn'],play:['.vjs-big-play-button','.dplayer-play-icon','.playIco']}
 ,{name:'snrtv-mobile',hosts:['m.snrtv.com'],play:[],selection:function(url){var parsed=new URL(url),match=parsed.hash.match(/^#(star|nl|\d+)(?:\?|$)/);if(!/\/snrtv_tv\//.test(parsed.pathname)||!match)return '';return "(function(){var target="+JSON.stringify(match[1])+",tries=0;function choose(){var root=document.getElementById('programSwiper'),swiper=root&&root.swiper,slides=root&&root.querySelectorAll('.swiper-slide');if(swiper&&typeof swiper.slideTo==='function'&&slides){for(var i=0;i<slides.length;i++){var slide=slides[i];if(slide.getAttribute('data-num')===target&&!slide.classList.contains('swiper-slide-duplicate')){slide.click();swiper.slideTo(i,0,true);return;}}}if(++tries<80)setTimeout(choose,100);}choose();})()";}}
 ,{name:'iqilu-rongmei',hosts:['ady.dongyingnews.cn','iapp.aianqiu.cn','iapp.fcrmt.cn','iapp.grxrmtzx.com','iapp.laoshanrongmei.cn','iapp.tzrmt.com','iapp.wstvs.com','iapp.zichuanrongmei.cn'],play:['.vjs-big-play-button']}
 ,{name:'cjyun',hosts:['m-jingzhou.cjyun.org','m-suizhou.cjyun.org','xiangyang.cjyun.org','m.hbdangyangwang.cn','cjy.jptnews.cn'],play:['.vjs-big-play-button','.jw-icon-display']}
 ,{name:'cditv',hosts:['www.cditv.cn'],play:['.vjs-big-play-button','.dplayer-play-icon']}
 ,{name:'jxntv',hosts:['www.jxntv.cn'],play:['.prism-big-play-btn'],selection:function(url){var match=new URL(url).hash.match(/^#\/(?:jxtv(\d+)|(tcpd))$/);if(!match)return '';return "(function(){var tries=0;function choose(){var icon=document.querySelector('.item .icon.tv"+(match[1]?+match[1]:10)+"');if(icon&&icon.parentElement)icon.parentElement.click();else if(++tries<80)setTimeout(choose,100);}choose();})()";}}
];
// Some original playlist URLs now open a channel directory. Activate only its
// real, named TV entry; never guess a stream or choose a neighbouring radio tile.
function channelEntryScript(kind){
 return "(function(){var kind="+JSON.stringify(kind)+",tries=0;function choose(){var links=document.querySelectorAll('a[href]');for(var i=0;i<links.length&&i<256;i++){var link=links[i],label=link.textContent.replace(/\\s+/g,'');if(label!==(kind==='shaoguan'?'韶关新闻综合':'看电视'))continue;var target;try{target=new URL(link.getAttribute('href'),location.href);}catch(ignore){continue;}if(target.origin!==location.origin||!/^https?:$/.test(target.protocol))continue;if(kind==='shaoguan'?!/^\\/mobile\\/tvinfo$/.test(target.pathname)||!target.searchParams.get('id'):!/^\\/(?:live|ActivityLive\\/get_cms_live)$/i.test(target.pathname))continue;link.click();return;}if(++tries<60)setTimeout(choose,100);}choose();})()";
}
if(typeof g.__host!=='function'){var run=function(){try{var item=profiles.find(function(p){return p.hosts.indexOf(location.hostname)>=0;});if(item&&item.selection){var action=item.selection(location.href);if(action)(0,eval)(action);}}catch(e){console.warn('站点选台: '+e);}};if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',run,{once:true});else run();return;}
var auto=g.__auto,selected=false,profile=null,config=null,taiyuanSelection=null,ngczSelection=null;
// This TV SDK appends audio before constructing its video SourceBuffer. Do not
// let that transient audio-only state publish the native playback master.
g.__mseVideoRequired=function(){return !!(profile&&profile.name==='taiyuan'&&/^\/23\/23mbdszb\//.test(location.pathname));};
g.__configureTaiyuanPlayer=function(player,kind){try{
 if(!g.__mseVideoRequired()||(kind!=='CmcPCPlayer'&&kind!=='CmcPlayer'))return;
 var data=player&&player.h5Player&&player.h5Player.PlayerData;
 // Configure the original player before its asynchronous startup creates Hls.
 if(data&&data.liveSyncDurationCount===2){data.liveSyncDurationCount=4;console.log('太原直播：原播放器安全余量调整为4分片');}
}catch(ignore){}};
// A click starts an asynchronous signed-URL request. The old player can still
// append media meanwhile; authorize only the URL the selected tile's callback
// actually passes to the original SDK, without constructing its token or URL.
g.__selectedTaiyuanSource=function(player,options){try{
 if(!profile||profile.name!=='taiyuan'||!config||!config.selectionRequired||!taiyuanSelection||taiyuanSelection.ready)return '';
 var node=taiyuanSelection.node,movie=taiyuanSelection.movie;
 if(!node.isConnected||!node.classList.contains('hover')||player!==g.cmcPlayer||options!==g.flashvars)return '';
 var base=new URL(movie.liveUrl,location.href),url=new URL(options.url,location.href),prefix=base.pathname.replace(/\/$/,'')+'/'+movie.liveId+'/'+movie.liveRate+'/';
 if(!/^https?:$/.test(url.protocol)||url.origin!==base.origin||url.pathname.indexOf(prefix)!==0||!url.pathname.endsWith('.m3u8'))return '';
 // The original SDK's two-fragment live margin can request an edge object
 // while it is still being populated. Use its own supported live-sync state
 // before playerPlay creates Hls; do not change the selected source or clock.
 var actual=player.cmcPlayer&&player.cmcPlayer.h5Player,data=actual&&actual.PlayerData;
 if(data&&data.liveSyncDurationCount===2)data.liveSyncDurationCount=4;
 selected=true;taiyuanSelection.ready=true;host({op:'selectionReady',target:url.href,provider:'taiyuan-live'});return url.href;
 }catch(ignore){return '';}};
var ngczAjaxMethods=new WeakSet();
function rememberNgczProgramme(data,id){
 if(!ngczSelection||ngczSelection.ready||String(id)!==ngczSelection.id||!Array.isArray(data)||data.length>2000)return;
 data.forEach(function(item){var stream=item&&item.channel_stream&&item.channel_stream[0],url=stream&&stream.m3u8;if(item&&Number(item.now_play)===1&&typeof url==='string'&&/^https?:\/\//i.test(url))ngczSelection.urls.add(url);});
}
function observeNgczAjax(){try{
 if(!profile||profile.name!=='ngcz'||!g.jQuery||typeof g.jQuery.ajax!=='function'||ngczAjaxMethods.has(g.jQuery.ajax))return;
 var original=g.jQuery.ajax,observed=new Proxy(original,{apply:function(target,receiver,args){
  var options=args[0];if(options&&typeof options==='object'&&typeof options.success==='function')try{
   var request=new URL(options.url,location.href);
   if(request.protocol==='https:'&&request.hostname==='mapi.ngcz.tv'&&request.pathname==='/api/v1/program.php'){
    var callback=options.success,id=options.data&&options.data.channel_id,copy=Object.assign({},options);
    copy.success=function(data){
     // An older channel's late response must not repaint the newly selected
     // programme list or restart its old player after the user's click.
     if(ngczSelection&&String(id)!==ngczSelection.id)return;
     try{rememberNgczProgramme(data,id);}catch(ignore){}
     var result=callback.apply(this,arguments);
     try{var programme=document.querySelector('#programList .active');if(programme)g.__selectedNgczSource({file:programme.getAttribute('data-url')});}catch(ignore){}
     return result;
    };args=args.slice();args[0]=copy;
   }
  }catch(ignore){}return Reflect.apply(target,receiver,args);
 }});ngczAjaxMethods.add(observed);g.jQuery.ajax=observed;
 }catch(ignore){}}
g.__selectedNgczSource=function(options){try{
 if(!profile||profile.name!=='ngcz'||!ngczSelection||ngczSelection.ready)return '';
 var node=ngczSelection.node,url=String(options&&options.file||'');
 if(!node.isConnected||document.querySelector('#channelList .active')!==node||!ngczSelection.urls.has(url))return '';
 ngczSelection.ready=true;host({op:'selectionReady',target:url,provider:'ngcz-live'});host({op:'media',value:url});return url;
 }catch(ignore){return '';}};
// The TV directory chooses the first future EPG item before the day's first
// programme. Keep the real AliPlayer and switch only that initial, unstarted
// item to the exact live URL returned for the currently selected channel.
function repairYanchengInitialProgramme(pageUrl){
 if(!profile||profile.name!=='yancheng-live'||!/\/folder156\/?$/.test(new URL(pageUrl).pathname))return;
 var player=null,playerReady=false,revision=0,detail=null,attempts=0,done=false,timer=null;
 function choose(){
  if(done||!player||!playerReady||!detail)return;
  if(config&&config.selectionRequired&&!selected)return;
  var channel=document.querySelector('#tv-title li.current'),programme=document.querySelector('.channel-box.cur');
  if(!channel||!programme||String(channel.getAttribute('value'))!==String(detail.id))return;
  var label=channel.querySelector('p');if(!label||label.textContent.trim()!==detail.title)return;
  // Explicit replay and an already running programme remain the page's choice.
  if(programme.getAttribute('data-play')!==null)return;
  if(typeof player.loadByUrl!=='function')return;
  done=true;selected=true;host({op:'selectionReady',target:detail.hls});
  player.loadByUrl(detail.hls);host({op:'log',value:'盐城直播：首个节目尚未开始，使用当前频道接口返回的直播源'});
 }
 var value=g.Aliplayer;
 function wrapConstructor(original){if(typeof original!=='function')return original;return new Proxy(original,{construct:function(target,args,newTarget){
  var current=++revision,copy=args.slice(),callback=copy[1];playerReady=false;
  copy[1]=function(instance){if(current===revision){player=instance;playerReady=true;setTimeout(choose,0);}if(typeof callback==='function')return callback.apply(this,arguments);};
  var instance=Reflect.construct(target,copy,newTarget);if(current===revision)player=instance;return instance;
 }});}
 Object.defineProperty(g,'Aliplayer',{configurable:true,enumerable:true,get:function(){return value;},set:function(v){value=wrapConstructor(v);}});
 if(value)value=wrapConstructor(value);
 var send=XMLHttpRequest.prototype.send;
 XMLHttpRequest.prototype.send=function(){this.addEventListener('load',function(){try{
  var target=new URL(this.responseURL,location.href);if(target.hostname!=='frontendyc.0515yc.cn'||target.pathname!=='/cloudlive-manage-mapi/api/topic/detail'||this.status!==200)return;
  var data=JSON.parse(this.responseText),camera=(data.topic_camera||[]).find(function(c){return c.is_default===1&&c.status===1;}),stream=camera&&(camera.streams||[]).find(function(s){return typeof s.hls==='string'&&/^https?:\/\//.test(s.hls);});
  if(!stream||String(data.id)!==target.searchParams.get('id'))return;
  detail={id:data.id,title:data.title,hls:stream.hls};setTimeout(choose,0);
 }catch(ignore){}});return send.apply(this,arguments);};
 timer=setInterval(function(){choose();if(done||++attempts>=100)clearInterval(timer);},200);
}
g.__selectionIsPending=function(){return !!(config&&config.selectionRequired&&!selected);};
var webpackRequire=null,tracedVue=null,tracedHandler=null;
var guangxiCrypto=new WeakSet(),guangxiFactories=new WeakSet(),guangxiPushes=new WeakSet();
function observeGuangxiCrypto(){try{
 var chunks=g.webpackJsonpDHYPlayer;if(!Array.isArray(chunks)||typeof chunks.push!=='function')return;
 function observe(value){var constructor=value&&(value.a||value.default),p=constructor&&constructor.prototype;
  if(!p||guangxiCrypto.has(p)||!['_decryptV2','_decryptV3','_swapBlocks','decrypt','reinit'].every(function(k){return typeof p[k]==='function';}))return;
  var original=p._decryptV2;
  // Match the audited SDK's V2 range, rather than guessing from a module ID.
  var source=Function.prototype.toString.call(original);
  if(source.indexOf('ModeOfOperation.ecb')<0||source.indexOf('byteLength-t-1')<0||source.indexOf('parseInt(t/2)')<0)return;
  p._decryptV2=new Proxy(original,{apply:function(target,receiver,args){var keys=[];
   try{
    var buffer=args[0],stride=args[1],key=receiver&&receiver._key;
    if(!(buffer instanceof ArrayBuffer)||!Number.isInteger(stride)||stride<0||!(key instanceof Uint8Array)||[16,24,32].indexOf(key.byteLength)<0)throw Error('AES block input');
    var start=Math.trunc(stride/2),length=Math.trunc((buffer.byteLength-start-1)/16)*16;
    if(length<=0||length>4*1024*1024||start+length>buffer.byteLength)throw Error('AES block range');
    var keyId=__binaryStore(key);keys.push(keyId);var dataId=__binaryStore(new Uint8Array(buffer,start,length));keys.push(dataId);
    var result=host({op:'aesBlocks',key:keyId,data:dataId});if(typeof result!=='string')throw Error('AES block adapter');
    var output=new Uint8Array(__binaryTake(result));if(output.byteLength!==length)throw Error('AES block output size');
    // Commit only after the whole range succeeds. The caller still performs
    // its original block swap, TS parsing, authentication and MSE delivery.
    new Uint8Array(buffer,start,length).set(output);return;
   }catch(ignore){}
   finally{keys.forEach(function(key){host({op:'discardBinary',key:key});});}
   return Reflect.apply(target,receiver,args);
  }});guangxiCrypto.add(p);console.log('广西原 SDK 标准 AES 分块已启用系统运算');
 }
 function factories(payload){var modules=payload&&payload[1];if(!modules||typeof modules!=='object')return;
  Object.keys(modules).slice(0,512).forEach(function(id){var original=modules[id];if(typeof original!=='function'||guangxiFactories.has(original))return;guangxiFactories.add(original);
   var source=Function.prototype.toString.call(original);if(source.indexOf('_decryptV2')<0||source.indexOf('ModeOfOperation.ecb')<0)return;
   // webworkify serializes factory.toString() into its real Worker program.
   // Preserve the original module text for that serializer; the intrinsic
   // Function.prototype.toString still retains normal Proxy/native branding.
   var wrapper=new Proxy(original,{get:function(target,key,receiver){if(key==='toString')return function(){return source;};return Reflect.get(target,key,receiver);},apply:function(target,receiver,args){var result=Reflect.apply(target,receiver,args);try{if(args[0])observe(args[0].exports);}catch(ignore){}return result;}});guangxiFactories.add(wrapper);modules[id]=wrapper;
  });
 }
 // This SDK's JSONP runtime only registers factories; it has no deferred-entry
 // payload. Observe the incoming real factory, without inventing an entry or
 // invoking the SDK's private require. Its original loader executes it normally.
 if(!guangxiPushes.has(chunks.push)){var original=chunks.push,wrapped=new Proxy(original,{apply:function(target,receiver,args){for(var i=0;i<args.length;i++)factories(args[i]);return Reflect.apply(target,receiver,args);}});guangxiPushes.add(wrapped);chunks.push=wrapped;}
 for(var i=0;i<chunks.length&&i<32;i++)factories(chunks[i]);
 }catch(ignore){}}
var configuredNativePlayers=new WeakSet(),nativeFactories=new WeakSet(),nativeFactoryCount=0,nativeCaptureAttempts=0;
// The ES-module Video.js build is not a Window export. It assigns the real
// player to its video before constructing the Html5 tech. Configure that
// instance's public options at this boundary; retain its source and callbacks.
function bindJilinVideo(node){
 if(node.tagName!=='VIDEO'||Object.getOwnPropertyDescriptor(node,'player'))return;
 var assigned;
 Object.defineProperty(node,'player',{configurable:true,enumerable:true,get:function(){return assigned;},set:function(player){
  assigned=player;
  try{
   if(!player||typeof player.options!=='function'||typeof player.tech!=='function'||typeof player.currentSrc!=='function'||!this.canPlayType('application/vnd.apple.mpegurl'))return;
   var options=player.options(),html=options&&(options.html5||(options.html5={}));if(!html)return;
   [options,html].forEach(function(o){(o.vhs||(o.vhs={})).overrideNative=false;(o.hls||(o.hls={})).overrideNative=false;});
   Object.defineProperty(this,'player',{value:player,writable:true,configurable:true,enumerable:true});
   console.log('播放器实例已启用设备原生HLS能力');
  }catch(ignore){}
 }});
}
function configureSiteElementFactory(){
 // Vue also creates/clones videos through parsed template fragments. Observe
 // native DOM wrappers, without replacing any document factory or SDK export.
 if(profile&&profile.name==='wuxi-mobile'&&config&&config.headless&&typeof g.__bindWuxiMobilePlayer==='function')g.__siteWrapNode=g.__bindWuxiMobilePlayer;
 else if(profile&&(profile.name==='jilin'||profile.name==='native-videojs'&&config&&config.headless))g.__siteWrapNode=bindJilinVideo;
 else if(g.__siteWrapNode===bindJilinVideo)delete g.__siteWrapNode;
}
function nativeVideoOptions(value){try{var candidates=[value];if(value&&typeof value==='object'){candidates.push(value.default,value.a);}candidates.forEach(function(player){if(typeof player!=='function'||!player.VERSION||typeof player.getTech!=='function'||!player.options||configuredNativePlayers.has(player))return;if(!document.createElement('video').canPlayType('application/vnd.apple.mpegurl'))return;configuredNativePlayers.add(player);var options=player.options,html=options.html5||(options.html5={});[options,html].forEach(function(o){(o.vhs||(o.vhs={})).overrideNative=false;(o.hls||(o.hls={})).overrideNative=false;});console.log('播放器已启用设备原生HLS能力');});}catch(ignore){}}
function observeNativeVideojs(){try{
 if(!webpackRequire&&nativeCaptureAttempts++<12&&Array.isArray(g.webpackJsonp)){var id='ntv-native-hls-observer',modules={};modules[id]=function(module,exports,require){webpackRequire=require;};g.webpackJsonp.push([[id],modules,[[id]]]);}
 if(!webpackRequire)return;var cache=webpackRequire.c||{},modules=webpackRequire.m||{};
 Object.keys(cache).slice(0,2048).forEach(function(id){nativeVideoOptions(cache[id].exports);});
 Object.keys(modules).slice(0,2048).forEach(function(id){var original=modules[id];if(cache[id]||typeof original!=='function'||nativeFactories.has(original)||nativeFactoryCount>=1024)return;var wrapper=new Proxy(original,{apply:function(target,receiver,args){var result=Reflect.apply(target,receiver,args);if(args[0])nativeVideoOptions(args[0].exports);return result;}});nativeFactories.add(wrapper);nativeFactoryCount++;modules[id]=wrapper;});
 nativeVideoOptions(g.videojs);
 }catch(ignore){}}
// The mobile page deliberately swallows Vue lifecycle exceptions. Retain its
// handler, but record the real error so missing DOM APIs can be diagnosed.
var deferredShanxiReports=new WeakSet();
function observeShanxiReport(){
 if(!config||!config.headless||typeof g.learnCountryDataAdd!=='function'||deferredShanxiReports.has(g.learnCountryDataAdd))return;
 var original=g.learnCountryDataAdd;
 // The site's async loader returns undefined. Wait for its real dependencies
 // before invoking its unchanged reporting function and actual network request.
 function readyReport(){
  var receiver=this,args=arguments,deadline=Date.now()+8000;
  function run(){
   if(typeof g.md5==='function'&&typeof g.UAParser==='function')return original.apply(receiver,args);
   if(Date.now()>=deadline){console.error(new Error('Shanxi reporting dependencies did not load'));return;}
   setTimeout(run,100);
  }
  return run();
 }
 deferredShanxiReports.add(readyReport);g.learnCountryDataAdd=readyReport;
}
g.__observeSite=function(){observeNgczAjax();
 // CMG's obfuscated self-serialized Worker references its outer a0b closure.
 // On KitKat use its documented main-thread transmuxer option; keep the
 // original SDK, WASM decryption, signed requests and media bytes intact.
 if(profile&&profile.name==='yangshipin-original'&&config&&config.legacyMediaWorkers)try{var H=g.Hls,d=H&&H.DefaultConfig;if(d&&d.enableWorker===true)d.enableWorker=false;}catch(ignore){}
if(!profile)return;if(profile.name==='shanxi'){observeShanxiReport();return;}if(profile.name==='mgtv')return;if(profile.name==='guangxi'){observeGuangxiCrypto();return;}if(profile.name==='yft'){observeNativeVideojs();return;}if(profile.name!=='gdtv')return;try{
 if(!webpackRequire&&typeof g.webpackJsonp==='function'){var id='ntv-site-diagnostics';var modules={};modules[id]=function(module,exports,require){webpackRequire=require;};g.webpackJsonp([id],modules,[id]);}
 if(!tracedVue&&webpackRequire&&webpackRequire.c)Object.keys(webpackRequire.c).some(function(id){var value=webpackRequire.c[id].exports;value=value&&value.default||value;if(typeof value==='function'&&/^2\./.test(value.version||'')&&value.config){tracedVue=value;return true;}return false;});
 if(tracedVue&&tracedVue.config.errorHandler!==tracedHandler){var original=tracedVue.config.errorHandler;tracedHandler=function(error,instance,info){try{console.error('Vue lifecycle '+info+': '+String(error)+(error&&error.stack?'\n'+error.stack:''));}catch(ignore){}if(typeof original==='function')return original.apply(this,arguments);};tracedVue.config.errorHandler=tracedHandler;}
 }catch(ignore){}};
// This page logs the exact signed URL it has just assigned to the Kwai player.
// Observe that selected value before the SDK tries to start a software Worker;
// do not harvest an API's whole channel list or manufacture a stream/signature.
var originalLog=g.console.log;
g.console.log=function(){if(profile&&profile.name==='cztv'&&arguments[1]==='我是src.value'&&/\/liveTV\/\d+\/?$/.test(location.pathname))try{var candidate=new URL(String(arguments[0]),location.href);if(/^https?:$/.test(candidate.protocol))g.ntvBrowser.offerMedia(candidate.href);}catch(ignore){}return originalLog.apply(this,arguments);};
function siteReport(url,body){if(profile&&profile.name==='gdtv'&&/\/api\/\d+\/store\//.test(url)&&typeof body==='string')try{var report=JSON.parse(body);if(report.exception)console.error('Site exception: '+JSON.stringify(report.exception).slice(0,6000));}catch(ignore){}}
var send=g.XMLHttpRequest.prototype.send,fetch=g.fetch;
g.fetch=function(url,options){if(profile&&profile.name==='ngcz'&&ngczSelection)try{g.__selectedNgczSource({file:new URL(typeof url==='string'?url:url.url,location.href).href});}catch(ignore){}if(options)siteReport(String(url),options.body);return fetch.apply(this,arguments);};
g.XMLHttpRequest.prototype.send=function(body){
 if(profile&&profile.name==='ngcz'&&ngczSelection)try{g.__selectedNgczSource({file:new URL(this._url,location.href).href});}catch(ignore){}
 if(profile&&profile.name==='ngcz')this.addEventListener('readystatechange',function(){try{
  if(this.readyState!==4||this.status!==200||!ngczSelection||ngczSelection.ready)return;
  var request=new URL(this.responseURL,location.href);
  if(request.protocol!=='https:'||request.hostname!=='mapi.ngcz.tv'||request.pathname!=='/api/v1/program.php'||request.searchParams.get('channel_id')!==ngczSelection.id)return;
  var text=this.responseText;if(text.length>1024*1024)return;var entries=JSON.parse(text);if(!Array.isArray(entries)||entries.length>2000)return;
  entries.forEach(function(item){var stream=item&&item.channel_stream&&item.channel_stream[0],value=stream&&stream.m3u8;if(item&&Number(item.now_play)===1&&typeof value==='string'&&/^https?:\/\//i.test(value))ngczSelection.urls.add(value);});
 }catch(ignore){}});
 siteReport(this._url,body);
 return send.apply(this,arguments);
};
function installLazyImages(){var $=g.jQuery;if(!$||!$.fn)return false;
 // The page's synchronous gallery walker repeatedly forces layout on hundreds of
 // thumbnails. Keep its real lazy image behavior, but share a 1 Hz bounded queue.
 var queue=[],timer=0;
 function pump(){var batch=0;for(var i=0;i<queue.length&&batch<4;){var item=queue[i],node=item.node,rect=node.getBoundingClientRect(),threshold=+item.options.threshold||0;
  if(!node.isConnected||node.loaded){queue.splice(i,1);continue;}
  if(rect.bottom>=-threshold&&rect.top<=innerHeight+threshold&&rect.right>=-threshold&&rect.left<=innerWidth+threshold){queue.splice(i,1);batch++;load(item);}else i++;
 }if(!queue.length){clearInterval(timer);timer=0;}}
 function load(item){var node=item.node,options=item.options,url=node.getAttribute('data-'+(options.data_attribute||'original'));if(!url)return;
  if(options.appear)options.appear.call(node,queue.length,options);var image=new Image();image.onload=function(){if(node.tagName==='IMG')node.src=url;else node.style.backgroundImage='url("'+url.replace(/"/g,'%22')+'")';node.loaded=true;if(options.load)options.load.call(node,queue.length,options);};image.src=url;
 }
 $.fn.lazyload=function(options){options=options||{};this.each(function(){if(!queue.some(function(item){return item.node===this;},this))queue.push({node:this,options:options});});if(!timer)timer=setInterval(pump,1000);return this;};console.log('nTv CGTN lazy images: shared 1 Hz queue');return true;
}
function headlessDecoration(url){
 if(profile&&profile.name==='bilibili-vod'&&config&&config.headless){
  var commentUrl;try{commentUrl=new URL(url,location.href);}catch(ignore){}
  // The comment Web Component requires Shadow DOM. Headless media tests do
  // not render comment threads; keep player, account/login and icon scripts.
  if(commentUrl&&commentUrl.hostname==='s1.hdslb.com'&&!commentUrl.port&&/^\/bfs\/seed\/jinkela\/commentpc\/bili-comments(?:\.[0-9a-f]+)?\.js$/.test(commentUrl.pathname))return true;
 }
 if(profile&&profile.name==='cctv-original'&&config&&config.headless){
  var part;try{part=new URL(url,location.href);}catch(ignore){}
  // Presentation-only header/footer builders force thousands of DOM mutations
  // and repeated layouts. Keep every player, login, WAF and crypto script.
  if(part&&part.hostname==='www.cctv.com'&&/^\/newcctv\/containingPages\/(?:mapjs|bottomjs)\/index\.js$/.test(part.pathname))return true;
  if(part&&part.hostname==='tv.cctv.com'&&part.pathname==='/common/pc_nav/index.js')return true;
  if(part&&part.hostname==='r.img.cctvpic.com'&&part.pathname==='/photoAlbum/templet/common/DEPA1635928379783438/indexPC.js')return true;
 }
 return false;
}
function headlessAnalytics(url){
 if(!config||!config.headless)return false;
 try{var parsed=new URL(url,location.href);if(!/^https?:$/.test(parsed.protocol)||parsed.port)return false;
  var pageHost=new URL(location.href).hostname;
  // Public-service badge statistics are unrelated to media/login. Some old
  // pages insert this synchronously via document.write and otherwise wait 30s.
  if(parsed.hostname==='dcs.conac.cn'&&/^\/js\/.+\.js$/.test(parsed.pathname))return true;
  if(pageHost==='apphhplushttps.sxrtv.com'&&parsed.hostname==='dyhhplus.sxrtv.com'&&parsed.pathname==='/tapi/tongji/tide_log')return true;
  if(pageHost==='tv.gxtv.cn'&&parsed.hostname==='api2019.gxtv.cn'&&parsed.pathname==='/statistical/traffic')return true;
  if(config.originalMediaPipeline)return false;
  // Btime's optional classic QuickTracking bundle declares a global `_` and
  // overwrites the original login/player Lodash. Its queue is already created
  // by monitor.js; leave login, captcha and media scripts on their real paths.
  if(new URL(location.href).hostname==='www.btime.com'&&parsed.hostname==='o.alicdn.com'
      &&parsed.pathname==='/QTSDK/quicktracking-sdk/qt_web.cjs.js')return true;
  return parsed.hostname==='hm.baidu.com'&&parsed.pathname==='/hm.js'||(parsed.hostname==='cnzz.com'||parsed.hostname.endsWith('.cnzz.com'))&&/^\/(?:z\.js|z_stat\.php|stat\.php|core\.php|c\.js)$/.test(parsed.pathname);
 }catch(ignore){return false;}
}
g.__optionalAnalytics=headlessAnalytics;
g.__skipScriptTransfer=function(url){return headlessDecoration(url)||headlessAnalytics(url);};
g.__skipScript=function(url){
 if(headlessDecoration(url)||headlessAnalytics(url))return true;
 if(profile&&profile.name==='cgtn'&&/\/jquery\.lazyload(?:\.min)?\.js(?:\?|$)/.test(url))return installLazyImages();return profile&&profile.name==='guangdianyun'&&/\/web-animations\.min\.js(?:\?|$)/.test(url);
};

// Headless sniffing does not display CGTN's decorative image carousels. Their
// synchronous Slick initialization otherwise forces hundreds of full layouts
// before the original live-player callback can run. Defer only initialization;
// a later plugin command first initializes that real node with the real plugin.
var galleryPlugins=new WeakSet();
function deferHeadlessGalleries(){var $=g.jQuery,original=$&&$.fn&&$.fn.slick;if(typeof original!=='function'||galleryPlugins.has(original))return;
 var pending=new WeakMap(),count=0;
 function flush(collection){collection.each(function(){var args=pending.get(this);if(args){pending.delete(this);count--;original.apply($(this),args);}});}
 var wrapped=new Proxy(original,{apply:function(target,collection,args){
  if(args.length===0||args[0]===undefined||args[0]===null||typeof args[0]==='object'){
   var fresh=0,existing=false;collection.each(function(){if(this.slick)existing=true;else if(!pending.has(this))fresh++;});
   if(!existing&&count+fresh<=64){collection.each(function(){if(!pending.has(this))count++;pending.set(this,Array.prototype.slice.call(args));});return collection;}
  }
  flush(collection);return target.apply(collection,args);
 }});galleryPlugins.add(wrapped);$.fn.slick=wrapped;console.log('nTv CGTN headless: decorative galleries deferred; real plugin commands remain available');
}
var swiperConstructors=new WeakSet();
function trimHeadlessHainanNews(url){
 if(!profile||profile.name!=='hainan'||!config||!config.headless||!/^\/(?:index\.shtml)?$/.test(new URL(url).pathname))return;
 var player=document.querySelector('.vms-player.playerWrapper');
 if(!player||!player.querySelector('#flashdiv'))return;
 var blocks=document.querySelectorAll('.contentCon'),removed=0;
 for(var i=0;i<blocks.length&&i<32;i++){
  var block=blocks[i];
  // Retain all executable resources, media, original player ancestors and DOM
  // identity. Only the known independent news galleries leave the sniffing DOM.
  if(!block.isConnected||block===player||block.contains(player)||!block.querySelector('.contentWrap')||block.querySelector('script,style,link,video,audio,iframe,object,embed,.vms-player,.playerWrapper'))continue;
  block.remove();removed++;
 }
 if(removed)console.log('nTv Hainan headless: independent news galleries removed '+removed);
}
function deferHeadlessNewsSwipers(){
 var original=g.Swiper;if(typeof original!=='function'||swiperConstructors.has(original))return;
 var wrapped=new Proxy(original,{construct:function(target,args,newTarget){
  var node=args[0];
  // The original homepage has fifteen unrelated editorial carousels. Keep a
  // real Swiper instance, using its own supported deferred-init option. Never
  // defer a media node, player ancestor/descendant, or a detached feature probe.
  if(node instanceof g.HTMLElement&&node.isConnected&&node.matches('.contentCon')&&node.querySelector('.contentWrap')&&!node.closest('.vms-player,.playerWrapper')&&!node.querySelector('video,audio,iframe,object,embed,.vms-player,.playerWrapper')){
   var next=args.slice();next[1]=Object.assign({},args[1],{init:false});return Reflect.construct(target,next,newTarget===wrapped?target:newTarget);
  }
  return Reflect.construct(target,args,newTarget===wrapped?target:newTarget);
 }});swiperConstructors.add(wrapped);g.Swiper=wrapped;console.log('nTv Hainan headless: original news Swiper initialization deferred');
}
var readOnlyClockHelpers=new WeakSet();
function repairBozhouReadOnlyClock(){
 var original=g.requestExtApi;if(typeof original!=='function'||readOnlyClockHelpers.has(original))return;
 var code=Function.prototype.toString.call(original);
 // This audited wrapper returns no XHR handle and signs each call itself.
 // Do not alter a replacement helper with a different cancellation contract.
 if(code.indexOf('$.ajax(ajaxObj)')<0||code.indexOf('signBaseUrlAndParams')<0||/return\s+\$\.ajax/.test(code))return;
 var wrapped=new Proxy(original,{apply:function(target,receiver,args){
  var input=args[0],data=input&&input.data,api;
  try{api=new URL(input.url,location.href);}catch(ignore){}
  if(!input||!data||data.service!=='getMenuContentList'||input.noSign||!api||api.protocol!=='https:'||api.hostname!=='jkwg.ahbztv.com'||api.pathname!=='/bzapi/external/externalService')return Reflect.apply(target,receiver,args);
  var initial=host({op:'pageConfig'}).clockOffsetMs,saved=Object.assign({},data),request=Object.assign({},input),next=args.slice();
  function success(){if(typeof input.success==='function')return Reflect.apply(input.success,input,arguments);}
  function error(){if(typeof input.error==='function')return Reflect.apply(input.error,input,arguments);}
  request.error=error;request.success=function(response){
   var corrected=host({op:'pageConfig'}).clockOffsetMs;
   if(response&&response.state===1006&&/^\d{14}$/.test(response.currTime||'')&&Math.abs(corrected-initial)>60000){
    // Java observed the fresh original HTTPS API timestamp. Apply it before
    // this synchronous SDK signs again; no response/key/signature is replaced.
    __updatePageClockOffset(corrected);
    var retry=Object.assign({},input,{data:Object.assign({},saved),success:success,error:error}),retryArgs=args.slice();retryArgs[0]=retry;
    console.log('原频道目录时钟已校正，原 SDK 重新签名查询一次');
    return Reflect.apply(target,receiver,retryArgs);
   }
   return success.apply(null,arguments);
  };next[0]=request;return Reflect.apply(target,receiver,next);
 }});readOnlyClockHelpers.add(wrapped);g.requestExtApi=wrapped;
}
if(typeof g.__classicEval==='function'){var classicEval=g.__classicEval;g.__classicEval=new Proxy(classicEval,{apply:function(target,receiver,args){var result=target.apply(receiver,args);if(profile&&config&&config.headless){var source=String(args[1]);if(profile.name==='cgtn'&&/\/slick\/slick(?:\.min)?\.js(?:[?#]|$)/.test(source))deferHeadlessGalleries();if(profile.name==='hainan'&&/\/newPublic\/js\/swiper\.min\.js(?:[?#]|$)/.test(source))deferHeadlessNewsSwipers();if(profile.name==='native-videojs')nativeVideoOptions(g.videojs);if(profile.name==='bozhou')repairBozhouReadOnlyClock();}return result;}});}
function deferHeadlessMgtvDecoration(url){
 if(!profile||profile.name!=='mgtv'||!config||!config.headless||!/^\/live\/?$/.test(new URL(url).pathname))return;
 // Leave Nuxt's real tree, listeners, player and channel tabs intact. Only
 // decorative siblings are excluded from layout in the headless live page.
 // A stylesheet also covers later Vue mounts without a mutation polling loop.
 var style=document.createElement('style');
 style.setAttribute('data-ntv-headless-mgtv','');
 style.textContent='body > svg#__SVG_SPRITE_NODE__,body > svg#__svg__icons__dom__,#m-topheader,#channel-left-nav,.channel-module-list__modules > .t-liveEvent,.channel-module-list__modules > .t-liveList,#sideFloatbtn{display:none!important}';
 (document.head||document.documentElement).appendChild(style);
}
var mgtvSelection=null;
// The iframe's original Vue player owns channel selection and resolves the
// source. Read only the selected route/channel and the actual SDK init config;
// never scan the catalogue for another URL or manufacture a live endpoint.
g.__yanbianResolvedSource=function(){try{
 if(!profile||profile.name!=='yanbian'||!config||!config.headless)return '';
 var route=location.pathname.match(/^\/fuesf\/TV\/(\d+)\/?$/);if(!route)return '';
 var seen=[],queue=[{vm:g.$nuxt,depth:0}],tv=null,player=null;
 while(queue.length&&seen.length<64){var item=queue.shift(),vm=item.vm;if(!vm||item.depth>12||seen.indexOf(vm)>=0)continue;seen.push(vm);
  if(vm.$options&&vm.$options.name==='TV')tv=vm;
  if(vm.$options&&vm.$options.name==='xgplayers')player=vm;
  var children=vm.$children;if(Array.isArray(children))for(var i=0;i<children.length&&queue.length<64;i++)queue.push({vm:children[i],depth:item.depth+1});
 }
 if(!tv||!player||!Array.isArray(tv.channelList)||!Number.isInteger(tv.currentChannelIndex))return '';
 var channel=tv.channelList[tv.currentChannelIndex],source=tv.playerConfig&&tv.playerConfig.source,options=player.initConfig,props=player.$props&&player.$props.config;
 if(!channel||String(channel.id)!==route[1]||typeof source!=='string'||!/^https?:\/\//i.test(source)||channel.play_url!==source||tv.playerConfig.title!==channel.name||!options||options.url!==source||!props||props.source!==source)return '';
 var root=document.getElementById(options.id);if(!root||!root.classList.contains('xgplayer')||!/^https?:$/.test(new URL(source).protocol))return '';
 return source;
 }catch(ignore){return '';}};
// The Huaibei iframe's module-local XGPlayer is not a window constructor.
// Observe its real selected live configuration, including the original EPG
// copyright/replay state. A catalogue URL alone is never sufficient.
function selectedPeonyLive(profileName){try{
 if(!profile||profile.name!==profileName||!config||!config.headless||!/^\/fuesf\/TV\/?$/.test(location.pathname))return '';
 var seen=[],queue=[{vm:g.$nuxt,depth:0}],tv=null,player=null;
 while(queue.length&&seen.length<64){var item=queue.shift(),vm=item.vm;if(!vm||item.depth>12||seen.indexOf(vm)>=0)continue;seen.push(vm);
  if(vm.$options&&vm.$options.name==='TV'){if(tv)return '';tv=vm;}
  if(vm.$options&&vm.$options.name==='xgplayers'){if(player)return '';player=vm;}
  if(Array.isArray(vm.$children))for(var i=0;i<vm.$children.length&&queue.length<64;i++)queue.push({vm:vm.$children[i],depth:item.depth+1});
 }
 if(!tv||!player||!Array.isArray(tv.channelList)||!Number.isInteger(tv.currentChannelIndex)||tv.defaultType!==1)return '';
 var channel=tv.channelList[tv.currentChannelIndex],params=tv.playbillData&&tv.playbillData.params,current=tv.currentData,source=tv.playerConfig&&tv.playerConfig.source,options=player.initConfig,props=player.$props&&player.$props.config;
 if(!channel||!/^\d+$/.test(String(channel.id))||Number(channel.id)<=0||!params||String(params.channel)!==String(channel.id)||typeof source!=='string'||!/^https?:\/\//i.test(source)||channel.play_url!==source||!current||current.copyright||!options||options.url!==source||!props||props.source!==source)return '';
 if(current.TVType!==undefined?current.TVType!==1:!Array.isArray(channel.playbilllist)||channel.playbilllist.length!==0||Object.keys(current).length!==0)return '';
 if(tv.playerConfig.type!==undefined&&tv.playerConfig.type!==1)return '';
 var root=document.getElementById(options.id);if(!root||!root.classList.contains('xgplayer'))return '';
 return source;
 }catch(ignore){return '';}}
g.__huaibeiResolvedSource=function(){return selectedPeonyLive('huaibei');};
g.__jiningResolvedSource=function(){return selectedPeonyLive('jining');};
g.__mgtvAudit=function(){var p=g.__mgtvCurrentPlayer?g.__mgtvCurrentPlayer():g.mgtvLivePlayer,d=p&&p.context&&p.context.liveDataSource,info=d&&d.liveSource,current=d&&d.currentSource,active=document.querySelector('a.channel.selected[data-channel-sid]');return {target:mgtvSelection,selected:selected,hasPlayer:!!p,isLive:!!(p&&p.isLive),hasData:!!d,sid:d&&d.cameraId,metadataSid:info&&info.cameraId,name:info&&info.cameraName,activeSid:active&&active.getAttribute('data-channel-sid'),hasSource:!!(current&&current.url),sourceMatchesHls:!!(current&&g.hhls&&current.url===g.hhls.url),sourceMatchesKernel:!!(current&&p.liveKernel&&current.url===p.liveKernel.src)}};
g.__selectedMgtvSource=function(player,hls){try{
 if(!mgtvSelection||!player||player.isLive!==true)return '';
 var active=document.querySelector('a.channel.selected[data-channel-sid]'),data=player.context&&player.context.liveDataSource,info=data&&data.liveSource,url=hls&&hls.url;
 if(!active||active.getAttribute('data-channel-sid')!==mgtvSelection.sid||active.textContent.trim()!==mgtvSelection.name||!data||String(data.cameraId)!==mgtvSelection.sid||!info||String(info.cameraId)!==mgtvSelection.sid||info.cameraName!==mgtvSelection.name||typeof url!=='string'||!Array.isArray(info.sources)||!info.sources.some(function(source){return source&&source.url===url})||!player.liveKernel||player.liveKernel.src!==url)return '';
 if(!selected){selected=true;host({op:'selectionReady',target:url,provider:'mgtv-live'});}return url;
 }catch(ignore){return '';}};
g.__refreshSelectedMse=function(){try{if(profile&&profile.name==='mgtv'&&config&&config.selectionRequired)return g.__selectedMgtvSource(g.mgtvLivePlayer,g.hhls);}catch(ignore){}return '';};
g.__siteClick=function(fromSelection,ready,node){if(fromSelection&&ready!==false&&config&&config.selectionRequired&&profile&&profile.name==='ngcz'){
 try{var tile=node.closest('#channelList li'),id=tile&&tile.getAttribute('value');if(id&&/^\d+$/.test(id)){ngczSelection={node:tile,id:id,urls:new Set()};selected=true;}}catch(ignore){}return;
 }if(fromSelection&&ready!==false&&config&&config.selectionRequired&&profile&&profile.name==='taiyuan'){
 try{var tile=node.closest('.list'),data=tile&&JSON.parse(tile.getAttribute('url'));if(data&&data.movie&&typeof data.movie.liveUrl==='string'&&typeof data.movie.liveId==='string'){taiyuanSelection={node:tile,movie:data.movie};selected=true;}}catch(ignore){}return;
 }if(fromSelection&&ready!==false&&config&&config.selectionRequired&&profile&&profile.name==='mgtv'){for(var link=node,level=0;link&&link!==document&&level++<8;link=link.parentElement){var sid=link.getAttribute('data-channel-sid');if(sid&&/^[0-9]+$/.test(sid)){mgtvSelection={sid:sid,name:link.textContent.trim()};return;}}return;}if(fromSelection&&ready!==false&&config&&config.selectionRequired&&profile&&profile.name==='bozhou'){
 try{for(var item=node,level=0;item&&item!==document&&level++<8;item=item.parentElement){var raw=item.getAttribute('data-url');if(!raw||!item.closest('#channel')||typeof g.getPlayUrl!=='function')continue;var chosen=g.getPlayUrl(raw),value=chosen&&chosen.playUrl;if(typeof value!=='string')return;var target=new URL(value,location.href);if(!/^https?:$/.test(target.protocol)||!raw.split(',').some(function(part){return part.trim().replace(/^https?:/i,'')===value.replace(/^https?:/i,'');}))return;selected=true;host({op:'selectionReady',target:target.href});return;}}catch(ignore){}return;
 }if(fromSelection&&ready!==false&&config&&config.selectionRequired&&config.selectionScript&&!selected){var target='';for(var depth=0;node&&node!==document&&depth++<8;node=node.parentElement){var value=node.getAttribute('stream')||node.getAttribute('data-url')||node.getAttribute('data-src');if(value&&/\.(m3u8|mp4)(?:[?#]|$)/i.test(value)){target=new URL(value,location.href).href;break;}}selected=true;host({op:'selectionReady',target:target});}};
function configure(url){config=host({op:'pageConfig'})||{};profile=profiles.find(function(p){return p.hosts.indexOf(new URL(url).hostname)>=0;});repairYanchengInitialProgramme(url);configureSiteElementFactory();trimHeadlessHainanNews(url);deferHeadlessMgtvDecoration(url);if(profile&&profile.name==='migu'&&/^\/m\/liveDetail\/\d+\/?$/.test(new URL(url).pathname)){config.selectionRequired=true;host({op:'requireSelection'});}if(!config.selectionScript&&profile&&profile.selection){config.selectionScript=profile.selection(url);if(config.selectionScript){config.selectionRequired=true;host({op:'requireSelection'});}}host({op:'log',value:'站点配置: '+(profile?profile.name:'generic')+(config.selectionScript?' · 包含页面自定义脚本':'')});};
// The original SDK reports the selected content ID and its already resolved
// live URL. Bind handoff to that exact source, so an unrelated pre-roll media
// request cannot become the channel. No whole-catalog scan or auth fabrication.
if(typeof g.addEventListener==='function')g.addEventListener('message',function(event){
 if(!profile||profile.name!=='migu'||selected||event.source!==g)return;
 try{var current=new URL(location.href),match=current.pathname.match(/^\/m\/liveDetail\/(\d+)\/?$/);if(!match||event.origin!==current.origin)return;
 var message=typeof event.data==='string'?JSON.parse(event.data):event.data,data=message&&message.data,body=data&&data.body;
 if(!message||message.type!=='video_playurl_code'||!data||String(data.code)!=='200'||String(data.playCode)!=='100000'||!body||!body.content||String(body.content.contId)!==match[1])return;
 if(body.content.contentMediaType&&body.content.contentMediaType.isDrmContent)return;
 var url=new URL(body.urlInfo&&body.urlInfo.url,current.href);if(!/^https?:$/.test(url.protocol)||!/^https?:\/\//i.test(String(body.urlInfo&&body.urlInfo.url)))return;
 // GSLB URLs are still processed by the original SDK. Authorize only this
 // selected content path across its own CDN family; do not probe the unfinished
 // first-stage URL or hand off an advertisement requested by the same SDK.
 selected=true;host({op:'selectionReady',target:url.href,provider:'migu-live'});
 }catch(ignore){}
});
g.__siteReady=function(){
 if(config&&config.selectionScript){try{g.__runSelectionScript(config.selectionScript);}catch(e){console.error('频道选择脚本: '+e);}}
 // Retry only the playlist's declarative click forms. Arbitrary user scripts
 // (including their own timers/network calls) are executed once as before.
 if(config&&config.selectionRequired&&!selected){
  var code=config.selectionScript.trim().replace(/;\s*$/,''),literal='(?:"[^"\\r\\n]*"|\x27[^\x27\\r\\n]*\x27)';
  code=code.replace(/;\s*_tvFunc\.fullscreenW\(\s*["']video["']\s*\)\s*$/,'');
  var simple=new RegExp('^\\$\\$\\(\\s*'+literal+'\\s*\\)(?:\\.filter\\(\\s*function\\s*\\(\\s*\\)\\s*\\{\\s*return\\s*\\$?\\$\\(this\\)\\.text\\(\\)\\.trim\\(\\)\\s*===\\s*'+literal+'\\s*;?\\s*\\}\\s*\\))?\\.click\\(\\s*\\)\\s*;?$');
  if(simple.test(code)){var attempts=0,timer=setInterval(function(){if(selected||++attempts>60){clearInterval(timer);return;}try{g.__runSelectionScript(code);}catch(e){clearInterval(timer);console.error('延迟选台: '+e);}},1000);}
 }
};
var gdtvLiveWait=new WeakMap(),gdtvLiveRetried=new WeakSet();
function recoverGdtvLiveRequest(){
 if(!profile||profile.name!=='gdtv'||!config||!config.headless||!config.legacyMediaWorkers)return;
 var route=location.pathname.match(/^\/tvChannelDetail\/(\d+)\/?$/);if(!route||host({op:'pageConfig'}).mediaReady)return;
 var element=document.getElementById('root'),container=element&&element._reactRootContainer,root=container&&container._internalRoot&&container._internalRoot.current;
 var stack=root?[root]:[],seen=new Set();
 while(stack.length&&seen.size<4096){
  var node=stack.pop();if(!node||seen.has(node))continue;seen.add(node);
  var owner=node.stateNode,props=owner&&owner.props,params=props&&props.match&&props.match.params;
  if(owner&&typeof owner.getTvChannelDetailWithTcdn==='function'&&params&&String(params.id)===route[1]){
   if(gdtvLiveRetried.has(owner)||props.isPrefetch||owner.state&&owner.state.timeShift)return;
   var now=performance.now(),since=gdtvLiveWait.get(owner);if(since===undefined){gdtvLiveWait.set(owner,now);return;}if(now-since<3000)return;
   // Call exactly the site's existing onError/onMessage handler. Its own HTTP
   // client supplies authentication and the current route/shift; never copy a
   // catalogue token or invent a WebSocket success/node/signature.
   gdtvLiveRetried.add(owner);
   try{var request=owner.getTvChannelDetailWithTcdn();if(request&&typeof request.catch==='function')request.catch(function(){console.log('站点直播回退：原站请求失败');});console.log('站点直播回退：原站请求当前频道 '+route[1]);}catch(error){console.log('站点直播回退：原站处理器未就绪');}
   return;
  }
  if(node.sibling)stack.push(node.sibling);if(node.child)stack.push(node.child);
 }
}
g.__auto=function(){
 // MGTV creates its live context from the real play control. Its strict SDK
 // identity gate above still prevents the initialized default stream handing off.
 if(config&&config.selectionRequired&&!selected&&(!profile||profile.name!=='mgtv'))return;
 recoverGdtvLiveRequest();
 if(profile)for(var i=0;i<profile.play.length;i++){var button=document.querySelector(profile.play[i]);if(button&&g.__tryPlayControl(button))return;}
 auto();
};
configure(location.href);
})(globalThis);
