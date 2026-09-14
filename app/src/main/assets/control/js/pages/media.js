var mediaSniffedKey = null, mediaSniffedBusy = false;

function mediaResourceDescription(url) {
  var path = String(url || "").split(/[?#]/)[0];
  var match = path.match(/\.([a-z0-9]+)$/i);
  var extension = match ? match[1].toUpperCase() : "";
  if (!extension && /[?&](?:format|type)=m3u8(?:[&#]|$)/i.test(url)) extension = "M3U8";
  var audio = /^(MP3|M4A|FLAC|WAV|OGG|OGA|OPUS)$/.test(extension);
  var type = extension ? (audio ? "音频 · " : "视频 · ") + extension : "媒体";
  // Drop signed query parameters only in the label; playback keeps the original URL.
  var shortUrl = path.length > 64 ? path.slice(0, 26) + "…" + path.slice(-37) : path;
  return { type: type, url: shortUrl };
}

function renderMediaSources(data) {
  data = data || {};
  var resources = data.webPage && Array.isArray(data.sniffedResources) ? data.sniffedResources : [];
  // Web-based channels can still have multiple configured playback lines.
  var web = !!data.webPage && resources.length > 0;
  var count = web ? resources.length : Math.max(0, Number(data.sourceCount) || 0);
  var visible = web ? count > 0 : count > 1;
  var key = JSON.stringify([web, resources, count, data.sourceIndex, data.sourceKey]);
  if (key === mediaSniffedKey) return;
  mediaSniffedKey = key;
  var list = document.getElementById("mediaSniffedList"), label = web ? "资源" : "线路";
  list.innerHTML = "";
  document.getElementById("mediaSniffedLabel").textContent = label;
  document.getElementById("mediaSniffedTitle").textContent = label + " · " + count;
  document.getElementById("mediaSniffedSubtitle").textContent = web ? "网页发现的视频与音乐" : "选择当前频道的播放线路";
  var entry = document.getElementById("mediaSniffedButton");
  entry.hidden = !visible;
  entry.setAttribute("aria-label", label + "，" + count + " 个");
  if (!visible) { mediaCloseSniffed(); return; }
  for (var i = 0; i < count; i++) {
    (function (index) {
      var button = document.createElement("button"), title = document.createElement("b"), detail = document.createElement("span");
      var selected = !web && index === data.sourceIndex;
      button.type = "button";
      button.className = "media-sniffed-item" + (selected ? " selected" : "");
      button.setAttribute("aria-current", selected ? "true" : "false");
      var description = web ? mediaResourceDescription(resources[index].url) : null;
      title.textContent = web ? "资源 " + (index + 1) + " · " + description.type : "线路 " + (index + 1) + (selected ? " · 当前播放" : "");
      detail.textContent = web ? description.url : "点击播放此线路";
      detail.title = web ? resources[index].url || "" : detail.textContent;
      if (web) {
        detail.style.whiteSpace = "normal";
        detail.style.wordBreak = "break-all";
      }
      button.appendChild(title); button.appendChild(detail);
      button.onclick = function () {
        if (web) playMediaSniffedResource(resources[index], button);
        else if (selected) mediaCloseSniffed();
        else mediaSelectSource("/api/media/control", { action: "source", index: index, sourceKey: data.sourceKey }, button);
      };
      list.appendChild(button);
    })(i);
  }
}

function playMediaSniffedResource(resource, button) {
  if (!resource || !resource.url) return;
  mediaSelectSource("/api/control", { action: "playSniffed", url: resource.url }, button);
}

function mediaSelectSource(path, request, button) {
  if (!mediaControllerOpen || mediaSniffedBusy) return;
  mediaSniffedBusy = true;
  button.disabled = true;
  var generation = mediaControllerGeneration;
  api(path, request, function (error) {
    mediaSniffedBusy = false;
    button.disabled = false;
    if (!mediaControllerOpen || generation !== mediaControllerGeneration) return;
    toast(error ? error.message : "正在播放所选" + (request.action === "source" ? "线路" : "资源"), !!error);
    if (!error) mediaCloseSniffed();
    mediaNeedsDetail = true;
    scheduleMediaControllerRefresh(0);
  });
}

var mediaControllerTimer = null,
  mediaClockTimer = null,
  mediaControllerOpen = false,
  mediaSeeking = false,
  mediaVolumeEditing = false,
  subtitleOffsetEditing = false,
  mediaState = null,
  mediaRenderKey = "",
  mediaNeedsDetail = true,
  mediaStateReceivedAt = 0;

function mediaSvg(path, extraClass) {
  return (
    '<svg class="media-icon' +
    (extraClass ? " " + extraClass : "") +
    '" viewBox="0 0 24 24" aria-hidden="true"><path d="' +
    path +
    '"></path></svg>'
  );
}

function openVideoRecorderPage() {
  var ip = location.hostname,
    port = location.port || "9966";
  location.href =
    "/video-recorder.html?ip=" +
    encodeURIComponent(ip) +
    "&port=" +
    encodeURIComponent(port) +
    "&v=" +
    Date.now();
}

var mediaPreviewKey = "", mediaShotBusy = false, mediaShotRequest = null, mediaShotUrl = "";

// Some TV Surface/PixelCopy implementations return a successful but flat green PNG.
// Inspect only decorative previews; never alter a user's downloaded screenshot.
function mediaPreviewHasInvalidGreen(image) {
  try {
    var canvas = document.createElement("canvas");
    canvas.width = 32;
    canvas.height = 18;
    var context = canvas.getContext("2d");
    if (!context) return false;
    context.drawImage(image, 0, 0, 32, 18);
    var pixels = context.getImageData(0, 0, 32, 18).data;
    var count = 0, low = 255, high = 0;
    for (var i = 0; i < pixels.length; i += 4) {
      var r = pixels[i], g = pixels[i + 1], b = pixels[i + 2];
      if (pixels[i + 3] > 240 && r < 40 && b < 40 && g > 80 && g > 3 * Math.max(r, b)) {
        count++;
        low = Math.min(low, g);
        high = Math.max(high, g);
      }
    }
    // Uniformity avoids treating grass, pitches or other normal green scenes as errors.
    return count >= 565 && high - low <= 6;
  } catch (ignored) { return false; }
}

function mediaUpdatePreview(ready) {
  var image = document.getElementById("mediaBackdropImage");
  var key = (mediaState.group || "") + "|" + (mediaState.name || "");
  if (key !== mediaPreviewKey) image.hidden = true;
  if (!ready || mediaState.lowResource || key === mediaPreviewKey) return;
  mediaPreviewKey = key;
  image.onload = function () { image.hidden = mediaPreviewHasInvalidGreen(image); };
  image.onerror = function () { image.hidden = true; };
  // A single still per channel, never a screenshot polling loop.
  image.src = "/api/recording/screenshot?t=" + Date.now();
}

function mediaCloseShot(event) {
  var backdrop = document.getElementById("mediaShotBackdrop");
  if (event && event.target !== backdrop) return;
  backdrop.className = "media-sheet-backdrop";
  backdrop.setAttribute("aria-hidden", "true");
}

function mediaCaptureScreenshot() {
  if (mediaShotBusy || !mediaControllerOpen) return;
  if (window.NtvDevice && typeof NtvDevice.saveVideoScreenshot === "function") {
    NtvDevice.saveVideoScreenshot();
    return;
  }
  mediaShotBusy = true;
  var button = document.getElementById("mediaScreenshot"), request = new XMLHttpRequest();
  var generation = mediaControllerGeneration;
  mediaShotRequest = request;
  button.disabled = true;
  function finish(error) {
    mediaShotBusy = false;
    mediaShotRequest = null;
    button.disabled = false;
    if (error && mediaControllerOpen) toast(error, true);
  }
  request.open("GET", "/api/recording/screenshot?t=" + Date.now(), true);
  request.responseType = "blob";
  request.timeout = 20000;
  request.onload = function () {
    if (!mediaControllerOpen || generation !== mediaControllerGeneration) { finish(); return; }
    var blob = request.response;
    if (request.status !== 200 || !blob || !blob.size || blob.type.indexOf("image/png") !== 0) {
      var reader = new FileReader();
      reader.onload = function () {
        var message = "无法截图，请确认设备正在播放视频";
        try { message = JSON.parse(reader.result).message || message; } catch (ignored) {}
        finish(message);
      };
      reader.onerror = function () { finish("无法读取截图"); };
      if (blob) reader.readAsText(blob); else finish("无法读取截图");
      return;
    }
    if (mediaShotUrl) URL.revokeObjectURL(mediaShotUrl);
    mediaShotUrl = URL.createObjectURL(blob);
    document.getElementById("mediaShotPreview").src = mediaShotUrl;
    var save = document.getElementById("mediaShotSave");
    save.href = mediaShotUrl;
    save.download = "nTv-screenshot-" + Date.now() + ".png";
    mediaCloseSettings(); mediaCloseSniffed();
    var backdrop = document.getElementById("mediaShotBackdrop");
    backdrop.className = "media-sheet-backdrop open";
    backdrop.setAttribute("aria-hidden", "false");
    save.click();
    finish();
  };
  request.onerror = function () { finish("截图连接失败，请重试"); };
  request.ontimeout = function () { finish("截图超时，请重试"); };
  request.onabort = function () { finish(); };
  request.send();
}
window.addEventListener("unload", function () { if (mediaShotUrl) URL.revokeObjectURL(mediaShotUrl); }, false);

function mediaOpenSettings() {
  mediaCloseShot();
  mediaCloseSniffed();
  var backdrop = document.getElementById("mediaSettingsBackdrop");
  backdrop.className = "media-sheet-backdrop open";
  backdrop.setAttribute("aria-hidden", "false");
}

function mediaOpenChannels() {
  mediaDismissSheet();
  if (window.NtvChannelPicker) {
    NtvChannelPicker.update(state);
    NtvChannelPicker.open();
  }
}

function mediaDismissSheet() {
  var picker = document.getElementById("channelPickerBackdrop");
  if (picker && picker.hidden === false && typeof closeChannelPicker === "function") {
    closeChannelPicker();
    return true;
  }

  var ids = ["mediaMultimediaBackdrop", "mediaShotBackdrop", "mediaSniffedBackdrop", "mediaSettingsBackdrop"];
  var close = [function () { if (typeof mediaCloseMultimedia === "function") mediaCloseMultimedia(); }, mediaCloseShot, mediaCloseSniffed, mediaCloseSettings];
  for (var i = 0; i < ids.length; i++) {
    var sheet = document.getElementById(ids[i]);
    if (sheet && sheet.getAttribute("aria-hidden") === "false") {
      close[i]();
      return true;
    }
  }
  return false;
}

function mediaOpenSniffed() {
  if (document.getElementById("mediaSniffedButton").hidden) return;
  mediaCloseShot();
  mediaCloseSettings();
  var backdrop = document.getElementById("mediaSniffedBackdrop");
  backdrop.className = "media-sheet-backdrop open";
  backdrop.setAttribute("aria-hidden", "false");
  document.getElementById("mediaSniffedButton").setAttribute("aria-expanded", "true");
  backdrop.querySelector("button").focus();
}

function mediaCloseSniffed(event) {
  var backdrop = document.getElementById("mediaSniffedBackdrop");
  if (event && event.target !== backdrop) return;
  backdrop.className = "media-sheet-backdrop";
  backdrop.setAttribute("aria-hidden", "true");
  document.getElementById("mediaSniffedButton").setAttribute("aria-expanded", "false");
}

function mediaCloseSettings(event) {
  var backdrop = document.getElementById("mediaSettingsBackdrop");
  if (event && event.target !== backdrop) return;
  backdrop.className = "media-sheet-backdrop";
  backdrop.setAttribute("aria-hidden", "true");
}

function formatMediaTime(milliseconds) {
  var seconds = Math.max(0, Math.floor(Number(milliseconds || 0) / 1000)),
    hours = Math.floor(seconds / 3600),
    minutes = Math.floor((seconds % 3600) / 60),
    remain = seconds % 60;
  return (
    (hours ? hours + ":" : "") +
    (hours && minutes < 10 ? "0" : "") +
    minutes +
    ":" +
    (remain < 10 ? "0" : "") +
    remain
  );
}

function mediaTrackKey(data) {
  var audio = data.audioTracks || [],
    video = data.videoTracks || [],
    subtitles = data.subtitleTracks || [],
    parts = [
      data.name || "",
      String(data.available),
      String(data.prepared),
      String(data.selectedAudioTrack),
      String(data.selectedSubtitleTrack),
      String(data.selectedVideoTrack)
    ];
  for (var i = 0; i < audio.length; i++) parts.push("a" + audio[i].index + audio[i].label);
  for (i = 0; i < video.length; i++) parts.push("v" + video[i].index + video[i].label);
  for (i = 0; i < subtitles.length; i++) parts.push("s" + subtitles[i].index + subtitles[i].label);
  return parts.join("|");
}

function fillMediaTrackSelect(id, tracks, selected, emptyText, allowOff) {
  var select = document.getElementById(id);
  if (!select) return;
  select.innerHTML = "";
  if (allowOff) {
    var off = document.createElement("option");
    off.value = "-1";
    off.textContent = "关闭字幕";
    select.appendChild(off);
  }
  for (var i = 0; i < tracks.length; i++) {
    var option = document.createElement("option");
    option.value = String(tracks[i].index);
    option.textContent = tracks[i].label || emptyText;
    select.appendChild(option);
  }
  if (!tracks.length && !allowOff) {
    var empty = document.createElement("option");
    empty.value = "-1";
    empty.textContent = emptyText;
    select.appendChild(empty);
  }
  select.value = String(selected);
  if (select.selectedIndex < 0) select.selectedIndex = 0;
  select.disabled = !tracks.length;
}

function buildMediaController(data) {
  // Rounded Material Icons are embedded as SVG paths for offline use.
  // Source and license: https://github.com/google/material-design-icons
  var body = document.getElementById("mediaControllerBody"),
    previous = mediaSvg(
      "M7 6c.55 0 1 .45 1 1v10c0 .55-.45 1-1 1s-1-.45-1-1V7c0-.55.45-1 1-1zm3.66 6.82 5.77 4.07c.66.47 1.58-.01 1.58-.82V7.93c0-.81-.91-1.28-1.58-.82l-5.77 4.07c-.57.4-.57 1.24 0 1.64z"
    ),
    play = mediaSvg(
      "M8 6.82v10.36c0 .79.87 1.27 1.54.84l8.14-5.18c.62-.39.62-1.29 0-1.69L9.54 5.98C8.87 5.55 8 6.03 8 6.82z",
      "media-play-icon"
    ),
    pause = mediaSvg(
      "M8 19c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2S6 5.9 6 7v10c0 1.1.9 2 2 2zm6-12v10c0 1.1.9 2 2 2s2-.9 2-2V7c0-1.1-.9-2-2-2s-2 .9-2 2z",
      "media-pause-icon"
    ),
    next = mediaSvg(
      "M7.58 16.89l5.77-4.07c.56-.4.56-1.24 0-1.63L7.58 7.11C6.91 6.65 6 7.12 6 7.93v8.14c0 .81.91 1.28 1.58.82zM16 7v10c0 .55.45 1 1 1s1-.45 1-1V7c0-.55-.45-1-1-1s-1 .45-1 1z"
    );
  body.innerHTML =
    '<section class="media-player-card">' +
    '<div id="mediaVolumeControl" class="media-volume-control"><span id="mediaVolumeValue">--</span><div class="media-volume-range"><div class="media-volume-track"><i id="mediaVolumeFill"></i><i id="mediaVolumeKnob"></i></div><input id="mediaVolume" type="range" min="0" max="15" value="0" step="1" aria-label="播放设备音量" aria-orientation="vertical" oninput="mediaVolumePreview(this)" onchange="mediaVolumeCommit(this)" onblur="mediaVolumeCancel()" ontouchcancel="mediaVolumeCancel()"></div><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M11 4 6 8H3v8h3l5 4V4Z M15 8a6 6 0 0 1 0 8 M18 5a10 10 0 0 1 0 14"/></svg></div>' +
    '<div class="media-scene"><span id="mediaLiveBadge" class="media-live-badge">正在播放</span><div class="media-scene-actions"><button id="mediaScreenshot" class="media-circle-action" type="button" aria-label="截图" onclick="mediaCaptureScreenshot()"><svg class="media-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h4l2-3h4l2 3h4a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2Z"/><circle cx="12" cy="13" r="4"/></svg></button><button id="mediaMultimediaButton" class="media-circle-action" type="button" hidden aria-label="投送图片、视频或音乐" title="多媒体投送" aria-haspopup="dialog" onclick="mediaOpenMultimedia()"><svg class="media-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M3 8V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-3M3 12a9 9 0 0 1 9 9M3 16a5 5 0 0 1 5 5M3 20v1h1M11 7l6 4-6 4Z"/></svg></button></div></div>' +
    '<div class="media-now"><span id="mediaGroup">当前频道</span><b id="mediaTitle">当前节目</b><small id="mediaStatus">正在读取播放状态…</small></div>' +
    '<div class="media-progress"><div id="mediaSeekBar" class="media-seekbar"><div class="media-seek-track"><i id="mediaSeekFill"></i><i id="mediaSeekKnob"></i></div><input id="mediaProgress" type="range" min="0" max="1" value="0" step="250" aria-label="播放进度" oninput="mediaProgressPreview(this)" onchange="mediaSeekCommit(this)"></div><div class="media-time"><span id="mediaPosition">--:--</span><span id="mediaDuration">直播</span></div></div>' +
    '<div class="media-transport"><button id="mediaPrevious" type="button" aria-label="上一个频道" onclick="mediaCommand(\'previous\')">' + previous + '<span>上一个</span></button>' +
    '<button id="mediaToggle" class="media-play-button" type="button" aria-label="播放" onclick="mediaCommand(\'toggle\')">' + play + pause + '</button>' +
    '<button id="mediaNext" type="button" aria-label="下一个频道" onclick="mediaCommand(\'next\')">' + next + '<span>下一个</span></button></div>' +
    '</section>';
  fillMediaTrackSelect("mediaVideo", data.videoTracks || [], data.selectedVideoTrack, "未检测到视轨", false);
  fillMediaTrackSelect("mediaAudio", data.audioTracks || [], data.selectedAudioTrack, "未检测到音轨", false);
  fillMediaTrackSelect("mediaSubtitle", data.subtitleTracks || [], data.selectedSubtitleTrack, "未检测到字幕", true);
}

function renderMediaController(data) {
  mediaState = data || {};
  renderMediaSources(mediaState);
  document.getElementById("mediaSubtitlesEnabled").checked = mediaState.subtitlesEnabled !== false;
  var key = mediaTrackKey(mediaState);
  if (key !== mediaRenderKey && !subtitleOffsetEditing && !mediaVolumeEditing) {
    mediaRenderKey = key;
    buildMediaController(mediaState);
  }
  mediaRenderVolume();
  var available = mediaState.available === true,
    prepared = mediaState.prepared === true,
    duration = Number(mediaState.durationMs) || 0,
    position = Number(mediaState.positionMs) || 0,
    style = mediaState.subtitleStyle || {},
    favorite = mediaState.favorite === true,
    favoriteButton = document.getElementById("mediaFavorite");
  document.getElementById("mediaTitle").textContent = mediaState.name || "当前没有节目";
  document.getElementById("mediaGroup").textContent = mediaState.group || "当前频道";
  document.getElementById("mediaStatus").textContent = !available
    ? "等待电视开始播放"
    : !prepared
      ? "正在准备媒体信息"
      : duration > 0
        ? "可以拖动进度"
        : mediaState.playing
          ? "正在直播"
          : "直播已暂停";
  favoriteButton.setAttribute("aria-pressed", favorite ? "true" : "false");
  favoriteButton.setAttribute("aria-label", favorite ? "取消收藏当前频道" : "收藏当前频道");
  favoriteButton.className = "media-favorite" + (favorite ? " selected" : "");
  favoriteButton.disabled = mediaState.favoriteAvailable === false;
  document.getElementById("mediaLiveBadge").textContent = !available ? "等待播放" : !prepared ? "加载中" : !mediaState.playing ? "已暂停" : duration > 0 ? "播放中" : "直播中";
  document.getElementById("mediaScreenshot").disabled = !available || !prepared || mediaShotBusy;
  if (typeof renderMultimediaEntry === "function") renderMultimediaEntry();
  mediaUpdatePreview(available && prepared);
  var progress = document.getElementById("mediaProgress");
  progress.max = String(Math.max(1, duration));
  progress.disabled = mediaState.seekable !== true;
  if (!mediaSeeking) progress.value = String(duration > 0 ? Math.min(duration, position) : 0);
  renderMediaProgress(progress);
  if (!mediaSeeking)
    document.getElementById("mediaPosition").textContent =
      duration > 0 ? formatMediaTime(position) : "实时";
  document.getElementById("mediaDuration").textContent =
    duration > 0 ? formatMediaTime(duration) : "直播";
  var toggle = document.getElementById("mediaToggle");
  toggle.className = "media-play-button" + (mediaState.playing ? " is-playing" : "");
  toggle.setAttribute("aria-label", mediaState.playing ? "暂停" : "播放");
  toggle.disabled = !available;
  document.getElementById("mediaPrevious").disabled = mediaState.previousAvailable === false;
  document.getElementById("mediaNext").disabled = mediaState.nextAvailable === false;
  var speed = document.getElementById("mediaSpeed");
  speed.value = String(Number(mediaState.speed) || 1);
  speed.disabled = !prepared;
  document.getElementById("subtitleSize").value = String(style.sizePercent || 100);
  if (!subtitleOffsetEditing) {
    document.getElementById("subtitlePosition").value = style.position || "bottom";
    var offset = normalizeSubtitleOffset(style.offsetPercent);
    document.getElementById("subtitleOffset").value = String(offset);
    document.getElementById("subtitleOffsetValue").textContent = offset + "%";
    document.getElementById("subtitleOffsetControls").style.display =
      style.position === "manual" ? "block" : "none";
  }
  document.getElementById("subtitleShadow").value = style.shadow || "standard";
}

var mediaControllerGeneration = 0,
  mediaRequestSequence = 0;

function scheduleMediaControllerRefresh(delay) {
  clearTimeout(mediaControllerTimer);
  if (!mediaControllerOpen) return;
  mediaControllerTimer = setTimeout(
    refreshMediaController,
    typeof delay === "number" ? delay : 900
  );
}

function scheduleMediaClock() {
  clearTimeout(mediaClockTimer);
  if (!mediaControllerOpen) return;
  mediaClockTimer = setTimeout(function () {
    if (mediaControllerOpen && mediaState && mediaState.playing && !mediaSeeking) {
      var duration = Number(mediaState.durationMs) || 0,
        elapsed = Math.max(0, Date.now() - mediaStateReceivedAt),
        position = (Number(mediaState.positionMs) || 0) +
          elapsed * (Number(mediaState.speed) || 1),
        progress = document.getElementById("mediaProgress");
      if (duration > 0 && progress) {
        position = Math.min(duration, position);
        progress.value = String(position);
        renderMediaProgress(progress);
        document.getElementById("mediaPosition").textContent = formatMediaTime(position);
      }
    }
    scheduleMediaClock();
  }, 500);
}

function mergeMediaState(update) {
  var merged = mediaState || {}, key;
  for (key in update) if (update.hasOwnProperty(key)) merged[key] = update[key];
  return merged;
}

function refreshMediaController() {
  if (!mediaControllerOpen) return;
  var generation = mediaControllerGeneration,
    sequence = ++mediaRequestSequence,
    detailed = mediaNeedsDetail || !mediaState;
  api("/api/media?detail=" + (detailed ? "1" : "0"), null, function (error, data) {
    if (!mediaControllerOpen || generation !== mediaControllerGeneration || sequence !== mediaRequestSequence) return;
    document.getElementById("mediaConnectionStatus").textContent = error ? "连接失败" : "电视在线";
    if (error) {
      var body = document.getElementById("mediaControllerBody");
      body.innerHTML = "";
      renderMediaSources({});
      var message = document.createElement("div");
      message.className = "media-empty";
      message.textContent = error.message || "无法读取播放状态";
      body.appendChild(message);
      mediaRenderKey = "";
      scheduleMediaControllerRefresh(1600);
      return;
    }
    if (!detailed && mediaState &&
        (data.revision !== mediaState.revision || data.prepared !== mediaState.prepared ||
          data.name !== mediaState.name || data.group !== mediaState.group)) {
      mediaNeedsDetail = true;
      scheduleMediaControllerRefresh(0);
      return;
    }
    mediaState = mergeMediaState(data);
    mediaNeedsDetail = false;
    mediaStateReceivedAt = Date.now();
    renderMediaController(mediaState);
    scheduleMediaControllerRefresh(mediaState.lowResource ? 3500 : 1800);
  });
}

function setMediaControllerActive(active) {
  active = !!active && !document.hidden;
  if (active === mediaControllerOpen) return;
  mediaControllerOpen = active;
  mediaControllerGeneration++;
  mediaSeeking = false;
  mediaVolumeEditing = false;
  subtitleOffsetEditing = false;
  clearTimeout(mediaControllerTimer);
  clearTimeout(mediaClockTimer);
  if (active) {
    mediaRenderKey = "";
    mediaNeedsDetail = true;
    document.getElementById("mediaConnectionStatus").textContent = "连接中";
    refreshMediaController();
    scheduleMediaClock();
  } else {
    mediaCloseSettings();
    mediaCloseSniffed();
    mediaCloseShot();
    if (mediaShotRequest) mediaShotRequest.abort();
  }
}

function mediaCommand(action, extra) {
  if (!mediaControllerOpen) return;
  var body = extra || {},
    generation = mediaControllerGeneration,
    sequence = ++mediaRequestSequence;
  body.action = action;
  clearTimeout(mediaControllerTimer);
  api("/api/media/control", body, function (error, data) {
    if (!mediaControllerOpen || generation !== mediaControllerGeneration || sequence !== mediaRequestSequence) return;
    if (error) {
      toast(error.message, true);
      scheduleMediaControllerRefresh(500);
      return;
    }
    mediaState = mergeMediaState(data);
    mediaNeedsDetail = false;
    mediaStateReceivedAt = Date.now();
    renderMediaController(mediaState);
    if (action === "favorite") toast(data.favorite ? "已收藏当前频道" : "已取消收藏");
    scheduleMediaControllerRefresh(action === "previous" || action === "next" ? 600 : 250);
  });
}

function mediaRenderVolume() {
  var input = document.getElementById("mediaVolume");
  if (!input || mediaVolumeEditing) return;
  input.max = String(Math.max(1, Number(mediaState.volumeMax) || 1));
  input.value = String(Math.max(0, Number(mediaState.volume) || 0));
  input.disabled = mediaState.volumeAvailable !== true;
  mediaVolumePaint(input);
}

function mediaVolumePaint(input) {
  var percent = Math.round(Math.max(0, Math.min(100,
    Number(input.value) / Math.max(1, Number(input.max)) * 100)));
  document.getElementById("mediaVolumeValue").textContent = input.disabled ? "--" : percent + "%";
  document.getElementById("mediaVolumeFill").style.height = percent + "%";
  document.getElementById("mediaVolumeKnob").style.bottom = percent + "%";
  document.getElementById("mediaVolumeControl").className = "media-volume-control" + (input.disabled ? " unavailable" : "");
  input.setAttribute("aria-valuetext", input.disabled ? "设备不支持音量调节" : percent === 0 ? "静音" : percent + "%");
}

function mediaVolumePreview(input) {
  mediaVolumeEditing = true;
  mediaVolumePaint(input);
}

function mediaVolumeCommit(input) {
  mediaVolumeEditing = false;
  if (!input.disabled) mediaCommand("volume", { volume: Math.round(Number(input.value) || 0) });
}

function mediaVolumeCancel() {
  if (!mediaVolumeEditing) return;
  mediaVolumeEditing = false;
  mediaRenderVolume();
}

function mediaToggleFavorite() {
  mediaCommand("favorite");
}

function renderMediaProgress(input) {
  var percent = Math.max(0, Math.min(100, (Number(input.value) || 0) / Math.max(1, Number(input.max) || 1) * 100));
  document.getElementById("mediaSeekBar").className = "media-seekbar" + (input.disabled ? " unavailable" : "");
  document.getElementById("mediaSeekFill").style.width = percent + "%";
  document.getElementById("mediaSeekKnob").style.left = percent + "%";
}

function mediaProgressPreview(input) {
  mediaSeeking = true;
  renderMediaProgress(input);
  document.getElementById("mediaPosition").textContent = formatMediaTime(Number(input.value));
}

function mediaSeekCommit(input) {
  var value = Number(input.value) || 0;
  mediaCommand("seek", { positionMs: Math.round(value) });
  setTimeout(function () { mediaSeeking = false; }, 350);
}

function mediaSpeedChanged(select) {
  mediaCommand("speed", { speed: Number(select.value) || 1 });
}

function mediaTrackChanged(select, audio) {
  mediaCommand(audio ? "audioTrack" : "subtitleTrack", { index: Number(select.value) });
}

function mediaVideoTrackChanged(select) {
  mediaCommand("videoTrack", { index: Number(select.value) });
}

function mediaSubtitlesEnabledChanged(input) {
  mediaCommand("subtitleEnabled", { enabled: !!input.checked });
}

function mediaSubtitleStyleChanged() {
  var position = document.getElementById("subtitlePosition").value;
  document.getElementById("subtitleOffsetControls").style.display =
    position === "manual" ? "block" : "none";
  mediaCommand("subtitleStyle", {
    sizePercent: Number(document.getElementById("subtitleSize").value),
    position: position,
    offsetPercent: normalizeSubtitleOffset(document.getElementById("subtitleOffset").value),
    shadow: document.getElementById("subtitleShadow").value
  });
}

function normalizeSubtitleOffset(value) {
  if (value === null || typeof value === "undefined" || value === "") return 50;
  var number = Number(value);
  return isFinite(number) ? Math.max(0, Math.min(100, Math.round(number))) : 50;
}

function mediaSubtitleOffsetPreview(input) {
  subtitleOffsetEditing = true;
  document.getElementById("subtitleOffsetValue").textContent = normalizeSubtitleOffset(input.value) + "%";
}

function mediaSubtitleOffsetCommit(input) {
  input.value = String(normalizeSubtitleOffset(input.value));
  subtitleOffsetEditing = false;
  mediaSubtitleStyleChanged();
}

function suspendRemoteControl() {
  setMediaControllerActive(false);
}
function resumeRemoteControl() {
  setMediaControllerActive(true);
}
document.addEventListener("visibilitychange", resumeRemoteControl, false);
document.addEventListener("keydown", function (event) {
  if (event.key === "Escape") mediaDismissSheet();
}, false);
window.addEventListener("pagehide", suspendRemoteControl, false);
window.addEventListener("pageshow", resumeRemoteControl, false);
resumeRemoteControl();
