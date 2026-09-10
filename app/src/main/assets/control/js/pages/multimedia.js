var multimediaUpload = null;
var multimediaNotice = false, multimediaChoosing = false;
var multimediaPollFlight = null;
function multimediaFailure(message) {
  multimediaNotice = true;
  showMultimediaStatus(message);
  toast(message, true);
}
function beginMultimediaChoice() {
  if (multimediaUpload || document.getElementById("multimediaChoose").disabled) return false;
  multimediaNotice = true;
  multimediaChoosing = true;
  showMultimediaStatus("请选择图片或视频，选好后将开始读取文件");
  return true;
}
function multimediaChooserResult(message) {
  if (!multimediaChoosing) return;
  if (message) { multimediaChoosing = false; multimediaFailure(message); }
  else setTimeout(function () {
    if (multimediaChoosing && !multimediaUpload) {
      multimediaChoosing = false;
      multimediaFailure("系统已返回文件，但网页未收到文件，请重新选择或换用系统文件管理器");
    }
  }, 5000);
}
function showMultimediaStatus(text) {
  document.getElementById("multimediaStatus").textContent = text || "";
}
function pollMultimedia() {
  if (document.hidden || multimediaUpload || multimediaPollFlight) return;
  var flight = multimediaPollFlight = {};
  flight.request = api("/api/multimedia/control", { action: "state" }, function (error, result) {
    if (multimediaPollFlight !== flight) return;
    multimediaPollFlight = null;
    if (document.hidden || multimediaUpload) return;
    if (error || !result) return;
    document.getElementById("multimediaChoose").disabled = !!result.active;
    document.getElementById("multimediaStop").style.display = result.active ? "" : "none";
    if (!multimediaNotice && !multimediaChoosing) showMultimediaStatus(result.message);
  });
}
function sendMultimediaFile(input) {
  var file = input.files && input.files[0], target;
  multimediaChoosing = false;
  if (!file) { multimediaFailure("未取得文件，请重新选择图片或视频"); return; }
  multimediaNotice = false;
  try {
    if (state && state.takeoverReceiverUrl) throw new Error("请先退出接管，再投送多媒体");
    target = normalizeReceiverAddress(document.getElementById("remoteCatalogUrl").value);
    if (!target) throw new Error("请先填写上方电视地址");
    if (!file.size || file.size > 1024 * 1024 * 1024) throw new Error("请选择不超过 1 GB 的图片或视频");
  } catch (error) { input.value = ""; multimediaFailure(error.message); return; }
  var xhr = multimediaUpload = new XMLHttpRequest();
  document.getElementById("multimediaChoose").disabled = true;
  document.getElementById("multimediaStop").style.display = "";
  showMultimediaStatus("正在读取文件…");
  xhr.open("POST", "/api/multimedia/upload?receiverUrl=" + encodeURIComponent(target) + "&preserveAudio=" + (document.getElementById("multimediaPreserveAudio").checked ? "1" : "0") + "&name=" + encodeURIComponent(file.name), true);
  xhr.setRequestHeader("Content-Type", "application/octet-stream");
  xhr.timeout = 180000;
  xhr.upload.onprogress = function (event) {
    if (event.lengthComputable) showMultimediaStatus("读取文件 " + Math.round(event.loaded / event.total * 100) + "%");
  };
  xhr.onload = function () {
    input.value = "";
    multimediaUpload = null;
    var result = null;
    try { result = JSON.parse(xhr.responseText); } catch (ignored) {}
    if (xhr.status < 200 || xhr.status >= 300 || !result || result.ok === false)
      multimediaFailure(result && (result.error || result.message) || "文件投送失败：服务器未返回有效结果");
    document.getElementById("multimediaChoose").disabled = false;
    pollMultimedia();
  };
  function failed(message) { input.value = ""; multimediaUpload = null; document.getElementById("multimediaChoose").disabled = false; multimediaFailure(message); }
  xhr.onerror = function () { failed("无法读取或发送文件，请检查文件权限和网络连接"); };
  xhr.onabort = function () { failed("已取消文件投送"); };
  xhr.ontimeout = function () { failed("文件投送超时，请重试"); };
  try { xhr.send(file); } catch (error) { failed("无法读取文件：" + error.message); }
}
function stopMultimedia() {
  if (multimediaUpload) multimediaUpload.abort();
  api("/api/multimedia/control", { action: "stopSending" }, function () { pollMultimedia(); });
}
document.addEventListener("visibilitychange", function () {
  if (document.hidden) {
    var flight = multimediaPollFlight;
    multimediaPollFlight = null;
    if (flight && flight.request) flight.request.abort();
  } else pollMultimedia();
}, false);
setInterval(pollMultimedia, 1500);
pollMultimedia();
