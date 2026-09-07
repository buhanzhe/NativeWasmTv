(function () {
  var ROW_HEIGHT = 48,
    selectedGroup = 0,
    selectedChannel = 0,
    pickerState = null,
    groupTimer = null,
    channelTimer = null,
    groupHapticIndex = -1,
    channelHapticIndex = -1,
    lastHapticAt = 0,
    previousOverflow = "";

  function byId(id) {
    return document.getElementById(id);
  }

  function clamp(value, length) {
    if (!length) return 0;
    return Math.max(0, Math.min(Number(value) || 0, length - 1));
  }

  function groups() {
    return pickerState && pickerState.groups ? pickerState.groups : [];
  }

  function channels() {
    var items = groups(), group = items[selectedGroup];
    return group && group.channels ? group.channels : [];
  }

  function markSelected(wheel, index) {
    if (!wheel) return;
    var rows = wheel.children;
    for (var i = 0; i < rows.length; i++) {
      rows[i].className = "channel-wheel-row" + (i === index ? " selected" : "");
      rows[i].setAttribute("aria-selected", i === index ? "true" : "false");
    }
  }

  function hapticTick() {
    var now = Date.now();
    if (now - lastHapticAt < 18) return;
    lastHapticAt = now;
    try {
      if (window.NtvDevice && NtvDevice.hapticTick) {
        NtvDevice.hapticTick();
        return;
      }
    } catch (ignored) {}
    if (navigator.vibrate) navigator.vibrate(7);
  }

  function rememberWheelIndex(wheel, index) {
    if (!wheel) return;
    if (wheel.id === "channelGroupWheel") groupHapticIndex = index;
    else if (wheel.id === "channelItemWheel") channelHapticIndex = index;
  }

  function tickWheelIfChanged(wheel, index) {
    var previous = wheel && wheel.id === "channelGroupWheel"
      ? groupHapticIndex : channelHapticIndex;
    if (index === previous) return;
    rememberWheelIndex(wheel, index);
    hapticTick();
  }

  function settleWheel(wheel, index) {
    if (!wheel) return;
    rememberWheelIndex(wheel, index);
    wheel.scrollTop = index * ROW_HEIGHT;
    markSelected(wheel, index);
  }

  function buildWheel(wheel, items, selected, label, onSelect) {
    wheel.innerHTML = "";
    for (var i = 0; i < items.length; i++) {
      var row = document.createElement("button");
      row.type = "button";
      row.className = "channel-wheel-row";
      row.setAttribute("role", "option");
      row.setAttribute("data-index", String(i));
      row.textContent = label(items[i]);
      row.onclick = function () {
        var index = Number(this.getAttribute("data-index"));
        tickWheelIfChanged(wheel, index);
        settleWheel(wheel, index);
        onSelect(index);
      };
      wheel.appendChild(row);
    }
    settleWheel(wheel, selected);
  }

  function buildChannels(preferred) {
    var items = channels();
    selectedChannel = clamp(preferred, items.length);
    buildWheel(byId("channelItemWheel"), items, selectedChannel, function (channel) {
      return channel.name + (channel.sourceCount > 1 ? " · " + channel.sourceCount + "线" : "");
    }, function (index) {
      selectedChannel = index;
    });
  }

  function buildGroups(preferredGroup, preferredChannel) {
    var items = groups();
    selectedGroup = clamp(preferredGroup, items.length);
    buildWheel(byId("channelGroupWheel"), items, selectedGroup, function (group) {
      return group.name + " · " + group.channels.length;
    }, function (index) {
      if (selectedGroup === index) return;
      selectedGroup = index;
      buildChannels(selectedGroup === Number(pickerState.current.groupIndex)
        ? pickerState.current.channelIndex : 0);
    });
    buildChannels(preferredChannel);
  }

  function selectedFromScroll(wheel, length) {
    return clamp(Math.round(wheel.scrollTop / ROW_HEIGHT), length);
  }

  function bindWheelScrolling() {
    var groupWheel = byId("channelGroupWheel"), channelWheel = byId("channelItemWheel");
    groupWheel.onscroll = function () {
      tickWheelIfChanged(groupWheel,
        selectedFromScroll(groupWheel, groups().length));
      clearTimeout(groupTimer);
      groupTimer = setTimeout(function () {
        var index = selectedFromScroll(groupWheel, groups().length);
        settleWheel(groupWheel, index);
        if (selectedGroup !== index) {
          selectedGroup = index;
          buildChannels(selectedGroup === Number(pickerState.current.groupIndex)
            ? pickerState.current.channelIndex : 0);
        }
      }, 90);
    };
    channelWheel.onscroll = function () {
      tickWheelIfChanged(channelWheel,
        selectedFromScroll(channelWheel, channels().length));
      clearTimeout(channelTimer);
      channelTimer = setTimeout(function () {
        selectedChannel = selectedFromScroll(channelWheel, channels().length);
        settleWheel(channelWheel, selectedChannel);
      }, 90);
    };
  }

  function update(nextState) {
    if (!nextState) return;
    if (!nextState.groups && pickerState && pickerState.groups) {
      pickerState.current = nextState.current || pickerState.current;
      return;
    }
    pickerState = nextState;
  }

  function open() {
    if (!pickerState || !groups().length) {
      api("/api/state?view=home", null, function (error, data) {
        if (error) {
          toast(error.message, true);
          return;
        }
        update(data);
        if (!groups().length) {
          toast("暂无可用频道", true);
          return;
        }
        open();
      });
      return;
    }
    var current = pickerState.current || {};
    buildGroups(current.groupIndex, current.channelIndex);
    var backdrop = byId("channelPickerBackdrop");
    backdrop.hidden = false;
    previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    setTimeout(function () { backdrop.className = "channel-picker-backdrop open"; }, 0);
  }

  function close() {
    var backdrop = byId("channelPickerBackdrop");
    if (!backdrop || backdrop.hidden) return;
    backdrop.className = "channel-picker-backdrop";
    document.body.style.overflow = previousOverflow;
    setTimeout(function () { backdrop.hidden = true; }, 220);
  }

  function confirm() {
    if (!channels().length) {
      toast("该分组没有可用频道", true);
      return;
    }
    api("/api/control", {
      action: "play",
      group: selectedGroup,
      channel: selectedChannel
    }, function (error) {
      if (error) {
        toast(error.message, true);
        return;
      }
      close();
      toast("正在切换频道");
      setTimeout(refresh, 450);
    });
  }

  function setup() {
    var backdrop = byId("channelPickerBackdrop");
    if (!backdrop) return;
    backdrop.onclick = function (event) {
      if (event.target === backdrop) close();
    };
    bindWheelScrolling();
  }

  window.NtvChannelPicker = {
    update: update,
    open: open,
    close: close,
    confirm: confirm
  };
  window.openChannelPicker = open;
  window.closeChannelPicker = close;
  window.confirmChannelPicker = confirm;
  setup();
})();
