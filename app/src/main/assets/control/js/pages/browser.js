function clearWebCache() {
  document.getElementById("webCacheSize").textContent = "当前占用：清理中…";
  toast("正在清除网页缓存…");
  api("/api/settings", { clearWebCache: true }, function (error, result) {
    if (error) {
      toast(error.message, true);
      refresh();
      return;
    }
    toast(result.message || "网页缓存已清除");
    setTimeout(refresh, 500);
  });
}

function saveWebViewSettings() {
  var resolution = document.getElementById("webViewResolution"),
    pageScale = document.getElementById("webViewPageScale"),
    userAgent = document.getElementById("webViewUserAgent"),
    images = document.getElementById("webViewLoadImages"),
    autoPlaySniffed = document.getElementById("webViewAutoPlaySniffed");
  api(
    "/api/settings",
    {
      webViewResolution: resolution.value,
      webViewPageScale: Number(pageScale.value),
      webViewUserAgent: userAgent.value,
      webViewLoadImages: images.checked,
      webViewAutoPlaySniffed: autoPlaySniffed.checked
    },
    function (error) {
      if (error) {
        toast(error.message, true);
        refresh();
        return;
      }
      toast("浏览器配置已保存");
    }
  );
}
function renderPageState() {
  var s = state.settings;
  document.getElementById("webViewResolution").value = s.webViewResolution || "720p";
  document.getElementById("webViewPageScale").value = String(s.webViewPageScale || 1);
  document.getElementById("webViewUserAgent").value = s.webViewUserAgent || "windows";
  document.getElementById("webViewLoadImages").checked = s.webViewLoadImages !== false;
  document.getElementById("webViewAutoPlaySniffed").checked = s.webViewAutoPlaySniffed === true;
  document.getElementById("webCacheSize").textContent =
    "当前占用：" + formatBytes(Number(s.webViewCacheBytes) || 0);
}
startPage();
