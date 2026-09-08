function setSystemInfo(id, value) {
  var element = document.getElementById(id);
  if (element) element.textContent = value || "无";
}

var apkUploadActive = false;
var updateCheckRequested = false,
  updatePollTimer = null;

function updateStatusText(update) {
  if (!update || !update.state || update.state === "idle") return "点击检查更新";
  if (update.state === "checking") return "正在检查最新 Release…";
  if (update.state === "available" && update.architectureUpgrade)
    return "可升级 64 位版本 · 点击再次检查";
  return update.message || "点击检查更新";
}

function renderAppUpdate() {
  var update = (state && state.update) || {},
    button = document.getElementById("appUpdateButton"),
    status = document.getElementById("sysUpdateStatus");
  button.disabled = update.state === "checking";
  status.textContent = updateStatusText(update);
  if (updatePollTimer) {
    clearTimeout(updatePollTimer);
    updatePollTimer = null;
  }
  if (update.state === "checking") {
    updatePollTimer = setTimeout(refresh, 700);
  }
}

function checkAppUpdate(automatic) {
  var button = document.getElementById("appUpdateButton");
  button.disabled = true;
  document.getElementById("sysUpdateStatus").textContent = "正在检查最新 Release…";
  api("/api/update/check", {}, function (error, result) {
    if (error) {
      button.disabled = false;
      document.getElementById("sysUpdateStatus").textContent = "检查失败，点击重试";
      if (!automatic) toast(error.message, true);
      return;
    }
    if (state) state.update = result.update || {};
    renderAppUpdate();
    if (!automatic && result.update && result.update.state !== "checking")
      toast(updateStatusText(result.update), result.update.state === "error");
  });
}

function apkLanPrefix() {
  var hosts = [], management = (state && state.managementUrl) || "";
  if (management) {
    var link = document.createElement("a");
    link.href = management;
    hosts.push(link.hostname);
  }
  hosts.push(location.hostname);
  for (var i = 0; i < hosts.length; i++) {
    var match = String(hosts[i] || "").match(/^(\d{1,3}\.\d{1,3}\.\d{1,3}\.)\d{1,3}$/);
    if (match) return match[1];
  }
  return "";
}

function normalizeApkReceiverAddress(value) {
  value = String(value || "").replace(/^\s+|\s+$/g, "");
  if (/^\d{1,3}$/.test(value)) {
    var part = Number(value), prefix = apkLanPrefix();
    if (part < 1 || part > 254) throw new Error("请输入 1 到 254 的 IP 最后一段");
    if (!prefix) throw new Error("无法识别手机网段，请输入完整 IPv4 地址");
    return "http://" + prefix + part + ":9966";
  }
  var match = value.match(/^(?:(https?):\/\/)?(\d{1,3}(?:\.\d{1,3}){3})(?::(\d{1,5}))?(?:\/(?:index\.html)?)?$/i);
  if (!match) throw new Error("请输入电视的完整 IPv4 地址，或 IP 最后一段");
  var parts = match[2].split(".");
  for (var i = 0; i < parts.length; i++) {
    var octet = Number(parts[i]);
    if (octet > 255) throw new Error("IPv4 地址每一段应为 0 到 255");
    parts[i] = String(octet);
  }
  var port = match[3] ? Number(match[3]) : 9966;
  if (port < 1 || port > 65535) throw new Error("端口应为 1 到 65535");
  return (match[1] || "http").toLowerCase() + "://" + parts.join(".") + ":" + port;
}

function apkReceiverUrl() {
  if (state && state.isTelevision === true) return "";
  if (state && state.takeoverReceiverUrl) return state.takeoverReceiverUrl;
  return normalizeApkReceiverAddress(document.getElementById("apkReceiverUrl").value);
}

function renderApkTransfer() {
  if (!state) return;
  var television = state.isTelevision === true,
    input = document.getElementById("apkReceiverUrl"),
    target = state.takeoverReceiverUrl || state.lastTakeoverReceiverUrl || "";
  document.getElementById("apkReceiverField").style.display = television ? "none" : "";
  document.getElementById("apkTransferButton").disabled = apkUploadActive;
  if (!television && !input.value && target) input.value = target;
  if (!apkUploadActive) {
    document.getElementById("apkTransferHint").textContent = television
      ? "从手机打开本页即可上传；接收完成后直接跳转系统安装界面。"
      : "填写电视 IP 即可发送，无需接管；接收完成后直接跳转安装。";
  }
}

function chooseApk() {
  if (!state) {
    toast("正在读取设备状态，请稍候", true);
    return;
  }
  if (state.isTelevision !== true) {
    try {
      apkReceiverUrl();
    } catch (error) {
      toast(error.message, true);
      document.getElementById("apkReceiverUrl").focus();
      return;
    }
  }
  document.getElementById("apkFile").click();
}

function sendSelectedApk() {
  var input = document.getElementById("apkFile"), file = input.files && input.files[0];
  if (!file) return;
  if (!/\.apk$/i.test(file.name || "")) {
    input.value = "";
    toast("请选择 .apk 文件", true);
    return;
  }
  var limit = Number(state && state.apkTransferMaxBytes) || 0;
  if (limit > 0 && file.size > limit) {
    input.value = "";
    toast("APK 不能超过 " + Math.max(1, Math.floor(limit / 1024 / 1024)) + " MB", true);
    return;
  }
  uploadApk(file);
}

