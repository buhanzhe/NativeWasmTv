// ==UserScript==
// @name nTv 内置网页电视播放助手
// @version 1.0.15
// @description 自动铺满原播放器并尝试播放，继续供原生嗅探；保留原站请求和加密流程，及时释放已播放的直播缓冲。
// @grant none
// ==/UserScript==
// Bound the provider's live HLS cache before it creates its player.
(function(w,d){
  'use strict';
  if(w.location.hostname!=='live.jstv.com'||!/(?:^|&)channelId=676(?:&|$)/.test(w.location.search.slice(1)))return;
  if(w.__ntvJstvBufferPolicy)return;
  var tries=0,timer=0;
  function configure(){
    var root=d.getElementById('app'),queue=root&&root.__vue__?[root.__vue__]:[],seen=[];
    while(queue.length&&seen.length<64){
      var component=queue.shift();if(!component||seen.indexOf(component)>=0)continue;seen.push(component);
      var options=component.opitionLive;
      if(options&&typeof options==='object'){
        var config=options.hlsOpts||(options.hlsOpts={});
        // Use the SDK's own eviction/loader controls; requests and signing stay in the SDK.
        config.maxBufferLength=6;config.maxMaxBufferLength=12;config.maxBufferSize=8*1024*1024;
        config.liveBackBufferLength=4;config.enableWorker=false;
        w.__ntvJstvBufferPolicy=true;if(timer)w.clearInterval(timer);return;
      }
      var children=component.$children||[];for(var i=0;i<children.length;i++)queue.push(children[i]);
    }
    if(++tries>=300&&timer)w.clearInterval(timer);
  }
  configure();if(!w.__ntvJstvBufferPolicy)timer=w.setInterval(configure,100);
})(window,document);

