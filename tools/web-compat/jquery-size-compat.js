// Shared legacy jQuery API compatibility; independently reviewed and API19 tested.
// jQuery's removed .size() is the documented collection .length alias.
(function (w, d) {
  'use strict';
  var marker = w.__ntvTvCompatibility;
  if (!marker || marker.jquerySizeHook || !Object.isExtensible(marker)) return;
  marker.jquerySizeHook = true;
  function install() {
    var jq = w.jQuery, proto = jq && jq.fn;
    if (typeof jq !== 'function' || !proto ||
        !/^3\.\d+\.\d+(?:[-+].*)?$/.test(proto.jquery || '') ||
        'size' in proto || !Object.isExtensible(proto)) return;
    Object.defineProperty(proto, 'size', {
      configurable: true, writable: true,
      value: function () { return this.length; }
    });
  }
  install();
  if (d.readyState === 'loading') d.addEventListener('DOMContentLoaded', install, false);
  if (d.readyState !== 'complete') w.addEventListener('load', install, false);
  // Capturing load does not change script execution, request, or event behavior.
  // It also covers external scripts inserted after DOMContentLoaded/window.load.
  d.addEventListener('load', function (event) {
    if (event.target && String(event.target.nodeName).toLowerCase() === 'script') install();
  }, true);
})(window, document);
