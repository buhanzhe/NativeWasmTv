var legacySelectTarget = null,
  legacySelectScrollY = 0;
function selectedOptionText(select) {
  var index = select.selectedIndex;
  return index >= 0 && select.options[index] ? select.options[index].textContent : "";
}

function syncLegacySelectButtons() {
  var ids = ["clockLocation", "dateTimeFormat"];
  for (var i = 0; i < ids.length; i++) {
    var select = document.getElementById(ids[i]),
      button = select && select._legacyButton;
    if (button) button.textContent = selectedOptionText(select);
  }
}

function lockLegacySelectScroll() {
  var body = document.body;
  body.style.position = "fixed";
  body.style.top = -legacySelectScrollY + "px";
  body.style.left = "0";
  body.style.right = "0";
  body.style.width = "100%";
}

function restoreLegacySelectScroll() {
  var body = document.body,
    y = legacySelectScrollY;
  body.style.position = "";
  body.style.top = "";
  body.style.left = "";
  body.style.right = "";
  body.style.width = "";
  setTimeout(function () {
    window.scrollTo(0, y);
  }, 0);
  setTimeout(function () {
    window.scrollTo(0, y);
  }, 80);
}

function closeLegacySelectPicker(event) {
  var picker = document.getElementById("legacySelectPicker");
  if (event && event.target !== picker) return;
  picker.className = "source-picker";
  legacySelectTarget = null;
  restoreLegacySelectScroll();
}

function chooseLegacySelectOption(value) {
  var select = legacySelectTarget;
  if (!select) return;
  select.value = value;
  syncLegacySelectButtons();
  closeLegacySelectPicker();
  saveDateTime();
}

function openLegacySelectPicker(select) {
  if (!select) return;
  legacySelectTarget = select;
  legacySelectScrollY =
    window.pageYOffset || document.documentElement.scrollTop || document.body.scrollTop || 0;
  var label = document.querySelector('label[for="' + select.id + '"]'),
    title = document.getElementById("legacySelectTitle"),
    choices = document.getElementById("legacySelectChoices");
  title.textContent = label ? label.textContent : select.getAttribute("aria-label") || "选择设置";
  choices.innerHTML = "";
  for (var i = 0; i < select.options.length; i++) {
    var option = select.options[i],
      button = document.createElement("button"),
      check = document.createElement("i");
    button.type = "button";
    button.className = "legacy-select-choice" + (option.value === select.value ? " selected" : "");
    button.appendChild(document.createTextNode(option.textContent));
    check.textContent = "✓";
    button.appendChild(check);
    button._value = option.value;
    button.onclick = function () {
      chooseLegacySelectOption(this._value);
    };
    choices.appendChild(button);
  }
  lockLegacySelectScroll();
  document.getElementById("legacySelectPicker").className = "source-picker show";
}

function setupLegacyDateTimeSelects() {
  var ids = ["clockLocation", "dateTimeFormat"];
  for (var i = 0; i < ids.length; i++) {
    var select = document.getElementById(ids[i]);
    if (!select || select._legacyButton) continue;
    var button = document.createElement("button");
    button.type = "button";
    button.id = select.id + "Button";
    button.className = "legacy-select-trigger";
    button.setAttribute("aria-label", select.getAttribute("aria-label") || "选择设置");
    button._select = select;
    button.onclick = function () {
      openLegacySelectPicker(this._select);
    };
    select.style.display = "none";
    select.parentNode.insertBefore(button, select.nextSibling);
    select._legacyButton = button;
  }
  syncLegacySelectButtons();
}
function saveAutoSwitchSource() {
  var input = document.getElementById("autoSwitchSource");
  api("/api/settings", { autoSwitchSource: input.checked }, function (error) {
    if (error) {
      input.checked = !input.checked;
      toast(error.message, true);
      return;
    }
    toast(input.checked ? "已开启自动切换备用线路" : "已关闭自动切换备用线路");
  });
}
function saveDebugInfo() {
  var input = document.getElementById("showDebugInfo");
  api("/api/settings", { showDebugInfo: input.checked }, function (error) {
    if (error) {
      input.checked = !input.checked;
      toast(error.message, true);
      return;
    }
    toast(input.checked ? "已显示调试信息" : "已隐藏调试信息");
  });
}

