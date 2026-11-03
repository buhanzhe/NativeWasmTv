function setSystemInfo(id, value) {
  var element = document.getElementById(id);
  if (element) element.textContent = value || "无";
}

var apkUploadActive = false;
var updatePollTimer = null;

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
  NtvDialogs.confirm(message,function(accepted){
  if (!accepted) return;
  appUpdateInstalling = true;
  renderAppUpdate();
  api("/api/update/install", {}, function (error, result) {
    appUpdateInstalling = false;
    if (error) { toast(error.message, true); renderAppUpdate(); return; }
    if (state) state.update = result.update || {};
    renderAppUpdate();
    refresh();
  });
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


function renderPageState(){if(!state)return;setSystemInfo('sysApp',state.system&&state.system.app);setSystemInfo('aboutDevice',state.system&&state.system.device);renderAppUpdate();}
startPage();
