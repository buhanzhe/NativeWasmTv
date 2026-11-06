(function (w, d) {
  'use strict';
  var api = w.__ntvTvPlayback, checks = [], player, video, original, videoOriginal;
  function sameStyle(el, originalText) {
    var expected = d.createElement('div').style; expected.cssText = originalText;
    if (el.style.length !== expected.length) return false;
    for (var i = 0; i < expected.length; i++) {
      var key = expected[i];
      if (el.style.getPropertyValue(key) !== expected.getPropertyValue(key)
          || el.style.getPropertyPriority(key) !== expected.getPropertyPriority(key)) return false;
    }
    return true;
  }
  function check(ok, name) { if (!ok) throw new Error(name); checks.push(name); }
  function done(error) {
    if (api) api.stop();
    var result = {pass:!error, error:error ? String(error.message || error) : '', checks:checks};
    w.__youtubeCheckResult = result;
    if (w.YouTubeCheck) w.YouTubeCheck.result(JSON.stringify(result));
  }
  function step(fn) { w.setTimeout(function () { try { fn(); } catch (error) { done(error); } }, 350); }
  try {
    check(!!api, 'helper installed');
    api.exit();
    d.body.innerHTML = '<div id="login">Private video: sign in</div>';
    api.fullscreen();
    step(function () {
      check(!api.state().fullscreen && d.getElementById('login').offsetHeight > 0, 'login without video stays accessible');
      d.body.innerHTML = '<div id="movie_player" class="html5-video-player paused-mode" style="position:relative;width:320px;height:180px;margin:9px">'
        + '<div class="html5-video-container" style="width:320px;height:180px;position:relative;left:13px">'
        + '<video style="width:320px;height:180px"></video></div>'
        + '<div class="ytp-caption-window-container">Subtitles</div><div class="ytp-error">Player notice</div>'
        + '<div class="ytp-chrome-bottom"><button class="ytp-play-button" aria-label="Play">Play</button></div></div>';
      player = d.getElementById('movie_player'); video = player.querySelector('video');
      original = player.style.cssText; videoOriginal = video.style.cssText;
      w.__fixtureClicks = 0;
      player.querySelector('button').onclick = function () { w.__fixtureClicks++; };
      api.refresh();
      step(function () {
        check(api.state().fullscreen && api.state().webOnly, 'YouTube original SDK fullscreen');
        var box = player.getBoundingClientRect(), container = player.querySelector('.html5-video-container').getBoundingClientRect();
        check(Math.abs(box.width - w.innerWidth) < 3 && Math.abs(box.height - w.innerHeight) < 3, 'player fills viewport');
        check(Math.abs(container.width - box.width) < 3 && Math.abs(container.left - box.left) < 3, 'SDK video container resized');
        check(w.getComputedStyle(player.querySelector('.ytp-caption-window-container')).display !== 'none', 'captions remain visible');
        check(w.getComputedStyle(player.querySelector('.ytp-error')).display !== 'none', 'player notices remain visible');
        check(w.getComputedStyle(player.querySelector('.ytp-chrome-bottom')).opacity === '1', 'paused controls accessible');
        check(w.__fixtureClicks === 0, 'autoplay off does not click SDK');
        video.style.width = '111px'; video.style.height = '62px';
        check(Math.abs(video.getBoundingClientRect().width - box.width) < 3, 'SDK inline resize cannot shrink video');
        api.exit();
        check(sameStyle(player, original) && sameStyle(video, videoOriginal), 'exit restores original inline layout');
        api.refresh();
        step(function () {
          check(!api.state().fullscreen, 'exit stays exited');
          api.fullscreen();
          step(function () {
            check(api.state().fullscreen, 'explicit fullscreen reenters');
            w.history.pushState({}, '', '/results?search_query=fixture'); api.refresh();
            step(function () {
              check(!api.state().fullscreen && api.state().stage === 'page', 'SPA search releases miniplayer');
              w.history.pushState({}, '', '/watch?v=fixture2'); api.refresh();
              step(function () {
                check(api.state().fullscreen, 'SPA next video reenters');
                api.stop();
                check(!player.hasAttribute('data-ntv-tv-fullscreen') && sameStyle(player, original), 'stop removes fullscreen styles');
                done(null);
              });
            });
          });
        });
      });
    });
  } catch (error) { done(error); }
})(window, document);
