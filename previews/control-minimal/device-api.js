/* ES5 adapter: original management scripts talk directly to the MI 6 API. */
(function(){
  'use strict';
  var originalOpen=XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open=function(method,url){
    var args=Array.prototype.slice.call(arguments);
    if(typeof url==='string' && url.indexOf('/api/')===0)args[1]='http://192.168.0.106:9966'+url;
    return originalOpen.apply(this,args);
  };
}());