function saveNetworkSpeed() {
  var input = document.getElementById("showNetworkSpeed");
  api("/api/settings", { showNetworkSpeed: input.checked }, function (error) {
    if (error) {
      input.checked = !input.checked;
      toast(error.message, true);
      return;
    }
    toast(input.checked ? "已在右下角显示网速" : "已隐藏网速");
  });
}

function saveDateTime() {
  var enabled = document.getElementById("showDateTime"),
    clock = document.getElementById("clockLocation"),
    format = document.getElementById("dateTimeFormat");
  api(
    "/api/settings",
    {
      showDateTime: enabled.checked,
      clockLocation: clock.value,
      dateTimeFormat: format.value
    },
    function (error) {
      if (error) {
        toast(error.message, true);
        refresh();
        return;
      }
      toast(enabled.checked ? "日期时间显示已保存" : "已隐藏日期时间");
    }
  );
}

function saveDisplay() {
  var scale = document.getElementById("videoScale"),
    resolution = document.getElementById("resolutionMode"),
    uiScale = document.getElementById("uiScaleMode");
  api(
    "/api/settings",
    {
      videoScaleMode: scale.value,
      resolutionMode: resolution.value,
      uiScaleMode: uiScale.value
    },
    function (error) {
      if (error) {
        toast(error.message, true);
        refresh();
        return;
      }
      toast("画面与界面设置已保存");
      setTimeout(refresh, 250);
    }
  );
}

function saveLiveDelay() {
  var delay = document.getElementById("liveDelay");
  toast("正在应用直播延迟…");
  api("/api/settings", { liveDelayMode: delay.value }, function (error) {
    if (error) {
      toast(error.message, true);
      refresh();
      return;
    }
    toast("直播延迟已保存，正在重新播放");
  });
}
function renderPageState() {
  var s = state.settings;
  var checked = ["showDebugInfo", "showNetworkSpeed", "showDateTime", "autoSwitchSource"];
  for (var i = 0; i < checked.length; i++)
    document.getElementById(checked[i]).checked = s[checked[i]] === true;
  document.getElementById("videoScale").value = s.videoScaleMode || "fit";
  renderSiteQualities();
  document.getElementById("uiScaleMode").value = s.uiScaleMode || "auto";
  var display = state.display || {},
    size = Number(display.diagonalInches) || 0,
    parts = ["当前 " + Math.round((Number(s.uiScaleFactor) || 1) * 100) + "%"];
  if (size > 0) parts.push("识别约 " + size.toFixed(1) + " 英寸");
  if (display.densityDpi) parts.push("DPI " + display.densityDpi);
  document.getElementById("uiScaleHint").textContent = parts.join(" · ");
  document.getElementById("clockLocation").value = s.clockLocation || "right";
  document.getElementById("dateTimeFormat").value = s.dateTimeFormat || "date_time_week";
  document.getElementById("liveDelay").value = s.liveDelayMode || "stable";
  syncLegacySelectButtons();
}
setupLegacyDateTimeSelects();
function renderSiteQualities(){var select=document.getElementById('resolutionMode'),modes=state.settings.siteQualities||['high','medium','low'],names={high:'最高',medium:'适中',low:'最低'};select.innerHTML='';for(var i=0;i<modes.length&&i<3;i++){var option=document.createElement('option');option.value=modes[i];option.textContent=names[modes[i]]||modes[i];select.appendChild(option)}var preferred=state.settings.resolutionMode||'high';select.value=modes.indexOf(preferred)>=0?preferred:modes[0];select.disabled=modes.length===1}
startPage();
