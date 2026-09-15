var recommended = "",
  recommendedSources = [],
  recommendedEpg = "",
  playlistSources = [],
  playlistGroups = [],
  mergeBusy = false;

// Keep unsaved source edits when visiting the independent group-management page.
// Drafts are scoped to this browser tab/origin and never override changed TV configuration.
var channelPageRendered = false,
  channelBaseSources = "",
  channelBaseEpg = "";
function channelSourceSnapshot(sources) {
  return JSON.stringify(
    sources.map(function (source) {
      return {
        id: source.id,
        name: source.name,
        location: source.location,
        enabled: source.enabled !== false
      };
    })
  );
}
function saveChannelDraft() {
  if (!channelPageRendered) return;
  try {
    sessionStorage.setItem(
      "ntv.channelDraft",
      JSON.stringify({
        baseSources: channelBaseSources,
        sources: playlistSources.map(function (source) {
          var saved = {};
          for (var key in source) if (Object.prototype.hasOwnProperty.call(source, key)) saved[key] = source[key];
          if (saved._kind === "loading") { saved._kind = "bad"; saved._status = "上次操作未完成，请重试"; }
          return saved;
        }),
        baseEpg: channelBaseEpg,
        epg: document.getElementById("epgUrl").value
      })
    );
  } catch (error) {
    /* Storage can be unavailable in a restricted WebView. */
  }
}
function readChannelDraft() {
  try {
    return JSON.parse(sessionStorage.getItem("ntv.channelDraft") || "null");
  } catch (error) {
    return null;
  }
}
function saveAutoUpdateChannelList() {
  var input = document.getElementById("autoUpdateChannelList");
  api("/api/settings", { autoUpdateChannelList: input.checked }, function (error) {
    if (error) {
      input.checked = !input.checked;
      toast(error.message, true);
      return;
    }
    toast(input.checked ? "已开启冷启动自动更新" : "已关闭自动更新频道列表");
  });
}

function newPlaylistSource(name, location) {
  return {
    id: "source_" + Date.now() + "_" + Math.floor(Math.random() * 10000),
    name: name || "频道源 " + (playlistSources.length + 1),
    location: location || "",
    enabled: true
  };
}

function addPlaylistSource() {
  playlistSources.push(newPlaylistSource("", ""));
  renderPlaylistSources();
  var items = document.getElementById("playlistSources").getElementsByClassName("source-location");
  if (items.length) items[items.length - 1].focus();
}

function addLocalPlaylistSource() {
  var source = newPlaylistSource("", "");
  playlistSources.push(source);
  renderPlaylistSources();
  choosePlaylistFile(source);
}

function openRecommendedSourcePicker() {
  var picker = document.getElementById("recommendedSourcePicker"),
    choices = document.getElementById("recommendedSourceChoices"),
    items = recommendedSources.length
      ? recommendedSources
      : [{ name: "网页电视台", url: recommended }];
  choices.innerHTML = "";
  for (var i = 0; i < items.length; i++) {
    var source = items[i],
      button = document.createElement("button"),
      name = document.createElement("b"),
      detail = document.createElement("span");
    button.className = "source-choice";
    button.type = "button";
    name.textContent = source.name || "推荐频道源";
    detail.textContent = source.url || "";
    button.appendChild(name);
    button.appendChild(detail);
    button._source = source;
    button.onclick = function () {
      addRecommendedSource(this._source);
    };
    choices.appendChild(button);
  }
  picker.className = "source-picker show";
}

function closeRecommendedSourcePicker(event) {
  var picker = document.getElementById("recommendedSourcePicker");
  if (event && event.target !== picker) return;
  picker.className = "source-picker";
}

function addRecommendedSource(source) {
  source = source || { name: "推荐频道源", url: recommended };
  if (!source.url) {
    toast("推荐源地址不可用", true);
    return;
  }
  for (var i = 0; i < playlistSources.length; i++) {
    if (playlistSources[i].location === source.url) {
      closeRecommendedSourcePicker();
      toast("“" + (source.name || "推荐源") + "”已经添加");
      return;
    }
  }
  playlistSources.push(newPlaylistSource(source.name || "推荐频道源", source.url));
  closeRecommendedSourcePicker();
  renderPlaylistSources();
  toast("已添加“" + (source.name || "推荐源") + "”");
}

function removePlaylistSource(index) {
  if (mergeBusy || playlistFileBusy) return;
  playlistSources.splice(index, 1);
  renderPlaylistSources();
}

function updateConfigStats(groupCount, channelCount) {
  var enabled = 0;
  for (var i = 0; i < playlistSources.length; i++)
    if (playlistSources[i].enabled !== false) enabled++;
  document.getElementById("configSourceStat").textContent = enabled + " 个来源";
  if (typeof groupCount === "number")
    document.getElementById("configGroupStat").textContent = groupCount + " 个分组";
  if (typeof channelCount === "number")
    document.getElementById("configChannelStat").textContent = channelCount + " 个频道";
}

