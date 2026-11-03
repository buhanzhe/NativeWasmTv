var userScripts = [],
  editingScriptId = "",
  editingInstallUrl = "",
  editingVersion = "",
  editingWarnings = [],
  editorDirty = false,
  scriptSaveBusy = false,
  scriptImportBusy = false,
  scriptMasterBusy = false,
  scriptListRendered = false;

function copyUserScript(item) {
  return {
    id: String(item.id || ""),
    name: String(item.name || ""),
    enabled: item.enabled !== false,
    source: String(item.source || ""),
    installUrl: String(item.installUrl || ""),
    version: String(item.version || "")
  };
}

function scriptNameFromSource(source, fallback) {
  var lines = String(source || "").replace(/\r/g, "").split("\n"), metadata = false;
  for (var i = 0; i < lines.length; i++) {
    var line = lines[i].replace(/^\s+|\s+$/g, "");
    if (line === "// ==UserScript==") { metadata = true; continue; }
    if (line === "// ==/UserScript==") break;
    if (!metadata) continue;
    var match = line.match(/^\/\/\s*@name\s+(.+)$/);
    if (match && match[1].replace(/^\s+|\s+$/g, "")) {
      return match[1].replace(/^\s+|\s+$/g, "").slice(0, 80);
    }
  }
  return fallback;
}

function newScriptId() {
  var id;
  do {
    id = "script-" + Date.now().toString(36) + "-" + Math.floor(Math.random() * 1679616).toString(36);
  } while (findUserScript(id));
  return id;
}

function findUserScript(id) {
  for (var i = 0; i < userScripts.length; i++) if (userScripts[i].id === id) return userScripts[i];
  return null;
}

function scriptMatchSummary(source) {
  var rules = [], lines = String(source || "").replace(/\r/g, "").split("\n");
  for (var i = 0; i < lines.length; i++) {
    var match = lines[i].match(/^\s*\/\/\s*@(match|include)\s+(.+?)\s*$/);
    if (match) rules.push(match[2]);
  }
  if (!rules.length) return "所有网页（未设置 @match）";
  return rules.slice(0, 2).join("、") + (rules.length > 2 ? " 等 " + rules.length + " 条规则" : "");
}

function scriptItemSummary(item) {
  var parts = [], host = String(item.installUrl || "").match(/^https:\/\/([^/]+)/i);
  if (host) parts.push(host[1]);
  if (item.version) parts.push("v" + item.version);
  parts.push(scriptMatchSummary(item.source));
  return parts.join(" · ");
}

function scriptsFromSettings(settings) {
  var raw = settings.webViewUserScripts, result = [];
  if (Array.isArray(raw)) {
    for (var i = 0; i < raw.length; i++) {
      if (!raw[i] || typeof raw[i] !== "object") continue;
      var item = copyUserScript(raw[i]);
      if (!item.id) item.id = "script-" + (i + 1);
      if (!item.name) item.name = scriptNameFromSource(item.source, "脚本 " + (i + 1));
      result.push(item);
    }
  } else if (settings.webViewUserScript) {
    result.push({
      id: "script-1",
      name: scriptNameFromSource(settings.webViewUserScript, "脚本 1"),
      enabled: true,
      source: String(settings.webViewUserScript)
    });
  }
  return result;
}

