var videoRenderModeSaving = false, videoRenderModeStateAfter = -1;
function renderVideoRenderMode() {
  var input = document.getElementById("videoRenderMode");
  if (!input) return;
  if (!videoRenderModeSaving && stateRequestSequence > videoRenderModeStateAfter) input.value = state.settings.videoRenderMode || "surface";
  input.disabled = videoRenderModeSaving;
}
function saveVideoRenderMode() {
  if (videoRenderModeSaving || !state || !state.settings) return;
  var value = document.getElementById("videoRenderMode").value;
  videoRenderModeSaving = true;
  renderVideoRenderMode();
  api("/api/settings", {videoRenderMode: value}, function(error) {
    videoRenderModeStateAfter = stateRequestSequence;
    videoRenderModeSaving = false;
    if (!error) state.settings.videoRenderMode = value;
    document.getElementById("videoRenderMode").value = state.settings.videoRenderMode || "surface";
    renderVideoRenderMode();
    toast(error ? error.message : "渲染方式已保存，正在重新加载", !!error);
    refresh();
  });
}
var continuousTsSaving=false,continuousTsStateAfter=-1;
function saveContinuousTs(){
  if(continuousTsSaving||!state||!state.settings||typeof state.settings.continuousTsEnabled!=="boolean")return;
  var input=document.getElementById("continuousTsEnabled"),value=input.value==="true";
  continuousTsSaving=true;input.disabled=true;
  api("/api/settings",{continuousTsEnabled:value},function(error){
    continuousTsStateAfter=stateRequestSequence;continuousTsSaving=false;input.disabled=false;input.value=String(error?!value:value);
    if(!error&&state&&state.settings)state.settings.continuousTsEnabled=value;
    renderPageState();toast(error?error.message:"连续 TS 已"+(value?"开启":"关闭")+"，正在重新播放",!!error);refresh();
  });
}
var fullscreenSaving=false,fullscreenStateAfter=-1;
function saveFullscreenStartup(){
  if(fullscreenSaving||!state||!state.settings||typeof state.settings.fullscreenStartup!=="boolean")return;
  var input=document.getElementById("fullscreenStartup"),value=input.checked;
  fullscreenSaving=true;input.disabled=true;
  api("/api/settings",{fullscreenStartup:value},function(error){
    fullscreenStateAfter=stateRequestSequence;fullscreenSaving=false;input.disabled=false;
    input.checked=error?!value:value;
    state.settings.fullscreenStartup=input.checked;
    toast(error?error.message:value?"已开启全屏启动":"已关闭全屏启动，状态栏保持隐藏",!!error);refresh();
  });
}
function saveReverse() {
  var reverse = document.getElementById("reverse");
  api("/api/settings", { reverseKeys: reverse.checked }, function (error) {
    if (error) {
      reverse.checked = !reverse.checked;
      toast(error.message, true);
      return;
    }
    toast("设置已保存");
  });
}
function saveAutoStart() {
  var input = document.getElementById("autoStart");
  api("/api/settings", { autoStart: input.checked }, function (error) {
    if (error) {
      input.checked = !input.checked;
      toast(error.message, true);
      return;
    }
    toast(input.checked ? "已开启开机自启动" : "已关闭开机自启动");
  });
}
var backgroundSaving=false;
function saveBackgroundPlayback(){
  if(backgroundSaving||!state||!state.settings)return;
  var input=document.getElementById("backgroundPlayback"),value=input.checked;
  backgroundSaving=true;input.disabled=true;
  api("/api/settings",{backgroundPlayback:value},function(error){
    backgroundSaving=false;input.disabled=false;
    if(error){input.checked=!value;toast(error.message,true);refresh();return;}
    state.settings.backgroundPlayback=value;
    toast(value?"已开启后台播放":"已关闭后台播放");refresh();
  });
}
var playerEngineSaving = false;
function renderPlayerEngine() {
  renderVideoRenderMode();
  var select = document.getElementById("playerEngine");
  if (!playerEngineSaving) select.value = state.settings.playerEngine || "ijk";
  select.disabled = playerEngineSaving;
  var system = select.value === "system";
  var ids = ["decoder", "hardwareDecoder", "hdrMode", "rtspTransport"];
  for (var i = 0; i < ids.length; i++) document.getElementById(ids[i]).disabled = system || playerEngineSaving;
  document.getElementById("systemPlayerHint").style.display = system ? "" : "none";
}
function savePlayerEngine() {
  if (playerEngineSaving || !state || !state.settings) return;
  var select = document.getElementById("playerEngine"), value = select.value;
  playerEngineSaving = true;
  renderPlayerEngine();
  api("/api/settings", { playerEngine: value }, function (error) {
    playerEngineSaving = false;
    if (!error) state.settings.playerEngine = value;
    renderPlayerEngine();
    toast(error ? error.message : "播放器已保存，正在重新播放", !!error);
    refresh();
  });
}
function saveDecoder() {
  var decoder = document.getElementById("decoder"),
    hardware = document.getElementById("hardwareDecoder"),
    transport = document.getElementById("rtspTransport"),
    sps = document.getElementById("spsCompatibility");
  toast("正在切换解码配置…");
  api(
    "/api/settings",
    {
      decodeMode: decoder.value,
      hardwareDecoder: hardware.value,
      rtspTransport: transport.value,
      h264SpsCompatibility: sps.value === "true"
    },
    function (error) {
      if (error) {
        toast(error.message, true);
        refresh();
        return;
      }
      toast("解码配置已保存，正在重新播放");
    }
  );
}
function saveHdrMode() {
  var select = document.getElementById("hdrMode");
  select.disabled = true;
  api("/api/settings", { hdrMode: select.value }, function (error) {
    select.disabled = false;
    if (error) { toast(error.message, true); refresh(); return; }
    toast("HDR 模式已保存，正在重新播放");
    refresh();
  });
}
function renderHardwareDecoders() {
  var select = document.getElementById("hardwareDecoder"),
    selected = state.settings.hardwareDecoder || "auto",
    items = state.settings.hardwareDecoders || [];
  select.innerHTML = "";
  var automatic = document.createElement("option");
  automatic.value = "auto";
  automatic.textContent = "自动选择";
  select.appendChild(automatic);
  for (var i = 0; i < items.length; i++) {
    var option = document.createElement("option");
    option.value = items[i];
    option.textContent = items[i];
    select.appendChild(option);
  }
  select.value = selected;
  if (select.value !== selected) {
    var saved = document.createElement("option");
    saved.value = selected;
    saved.textContent = selected + "（当前）";
    select.appendChild(saved);
    select.value = selected;
  }
}
function renderPageState() {
  var s = state.settings;
  if(!fullscreenSaving){
    var fullscreen=document.getElementById("fullscreenStartup");
    fullscreen.disabled=typeof s.fullscreenStartup!=="boolean";
    if(!fullscreen.disabled&&stateRequestSequence>fullscreenStateAfter)fullscreen.checked=s.fullscreenStartup;
  }
  var proxy = document.getElementById("githubProxyUrl"), mode = document.getElementById("githubConnectionMode");
  if (!mode._dirty) mode.value = s.githubProxyEnabled === false ? "direct"
    : githubProxyChoice(s.githubProxyBaseUrl || "https://gh-proxy.org/");
  if (document.activeElement !== proxy && !proxy._dirty) {
    proxy.value = s.githubProxyBaseUrl || "https://gh-proxy.org/";
  }
  renderGithubProxyFields();
  document.getElementById("dnsMode").value = s.dnsMode || "system";
  document.getElementById("reverse").checked = s.reverseKeys === true;
  document.getElementById("autoStart").checked = s.autoStart === true;
  if(!backgroundSaving){document.getElementById("backgroundPlayback").checked=s.backgroundPlayback!==false;document.getElementById("backgroundPlayback").disabled=false;}
  document.getElementById("decoder").value = s.decodeMode || "auto";
  document.getElementById("hdrMode").value = s.hdrMode === "hardware_sdr" ? "mapping" : s.hdrMode === "passthrough" ? "hardware" : s.hdrMode || "hardware";
  document.getElementById("rtspTransport").value = s.rtspTransport || "tcp";
  if(!continuousTsSaving){
    var continuous=document.getElementById("continuousTsEnabled");continuous.disabled=typeof s.continuousTsEnabled!=="boolean";
    if(!continuous.disabled&&stateRequestSequence>continuousTsStateAfter)continuous.value=String(s.continuousTsEnabled);
  }
  document.getElementById("spsCompatibility").value = String(s.h264SpsCompatibility !== false);
  renderHardwareDecoders();
  renderPlayerEngine();
}
function saveDns(){var dns=document.getElementById('dnsMode');toast('正在切换 DNS…');api('/api/settings',{dnsMode:dns.value},function(error){if(error){toast(error.message,true);refresh();return}toast('DNS 配置已保存')})}
var githubProxySaving = false;
function githubProxyChoice(url) {
  var value = String(url).replace(/\/+$/, "") + "/";
  return /^(https:\/\/)(gh-proxy\.org|gh-proxy\.com|ghfile\.geekertao\.top|github-proxy\.memory-echoes\.cn|github\.tbap\.top)\/$/.test(value)
    ? value : "custom";
}
function renderGithubProxyFields() {
  var mode = document.getElementById("githubConnectionMode"), custom = mode.value === "custom";
  document.getElementById("githubProxyCustom").style.display = custom ? "" : "none";
  document.getElementById("githubProxyUrl").disabled = !custom || githubProxySaving;
  document.getElementById("githubProxySave").disabled = githubProxySaving;
  mode.disabled = githubProxySaving;
}
function changeGithubConnectionMode() {
  var mode = document.getElementById("githubConnectionMode");
  mode._dirty = true;
  renderGithubProxyFields();
  if (mode.value !== "custom") saveGithubProxy();
}
function saveGithubProxy() {
  if (githubProxySaving) return;
  var input = document.getElementById("githubProxyUrl");
  var value = input.value.replace(/^\s+|\s+$/g, ""),
    mode = document.getElementById("githubConnectionMode"), enabled = mode.value !== "direct",
    settings = { githubProxyEnabled: enabled };
  if (enabled) settings.githubProxyBaseUrl = mode.value === "custom" ? value : mode.value;
  githubProxySaving = true;
  renderGithubProxyFields();
  api("/api/settings", settings, function (error) {
    githubProxySaving = false;
    renderGithubProxyFields();
    if (error) {
      if (mode.value !== "custom") { mode._dirty = false; renderPageState(); }
      toast(error.message, true); return;
    }
    input._dirty = false;
    mode._dirty = false;
    state.settings.githubProxyEnabled = enabled;
    if (enabled) state.settings.githubProxyBaseUrl = settings.githubProxyBaseUrl || "https://gh-proxy.org/";
    renderPageState();
    toast(enabled ? "GitHub 加速已保存，新请求生效" : "已切换为 GitHub 直连，新请求生效");
    refresh();
  });
}
document.getElementById("githubProxyUrl").oninput = function () { this._dirty = true; };
startPage();
