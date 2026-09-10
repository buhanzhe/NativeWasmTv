function remoteAddressLabel(url) {
  var match = String(url || "").match(/^http:\/\/(\d{1,3}\.\d{1,3}\.\d{1,3}\.)(\d{1,3}):9966\/?$/i);
  return match && match[1] === receiverLanPrefix() ? match[2] : url;
}

function renderRemoteControlState() {
  if (!state) return;
  var section = document.getElementById("takeoverSection"),
    allowed = state.canInitiateTakeover === true,
    url = state.takeoverReceiverUrl || "",
    rememberedUrl = state.lastTakeoverReceiverUrl || "",
    connected = !!url,
    prefix = receiverLanPrefix(),
    input = document.getElementById("remoteCatalogUrl"),
    button = document.getElementById("remoteCatalogButton"),
    hint = document.getElementById("remoteCatalogHint");
  section.style.display = allowed ? "" : "none";
  if (!allowed) return;
  if (connected) input.value = remoteAddressLabel(url);
  else if (input.disabled || !input.value) input.value = remoteAddressLabel(rememberedUrl);
  input.disabled = connected;
  button.textContent = connected ? "退出接管" : "开始接管";
  button.className = connected ? "wide" : "primary wide";
  if (connected) {
    var direct = state.wifiDirect || {};
    hint.textContent = direct.active ? "已通过 Wi-Fi Direct 连接 · " + url :
      direct.upgrading ? "局域网投屏中 · 正在后台建立直连" : "已通过局域网连接 · " + url;
    return;
  }
  hint.textContent = prefix
    ? "电视地址（" + prefix + "___ 或完整 IP，默认端口 9966）"
    : "电视 IP 或 HTTP/HTTPS 地址（默认端口 9966）";
}

var takeoverProgressTimer = null,
  takeoverNavigationPending = false,
  takeoverProgressClosing = false,
  takeoverProgressVisible = false;

function openTakeoverControls() {
  if (!takeoverNavigationPending || !state || !state.takeoverReceiverUrl) return false;
  takeoverNavigationPending = false;
  renderRemoteControlState();
  closeTakeoverProgress();
  // Replace the connection form so Back cannot reveal a stale pre-claim page.
  location.replace("/pages/flymouse.html");
  return true;
}

function renderTakeoverProgress(progress) {
  progress = progress || {};
  var dialog = document.getElementById("takeoverProgressDialog"),
    percent = Math.max(0, Math.min(100, Number(progress.percent) || 0));
  takeoverProgressVisible = true;
  dialog.className = "takeover-progress-dialog active";
  dialog.setAttribute("aria-hidden", "false");
  document.getElementById("takeoverProgressTitle").textContent =
    progress.title || "正在连接电视";
  document.getElementById("takeoverProgressDetail").textContent =
    progress.detail || "检查网络和设备状态";
  document.getElementById("takeoverProgressBar").style.width = percent + "%";
  document.getElementById("takeoverProgressPercent").textContent = Math.round(percent) + "%";
}

function closeTakeoverProgress(delay) {
  takeoverProgressClosing = true;
  clearTimeout(takeoverProgressTimer);
  takeoverProgressTimer = setTimeout(function () {
    var dialog = document.getElementById("takeoverProgressDialog");
    takeoverProgressVisible = false;
    dialog.className = "takeover-progress-dialog";
    dialog.setAttribute("aria-hidden", "true");
  }, delay || 0);
}

function pollTakeoverProgress() {
  clearTimeout(takeoverProgressTimer);
  if (takeoverProgressVisible && !takeoverProgressClosing) refresh();
}

function afterStateRefresh(error) {
  if (!takeoverProgressVisible || takeoverProgressClosing || !pageActive) return;
  if (!error && state && state.takeoverProgress) {
    renderTakeoverProgress(state.takeoverProgress);
    if (!state.takeoverProgress.active && Number(state.takeoverProgress.percent) >= 100) {
      if (openTakeoverControls()) return;
      closeTakeoverProgress(650);
      setTimeout(refresh, 700);
      return;
    }
  }
  clearTimeout(takeoverProgressTimer);
  takeoverProgressTimer = setTimeout(pollTakeoverProgress, 300);
}

function beginTakeoverProgress(title, detail) {
  takeoverProgressClosing = false;
  renderTakeoverProgress({ title: title, detail: detail, percent: 4 });
  pollTakeoverProgress();
}

function saveRemoteCatalogUrl() {
  var current = state ? state.takeoverReceiverUrl || "" : "",
    input = document.getElementById("remoteCatalogUrl"),
    url = "";
  if (!current) {
    try {
      url = normalizeReceiverAddress(input.value);
    } catch (error) {
      toast(error.message, true);
      input.focus();
      return;
    }
  }
  takeoverNavigationPending = !current;
  beginTakeoverProgress(current ? "正在退出接管" : "正在连接电视",
    current ? "恢复电视原有频道" : "检查网络和设备状态");
  document.getElementById("remoteCatalogButton").disabled = true;
  api("/api/takeover", { receiverUrl: url }, function (error, result) {
    document.getElementById("remoteCatalogButton").disabled = false;
    if (error) {
      takeoverNavigationPending = false;
      renderTakeoverProgress({ title: "接管失败", detail: error.message, percent: 100 });
      closeTakeoverProgress(1200);
      toast(error.message, true);
      return;
    }
    if (result && result.pending) {
      toast(result.message || "请在手机上完成投屏权限授权");
      setTimeout(refresh, 900);
      return;
    }
    if (state) {
      state.takeoverReceiverUrl =
        result && typeof result.receiverUrl === "string" ? result.receiverUrl : url;
      if (result && typeof result.rememberedReceiverUrl === "string")
        state.lastTakeoverReceiverUrl = result.rememberedReceiverUrl;
      else if (url) state.lastTakeoverReceiverUrl = url;
    }
    renderRemoteControlState();
    if (openTakeoverControls()) return;
    renderTakeoverProgress({
      title: url ? "接管完成" : "已退出接管",
      detail: result && result.wifiDirect ? "已通过 Wi-Fi Direct 连接" :
        (url ? "已通过局域网连接" : "电视已恢复原有频道"),
      percent: 100
    });
    closeTakeoverProgress(650);
    toast(url ? "已接管电视，可在管理页面切换频道" : "已退出接管");
    setTimeout(refresh, 900);
  }, 25000);
}

