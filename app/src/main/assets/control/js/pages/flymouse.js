function fitFlyViewport() {
  var viewport = window.visualViewport;
  var height = viewport ? viewport.height : window.innerHeight;
  if (height > 0) document.getElementById("flyMousePage").style.height = Math.floor(height) + "px";
}
function updateFlyFullscreen() {
  var active = !!(document.fullscreenElement || document.webkitFullscreenElement);
  var button = document.getElementById("flyFullscreen");
  button.textContent = active ? "⛶ 退出全屏" : "⛶ 全屏";
  button.setAttribute("aria-label", active ? "退出全屏" : "进入全屏");
  button.setAttribute("aria-pressed", String(active));
  fitFlyViewport();
}
function toggleFlyFullscreen() {
  var active = document.fullscreenElement || document.webkitFullscreenElement;
  var target = active ? document : document.documentElement;
  var method = active ? (document.exitFullscreen || document.webkitExitFullscreen)
    : (target.requestFullscreen || target.webkitRequestFullscreen);
  if (!method) { toast("当前浏览器不支持全屏，仍可直接使用飞鼠", true); return; }
  try {
    var result = method.call(target);
    if (result && result.catch) result.catch(function () { toast("无法进入或退出全屏，请重试", true); });
  } catch (error) { toast("无法进入或退出全屏，请重试", true); }
}
window.addEventListener("resize", fitFlyViewport, false);
if (window.visualViewport) window.visualViewport.addEventListener("resize", fitFlyViewport, false);
document.addEventListener("fullscreenchange", updateFlyFullscreen, false);
document.addEventListener("webkitfullscreenchange", updateFlyFullscreen, false);
fitFlyViewport();

var pointerScale = 1,
  pointerApiBase = "",
  pointerDirectRetryAt = 0,
  remoteModifiers = { shift: false, ctrl: false, alt: false };
var gyroRunning = false,
  gyroTicker = null,
  gyroResumeAfterPause = false,
  gyroSeen = false,
  gyroYawRate = 0,
  gyroPitchRate = 0,
  gyroRollRate = 0,
  filteredYawRate = 0,
  filteredPitchRate = 0,
  gyroHasYaw = false,
  genericGyroscope = null,
  lastSensorName = "",
  lastSensorAt = 0,
  lastRateAt = 0,
  lastMotionRateAt = 0,
  lastOrientationRateAt = 0,
  lastOrientationAlpha = null,
  lastOrientationBeta = null,
  lastOrientationGamma = null,
  lastOrientationAt = 0,
  orientationEventType = "",
  lastGravityPitch = null,
  lastGravityAt = 0,
  lastGyroFrameAt = 0,
  gravityFlatWeight = 1,
  lastMotionAt = 0,
  lastTranslationAt = 0,
  filteredAccelX = 0,
  filteredAccelY = 0,
  translationVelocityX = 0,
  translationVelocityY = 0;

