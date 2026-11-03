/* Multi-document management navigation. ES5; no player commands or polling. */
(function (global) {
  "use strict";
  var current = null, overlays = [], popping = false, busy = false, closeTimer = null,
    pendingNavigation = null, leaveHooks = [], sequence = 0, retiringOverlay = false,
    busyTimer = null, resumePending = false;
  var key = "ntvNavigationTransferV2";
  function unlockNavigation() {
    busy = false;
    clearTimeout(busyTimer); busyTimer = null;
  }
  function lockNavigation() {
    unlockNavigation(); busy = true;
    // Some embedded browsers omit popstate/pageshow on history restoration.
    // A cancelled or failed navigation must not leave visible menu links locked.
    busyTimer = setTimeout(function () {
      busyTimer = null;
      if (document.hidden || !current || route(location.href) !== current.url) return;
      busy = false; retiringOverlay = false;
      if (!overlays.length && history.state && history.state.ntvOverlay === current.id)
        write(false, false);
      if (pendingNavigation) {
        var target = pendingNavigation; pendingNavigation = null; navigate(target);
      }
    }, 4000);
  }
  function route(value) {
    try {
      if (/^[a-z]+:\/\/[^/?#]*@/i.test(String(value))) return "";
      var a = document.createElement("a"); a.href = value;
      if (a.protocol !== location.protocol || a.host !== location.host || a.username || a.password) return "";
      var path = a.pathname.charAt(0) === "/" ? a.pathname : "/" + a.pathname;
      if (!/^(\/|\/index\.html|\/pages\/(media|playback|channels|browser|script|system|groups|flymouse|advanced|personalization|about|licenses|operation|decoder)\.html|\/video-recorder\.html)$/.test(path)) return "";
      var query = String(a.search || "").replace(/([?&])__ntvNav=[^&]*(&?)/g, function (_, before, after) {
        return after ? before : "";
      });
      return (path === "/" ? "/index.html" : path) + query;
    } catch (ignored) { return ""; }
  }
  function home() { return route(location.href).split("?")[0] === "/index.html"; }
  function id() { return Date.now().toString(36) + "-" + (++sequence) + "-" + Math.random().toString(36).slice(2); }
  function write(overlay, push) {
    var entry = {}, old = history.state;
    if (old && typeof old === "object") for (var name in old)
      if (Object.prototype.hasOwnProperty.call(old, name)) entry[name] = old[name];
    entry.ntvNavigation = current;
    entry.ntvOverlay = overlay ? current.id : null;
    try { history[push ? "pushState" : "replaceState"](entry, "", current.url); return true; }
    catch (ignored) { return false; }
  }
  function init() {
    var url = route(location.href), saved = history.state && history.state.ntvNavigation;
    if (!url) return;
    if (saved && saved.version === 2 && saved.url === url && saved.id) current = saved;
    else {
      current = {version: 2, id: id(), url: url, parent: null, fallback: "/index.html", ui: {}};
      var token = /[?&]__ntvNav=([^&#]+)/.exec(location.href);
      try {
        var transfer = JSON.parse(sessionStorage.getItem(key) || "null");
        if (token && transfer && transfer.id === token[1] && transfer.url === url
            && Date.now() >= transfer.time && Date.now() - transfer.time < 60000
            && transfer.parent && route(transfer.parent.url)) {
          current.id = transfer.id; current.parent = transfer.parent;
          sessionStorage.removeItem(key);
        }
      } catch (ignored) {}
      write(false, false);
    }
    // A reload can restore an overlay history entry, but not its live UI/callbacks.
    // Consume that owned entry once rather than leaving an invisible extra Back.
    if (history.state && history.state.ntvOverlay === current.id && !overlays.length && !busy) {
      lockNavigation(); retiringOverlay = true; history.back();
    }
  }
  function saveUi(value) {
    if (!current) init();
    if (!current) return;
    if (!current.ui) current.ui = {};
    for (var name in value) if (Object.prototype.hasOwnProperty.call(value, name)) current.ui[name] = value[name];
    write(!!(history.state && history.state.ntvOverlay === current.id), false);
  }
  function leave(done) {
    saveUi({scrollX: global.pageXOffset || 0, scrollY: global.pageYOffset || 0});
    var remaining = leaveHooks.length, finished = false, timeout;
    function finish() {
      if (finished) return;
      finished = true; clearTimeout(timeout);
      if (typeof done === "function") done();
    }
    if (!remaining) { finish(); return; }
    timeout = setTimeout(finish, 1500); // A disconnected receiver must not trap navigation.
    for (var i = 0; i < leaveHooks.length; i++) (function (hook) {
      var called = false;
      function next() { if (!called) { called = true; if (!--remaining) finish(); } }
      try { hook(next); if (!hook.length) next(); } catch (ignored) { next(); }
    })(leaveHooks[i]);
  }
  function navigate(value) {
    var target = route(value);
    if (!target || busy) return false;
    if (!current) init();
    if (!current) return false;
    if (overlays.length) return false; // Close the current dialog before navigating.
    if (closeTimer !== null) { pendingNavigation = target; return true; }
    lockNavigation();
    leave(function () {
      if (target.split("?")[0] === "/index.html") { location.replace(target); return; }
      var transfer = {id: id(), url: target, parent: {id: current.id, url: current.url}, time: Date.now()};
      var address = target;
      try {
        sessionStorage.setItem(key, JSON.stringify(transfer));
        address += (target.indexOf("?") < 0 ? "?" : "&") + "__ntvNav=" + transfer.id;
      } catch (ignored) {} // No storage: use safe home fallback, not unrelated browser history.
      location.assign(address);
    });
    return true;
  }
  function overlayOpen(name, close) {
    if (!current) init();
    if (!current || popping) return;
    clearTimeout(closeTimer); closeTimer = null;
    for (var i = 0; i < overlays.length; i++) if (overlays[i].name === name) return;
    overlays.push({name: name, close: close});
    if (!busy && !(history.state && history.state.ntvOverlay === current.id)) write(true, true);
  }
  function overlayClosed(name) {
    for (var i = overlays.length - 1; i >= 0; i--) if (overlays[i].name === name) overlays.splice(i, 1);
    if (popping || busy || overlays.length || !current || !(history.state && history.state.ntvOverlay === current.id)) return;
    // Allow synchronous sheet-to-sheet replacement to reuse the owned entry.
    clearTimeout(closeTimer);
    closeTimer = setTimeout(function () {
      closeTimer = null;
      if (!overlays.length && history.state && history.state.ntvOverlay === current.id) {
        lockNavigation(); retiringOverlay = true; history.back();
      }
    }, 0);
  }
  function back(nativeBack) {
    if (busy || closeTimer !== null) return true;
    if (!current) init();
    if (overlays.length) {
      var top = overlays[overlays.length - 1];
      if (top.close() !== false) overlayClosed(top.name);
      return true;
    }
    if (home()) {
      if (nativeBack) return false; // Host closes only the management Activity.
      lockNavigation(); leave(function () { history.back(); }); return true;
    }
    lockNavigation();
    leave(function () {
      if (current && current.parent && current.parent.id && route(current.parent.url)) history.back();
      else location.replace("/index.html");
    });
    return true;
  }
  global.NtvNavigation = {
    init: init, go: navigate, back: back, safeRoute: route,
    overlayOpen: overlayOpen, overlayClosed: overlayClosed,
    beforeLeave: function (hook) { leaveHooks.push(hook); },
    saveUi: saveUi, readUi: function () { return current && current.ui || {}; }
  };
  global.addEventListener("popstate", function () {
    unlockNavigation();
    var top = overlays[overlays.length - 1];
    if (top && !retiringOverlay) {
      popping = true;
      if (top.close() !== false) overlayClosed(top.name);
      popping = false;
    }
    retiringOverlay = false;
    if (overlays.length) write(true, true); // Nested layer or cancelled editor dismissal.
    else if (history.state && history.state.ntvOverlay === (current && current.id)) {
      // Forward into an already-dismissed overlay must not create a dead Back step.
      lockNavigation(); history.back(); return;
    }
    if (pendingNavigation) { var target = pendingNavigation; pendingNavigation = null; navigate(target); }
  }, false);
  global.addEventListener("pageshow", function () {
    resumePending = false; unlockNavigation(); init();
    var ui = current && current.ui;
    if (ui && typeof ui.scrollY === "number" && global.scrollTo) global.scrollTo(ui.scrollX || 0, ui.scrollY);
  }, false);
  global.addEventListener("pagehide", function () { resumePending = true; busy = true; leave(); }, false);
  document.addEventListener("visibilitychange", function () {
    if (document.hidden) { resumePending = true; return; }
    if (resumePending) { resumePending = false; unlockNavigation(); init(); }
  }, false);
  global.addEventListener("focus", function () {
    if (resumePending && !document.hidden) { resumePending = false; unlockNavigation(); init(); }
  }, false);
  document.addEventListener("click", function (event) {
    if (event.defaultPrevented || event.button > 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    var a = event.target;
    while (a && String(a.tagName).toLowerCase() !== "a") a = a.parentNode;
    if (!a || /^#/.test(a.getAttribute("href") || "") || a.getAttribute("download") !== null
        || (a.target && a.target !== "_self") || !route(a.href)) return;
    event.preventDefault(); navigate(a.href);
  }, false);
  init();
})(window);
