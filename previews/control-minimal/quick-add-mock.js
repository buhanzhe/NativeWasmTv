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
      action(select.value);
    };
    row.appendChild(button); el("quickAddResults").appendChild(row);
    return select;
  }
  el("quickAddDetect").onclick = function () {
    var urls = extractUrls(el("quickAddText").value);
    el("quickAddResults").textContent = "";
    status(urls.length ? "已提取 " + urls.length + " 个网址（模拟预览）" : "未找到 HTTP、TVBus 或 SOP 网址");
    urls.forEach(function (url) {
      var path=url.split(/[?#]/)[0];
      var kind=/\.(txt|m3u|json)$/i.test(path)?"playlist": /^(tvbus|sop):/i.test(url)||/\.(mp4|mkv|mp3|m3u8|ts|flv)$/i.test(path)?"play":"web";
      result(url,kind,function(choice){status("模拟操作完成，未发送到电视");});
    });
  };
  el("quickAddFile").onclick = function () { el("quickAddPicker").click(); };
  el("quickAddPicker").onchange = function () {
    var file=this.files && this.files[0];if(!file)return;
    el("quickAddResults").textContent="";
    var kind=/\.apk$/i.test(file.name)?"apk":/\.js$/i.test(file.name)?"js":/\.jar$/i.test(file.name)?"tvbus":/\.(txt|m3u|json)$/i.test(file.name)?"playlist":"play";
    var action=function(){status("模拟操作完成，文件未上传到电视");};action.file=true;
    result(file.name,kind,action);status("已选择本地文件（模拟预览，不会上传）");this.value="";
  };
}());
