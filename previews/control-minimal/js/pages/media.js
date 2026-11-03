var mediaSniffedKey = null, mediaSniffedBusy = false;

function mediaBitrateLabel(value, decimals) {
  var bitrate = Number(value);
  if (!(bitrate > 0)) return "";
  return bitrate >= 1000000 ? (bitrate / 1000000).toFixed(decimals) + " Mbps"
    : Math.round(bitrate / 1000) + " kbps";
}

function mediaResourceDescription(url, resource) {
  resource = resource || {};
  var path = String(url || "").split(/[?#]/)[0];
  var match = path.match(/\.([a-z0-9]+)$/i);
  var extension = match ? match[1].toUpperCase() : "";
  if (!extension && /[?&](?:format|type)=m3u8(?:[&#]|$)/i.test(url)) extension = "M3U8";
  var kind = resource.type === "live" ? "直播" : resource.type === "video" ? "视频"
    : resource.type === "audio" ? "音乐" : resource.probeStatus === "unavailable" ? "类型未知" : "待探测";
  var type = kind + (extension ? " · " + extension : "");
  var info = [];
  if (resource.type === "video" || resource.type === "audio") {
    var seconds = Math.floor(Number(resource.durationMs) / 1000);
    info.push(seconds > 0 ? "时长 " + (seconds >= 3600 ? Math.floor(seconds / 3600) + ":" : "")
      + (seconds >= 3600 && Math.floor(seconds / 60) % 60 < 10 ? "0" : "")
      + Math.floor(seconds / 60) % 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60 : "时长未知");
  }
  if (resource.type === "live" || resource.type === "video")
    info.push(resource.width > 0 && resource.height > 0 ? resource.width + "×" + resource.height : "分辨率未知");
  if (resource.type === "live" || resource.type === "video" || resource.type === "audio") {
    var bitrate = mediaBitrateLabel(resource.bitrate, 2);
    info.push(bitrate ? (resource.bitrateEstimated ? "约 " : "") + bitrate : "码率未知");
  }
  if (resource.probeStatus === "pending") info.push("后台探测中…");
  else if (resource.probeStatus === "unavailable") info.push(resource.probeError || "未能获取完整信息，可尝试播放");
  // Drop signed query parameters only in the label; playback keeps the original URL.
  var shortUrl = path.length > 64 ? path.slice(0, 26) + "…" + path.slice(-37) : path;
  return { type: type, url: shortUrl, info: info.join(" · ") };
}

function mediaCurrentSourceDescription(data) {
  var stats = data && data.currentSourceStats || {}, info = [], bitrate = mediaBitrateLabel(stats.bitrate, 1);
  if (!stats.audioOnly && Number(stats.width) > 0 && Number(stats.height) > 0)
    info.push(Math.round(Number(stats.width)) + "×" + Math.round(Number(stats.height)));
  if (bitrate) info.push(bitrate);
  var frameRate = Number(stats.frameRate), streamRate = Number(stats.videoStreamFrameRate);
  if (!stats.audioOnly && (typeof stats.actualFrameRateAvailable === "boolean" || streamRate > 0)) {
    var actual = stats.actualFrameRateAvailable ? Number(stats.actualFrameRate).toFixed(1) : "--";
    var source = streamRate > 0 ? stats.scanType === "interlaced"
      ? streamRate.toFixed(1) + "帧/" + (streamRate * 2).toFixed(1) + "场"
      : streamRate.toFixed(1) + "fps" : "--fps";
    info.push("输出 " + actual + "fps / 源 " + source);
  } else if (!stats.audioOnly && frameRate > 0)
    info.push((frameRate >= 10 ? Math.round(frameRate) : frameRate.toFixed(1))
      + "fps" + (stats.sourceFrameRate ? "（源）" : ""));
  if (!stats.audioOnly && stats.scanType === "interlaced")
    info.push("去隔行 " + (stats.deinterlace === "bwdif_field" ? "BWDIF逐场"
      : stats.deinterlace === "system_unverified" ? "系统处理（待验证）" : "未知"));
  if (info.length) return info.join(" · ");
  return data && data.prepared ? "正在获取播放参数…" : "正在准备播放…";
}

function renderMediaSources(data) {
  data = data || {};
  var resources = data.webPage && Array.isArray(data.sniffedResources) ? data.sniffedResources : [];
  // Web-based channels can still have multiple configured playback lines.
  var web = !!data.webPage && resources.length > 0;
  var count = web ? resources.length : Math.max(0, Number(data.sourceCount) || 0);
  var visible = web ? count > 0 : count > 1;
  var currentSourceDescription = web ? "" : mediaCurrentSourceDescription(data);
  var key = JSON.stringify([web, resources, count, data.sourceIndex, data.sourceKey, data.webPageKey,
    currentSourceDescription]);
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
      var description = web ? mediaResourceDescription(resources[index].url, resources[index]) : null;
      title.textContent = web ? "资源 " + (index + 1) + " · " + description.type : "线路 " + (index + 1) + (selected ? " · 当前播放" : "");
      detail.textContent = web ? description.url
        : selected ? currentSourceDescription : "点击播放此线路";
      detail.title = web ? resources[index].url || "" : detail.textContent;
      if (web) {
        detail.style.whiteSpace = "normal";
        detail.style.wordBreak = "break-all";
      }
      button.appendChild(title); button.appendChild(detail);
      if (web && description.info) {
        var metadata = document.createElement("span");
        metadata.textContent = description.info;
        button.appendChild(metadata);
      }
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
  var request = { action: "playSniffed", url: resource.url };
  if (resource.pageKey) request.pageKey = resource.pageKey;
  mediaSelectSource("/api/control", request, button);
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
  mediaSeekPending = false,
  mediaSeekRevision = 0,
  mediaLevels = {volume: mediaCreateLevel("volume", 0), brightness: mediaCreateLevel("brightness", 50)},
  subtitleOffsetEditing = false,
  mediaState = null,
  mediaControllerBuilt = false,
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
  if (mediaState && mediaState.fileDownloadAvailable === true && mediaState.sourceKey) {
    // Native browser download streams to storage; do not buffer a movie in JS/Blob.
    var link = document.createElement("a");
    link.href = "/api/media/download?sourceKey=" + encodeURIComponent(mediaState.sourceKey);
    link.download = mediaState.downloadName || "nTv-media";
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    return;
  }
  var ip = location.hostname,
    port = location.port || "9966";
  navigateTo(
    "/video-recorder.html?ip=" +
    encodeURIComponent(ip) +
    "&port=" +
    encodeURIComponent(port) +
    "&v=" +
    Date.now());
}

var mediaPreviewKey = "", mediaPreviewGeneration = 0,
  mediaPreviewCrop = { top: 0, bottom: 0 },
  mediaShotBusy = false, mediaShotRequest = null, mediaShotUrl = "";

function mediaConstrainBackdrop() {
  var frame = document.getElementById("mediaBackdropFrame"), image = document.getElementById("mediaBackdropImage"),
    progress = document.getElementById("mediaProgressSection");
  if (!frame || !image || !progress || frame.hidden || !image.naturalWidth || !image.naturalHeight) return;
  var width = document.documentElement.clientWidth,
    height = Math.max(0, Math.round(progress.getBoundingClientRect().top)),
    cropTop = mediaPreviewCrop.top * image.naturalHeight,
    contentHeight = image.naturalHeight * (1 - mediaPreviewCrop.top - mediaPreviewCrop.bottom),
    scale = Math.max(width / image.naturalWidth, height / contentHeight),
    renderedWidth = image.naturalWidth * scale,
    renderedHeight = image.naturalHeight * scale;
  frame.style.cssText = "top:0;left:0;width:" + width + "px;height:" + height + "px";
  image.style.cssText = "left:" + ((width - renderedWidth) / 2).toFixed(2) + "px;top:" +
    ((height - contentHeight * scale) / 2 - cropTop * scale).toFixed(2) + "px;width:" +
    renderedWidth.toFixed(2) + "px;height:" + renderedHeight.toFixed(2) + "px";
}

// Inspect the small decorative preview only: reject broken green captures and trim
// uniform encoded letterbox rows. The user's downloaded screenshot stays untouched.
function mediaAnalyzePreview(image) {
  var result = { invalidGreen: false, top: 0, bottom: 0 };
  try {
    var canvas = document.createElement("canvas");
    canvas.width = 96;
    canvas.height = 54;
    var context = canvas.getContext("2d");
    if (!context) return result;
    context.drawImage(image, 0, 0, canvas.width, canvas.height);
    var pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
    var count = 0, low = 255, high = 0;
    for (var i = 0; i < pixels.length; i += 4) {
      var r = pixels[i], g = pixels[i + 1], b = pixels[i + 2];
      if (pixels[i + 3] > 240 && r < 40 && b < 40 && g > 80 && g > 3 * Math.max(r, b)) {
        count++;
        low = Math.min(low, g);
        high = Math.max(high, g);
      }
    }
    result.invalidGreen = count >= canvas.width * canvas.height * 0.98 && high - low <= 6;
    if (result.invalidGreen) return result;

    function blackRow(y) {
      var start = Math.floor(canvas.width * 0.12), end = Math.ceil(canvas.width * 0.88),
        samples = end - start, dark = 0, sum = 0, squareSum = 0;
      for (var x = start; x < end; x++) {
        var offset = (y * canvas.width + x) * 4;
        var luma = (pixels[offset] * 3 + pixels[offset + 1] * 6 + pixels[offset + 2]) / 10;
        if (luma <= 20) dark++;
        sum += luma;
        squareSum += luma * luma;
      }
      var average = sum / samples, variance = squareSum / samples - average * average;
      return dark >= samples * 0.94 && average <= 11 && variance <= 80;
    }

    var limit = Math.floor(canvas.height * 0.24), top = 0, bottom = 0;
    while (top < limit && blackRow(top)) top++;
    while (bottom < limit && blackRow(canvas.height - 1 - bottom)) bottom++;
    if (top < 2) top = 0;
    if (bottom < 2) bottom = 0;
    if (top + bottom <= canvas.height * 0.35) {
      result.top = top / canvas.height;
      result.bottom = bottom / canvas.height;
    }
  } catch (ignored) {}
  return result;
}

function mediaUpdatePreview(ready) {
  var frame = document.getElementById("mediaBackdropFrame"), image = document.getElementById("mediaBackdropImage");
  if (mediaState.audioOnly) { frame.hidden = image.hidden = true; mediaPreviewKey = ""; return; }
  var key = (mediaState.webPageVisible ? mediaState.webPageKey || "" : mediaState.sourceKey || "")
    + "|" + (mediaState.group || "") + "|" + (mediaState.name || "");
  if (key !== mediaPreviewKey) {
    frame.hidden = image.hidden = true;
    mediaPreviewKey = ""; // Invalidate an in-flight still from the previous tab even before the new one is ready.
  }
  if (!ready || key === mediaPreviewKey) return;
  mediaPreviewKey = key;
  var generation = ++mediaPreviewGeneration;
  image.onload = function () {
    if (generation !== mediaPreviewGeneration) return;
    var analysis = mediaAnalyzePreview(image);
    mediaPreviewCrop = { top: analysis.top, bottom: analysis.bottom };
    frame.hidden = image.hidden = !!mediaState.audioOnly || key !== mediaPreviewKey || analysis.invalidGreen;
    if (!frame.hidden) mediaConstrainBackdrop();
  };
  image.onerror = function () {
    if (generation === mediaPreviewGeneration) frame.hidden = image.hidden = true;
  };
  // One small JPEG still when the controller opens or the channel changes.
  // Full-resolution JPEG screenshots are a separate, explicit user action.
  image.src = "/api/recording/screenshot?preview=1&t=" + Date.now();
}

function mediaUpdateArtwork() {
  var image = document.getElementById("mediaArtwork");
  var webUrl = mediaState.webPageVisible ? mediaState.webArtworkUrl || "" : "";
  if (!/^https?:\/\//i.test(webUrl) && !/^data:image\/(png|jpeg|gif|webp);base64,/i.test(webUrl)) webUrl = "";
  var nativeKey = !mediaState.webPageVisible && mediaState.audioOnly && mediaState.prepared ? mediaState.artworkKey || "" : "";
  var key = webUrl ? "web:" + (mediaState.webPageKey || "") + ":" + webUrl : nativeKey;
  if (image.artworkKey === key) return;
  image.artworkKey = key;
  image.hidden = true;
  if (!key) { image.src = ""; return; }
  image.onload = function () { if (image.artworkKey === key) image.hidden = false; };
  image.onerror = function () { if (image.artworkKey === key) image.hidden = true; };
  image.referrerPolicy = "no-referrer";
  image.src = webUrl || "/api/media/artwork?key=" + encodeURIComponent(nativeKey);
}

function mediaCloseShot(event) {
  var backdrop = document.getElementById("mediaShotBackdrop");
  if (event && event.target !== backdrop) return;
  backdrop.className = "media-sheet-backdrop";
  backdrop.setAttribute("aria-hidden", "true");
  if (window.NtvNavigation) NtvNavigation.overlayClosed("media-shot");
}

function mediaShowCapturedScreenshot(url, savedToGallery, width, height, extension) {
  if (!mediaControllerOpen || !mediaState) return;
  document.getElementById("mediaShotPreview").src = url;
  var save = document.getElementById("mediaShotSave"),
    saved = document.getElementById("mediaShotSaved");
  save.hidden = !!savedToGallery;
  saved.hidden = !savedToGallery;
  if (savedToGallery) {
    saved.textContent = "原图 " + width + "×" + height + " 已保存到相册 Pictures/nTv";
  } else {
    save.href = url;
    save.download = "nTv-screenshot-" + Date.now() + (extension || ".jpg");
  }
  mediaCloseSettings(); mediaCloseSniffed();
  var backdrop = document.getElementById("mediaShotBackdrop");
  backdrop.className = "media-sheet-backdrop open";
  backdrop.setAttribute("aria-hidden", "false");
  if (window.NtvNavigation) NtvNavigation.overlayOpen("media-shot", mediaCloseShot);
}

function mediaNativeScreenshotReady(width, height) {
  if (!window.NtvDevice || typeof NtvDevice.consumeScreenshotPreview !== "function") return;
  var preview = NtvDevice.consumeScreenshotPreview();
  if (preview) mediaShowCapturedScreenshot(preview, true, width, height);
}

function mediaCaptureScreenshot() {
  if (mediaShotBusy || !mediaControllerOpen || !mediaState || mediaState.screenshotAvailable !== true) return;
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
  request.timeout = 60000;
  request.onload = function () {
    if (!mediaControllerOpen || generation !== mediaControllerGeneration) { finish(); return; }
    var blob = request.response;
    if (request.status !== 200 || !blob || !blob.size
        || !/^image\/(jpeg|png)/i.test(blob.type)) {
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
    mediaShowCapturedScreenshot(mediaShotUrl, false, 0, 0,
      blob.type.indexOf("image/png") === 0 ? ".png" : ".jpg");
    document.getElementById("mediaShotSave").click();
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
  if (window.NtvNavigation) NtvNavigation.overlayOpen("media-settings", mediaCloseSettings);
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

  var ids = ["mediaShotBackdrop", "mediaSniffedBackdrop", "mediaSettingsBackdrop"];
  var close = [mediaCloseShot, mediaCloseSniffed, mediaCloseSettings];
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
  if (window.NtvNavigation) NtvNavigation.overlayOpen("media-sniffed", mediaCloseSniffed);
  document.getElementById("mediaSniffedButton").setAttribute("aria-expanded", "true");
  backdrop.querySelector("button").focus();
}

function mediaCloseSniffed(event) {
  var backdrop = document.getElementById("mediaSniffedBackdrop");
  if (event && event.target !== backdrop) return;
  backdrop.className = "media-sheet-backdrop";
  backdrop.setAttribute("aria-hidden", "true");
  if (window.NtvNavigation) NtvNavigation.overlayClosed("media-sniffed");
  document.getElementById("mediaSniffedButton").setAttribute("aria-expanded", "false");
}

function mediaCloseSettings(event) {
  var backdrop = document.getElementById("mediaSettingsBackdrop");
  if (event && event.target !== backdrop) return;
  backdrop.className = "media-sheet-backdrop";
  backdrop.setAttribute("aria-hidden", "true");
  if (window.NtvNavigation) NtvNavigation.overlayClosed("media-settings");
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

function fillMediaTrackSelect(id, tracks, selected, emptyText, allowOff) {
  var select = document.getElementById(id);
  if (!select) return;
  var key = JSON.stringify([tracks, emptyText, allowOff]);
  if (select.mediaOptionsKey !== key) {
    select.mediaOptionsKey = key;
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
  }
  if (select.value !== String(selected)) select.value = String(selected);
  if (select.selectedIndex < 0) select.selectedIndex = 0;
  select.disabled = !tracks.length;
}

function buildMediaController() {
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
    mediaLevelMarkup("volume", "播放设备音量", '<path d="M11 4 6 8H3v8h3l5 4V4Z M15 8a6 6 0 0 1 0 8 M18 5a10 10 0 0 1 0 14"/>') +
    mediaLevelMarkup("brightness", "播放设备亮度", '<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"/>') +
    '<div class="media-scene"><img id="mediaArtwork" class="media-artwork" alt="专辑封面" hidden><span id="mediaLiveBadge" class="media-live-badge">正在播放</span><div class="media-scene-actions"><button id="mediaScreenshot" class="media-circle-action" type="button" hidden aria-label="截图" onclick="mediaCaptureScreenshot()"><svg class="media-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h4l2-3h4l2 3h4a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2Z"/><circle cx="12" cy="13" r="4"/></svg></button></div></div>' +
    '<div class="media-now"><span id="mediaGroup">当前频道</span><small id="mediaStatus">正在读取播放状态…</small></div>' +
    '<div id="mediaProgressSection" class="media-progress"><div id="mediaSeekBar" class="media-seekbar"><div class="media-seek-track"><i id="mediaSeekFill"></i><i id="mediaSeekKnob"></i></div><input id="mediaProgress" type="range" min="0" max="1" value="0" step="250" aria-label="播放进度" oninput="mediaProgressPreview(this)" onchange="mediaSeekCommit(this)" ontouchcancel="mediaProgressCancel()" onblur="mediaProgressCancel()"></div><div class="media-time"><span id="mediaPosition">--:--</span><span id="mediaDuration">直播</span></div><div id="mediaLiveBufferActions" class="media-live-buffer-actions" hidden><small id="mediaLiveBufferHint">可向前调整未播放的缓冲</small><button id="mediaLiveEdge" type="button" onclick="mediaJumpBufferedEdge()">跳至缓冲末端</button></div></div>' +
    '<div class="media-transport"><button id="mediaPrevious" type="button" aria-label="上一个频道" onclick="mediaCommand(\'previous\')">' + previous + '<span>上一个</span></button>' +
    '<button id="mediaToggle" class="media-play-button" type="button" aria-label="播放" onclick="mediaCommand(\'toggle\')">' + play + pause + '</button>' +
    '<button id="mediaNext" type="button" aria-label="下一个频道" onclick="mediaCommand(\'next\')">' + next + '<span>下一个</span></button></div>' +
    '</section>';
}

function renderMediaController(data) {
  mediaState = data || {};
  document.getElementById("mediaDownload").setAttribute("aria-label",
    mediaState.fileDownloadAvailable ? "下载当前媒体文件" : "直播录制与下载");
  renderMediaSources(mediaState);
  document.getElementById("mediaSubtitlesEnabled").checked = mediaState.subtitlesEnabled !== false;
  var rebuilt = !mediaControllerBuilt;
  if (rebuilt) {
    buildMediaController();
    mediaControllerBuilt = true;
  }
  if (!subtitleOffsetEditing && !mediaLevels.volume.editing && !mediaLevels.brightness.editing) {
    fillMediaTrackSelect("mediaVideo", mediaState.videoTracks || [], mediaState.selectedVideoTrack, "未检测到视轨", false);
    fillMediaTrackSelect("mediaAudio", mediaState.audioTracks || [], mediaState.selectedAudioTrack, "未检测到音轨", false);
    fillMediaTrackSelect("mediaSubtitle", mediaState.subtitleTracks || [], mediaState.selectedSubtitleTrack, "未检测到字幕", true);
  }
  mediaRenderVolume();
  mediaLevels.brightness.render();
  var available = mediaState.available === true,
    prepared = mediaState.prepared === true,
    duration = Number(mediaState.durationMs) || 0,
    position = Number(mediaState.positionMs) || 0,
    style = mediaState.subtitleStyle || {},
    favorite = mediaState.favorite === true,
    favoriteButton = document.getElementById("mediaFavorite");
  document.getElementById("mediaTitle").textContent = mediaState.name || "当前没有节目";
  document.getElementById("mediaGroup").textContent = mediaState.webMedia
    ? [mediaState.artist, mediaState.album].filter(function (v) { return !!v; }).join(" · ") || "网页媒体"
    : mediaState.group || "当前频道";
  document.getElementById("mediaStatus").textContent = mediaState.loadError ? mediaState.loadError : mediaState.webPageVisible
    ? mediaState.webMediaError || (mediaState.webMedia ? (mediaState.playing ? "网页正在播放" : "网页媒体已暂停") : "正在浏览网页")
    : !available
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
  document.getElementById("mediaLiveBadge").textContent = mediaState.loadError ? "加载失败" : mediaState.webPageVisible
    ? (mediaState.webMedia ? (mediaState.playing ? "播放中" : "已暂停") : "网页")
    : !available ? "等待播放" : !prepared ? "加载中" : !mediaState.playing ? "已暂停" : duration > 0 ? "播放中" : "直播中";
  var screenshotAvailable = available && prepared && !mediaState.audioOnly && mediaState.screenshotAvailable === true;
  var screenshotButton = document.getElementById("mediaScreenshot");
  screenshotButton.hidden = !screenshotAvailable;
  screenshotButton.disabled = !screenshotAvailable || mediaShotBusy;
  mediaUpdatePreview(screenshotAvailable);
  mediaUpdateArtwork();
  var live = !(duration > 0), liveBuffer = Math.max(0, Number(mediaState.liveBufferMs) || 0);
  document.getElementById("mediaProgressSection").hidden = !!mediaState.audioOnly && live && !liveBuffer;
  var progress = document.getElementById("mediaProgress");
  if (!mediaSeeking) {
    progress.max = String(Math.max(1, live ? Number(mediaState.liveBufferSeekMaxMs) || 0 : duration));
    progress.value = String(live ? 0 : Math.min(duration, position));
    progress.ntvLive = live;
    progress.ntvSourceKey = mediaState.sourceKey || "";
  }
  progress.disabled = live ? mediaState.liveBufferSeekable !== true || !!mediaState.liveBufferAdjusting || mediaSeekPending : mediaState.seekable !== true;
  progress.setAttribute("aria-label", live ? "直播缓冲进度" : "播放进度");
  renderMediaProgress(progress);
  if (!mediaSeeking)
    document.getElementById("mediaPosition").textContent =
      live ? mediaState.liveBufferAdjusting ? "正在跳过缓冲" : "当前播放" : formatMediaTime(position);
  document.getElementById("mediaDuration").textContent =
    live ? "缓冲 " + (liveBuffer / 1000).toFixed(1) + " 秒" : formatMediaTime(duration);
  document.getElementById("mediaLiveBufferActions").hidden = !live || !!mediaState.webPageVisible;
  document.getElementById("mediaLiveEdge").disabled = progress.disabled;
  document.getElementById("mediaLiveBufferHint").textContent = mediaState.liveBufferAdjusting ? "正在向前调整，请稍候"
    : !mediaState.playing && liveBuffer > 0 ? "播放后可调整缓冲"
    : mediaState.liveBufferSeekable ? "可向前调整未播放的缓冲" : liveBuffer >= 500 ? "当前播放器不支持缓冲调节" : "缓冲不足时无法调整";
  var toggle = document.getElementById("mediaToggle");
  toggle.className = "media-play-button" + (mediaState.playing ? " is-playing" : "");
  toggle.setAttribute("aria-label", mediaState.playing ? "暂停" : "播放");
  toggle.disabled = !available || !!mediaState.webPageVisible
    && !(mediaState.playing ? mediaState.pauseAvailable : mediaState.playAvailable);
  document.getElementById("mediaPrevious").disabled = mediaState.previousAvailable === false;
  document.getElementById("mediaNext").disabled = mediaState.nextAvailable === false;
  var speed = document.getElementById("mediaSpeed");
  speed.value = String(Number(mediaState.speed) || 1);
  speed.disabled = !prepared || !mediaState.speedAvailable || !!mediaState.webPageVisible;
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
  if (rebuilt && window.requestAnimationFrame) requestAnimationFrame(mediaConstrainBackdrop);
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
      mediaControllerBuilt = false;
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
  mediaSeekPending = false;
  mediaSeekRevision++;
  mediaLevels.volume.reset();
  mediaLevels.brightness.reset();
  subtitleOffsetEditing = false;
  clearTimeout(mediaControllerTimer);
  clearTimeout(mediaClockTimer);
  if (active) {
    mediaControllerBuilt = false;
    mediaNeedsDetail = true;
    document.getElementById("mediaConnectionStatus").textContent = "连接中";
    refreshMediaController();
    scheduleMediaClock();
  } else {
    mediaCloseSettings();
    mediaCloseSniffed();
    mediaCloseShot();
    if (mediaShotRequest) mediaShotRequest.abort();
    mediaPreviewKey = "";
    mediaPreviewGeneration++;
    var previewFrame = document.getElementById("mediaBackdropFrame"),
      previewImage = document.getElementById("mediaBackdropImage");
    if (previewFrame) previewFrame.hidden = true;
    if (previewImage) {
      previewImage.hidden = true;
      previewImage.removeAttribute("src");
    }
  }
}

function mediaCommand(action, extra) {
  if (!mediaControllerOpen) return;
  var body = extra || {},
    generation = mediaControllerGeneration,
    sequence = ++mediaRequestSequence;
  var bufferCommand = action === "liveSeek";
  if (bufferCommand) {
    mediaSeekPending = true;
    renderMediaController(mediaState);
  }
  body.action = action;
  if (mediaState && mediaState.webPageVisible && action !== "volume" && action !== "brightness") {
    body.webPageKey = mediaState.webPageKey || "";
    body.webMediaToken = mediaState.webMediaToken || "";
    if (action === "toggle") body.action = mediaState.playing ? "pause" : "play";
  }
  clearTimeout(mediaControllerTimer);
  api("/api/media/control", body, function (error, data) {
    if (bufferCommand && generation === mediaControllerGeneration) {
      mediaSeekPending = false;
      if (mediaControllerOpen && mediaState) renderMediaController(mediaState);
    }
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

// The two sliders share request coalescing, cancellation and stale-session guards.
function mediaCreateLevel(action, fallback) {
  var id = action === "volume" ? "mediaVolume" : "mediaBrightness",
    control = { editing: false, finalizing: false, sending: false, queued: null,
      start: null, timer: null, request: null, sentAt: 0 };
  function value() {
    var current = Number(mediaState && mediaState[action]);
    return mediaState && mediaState[action] != null && isFinite(current) ? Math.round(current) : fallback;
  }
  function queue(level, finalValue) {
    control.queued = Math.max(action === "volume" ? 0 : 1, Math.round(Number(level) || 0));
    if (finalValue) control.finalizing = true;
    flush(!!finalValue);
  }
  function flush(force) {
    if (!mediaControllerOpen || control.sending || control.queued === null) return;
    clearTimeout(control.timer);
    control.timer = null;
    var wait = force ? 0 : Math.max(0, 70 - (Date.now() - control.sentAt));
    if (wait > 0) { control.timer = setTimeout(function () { flush(false); }, wait); return; }
    var level = control.queued, generation = mediaControllerGeneration, body = {action: action};
    body[action] = level;
    control.queued = null;
    control.sending = true;
    control.sentAt = Date.now();
    control.request = api("/api/media/control", body, function (error, data) {
      // A request aborted by reset must not clear a newer session's in-flight state.
      if (!mediaControllerOpen || generation !== mediaControllerGeneration) return;
      control.request = null;
      control.sending = false;
      if (!error && data) mediaState = mergeMediaState(data);
      if (control.queued !== null) { flush(control.finalizing); return; }
      if (!control.finalizing) return;
      control.editing = false;
      control.finalizing = false;
      control.start = null;
      if (error) toast(error.message, true);
      control.render();
      scheduleMediaControllerRefresh(error ? 500 : 250);
    });
  }
  function paint(input) {
    var percent = Math.round(Math.max(0, Math.min(100,
      Number(input.value) / Math.max(1, Number(input.max)) * 100)));
    document.getElementById(id + "Value").textContent = input.disabled ? "--" : percent + "%";
    document.getElementById(id + "Fill").style.height = percent + "%";
    document.getElementById(id + "Knob").style.bottom = percent + "%";
    document.getElementById(id + "Control").className = "media-volume-control"
      + (action === "brightness" ? " media-brightness-control" : "") + (input.disabled ? " unavailable" : "");
    input.setAttribute("aria-valuetext", input.disabled ? "设备不支持调节" : percent === 0 ? "静音" : percent + "%");
  }
  function begin() {
    if (control.editing) return;
    mediaRequestSequence++;
    clearTimeout(mediaControllerTimer);
    control.editing = true;
    control.finalizing = false;
    control.start = value();
  }
  control.render = function () {
    var input = document.getElementById(id);
    if (!input || control.editing) return;
    input.max = String(action === "volume" ? Math.max(1, Number(mediaState && mediaState.volumeMax) || 1) : 100);
    input.value = String(Math.max(action === "volume" ? 0 : 1, value()));
    input.disabled = !mediaState || mediaState[action + "Available"] !== true;
    paint(input);
  };
  control.preview = function (input) {
    if (!input || input.disabled) return;
    begin(); paint(input); queue(input.value, false);
  };
  control.commit = function (input) {
    if (!input || input.disabled) return;
    begin(); paint(input); queue(input.value, true);
  };
  control.cancel = function () {
    if (!control.editing || control.finalizing) return;
    var input = document.getElementById(id), restore = control.start === null ? value() : control.start;
    if (input) { input.value = String(restore); paint(input); }
    queue(restore, true);
  };
  control.reset = function () {
    clearTimeout(control.timer);
    var request = control.request;
    control.request = control.timer = control.queued = control.start = null;
    control.editing = control.finalizing = control.sending = false;
    control.sentAt = 0;
    if (request) request.abort();
  };
  return control;
}

function mediaLevelMarkup(action, label, icon) {
  var id = action === "volume" ? "mediaVolume" : "mediaBrightness";
  return '<div id="' + id + 'Control" class="media-volume-control' + (action === "brightness" ? ' media-brightness-control' : '') + '"><span id="' + id + 'Value">' + (action === "brightness" ? '50%' : '--') + '</span><div class="media-volume-range"><div class="media-volume-track"><i id="' + id + 'Fill" class="media-level-fill"></i><i id="' + id + 'Knob" class="media-level-knob"></i></div><input id="' + id + '" class="media-level-input" type="range" min="' + (action === "volume" ? '0' : '1') + '" max="' + (action === "volume" ? '15' : '100') + '" value="' + (action === "volume" ? '0' : '50') + '" step="1" aria-label="' + label + '" aria-orientation="vertical" oninput="mediaLevels.' + action + '.preview(this)" onchange="mediaLevels.' + action + '.commit(this)" onblur="mediaLevels.' + action + '.cancel()" ontouchcancel="mediaLevels.' + action + '.cancel()"></div><svg viewBox="0 0 24 24" aria-hidden="true">' + icon + '</svg></div>';
}

function mediaRenderVolume() { mediaLevels.volume.render(); }
function mediaVolumePreview(input) { mediaLevels.volume.preview(input); }
function mediaVolumeCommit(input) { mediaLevels.volume.commit(input); }
function mediaVolumeCancel() { mediaLevels.volume.cancel(); }

function mediaToggleFavorite() {
  mediaCommand("favorite");
}

function renderMediaProgress(input) {
  var percent = Math.max(0, Math.min(100, (Number(input.value) || 0) / Math.max(1, Number(input.max) || 1) * 100));
  document.getElementById("mediaSeekBar").className = "media-seekbar" + (input.ntvLive ? " live-buffer" : "") + (input.disabled ? " unavailable" : "");
  document.getElementById("mediaSeekFill").style.width = percent + "%";
  document.getElementById("mediaSeekKnob").style.left = percent + "%";
}

function mediaProgressPreview(input) {
  if (input.disabled) return;
  mediaSeekRevision++;
  mediaSeeking = true;
  renderMediaProgress(input);
  document.getElementById("mediaPosition").textContent = input.ntvLive
    ? "前进 " + (Number(input.value) / 1000).toFixed(1) + " 秒" : formatMediaTime(Number(input.value));
}

function mediaSeekCommit(input) {
  if (mediaSeekPending) return;
  if (input.disabled) { mediaProgressCancel(); return; }
  var value = Number(input.value) || 0;
  if (input.ntvLive) {
    if (!mediaState || input.ntvSourceKey !== mediaState.sourceKey || Number(mediaState.durationMs) > 0) {
      mediaProgressCancel(); toast("节目已变化，请重新调整", true); return;
    }
    if (value >= 100) mediaCommand("liveSeek", { advanceMs: Math.round(value), sourceKey: input.ntvSourceKey });
  } else mediaCommand("seek", { positionMs: Math.round(value) });
  var revision = mediaSeekRevision;
  setTimeout(function () { if (revision === mediaSeekRevision) mediaProgressCancel(); }, 350);
}

function mediaProgressCancel() {
  var revision = ++mediaSeekRevision, generation = mediaControllerGeneration;
  mediaSeeking = false;
  // blur can fire while innerHTML is removing the old controller. Do not
  // synchronously rebuild that same subtree from inside its removal event.
  setTimeout(function () {
    if (revision === mediaSeekRevision && generation === mediaControllerGeneration
        && mediaControllerOpen && mediaState) renderMediaController(mediaState);
  }, 0);
}

function mediaJumpBufferedEdge() {
  var input = document.getElementById("mediaProgress");
  if (!input || input.disabled || !input.ntvLive) return;
  input.value = input.max;
  mediaProgressPreview(input);
  mediaSeekCommit(input);
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
if (window.NtvNavigation) NtvNavigation.beforeLeave(suspendRemoteControl);
window.addEventListener("pageshow", resumeRemoteControl, false);
window.addEventListener("resize", mediaConstrainBackdrop, false);
resumeRemoteControl();
