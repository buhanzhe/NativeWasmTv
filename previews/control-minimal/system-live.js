/* Real system information, using the original renderer. Read-only ES5 preview. */
(function(){
'use strict';
var state=null, updateCheckRequested=true, logSequence=0;
function el(id){return document.getElementById(id);}
function put(id,value){el(id).textContent=value;}
function connection(value){var online=document.querySelector('.online');online.textContent=value;}
function request(path,done){
 var xhr=new XMLHttpRequest(),finished=false;
 function finish(error,data){if(finished)return;finished=true;done(error,data);}
 xhr.open('GET',path,true);xhr.timeout=10000;
 xhr.onreadystatechange=function(){if(xhr.readyState!==4)return;var data;try{data=JSON.parse(xhr.responseText);}catch(e){finish('设备返回的数据无效');return;}if(xhr.status!==200){finish(data.message||'连接失败');return;}finish(null,data);};
 xhr.onerror=function(){finish('无法连接设备');};xhr.ontimeout=function(){finish('连接超时');};xhr.send(null);return xhr;
}
function renderAppUpdate(){
 var u=state.update||{};
 put('sysUpdateStatus',u.message||'点击检查更新');
 put('appUpdateAction',u.state==='available'?'有可用更新':u.state==='ready'?'更新已就绪':u.state==='checking'?'检查中…':u.state==='downloading'?'下载中…':'检查更新');
}
function setSystemInfo(id, value) {
  var element = document.getElementById(id);
  if (element) element.textContent = value || "无";
}

function renderSystemInfo() {
  if (!state) return;
  var info = state.system || {},
    display = state.display || {},
    settings = state.settings || {},
    isTv = state.isTelevision === true,
    title = document.getElementById("systemDeviceTitle"),
    description = document.getElementById("systemDeviceDescription"),
    displayText = (display.width || "--") + "×" + (display.height || "--"),
    webViewText = [info.webView, info.webViewPackage, info.webViewArchitectures]
      .filter(function (value) {
        return value && value !== "无";
      })
      .join(" · "),
    cpuParts = [];
  title.textContent = isTv ? "这台电视" : "这台设备";
  description.textContent = isTv
    ? "查看电视本机的系统、硬件、显示和网络信息。"
    : "查看设备本机的系统、硬件、显示和网络信息。";
  if (info.cpuName && info.cpuName !== "未知" && info.cpuName !== "0") cpuParts.push(info.cpuName);
  if (info.cpuCores) cpuParts.push(info.cpuCores + " 核");
  var cpuText = cpuParts.join(" · ") || "无";
  if (info.cpuCoreNames && info.cpuCoreNames !== info.cpuName)
    cpuText += "\n" + info.cpuCoreNames;
  if (info.cpuFrequencies && info.cpuFrequencies !== "主频未知")
    cpuText += "\n" + info.cpuFrequencies;
  var memoryStorageText = info.memory || "未知";
  if (info.storage) memoryStorageText += " / " + info.storage;
  if (display.densityDpi) displayText += " · " + display.densityDpi + " DPI";
  displayText += " · 界面 " + Math.round((Number(settings.uiScaleFactor) || 1) * 100) + "%";
  if (Number(display.diagonalInches) > 0)
    displayText += " · 约 " + Number(display.diagonalInches).toFixed(1) + " 英寸";
  setSystemInfo("sysAndroid", info.android);
  setSystemInfo("sysDevice", info.device);
  setSystemInfo("sysWebViewSummary", webViewText);
  setSystemInfo("sysCpu", cpuText);
  setSystemInfo("sysAbi", info.cpuArchitectures);
  setSystemInfo("sysMemory", memoryStorageText);
  setSystemInfo("sysDisplay", displayText);
  setSystemInfo("sysIpv4", info.lanIpv4);
  setSystemInfo("sysIpv6", info.publicIpv6);
  setSystemInfo("sysApp", info.app);
  renderAppUpdate();
  if (!updateCheckRequested) {
    updateCheckRequested = true;
    checkAppUpdate(true);
  }
}

function load(){
 connection('连接中');
 request('/device/system',function(error,data){
  if(error){connection('连接失败');put('systemDeviceDescription',error+' 刷新页面重试。');return;}
  state=data;renderSystemInfo();
  put('sysWebViewSummary',el('sysWebViewSummary').textContent.replace(/ · /g,'\n'));
  put('sysIpv6',el('sysIpv6').textContent.replace(/、/g,'\n'));
  connection('设备在线');
 });
}
var ids=['sysAndroid','sysDevice','sysWebViewSummary','sysCpu','sysAbi','sysMemory','sysDisplay','sysIpv4','sysIpv6','sysApp'];
for(var i=0;i<ids.length;i++)put(ids[i],'读取中…');
put('systemDeviceDescription','正在连接 192.168.0.106，读取完整设备信息。');
el('appUpdateButton').onclick=function(){put('sysUpdateStatus','预览仅展示更新状态，未发起检查或安装。');};
el('apkTransferButton').onclick=function(){el('apkFile').click();};
el('apkFile').onchange=function(){var f=this.files&&this.files[0];if(f)put('apkTransferHint','已选择 '+f.name+'（预览模式，未发送到设备）');this.value='';};
function logs(){
 var sequence=++logSequence;put('appLogNote','正在读取设备日志…');
 request('/device/logs?filter='+encodeURIComponent(el('appLogFilter').value),function(error,data){
  if(sequence!==logSequence)return;
  if(error){put('appLogNote',error);return;}
  el('appLogText').value=data.text||'';
  put('appLogNote',(data.note||'')+(data.text||data.available===false?'':' 当前筛选没有日志。'));
 });
}
el('appLogsOpen').onclick=function(){var p=el('appLogsPanel'),open=p.style.display!=='none';p.style.display=open?'none':'block';this.setAttribute('aria-expanded',open?'false':'true');this.textContent=open?'查看日志':'收起日志';if(!open)logs();};
el('appLogFilter').onchange=logs;el('appLogRefresh').onclick=logs;
el('appLogSave').onclick=function(){location.href='/device/logs?filter='+encodeURIComponent(el('appLogFilter').value)+'&download=1';};
load();
}());
