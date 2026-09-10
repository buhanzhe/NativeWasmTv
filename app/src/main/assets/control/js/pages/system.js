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
    return "可升级 64 位版本 · 提升编码性能，但可能会占用更多内存";
  return update.message || "点击检查更新";
}

function renderAppUpdate() {
  var update = (state && state.update) || {},
    button = document.getElementById("appUpdateButton"),
    status = document.getElementById("sysUpdateStatus");
  button.disabled = update.state === "checking" || update.state === "downloading" || appUpdateInstalling;
  document.getElementById("appUpdateAction").textContent = update.state === "downloading" ? "下载中…"
    : update.state === "checking" ? "检查中…" : update.state === "ready" ? "安装更新"
    : update.state === "available" ? (update.architectureUpgrade ? "升级到 64 位" : "立即升级") : "检查更新";
  status.textContent = updateStatusText(update);
  if (updatePollTimer) {
    clearTimeout(updatePollTimer);
    updatePollTimer = null;
  }
  if (update.state === "checking" || update.state === "downloading") {
    updatePollTimer = setTimeout(refresh, 700);
  }
}

var appUpdateInstalling = false;
function appUpdateClick() {
  var update = (state && state.update) || {};
  if (appUpdateInstalling || update.state === "checking" || update.state === "downloading") return;
  if (update.state !== "available" && update.state !== "ready") { checkAppUpdate(false); return; }
  var message = "下载并安装更新到当前网页所在的设备？";
  if (update.architectureUpgrade) message += "\n\n64 位版本可提升编码性能，但可能会占用更多内存。";
  if (!window.confirm(message)) return;
  appUpdateInstalling = true;
  renderAppUpdate();
  api("/api/update/install", {}, function (error, result) {
    appUpdateInstalling = false;
    if (error) { toast(error.message, true); renderAppUpdate(); return; }
    if (state) state.update = result.update || {};
    renderAppUpdate();
    refresh();
  });
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
}
startPage();
