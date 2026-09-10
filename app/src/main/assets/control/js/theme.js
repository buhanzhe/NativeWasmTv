// ES5 and literal CSS colors keep manual night mode working on old WebViews.
(function () {
  var mode = "system", query = window.matchMedia ? window.matchMedia("(prefers-color-scheme: dark)") : null;
  try { mode = localStorage.getItem("ntv-theme") || "system"; } catch (ignored) {}
  function systemDark() {
    // Android WebView's media query reflects the host theme, which may be fixed
    // for legacy playback. The local bridge supplies the actual UI night mode.
    try {
      if (window.NtvDevice && typeof window.NtvDevice.isSystemDark === "function") {
        return window.NtvDevice.isSystemDark() === true;
      }
    } catch (ignored) {}
    return !!(query && query.matches);
  }
  function apply() {
    var dark = mode === "dark" || (mode === "system" && systemDark());
    document.documentElement.setAttribute("data-theme", dark ? "dark" : "light");
    document.documentElement.style.colorScheme = dark ? "dark" : "light";
    var select = document.getElementById("pageTheme");
    if (select) select.value = mode;
  }
  window.setPageTheme = function (value) {
    mode = value === "dark" || value === "light" ? value : "system";
    try { localStorage.setItem("ntv-theme", mode); } catch (ignored) {}
    apply();
  };
  window.refreshSystemTheme = apply;
  apply();
  document.addEventListener("DOMContentLoaded", apply);
  window.addEventListener("pageshow", apply);
  if (query) {
    if (query.addEventListener) query.addEventListener("change", apply);
    else if (query.addListener) query.addListener(apply);
  }
  window.addEventListener("storage", function (event) {
    if (event.key === "ntv-theme") {
      mode = event.newValue || "system";
      apply();
    }
  });
})();
