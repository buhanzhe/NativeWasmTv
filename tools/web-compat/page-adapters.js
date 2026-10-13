// Hainan's known getOtherHtml helper reads XHR.status during OPENED. KitKat's
// native accessor throws then. Wait for DONE in this helper only; leave native
// XHR, transport security, network errors and the rest of the page unchanged.
(function (w, d) {
  'use strict';
  if (!w.__ntvTvCompatibility || w.location.hostname !== 'www.hnntv.cn' ||
      w.location.pathname !== '/live.html') return;
  function installHtmlHelper() {
    if (typeof w.getOtherHtml !== 'function') return;
    // Exact captured implementation fingerprint, normalizing CRLF line endings only.
    // An upstream change fails closed instead of replacing unknown behavior.
    var source = Function.prototype.toString.call(w.getOtherHtml).replace(/\r\n/g, '\n');
    var hash = 5381;
    for (var i = 0; i < source.length; i++) hash = ((hash * 33) ^ source.charCodeAt(i)) >>> 0;
    if (hash !== 3279651206) return;
    w.getOtherHtml = function (url, callback, update) {
      var xhr = new w.XMLHttpRequest();
      xhr.onreadystatechange = function () {
        if (xhr.readyState !== 4) return;
        var status = xhr.status;
        if (status === 200) callback(true, xhr.responseText);
        else if (status !== 0) callback(false, xhr.responseText);
      };
      xhr.open('GET', url + (update ? '?v=' + (new Date()).getTime() : ''), true);
      xhr.send(null);
    };
  }
  installHtmlHelper();
  if (d.readyState === 'loading') d.addEventListener('DOMContentLoaded', installHtmlHelper, false);
})(window, document);
