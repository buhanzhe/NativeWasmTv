(function (x, y) {
  function inspect(doc, px, py, depth) {
    var win = doc.defaultView, root = doc.scrollingElement || doc.documentElement;
    var el = doc.elementFromPoint(px, py);
    // Opaque frames are conservatively scroll-only: never navigate away from a player.
    if (el && /^(IFRAME|FRAME)$/.test(el.tagName)) {
      try {
        var child = el.contentDocument, rect = el.getBoundingClientRect();
        if (!child || depth > 6) return true;
        if (inspect(child, (px - rect.left) * el.clientWidth / Math.max(1, rect.width),
            (py - rect.top) * el.clientHeight / Math.max(1, rect.height), depth + 1)) return true;
      } catch (ignored) { return true; }
    }
    while (el) {
      var overflow = win.getComputedStyle(el).overflowX;
      if (el !== root && el.scrollWidth > el.clientWidth + 1 && /^(auto|scroll|overlay)$/.test(overflow)) return true;
      el = el.parentElement || (el.getRootNode && el.getRootNode().host);
    }
    if (!root) return false;
    var rootOverflow = win.getComputedStyle(root).overflowX;
    var bodyOverflow = doc.body ? win.getComputedStyle(doc.body).overflowX : "visible";
    return root.scrollWidth > root.clientWidth + 1 && !/^(hidden|clip)$/.test(rootOverflow)
      && !/^(hidden|clip)$/.test(bodyOverflow);
  }
  try { return inspect(document, x, y, 0); } catch (ignored) { return true; }
})
