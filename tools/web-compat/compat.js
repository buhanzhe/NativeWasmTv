// DOM-only shims. Standard library APIs are supplied by the pinned core-js bundle.
(function (w, d) {
  'use strict';
  function method(target, name, fn) {
    if (!target[name]) Object.defineProperty(target, name, {value:fn, configurable:true, writable:true});
  }
  if(w.Element){method(w.Element.prototype,'matches',w.Element.prototype.webkitMatchesSelector||function(selector){var list=(this.ownerDocument||d).querySelectorAll(selector);for(var i=0;i<list.length;i++)if(list[i]===this)return true;return false;});
    method(w.Element.prototype,'closest',function(selector){var e=this;while(e&&e.nodeType===1){if(e.matches(selector))return e;e=e.parentElement;}return null;});}
  if(typeof w.CustomEvent!=='function'){w.CustomEvent=function(name,params){params=params||{};var e=d.createEvent('CustomEvent');e.initCustomEvent(name,!!params.bubbles,!!params.cancelable,params.detail);return e;};w.CustomEvent.prototype=w.Event.prototype;}
  if(!w.requestAnimationFrame){w.requestAnimationFrame=w.webkitRequestAnimationFrame||function(fn){return setTimeout(function(){fn(w.performance?w.performance.now():Date.now());},16);};w.cancelAnimationFrame=w.webkitCancelAnimationFrame||clearTimeout;}
  w.__ntvTvCompatibility={version:2,coreJs:'3.50.0',installedMs:w.performance?w.performance.now():0,host:w.location.hostname};
  w.__ntvCompatErrors=[];
  w.addEventListener('error',function(e){if(e.message&&w.__ntvCompatErrors.length<8)w.__ntvCompatErrors.push(String(e.message).slice(0,180));},false);
})(window,document);
