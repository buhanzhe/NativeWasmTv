function saveColorDebugInfo() {
  var input = document.getElementById("showColorDebugInfo");
  api("/api/settings", {showColorDebugInfo: input.checked}, function(error) {
    if(error){input.checked=!input.checked;toast(error.message,true);return;}
    toast("色彩和解码器信息显示已保存");
  });
}
function saveLoadTiming() {
  var input = document.getElementById("showLoadTiming");
  api("/api/settings", {showLoadTiming: input.checked}, function(error) {
    if(error){input.checked=!input.checked;toast(error.message,true);return;}
    toast("加载耗时显示已保存");
  });
}
function saveAutoSwitchSource() {
  var input = document.getElementById("autoSwitchSourceSeconds"),
    seconds = Number(input.value);
  api("/api/settings", { autoSwitchSourceSeconds: seconds }, function (error) {
    if (error) {
      refresh();
      toast(error.message, true);
      return;
    }
    if (state && state.settings) {
      state.settings.autoSwitchSourceSeconds = seconds;
      state.settings.autoSwitchSource = seconds > 0;
    }
    toast(seconds ? "未播放 " + seconds + " 秒后自动切换备用线路" : "已关闭超时自动切换线路");
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

function savePlaybackPosition() {
  var input = document.getElementById("showPlaybackPosition");
  api("/api/settings", { showPlaybackPosition: input.checked }, function (error) {
    if (error) { input.checked = !input.checked; toast(error.message, true); return; }
    toast(input.checked ? "已显示播放进度" : "已隐藏播放进度");
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

var uiScaleEditing = false;
function uiScalePreview() {
  uiScaleEditing = true;
  document.getElementById("uiScalePercentValue").textContent = document.getElementById("uiScalePercent").value + "%";
}
function uiScaleModeChanged() {
  var custom = document.getElementById("uiScaleMode").value === "custom";
  document.getElementById("uiScaleCustomControls").style.display = custom ? "block" : "none";
  uiScalePreview();
  saveDisplay();
}
function saveDisplay() {
  var scale = document.getElementById("videoScale"),
    resolution = document.getElementById("resolutionMode"),
    uiScale = document.getElementById("uiScaleMode");
  var custom = uiScale.value === "custom";
  var percent = Number(document.getElementById("uiScalePercent").value);
  if (custom && (!isFinite(percent) || Math.floor(percent) !== percent || percent < 10 || percent > 300)) {
    toast("界面大小请输入 10–300 的整数百分比", true);
    uiScale.focus();
    return;
  }
  api(
    "/api/settings",
    {
      videoScaleMode: scale.value,
      resolutionMode: resolution.value,
      uiScaleMode: custom ? String(percent) : uiScale.value
    },
    function (error) {
      uiScaleEditing = false;
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
  var checked = ["showColorDebugInfo", "showLoadTiming", "showDebugInfo", "showNetworkSpeed", "showDateTime", "showPlaybackPosition"];
  for (var i = 0; i < checked.length; i++)
    document.getElementById(checked[i]).checked = s[checked[i]] === true;
  var scaleSelect = document.getElementById("videoScale");
  var blurOption = scaleSelect.querySelector('option[value="blur"]');
  if (s.videoBlurSupported !== true && blurOption) scaleSelect.removeChild(blurOption);
  if (s.videoBlurSupported === true && !blurOption) {
    blurOption = document.createElement("option");
    blurOption.value = "blur"; blurOption.textContent = "背景模糊";
    scaleSelect.appendChild(blurOption);
  }
  scaleSelect.value = s.videoScaleMode === "blur" && s.videoBlurSupported !== true ? "fit" : (s.videoScaleMode || "fit");
  document.getElementById("autoSwitchSourceSeconds").value = String(
    s.autoSwitchSourceSeconds == null ? (s.autoSwitchSource === true ? 5 : 0) : s.autoSwitchSourceSeconds);
  renderSiteQualities();
  var presets = { small: 80, slightly_small: 90, standard: 100, large: 125, extra_large: 150, extra_extra_large: 200 };
  var uiScaleInput = document.getElementById("uiScaleMode");
  var slider = document.getElementById("uiScalePercent");
  if (!uiScaleEditing && document.activeElement !== slider && document.activeElement !== uiScaleInput) {
    uiScaleInput.value = presets[s.uiScaleMode] ? s.uiScaleMode : Number(s.uiScaleMode) ? "custom" : "standard";
    slider.value = String(presets[s.uiScaleMode] || Number(s.uiScaleMode) || 100);
    document.getElementById("uiScalePercentValue").textContent = slider.value + "%";
    document.getElementById("uiScaleCustomControls").style.display = uiScaleInput.value === "custom" ? "block" : "none";
  }
  document.getElementById("uiScaleHint").textContent = "随屏幕适配，可调整缩放";
  document.getElementById("clockLocation").value = s.clockLocation || "right";
  document.getElementById("dateTimeFormat").value = s.dateTimeFormat || "date_time_week";
  var liveMode = s.liveDelayMode || "stable";
  document.getElementById("liveDelay").value = /^(auto|stable|balanced|low)$/.test(liveMode) ? liveMode : "stable";
}
function renderSiteQualities(){var select=document.getElementById('resolutionMode'),modes=state.settings.siteQualities||['high','medium','low'],names={high:'最高',medium:'适中',low:'最低'};select.innerHTML='';for(var i=0;i<modes.length&&i<3;i++){var option=document.createElement('option');option.value=modes[i];option.textContent=names[modes[i]]||modes[i];select.appendChild(option)}var preferred=state.settings.resolutionMode||'high';select.value=modes.indexOf(preferred)>=0?preferred:modes[0];select.disabled=modes.length===1}
startPage();