function updatePointerRoute(data) {
  var settings = (data && data.settings) || {},
    playback = (data && data.remotePlayback) || {},
    source = String(playback.sourceUrl || ""),
    host = String(settings.remoteCatalogUrl || "").replace(/\/+$/, "");
  pointerApiBase =
    Date.now() >= pointerDirectRetryAt && host && /^rtsp:\/\/.*\/cast(?:[?#].*)?$/i.test(source)
      ? host
      : "";
}

function resetPointerTransport() {
  pointerQueue.reset();
  pointerApiBase = "";
  pointerDirectRetryAt = 0;
}
var pointerQueue = new NtvPointerQueue(function (body, done) {
  sendPointerRequest("/api/pointer", body, done);
}, function (error) { setSensorStatus(error.message, true); });
function pointerRequest(path, body, done) {
  pointerQueue.push(body, done);
}
function sendPointerRequest(path, body, done) {
  if (path === "/api/pointer" && NtvPointerQueue.sendLocal(body, done)) return;
  if (path === "/api/pointer") updatePointerRoute(state);
  var request = new XMLHttpRequest(),
    target = path === "/api/pointer" && pointerApiBase ? pointerApiBase + path : path,
    directPointer = target !== path,
    finished = false;
  function finish(error, data) {
    if (finished) return;
    finished = true;
    if (error && directPointer) {
      pointerDirectRetryAt = Date.now() + 2000;
      pointerApiBase = "";
      // The direct request may already have arrived. Switch route for the next
      // input, but don't duplicate a click/press whose response was lost.
    }
    if (!error && directPointer) pointerDirectRetryAt = 0;
    done(error, data);
  }
  try {
    request.open(body ? "POST" : "GET", target, true);
    request.timeout = path === "/api/pointer" ? 2200 : 15000;
    if (body) request.setRequestHeader("Content-Type", "application/json");
    request.onreadystatechange = function () {
      if (request.readyState !== 4) return;
      var data;
      try {
        data = JSON.parse(request.responseText);
      } catch (e) {
        finish(new Error("电视返回了无效数据"));
        return;
      }
      if (request.status < 200 || request.status >= 300 || data.ok === false) {
        finish(new Error(data.message || "请求失败"));
        return;
      }
      finish(null, data);
    };
    request.onerror = function () {
      finish(new Error("无法连接电视"));
    };
    request.ontimeout = function () {
      finish(new Error(path === "/api/pointer" ? "飞鼠连接超时，正在恢复" : "请求超时"));
    };
    request.onabort = function () {
      finish(new Error("请求已中止"));
    };
    request.send(body ? JSON.stringify(body) : null);
  } catch (error) {
    finish(error);
  }
}
function updateFlyMouseUi() {
  updatePointerRoute(state);
  document.getElementById("mouseControls").className = "mouse-controls";
}
function pointerMove(dx, dy) {
  if (dx || dy) pointerQueue.push({ action: "move", dx: dx, dy: dy });
}

function pointerAction(action, value) {
  var body = { action: action };
  if (action === "scroll") body.scrollY = value || 0;
  api("/api/pointer", body, function (error) {
    if (error) {
      toast(error.message, true);
      return;
    }
    if (action === "reset") toast("电视光标已居中");
  });
}

function pointerButtonAction(action) {
  pointerQueue.push({ action: action });
}

function switchControlMode(mode, userInitiated) {
  if (mode !== "keyboard" || userInitiated) autoInputKeyboard = false;
  var names = ["Touch", "Keyboard", "Gamepad"];
  for (var i = 0; i < names.length; i++) {
    var active = names[i].toLowerCase() === mode;
    document.getElementById("mode" + names[i]).className = active ? "active" : "";
    document.getElementById("panel" + names[i]).className =
      "control-panel" + (active ? " active" : "");
  }
  try {
    localStorage.setItem("ntvControlMode", mode);
  } catch (error) {}
  if (!gyroRunning)
    setSensorStatus(
      mode === "keyboard"
        ? "按键发送到当前网页焦点；文字框支持中文输入。"
        : mode === "gamepad"
          ? "方向键可长按；A 确认，B 返回，X 点击。"
          : "像遥控器一样握持；移动、指向或旋转手机控制光标。",
      false
    );
  setFullKeyboardVisible(false);
  var input = document.getElementById("remoteText");
  if (mode === "keyboard" && userInitiated) input.blur();
  else if (mode !== "keyboard") input.blur();
  if (mode === "gamepad" && innerHeight > innerWidth)
    toast("旋转手机横屏，操作空间更大");
}

function setFullKeyboardVisible(visible) {
  document.getElementById("fullKeyboard").style.display = visible ? "" : "none";
  var button = document.getElementById("fullKeyboardToggle");
  button.textContent = visible ? "竖向键盘 ↔" : "横向全键盘 ↔";
  button.setAttribute("aria-expanded", visible ? "true" : "false");
  document.getElementById("simpleKeyboard").style.display = visible ? "none" : "";
  try {
    if (window.NtvDevice && NtvDevice.setKeyboardLandscape)
      NtvDevice.setKeyboardLandscape(visible);
    else if (visible && innerHeight > innerWidth)
      toast("请横放手机使用全键盘，也可左右滑动按键区域");
  } catch (error) {}
  remoteModifiers.shift = remoteModifiers.ctrl = remoteModifiers.alt = false;
  updateModifierButtons();
}

function toggleFullKeyboard() {
  var visible = document.getElementById("fullKeyboard").style.display === "none";
  setFullKeyboardVisible(visible);
  var input = document.getElementById("remoteText");
  if (visible) input.blur();
  else input.blur();
}

var phoneSymbols = false, phoneShift = false;
function phoneCharacter(value) {
  api("/api/pointer", { action: "text", text: value }, function (error) {
    if (error) toast(error.message, true);
  });
  if (phoneShift) { phoneShift = false; renderPhoneKeyboard(); }
}
function togglePhoneSymbols() {
  phoneSymbols = !phoneSymbols;
  phoneShift = false;
  renderPhoneKeyboard();
}
function renderPhoneKeyboard() {
  var container = document.getElementById("phoneKeyRows");
  if (!container) return;
  container.innerHTML = "";
  var rows = phoneSymbols ? ["1234567890", "@#%&*()-+", "!?/:;,'\""]
    : ["qwertyuiop", "asdfghjkl", "zxcvbnm"];
  function key(row, label, action, wide) {
    var button = document.createElement("button");
    button.type = "button";
    button.className = "key" + (wide ? " wide" : "");
    button.textContent = label;
    button.onclick = action;
    row.appendChild(button);
    return button;
  }
  for (var i = 0; i < rows.length; i++) {
    var row = document.createElement("div");
    row.className = "keyboard-row phone-keyboard-row";
    if (i === 2) {
      var shift = key(row, phoneSymbols ? "ABC" : "⇧", function () {
        if (phoneSymbols) phoneSymbols = false;
        else phoneShift = !phoneShift;
        renderPhoneKeyboard();
      }, true);
      shift.setAttribute("aria-label", "切换大小写或字母");
      shift.setAttribute("aria-pressed", phoneShift ? "true" : "false");
    }
    for (var j = 0; j < rows[i].length; j++) {
      (function (value) {
        key(row, value, function () { phoneCharacter(value); });
      })(phoneShift ? rows[i].charAt(j).toUpperCase() : rows[i].charAt(j));
    }
    if (i === 2) key(row, "⌫", function () { remoteKey("backspace"); }, true)
      .setAttribute("aria-label", "删除");
    container.appendChild(row);
  }
  document.getElementById("phoneSymbols").textContent = phoneSymbols ? "ABC" : "123";
}

function updateModifierButtons() {
  var names = ["shift", "ctrl", "alt"];
  for (var i = 0; i < names.length; i++) {
    var button = document.getElementById(names[i] + "Key");
    if (button)
      button.className = "key wide accent" + (remoteModifiers[names[i]] ? " primary" : "");
  }
}

function toggleModifier(name) {
  remoteModifiers[name] = !remoteModifiers[name];
  updateModifierButtons();
}

function remoteKey(key) {
  var body = {
    action: "key",
    key: key,
    shift: remoteModifiers.shift,
    ctrl: remoteModifiers.ctrl,
    alt: remoteModifiers.alt
  };
  api("/api/pointer", body, function (error) {
    if (error) toast(error.message, true);
  });
  if (key.length === 1 || key === "space" || key === "enter" || key === "tab") {
    remoteModifiers = { shift: false, ctrl: false, alt: false };
    updateModifierButtons();
  }
}

function sendRemoteText() {
  var input = document.getElementById("remoteText"),
    text = input.value;
  if (!text) {
    toast("请先输入文字", true);
    return;
  }
  api("/api/pointer", { action: "text", text: text }, function (error) {
    if (error) {
      toast(error.message, true);
      return;
    }
    input.value = "";
    toast("文字已发送到电视网页");
  });
}

function setupRemoteControls() {
  renderPhoneKeyboard();
  var input = document.getElementById("remoteText");
  if (input)
    input.addEventListener("keydown", function (event) {
      if (event.key === "Enter" && !event.isComposing && event.keyCode !== 229) {
        event.preventDefault();
        sendRemoteText();
      }
    });
  var buttons = document.querySelectorAll("[data-repeat-key]"),
    timerId = null,
    repeatId = null;
  function stop() {
    clearTimeout(timerId);
    clearInterval(repeatId);
    timerId = null;
    repeatId = null;
  }
  for (var i = 0; i < buttons.length; i++) {
    (function (button) {
      function start(event) {
        stop();
        remoteKey(button.getAttribute("data-repeat-key"));
        timerId = setTimeout(function () {
          repeatId = setInterval(function () {
            remoteKey(button.getAttribute("data-repeat-key"));
          }, 105);
        }, 360);
        if (event) event.preventDefault();
      }
      button.addEventListener("touchstart", start, { passive: false });
      button.addEventListener("touchend", stop, false);
      button.addEventListener("touchcancel", stop, false);
      button.addEventListener("mousedown", start, false);
      button.addEventListener("mouseup", stop, false);
      button.addEventListener("mouseleave", stop, false);
    })(buttons[i]);
  }
  var mode = "touch";
  try {
    mode = localStorage.getItem("ntvControlMode") || "touch";
  } catch (error) {}
  if (mode !== "keyboard" && mode !== "gamepad") mode = "touch";
  switchControlMode(mode);
}

function setSensorStatus(text, bad) {
  var el = document.getElementById("sensorStatus");
  el.textContent = text;
  el.style.color = bad ? "#ff3b30" : "#248a3d";
}

function ntvVibrate(ms) {
  try {
    if (window.NtvDevice && NtvDevice.vibrate) {
      NtvDevice.vibrate(Number(ms) || 8);
      return;
    }
  } catch (error) {}
  if (navigator.vibrate) navigator.vibrate(ms);
}

function startNativeGyroscope() {
  try {
    if (window.NtvDevice && NtvDevice.hasGyroscope && NtvDevice.hasGyroscope()) {
      NtvDevice.startGyroscope();
      return true;
    }
  } catch (error) {}
  return false;
}

function stopNativeGyroscope() {
  try {
    if (window.NtvDevice && NtvDevice.stopGyroscope) NtvDevice.stopGyroscope();
  } catch (error) {}
}

window.__ntvNativeGyroscope = function (x, y, z) {
  if (!gyroRunning || document.hidden) return;
  var now = Date.now();
  if (now - lastOrientationRateAt < 250 || now - lastMotionRateAt < 250) return;
  var rates = fusedRotationRates(Number(z) * 57.2958, Number(x) * 57.2958, Number(y) * 57.2958);
  gyroSeen = true;
  gyroHasYaw = true;
  gyroYawRate = rates.horizontal;
  gyroPitchRate = rates.vertical;
  gyroRollRate = rates.roll;
  lastRateAt = now;
  lastSensorAt = now;
  sensorReady("应用内陀螺仪");
};

function enableGyroscope() {
  if (gyroRunning) {
    stopGyroscope();
    return;
  }
  var nativeReady = startNativeGyroscope(),
    requests = [],
    denied = false;
  if (
    typeof DeviceOrientationEvent !== "undefined" &&
    typeof DeviceOrientationEvent.requestPermission === "function"
  )
    requests.push(function () {
      return DeviceOrientationEvent.requestPermission();
    });
  if (
    typeof DeviceMotionEvent !== "undefined" &&
    typeof DeviceMotionEvent.requestPermission === "function"
  )
    requests.push(function () {
      return DeviceMotionEvent.requestPermission();
    });
  if (!requests.length) {
    startGyroscope();
    return;
  }
  var remaining = requests.length;
  function finished(result) {
    if (result !== "granted") denied = true;
    remaining--;
    if (remaining === 0) {
      if (denied && !nativeReady)
        setSensorStatus("动作与方向权限未开启，请在浏览器网站设置中允许。", true);
      else startGyroscope();
    }
  }
  for (var i = 0; i < requests.length; i++) {
    try {
      var promise = requests[i]();
      if (promise && promise.then)
        promise.then(finished, function () {
          finished("denied");
        });
      else finished("granted");
    } catch (error) {
      finished("denied");
    }
  }
}

function resetMotionTracking() {
  gyroYawRate = 0;
  gyroPitchRate = 0;
  gyroRollRate = 0;
  filteredYawRate = 0;
  filteredPitchRate = 0;
  lastSensorAt = 0;
  lastRateAt = 0;
  lastMotionRateAt = 0;
  lastOrientationRateAt = 0;
  lastOrientationAlpha = null;
  lastOrientationBeta = null;
  lastOrientationGamma = null;
  lastOrientationAt = 0;
  orientationEventType = "";
  lastGravityPitch = null;
  lastGravityAt = 0;
  lastGyroFrameAt = 0;
  lastMotionAt = 0;
  lastTranslationAt = 0;
  filteredAccelX = 0;
  filteredAccelY = 0;
  translationVelocityX = 0;
  translationVelocityY = 0;
}

function startGyroscope() {
  if (!gyroRunning) {
    window.addEventListener("deviceorientation", onOrientation, true);
    window.addEventListener("deviceorientationabsolute", onOrientation, true);
    window.addEventListener("devicemotion", onMotion, true);
    startGenericGyroscope();
    gyroRunning = true;
    gyroTicker = setInterval(updateGyroPointer, 16);
  }
  gyroSeen = false;
  lastSensorName = "";
  resetMotionTracking();
  document.getElementById("gyroButton").textContent = "关闭陀螺仪";
  setSensorStatus("请像遥控器一样握持手机，然后移动或转动…", false);
  setTimeout(function () {
    if (gyroRunning && !gyroSeen) {
      var secure = typeof isSecureContext === "undefined" || isSecureContext;
      setSensorStatus(
        secure
          ? "未收到传感器数据，请检查浏览器动作与方向权限。"
          : "浏览器禁止局域网 HTTP 使用传感器，请尝试系统浏览器或使用触控板。",
        true
      );
    }
  }, 3000);
}

function stopGyroscope() {
  window.removeEventListener("deviceorientation", onOrientation, true);
  window.removeEventListener("deviceorientationabsolute", onOrientation, true);
  window.removeEventListener("devicemotion", onMotion, true);
  stopNativeGyroscope();
  if (genericGyroscope) {
    try {
      genericGyroscope.stop();
    } catch (error) {}
    genericGyroscope = null;
  }
  gyroRunning = false;
  clearInterval(gyroTicker);
  gyroTicker = null;
  gyroSeen = false;
  lastSensorName = "";
  resetMotionTracking();
  document.getElementById("gyroButton").textContent = "启用陀螺仪";
  setSensorStatus("陀螺仪已关闭，仍可使用触控板。", false);
}

function suspendRemoteControl() {
  // Native onPause and page visibility can both notify us. Do not lose resume intent.
  gyroResumeAfterPause = gyroResumeAfterPause || gyroRunning;
  if (gyroRunning) stopGyroscope();
  resetPointerTransport();
}

function resumeRemoteControl() {
  resetPointerTransport();
  if (gyroResumeAfterPause) {
    gyroResumeAfterPause = false;
    enableGyroscope();
  }
}

function angleDelta(value, center) {
  return ((value - center + 540) % 360) - 180;
}

function displayAngle() {
  var value = 0;
  if (
    typeof screen !== "undefined" &&
    screen.orientation &&
    typeof screen.orientation.angle === "number"
  )
    value = screen.orientation.angle;
  else if (typeof window.orientation === "number") value = window.orientation;
  return (((Math.round(value / 90) * 90) % 360) + 360) % 360;
}

function screenAxes(x, y) {
  var angle = displayAngle();
  if (angle === 90) return [y, -x];
  if (angle === 180) return [-x, -y];
  if (angle === 270) return [-y, x];
  return [x, y];
}

function fusedRotationRates(alpha, beta, gamma) {
  var axes = screenAxes(beta, gamma);
  return { horizontal: alpha, vertical: axes[0], roll: alpha };
}

function updatePosture(gravity) {
  if (!gravity || typeof gravity.z !== "number") return;
  var x = Number(gravity.x) || 0,
    y = Number(gravity.y) || 0,
    z = Number(gravity.z) || 0,
    magnitude = Math.sqrt(x * x + y * y + z * z);
  if (magnitude < 1) return;
  var target = Math.max(0, Math.min(1, Math.abs(z) / magnitude));
  gravityFlatWeight += (target - gravityFlatWeight) * 0.18;
}

function accelerationDeadzone(value) {
  var sign = value < 0 ? -1 : 1,
    amount = Math.abs(value);
  return amount < 0.18 ? 0 : sign * (amount - 0.18);
}

function updateTranslation(acceleration, now, rotationPeak) {
  if (!acceleration || typeof acceleration.x !== "number" || typeof acceleration.y !== "number") {
    lastMotionAt = now;
    return;
  }
  var elapsed = lastMotionAt ? Math.max(0.008, Math.min(0.06, (now - lastMotionAt) / 1000)) : 0.016;
  lastMotionAt = now;
  var axes = screenAxes(acceleration.x, acceleration.y),
    rotationGain = rotationPeak > 45 ? 0 : rotationPeak > 20 ? 0.45 : 1;
  filteredAccelX = filteredAccelX * 0.5 + Math.max(-8, Math.min(8, axes[0])) * 0.5;
  filteredAccelY = filteredAccelY * 0.5 + Math.max(-8, Math.min(8, axes[1])) * 0.5;
  var ax = accelerationDeadzone(filteredAccelX) * rotationGain,
    ay = accelerationDeadzone(filteredAccelY) * rotationGain,
    oldX = translationVelocityX,
    oldY = translationVelocityY;
  translationVelocityX += ax * elapsed;
  translationVelocityY += ay * elapsed;
  if (oldX * translationVelocityX < 0 && Math.abs(ax) > 0.45) translationVelocityX = 0;
  if (oldY * translationVelocityY < 0 && Math.abs(ay) > 0.45) translationVelocityY = 0;
  var active = Math.abs(ax) + Math.abs(ay) > 0.08,
    damping = Math.exp(-(active ? 1.4 : 5.5) * elapsed);
  translationVelocityX = Math.max(-1.2, Math.min(1.2, translationVelocityX * damping));
  translationVelocityY = Math.max(-1.2, Math.min(1.2, translationVelocityY * damping));
  if (Math.abs(translationVelocityX) < 0.006) translationVelocityX = 0;
  if (Math.abs(translationVelocityY) < 0.006) translationVelocityY = 0;
  lastTranslationAt = now;
  if (active) {
    gyroSeen = true;
    sensorReady("六轴运动传感器");
  }
}

function onOrientation(event) {
  if (document.hidden || typeof event.alpha !== "number" || typeof event.beta !== "number") return;
  if (!orientationEventType) orientationEventType = event.type || "deviceorientation";
  if ((event.type || "deviceorientation") !== orientationEventType) return;
  var now = Date.now(),
    gamma = typeof event.gamma === "number" ? event.gamma : 0;
  if (lastOrientationAt && now - lastOrientationAt >= 12 && now - lastOrientationAt <= 250) {
    var seconds = (now - lastOrientationAt) / 1000,
      alphaRate = angleDelta(event.alpha, lastOrientationAlpha) / seconds,
      betaRate = angleDelta(event.beta, lastOrientationBeta) / seconds,
      gammaRate =
        lastOrientationGamma === null ? 0 : angleDelta(gamma, lastOrientationGamma) / seconds,
      rates = fusedRotationRates(alphaRate, betaRate, gammaRate);
    gyroYawRate = rates.horizontal;
    gyroPitchRate = rates.vertical;
    gyroRollRate = rates.roll;
    gyroHasYaw = true;
    gyroSeen = true;
    lastOrientationRateAt = now;
    lastRateAt = now;
    lastSensorAt = now;
    sensorReady("方向传感器 Alpha/Beta");
  }
  lastOrientationAlpha = event.alpha;
  lastOrientationBeta = event.beta;
  lastOrientationGamma = gamma;
  lastOrientationAt = now;
}

function onMotion(event) {
  if (document.hidden) return;
  var now = Date.now(),
    rate = event.rotationRate,
    gravity = event.accelerationIncludingGravity;
  updatePosture(gravity);
  if (now - lastOrientationRateAt < 250) return;
  var alpha = rate && typeof rate.alpha === "number" ? rate.alpha : 0,
    beta = rate && typeof rate.beta === "number" ? rate.beta : 0,
    gamma = rate && typeof rate.gamma === "number" ? rate.gamma : 0,
    hasRate =
      rate &&
      (typeof rate.alpha === "number" ||
        typeof rate.beta === "number" ||
        typeof rate.gamma === "number");
  if (hasRate) {
    var rates = fusedRotationRates(alpha, beta, gamma);
    gyroSeen = true;
    gyroHasYaw = typeof rate.alpha === "number";
    gyroYawRate = rates.horizontal;
    gyroPitchRate = rates.vertical;
    gyroRollRate = rates.roll;
    lastMotionRateAt = now;
    lastRateAt = now;
    lastSensorAt = now;
    sensorReady("角速度备用传感器");
    return;
  }
  if (now - lastRateAt <= 300) return;
  if (gravity && typeof gravity.y === "number" && typeof gravity.z === "number") {
    var axes = screenAxes(Number(gravity.x) || 0, Number(gravity.y) || 0),
      pitch =
        (Math.atan2(axes[1], Math.sqrt(axes[0] * axes[0] + gravity.z * gravity.z)) * 180) / Math.PI;
    if (lastGravityAt && now - lastGravityAt >= 12 && now - lastGravityAt <= 250) {
      gyroSeen = true;
      gyroHasYaw = false;
      gyroYawRate = 0;
      gyroPitchRate = (pitch - lastGravityPitch) / ((now - lastGravityAt) / 1000);
      lastSensorAt = now;
      sensorReady("重力传感器（仅上下）");
    }
    lastGravityPitch = pitch;
    lastGravityAt = now;
  }
}

function startGenericGyroscope() {
  if (typeof Gyroscope === "undefined") return;
  try {
    genericGyroscope = new Gyroscope({ frequency: 24 });
    genericGyroscope.addEventListener("reading", function () {
      if (document.hidden) return;
      var now = Date.now();
      if (now - lastOrientationRateAt < 250 || now - lastMotionRateAt < 250) return;
      var rates = fusedRotationRates(
        genericGyroscope.z * 57.2958,
        genericGyroscope.x * 57.2958,
        genericGyroscope.y * 57.2958
      );
      gyroSeen = true;
      gyroHasYaw = true;
      gyroYawRate = rates.horizontal;
      gyroPitchRate = rates.vertical;
      gyroRollRate = rates.roll;
      lastRateAt = now;
      lastSensorAt = now;
      sensorReady("陀螺仪备用传感器");
    });
    genericGyroscope.addEventListener("error", function () {});
    genericGyroscope.start();
  } catch (error) {
    genericGyroscope = null;
  }
}

function sensorReady(name) {
  if (name !== lastSensorName) {
    document.getElementById("gyroButton").textContent = "关闭陀螺仪";
    lastSensorName = name;
    setSensorStatus(name + "控制中 · 转动时移动，停下即停止", false);
  }
}

function openBrowserSettings() {
  location.href = "/pages/browser.html";
}

function clampRate(value) {
  return Math.max(-720, Math.min(720, value));
}

function pointerRateVector(horizontal, vertical) {
  var horizontalGain = 1.45 + Math.min(0.9, Math.abs(horizontal) / 60),
    x = horizontal * horizontalGain,
    y = vertical,
    magnitude = Math.sqrt(x * x + y * y);
  if (magnitude < 0.9) return { x: 0, y: 0 };
  var adjusted = magnitude - 0.9,
    speed = adjusted * 13 + Math.pow(Math.max(0, magnitude - 8), 1.15) * 15,
    scale = Math.min(5000, speed) / magnitude;
  return { x: x * scale, y: y * scale * 1.5 };
}

function updateGyroPointer() {
  if (!gyroRunning || !gyroSeen || document.hidden) return;
  var now = Date.now(),
    elapsed = lastGyroFrameAt
      ? Math.max(0.008, Math.min(0.06, (now - lastGyroFrameAt) / 1000))
      : 0.016;
  lastGyroFrameAt = now;
  var fresh = now - lastSensorAt < 120,
    rawYaw = fresh && gyroHasYaw ? clampRate(gyroYawRate) : 0,
    rawPitch = fresh ? clampRate(gyroPitchRate) : 0,
    peak = Math.max(Math.abs(rawYaw), Math.abs(rawPitch)),
    blend = peak > 8 ? 0.76 : 0.5;
  filteredYawRate += (rawYaw - filteredYawRate) * blend;
  filteredPitchRate += (rawPitch - filteredPitchRate) * blend;
  if (Math.abs(filteredYawRate) < 0.24) filteredYawRate = 0;
  if (Math.abs(filteredPitchRate) < 0.24) filteredPitchRate = 0;
  var velocity = pointerRateVector(filteredYawRate, filteredPitchRate),
    dx = -velocity.x * elapsed * pointerScale,
    dy = -velocity.y * elapsed * pointerScale;
  if (dx || dy) pointerMove(dx, dy);
}

function setupTouchpad() {
  var pad = document.getElementById("touchpad"),
    lastX = 0,
    lastY = 0,
    lastAt = 0,
    filteredSpeed = 0,
    travel = 0,
    moved = false,
    multi = false,
    multiBlocked = false,
    twoFingerActive = false,
    gestureWebPage = false,
    holding = false,
    mouseDown = false,
    holdTimer = null,
    twoFingerTap = false,
    twoFingerTapAt = 0,
    twoFingerOrigins = {},
    trackpadGesture = new NtvTrackpadGesture();
  window.addEventListener("pagehide", function () {
    twoFingerTap = false;
    releaseHold(true);
  }, false);
  function now() {
    return window.performance && typeof window.performance.now === "function"
      ? window.performance.now()
      : Date.now();
  }
  function clearHold() {
    if (holdTimer) {
      clearTimeout(holdTimer);
      holdTimer = null;
    }
  }
  function armHold() {
    clearHold();
    holdTimer = setTimeout(function () {
      holdTimer = null;
      if (moved || multi) return;
      holding = true;
      pad.className = "touchpad active holding";
      pointerButtonAction("down");
      ntvVibrate(18);
    }, 480);
  }
  function releaseHold(cancelled) {
    clearHold();
    if (holding) {
      holding = false;
      pointerButtonAction(cancelled ? "cancel" : "up");
    }
  }
  function begin(x, y) {
    releaseHold(true);
    lastX = x;
    lastY = y;
    lastAt = now();
    filteredSpeed = 0;
    travel = 0;
    moved = false;
    multi = false;
    multiBlocked = false;
    twoFingerActive = false;
    pad.className = "touchpad active";
    armHold();
  }
  function controlsWebPage() {
    return !!(state && ((state.current && state.current.webPageActive === true) ||
      (state.cast && state.cast.webPageActive === true)));
  }
  function beginTwoFinger(touches) {
    twoFingerTap = !multi && !holding && !moved && (!holdTimer || now() - lastAt <= 180);
    twoFingerTapAt = now();
    twoFingerOrigins = {};
    for (var i = 0; i < touches.length; i++)
      twoFingerOrigins[touches[i].identifier] = { x: touches[i].clientX, y: touches[i].clientY };
    releaseHold(true);
    multi = true;
    moved = true;
    twoFingerActive = false;
    gestureWebPage = controlsWebPage();
    cancelQueuedScroll();
    trackpadGesture.begin(touches, now());
    pad.className = "touchpad active";
  }
  function queueScroll(value) {
    if (value && (value.scrollX || value.scrollY)) pointerQueue.push({
      action: "scroll", scrollX: value.scrollX || 0, scrollY: value.scrollY || 0
    });
  }
  function cancelQueuedScroll() {
    pointerQueue.cancelScroll();
  }
  function checkTwoFingerTap(touches) {
    for (var i = 0; i < touches.length; i++) {
      var origin = twoFingerOrigins[touches[i].identifier];
      if (!origin || Math.abs(touches[i].clientX - origin.x) > 10
          || Math.abs(touches[i].clientY - origin.y) > 10) twoFingerTap = false;
    }
  }
  function updateTwoFinger(touches) {
    checkTwoFingerTap(touches);
    if (touches.length !== 2) {
      twoFingerActive = false;
      return;
    }
    var value = trackpadGesture.update(touches, now(), pointerScale);
    if (!value) return;
    twoFingerTap = false;
    if (!twoFingerActive) ntvVibrate(6);
    twoFingerActive = true;
    if (!gestureWebPage) return;
    if (value.type === "pinch") {
      pointerQueue.push({ action: "zoom", zoomFactor: value.factor });
      pad.className = "touchpad active holding";
    } else {
      queueScroll(value);
    }
  }
  function move(x, y) {
    var time = now(),
      dx = x - lastX,
      dy = y - lastY,
      elapsed = Math.max(8, Math.min(80, time - lastAt || 16)),
      distance = Math.sqrt(dx * dx + dy * dy),
      speed = distance / elapsed;
    lastX = x;
    lastY = y;
    lastAt = time;
    travel += distance;
    if (travel > 6 && !holding) {
      moved = true;
      clearHold();
    }
    if (holding) moved = true;
    filteredSpeed = filteredSpeed * 0.58 + speed * 0.42;
    // Dragging a seek bar/slider needs a stable ratio, not speed-dependent acceleration.
    var gain = holding ? 1 : 0.82 + Math.min(2.18, Math.pow(filteredSpeed / 0.35, 0.72) * 0.55);
    pointerMove(dx * gain * pointerScale, dy * gain * pointerScale);
  }
  function end(cancelled) {
    var wasHolding = holding;
    releaseHold(cancelled);
    if (cancelled) cancelQueuedScroll();
    else if (multi && twoFingerTap && now() - twoFingerTapAt <= 350) {
      pointerAction("rightclick");
      ntvVibrate(16);
    }
    pad.className = "touchpad";
    filteredSpeed = 0;
    if (!cancelled && !wasHolding && !moved && !multi) {
      pointerAction("click");
      ntvVibrate(8);
    }
    twoFingerTap = false;
    multi = false;
    multiBlocked = false;
    twoFingerActive = false;
    moved = false;
  }
  pad.addEventListener(
    "touchstart",
    function (e) {
      if (e.touches.length === 2 && !multiBlocked) beginTwoFinger(e.touches);
      else if (e.touches.length > 2) {
        twoFingerTap = false;
        releaseHold(true);
        multi = true;
        multiBlocked = true;
        moved = true;
        twoFingerActive = false;
        cancelQueuedScroll();
      } else if (e.touches.length === 1 && !multi)
        begin(e.touches[0].clientX, e.touches[0].clientY);
      e.preventDefault();
    },
    false
  );
  pad.addEventListener(
    "touchmove",
    function (e) {
      if (e.touches.length === 2 && !multiBlocked) {
        if (!multi) beginTwoFinger(e.touches);
        else updateTwoFinger(e.touches);
      } else if (e.touches.length === 1 && !multi) move(e.touches[0].clientX, e.touches[0].clientY);
      e.preventDefault();
    },
    false
  );
  pad.addEventListener(
    "touchend",
    function (e) {
      if (multi && !multiBlocked) {
        var finalTouches = [], i;
        for (i = 0; i < e.touches.length; i++) finalTouches.push(e.touches[i]);
        for (i = 0; i < e.changedTouches.length; i++) finalTouches.push(e.changedTouches[i]);
        if (finalTouches.length === 2) updateTwoFinger(finalTouches);
      }
      if (multi) checkTwoFingerTap(e.changedTouches);
      if (multi && e.touches.length > 0) {
        multiBlocked = true;
        twoFingerActive = false;
        cancelQueuedScroll();
      }
      if (!e.touches.length) end(false);
      e.preventDefault();
    },
    false
  );
  pad.addEventListener(
    "touchcancel",
    function (e) {
      end(true);
      e.preventDefault();
    },
    false
  );
  pad.addEventListener("mousedown", function (e) {
    mouseDown = true;
    begin(e.clientX, e.clientY);
    e.preventDefault();
  });
  document.addEventListener("mousemove", function (e) {
    if (mouseDown) move(e.clientX, e.clientY);
  });
  document.addEventListener("mouseup", function () {
    if (mouseDown) {
      mouseDown = false;
      end(false);
    }
  });
  window.addEventListener("blur", function () {
    mouseDown = false;
    end(true);
  });
  document.addEventListener("visibilitychange", function () {
    if (document.hidden) { mouseDown = false; end(true); }
  });
  pad.addEventListener(
    "wheel",
    function (e) {
      queueScroll(e.deltaY * (e.deltaMode === 1 ? 16 : e.deltaMode === 2 ? innerHeight : 1));
      e.preventDefault();
    },
    false
  );
}
function renderPageState() {
  var width = state.cast && state.cast.running ? Number(state.cast.width)
    : state.display && Number(state.display.width);
  pointerScale = width > 0 ? Math.max(0.75, Math.min(3, width / 1280)) : 1;
  var webPage = state && ((state.current && state.current.webPageActive === true) ||
      (state.cast && state.cast.webPageActive === true));
  document.getElementById("touchpad").textContent = webPage
    ? "单击左键 · 双指轻点右键 · 长按拖动 · 双指滚动 · 无横向滚动时左右前进/后退 · 捏合缩放"
    : "单指移动 · 单击左键 · 双指轻点右键";
  if (state.settings.flyMouseEnabled !== true) {
    state.settings.flyMouseEnabled = true;
    api("/api/settings", { flyMouseEnabled: true }, function (error) {
      if (error) toast(error.message, true);
    });
  }
  updateFlyMouseUi();
  NtvChannelPicker.update(state);
}
setupTouchpad();
setupRemoteControls();
window.addEventListener("online", resetPointerTransport, false);
window.addEventListener("pageshow", resumeRemoteControl, false);
window.addEventListener("pagehide", suspendRemoteControl, false);
window.addEventListener("orientationchange", resetPointerTransport, false);
document.addEventListener(
  "visibilitychange",
  function () {
    if (document.hidden) suspendRemoteControl();
    else resumeRemoteControl();
  },
  false
);
startPage();
