(function () {
  "use strict";
  function extractUrls(text) {
    var matches = String(text || "").match(/(?:https?|tvbus|sop):\/\/[^\s<>"'`，。；！？【】]+/gi) || [], result = [];
    matches.forEach(function (url) {
      url = url.replace(/[.,;!?）]+$/, "");
      while (/\)$/.test(url) && (url.match(/\)/g) || []).length > (url.match(/\(/g) || []).length) url = url.slice(0, -1);
      if (result.indexOf(url) < 0) result.push(url);
    });
    return result;
  }
  window.quickAddExtractUrls = extractUrls;
  var fab = document.createElement("button");
  fab.className = "quick-add-fab"; fab.setAttribute("aria-label", "智能添加");
  fab.innerHTML = '<svg width="24" height="24" viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M12 5v14M5 12h14" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>';
  var panel = document.createElement("div");
  panel.className = "quick-add-backdrop"; panel.hidden = true;
  panel.innerHTML = '<div class="quick-add-sheet" role="dialog" aria-modal="true" aria-labelledby="quickAddTitle" aria-describedby="quickAddHint">' +
    '<div class="quick-add-header"><h2 id="quickAddTitle">智能添加</h2><button id="quickAddClose" aria-label="关闭"><svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M6 6l12 12M18 6L6 18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg></button></div>' +
    '<p id="quickAddHint" class="quick-add-hint">添加频道、播放媒体，或推送本地文件</p>' +
    '<label class="quick-add-label" for="quickAddText">网址或分享文字</label>' +
    '<textarea id="quickAddText" rows="4" placeholder="粘贴网址，也可以直接粘贴整段分享文字" aria-label="网址或分享文字"></textarea>' +
    '<div class="quick-add-actions"><button id="quickAddDetect" class="primary">识别网址</button><button id="quickAddFile">选择文件</button></div>' +
    '<p class="quick-add-formats">支持频道列表、书签、视频、音乐、APK、JS 和 TVBus JAR</p>' +
    '<input id="quickAddPicker" type="file" hidden><div id="quickAddResults"></div>' +
    '<p id="quickAddStatus" role="status" aria-live="polite"></p></div>';
  document.body.appendChild(fab); document.body.appendChild(panel);
  function el(id) { return document.getElementById(id); }
  var busy = false, previousFocus, previousOverflow;
  function status(message) { el("quickAddStatus").textContent = message; }
  function lock(value) {
    busy = value;
    var buttons = panel.querySelectorAll("button, select, textarea, input");
    for (var i = 0; i < buttons.length; i++) buttons[i].disabled = value;
  }
  function done(error, data) {
    lock(false); status(error ? error.message : (data && data.message || "操作完成"));
    if (!error) refresh();
  }
  fab.onclick = function () {
    previousFocus = document.activeElement; previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden"; panel.hidden = false;
    // Do not summon the phone keyboard until the user chooses to enter text.
    el("quickAddClose").focus();
  };
  function close() {
    if (busy) return;
    panel.hidden = true; document.body.style.overflow = previousOverflow;
    if (previousFocus) previousFocus.focus();
  }
  el("quickAddClose").onclick = close;
  panel.onclick = function (event) { if (event.target === panel) close(); };
  panel.onkeydown = function (event) {
    if (event.key === "Escape") close();
    if (event.key !== "Tab") return;
    var nodes = panel.querySelectorAll('button:not(:disabled),textarea:not(:disabled),select:not(:disabled)');
    var first = nodes[0], last = nodes[nodes.length - 1];
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
  };
  function upload(path, file, callback) {
    lock(true); status("正在上传…");
    var request = new XMLHttpRequest();
    request.open("POST", path + (path.indexOf("?") < 0 ? "?" : "&") + "name=" + encodeURIComponent(file.name), true);
    request.timeout = 300000;
    request.upload.onprogress = function (event) { if (event.lengthComputable) status("上传 " + Math.round(event.loaded * 100 / event.total) + "%"); };
    request.onload = function () {
      var data;
      try { data = JSON.parse(request.responseText); } catch (e) { done(new Error("设备返回无效响应")); return; }
      if (request.status < 200 || request.status >= 300 || data.ok === false) { done(new Error(data.message || "上传失败")); return; }
      callback(data);
    };
    request.onerror = function () { done(new Error("上传失败，请检查设备连接")); };
    request.ontimeout = function () { done(new Error("上传超时，请检查设备状态后重试")); };
    request.send(file);
  }
  function addSource(location, name) {
    lock(true); status("正在添加并刷新频道…");
    api("/api/state?view=channels", null, function (error, data) {
      if (error) { done(error); return; }
      var sources = (data.settings.playlistSources || []).slice();
      for (var i = 0; i < sources.length; i++) if (sources[i].location === location) {
        sources[i].enabled = true;
        api("/api/settings", { playlistSources: sources }, done, 180000); return;
      }
      sources.push({ id: "quick-" + Date.now(), name: name || "智能添加", location: location, enabled: true });
      api("/api/settings", { playlistSources: sources }, done, 180000);
    });
  }
  function result(label, kind, action) {
    var row = document.createElement("div"), title = document.createElement("p"), select = document.createElement("select"), button = document.createElement("button");
    row.className = "quick-add-result"; title.textContent = label; row.appendChild(title);
    var options = { playlist: "添加并刷新频道", play: "直接播放媒体", web: "作为网页播放", apk: "推送并安装 APK", js: "保存到 Ku9 js 目录", tvbus: "安装 TVBus JAR 插件（含可执行代码）" };
    var keys = action.file ? [kind] : /^(tvbus|sop):\/\//i.test(label) ? ["play"] : ["playlist", "play", "web"];
    keys.forEach(function (key) { var option = document.createElement("option"); option.value = key; option.textContent = options[key]; select.appendChild(option); });
    select.value = kind; select.setAttribute("aria-label", "操作方式"); row.appendChild(select);
    button.textContent = "确认执行";
    button.onclick = function () {
      if (busy) return;
      NtvDialogs.confirm(options[select.value] + "？\n" + label,function(accepted){if(accepted&&!busy)action(select.value);});
    };
    row.appendChild(button); el("quickAddResults").appendChild(row);
    return select;
  }
  el("quickAddDetect").onclick = function () {
    var urls = extractUrls(el("quickAddText").value);
    el("quickAddResults").textContent = "";
    status(urls.length ? "已提取 " + urls.length + " 个网址，可逐项确认操作。HTML、M3U8 可手动更改识别方式。" : "未找到 HTTP、TVBus 或 SOP 网址");
    urls.forEach(function (url) {
      var path = url.split(/[?#]/)[0], kind = /^(tvbus|sop):\/\//i.test(url) ? "play" : /\.(txt|m3u|json)$/i.test(path) ? "playlist" : /\.(mp4|mkv|webm|mp3|m4a|aac|flac|wav|ogg|m3u8|ts|flv)$/i.test(path) ? "play" : "web";
      var select = result(url, kind, function (choice) {
        if (choice === "playlist") addSource(url);
        else { lock(true); status("正在打开播放…"); api("/api/control", { action: "quickPlay", url: url, web: choice === "web" }, done); }
      });
      // Read text candidates through the device to avoid browser CORS restrictions.
      if (/\.(html?|m3u8|txt|m3u)$/i.test(path)) {
        var probe = new XMLHttpRequest(); probe.open("GET", "/api/playlist/source?location=" + encodeURIComponent(url), true); probe.timeout = 20000;
        probe.onload = function () {
          if (select._chosen || busy) return;
          if (probe.status === 200 && /^#EXTM3U/m.test(probe.responseText) && /#EXTINF:[^\n]*,[^\r\n]+/.test(probe.responseText) && !/#EXT-X-(TARGETDURATION|STREAM-INF|MEDIA-SEQUENCE)/.test(probe.responseText)) select.value = "playlist";
          if (probe.status === 200 && /netscape-bookmark-file|<dl[\s>][\s\S]*<a\s/i.test(probe.responseText)) {
            select.value = "playlist";
            // Import HTML via the existing bookmark converter instead of a plain playlist parser.
            select._bookmark = probe.responseText;
          }
        };
        probe.send();
      }
      select.onchange = function () { select._chosen = true; };
      var original = select.parentNode.querySelector("button").onclick;
      select.parentNode.querySelector("button").onclick = function () {
        if (select.value !== "playlist" || !select._bookmark) { original(); return; }
        if (busy) return;
        NtvDialogs.confirm("导入书签并刷新频道？\n" + url,function(accepted){if(!accepted||busy)return;
        upload("/api/playlist/upload", new File([select._bookmark], "浏览器书签.html", { type: "text/html" }), function (data) { addSource(data.location, data.name); });
        });
      };
    });
  };
  el("quickAddFile").onclick = function () { el("quickAddPicker").click(); };
  el("quickAddPicker").onchange = function () {
    var file = this.files && this.files[0]; this.value = ""; if (!file) return;
    var kind = /\.jar$/i.test(file.name) ? "tvbus" : /\.apk$/i.test(file.name) ? "apk" : /\.js$/i.test(file.name) ? "js" : /\.(m3u8?|txt|html?|json)$/i.test(file.name) ? "playlist" : /\.(mp4|mkv|webm|avi|mov|ts|m4v|mp3|m4a|aac|flac|wav|ogg|opus)$/i.test(file.name) ? "play" : "";
    if (!kind) { status("暂不支持此文件类型"); return; }
    var limit = kind === "js" ? 2 * 1024 * 1024 : kind === "tvbus" ? 32 * 1024 * 1024 : Number(state && state.apkTransferMaxBytes) || 8 * 1024 * 1024;
    if (!file.size || file.size > limit) { status("文件为空或超过设备上传上限（" + Math.floor(limit / 1024 / 1024) + " MB）"); return; }
    el("quickAddResults").textContent = "";
    status(kind === "play" ? "临时媒体在切换频道后删除。" : "请核对操作后确认。");
    var action = function () {
      var paths = { apk: "/api/apk/upload", js: "/api/ku9/script/upload", playlist: "/api/playlist/upload", play: "/api/quick/media", tvbus: "/api/tvbus/plugin" };
      upload(paths[kind], file, function (data) { if (kind === "playlist") addSource(data.location, data.name); else done(null, data); });
    };
    action.file = true; result(file.name, kind, action);
  };
}());
