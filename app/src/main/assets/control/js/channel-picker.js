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
    isOpen = false,
    openTimer = null,
    closeTimer = null;

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
    clearTimeout(channelTimer);
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
      var current = pickerState.current || {};
      buildChannels(selectedGroup === Number(current.groupIndex) ? current.channelIndex : 0);
    });
    buildChannels(preferredChannel);
  }

  function selectedFromScroll(wheel, length) {
    return clamp(Math.round(wheel.scrollTop / ROW_HEIGHT), length);
  }

  function bindWheelScrolling() {
    var groupWheel = byId("channelGroupWheel"), channelWheel = byId("channelItemWheel");
    groupWheel.onscroll = function () {
      if (!isOpen) return;
      tickWheelIfChanged(groupWheel,
        selectedFromScroll(groupWheel, groups().length));
      clearTimeout(groupTimer);
      groupTimer = setTimeout(function () {
        var index = selectedFromScroll(groupWheel, groups().length);
        settleWheel(groupWheel, index);
        if (selectedGroup !== index) {
          selectedGroup = index;
          var current = pickerState.current || {};
          buildChannels(selectedGroup === Number(current.groupIndex) ? current.channelIndex : 0);
        }
      }, 90);
    };
    channelWheel.onscroll = function () {
      if (!isOpen) return;
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
    if (isOpen) return;
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
    var backdrop = byId("channelPickerBackdrop");
    clearTimeout(closeTimer);
    clearTimeout(groupTimer);
    clearTimeout(channelTimer);
    backdrop.hidden = false;
    isOpen = true;
    // Hidden wheels have no scroll range: make them measurable before positioning.
    var current = pickerState.current || {};
    buildGroups(current.groupIndex, current.channelIndex);
    openTimer = setTimeout(function () {
      if (isOpen) backdrop.className = "channel-picker-backdrop open";
    }, 0);
  }

  function close() {
    var backdrop = byId("channelPickerBackdrop");
    if (!backdrop || !isOpen) return;
    isOpen = false;
    clearTimeout(openTimer);
    clearTimeout(groupTimer);
    clearTimeout(channelTimer);
    backdrop.className = "channel-picker-backdrop";
    closeTimer = setTimeout(function () { if (!isOpen) backdrop.hidden = true; }, 220);
  }

  function confirm() {
    if (!isOpen) return;
    clearTimeout(groupTimer);
    clearTimeout(channelTimer);
    var group = selectedFromScroll(byId("channelGroupWheel"), groups().length);
    if (group !== selectedGroup) {
      selectedGroup = group;
      var current = pickerState.current || {};
      buildChannels(group === Number(current.groupIndex) ? current.channelIndex : 0);
    }
    selectedChannel = selectedFromScroll(byId("channelItemWheel"), channels().length);
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
    // Keep the main page's width, scroll position and flex layout unchanged.
    // Older WebViews lack overscroll-behavior, so also stop chaining at wheel edges.
    function wheelFor(target) {
      while (target && target !== backdrop) {
        if (target.id === "channelGroupWheel" || target.id === "channelItemWheel") return target;
        target = target.parentNode;
      }
      return null;
    }
    function blockScroll(event, delta) {
      var wheel = wheelFor(event.target);
      if (!wheel || (delta < 0 && wheel.scrollTop <= 0)
          || (delta > 0 && wheel.scrollTop + wheel.clientHeight >= wheel.scrollHeight - 1)) {
        event.preventDefault();
      }
    }
    var touchY = 0;
    backdrop.addEventListener("touchstart", function (event) {
      if (event.touches.length) touchY = event.touches[0].clientY;
    }, false);
    backdrop.addEventListener("touchmove", function (event) {
      if (!event.touches.length) return;
      var y = event.touches[0].clientY;
      blockScroll(event, touchY - y);
      touchY = y;
    }, false);
    backdrop.addEventListener("wheel", function (event) { blockScroll(event, event.deltaY); }, false);
    document.addEventListener("keydown", function (event) {
      if (!isOpen) return;
      if (event.key === "Escape" || event.keyCode === 27) { close(); event.preventDefault(); }
      else if (!wheelFor(event.target) && (event.keyCode === 32 || (event.keyCode >= 33 && event.keyCode <= 40))) event.preventDefault();
    }, false);
    window.addEventListener("pagehide", close, false);
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
