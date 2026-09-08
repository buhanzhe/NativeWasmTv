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
        sources: playlistSources,
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
  if (mergeBusy) return;
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
    empty.textContent = "还没有频道来源，可添加网络地址或推荐源。";
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
    var file = document.createElement("button");
    file.type = "button";
    file.className = "source-file";
    file.disabled = mergeBusy;
    file.title = "选择本地频道源文件";
    file.setAttribute("aria-label", "选择本地频道源文件");
    file.innerHTML =
      '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6.5 3.5h7l4 4v13h-11z"/><path d="M13.5 3.5v4h4M9 12h6M9 16h6"/></svg>';
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
var playlistFileTarget = null;

function choosePlaylistFile(source) {
  playlistFileTarget = source;
  var picker = document.getElementById("playlistFilePicker");
  picker.value = "";
  picker.click();
}

function playlistFileSelected(picker) {
  var file = picker.files && picker.files[0],
    source = playlistFileTarget;
  playlistFileTarget = null;
  if (!file || !source) return;
  source._status = "正在保存 " + file.name;
  source._kind = "loading";
  renderPlaylistSources();
  var request = new XMLHttpRequest(),
    url =
      "/api/playlist/upload?id=" +
      encodeURIComponent(source.id || "") +
      "&name=" +
      encodeURIComponent(file.name || "本地频道源");
  request.open("POST", url, true);
  request.setRequestHeader("Content-Type", "application/octet-stream");
  request.onreadystatechange = function () {
    if (request.readyState !== 4) return;
    var data;
    try {
      data = JSON.parse(request.responseText);
    } catch (e) {
      source._status = "电视返回了无效数据";
      source._kind = "bad";
      renderPlaylistSources();
      return;
    }
    if (request.status < 200 || request.status >= 300 || data.ok === false) {
      source._status = data.message || "本地文件保存失败";
      source._kind = "bad";
      renderPlaylistSources();
      return;
    }
    source.location = data.location || "";
    if (!source.name || /^频道源\s+\d+$/.test(source.name)) {
      source.name = String(data.name || file.name).replace(/\.(m3u8?|txt)$/i, "");
    }
    source._status = "文件已就绪，等待手机合并";
    source._kind = "good";
    renderPlaylistSources();
    toast("本地文件已添加");
  };
  request.onerror = function () {
    source._status = "无法把文件发送到电视";
    source._kind = "bad";
    renderPlaylistSources();
  };
  request.send(file);
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
    if (!location) throw new Error("“" + (name || "频道来源") + "”缺少地址或文件");
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

function githubProxySourceUrl(value) {
  var url = String(value || "").replace(/^\s+|\s+$/g, ""),
    prefix = "https://gh-proxy.com/";
  if (url.indexOf(prefix) === 0) return url;
  var anchor = document.createElement("a");
  anchor.href = url;
  var host = String(anchor.hostname || "").toLowerCase();
  return host === "github.com" || host === "raw.githubusercontent.com" ||
    /\.githubusercontent\.com$/.test(host) ? prefix + url : value;
}

function requestPlaylistText(source, done) {
  var effective = githubProxySourceUrl(source.location),
    direct = !isLocalPlaylistLocation(effective) && /^https?:\/\//i.test(effective);
  function load(url, fallback) {
    var request = new XMLHttpRequest();
    request.open("GET", url, true);
    request.onreadystatechange = function () {
      if (request.readyState !== 4) return;
      if (request.status >= 200 && request.status < 300) {
        done(null, request.responseText || "");
        return;
      }
      if (fallback) {
        load("/api/playlist/source?location=" + encodeURIComponent(source.location), false);
        return;
      }
      done(new Error("读取失败：HTTP " + request.status));
    };
    request.onerror = function () {
      if (fallback)
        load("/api/playlist/source?location=" + encodeURIComponent(source.location), false);
      else done(new Error("无法读取频道源"));
    };
    request.send();
  }
  load(
    direct ? effective : "/api/playlist/source?location=" + encodeURIComponent(source.location),
    direct
  );
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
    epg = "";
  if (lines.length && /^#EXTM3U/i.test(lines[0]))
    epg = playlistAttribute(lines[0], "x-tvg-url") || playlistAttribute(lines[0], "url-tvg");
  for (var i = 0; i < lines.length; i++) {
    var line = lines[i].replace(/^\s+|\s+$/g, "");
    if (!line) continue;
    if (/^#EXTINF:/i.test(line)) {
      var comma = line.lastIndexOf(",");
      pending = {
        name:
          comma >= 0
            ? line.substring(comma + 1).replace(/^\s+|\s+$/g, "")
            : playlistAttribute(line, "tvg-name"),
        group: playlistAttribute(line, "group-title") || group,
        epgId: playlistAttribute(line, "tvg-id")
      };
      continue;
    }
    if (line.charAt(0) === "#") continue;
    var url = playlistStreamUrl(line, source.location);
    if (pending && url) {
      entries.push({
        name: pending.name || "未命名频道",
        group: pending.group || group,
        epgId: pending.epgId || "",
        url: url
      });
      pending = null;
      continue;
    }
    var split = line.indexOf(",");
    if (split <= 0) continue;
    var name = line.substring(0, split).replace(/^\s+|\s+$/g, ""),
      value = line.substring(split + 1).replace(/^\s+|\s+$/g, "");
    if (/^#genre#$/i.test(value)) {
      group = name || "在线频道";
      continue;
    }
    url = playlistStreamUrl(value, source.location);
    if (url)
      entries.push({
        name: name || "未命名频道",
        group: group,
        epgId: "",
        url: url
      });
  }
  if (!entries.length) throw new Error("没有找到可播放频道");
  return { entries: entries, epg: epg };
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
          urls: []
        };
        merged.push(target);
        byName[nameKey] = target;
      }
      var duplicate = false;
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
          '" group-title="' +
          escapePlaylistAttribute(item.group) +
          '",' +
          item.name.replace(/[\r\n]/g, " ")
      );
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
  if (mergeBusy) return;
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
    index = 0;
  function finish(error) {
    mergeBusy = false;
    document.getElementById("mergeButton").disabled = false;
    if (error) {
      setMergeUi("刷新未完成", error.message || String(error), "");
      toast(error.message || String(error), true);
      renderPlaylistSources();
      return;
    }
    var merged = mergePhoneEntries(results);
    if (!merged.channels.length) {
      finish(new Error("所有来源都读取失败，没有可刷新的频道"));
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
          toast(detail);
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
        results.push(parsed);
        setSourceState(source, "已解析 " + parsed.entries.length + " 个频道", "good");
      } catch (parseError) {
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
  if (mergeBusy) return;
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
    if (mergeBusy) {
      event.preventDefault();
      event.returnValue = "正在整理频道，离开会中断刷新";
      return event.returnValue;
    }
  },
  false
);
startPage();
