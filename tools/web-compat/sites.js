// Site adapters use the public website's playback APIs, not stale CDN addresses.
(function(w,d){
  'use strict';
  // onPageStarted can run before the replacement HTML has installed its shell.
  if(w.__ntvNativeTvCompatibility&&!w.__ntvLegacySiteShell)return;
  var cztv=w.location.hostname==='www.cztv.com'&&/^\/liveTV(?:\/\d+)?\/?$/.test(w.location.pathname);
  var iqilu=w.location.hostname==='v.iqilu.com'&&/^\/live\/[a-z0-9]+\/?$/.test(w.location.pathname);
  var gdy=w.location.hostname==='web.guangdianyun.tv'&&/^\/tv\/\d+\/?$/.test(w.location.pathname);
  if(!cztv&&!iqilu&&!gdy)return;
  var marker=w.__ntvTvCompatibility;if(!marker||marker.adapter)return;
  marker.adapter=cztv?'cztv':gdy?'guangdianyun':'iqilu';
  var id=(w.location.pathname.match(/\/(\d+)\/?$/)||[])[1]||'101';
  var api=w.__ntvLegacySiteShell?(cztv?'/__ntv_kitkat__/cztv-play-info?id='+id:gdy?'/__ntv_kitkat__/gdy-play-info':'/__ntv_kitkat__/iqilu-play-info'):
    'https://zlive-das.cztv.com/zapp/live/tv/channel/playInfo?channelId='+id+'&platform=WEB';
  // On a standalone userscript, wait for the site's current crypto/config script.
  var response=cztv||w.__ntvLegacySiteShell?w.fetch(api,{credentials:'omit'}).then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json();}):null;
  if(gdy&&!w.__ntvLegacySiteShell){
    var tenant=new URLSearchParams(w.location.search).get('uin'),channel=w.location.pathname.replace(/[^0-9]/g,''),base='https://1812501212048408.cn-hangzhou.fc.aliyuncs.com/2016-08-15/proxy/node-api.online/node-api/tv/';
    var query='?id='+encodeURIComponent(channel)+'&uin='+encodeURIComponent(tenant||'');
    response=w.fetch(base+'channelInfo'+query,{credentials:'omit'}).then(function(r){return r.json();}).then(function(info){
      if(info.code!==200||info.errorCode!==0||!info.data||info.data.status!==1||String(info.data.uin)!==tenant)throw new Error('频道不可用');
      var client='xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g,function(c){var r=Math.random()*16|0;return(c==='x'?r:r&3|8).toString(16);});
      return w.fetch(base+'getPlayAddress'+query+'&clientId='+client,{credentials:'omit'}).then(function(r){return r.json();}).then(function(play){
        if(play.code!==200||play.errorCode!==0||!play.data)throw new Error(play.errMessage||'直播不可用');
        return {success:true,data:{url:play.data.hlsUrl,channelName:info.data.name}};
      });
    });
  }
  function ready(){
    var root=d.createElement('div');root.id='ntv-legacy-player';
    root.style.cssText='position:fixed;left:0;top:0;right:0;bottom:0;background:#000;color:white;z-index:2147483646';
    var status=d.createElement('div');status.style.cssText='position:absolute;left:16px;top:16px;z-index:2';status.textContent='正在加载直播…';
    var video=d.createElement('video');video.controls=true;video.autoplay=true;
    video.setAttribute('playsinline','');video.style.cssText='width:100%;height:100%;background:#000';
    root.appendChild(video);root.appendChild(status);d.body.appendChild(root);
    if(!response){
      try {
        if(!w.CryptoJS||!w._pdCid||!w.aly||!w.mxpx)throw new Error('网站播放配置不可用');
        var now=Date.now(),crypto=w.CryptoJS,options={iv:crypto.enc.Utf8.parse('0000000000000000'),mode:crypto.mode.CBC,padding:crypto.pad.Pkcs7};
        var payload=crypto.AES.encrypt(JSON.stringify({channelMark:w._pdCid}),crypto.enc.Utf8.parse(w.aly),options).toString();
        response=w.fetch('https://feiying.litenews.cn/api/v1/auth/exchange?t='+now+'&s='+w.__ntvMd5(w._pdCid+now+w.mxpx),
          {method:'POST',headers:{'Content-Type':'text/plain'},body:payload,credentials:'omit'}).then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.text();}).then(function(raw){
          var decoded=JSON.parse(crypto.AES.decrypt(raw,crypto.enc.Utf8.parse(w.aly),options).toString(crypto.enc.Utf8));
          return {success:!!decoded.data,data:{url:decoded.data,channelName:w._pdName}};
        });
      }catch(error){response=Promise.reject(error);}
    }
    response.then(function(value){
      if(!value.success||!value.data)throw new Error(value.message||'直播接口不可用');
      var list=value.data.multiBitrateStreamList||[],selected=null,i;
      if(!cztv)selected={urlList:[value.data.url]};
      // Prefer HD on 4.4. Avoid needlessly decoding 4K on the legacy CPU.
      if(cztv)for(i=0;i<list.length;i++)if(list[i].bitrateCode==='720P'&&!list[i].isHdr){selected=list[i];break;}
      if(!selected)for(i=0;i<list.length;i++)if(list[i].bitrateCode!=='AUDIO'&&!list[i].isHdr){selected=list[i];break;}
      if(!selected||!selected.urlList||!selected.urlList[0]||typeof selected.urlList[0]!=='string')throw new Error('没有可用的视频线路');
      var link=d.createElement('a');link.href=selected.urlList[0];
      // Public web player's path/key formula. Strict CDN nodes require Unix seconds;
      // its millisecond timestamp works only on some of the returned mirrors.
      var timestamp=Math.floor((value._ntvServerTimeMs>0?value._ntvServerTimeMs:Date.now())/1000),key=/^zwebl0[1-7]\.cztv\.com$/.test(link.hostname)?'CHWr9VybUeBZE1VB':
        /^zhfivel0[1-7]\.cztv\.com$/.test(link.hostname)?'9T08yiAoqM4eeCwV':null;
      if(link.protocol!=='https:'&&link.protocol!=='http:')throw new Error('视频地址无效');
      if(key)link.href+=(link.search?'&':'?')+'auth_key='+timestamp+'-0-0-'+w.__ntvMd5(link.pathname+'-'+timestamp+'-0-0-'+key);
      marker.stream=link.href;status.textContent=value.data.channelName||'直播';video.src=link.href;
      // KitKat's MediaPlayer can fetch HLS outside WebView interception. One small
      // manifest request makes it visible to the app's existing native handoff.
      if(w.__ntvNativeTvCompatibility&&/\.m3u8(?:[?#]|$)/i.test(link.href))
        w.fetch(link.href,{credentials:'omit'}).catch(function(){});
      video.addEventListener('playing',function(){status.style.display='none';marker.playing=true;},false);
      video.addEventListener('error',function(){status.textContent='网页播放器无法播放，请使用应用的原生播放';},false);
      var promise=video.play();if(promise&&promise.catch)promise.catch(function(){status.textContent='点击播放直播';});
    }).catch(function(error){status.textContent='直播加载失败：'+error.message;marker.failure=error.message;});
  }
  if(d.body)ready();else d.addEventListener('DOMContentLoaded',ready,false);
})(window,document);
