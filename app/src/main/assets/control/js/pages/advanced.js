function loadTvUi() {
  api("/api/tv-ui",null,function(error,result){
    document.getElementById("tvUiStatus").textContent=error?error.message:(result.error || "离线切换，播放不中断");
    if(!error)document.getElementById("tvUiStyle").value=result.selected;
  });
}
function saveTvUiStyle() {
  var select=document.getElementById("tvUiStyle");select.disabled=true;
  api("/api/tv-ui",{id:select.value},function(error){
    select.disabled=false;if(error)toast(error.message,true);else toast("电视界面已切换");loadTvUi();
  },30000);
}
var radioUiSaving=false, radioUiSequence=0, radioUiTimer=null, radioUiOptionsSignature="";
function loadRadioUi() {
  if(radioUiSaving)return;
  var sequence=++radioUiSequence;clearTimeout(radioUiTimer);
  api("/api/radio-ui",null,function(error,result){
    if(sequence!==radioUiSequence||radioUiSaving)return;
    var status=document.getElementById("radioUiStatus"),select=document.getElementById("radioUiStyle");
    if(error){status.textContent=error.message;return;}
    var choices=result.choices||[],current=result.selected||result.style||"classic";
    var signature=JSON.stringify(choices);
    if(signature!==radioUiOptionsSignature){
      select.innerHTML="";
      for(var i=0;i<choices.length;i++){var option=document.createElement("option");option.value=choices[i].id;option.textContent=choices[i].name;select.appendChild(option);}
      radioUiOptionsSignature=signature;
    }
    select.value=current;select.disabled=false;
    status.textContent=result.error || (result.catalogRefreshing ? "正在刷新可选界面…" : result.catalogError || "已下载的界面可离线使用");
    if(result.catalogRefreshing)radioUiTimer=setTimeout(loadRadioUi,1000);
  });
}
function saveRadioUiStyle() {
  if(radioUiSaving)return;
  var select=document.getElementById("radioUiStyle");radioUiSaving=true;radioUiSequence++;clearTimeout(radioUiTimer);select.disabled=true;
  document.getElementById("radioUiStatus").textContent="正在应用界面，首次使用会下载…";
  api("/api/radio-ui",{id:select.value},function(error){
    radioUiSaving=false;select.disabled=false;
    toast(error?error.message:"电台界面已切换",!!error);loadRadioUi();
  },40000);
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
  document.getElementById("hdrMode").value = s.hdrMode === "hardware_sdr" ? "mapping" : s.hdrMode === "passthrough" ? "hardware" : s.hdrMode || "hardware";
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

loadRadioUi();
loadTvUi();
