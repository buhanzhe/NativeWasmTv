var displayedManagementUrl = "";
function localManagementUrl() {
  return displayedManagementUrl || location.protocol + "//" + location.host + "/";
}
function setupManagementUrl(value) {
  if (value) displayedManagementUrl = value;
  else if (!displayedManagementUrl)
    displayedManagementUrl = location.protocol + "//" + location.host + "/";
  value = localManagementUrl();
  var text = document.getElementById("managementUrlText"),
    button = document.getElementById("managementUrl");
  text.textContent = value;
  button.title = "点击复制 " + value;
}
function fallbackCopyManagementUrl(value) {
  var input = document.createElement("textarea");
  input.value = value;
  input.setAttribute("readonly", "readonly");
  input.style.position = "fixed";
  input.style.left = "-9999px";
  document.body.appendChild(input);
  input.select();
  var copied = false;
  try {
    copied = document.execCommand("copy");
  } catch (error) {}
  document.body.removeChild(input);
  toast(copied ? "管理网址已复制" : "无法自动复制，请长按网址复制", !copied);
}
function copyManagementUrl() {
  var value = localManagementUrl();
  if (navigator.clipboard && window.isSecureContext) {
    navigator.clipboard.writeText(value).then(
      function () {
        toast("管理网址已复制");
      },
      function () {
        fallbackCopyManagementUrl(value);
      }
    );
    return;
  }
  fallbackCopyManagementUrl(value);
}
function renderCurrentChannel() {
  setupManagementUrl(state.managementUrl);
  var summary = document.getElementById("currentChannelSummary"),
    group = state.current && state.current.group ? state.current.group : "",
    channel = state.current && state.current.name ? state.current.name : "";
  summary.textContent = group && channel ? group + " · " + channel : channel || "暂无可用频道";
  NtvChannelPicker.update(state);
}
function renderPageState() {
  var repo = document.getElementById("repo");
  repo.href = state.githubUrl;
  repo.textContent = "buhanzhe/NativeWasmTv";
  document.getElementById("castSettingsEntry").style.display = state.isTelevision ? "none" : "";
  renderCurrentChannel();
}
setupManagementUrl();
startPage();