function renderPlaylistSources() {
  var list = document.getElementById("playlistSources");
  list.innerHTML = "";
  if (!playlistSources.length) {
    var empty = document.createElement("p");
    empty.className = "hint";
    empty.textContent = "还没有频道来源，可添加源地址或推荐源。";
    list.appendChild(empty);
    updateConfigStats();
    return;
  }
  for (var i = 0; i < playlistSources.length; i++) {
    var source = playlistSources[i],
      item = document.createElement("div");
    item.className = "source-item" + (source._kind ? " " + source._kind : "");
    var head = document.createElement("div");
    head.className = "source-head";
    var enabled = document.createElement("input");
    enabled.type = "checkbox";
    enabled.className = "source-toggle";
    enabled.checked = source.enabled !== false;
    enabled.disabled = mergeBusy;
    enabled.setAttribute("aria-label", "启用频道源");
    enabled._source = source;
    enabled.onchange = function () {
      this._source.enabled = this.checked;
      this._source._status = "";
      this._source._kind = "";
      updateConfigStats();
    };
    var name = document.createElement("input");
    name.className = "source-name";
    name.placeholder = "来源名称";
    name.value = source.name || "";
    name.disabled = mergeBusy;
    name._source = source;
    name.oninput = function () {
      this._source.name = this.value;
    };
    var remove = document.createElement("button");
    remove.className = "source-delete";
    remove.textContent = "删除";
    remove.disabled = mergeBusy;
    remove._index = i;
    remove.onclick = function () {
      removePlaylistSource(this._index);
    };
    head.appendChild(enabled);
    head.appendChild(name);
    head.appendChild(remove);
    var row = document.createElement("div");
    row.className = "source-location-row";
    var location = document.createElement("input");
    location.className = "source-location";
    location.type = "text";
    location.inputMode = "url";
    location.placeholder = "粘贴 M3U / M3U8 / TXT 地址";
    location.value = source.location || "";
    location.disabled = mergeBusy;
    location._source = source;
    location.oninput = function () {
      this._source.location = this.value;
      this._source._status = "";
      this._source._kind = "";
    };
    location.onchange = function () {
      var problem = playlistAddressProblem(this.value, true);
      this._source._status = problem ? playlistFormatError(this._source, 0, this.value, problem).message : "等待刷新";
      this._source._kind = problem ? "bad" : "";
      renderPlaylistSources();
    };
    var file = document.createElement("button");
    file.type = "button";
    file.className = "source-file";
    file.disabled = mergeBusy;
    file.title = "选择本地频道源文件";
    file.setAttribute("aria-label", "选择本地频道源文件");
    file.innerHTML =
      '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6.5 3.5h7l4 4v13h-11z"/><path d="M13.5 3.5v4h4M9 12h6M9 16h6"/></svg><span>本地</span>';
    file._source = source;
    file.onclick = function () {
      choosePlaylistFile(this._source);
    };
    row.appendChild(location);
    row.appendChild(file);
    var status = document.createElement("div");
    status.className = "source-state";
    status.textContent =
      source.enabled === false ? "已关闭，不参与刷新" : source._status || "等待刷新";
    item.appendChild(head);
    item.appendChild(row);
    item.appendChild(status);
    list.appendChild(item);
  }
  updateConfigStats();
}
function updateMergedConfigStatsFromGroups() {
  if (!state || !state.settings.mobileMergedPlaylist) return;
  var total = 0;
  for (var i = 0; i < playlistGroups.length; i++)
    total += Number(playlistGroups[i].channelCount) || 0;
  updateConfigStats(playlistGroups.length, total);
}
var playlistFileTarget = null, playlistFileBusy = false;

function choosePlaylistFile(source) {
  if (playlistFileBusy || mergeBusy) { toast("请等待当前操作完成", true); return; }
  playlistFileTarget = source.id;
  var picker = document.getElementById("playlistFilePicker");
  picker.value = "";
  picker.click();
}

