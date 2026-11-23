var pluginType = "", pluginBusy = false, catalogPending = false;
var pluginLabels = { tvbus: "TVBus", python: "Python", bl: "BL", tvbox: "TVBox", "file-browser": "文件浏览", ku9py: "Ku9 脚本" };

function refreshPlugins() {
  api("/api/plugins", null, function (error, data) {
    setConnectionStatus(error ? "连接失败" : "电视在线");
    if (error) { toast(error.message, true); return; }
    ["tvbus", "python", "bl", "tvbox", "file-browser"].forEach(function (type) {
      var item = data[type] || { installed: false };
      document.getElementById(type + "Status").textContent = type === "python" && data.pythonRestart
        ? "已更新，重启 nTv 后生效" : item.installed ? "已安装 · " + item.version : "未安装";
    });
    document.getElementById("openBl").disabled = !data.bl.installed;
    document.getElementById("openTvbox").disabled = !data.tvbox.installed;
    document.getElementById("openFiles").disabled = !data["file-browser"] || !data["file-browser"].installed;
    document.getElementById("openKu9py").disabled = !data.python.installed || data.pythonRestart;
  });
}

function refreshCatalog(force) {
  if (catalogPending || pluginBusy) return;
  var list = document.getElementById("pluginCatalog"), status = document.getElementById("pluginCatalogStatus"),
    refresh = document.getElementById("refreshPluginCatalog");
  catalogPending = true;
  refresh.disabled = true;
  status.textContent = "正在获取插件列表…";
  api("/api/plugins?action=catalog" + (force ? "&name=1" : ""), null, function (error, data) {
    catalogPending = false;
    refresh.disabled = false;
    if (error) { status.textContent = error.message; return; }
    status.textContent = (data.cached ? "已保存的发布版本 " : "发布版本 ") + data.release;
    while (list.firstChild) list.removeChild(list.firstChild);
    data.plugins.forEach(function (entry) { catalogRow(list, entry, data.release); });
  }, 22000);
}

function catalogRow(list, entry, release) {
  var row = document.createElement("div"), copy = document.createElement("div"),
    title = document.createElement("b"), details = document.createElement("span"),
    button = document.createElement("button");
  row.className = "browser-setting-row";
  copy.className = "browser-setting-copy";
  title.textContent = entry.name;
  details.textContent = entry.version ? "版本 " + entry.version : "";
  if (entry.pythonVersion) details.textContent = "CPython " + entry.pythonVersion + " · " + entry.file;
  copy.appendChild(title);
  copy.appendChild(details);
  row.appendChild(copy);
  button.type = "button";
  if (entry.action === "install") {
    if (entry.current && entry.kind === "channel" && (entry.id.indexOf("folia") === 0 || entry.id === "file-browser")) {
      button.textContent = "在电视打开";
      button.onclick = function () { openPlugin(entry.id); };
    } else {
      button.textContent = entry.current ? "已安装" : "安装";
      button.disabled = entry.current;
      button.onclick = function () { installCatalogPlugin(entry, release, button); };
    }
  } else if (entry.action === "settings") {
    button.textContent = "偏好设置";
    button.onclick = function () { navigateTo("/pages/personalization.html"); };
  } else if (entry.action === "upload") {
    button.textContent = "上传 JAR";
    button.onclick = function () { choosePlugin("tvbus"); };
  } else {
    details.textContent = entry.action === "archive" ? "可在发布页查看" : "当前设备暂无安装包";
    button = null;
  }
  if (button) row.appendChild(button);
  list.appendChild(row);
}

function installCatalogPlugin(entry, release, button) {
  if (pluginBusy || catalogPending) return;
  window.NtvDialogs.confirm("安装 " + entry.name + " 到电视？", function (confirmed) {
    if (!confirmed || pluginBusy || catalogPending) return;
    var refresh = document.getElementById("refreshPluginCatalog");
    button.disabled = true;
    button.textContent = "安装中…";
    refresh.disabled = true;
    pluginRequest("install-release", entry.id, release, "", function (data) {
      button.textContent = "已安装";
      refresh.disabled = catalogPending;
      toast(entry.name + " 已安装");
      refreshPlugins();
      refreshCatalog(false);
    }, function () {
      button.disabled = false;
      button.textContent = "安装";
      refresh.disabled = catalogPending;
    });
  });
}

function choosePlugin(type) {
  if (pluginBusy) return;
  var input = document.getElementById("pluginFile");
  pluginType = type;
  input.accept = type === "ku9py" ? ".py" : ".jar";
  input.value = "";
  input.click();
}

function uploadPlugin(input) {
  var file = input.files && input.files[0], type = pluginType;
  if (!file || !type || pluginBusy) return;
  window.NtvDialogs.confirm("安装 " + file.name + " 到电视？", function (confirmed) {
    if (!confirmed || pluginBusy) return;
    pluginRequest("install", type, file.name, file, function (data) {
      if (type === "ku9py") document.getElementById("ku9pyUrl").value = data.url;
      toast(pluginLabels[type] + " 已安装");
      refreshPlugins();
      refreshCatalog(false);
    });
  });
}

function openPlugin(type) {
  if (pluginBusy) return;
  var needsUrl = type === "tvbox" || type === "ku9py",
    value = needsUrl ? document.getElementById(type + "Url").value.trim() : "";
  if (needsUrl && !/^https?:\/\//i.test(value)) {
    toast("请输入 HTTP 或 HTTPS 地址", true);
    return;
  }
  pluginRequest("open", type, "", value, function (data) {
    if (type !== "ku9py") {
      document.getElementById("pluginChannelUrl").value = data.url;
      document.getElementById("pluginAddress").hidden = false;
    }
    toast("已发送到电视");
  });
}

function pluginRequest(action, type, name, body, success, failure) {
  var request;
  pluginBusy = true;
  function fail(message) {
    pluginBusy = false;
    if (failure) failure();
    toast(message, true);
  }
  try {
    request = new XMLHttpRequest();
    request.open("POST", "/api/plugins?action=" + action + "&type=" + type + "&name=" + encodeURIComponent(name), true);
    request.setRequestHeader("X-Ntv-Plugin-Control", "1");
    request.setRequestHeader("Content-Type", "application/octet-stream");
    request.timeout = 90000;
    request.onload = function () {
      pluginBusy = false;
      var data;
      try { data = JSON.parse(request.responseText); }
      catch (e) { fail("电视返回了无效数据"); return; }
      if (request.status >= 200 && request.status < 300 && data.ok) success(data);
      else fail(data.message || "插件操作失败");
    };
    request.onerror = function () { fail("无法连接电视"); };
    request.onabort = function () { fail("插件操作已取消"); };
    request.ontimeout = function () { fail("插件操作超时"); refreshPlugins(); };
    request.send(body);
  } catch (error) { fail(error.message); }
}

rememberManagementNavigation();
refreshPlugins();
refreshCatalog(false);
window.addEventListener("pageshow", function (event) {
  if (event.persisted) { refreshPlugins(); refreshCatalog(false); }
});