function uploadApk(file) {
  var direct = state && state.isTelevision === true,
    path = direct ? "/api/apk/upload" : "/api/apk/push",
    request = new XMLHttpRequest(),
    progress = document.getElementById("apkTransferProgress"),
    bar = document.getElementById("apkTransferProgressBar"),
    hint = document.getElementById("apkTransferHint"),
    input = document.getElementById("apkFile"),
    finished = false;
  if (!direct) {
    try {
      path += "?receiverUrl=" + encodeURIComponent(apkReceiverUrl())
        + "&name=" + encodeURIComponent(file.name);
    } catch (error) {
      input.value = "";
      toast(error.message, true);
      return;
    }
  } else {
    path += "?name=" + encodeURIComponent(file.name);
  }
  apkUploadActive = true;
  progress.className = "upload-progress active";
  progress.setAttribute("aria-hidden", "false");
  bar.style.width = "0%";
  hint.textContent = "正在发送 " + file.name + "（" + formatBytes(file.size) + "）…";
  renderApkTransfer();
  request.open("POST", path, true);
  request.timeout = 300000;
  request.setRequestHeader("Content-Type", "application/vnd.android.package-archive");
  request.upload.onprogress = function (event) {
    if (!event.lengthComputable) return;
    var percent = Math.max(0, Math.min(100, Math.round(event.loaded * 100 / event.total)));
    bar.style.width = percent + "%";
    hint.textContent = "正在发送 " + file.name + " · " + percent + "%";
  };
  request.upload.onload = function () {
    bar.style.width = "100%";
    hint.textContent = direct ? "电视正在校验 APK…" : "手机已接收，正在转发给电视…";
  };
  function finish(error, result) {
    if (finished) return;
    finished = true;
    apkUploadActive = false;
    input.value = "";
    if (error) {
      bar.style.width = "0%";
      progress.className = "upload-progress";
      progress.setAttribute("aria-hidden", "true");
      renderApkTransfer();
      toast(error.message, true);
      return;
    }
    bar.style.width = "100%";
    hint.textContent = (result.label || result.name || "APK") + " 已发送，电视正在打开安装界面。";
    document.getElementById("apkTransferButton").disabled = false;
    if (state && result.receiverUrl) state.lastTakeoverReceiverUrl = result.receiverUrl;
    toast("APK 已发送到电视");
  }
  request.onreadystatechange = function () {
    if (request.readyState !== 4) return;
    var result;
    try {
      result = JSON.parse(request.responseText || "{}");
    } catch (error) {
      finish(new Error("设备返回了无效数据"));
      return;
    }
    if (request.status < 200 || request.status >= 300 || result.ok === false) {
      finish(new Error(result.message || "APK 发送失败"));
      return;
    }
    finish(null, result);
  };
  request.onerror = function () { finish(new Error("无法连接设备")); };
  request.ontimeout = function () { finish(new Error("APK 发送超时")); };
  request.onabort = function () { finish(new Error("APK 发送已中止")); };
  try {
    request.send(file);
  } catch (error) {
    finish(error);
  }
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
  if (info.cpuFrequencies && info.cpuFrequencies !== "主频未知")
    cpuText += "\n" + info.cpuFrequencies;
  if (display.densityDpi) displayText += " · " + display.densityDpi + " DPI";
  displayText += " · 界面 " + Math.round((Number(settings.uiScaleFactor) || 1) * 100) + "%";
  if (Number(display.diagonalInches) > 0)
    displayText += " · 约 " + Number(display.diagonalInches).toFixed(1) + " 英寸";
  setSystemInfo("sysAndroid", info.android);
  setSystemInfo("sysDevice", info.device);
  setSystemInfo("sysWebViewSummary", webViewText);
  setSystemInfo("sysCpu", cpuText);
  setSystemInfo("sysAbi", info.cpuArchitectures);
  setSystemInfo("sysMemory", info.memory);
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
function renderPageState() {
  renderSystemInfo();
  renderApkTransfer();
  renderCjsPlugin();
}
function renderCjsPlugin() {
  var plugin = state.cjsPlugin || {}, input = document.getElementById("cjsPluginUrl");
  if (document.activeElement !== input) input.value = plugin.manifestUrl || "";
  document.getElementById("cjsPluginStatus").textContent = plugin.pendingVersion
    ? "已下载 " + plugin.pendingVersion + "，重启后启用"
    : plugin.installed ? "已安装 " + plugin.version + " · " + plugin.abi : "未安装";
  renderCjsSites(plugin.sites || []);
}
function renderCjsSites(sites){var list=document.getElementById('cjsSiteList');list.innerHTML='';for(var i=0;i<sites.length;i++){var site=sites[i],row=document.createElement('div'),label=document.createElement('span'),button=document.createElement('button');row.className='system-info-row';label.textContent=site.id+' · '+(site.installed?'v'+site.version:'未安装')+(site.pendingVersion?' · v'+site.pendingVersion+' 重启后启用':'');button.textContent=site.installed?'检查更新':'下载';button.onclick=(function(id){return function(){api('/api/settings',{updateCjsPlugin:true,cjsSiteId:id},function(e,v){toast(e?e.message:v.message,!!e);refresh()})}})(site.id);row.appendChild(label);row.appendChild(button);list.appendChild(row)}}
function updateCjsPlugin(){var input=document.getElementById('cjsPluginUrl'),status=document.getElementById('cjsPluginStatus'),value=input.value.replace(/^\s+|\s+$/g,'');status.textContent='正在下载并校验…';api('/api/settings',{cjsPluginManifestUrl:value,updateCjsPlugin:true},function(error,result){if(error){status.textContent='更新失败';toast(error.message,true);return}toast(result.message||'兼容插件已更新');refresh()})}
startPage();