function playlistFileSelected(picker) {
  var file = picker.files && picker.files[0], source = null, id = playlistFileTarget;
  playlistFileTarget = null;
  if (!file || playlistFileBusy) return;
  // Polling may have replaced source objects while the system picker was open.
  for (var i = 0; i < playlistSources.length; i++)
    if (playlistSources[i].id === id) { source = playlistSources[i]; break; }
  if (!source) { toast("该频道源已移除，请重新选择", true); return; }
  if (!file.size) { toast("所选文件为空，请重新选择", true); return; }
  if (file.size > 64 * 1024 * 1024) { toast("频道文件不能超过 64 MB", true); return; }
  playlistFileBusy = true;
  var request = null, reader = null, finished = false, timer;
  function status(text) {
    source._status = text; source._kind = "loading"; renderPlaylistSources();
  }
  function complete(error, data) {
    if (finished) return;
    finished = true;
    clearTimeout(timer);
    playlistFileBusy = false;
    if (error) {
      source._status = error; source._kind = "bad";
    } else {
      source.location = data.location;
      if (!source.name || /^频道源\s+\d+$/.test(source.name))
        source.name = String(data.name || file.name).replace(/\.(m3u8?|txt)$/i, "");
      source._status = "文件已就绪，等待手机合并"; source._kind = "good";
    }
    renderPlaylistSources();
    saveChannelDraft();
    toast(error || "本地文件已添加", !!error);
  }
  function deadline(ms, message) {
    clearTimeout(timer);
    timer = setTimeout(function () {
      complete(message);
      if (request) request.abort();
      if (reader && reader.readyState === 1) reader.abort();
    }, ms);
  }
  function upload(body) {
    if (finished) return;
    status("正在保存 " + file.name);
    deadline(60000, "保存超时，请检查手机与电视的连接后重新选择文件");
    try {
      request = new XMLHttpRequest();
      request.open("POST", "/api/playlist/upload?id=" + encodeURIComponent(source.id || "")
        + "&name=" + encodeURIComponent(file.name || "本地频道源"), true);
      request.timeout = 60000;
      request.setRequestHeader("Content-Type", "application/octet-stream");
      request.onreadystatechange = function () {
        if (request.readyState !== 4 || request.status === 0 || finished) return;
        var data;
        try { data = JSON.parse(request.responseText); }
        catch (error) { complete("电视返回了无效数据，请重试"); return; }
        if (!data || request.status < 200 || request.status >= 300 || data.ok === false) {
          complete(data && data.message || "本地文件保存失败：HTTP " + request.status); return;
        }
        if (!data.location) { complete("电视未返回文件保存位置，请重试"); return; }
        complete(null, data);
      };
      request.onerror = function () { complete("无法把文件发送到电视，请检查局域网连接"); };
      request.ontimeout = function () { complete("保存超时，请检查局域网连接后重试"); };
      request.onabort = function () { complete("文件保存已取消，请重新选择"); };
      request.send(body);
    } catch (error) { complete("无法发送所选文件：" + error.message); }
  }
  status("正在读取 " + file.name);
  deadline(30000, "读取文件超时，请将文件下载到手机后重新选择");
  if (typeof FileReader === "undefined") { upload(file); return; }
  try {
    reader = new FileReader();
    reader.onload = function () { upload(reader.result); };
    reader.onerror = function () { complete("无法读取所选文件，请检查文件访问权限后重新选择"); };
    reader.onabort = function () { complete("文件读取已取消，请重新选择"); };
    reader.readAsArrayBuffer(file);
  } catch (error) { complete("无法读取所选文件：" + error.message); }
}

function chooseKu9JsFile() {
  var picker = document.getElementById("ku9JsFilePicker");
  picker.value = "";
  picker.click();
}

function ku9JsFileSelected(picker) {
  var file = picker.files && picker.files[0];
  if (!file) return;
  if (!/^[A-Za-z0-9_.-]+\.js$/i.test(file.name || "")) {
    toast("请选择名称只含字母、数字、点、横线或下划线的 .js 文件", true);
    return;
  }
  if (!file.size) {
    toast("JS 文件内容为空", true);
    return;
  }
  if (file.size > 2 * 1024 * 1024) {
    toast("JS 文件不能超过 2 MB", true);
    return;
  }
  if (!window.confirm("上传 " + file.name + "？如果电视中已有同名文件将被覆盖。")) return;
  var button = document.getElementById("ku9JsUploadButton"),
    status = document.getElementById("ku9JsUploadStatus");
  button.disabled = true;
  button.textContent = "上传中…";
  status.textContent = "正在读取 " + file.name;
  function complete(message, bad) {
    button.disabled = false;
    button.textContent = "选择文件";
    status.textContent = message;
    toast(message, bad);
  }
  function upload(body) {
    var request = new XMLHttpRequest();
    status.textContent = "正在发送 " + file.name;
    request.open("POST", "/api/ku9/script/upload?name=" + encodeURIComponent(file.name), true);
    request.timeout = 60000;
    request.setRequestHeader("Content-Type", "application/octet-stream");
    request.onreadystatechange = function () {
      if (request.readyState !== 4 || request.status === 0) return;
      var data;
      try {
        data = JSON.parse(request.responseText);
      } catch (error) {
        complete("电视返回了无效数据", true);
        return;
      }
      if (request.status < 200 || request.status >= 300 || data.ok === false) {
        complete(data.message || "JS 文件上传失败", true);
        return;
      }
      complete(
        (data.replaced ? "已覆盖 " : "已上传 ") + (data.name || file.name) + "，电视已校验可读取",
        false
      );
    };
    request.onerror = function () {
      complete("无法把 JS 文件发送到电视，请确认手机与电视处于同一局域网", true);
    };
    request.ontimeout = function () {
      complete("上传超时，请检查局域网连接后重试", true);
    };
    request.send(body);
  }
  if (typeof FileReader === "undefined") {
    upload(file);
    return;
  }
  var reader = new FileReader();
  reader.onload = function () {
    upload(reader.result);
  };
  reader.onerror = function () {
    complete("无法读取所选 JS 文件", true);
  };
  reader.readAsArrayBuffer(file);
}