function renderCastState() {
  if (!state) return;
  renderRemoteControlState();
  var settings = state.settings || {},
    cast = state.cast || {};
  var directSwitch = document.getElementById("wifiDirectExperimental");
  directSwitch.checked = settings.wifiDirectExperimental === true;
  directSwitch.disabled = !!state.takeoverReceiverUrl || !!(state.takeoverProgress || {}).active;
  document.getElementById("wifiDirectHint").textContent = directSwitch.disabled
    ? "结束接管后可更改。默认使用路由器 IP；直连为实验性功能。"
    : "默认关闭，使用路由器 IP。开启后尝试直连，部分设备可能频繁断开。";
  document.getElementById("castStatus").textContent = cast.running
    ? (cast.status || "正在投送") : "电视在线";
  document.getElementById("castResolution").value = settings.webCastResolution || "1280x720";
  document.getElementById("castFps").value = String(settings.webCastFps || 25);
  document.getElementById("castCodec").value = settings.webCastCodec || "h264";
  document.getElementById("castTransport").value = settings.webCastTransport || "tcp";
  document.getElementById("castBitrate").value = String(settings.webCastBitrateMbps || 3);
  document.getElementById("castAudio").checked = settings.webCastAudio === true;
  document.getElementById("castAudio").disabled = cast.audioSupported !== true;
  document.getElementById("castAudioHint").textContent = cast.audioSupported
    ? "开启后需允许录音和系统声音捕获；只采集应用声音，画面独立编码"
    : "当前系统仅支持画面";
  var capabilityText = cast.compatibilityNote
    ? cast.compatibilityNote
    : cast.h265Supported === false
      ? "当前设备没有 H.265 编码器；选择 H.265 时会自动回退 H.264。60/120 fps 也会按硬件能力降级。"
      : "60/120 fps 与 H.265 需要接收端支持；编码器不满足所选参数时会自动降级。";
  if (cast.running && cast.rtspVideoBitrate > 0) {
    capabilityText += " RTSP 实时：视频 " + formatCastBitrate(cast.rtspVideoBitrate);
    if (cast.rtspAudioBitrate > 0)
      capabilityText += "，声音 " + formatCastBitrate(cast.rtspAudioBitrate);
    capabilityText += "。";
  }
  if (cast.running) {
    if (cast.rtspTransport) capabilityText += " 传输：" + cast.rtspTransport.toUpperCase() + "。";
    capabilityText += " 实际投屏：" + cast.width + "×" + cast.height + " / " + cast.fps + "fps / "
      Number(cast.encoderTargetBitrateMbps || cast.bitrateMbps).toFixed(1) + "Mbps。";
    if (cast.encodeSamples > 0) {
      capabilityText += " 编码链路均值 " + cast.encodeDelayMs + "ms，P95 " + cast.encodeP95Ms
        + "ms；提交 " + cast.surfaceSubmitMs + "ms，提交后 " + cast.afterSubmitMs
        + "ms，待完成 " + cast.encoderPendingFrames + " 帧。";
    }
    if (cast.adaptationStatus) capabilityText += " " + cast.adaptationStatus + "。";
  }
  document.getElementById("castCapabilityHint").textContent = capabilityText;
}

function formatCastBitrate(bitsPerSecond) {
  return bitsPerSecond >= 1000000
    ? (bitsPerSecond / 1000000).toFixed(1) + " Mbps"
    : Math.round(bitsPerSecond / 1000) + " kbps";
}

function saveWebCastSettings() {
  var payload = {
    webCastResolution: document.getElementById("castResolution").value,
    webCastFps: Number(document.getElementById("castFps").value),
    webCastCodec: document.getElementById("castCodec").value,
    webCastTransport: document.getElementById("castTransport").value,
    webCastBitrateMbps: Number(document.getElementById("castBitrate").value),
    webCastAudio: document.getElementById("castAudio").checked
  };
  api("/api/settings", payload, function (error) {
    if (error) {
      toast(error.message, true);
      refresh();
      return;
    }
    if (state && state.settings) {
      for (var key in payload) if (payload.hasOwnProperty(key)) state.settings[key] = payload[key];
    }
    toast("投屏参数已保存，下次投屏生效");
  });
}
function saveWifiDirectSetting() {
  var toggle = document.getElementById("wifiDirectExperimental");
  var enabled = toggle.checked;
  toggle.disabled = true;
  api("/api/settings", { wifiDirectExperimental: enabled }, function (error) {
    if (error) toast(error.message, true);
    else toast(enabled ? "已开启实验性直连，下次接管尝试使用" : "已关闭直连，使用路由器 IP");
    refresh();
  });
}
function renderPageState() {
  renderCastState();
}
startPage();
