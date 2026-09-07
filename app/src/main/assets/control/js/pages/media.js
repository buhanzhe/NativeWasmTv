var mediaControllerTimer = null,
  mediaClockTimer = null,
  mediaControllerOpen = false,
  mediaSeeking = false,
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

function mediaOpenSettings() {
  var backdrop = document.getElementById("mediaSettingsBackdrop");
  backdrop.className = "media-sheet-backdrop open";
  backdrop.setAttribute("aria-hidden", "false");
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
    ),
    heartOutline = mediaSvg(
      "M19.66 3.99c-2.64-1.8-5.9-.96-7.66 1.1-1.76-2.06-5.02-2.91-7.66-1.1C2.94 4.95 2.06 6.57 2 8.28c-.14 3.88 3.3 6.99 8.55 11.76l.1.09c.76.69 1.93.69 2.69-.01l.11-.1c5.25-4.76 8.68-7.87 8.55-11.75-.06-1.7-.94-3.32-2.34-4.28zM12.1 18.55l-.1.1-.1-.1C7.14 14.24 4 11.39 4 8.5 4 6.5 5.5 5 7.5 5c1.54 0 3.04.99 3.57 2.36h1.87C13.46 5.99 14.96 5 16.5 5c2 0 3.5 1.5 3.5 3.5 0 2.89-3.14 5.74-7.9 10.05z",
      "media-heart-outline"
    ),
    heartFilled = mediaSvg(
      "M13.35 20.13c-.76.69-1.93.69-2.69-.01l-.11-.1C5.3 15.27 1.87 12.16 2 8.28c.06-1.7.93-3.33 2.34-4.29 2.64-1.8 5.9-.96 7.66 1.1 1.76-2.06 5.02-2.91 7.66-1.1 1.41.96 2.28 2.59 2.34 4.29.14 3.88-3.3 6.99-8.55 11.76l-.1.09z",
      "media-heart-filled"
    ),
    settings = mediaSvg(
      "M19.5 12c0-.23-.01-.45-.03-.68l1.86-1.41c.4-.3.51-.86.26-1.3l-1.87-3.23c-.25-.44-.79-.62-1.25-.42l-2.15.91c-.37-.26-.76-.49-1.17-.68l-.29-2.31C14.8 2.38 14.37 2 13.87 2h-3.73c-.51 0-.94.38-1 .88l-.29 2.31c-.41.19-.8.42-1.17.68l-2.15-.91c-.46-.2-1-.02-1.25.42L2.41 8.62c-.25.44-.14.99.26 1.3l1.86 1.41C4.51 11.55 4.5 11.77 4.5 12s.01.45.03.68l-1.86 1.41c-.4.3-.51.86-.26 1.3l1.87 3.23c.25.44.79.62 1.25.42l2.15-.91c.37.26.76.49 1.17.68l.29 2.31c.06.5.49.88.99.88h3.73c.5 0 .93-.38.99-.88l.29-2.31c.41-.19.8-.42 1.17-.68l2.15.91c.46.2 1-.02 1.25-.42l1.87-3.23c.25-.44.14-.99-.26-1.3l-1.86-1.41c.03-.23.04-.45.04-.68zm-7.46 3.5a3.5 3.5 0 1 1 0-7 3.5 3.5 0 0 1 0 7z"
    ),
    download = mediaSvg(
      "M16.59 9H15V4c0-.55-.45-1-1-1h-4c-.55 0-1 .45-1 1v5H7.41c-.89 0-1.34 1.08-.71 1.71l4.59 4.59c.39.39 1.02.39 1.41 0l4.59-4.59c.63-.63.19-1.71-.7-1.71zM5 19c0 .55.45 1 1 1h12c.55 0 1-.45 1-1s-.45-1-1-1H6c-.55 0-1 .45-1 1z"
    );
  body.innerHTML =
    '<section class="media-player-card">' +
    '<div class="media-artwork" aria-hidden="true"><div class="media-artwork-orbit"></div><span>nTv</span></div>' +
    '<div class="media-title-row"><div class="media-now"><span id="mediaGroup">当前频道</span><b id="mediaTitle">当前节目</b><small id="mediaStatus">正在读取播放状态…</small></div>' +
    '<div class="media-title-actions"><button id="mediaFavorite" class="media-circle-action media-favorite" type="button" aria-label="收藏当前频道" aria-pressed="false" onclick="mediaToggleFavorite()">' +
    heartOutline + heartFilled +
    '</button><button class="media-circle-action" type="button" aria-label="媒体设置" onclick="mediaOpenSettings()">' + settings + '</button></div></div>' +
    '<div class="media-progress"><input id="mediaProgress" type="range" min="0" max="1" value="0" step="250" aria-label="播放进度" oninput="mediaProgressPreview(this)" onchange="mediaSeekCommit(this)"><div class="media-time"><span id="mediaPosition">--:--</span><span id="mediaDuration">直播</span></div></div>' +
    '<div class="media-transport"><button id="mediaPrevious" type="button" aria-label="上一个频道" onclick="mediaCommand(\'previous\')">' + previous + '<span>上一个</span></button>' +
    '<button id="mediaToggle" class="media-play-button" type="button" aria-label="播放" onclick="mediaCommand(\'toggle\')">' + play + pause + '</button>' +
    '<button id="mediaNext" type="button" aria-label="下一个频道" onclick="mediaCommand(\'next\')">' + next + '<span>下一个</span></button></div>' +
    '<button class="media-download-action" type="button" onclick="openVideoRecorderPage()">' + download + '<span><b>视频录制</b><small>录制当前节目或保存画面</small></span><i>打开</i></button>' +
    '</section>';
  fillMediaTrackSelect("mediaVideo", data.videoTracks || [], data.selectedVideoTrack, "未检测到视轨", false);
  fillMediaTrackSelect("mediaAudio", data.audioTracks || [], data.selectedAudioTrack, "未检测到音轨", false);
  fillMediaTrackSelect("mediaSubtitle", data.subtitleTracks || [], data.selectedSubtitleTrack, "未检测到字幕", true);
}

function renderMediaController(data) {
  mediaState = data || {};
  document.getElementById("mediaSubtitlesEnabled").checked = mediaState.subtitlesEnabled !== false;
  var key = mediaTrackKey(mediaState);
  if (key !== mediaRenderKey && !subtitleOffsetEditing) {
    mediaRenderKey = key;
    buildMediaController(mediaState);
  }
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
  favoriteButton.className = "media-circle-action media-favorite" + (favorite ? " selected" : "");
  favoriteButton.disabled = mediaState.favoriteAvailable === false;
  var progress = document.getElementById("mediaProgress");
  progress.max = String(Math.max(1, duration));
  progress.disabled = mediaState.seekable !== true;
  if (!mediaSeeking) progress.value = String(duration > 0 ? Math.min(duration, position) : 0);
  if (!mediaSeeking)
    document.getElementById("mediaPosition").textContent =
      duration > 0 ? formatMediaTime(position) : "--:--";
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

function mediaToggleFavorite() {
  mediaCommand("favorite");
}

function mediaProgressPreview(input) {
  mediaSeeking = true;
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
  if (event.key === "Escape") mediaCloseSettings();
}, false);
window.addEventListener("pagehide", suspendRemoteControl, false);
window.addEventListener("pageshow", resumeRemoteControl, false);
resumeRemoteControl();