function cleanPlaylistSources() {
  var cleaned = [];
  for (var i = 0; i < playlistSources.length; i++) {
    var source = playlistSources[i],
      name = String(source.name || "").replace(/^\s+|\s+$/g, ""),
      location = String(source.location || "").replace(/^\s+|\s+$/g, "");
    if (!name && !location) continue;
    var problem = playlistAddressProblem(location, true);
    if (problem && source.enabled !== false) {
      var error = playlistFormatError(source, 0, source.location, problem);
      source._status = error.message;
      source._kind = "bad";
      renderPlaylistSources();
      throw error;
    }
    cleaned.push({
      id: source.id || "",
      name: name || "频道源 " + (cleaned.length + 1),
      location: location,
      enabled: source.enabled !== false
    });
  }
  return cleaned;
}

function setMergeUi(title, detail, stateName) {
  var panel = document.getElementById("mergePanel");
  panel.className = "merge-panel" + (stateName ? " " + stateName : "");
  document.getElementById("mergeTitle").textContent = title;
  document.getElementById("mergeDetail").textContent = detail || "";
}

function setSourceState(source, text, kind) {
  source._status = text;
  source._kind = kind || "";
  renderPlaylistSources();
}

function isLocalPlaylistLocation(location) {
  return /^(file|content):\/\//i.test(location) || location.charAt(0) === "/";
}

function playlistFormatError(source, line, value, reason) {
  var error = new Error("来源：" + (source.name || "频道来源") + (line ? " · 第 " + line + " 行" : "")
    + "\n错误内容：" + (value == null || value === "" ? "（空）" : value)
    + "\n格式问题：" + reason);
  error.playlistFormat = true;
  return error;
}