function renderScriptList() {
  scriptListRendered = true;
  var list = document.getElementById("userScriptList"),
    summary = document.getElementById("scriptCountSummary"), enabled = 0;
  list.innerHTML = "";
  for (var i = 0; i < userScripts.length; i++) if (userScripts[i].enabled) enabled++;
  summary.textContent = userScripts.length
    ? userScripts.length + " 个脚本 · " + enabled + " 个启用" : "暂无脚本";
  document.getElementById("addScriptButton").disabled = scriptSaveBusy || scriptImportBusy || userScripts.length >= 32;
  document.getElementById("importLocalScriptButton").disabled = scriptSaveBusy || scriptImportBusy || userScripts.length >= 32;
  document.getElementById("importScriptButton").disabled = scriptSaveBusy || scriptImportBusy;
  if (!userScripts.length) {
    var empty = document.createElement("div");
    empty.className = "hint script-empty";
    empty.textContent = "暂无脚本，点击“新建”添加";
    list.appendChild(empty);
    return;
  }
  for (var index = 0; index < userScripts.length; index++) {
    (function (item) {
      var row = document.createElement("div"), head = document.createElement("div"),
        copy = document.createElement("button"), name = document.createElement("b"),
        detail = document.createElement("span"), toggle = document.createElement("input"),
        edit = document.createElement("button");
      row.className = "source-item script-item" + (item.enabled ? "" : " disabled");
      head.className = "source-head";
      copy.className = "script-item-copy";
      copy.onclick = function () { editUserScript(item.id); };
      name.textContent = item.name;
      detail.textContent = scriptItemSummary(item);
      copy.appendChild(name);
      copy.appendChild(detail);
      toggle.className = "source-toggle";
      toggle.type = "checkbox";
      toggle.checked = item.enabled;
      toggle.disabled = scriptSaveBusy || scriptImportBusy;
      toggle.setAttribute("aria-label", "启用" + item.name);
      toggle.onchange = function () { setUserScriptEnabled(item.id, toggle.checked); };
      edit.className = "source-edit";
      edit.textContent = "编辑";
      edit.disabled = scriptSaveBusy || scriptImportBusy;
      edit.onclick = function () { editUserScript(item.id); };
      head.appendChild(copy);
      head.appendChild(toggle);
      head.appendChild(edit);
      row.appendChild(head);
      list.appendChild(row);
    })(userScripts[index]);
  }
}

function saveScriptMasterSwitch() {
  if (scriptMasterBusy) return;
  scriptMasterBusy = true;
  var toggle = document.getElementById("webViewUserScriptEnabled"), value = toggle.checked;
  toggle.disabled = true;
  api("/api/settings", { webViewUserScriptEnabled: value }, function (error) {
    scriptMasterBusy = false;
    toggle.disabled = false;
    if (error) {
      toggle.checked = !value;
      toast(error.message, true);
      return;
    }
    if (state && state.settings) state.settings.webViewUserScriptEnabled = value;
    toast(value ? "脚本已启用" : "脚本已停用");
  });
}

function persistUserScripts(successText, done) {
  scriptSaveBusy = true;
  renderScriptList();
  var payload = [], i;
  for (i = 0; i < userScripts.length; i++) payload.push(copyUserScript(userScripts[i]));
  api("/api/settings", { webViewUserScripts: payload }, function (error) {
    scriptSaveBusy = false;
    if (!error && state && state.settings) state.settings.webViewUserScripts = payload;
    renderScriptList();
    if (error) toast(error.message, true);
    else if (successText) toast(successText);
    if (done) done(error);
  });
}

function setUserScriptEnabled(id, enabled) {
  var item = findUserScript(id), previous;
  if (!item || scriptSaveBusy || scriptImportBusy) return;
  previous = item.enabled;
  item.enabled = enabled;
  persistUserScripts(enabled ? "脚本已启用" : "脚本已停用", function (error) {
    if (error) { item.enabled = previous; renderScriptList(); }
  });
}

function showScriptEditor(item, isNew, warnings) {
  if (window.NtvNavigation) NtvNavigation.overlayOpen("script-editor", closeScriptEditor);
  editingScriptId = item.id;
  editingInstallUrl = item.installUrl || "";
  editingVersion = item.version || "";
  editingWarnings = warnings || [];
  editorDirty = false;
  document.getElementById("scriptListSection").hidden = true;
  document.getElementById("scriptImportSection").hidden = true;
  document.getElementById("scriptEditor").hidden = false;
  document.getElementById("scriptEditorTitle").textContent = isNew ? "新建脚本" : "编辑脚本";
  document.getElementById("webViewUserScriptName").value = item.name || "";
  document.getElementById("webViewUserScriptItemEnabled").checked = item.enabled !== false;
  document.getElementById("webViewUserScriptSource").value = item.source || "";
  document.getElementById("deleteScriptButton").hidden = isNew;
  document.getElementById("saveScriptButton").disabled = false;
  var info = document.getElementById("scriptImportInfo"),
    warning = document.getElementById("scriptCompatibilityWarning");
  info.hidden = !editingInstallUrl;
  info.textContent = editingInstallUrl
    ? "来源：" + editingInstallUrl + (editingVersion ? " · 版本 " + editingVersion : "") : "";
  warning.hidden = !editingWarnings.length;
  warning.textContent = editingWarnings.length ? "兼容性提示：" + editingWarnings.join("；") : "";
  document.getElementById("webViewUserScriptName").focus();
}

