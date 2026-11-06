(function () {
  'use strict';
  if (window.__ntvWebMedia) return;
  var handlers = {}, error = '', position = null;
  var token = String(Date.now()) + ':' + Math.random().toString(36).slice(2);
  var session = navigator.mediaSession;
  // Small compatibility surface for WebViews without Media Session support.
  // No native bridge, privileged page access or background timers are installed.
  if (!session) {
    session = { metadata: null, playbackState: 'none',
      setActionHandler: function (action, handler) {
        if (!/^(play|pause|stop|seekbackward|seekforward|seekto|previoustrack|nexttrack)$/.test(action))
          throw new TypeError('Unsupported media action');
        if (handler !== null && typeof handler !== 'function') throw new TypeError('Invalid media handler');
      }, setPositionState: function () {} };
    try { Object.defineProperty(navigator, 'mediaSession', { configurable: true, value: session }); }
    catch (ignored) { session = null; }
    if (session && !window.MediaMetadata) window.MediaMetadata = function (init) {
      init = init || {};
      this.title = init.title || ''; this.artist = init.artist || ''; this.album = init.album || '';
      this.artwork = init.artwork || [];
    };
  }
  if (session) {
    var originalAction = session.setActionHandler;
    try { if (typeof originalAction === 'function') session.setActionHandler = function (action, handler) {
      var result = originalAction.apply(this, arguments);
      if (this === session) { if (handler === null) delete handlers[action]; else handlers[action] = handler; }
      return result;
    }; } catch (ignored) {}
    var originalPosition = session.setPositionState;
    try { if (typeof originalPosition === 'function') session.setPositionState = function (state) {
      var result = originalPosition.apply(this, arguments);
      if (this === session) position = state && isFinite(state.duration) && state.duration > 0
        ? { duration: state.duration, position: Number(state.position) || 0, rate: Number(state.playbackRate) || 1, time: Date.now() } : null;
      return result;
    }; } catch (ignored) {}
  }
  function text(value) { return typeof value === 'string' ? value.slice(0, 512) : ''; }
  function artwork(value) {
    if (typeof value !== 'string') return '';
    if (/^data:image\/(png|jpeg|gif|webp);base64,/i.test(value)) return value.length <= 262144 ? value : '';
    if (value.length > 8192) return '';
    var a = document.createElement('a'); a.href = value;
    return /^https?:$/i.test(a.protocol) ? a.href : '';
  }
  function media() {
    var items = document.querySelectorAll('audio,video'), best = null;
    for (var i = 0; i < items.length && i < 64; i++) {
      var item = items[i];
      if (!(item.currentSrc || item.src || item.srcObject)) continue;
      if (!best || (best.paused && !item.paused) || (best.ended && !item.ended)) best = item;
    }
    return best;
  }
  function ownSnapshot() {
    var m = media(), meta = session && session.metadata, state = session && session.playbackState;
    var frames = -1;
    if (m && m.tagName.toLowerCase() === 'video') try {
      if (typeof m.webkitDecodedFrameCount === 'number') frames = m.webkitDecodedFrameCount;
      else if (typeof m.getVideoPlaybackQuality === 'function') frames = m.getVideoPlaybackQuality().totalVideoFrames;
    } catch (ignored) {}
    var playing = state === 'playing' || (state !== 'paused' && !!m && !m.paused && !m.ended);
    var cover = '', coverScore = Infinity, covers = meta && meta.artwork || [];
    for (var i = 0; i < covers.length && i < 16; i++) {
      var url = artwork(covers[i].src), size = parseInt(covers[i].sizes, 10);
      var score = size > 0 ? Math.abs(size - 512) : 10000 + i;
      if (url && score < coverScore) { cover = url; coverScore = score; }
    }
    var duration = m && isFinite(m.duration) && m.duration > 0 ? m.duration : position ? position.duration : 0;
    var current = m ? Number(m.currentTime) || 0 : position ? position.position
      + (playing ? Math.max(0, Date.now() - position.time) / 1000 * position.rate : 0) : 0;
    return { token: token, available: !!(m || handlers.play || handlers.pause),
      prepared: !!(m && m.readyState >= 1 || handlers.play || handlers.pause),
      playing: playing, canPlay: !!(handlers.play || m), canPause: !!(handlers.pause || m),
      title: text(meta && meta.title), artist: text(meta && meta.artist), album: text(meta && meta.album),
      artwork: cover, audioOnly: !!m && m.tagName.toLowerCase() === 'audio',
      mediaReadyState: m ? Number(m.readyState) || 0 : 0,
      mediaTimeMs: m ? Math.round((Number(m.currentTime) || 0) * 1000) : 0,
      videoWidth: m ? Number(m.videoWidth) || 0 : 0, videoHeight: m ? Number(m.videoHeight) || 0 : 0,
      videoFrames: typeof frames === 'number' && isFinite(frames) ? frames : -1,
      mediaPaused: !m || !!m.paused, mediaEnded: !!(m && m.ended),
      durationMs: Math.round(duration * 1000), positionMs: Math.round(Math.max(0, duration ? Math.min(duration, current) : current) * 1000),
      error: error };
  }
  function selected() {
    var own = ownSnapshot();
    if (own.available || own.title || own.artwork) return { api: window.__ntvWebMedia, state: own };
    // Same-origin embedded players only. Never cross the page's origin boundary.
    var frames = document.querySelectorAll('iframe');
    for (var i = 0; i < frames.length && i < 16; i++) try {
      var api = frames[i].contentWindow.__ntvWebMedia;
      if (api) { var state = api.ownSnapshot(); if (state.available) return { api: api, state: state }; }
    } catch (ignored) {}
    return { api: window.__ntvWebMedia, state: own };
  }
  function act(action) {
    error = '';
    if (action !== 'play' && action !== 'pause') return { ok: false, error: '不支持的网页媒体操作' };
    try {
      var result, handler = handlers[action];
      if (typeof handler === 'function') result = handler.call(session, { action: action });
      else {
        var m = media();
        if (!m) return { ok: false, error: '网页没有可控制的媒体' };
        result = action === 'play' ? m.play() : m.pause();
      }
      if (result && typeof result.catch === 'function') result.catch(function () { error = '网页拒绝播放，请在网页中确认播放权限'; });
      return { ok: true };
    } catch (failed) { error = '网页媒体操作失败'; return { ok: false, error: error }; }
  }
  window.__ntvWebMedia = {
    ownSnapshot: ownSnapshot,
    snapshot: function () {
      var state = selected().state, tv = window.__ntvTvPlayback && window.__ntvTvPlayback.state();
      state.documentToken = token;
      state.tvFullscreen = !!(tv && tv.fullscreen);
      state.tvFullscreenRevision = tv ? tv.revision : 0;
      state.tvPlayback = tv || null;
      return state;
    },
    act: act,
    command: function (documentToken, mediaToken, action, deadline) {
      var target = selected();
      if (deadline && Date.now() > deadline || document.hidden || window.__ntvMediaPause
          || documentToken !== token || mediaToken !== target.state.token)
        return { ok: false, error: '网页媒体已变化，请刷新后重试' };
      return target.api.act(action);
    }
  };
})();
