function remoteLanPrefix() {
  var hosts = [],
    management = (state && state.managementUrl) || "";
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

function normalizeTakeoverAddress(value) {
  value = String(value || "").replace(/^\s+|\s+$/g, "");
  if (/^\d{1,3}$/.test(value)) {
    var part = Number(value), prefix = remoteLanPrefix();
    if (part < 1 || part > 254) throw new Error("请输入 1 到 254 的 IP 最后一段");
    if (!prefix) throw new Error("无法识别手机网段，请输入完整 IPv4 地址");
    return "http://" + prefix + part + ":9966";
  }
  // Parse before using a browser URL object: some engines accept shorthand or
  // octal IPv4, which could silently connect to a different device.
  var match = value.match(/^(?:(https?):\/\/)?(\d{1,3}(?:\.\d{1,3}){3})(?::(\d{1,5}))?(?:\/(?:index\.html)?)?$/i);
  if (!match) throw new Error("请输入完整 IPv4 地址（可带协议和端口），或 IP 最后一段");
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

function remoteAddressLabel(url) {
  var match = String(url || "").match(/^http:\/\/(\d{1,3}\.\d{1,3}\.\d{1,3}\.)(\d{1,3}):9966\/?$/i);
  return match && match[1] === remoteLanPrefix() ? match[2] : url;
}

function renderRemoteControlState() {
  if (!state) return;
  var section = document.getElementById("takeoverSection"),
    allowed = state.canInitiateTakeover === true,
    url = state.takeoverReceiverUrl || "",
    rememberedUrl = state.lastTakeoverReceiverUrl || "",
    connected = !!url,
    prefix = remoteLanPrefix(),
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
  hint.textContent = prefix
    ? "电视地址（" + prefix + "___ 或完整 IP，默认端口 9966）"
    : "电视 IP 或 HTTP/HTTPS 地址（默认端口 9966）";
}

var takeoverProgressTimer = null,
  takeoverProgressVisible = false;

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
  if (!takeoverProgressVisible || !pageActive) return;
  api("/api/state?view=cast", null, function (error, data) {
    if (!takeoverProgressVisible || !pageActive) return;
    if (!error && data && data.takeoverProgress) {
      renderTakeoverProgress(data.takeoverProgress);
      if (!data.takeoverProgress.active && Number(data.takeoverProgress.percent) >= 100) {
        closeTakeoverProgress(650);
        setTimeout(refresh, 700);
        return;
      }
    }
    takeoverProgressTimer = setTimeout(pollTakeoverProgress, 300);
  });
}

function beginTakeoverProgress(title, detail) {
  renderTakeoverProgress({ title: title, detail: detail, percent: 4 });
  pollTakeoverProgress();
}

function saveRemoteCatalogUrl() {
  var current = state ? state.takeoverReceiverUrl || "" : "",
    input = document.getElementById("remoteCatalogUrl"),
    url = "";
  if (!current) {
    try {
      url = normalizeTakeoverAddress(input.value);
    } catch (error) {
      toast(error.message, true);
      input.focus();
      return;
    }
  }
  beginTakeoverProgress(current ? "正在退出接管" : "正在连接电视",
    current ? "恢复电视原有频道" : "检查网络和设备状态");
  document.getElementById("remoteCatalogButton").disabled = true;
  api("/api/takeover", { receiverUrl: url }, function (error, result) {
    document.getElementById("remoteCatalogButton").disabled = false;
    if (error) {
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
  document.getElementById("castStatus").textContent = cast.running
    ? (cast.status || "正在投送") : "电视在线";
  document.getElementById("castResolution").value = settings.webCastResolution || "1280x720";
  document.getElementById("castFps").value = String(settings.webCastFps || 25);
  document.getElementById("castCodec").value = settings.webCastCodec || "h264";
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
function renderPageState() {
  renderCastState();
}
startPage();
