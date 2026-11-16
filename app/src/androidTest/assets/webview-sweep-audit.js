(function () {
  if (window.__ntvSweepErrorAudit) return;
  window.__ntvSweepErrorAudit = true;
  var original = console.error;
  console.error = new Proxy(original, {apply: function (fn, owner, args) {
    var details = args.map(function (item) {
      if (!item || typeof item !== 'object') return typeof item === 'string' ? item : null;
      var result = {};
      ['name', 'message', 'code', 'error_code', 'error_msg'].forEach(function (key) {
        try { if (typeof item[key] === 'string' || typeof item[key] === 'number') result[key] = item[key]; } catch (ignored) {}
      });
      try { if (item.paramData) {
        result.paramData = {};
        ['error_code', 'error_msg', 'message', 'code'].forEach(function (key) {
          try { if (typeof item.paramData[key] === 'string' || typeof item.paramData[key] === 'number') result.paramData[key] = item.paramData[key]; } catch (ignored) {}
        });
      }} catch (ignored) {}
      return result;
    });
    return Reflect.apply(fn, owner, [JSON.stringify(details)]);
  }});

  // Observe completed original requests. Never log signatures, stream URLs,
  // cookies or response bodies, and never alter the response delivered to the SDK.
  var open = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = new Proxy(open, {apply: function (fn, owner, args) {
    try {
      var url = new URL(args[1], location.href), kind = '';
      if (location.hostname === 'static.hntv.tv' && url.hostname === 'pubmod.hntv.tv' && /^\/program\/getAuth\/channel\/channelIds\/1\/[0-9]+$/.test(url.pathname)) kind = 'HNTV';
      if (location.hostname === 'live.kankanews.com' && url.hostname === 'kapi.kankanews.com' && /^\/content\/pc\/tv\/(programs|program\/detail)$/.test(url.pathname)) kind = 'KANKAN';
      if (location.hostname === 'www.dbw.cn' && url.hostname === 'idclive.hljtv.com' && /\.m3u8$/.test(url.pathname)) kind = 'DBW';
      if (kind) owner.addEventListener('loadend', function () {
        try {
          var meta = {http: owner.status, kind: kind};
          if (kind === 'DBW') {
            var lines = owner.responseText.split(/\r?\n/);
            meta.sequence = lines.filter(function (s) { return s.indexOf('#EXT-X-MEDIA-SEQUENCE:') === 0; })[0];
            meta.ended = lines.indexOf('#EXT-X-ENDLIST') >= 0;
            meta.segments = lines.filter(function (s) { return s && s[0] !== '#'; }).map(function (s) { return new URL(s, url.href).pathname; }).slice(-8);
          } else {
            var data = JSON.parse(owner.responseText);
            meta.array = Array.isArray(data);
            if (meta.array) {
              meta.items = data.length;
              if (data.length) meta.streams = Array.isArray(data[0].video_streams) ? data[0].video_streams.length : 0;
            } else if (data && typeof data === 'object') {
              meta.fields = Object.keys(data);
              if (typeof data.code === 'number' || typeof data.code === 'string') meta.code = data.code;
              if (kind === 'KANKAN') {
                meta.programmeFlags = [];
                var now = Date.now() / 1000;
                function visit(value, depth) {
                  if (!value || typeof value !== 'object' || depth > 6 || meta.programmeFlags.length >= 16) return;
                  if (value.start_time && value.end_time && (now < Number(value.start_time) || now >= Number(value.end_time))) return;
                  if ('is_shield' in value || 'is_review' in value || 'is_exist_pad' in value) {
                    var flags = {};
                    ['id', 'play', 'is_shield', 'is_review', 'is_exist_pad', 'start_time', 'end_time'].forEach(function (key) {
                      if (typeof value[key] === 'number' || typeof value[key] === 'boolean') flags[key] = value[key];
                    });
                    meta.programmeFlags.push(flags);
                  }
                  Object.keys(value).slice(0, 400).forEach(function (key) { visit(value[key], depth + 1); });
                }
                visit(data.result, 0);
              }
            }
          }
          console.log('Original player API status ' + JSON.stringify(meta));
        } catch (ignored) { console.log('Original player API status ' + kind + ' unreadable'); }
      });
    } catch (ignored) {}
    return Reflect.apply(fn, owner, args);
  }});
})();