function playlistAddressProblem(value, sourceLocation) {
  var text = String(value || "").replace(/^\s+|\s+$/g, "");
  if (!text) return "请填写地址，或通过“本地”按钮选择频道文件";
  if (/#genre#/i.test(text)) return "#genre# 是分组标记，应单独写成“分组名称,#genre#”，不能拼在地址后面";
  if (/^\[.*\]\(/.test(text)) return "请填写纯网址，不要粘贴 Markdown 的 [文字](网址) 格式";
  if (sourceLocation && isLocalPlaylistLocation(text)) return "";
  if (/[\s<>"\\^`{}|]/.test(text)) return "网址包含空格或非法字符，请使用编码后的完整网址";
  if (/%(?![0-9a-f]{2})/i.test(text)) return "百分号后需要两位十六进制编码，例如空格应写为 %20";
  if (text.indexOf("#") !== text.lastIndexOf("#")) return "网址中包含重复的 # 分隔符";
  if (/^webview:\/\//i.test(text) && !sourceLocation) text = text.replace(/^webview:\/\//i, "");
  var absolute = /^(https?|rtmps?|rtmpt|rtsp):\/\/([^/?#]+)/i.exec(text);
  if (absolute) {
    if (sourceLocation && !/^https?:/i.test(text)) return "频道列表来源需要 HTTP/HTTPS 地址，或选择本地文件";
    if (typeof URL === "function") {
      try { if (!new URL(text).hostname) return "网址缺少服务器域名或 IP"; }
      catch (error) { return "网址的服务器、端口或字符格式不正确"; }
    }
    return "";
  }
  if (sourceLocation) return "请填写以 http:// 或 https:// 开头的完整频道列表地址，或选择本地文件";
  if (/^[a-z][a-z0-9+.-]*:/i.test(text)) return "协议或服务器地址不正确";
  return ""; // M3U files may use paths relative to the playlist URL.
}

var githubWorkingPrefix = "", githubWorkingUntil = 0, githubFailedUntil = {}, githubRouteSetting = "";
function githubSourceRoutes(value) {
  var url = String(value || "").replace(/^\s+|\s+$/g, ""),
    settings = state && state.settings || {},
    prefix = settings.githubProxyBaseUrl || "https://gh-proxy.org/",
    known = [prefix, "https://gh-proxy.org/", "https://gh-proxy.com/",
      "https://ghfile.geekertao.top/", "https://github-proxy.memory-echoes.cn/", "https://github.tbap.top/"],
    setting = prefix + ":" + (settings.githubProxyEnabled !== false), now = Date.now();
  if (setting !== githubRouteSetting) {
    githubRouteSetting = setting; githubWorkingPrefix = ""; githubFailedUntil = {};
  }
  for (var i = 0; i < known.length; i++)
    if (url.indexOf(known[i]) === 0) { url = url.substring(known[i].length); break; }
  var anchor = document.createElement("a"); anchor.href = url;
  var host = String(anchor.hostname || "").toLowerCase();
  var github = /^https?:\/\//i.test(url) && (host === "github.com" || host === "raw.githubusercontent.com" ||
    host === "objects.githubusercontent.com" || host === "release-assets.githubusercontent.com");
  if (!github) return [{url:value, prefix:""}];
  if (settings.githubProxyEnabled === false) return [{url:url, prefix:""}];
  if (githubWorkingPrefix && githubWorkingUntil > now) known.unshift(githubWorkingPrefix);
  var routes = [], seen = {};
  for (var j = 0; j < known.length; j++) {
    var route = known[j];
    if (seen[route]) continue;
    seen[route] = true;
    if (githubFailedUntil[route] > now) continue;
    routes.push({url:route + url, prefix:route});
  }
  return routes;
}
function githubProxySourceUrl(value) {
  var routes = githubSourceRoutes(value);
  return routes.length ? routes[0].url : value;
}

function requestPlaylistText(source, done) {
  var routes = githubSourceRoutes(source.location), index = 0;
  function next() {
    if (index < routes.length && !isLocalPlaylistLocation(routes[index].url) && /^https?:\/\//i.test(routes[index].url))
      load(routes[index++], true);
    else load({url:"/api/playlist/source?location=" + encodeURIComponent(source.location), prefix:""}, false);
  }
  function load(route, fallback) {
    var request = new XMLHttpRequest(), settled = false;
    function complete(error) {
      if (settled) return;
      settled = true;
      if (!error) {
        if (route.prefix) {
          githubWorkingPrefix = route.prefix; githubWorkingUntil = Date.now() + 300000;
          delete githubFailedUntil[route.prefix];
        }
        done(null, request.responseText || ""); return;
      }
      if (fallback) {
        var status = request.status || 0;
        if (route.prefix && (!status || status === 403 || status === 408 || status === 429 || status >= 500)) {
          githubFailedUntil[route.prefix] = Date.now() + 60000;
        } else index = routes.length;
        next();
      } else done(error);
    }
    try {
      request.open("GET", route.url, true);
      request.timeout = fallback ? 8000 : 60000;
      request.onreadystatechange = function () {
        if (request.readyState !== 4 || request.status === 0) return;
        complete(request.status >= 200 && request.status < 300 ? null : new Error("读取失败：HTTP " + request.status));
      };
      request.onerror = function () { complete(new Error("无法读取频道源")); };
      request.ontimeout = function () { complete(new Error("读取频道源超时，请检查连接或切换 GitHub 直连")); };
      request.onabort = function () { complete(new Error("频道源读取已取消")); };
      request.send();
    } catch (error) { complete(error); }
  }
  next();
}

function playlistAttribute(line, name) {
  var match = new RegExp("(?:^|\\s)" + name + '="([^"]*)"', "i").exec(line);
  return match ? match[1] : "";
}

function playlistStreamUrl(value, base) {
  var text = String(value || "").replace(/^\s+|\s+$/g, "");
  if (/^(https?|rtmps?|rtmpt|rtsp|webview):\/\//i.test(text)) return text;
  if (!/^https?:\/\//i.test(base)) return "";
  try {
    return new URL(text, base).href;
  } catch (error) {
    return "";
  }
}

function parsePlaylistOnPhone(text, source) {
  var lines = String(text || "")
      .replace(/^\uFEFF/, "")
      .replace(/\r/g, "")
      .split("\n"),
    group = "在线频道",
    pending = null,
    entries = [],
    epg = "",
    pendingLine = 0,
    warningCount = 0,
    firstWarning = "";
  function invalid(lineNumber, raw, reason) {
    warningCount++;
    if (!firstWarning) firstWarning = playlistFormatError(source, lineNumber, raw, reason).message;
  }
  function stream(value, lineNumber, raw) {
    var problem = playlistAddressProblem(value, false);
    if (problem) { invalid(lineNumber, raw, problem); return ""; }
    var result = playlistStreamUrl(value, source.location);
    if (!result) invalid(lineNumber, raw, "无法解析频道地址；本地列表中的频道请使用完整网址");
    return result;
  }
  function subtitle(value, lineNumber) {
    value = String(value || "").replace(/^\s+|\s+$/g, "").replace(/^(["'])(.*)\1$/, "$2");
    if (!value) return;
    var url = playlistStreamUrl(value, source.location);
    if (!/^https?:\/\//i.test(url)) { invalid(lineNumber, value, "字幕需要 HTTP/HTTPS 地址，本地列表请填写完整网址"); return; }
    if (pending.subtitleUrls.indexOf(url) < 0 && pending.subtitleUrls.length < 16) pending.subtitleUrls.push(url);
  }
  if (lines.length && /^#EXTM3U/i.test(lines[0]))
    epg = playlistAttribute(lines[0], "x-tvg-url") || playlistAttribute(lines[0], "url-tvg");
  for (var i = 0; i < lines.length; i++) {
    var line = lines[i].replace(/^\s+|\s+$/g, "");
    if (!line) continue;
    if (/^#EXTINF:/i.test(line)) {
      if (pending) invalid(pendingLine, lines[pendingLine - 1], "#EXTINF 后缺少频道播放地址");
      pendingLine = i + 1;
      var comma = line.lastIndexOf(",");
      pending = {
        name:
          comma >= 0
            ? line.substring(comma + 1).replace(/^\s+|\s+$/g, "")
            : playlistAttribute(line, "tvg-name"),
        group: playlistAttribute(line, "group-title") || group,
        epgId: playlistAttribute(line, "tvg-id"),
        logoUrl: playlistAttribute(line, "tvg-logo"),
        subtitleUrls: [],
        radio: /^(true|1)$/i.test(playlistAttribute(line, "radio"))
      };
      subtitle(playlistAttribute(line, "subtitles") || playlistAttribute(line, "subtitle"), i + 1);
      continue;
    }
    if (pending && /^#EXTVLCOPT:sub-file=/i.test(line)) subtitle(line.substring(20), i + 1);
    if (line.charAt(0) === "#") continue;
    var url;
    if (pending) {
      url = stream(line, i + 1, lines[i]);
      if (url) entries.push({
        name: pending.name || "未命名频道",
        group: pending.group || group,
        epgId: pending.epgId || "",
        logoUrl: pending.logoUrl || "",
        radio: pending.radio,
        subtitleUrls: pending.subtitleUrls,
        url: url
      });
      pending = null;
      continue;
    }
    var split = line.indexOf(",");
    if (split <= 0) {
      invalid(i + 1, lines[i], "需要“频道名称,播放地址”，或 M3U 的 #EXTINF 与地址两行格式");
      continue;
    }
    var name = line.substring(0, split).replace(/^\s+|\s+$/g, ""),
      value = line.substring(split + 1).replace(/^\s+|\s+$/g, "");
    // Some TXT generators leave empty CSV columns after group markers.
    if (/^#genre#(?:\s*,\s*)*$/i.test(value)) {
      group = name || "在线频道";
      continue;
    }
    // Ignore empty columns before an explicit URL, never commas inside its query.
    value = value.replace(/^(?:,\s*)+(?=(?:https?|rtmps?|rtmpt|rtsp|webview):\/\/)/i, "");
    url = stream(value, i + 1, lines[i]);
    if (url)
      entries.push({
        name: name || "未命名频道",
        group: group,
        epgId: "",
        url: url
      });
  }
  if (pending) invalid(pendingLine, lines[pendingLine - 1], "#EXTINF 后缺少频道播放地址");
  if (!entries.length && !warningCount) invalid(0, source.location, "没有找到可播放频道，请检查文件内容是否为 TXT 或 M3U 频道列表");
  return { entries: entries, epg: epg, warningCount: warningCount, firstWarning: firstWarning };
}

function canonicalPlaylistText(value) {
  return String(value || "")
    .toLowerCase()
    .replace(/[^a-z0-9+\u4e00-\u9fff]/g, "");
}

function canonicalPlaylistUrl(value) {
  var text = String(value || "")
    .replace(/^webview:\/\//i, "")
    .replace(/#.*$/, "")
    .replace(/\/$/, "")
    .toLowerCase();
  return text;
}

function mergePhoneEntries(results) {
  var merged = [],
    byName = {},
    byUrl = {},
    epg = "";
  for (var r = 0; r < results.length; r++) {
    if (!epg && results[r].epg) epg = results[r].epg;
    var entries = results[r].entries;
    for (var i = 0; i < entries.length; i++) {
      var item = entries[i],
        group = item.group || "在线频道",
        nameKey = canonicalPlaylistText(group) + "|" + canonicalPlaylistText(item.name),
        urlKey = canonicalPlaylistText(group) + "|" + canonicalPlaylistUrl(item.url),
        target = byName[nameKey] || byUrl[urlKey];
      if (!target) {
        target = {
          name: item.name,
          group: group,
          epgId: item.epgId || "",
          logoUrl: item.logoUrl || "",
          radio: !!item.radio,
          subtitleUrls: [],
          urls: []
        };
        merged.push(target);
        byName[nameKey] = target;
      }
      var duplicate = false;
      if (!target.logoUrl && item.logoUrl) target.logoUrl = item.logoUrl;
      if (item.radio) target.radio = true;
      var subtitles = item.subtitleUrls || [];
      for (var sub = 0; sub < subtitles.length; sub++)
        if (target.subtitleUrls.length < 16 && target.subtitleUrls.indexOf(subtitles[sub]) < 0) target.subtitleUrls.push(subtitles[sub]);
      for (var u = 0; u < target.urls.length; u++)
        if (canonicalPlaylistUrl(target.urls[u]) === canonicalPlaylistUrl(item.url)) {
          duplicate = true;
          break;
        }
      if (!duplicate) target.urls.push(item.url);
      byUrl[urlKey] = target;
    }
  }
  return { channels: merged, epg: epg };
}

function escapePlaylistAttribute(value) {
  return String(value || "")
    .replace(/"/g, "'")
    .replace(/[\r\n]/g, " ");
}

function buildMergedM3u(merged) {
  var selectedEpg = document.getElementById("epgUrl").value.replace(/^\s+|\s+$/g, ""),
    epg = selectedEpg || merged.epg || "",
    lines = ["#EXTM3U" + (epg ? ' x-tvg-url="' + escapePlaylistAttribute(epg) + '"' : "")],
    groups = {};
  for (var i = 0; i < merged.channels.length; i++) {
    var item = merged.channels[i];
    groups[item.group] = true;
    for (var u = 0; u < item.urls.length; u++) {
      lines.push(
        '#EXTINF:-1 tvg-id="' +
          escapePlaylistAttribute(item.epgId) +
          '" tvg-name="' +
          escapePlaylistAttribute(item.name) +
          '" tvg-logo="' +
          escapePlaylistAttribute(item.logoUrl) +
          (item.radio ? '" radio="true' : '') +
          '" group-title="' +
          escapePlaylistAttribute(item.group) +
          '",' +
          item.name.replace(/[\r\n]/g, " ")
      );
      var subtitles = item.subtitleUrls || [];
      for (var sub = 0; sub < subtitles.length; sub++) lines.push("#EXTVLCOPT:sub-file=" + subtitles[sub].replace(/[\r\n]/g, ""));
      lines.push(item.urls[u]);
    }
  }
  return {
    text: lines.join("\n") + "\n",
    groupCount: Object.keys(groups).length,
    channelCount: merged.channels.length,
    epg: epg
  };
}

function mergeAndPushPlaylistSources() {
  if (mergeBusy || playlistFileBusy) return;
  var cleaned;
  try {
    cleaned = cleanPlaylistSources();
  } catch (error) {
    toast(error.message, true);
    return;
  }
  var enabled = [];
  for (var i = 0; i < cleaned.length; i++) if (cleaned[i].enabled) enabled.push(cleaned[i]);
  if (!enabled.length) {
    toast("请至少开启一个频道来源", true);
    return;
  }
  playlistSources = cleaned;
  mergeBusy = true;
  renderPlaylistSources();
  document.getElementById("mergeButton").disabled = true;
  setMergeUi("手机正在整理", "读取 0 / " + enabled.length + " 个来源", "working");
  var results = [],
    failures = [],
    warningCount = 0,
    firstWarning = "",
    index = 0;
  function finish(error) {
    if (error) {
      mergeBusy = false;
      document.getElementById("mergeButton").disabled = false;
      setMergeUi("刷新未完成", error.message || String(error), "");
      toast(error.message || String(error), true);
      renderPlaylistSources();
      return;
    }
    var merged = mergePhoneEntries(results);
    if (!merged.channels.length) {
      finish(new Error("没有可刷新的频道，已保留电视频道列表" + (firstWarning ? "\n" + firstWarning : "")));
      return;
    }
    var built = buildMergedM3u(merged);
    var summary = built.groupCount + " 个分组 · " + built.channelCount + " 个频道";
    if (failures.length) summary += " · 跳过 " + failures.length + " 个失败来源";
    setMergeUi("正在刷新电视", summary, "working");
    api(
      "/api/playlist/merge",
      {
        sources: cleaned,
        playlist: built.text,
        mergedSourceCount: results.length
      },
      function (pushError, response) {
        mergeBusy = false;
        document.getElementById("mergeButton").disabled = false;
        if (pushError) {
          setMergeUi("刷新失败", pushError.message, "");
          toast(pushError.message, true);
          renderPlaylistSources();
          return;
        }
        channelBaseSources = channelSourceSnapshot(cleaned);
        function completed() {
          var detail = response.message || "电视频道列表已刷新";
          if (failures.length) detail += "；" + failures.length + " 个来源待重试";
          setMergeUi("频道列表已刷新", detail, "done");
          updateConfigStats(
            Number(response.groupCount) || built.groupCount,
            Number(response.channelCount) || built.channelCount
          );
          toast(detail + (warningCount ? "；已跳过 " + warningCount + " 条格式错误\n" + firstWarning : ""), warningCount > 0);
          setTimeout(refresh, 700);
        }
        var epgValue = document.getElementById("epgUrl").value.replace(/^\s+|\s+$/g, "");
        api("/api/settings", { epgUrl: epgValue }, function (epgError) {
          if (!epgError) channelBaseEpg = epgValue;
          completed();
        });
      }
    );
  }
  function next() {
    if (index >= enabled.length) {
      finish(null);
      return;
    }
    var source = enabled[index++];
    setSourceState(source, "手机正在读取…", "loading");
    setMergeUi(
      "手机正在整理",
      "读取 " + index + " / " + enabled.length + "：" + source.name,
      "working"
    );
    requestPlaylistText(source, function (error, text) {
      if (error) {
        failures.push(source.name);
        setSourceState(source, error.message, "bad");
        next();
        return;
      }
      try {
        var parsed = parsePlaylistOnPhone(text, source);
        warningCount += parsed.warningCount;
        if (!firstWarning) firstWarning = parsed.firstWarning;
        if (parsed.entries.length) results.push(parsed);
        else failures.push(source.name);
        setSourceState(source, "已解析 " + parsed.entries.length + " 个频道"
          + (parsed.warningCount ? " · 跳过 " + parsed.warningCount + " 条格式错误" : ""),
          parsed.entries.length ? "good" : "bad");
      } catch (parseError) {
        if (!firstWarning) firstWarning = parseError.message;
        failures.push(source.name);
        setSourceState(source, parseError.message, "bad");
      }
      next();
    });
  }
  next();
}

function savePlaylistSources() {
  mergeAndPushPlaylistSources();
}

function useRecommendedEpg() {
  var input = document.getElementById("epgUrl");
  input.value = recommendedEpg;
  input.focus();
}

function saveEpg() {
  var value = document.getElementById("epgUrl").value.replace(/^\s+|\s+$/g, "");
  api("/api/settings", { epgUrl: value }, function (error) {
    if (error) {
      toast(error.message, true);
      return;
    }
    toast(value ? "节目单已保存，正在更新" : "已恢复自动选择节目单");
    channelBaseEpg = value;
    setTimeout(refresh, 500);
  });
}
function renderPageState() {
  if (mergeBusy || playlistFileBusy) return;
  saveChannelDraft();
  var draft = readChannelDraft();
  var s = state.settings;
  document.getElementById("autoUpdateChannelList").checked = s.autoUpdateChannelList === true;
  playlistSources = JSON.parse(JSON.stringify(s.playlistSources || []));
  channelBaseSources = channelSourceSnapshot(playlistSources);
  channelBaseEpg = s.epgUrl || "";
  if (s.mobileMergedPlaylist)
    for (var p = 0; p < playlistSources.length; p++)
      if (playlistSources[p].enabled !== false) {
        playlistSources[p]._status = "已包含在电视配置中";
        playlistSources[p]._kind = "good";
      }
  if (draft && draft.baseSources === channelBaseSources && Array.isArray(draft.sources)) {
    playlistSources = draft.sources;
  }
  renderPlaylistSources();
  playlistGroups = s.playlistGroups || [];
  updateMergedConfigStatsFromGroups();
  document.getElementById("epgUrl").value =
    draft && draft.baseEpg === channelBaseEpg ? draft.epg : channelBaseEpg;
  recommended = s.recommendedPlaylistUrl;
  recommendedSources = s.recommendedPlaylistSources || [];
  recommendedEpg = s.recommendedEpgUrl || "";
  channelPageRendered = true;
}
window.addEventListener("pagehide", saveChannelDraft, false);
window.addEventListener(
  "beforeunload",
  function (event) {
    saveChannelDraft();
    if (mergeBusy || playlistFileBusy) {
      event.preventDefault();
      event.returnValue = "正在整理频道，离开会中断刷新";
      return event.returnValue;
    }
  },
  false
);
startPage();