(function (w, d) {
  'use strict';
  var version = '1.0.15', versionCode = 10015;
  // One owner for accessible nested frames. Two helpers styling the same
  // video would otherwise restore each other's fullscreen styles on exit.
  try {
    for (var owner = w.parent; owner && owner !== w; owner = owner.parent) {
      var ownerApi = owner.__ntvTvPlayback;
      if (ownerApi && ownerApi.versionCode >= versionCode && !ownerApi.state().stopped) {
        if (w.__ntvTvPlayback) w.__ntvTvPlayback.stop();
        ownerApi.refresh(); return;
      }
      if (owner === owner.parent) break;
    }
  } catch (ignore) {}
  if (w.__ntvTvPlayback && w.__ntvTvPlayback.document === d && !w.__ntvTvPlayback.state().stopped
      && w.__ntvTvPlayback.versionCode >= versionCode) {
    w.__ntvTvPlayback.configure(w.__ntvTvAutoPlay !== false); return;
  }
  if (w.__ntvTvPlayback) w.__ntvTvPlayback.stop();
  var auto = w.__ntvTvAutoPlay !== false, started = Date.now(), timer = 0, pending = 0;
  var stopped = false, suppressed = false, active = null, revision = 0, changes = [], attempts = [];
  var controlsStyle = null, controlsPlayer = null, controlsMarker = null;
  var stage = 'sniffing', clicks = 0, lastGesture = 0, userPaused = false, scans = 0;
  var watchedDocs = [], watchedFrames = [];
  var youtube = /(^|\.)(youtube\.com|youtube-nocookie\.com)$|^youtu\.be$/i.test(w.location.hostname);
  var onlyWeb = youtube || /(^|\.)gdtv\.cn$/i.test(w.location.hostname), lastUrl = w.location.href;
  var playerSelector = '.bravo-player,#dhy_player,.video-js,.prism-player,.vcp-player,.cms-list-tv__player,.plyr,.mejs__container,.dplayer,.xgplayer,.jwplayer,.ckplayer,.tcplayer,.video-player,#videoPlayer,#player,#videoBox';
  if (/(^|\.)huya\.com$/i.test(w.location.hostname)) playerSelector = '#liveRoomObj,' + playerSelector;
  if (youtube) playerSelector = '#movie_player,.html5-video-player,ytm-player,#player-container-id,' + playerSelector;
  var playSelector = '.vjs-big-play-button,.prism-big-play-btn,.vcp-bigplay,.dplayer-play-icon,.xgplayer-start,.jw-display-icon-container,.txp_btn_play,.plyr__control--overlaid,.mejs__overlay-button,#video-action-play,.video-play-btn,.video-play-button,.play-btn,.play-button,[aria-label="播放"],[aria-label="播放视频"],[aria-label="Play"],[title="播放"],[title="Play"],[data-action="play"]';
  if (youtube) playSelector = '.ytp-large-play-button,.ytp-play-button[aria-label^="Play"],.ytp-play-button[aria-label^="播放"],' + playSelector;
  function handoff() {
    try { return !!w.__ntvMediaPause || (w.parent !== w && !!w.parent.__ntvMediaPause); }
    catch (ignore) { return !!w.__ntvMediaPause; }
  }
  function visible(el, transparentControl) {
    if (!el || !el.ownerDocument.documentElement.contains(el)) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 16 || r.height < 16) return false;
    for (var p = el; p && p.nodeType === 1; p = p.parentElement) {
      var s = p.ownerDocument.defaultView.getComputedStyle(p);
      if (s.display === 'none' || s.visibility === 'hidden' || s.opacity === '0' && !(p === el && transparentControl)) return false;
    }
    return true;
  }
  function attempt(el, kind) {
    var source = el.currentSrc || el.src || '', now = Date.now();
    for (var i = 0; i < attempts.length; i++) {
      var a = attempts[i];
      if (a.el === el && a.kind === kind && a.source === source) {
        if (a.count >= 3 || now - a.time < 2500) return false;
        a.count++; a.time = now; return true;
      }
    }
    if (attempts.length >= 64) return false;
    attempts.push({ el: el, kind: kind, source: source, count: 1, time: now }); return true;
  }
  function play(el) {
    if (!auto || userPaused || !el.paused || el.ended || el.error || !attempt(el, 'play')) return;
    try {
      var p = el.play();
      if (p && p.catch) p.catch(function (error) {
        // A desktop browser may forbid audible autoplay. Retry muted only for
        // that policy error; never change a working WebView's audio or source.
        if (!error || error.name !== 'NotAllowedError' || !auto || userPaused || stopped || handoff() || el.muted) return;
        try { el.muted = true; var retry = el.play(); if (retry && retry.catch) retry.catch(function () {}); } catch (ignore) {}
      });
    } catch (ignore) {}
  }
  function restoreHuyaAudio(el) {
    if (!/(^|\.)huya\.com$/i.test(w.location.hostname) || !auto || stopped || userPaused || handoff()
        || d.hidden || w.__ntvTabMuted || el.hasAttribute('data-ntv-muted')
        || el.__ntvHuyaAudioUserChoice || el.paused || el.readyState < 2 || el.currentTime <= 0) return;
    try {
      var sdk = w.videoPlayer, inner = sdk && sdk.player;
      // Only undo the SDK's audible-autoplay fallback, never a user volume of
      // zero or an explicitly muted tab. Clear its fallback flag as well: Huya
      // otherwise reapplies muted autoplay when it reconnects the same video.
      if (!inner || inner.autoMutedPlay !== true || typeof inner.setMuted !== 'function'
          || typeof sdk.getVolume !== 'function' || sdk.getVolume() <= 0 || !el.muted) return;
      inner.setMuted(false);
      inner.autoMutedPlay = false;
      if (el.muted) el.muted = false;
      el.__ntvHuyaAudioRestored = true;
    } catch (ignore) {}
  }
  function auxiliaryHuyaVideo(el) {
    if (!/(^|\.)huya\.com$/i.test(w.location.hostname)) return false;
    // The desktop room can play a portrait advertisement beside its live
    // video. Once present, Huya's stable live node owns fullscreen selection,
    // even while its SDK temporarily hides/reparents it during a stream change.
    var main = el.ownerDocument.getElementById('hy-video');
    return !!(main && main.tagName === 'VIDEO' && main !== el);
  }
  function clickPlay(doc) {
    if (!auto || userPaused) return;
    var list = doc.querySelectorAll(playSelector);
    for (var i = 0; i < list.length && i < 24; i++) {
      var el = list[i], href = el.getAttribute('href');
      var matches = el.matches || el.webkitMatchesSelector;
      var transparentControl = matches && matches.call(el, '.vcp-bigplay');
      if (!visible(el, transparentControl) || el.disabled || /暂停|登录|购买|下载|广告/.test(el.textContent || '')
          || href && !/^(#|javascript:)/i.test(href) || !attempt(el, 'click')) continue;
      try { el.click(); clicks++; return true; } catch (ignore) {}
    }
    list = doc.querySelectorAll('button,[role="button"]');
    for (var j = 0; j < list.length && j < 64; j++) {
      var button = list[j];
      if (visible(button) && !button.disabled && /^(点击播放|开始播放|立即播放|播放视频|继续播放|观看直播|播放|Play)$/i.test((button.textContent || '').replace(/^\s+|\s+$/g, ''))
          && attempt(button, 'click')) try { button.click(); clicks++; return true; } catch (ignore) {}
    }
    return false;
  }
  function sdkPlayer(media) {
    for (var p = media.parentElement; p && p !== media.ownerDocument.body; p = p.parentElement) {
      var matches = p.matches || p.webkitMatchesSelector;
      if (matches && matches.call(p, playerSelector)) return p;
    }
    return null;
  }
  function style(el, name, value) {
    if (!el || !el.style) return;
    var saved = false;
    for (var i = 0; i < changes.length; i++) if (changes[i][0] === el) { saved = true; break; }
    if (!saved) changes.push([el, el.style.cssText]);
    el.style.setProperty(name, value, 'important');
  }
  function restore() {
    if (controlsStyle && controlsStyle.parentNode) controlsStyle.parentNode.removeChild(controlsStyle);
    if (controlsPlayer) {
      if (controlsMarker === null) controlsPlayer.removeAttribute('data-ntv-tv-fullscreen');
      else controlsPlayer.setAttribute('data-ntv-tv-fullscreen', controlsMarker);
    }
    controlsStyle = controlsPlayer = controlsMarker = null;
    for (var i = changes.length - 1; i >= 0; i--) {
      var a = changes[i];
      // Legacy WebKit cannot reliably remove !important shorthands such as
      // margin/padding/border. Restore the captured declaration block in one go.
      a[0].style.cssText = a[1];
    }
    changes = []; active = null;
  }
  function fill(el) {
    var rules = { position: 'fixed', top: '0', left: '0', right: '0', bottom: '0',
      // Percentages on a fixed element fill the viewport, including WebKit
      // versions predating vw/vh support (Android 4.0).
      width: '100%', height: '100%', 'min-width': '100%', 'min-height': '100%',
      'max-width': '100%', 'max-height': '100%', margin: '0', padding: '0', border: '0',
      transform: 'none', 'box-sizing': 'border-box', 'z-index': '2147483646', 'background-color': '#000' };
    for (var name in rules) if (Object.prototype.hasOwnProperty.call(rules, name)) style(el, name, rules[name]);
    for (var p = el.parentElement; p; p = p.parentElement) {
      style(p, 'transform', 'none'); style(p, 'filter', 'none'); style(p, 'perspective', 'none');
      style(p, 'contain', 'none'); style(p, 'overflow', 'visible'); style(p, 'z-index', '2147483646');
      style(p, 'clip-path', 'none'); style(p, 'will-change', 'auto');
    }
    style(el.ownerDocument.documentElement, 'overflow', 'hidden');
    style(el.ownerDocument.body, 'overflow', 'hidden');
  }
  function enter(media) {
    if (suppressed || handoff()) return;
    var player = sdkPlayer(media) || media;
    // Video.js can wrap the same video after our first scan. Track the
    // actual SDK container as well as the media node before skipping layout.
    if (active === media && controlsPlayer === player
        && player.getAttribute('data-ntv-tv-fullscreen') !== null) return;
    restore(); active = media; stage = 'web-fullscreen'; revision++;
    fill(player);
    // Hide player chrome only inside this fullscreen player. A scoped CSS
    // rule also covers controls inserted later, without polling or removing
    // the site's DOM, playback events, subtitles or authentication handlers.
    controlsPlayer = player;
    controlsMarker = player.getAttribute('data-ntv-tv-fullscreen');
    player.setAttribute('data-ntv-tv-fullscreen', '');
    controlsStyle = player.ownerDocument.createElement('style');
    // Some SDKs rewrite video.style on every resize/metadata callback. Keep
    // layout in a removable stylesheet so later SDK sizing cannot shrink it.
    controlsStyle.textContent = '[data-ntv-tv-fullscreen]{position:fixed!important;top:0!important;left:0!important;right:0!important;bottom:0!important;'
      + 'width:100%!important;height:100%!important;min-width:100%!important;min-height:100%!important;max-width:100%!important;max-height:100%!important;'
      + 'margin:0!important;padding:0!important;border:0!important;transform:none!important;box-sizing:border-box!important;z-index:2147483646!important}'
      + '[data-ntv-tv-fullscreen] video{position:absolute!important;top:0!important;left:0!important;'
      + 'width:100%!important;height:100%!important;min-width:100%!important;min-height:100%!important;max-width:100%!important;max-height:100%!important;'
      + 'margin:0!important;transform:none!important;object-fit:contain!important}'
      + '#dhy_player #program_list{display:none!important}'
      + '#dhy_player .player-box,#dhy_player .player-container,#dhy_player .coordinator{position:absolute!important;top:0!important;left:0!important;width:100%!important;height:100%!important;max-width:none!important;max-height:none!important}'
      + '[data-ntv-tv-fullscreen] .vjs-control-bar,'
      + '[data-ntv-tv-fullscreen] .prism-controlbar,'
      + '[data-ntv-tv-fullscreen] .plyr__controls,'
      + '[data-ntv-tv-fullscreen] .mejs__controls,'
      + '[data-ntv-tv-fullscreen] .vcp-controls-panel,'
      + '[data-ntv-tv-fullscreen] video::-webkit-media-controls,'
      + 'video[data-ntv-tv-fullscreen]::-webkit-media-controls,'
      + '[data-ntv-tv-fullscreen] #video-Control{display:none!important}';
    if (youtube) controlsStyle.textContent +=
      '[data-ntv-tv-fullscreen] .html5-video-container{position:absolute!important;inset:0!important;top:0!important;left:0!important;width:100%!important;height:100%!important}'
      + '[data-ntv-tv-fullscreen] .ytp-chrome-top,[data-ntv-tv-fullscreen] .ytp-chrome-bottom,'
      + '[data-ntv-tv-fullscreen] .ytp-gradient-top,[data-ntv-tv-fullscreen] .ytp-gradient-bottom{opacity:0!important;pointer-events:none!important}'
      + '[data-ntv-tv-fullscreen]:hover .ytp-chrome-top,[data-ntv-tv-fullscreen]:hover .ytp-chrome-bottom,'
      + '[data-ntv-tv-fullscreen].paused-mode .ytp-chrome-top,[data-ntv-tv-fullscreen].paused-mode .ytp-chrome-bottom{opacity:1!important;pointer-events:auto!important}'
      // Older WebViews reject an entire selector list containing :focus-within.
      + '[data-ntv-tv-fullscreen]:focus-within .ytp-chrome-top,[data-ntv-tv-fullscreen]:focus-within .ytp-chrome-bottom{opacity:1!important;pointer-events:auto!important}';
    (player.ownerDocument.head || player.ownerDocument.documentElement).appendChild(controlsStyle);
    if (player !== media) {
      style(media, 'width', '100%'); style(media, 'height', '100%');
      style(media, 'min-width', '100%'); style(media, 'min-height', '100%');
      style(media, 'max-width', '100%'); style(media, 'max-height', '100%');
      style(media, 'position', 'absolute'); style(media, 'top', '0'); style(media, 'left', '0');
      style(media, 'margin', '0'); style(media, 'transform', 'none');
    }
    style(media, 'object-fit', 'contain');
    try { for (var f = media.ownerDocument.defaultView.frameElement; f; f = f.ownerDocument.defaultView.frameElement) fill(f); }
    catch (ignore) {}
  }
  function wake() {
    // Network/SDK initialization can outlast the initial autoplay budget.
    // Resume only for real media/iframe changes, not chat or layout mutations.
    if (stage === 'waiting-user') { started = Date.now(); stage = 'sniffing'; }
    schedule();
  }
  function watch(doc) {
    for (var i = 0; i < watchedDocs.length; i++) if (watchedDocs[i].doc === doc) return;
    if (watchedDocs.length >= 16) return;
    if (doc !== d) {
      var childApi = doc.defaultView && doc.defaultView.__ntvTvPlayback;
      if (childApi && !childApi.state().stopped) childApi.stop();
    }
    var entry = { doc: doc, observer: null };
    watchedDocs.push(entry);
    doc.addEventListener('loadedmetadata', wake, true); doc.addEventListener('playing', wake, true);
    var Observer = doc.defaultView && doc.defaultView.MutationObserver;
    if (!Observer) return;
    entry.observer = new Observer(function (records) {
      for (var ri = 0; ri < records.length; ri++) {
        var record = records[ri];
        if (record.type === 'attributes' && /^(VIDEO|SOURCE|IFRAME)$/.test(record.target.tagName)) { wake(); return; }
        for (var ni = 0; record.addedNodes && ni < record.addedNodes.length; ni++) {
          var node = record.addedNodes[ni];
          if (node.nodeType === 1 && (/^(VIDEO|IFRAME|CANVAS)$/.test(node.tagName)
              || node.querySelector('video,iframe,canvas'))) { wake(); return; }
        }
      }
    });
    entry.observer.observe(doc, { childList: true, subtree: true, attributes: true, attributeFilter: ['src'] });
  }
  function scan() {
    scans++;
    pending = 0;
    if (stopped) return;
    if (handoff()) { if (active) restore(); stage = 'native-handoff'; return; }
    if (d.hidden) return;
    if (lastUrl !== w.location.href) {
      restore(); lastUrl = w.location.href; started = Date.now(); attempts = []; suppressed = userPaused = false;
    }
    // YouTube reuses its video as a mini-player after SPA navigation. Leave
    // search/home/login pages accessible and never stretch that preview.
    if (youtube && !(/^\/(watch|embed\/[^/]+\/?|live\/[^/]+\/?|shorts\/[^/]+\/?)$/.test(w.location.pathname)
        || w.location.hostname === 'youtu.be' && /^\/[^/]+\/?$/.test(w.location.pathname))) {
      if (active) restore(); stage = 'page'; return;
    }
    if (active && !active.ownerDocument.documentElement.contains(active)) restore();
    if (Date.now() - started > 45000 && !active) { stage = 'waiting-user'; return; }
    var docs = [d], best = null, bestArea = 0, playing = false, embedded = null;
    // Inspect same-origin frames without moving nodes or replacing the site player.
    for (var di = 0; di < docs.length && di < 8; di++) {
      var doc = docs[di], nodes = doc.querySelectorAll('video'), frames = doc.querySelectorAll('iframe');
      watch(doc);
      for (var i = 0; i < nodes.length && i < 24; i++) {
        var el = nodes[i]; if (auxiliaryHuyaVideo(el) || !visible(el)) continue;
        var rect = el.getBoundingClientRect(), area = rect.width * rect.height;
        if (area > bestArea) { best = el; bestArea = area; }
        if (!el.paused && !el.ended && el.readyState >= 2 && el.currentTime > 0) playing = true;
      }
      for (var j = 0; j < frames.length && docs.length < 8; j++) try {
        if (watchedFrames.indexOf(frames[j]) < 0 && watchedFrames.length < 16) {
          watchedFrames.push(frames[j]); frames[j].addEventListener('load', wake);
        }
        if (visible(frames[j]) && frames[j].contentDocument && docs.indexOf(frames[j].contentDocument) < 0) docs.push(frames[j].contentDocument);
      } catch (ignore) {}
      var surfaces = doc.querySelectorAll('canvas,iframe');
      for (var si = 0; si < surfaces.length && si < 16; si++) {
        var surface = surfaces[si]; if (!visible(surface)) continue;
        var box = surface.getBoundingClientRect(); if (box.width < 160 || box.height < 90) continue;
        for (var ancestor = surface.parentElement; ancestor && ancestor !== doc.body; ancestor = ancestor.parentElement) {
          var matches = ancestor.matches || ancestor.webkitMatchesSelector;
          if (matches && matches.call(ancestor, playerSelector)) { embedded = surface; break; }
        }
      }
      // VCP and several provincial SDKs hide their video until initialization
      // finishes. Enlarge the real, visible player, without unhiding the video
      // or replacing its loading/error/authentication UI.
      if (!best && !embedded) {
        var players = doc.querySelectorAll(playerSelector);
        for (var pi = 0; pi < players.length && pi < 16; pi++) {
          var waitingVideo = players[pi].querySelector('video');
          if (waitingVideo && auxiliaryHuyaVideo(waitingVideo)) continue;
          var playerBox = players[pi].getBoundingClientRect();
          if (waitingVideo && visible(players[pi]) && playerBox.width >= 160 && playerBox.height >= 90) {
            embedded = waitingVideo; break;
          }
        }
      }
    }
    if (best) {
      restoreHuyaAudio(best);
      if (auto && !playing) {
        // Calling play() on an empty element can leave paused=false forever;
        // the provider's start button must initialize its SDK/source first.
        var hasSource = best.currentSrc || best.src || best.srcObject || best.querySelector('source[src]');
        // SDKs may already expose a source while their original start button
        // still has to position the live edge or finish player initialization.
        // Let that control run before touching the underlying video element.
        var clicked = false;
        for (var n = 0; n < docs.length; n++) if (clickPlay(docs[n])) { clicked = true; break; }
        if (!clicked && hasSource && (!sdkPlayer(best) || Date.now() - started >= 2500)) play(best);
      }
      enter(best);
    } else {
      if (auto) for (var k = 0; k < docs.length; k++) clickPlay(docs[k]);
      if (embedded) enter(embedded);
    }
  }
  function schedule() { if (!stopped && !pending) pending = w.setTimeout(scan, 200); }
  function gesture(e) { if (e.isTrusted !== false) { lastGesture = Date.now(); userPaused = false; } }
  function pause(e) { if (Date.now() - lastGesture < 1000 && e.target.tagName === 'VIDEO') userPaused = true; }
  function audioChoice(e) {
    if (lastGesture > 0 && Date.now() - lastGesture < 1000 && e.target.tagName === 'VIDEO')
      e.target.__ntvHuyaAudioUserChoice = true;
  }
  function exit() { suppressed = true; restore(); stage = 'web-exited'; }
  function stop() {
    stopped = true; w.clearInterval(timer); w.clearTimeout(pending); restore();
    for (var i = 0; i < watchedDocs.length; i++) {
      var entry = watchedDocs[i]; if (entry.observer) entry.observer.disconnect();
      entry.doc.removeEventListener('loadedmetadata', wake, true); entry.doc.removeEventListener('playing', wake, true);
    }
    for (var j = 0; j < watchedFrames.length; j++) watchedFrames[j].removeEventListener('load', wake);
    watchedDocs = []; watchedFrames = [];
    d.removeEventListener('loadedmetadata', schedule, true); d.removeEventListener('playing', schedule, true);
    d.removeEventListener('pause', pause, true); d.removeEventListener('mousedown', gesture, true);
    d.removeEventListener('volumechange', audioChoice, true);
    d.removeEventListener('touchstart', gesture, true); d.removeEventListener('keydown', gesture, true);
    w.removeEventListener('pagehide', pagehide);
  }
  function pagehide(e) { if (!e.persisted) stop(); }
  w.__ntvTvPlayback = {
    document: d, version: version, versionCode: versionCode,
    configure: function (value) { if (auto !== value) { auto = value; started = Date.now(); attempts = []; } schedule(); },
    exit: exit, stop: stop, refresh: wake,
    fullscreen: function () { suppressed = false; started = Date.now(); wake(); },
    state: function () { return { version: version, stage: stage, stopped: stopped, fullscreen: !!active, revision: revision, autoPlay: auto, webOnly: onlyWeb,
      clicks: clicks, scans: scans, hidden: !!d.hidden, currentDocument: d === w.document, elapsedMs: Date.now() - started }; }
  };
  d.addEventListener('loadedmetadata', schedule, true); d.addEventListener('playing', schedule, true);
  d.addEventListener('pause', pause, true); d.addEventListener('mousedown', gesture, true);
  d.addEventListener('volumechange', audioChoice, true);
  d.addEventListener('touchstart', gesture, true); d.addEventListener('keydown', gesture, true);
  w.addEventListener('pagehide', pagehide);
  timer = w.setInterval(scan, 1500); schedule();
})(window, document);
