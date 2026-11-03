/* Isolated ES5 preview. No receiver scripts, requests, timers or persistent writes. */
(function () {
  'use strict';
  var main = document.getElementsByTagName('main')[0], i;
  function byId(id) { return document.getElementById(id); }
  function text(id, value) { var el=byId(id); if(el) el.textContent=value; }
  function toast(value) {
    var el=byId('message');
    if(!el){el=document.createElement('div');el.id='message';el.className='message';document.body.appendChild(el);}
    el.textContent=value;
    clearTimeout(toast.timer);toast.timer=setTimeout(function(){el.textContent='';},1800);
  }
  var label=document.createElement('div');label.className='preview-label';label.textContent='nTv · 视觉预览 / 模拟数据';main.insertBefore(label,main.firstChild);
  var values={managementUrlText:'192.168.0.106:9978',status:'模拟在线',currentChannelSummary:'CCTV-1 综合 · 高清',playbackStatus:'模拟在线',mediaConnectionStatus:'模拟在线',sysDevice:'MI 6',sysApp:'nTv 预览版',sysCpu:'4 核',sysAbi:'armeabi-v7a',sysMemory:'1 GB / 8 GB',sysDisplay:'1920 × 1080',sysIpv4:'192.168.0.106',sysIpv6:'无',uiScaleHint:'1920 × 1080 · 当前 100%',tvUiStatus:'原版界面',radioUiStatus:'经典唱片',backgroundStatus:'当前未设置背景',systemStatus:'模拟在线'};
  for(var key in values) if(Object.prototype.hasOwnProperty.call(values,key)) text(key,values[key]);
  var nodes=document.querySelectorAll('span,b,p,div');
  for(i=0;i<nodes.length;i++) if(nodes[i].children.length===0 && /^(连接中|正在读取.*|正在获取.*|正在识别.*)$/.test(nodes[i].textContent.replace(/^\s+|\s+$/g,''))) nodes[i].textContent='模拟数据 · 已就绪';
  var controls=document.querySelectorAll('input,select,button,textarea');
  for(i=0;i<controls.length;i++) {
    controls[i].removeAttribute('disabled');
    if(controls[i].tagName==='SELECT'||controls[i].tagName==='INPUT'||controls[i].tagName==='TEXTAREA') controls[i].onchange=function(){toast('已在预览中修改，未发送到电视');};
    else controls[i].onclick=function(){toast('模拟操作完成，未发送到电视');};
  }
  var back=document.querySelector('.back-button');if(back) back.onclick=function(){location.href='../index.html';};
  var online=document.querySelectorAll('.online');for(i=0;i<online.length;i++) online[i].innerHTML='<span class="dot"></span>模拟在线';
  var picker=document.querySelector('.channel-picker-trigger');
  if(picker) picker.onclick=function(){
    var existing=byId('previewChannels');if(existing){existing.parentNode.removeChild(existing);return;}
    var panel=document.createElement('section');panel.id='previewChannels';panel.className='preview-channels';
    panel.innerHTML='<h2>选择频道</h2><button>CCTV-1 综合</button><button>CCTV-2 财经</button><button>CCTV-5 体育</button><button>湖南卫视</button>';
    picker.parentNode.appendChild(panel);picker.parentNode.style.paddingBottom='16px';
    var buttons=panel.getElementsByTagName('button');for(var j=0;j<buttons.length;j++)buttons[j].onclick=function(){text('currentChannelSummary',this.textContent+' · 高清');panel.parentNode.removeChild(panel);toast('已切换模拟频道');};
  };
  text('mediaTitle','CCTV-1 综合');text('mediaGroup','央视 · 线路 1');text('mediaStatus','模拟直播 · 高清');text('mediaLiveBadge','直播中');text('mediaPosition','当前播放');text('mediaDuration','缓冲 6.0 秒');
  var toggle=byId('mediaToggle');if(toggle){toggle.className+=' playing';toggle.onclick=function(){var playing=this.className.indexOf('playing')>=0;this.className=playing?'media-play-button':'media-play-button playing';text('mediaLiveBadge',playing?'已暂停':'直播中');};}
  var repo=byId('repo');if(repo){repo.removeAttribute('href');repo.style.color='#999';}
}());