function importUserScriptFromUrl() {
  if (scriptSaveBusy || scriptImportBusy || editingScriptId) return;
  var input = document.getElementById("scriptInstallUrl"),
    button = document.getElementById("importScriptButton"),
    url = input.value.replace(/^\s+|\s+$/g, "");
  if (!/^https:\/\//i.test(url)) { toast("请输入公开网站的 HTTPS 脚本地址", true); return; }
  scriptImportBusy = true;
  renderScriptList();
  button.disabled = true;
  button.textContent = "正在导入";
  api("/api/user-script/import", { url: url }, function (error, data) {
    scriptImportBusy = false;
    renderScriptList();
    button.disabled = false;
    button.textContent = "导入";
    if (error) { toast(error.message, true); return; }
    input.value = "";
    reviewImportedScript(data, url, "");
  }, 30000);
}

function reviewImportedScript(data, url, fileName) {
  var imported = data && data.script;
  if (!imported || !imported.source) { toast("导入内容无效", true); return; }
  var installUrl = fileName ? "" : imported.installUrl || url, existing = null;
  for (var i = 0; installUrl && i < userScripts.length; i++) {
    if (userScripts[i].installUrl === installUrl) { existing = userScripts[i]; break; }
  }
  if (!existing && userScripts.length >= 32) { toast("最多可配置 32 个脚本", true); return; }
  showScriptEditor({
    id: existing ? existing.id : newScriptId(), name: imported.name || "",
    enabled: existing ? existing.enabled : true, source: imported.source,
    installUrl: installUrl, version: imported.version || ""
  }, !existing, Array.isArray(data.warnings) ? data.warnings : []);
  document.getElementById("scriptEditorTitle").textContent = existing ? "更新脚本" : "安装脚本";
  editorDirty = true;
  if (fileName) {
    var info = document.getElementById("scriptImportInfo");
    info.hidden = false;
    info.textContent = "本地文件：" + fileName + (editingVersion ? " · 版本 " + editingVersion : "");
  }
}

function chooseLocalUserScript() {
  if (scriptSaveBusy || scriptImportBusy || editingScriptId) return;
  if (userScripts.length >= 32) { toast("最多可配置 32 个脚本", true); return; }
  if (typeof FileReader === "undefined") { toast("当前浏览器不支持读取文件，请使用手机或电脑浏览器", true); return; }
  var input = document.getElementById("scriptLocalFile");
  input.value = "";
  input.click();
}

function importUserScriptFromFile(input) {
  var file = input.files && input.files[0];
  input.value = ""; // Allow selecting the same file again after cancel/failure.
  if (!file || scriptSaveBusy || scriptImportBusy || editingScriptId) return;
  if (userScripts.length >= 32) { toast("最多可配置 32 个脚本", true); return; }
  if (!/\.js$/i.test(file.name || "")) { toast("请选择 .user.js 或 .js 脚本文件", true); return; }
  if (file.size > 262144) { toast("脚本文件超过 256KB", true); return; }
  scriptImportBusy = true;
  renderScriptList();
  var reader, readFinished = false, timer;
  function finish(error, data) {
    scriptImportBusy = false;
    renderScriptList();
    if (error) toast(error.message, true);
    else reviewImportedScript(data, "", file.name);
  }
  function readDone(error) {
    if (readFinished) return;
    readFinished = true;
    clearTimeout(timer);
    if (error) { finish(error); return; }
    var source = String(reader.result || "");
    if (!source || source.length > 262144) {
      finish(new Error(source ? "脚本文件超过 256KB" : "脚本文件为空")); return;
    }
    api("/api/user-script/import", { source: source, fileName: file.name }, finish);
  }
  try {
    reader = new FileReader();
    reader.onload = function () { readDone(null); };
    reader.onerror = function () { readDone(new Error("文件读取失败，请重新选择")); };
    reader.onabort = function () { readDone(new Error("文件读取已取消")); };
    timer = setTimeout(function () {
      readDone(new Error("文件读取超时，请重新选择"));
      try { reader.abort(); } catch (ignored) {}
    }, 15000);
    reader.readAsText(file, "UTF-8");
  } catch (error) { readDone(new Error("无法读取文件，请重新选择")); }
}

function createUserScript() {
  if (scriptSaveBusy || scriptImportBusy) return;
  if (userScripts.length >= 32) { toast("最多可配置 32 个脚本", true); return; }
  showScriptEditor({ id: newScriptId(), name: "", enabled: true, source: "" }, true, []);
}

function editUserScript(id) {
  if (scriptSaveBusy || scriptImportBusy) return;
  var item = findUserScript(id);
  if (item) showScriptEditor(copyUserScript(item), false);
}

function scriptEditorChanged() { editorDirty = true; }

function closeScriptEditor(force) {
  if (scriptSaveBusy) return false;
  if (!force && editorDirty) {setTimeout(function(){NtvDialogs.confirm("放弃未保存的修改？",function(accepted){if(accepted)closeScriptEditor(true);});},0);return false;}
  editingScriptId = "";
  editingInstallUrl = "";
  editingVersion = "";
  editingWarnings = [];
  editorDirty = false;
  document.getElementById("scriptEditor").hidden = true;
  document.getElementById("scriptListSection").hidden = false;
  document.getElementById("scriptImportSection").hidden = false;
  renderScriptList();
  if (window.NtvNavigation) NtvNavigation.overlayClosed("script-editor");
  return true;
}

function scriptPageBack() {
  if (!document.getElementById("scriptEditor").hidden) closeScriptEditor();
  else goBack();
}

function saveEditingScript() {
  if (scriptSaveBusy) return;
  var source = document.getElementById("webViewUserScriptSource").value,
    name = document.getElementById("webViewUserScriptName").value.replace(/^\s+|\s+$/g, ""),
    enabled = document.getElementById("webViewUserScriptItemEnabled").checked,
    existing = findUserScript(editingScriptId), previous = [], total = source.length, i;
  if (!source.replace(/^\s+|\s+$/g, "")) { toast("请输入脚本内容", true); return; }
  if (source.length > 262144) { toast("单个脚本不能超过 256KB", true); return; }
  name = name || scriptNameFromSource(source, "脚本 " + (existing ? userScripts.indexOf(existing) + 1 : userScripts.length + 1));
  if (name.length > 80) { toast("脚本名称不能超过 80 个字符", true); return; }
  for (i = 0; i < userScripts.length; i++) {
    previous.push(copyUserScript(userScripts[i]));
    if (!existing || userScripts[i].id !== existing.id) total += userScripts[i].source.length;
  }
  if (total > 524288) { toast("全部脚本总计不能超过 512KB", true); return; }
  var next = { id: editingScriptId, name: name, enabled: enabled, source: source,
    installUrl: editingInstallUrl, version: editingVersion };
  if (existing) {
    for (i = 0; i < userScripts.length; i++) if (userScripts[i].id === existing.id) userScripts[i] = next;
  } else userScripts.push(next);
  document.getElementById("saveScriptButton").disabled = true;
  persistUserScripts("脚本已保存", function (error) {
    document.getElementById("saveScriptButton").disabled = false;
    if (error) { userScripts = previous; renderScriptList(); return; }
    closeScriptEditor(true);
  });
}

function deleteEditingScript() {
  var item = findUserScript(editingScriptId), previous = [], next = [];
  if (!item || scriptSaveBusy) return;
  NtvDialogs.confirm("删除脚本“" + item.name + "”？",function(accepted){
  if(!accepted)return;
  for (var i = 0; i < userScripts.length; i++) {
    previous.push(copyUserScript(userScripts[i]));
    if (userScripts[i].id !== item.id) next.push(userScripts[i]);
  }
  userScripts = next;
  persistUserScripts("脚本已删除", function (error) {
    if (error) { userScripts = previous; renderScriptList(); return; }
    closeScriptEditor(true);
  });
  });
}

function renderPageState() {
  var settings = state.settings;
  if (!scriptMasterBusy) document.getElementById("webViewUserScriptEnabled").checked =
    settings.webViewUserScriptEnabled === true;
  if (!editorDirty && !scriptSaveBusy) {
    var next = scriptsFromSettings(settings), changed = next.length !== userScripts.length;
    for (var i = 0; !changed && i < next.length; i++) {
      var a = next[i], b = userScripts[i];
      changed = a.id !== b.id || a.name !== b.name || a.enabled !== b.enabled
        || a.source !== b.source || a.installUrl !== b.installUrl || a.version !== b.version;
    }
    if (changed) { userScripts = next; renderScriptList(); }
  }
  if (!scriptListRendered) renderScriptList();
}

startPage();
