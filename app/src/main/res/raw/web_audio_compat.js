(function () {
  "use strict";
  var host = location.hostname.toLowerCase();
  if (!window.__ntvWebAudio) {
    window.__ntvWebAudio = true;
    // Some players initialize volume:0. Correct only their initial
    // playback; subsequent user volume/mute changes must remain untouched.
    var interacted = false;
    function interaction() { interacted = true; }
    document.addEventListener("touchstart", interaction, true);
    document.addEventListener("mousedown", interaction, true);
    document.addEventListener("keydown", interaction, true);
    function initialVolume(video) {
      if (!video || (video.tagName !== "VIDEO" && video.tagName !== "AUDIO")
          || video.__ntvInitialAudio || window.__ntvMediaPause) return;
      video.__ntvInitialAudio = true;
      if (!interacted && !window.__ntvMediaPause && !video.muted && video.volume === 0)
        video.volume = 0.6;
    }
    document.addEventListener("playing", function (event) { initialVolume(event.target); }, true);
    var videos = document.querySelectorAll("video,audio");
    for (var i = 0; i < videos.length; i++) {
      if (!videos[i].paused && videos[i].readyState >= 2) initialVolume(videos[i]);
    }
  }
  if (host === "www.xjtvs.com.cn" && /^\/column\/tv\//.test(location.pathname)
      && !window.__ntvXjHls && window.HTMLMediaElement) {
    window.__ntvXjHls = true;
    var original = HTMLMediaElement.prototype.canPlayType;
    HTMLMediaElement.prototype.canPlayType = function (type) {
      // DPlayer prefers Android native HLS, which loses audio on the tested
      // Android 9 WebView. Let DPlayer own its bundled HLS.js/MSE lifecycle.
      // Retain native playback when the site's HLS.js or MSE is unavailable.
      if (/^application\/(x-mpegurl|vnd\.apple\.mpegurl)(?:\s*;|$)/i.test(String(type))
          && window.Hls && typeof Hls.isSupported === "function" && Hls.isSupported())
        return "";
      return original.apply(this, arguments);
    };
  }
})();
