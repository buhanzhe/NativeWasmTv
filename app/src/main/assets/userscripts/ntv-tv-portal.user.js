// ==UserScript==
// @name 电视页面组件精简（轻量模式）
// @version 1.0.2
// @match *://www.zyrb.com.cn/*
// @run-at document-start
// @grant none
// ==/UserScript==
(function(g){
'use strict';
// Keep the original TV component and its selected channel data. Only avoid
// mounting unrelated portal widgets in the host's headless playback mode.
var config=typeof __host==='function'?__host(JSON.stringify({op:'pageConfig'})):null;
if(!config||!config.headless||!g.XMLHttpRequest)return;
// Ask the page's own selected-channel function for refreshed authorization.
// Observe its successful original callback without replacing the DOM source or
// decoder. The generic HLS transport accepts only a validated same-channel
// manifest fetched by the page, retaining the selected program and quality.
if(location.pathname==='/tvradio_dvw/tv_detail_dvw.html'){
 var pending=false,epoch=0;
 function refresh(){setTimeout(refresh,90000);
  var player=g.myTvPlayer,detail=g.getTvDetail,jq=g.$,video=document.querySelector('video'),id=String(g.tv_id||''),active=document.querySelector('.tv_item_active');
  if(pending||!/^\d{1,16}$/.test(id)||typeof detail!=='function'||!jq||typeof jq.ajax!=='function'||!player||typeof player.src!=='function'||!video||video.paused||!(video.currentTime>=10)||active&&active.getAttribute('data-id')!==id)return;
  var source=typeof player.currentSrc==='function'?player.currentSrc():video.currentSrc||video.src,original;
  try{original=new URL(source,location.href);if(!/\.m3u8$/i.test(original.pathname)||!/^https?:$/.test(original.protocol))return;}catch(ignore){return;}
  pending=true;var version=++epoch,ajax=jq.ajax;
  function current(){var selected=document.querySelector('.tv_item_active');return version===epoch&&player===g.myTvPlayer&&String(g.tv_id)===id&&(!selected||selected.getAttribute('data-id')===id)&&!video.paused;}
  jq.ajax=new Proxy(ajax,{apply:function(target,owner,args){
   var options=args[0];if(!options||typeof options!=='object'||typeof options.success!=='function')return Reflect.apply(target,owner,args);
   var success=options.success,copy=Object.assign({},options,{success:function(){
    if(!current()){pending=false;return;}
    var assign=player.src;
    player.src=new Proxy(assign,{apply:function(method,receiver,values){
     if(!values.length)return Reflect.apply(method,receiver,values);
     try{var next=new URL(values[0][0].src,location.href);
      if(next.origin===original.origin&&next.pathname===original.pathname&&current()){
       if(__host(JSON.stringify({op:'nativeHlsRefresh',previous:original.href,url:next.href}))===true)__host(JSON.stringify({op:'log',value:'UserScript TV original selected-channel manifest refresh requested'}));
      }
     }catch(ignore){}
     return receiver;
    }});
    try{return Reflect.apply(success,this,arguments);}finally{player.src=assign;pending=false;}
   }});return Reflect.apply(target,owner,[copy].concat(args.slice(1)));
  }});
  try{detail.call(g,id);}catch(ignore){pending=false;}finally{jq.ajax=ajax;}
  setTimeout(function(){if(version===epoch){pending=false;epoch++;}},15000);
 }
 setTimeout(refresh,90000);
}
var route=location.hash.match(/^#\/([\w-]+)(?:\?|$)/);if(!route)return;
var cache=new WeakMap(),PlatformURL=g.URL,PlatformBlob=g.Blob,Decoder=g.TextDecoder,Encoder=g.TextEncoder;
function selectedPage(xhr,raw){try{
 if(xhr.readyState!==4||xhr.status!==200)return raw;
 var url=new PlatformURL(xhr.responseURL,location.href);if(url.origin!==location.origin||url.pathname.split('/').pop()!==route[1]+'.json')return raw;
 var previous=cache.get(xhr);if(previous&&previous.raw===raw)return previous.result;
 var blob=typeof g.__blobSnapshot==='function'?g.__blobSnapshot(raw):null;
 var binary=blob?blob.bytes:raw instanceof ArrayBuffer||ArrayBuffer.isView(raw)?raw:null;
 var data=binary?JSON.parse(new Decoder().decode(binary)):typeof raw==='string'?JSON.parse(raw):raw;
 if(!data||data.key!==route[1]||data.password||typeof data.content!=='string')return raw;
 var tree=JSON.parse(data.content),matches=0;
 function keep(node){if(!node||typeof node!=='object'||node.visible===false)return null;
  if(typeof node.type==='string'&&/\/(?:iframeTv)$/.test(node.type)&&typeof node.path==='string'&&node.path.indexOf('/iframeTv/')>=0){matches++;return node;}
  if((node.id==='webheaderContainer'||node.id==='webfooterContainer')&&Array.isArray(node.child))return Object.assign({},node,{child:[]});
  if(!Array.isArray(node.child))return null;
  var children=node.child.map(keep).filter(Boolean);return children.length?Object.assign({},node,{child:children}):null;
 }
 var slim=keep(tree);if(!slim||matches!==1)return raw;
 var changed=Object.assign({},data,{content:JSON.stringify(slim)}),result=changed;
 if(blob)result=new PlatformBlob([JSON.stringify(changed)],{type:blob.type});
 else if(binary)result=new Encoder().encode(JSON.stringify(changed)).buffer;
 else if(typeof raw==='string')result=JSON.stringify(changed);
 cache.set(xhr,{raw:raw,result:result});return result;
 }catch(ignore){return raw;}}
['responseText','response'].forEach(function(key){var descriptor=Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype,key);if(!descriptor||!descriptor.configurable||typeof descriptor.get!=='function')return;
 var getter=descriptor.get;descriptor.get=function(){return selectedPage(this,getter.call(this));};Object.defineProperty(XMLHttpRequest.prototype,key,descriptor);
});
if(typeof g.fetch==='function'){
 var fetch=g.fetch,observed=new WeakSet();
 function observeResponse(response){
  if(!response||observed.has(response))return response;observed.add(response);
  ['json','text'].forEach(function(name){var read=response[name];if(typeof read!=='function')return;
   response[name]=function(){var owner=this;return read.apply(owner,arguments).then(function(raw){return selectedPage({readyState:4,status:owner.status,responseURL:owner.url},raw);});};
  });
  var clone=response.clone;if(typeof clone==='function')response.clone=function(){return observeResponse(clone.apply(this,arguments));};
  return response;
 }
 g.fetch=function(){return fetch.apply(this,arguments).then(observeResponse);};
}
})(globalThis);
