// Shared HTTP, page lifecycle and navigation. Page-specific code owns its DOM.
var state = null,
  timer = null,
  stateRequestSequence = 0,
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
}
function setConnectionStatus(text) {
  var items = document.querySelectorAll(".online span:not(.dot)");
  for (var i = 0; i < items.length; i++) items[i].textContent = text;
}
function refresh() {
  if (!pageActive) return;
  var sequence = ++stateRequestSequence,
    name = String(location.pathname || "").split("/").pop() || "index.html",
    view = name === "index.html" ? "home" : name.replace(/\.html$/i, "");
  api("/api/state?view=" + encodeURIComponent(view), null, function (error, data) {
    if (!pageActive || sequence !== stateRequestSequence) return;
    if (error) {
      setConnectionStatus("连接失败");
      toast(error.message, true);
      return;
    }
    state = data;
    setConnectionStatus("电视在线");
    renderPageState();
  });
}
function startPage() {
  var firstShow = true;
  refresh();
  window.addEventListener(
    "pagehide",
    function () {
      pageActive = false;
      stateRequestSequence++;
    },
    false
  );
  window.addEventListener(
    "pageshow",
    function (event) {
      pageActive = true;
      if (!firstShow || event.persisted) refresh();
      firstShow = false;
    },
    false
  );
}
function goBack() {
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
