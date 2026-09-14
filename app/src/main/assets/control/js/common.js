// Shared HTTP, page lifecycle and navigation. Page-specific code owns its DOM.
var state = null,
  timer = null,
  stateRequestSequence = 0,
  stateRequest = null,
  stateRefreshPending = false,
  pageActive = true;
function api(path, body, done, timeoutMs) {
  if (path === "/api/pointer" && window.pointerRequest) {
    pointerRequest(path, body, done);
    return;
  }
  var request = new XMLHttpRequest(),
    finished = false;
  function finish(error, data) {
    if (finished) return;
    finished = true;
    done(error, data);
  }
  try {
    request.open(body ? "POST" : "GET", path, true);
    request.timeout = timeoutMs || 15000;
    if (body) request.setRequestHeader("Content-Type", "application/json");
    request.onreadystatechange = function () {
      if (request.readyState !== 4) return;
      var data;
      try {
        data = JSON.parse(request.responseText);
      } catch (e) {
        finish(new Error("电视返回了无效数据"));
        return;
      }
      if (request.status < 200 || request.status >= 300 || data.ok === false) {
        finish(new Error(data.message || "请求失败"));
        return;
      }
      finish(null, data);
    };
    request.onerror = function () {
      finish(new Error("无法连接电视"));
    };
    request.ontimeout = function () {
      finish(new Error("请求超时"));
    };
    request.onabort = function () {
      finish(new Error("请求已中止"));
    };
    request.send(body ? JSON.stringify(body) : null);
  } catch (error) {
    finish(error);
  }
  return request;
}
function setConnectionStatus(text) {
  var items = document.querySelectorAll(".online span:not(.dot)");
  for (var i = 0; i < items.length; i++) items[i].textContent = text;
}
function refresh() {
  if (!pageActive) return;
  if (stateRequest) {
    stateRefreshPending = true;
    return;
  }
  var sequence = ++stateRequestSequence,
    flight = { request: null },
    name = String(location.pathname || "").split("/").pop() || "index.html",
    view = name === "index.html" ? "home" : name.replace(/\.html$/i, "");
  stateRequest = flight;
  flight.request = api("/api/state?view=" + encodeURIComponent(view), null, function (error, data) {
    if (!pageActive || sequence !== stateRequestSequence) return;
    stateRequest = null;
    var again = stateRefreshPending;
    stateRefreshPending = false;
    if (error) {
      setConnectionStatus("连接失败");
      toast(error.message, true);
    } else {
      state = data;
      setConnectionStatus("电视在线");
      renderPageState();
    }
    if (typeof afterStateRefresh === "function") afterStateRefresh(error);
    if (again) refresh();
  });
}
function suspendPage() {
  pageActive = false;
  stateRequestSequence++;
  var flight = stateRequest;
  stateRequest = null;
  stateRefreshPending = false;
  if (flight && flight.request) flight.request.abort();
}
function isPageHidden() {
  return document.hidden === true || document.webkitHidden === true;
}
function startPage() {
  var firstShow = true;
  pageActive = !isPageHidden();
  refresh();
  window.addEventListener(
    "pagehide",
    function () {
      suspendPage();
    },
    false
  );
  window.addEventListener(
    "pageshow",
    function (event) {
      pageActive = !isPageHidden();
      if (!firstShow || event.persisted) refresh();
      firstShow = false;
    },
    false
  );
  function visibilityChanged() {
    if (isPageHidden()) suspendPage();
    else if (!pageActive) {
      pageActive = true;
      refresh();
    }
  }
  document.addEventListener("visibilitychange", visibilityChanged, false);
  document.addEventListener("webkitvisibilitychange", visibilityChanged, false);
}
function goBack() {
  if (typeof window.mediaDismissSheet === "function" && window.mediaDismissSheet()) return;
  if (window.NtvDevice && typeof NtvDevice.returnFromMultimedia === "function" && NtvDevice.returnFromMultimedia()) return;
  if (window.NtvDevice && typeof NtvDevice.returnFromSniffedResource === "function" && NtvDevice.returnFromSniffedResource()) return;
  if (typeof mediaState !== "undefined" && mediaState && mediaState.canReturnToWeb === true) {
    api("/api/control", { action: "returnToWeb" }, function (error) {
      if (error) { toast(error.message, true); return; }
      mediaState.canReturnToWeb = false;
      if (typeof refreshMediaController === "function") refreshMediaController();
    });
    return;
  }
  if (history.length > 1) {
    history.back();
    return;
  }
  location.replace(document.body.getAttribute("data-parent") || "/index.html");
}
function toast(text, bad) {
  var el = document.getElementById("message");
  el.textContent = text;
  el.className = "message" + (bad ? " bad" : "");
  el.style.display = "block";
  clearTimeout(timer);
  timer = setTimeout(
    function () {
      el.style.display = "none";
    },
    bad ? 5000 : 2600
  );
}

function formatBytes(bytes) {
  if (!isFinite(bytes) || bytes <= 0) return "0 B";
  var units = ["B", "KB", "MB", "GB"],
    index = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024))),
    value = bytes / Math.pow(1024, index);
  return (value >= 100 || index === 0 ? Math.round(value) : value.toFixed(1)) + " " + units[index];
}

function receiverLanPrefix() {
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

function normalizeReceiverAddress(value) {
  value = String(value || "").replace(/^\s+|\s+$/g, "");
  if (/^\d{1,3}$/.test(value)) {
    var part = Number(value), prefix = receiverLanPrefix();
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
