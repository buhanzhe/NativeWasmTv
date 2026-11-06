function setSystemInfo(id, value) {
  var element = document.getElementById(id);
  if (element) element.textContent = value || "无";
}

var apkUploadActive = false;
function renderApkTransfer() {
  document.getElementById("apkTransferButton").disabled = apkUploadActive;
  if (!apkUploadActive) {
    document.getElementById("apkTransferHint").textContent =
      "发送到当前网页所在的设备；安装成功后自动清理 APK。";
  }
}

function chooseApk() {
  if (!apkUploadActive) document.getElementById("apkFile").click();
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
  if (apkUploadActive) return;
  var path = "/api/apk/upload?name=" + encodeURIComponent(file.name),
    request = new XMLHttpRequest(),
    progress = document.getElementById("apkTransferProgress"),
    bar = document.getElementById("apkTransferProgressBar"),
    hint = document.getElementById("apkTransferHint"),
    input = document.getElementById("apkFile"),
    finished = false;
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
    hint.textContent = "设备正在校验 APK…";
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
    hint.textContent = (result.label || result.name || "APK") + " 已发送，设备正在打开安装界面；安装成功后自动清理。";
    document.getElementById("apkTransferButton").disabled = false;
    toast("APK 已发送到当前设备");
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
}
function renderPageState() {
  renderSystemInfo();
  renderApkTransfer();
}
startPage();

var appLogRequestId = 0;
function openAppLogs() {
  document.getElementById("appLogsPanel").style.display = "block";
  refreshAppLogs();
}
function refreshAppLogs() {
  var id = ++appLogRequestId;
  document.getElementById("appLogNote").textContent = "正在读取已保存的应用与 IJK 日志…";
  api("/api/logs?filter=" + encodeURIComponent(document.getElementById("appLogFilter").value), null, function (error, data) {
    if (id !== appLogRequestId) return;
    document.getElementById("appLogText").value = error ? error.message : data.text || "";
    document.getElementById("appLogNote").textContent = error ? "读取失败，请重试" : (data.note || "") + (data.text || data.available === false ? "" : " 当前筛选没有日志。");
  });
}
function saveAppLogs() {
  location.href = "/api/logs?filter="
    + encodeURIComponent(document.getElementById("appLogFilter").value) + "&download=1";
}

// User-initiated copy; insecure LAN and legacy WebKit use execCommand.
function copySystemAddress(id) {
  var node = document.getElementById(id), value = node ? node.textContent.replace(/^\s+|\s+$/g, "") : "";
  if (!value || value === "无" || value === "未知" || /读取/.test(value)) {
    toast("暂无可复制的IP地址", true); return;
  }
  value = value.replace(/[、，,]\s*/g, "\n");
  function fallback() {
    var input = document.createElement("textarea"), previous = document.activeElement, copied = false;
    input.value = value; input.setAttribute("readonly", "readonly");
    input.style.cssText = "position:fixed;left:0;top:0;width:1px;height:1px;opacity:0;font-size:16px";
    document.body.appendChild(input);
    try {
      input.select();
      if (input.setSelectionRange) input.setSelectionRange(0, value.length);
      copied = document.execCommand("copy");
    } catch (ignored) {}
    document.body.removeChild(input);
    if (previous && previous.focus) previous.focus();
    toast(copied ? "IP地址已复制" : "复制失败，请长按地址复制", !copied);
  }
  if (navigator.clipboard && window.isSecureContext) {
    try { navigator.clipboard.writeText(value).then(function () { toast("IP地址已复制"); }, fallback); }
    catch (ignored) { fallback(); }
  } else fallback();
}
function systemAddressKey(event, id) {
  var key = event.key || event.keyCode;
  if (key === "Enter" || key === " " || key === 13 || key === 32) {
    event.preventDefault(); copySystemAddress(id);
  }
}
