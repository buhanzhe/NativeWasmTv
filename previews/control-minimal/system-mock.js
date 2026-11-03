/* System preview only: no device requests or persistent writes. ES5. */
(function(){
  'use strict';
  function el(id){return document.getElementById(id);}
  function put(id,value){el(id).textContent=value;}
  put('sysAndroid','4.1.2');put('sysDevice','Android TV');
  put('sysWebViewSummary','系统 WebKit 534.30');
  put('systemDeviceDescription','设备的系统、硬件与网络信息，一目了然。');
  put('sysUpdateStatus','点击检查更新');
  el('appUpdateButton').onclick=function(){put('sysUpdateStatus','模拟检查完成');put('appUpdateAction','已是最新版本');};
  el('apkTransferButton').onclick=function(){el('apkFile').click();};
  el('apkFile').onchange=function(){var file=this.files&&this.files[0];if(file){put('apkTransferHint','已选择 '+file.name+'（模拟预览，文件未发送）');}this.value='';};
  var allLogs='[模拟日志]\n[信息] 应用启动完成\n[信息] 已载入频道列表\n[信息] 播放器准备就绪\n[信息] 管理页面连接正常';
  function renderLogs(){el('appLogText').value=el('appLogFilter').value==='all'?allLogs:'[模拟日志]\n暂无'+(el('appLogFilter').value==='error'?'报错':'崩溃')+'记录';}
  el('appLogsOpen').onclick=function(){var panel=el('appLogsPanel'),open=panel.style.display!=='none';panel.style.display=open?'none':'block';this.setAttribute('aria-expanded',open?'false':'true');this.textContent=open?'查看日志':'收起日志';renderLogs();put('appLogNote','仅显示模拟日志，不读取设备数据。');};
  el('appLogFilter').onchange=renderLogs;
  el('appLogRefresh').onclick=function(){renderLogs();put('appLogNote','模拟日志已刷新。');};
  el('appLogSave').onclick=function(){put('appLogNote','模拟保存完成，不生成设备日志文件。');};
}());
