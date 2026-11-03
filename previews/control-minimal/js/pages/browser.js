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

function updateLiteThreadSetting() {
  var lite = document.getElementById("webSourceEngine").value === "lite";
  var row = document.getElementById("liteNetworkThreadRow");
  var selector = document.getElementById("liteNetworkThreadMultiplier");
  row.hidden = !lite;
  row.style.display = lite ? "" : "none";
  selector.disabled = !lite;
  var counts = state && state.settings && state.settings.liteNetworkThreadCounts;
  var count = counts && counts[selector.value];
  document.getElementById("liteNetworkThreadSummary").textContent =
    (count ? "实际 " + count + " 个网络线程" + (count === 32 ? "（已达上限）" : "") + "；" : "") +
    "下次打开网页生效，仅影响网络请求";
}

function saveWebViewSettings(includeScript) {
  if(!state||!state.settings){toast("正在读取配置，请稍候");return;}
  updateLiteThreadSetting();
  var resolution = document.getElementById("webViewResolution"),
    pageScale = document.getElementById("webViewPageScale"),
    userAgent = document.getElementById("webViewUserAgent"),
    browserVersion = document.getElementById("webViewBrowserVersion"),
    images = document.getElementById("webViewLoadImages"),
    autoPlaySniffed = document.getElementById("webViewAutoPlaySniffed"),
    autoPlay = document.getElementById("webViewAutoPlay"),
    adBlock = document.getElementById("webViewAdBlock"),
    webRtc = document.getElementById("webViewWebRtcEnabled");
  var payload = {
    webSourceEngine: document.getElementById("webSourceEngine").value,
    liteNetworkThreadMultiplier: Number(document.getElementById("liteNetworkThreadMultiplier").value) || 0,
    webViewMinimalMode: document.getElementById("webViewMinimalMode").checked,
    webViewPageCoverStyle: document.getElementById("webViewPageCoverStyle").value,
    webViewPageCover: document.getElementById("webViewPageCover").checked,
    webViewResolution: resolution.value,
    webViewPageScale: Number(pageScale.value),
    webViewUserAgent: userAgent.value,
    webViewBrowserVersion: browserVersion.value,
    webViewLoadImages: images.checked,
    webViewAutoPlaySniffed: autoPlaySniffed.checked,
    webViewAutoPlay: autoPlay.checked,
    webViewAdBlock: adBlock.checked,
    webViewWebRtcEnabled: webRtc.checked
  };
  api(
    "/api/settings",
    payload,
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
  document.getElementById("webSourceEngine").value = s.webSourceEngine || s.defaultWebSourceEngine || "system";
  document.getElementById("webSourceEngine").disabled = false;
  document.getElementById("webSourceEngine").querySelector('[value="lite"]').disabled = s.liteBrowserSupported === false;
  document.getElementById("liteNetworkThreadMultiplier").value = String(s.liteNetworkThreadMultiplier || 0);
  updateLiteThreadSetting();
  document.getElementById("webViewMinimalMode").checked = s.webViewMinimalMode === true;
  document.getElementById("webViewPageCover").checked = s.webViewPageCover === true;
  document.getElementById("webViewPageCoverStyle").value = s.webViewPageCoverStyle || "status";
  document.getElementById("webViewResolution").value = s.webViewResolution || "720p";
  document.getElementById("webViewPageScale").value = String(s.webViewPageScale || 1);
  document.getElementById("webViewUserAgent").value = s.webViewUserAgent || "windows";
  document.getElementById("webViewBrowserVersion").value =
    s.webViewBrowserVersion || "native";
  document.getElementById("webViewLoadImages").checked = s.webViewLoadImages !== false;
  document.getElementById("webViewAutoPlaySniffed").checked = s.webViewAutoPlaySniffed === true;
  document.getElementById("webViewAutoPlay").checked = s.webViewAutoPlay !== false;
  document.getElementById("webViewAdBlock").checked = s.webViewAdBlock === true;
  document.getElementById("webViewWebRtcEnabled").checked = s.webViewWebRtcEnabled === true;
  document.getElementById("webCacheSize").textContent =
    "系统 WebView · 当前占用：" + formatBytes(Number(s.webViewCacheBytes) || 0);
}
startPage();
