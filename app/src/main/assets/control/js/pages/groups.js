var playlistGroups = [];
function renderPlaylistGroupSettings() {
  var list = document.getElementById("playlistGroupSettings");
  list.innerHTML = "";
  if (!playlistGroups.length) {
    var empty = document.createElement("p");
    empty.className = "hint";
    empty.textContent = "当前已启用的频道源中没有可管理的分组。";
    list.appendChild(empty);
    return;
  }
  for (var i = 0; i < playlistGroups.length; i++) {
    var group = playlistGroups[i],
      item = document.createElement("div");
    item.className = "group-item";
    var copy = document.createElement("div");
    copy.className = "group-copy";
    var name = document.createElement("b");
    name.textContent = group.name || "未命名分组";
    var count = document.createElement("span");
    count.textContent = String(group.channelCount || 0) + " 个频道";
    copy.appendChild(name);
    copy.appendChild(count);
    var enabled = document.createElement("input");
    enabled.type = "checkbox";
    enabled.className = "source-toggle";
    enabled.checked = group.enabled !== false;
    enabled.setAttribute("aria-label", "启用 " + (group.name || "频道分组"));
    enabled._group = group;
    enabled.onchange = function () {
      savePlaylistGroupState(this);
    };
    item.appendChild(copy);
    item.appendChild(enabled);
    list.appendChild(item);
  }
}

function savePlaylistGroupState(input) {
  var group = input._group,
    enabled = input.checked;
  input.disabled = true;
  api(
    "/api/settings",
    { playlistGroupStates: [{ name: group.name, enabled: enabled }] },
    function (error, result) {
      input.disabled = false;
      if (error) {
        input.checked = !enabled;
        toast(error.message, true);
        return;
      }
      group.enabled = enabled;
      toast(result.message || "频道分组设置已保存");
    }
  );
}
function renderPageState() {
  playlistGroups = state.settings.playlistGroups || [];
  renderPlaylistGroupSettings();
}
startPage();
