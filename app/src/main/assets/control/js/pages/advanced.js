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
function saveDecoder() {
  var decoder = document.getElementById("decoder"),
    hardware = document.getElementById("hardwareDecoder"),
    surface = document.getElementById("surfaceMode"),
    transport = document.getElementById("rtspTransport"),
    sps = document.getElementById("spsCompatibility");
  toast("正在切换解码配置…");
  api(
    "/api/settings",
    {
      decodeMode: decoder.value,
      hardwareDecoder: hardware.value,
      surfaceMode: surface.value,
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
  var proxy = document.getElementById("githubProxyUrl"), mode = document.getElementById("githubConnectionMode");
  if (!mode._dirty) mode.value = s.githubProxyEnabled === false ? "direct"
    : githubProxyChoice(s.githubProxyBaseUrl || "https://gh-proxy.org/");
  if (document.activeElement !== proxy && !proxy._dirty) {
    proxy.value = s.githubProxyBaseUrl || "https://gh-proxy.org/";
  }
  renderGithubProxyFields();
  document.getElementById("dnsMode").value = s.dnsMode || "ali";
  document.getElementById("reverse").checked = s.reverseKeys === true;
  document.getElementById("autoStart").checked = s.autoStart === true;
  document.getElementById("decoder").value = s.decodeMode || "auto";
  document.getElementById("surfaceMode").value = s.surfaceMode || "normal";
  document.getElementById("rtspTransport").value = s.rtspTransport || "tcp";
  document.getElementById("spsCompatibility").value = String(s.h264SpsCompatibility !== false);
  renderHardwareDecoders();
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
