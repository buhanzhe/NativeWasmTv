// Native KitKat only. Keep transformed ESM dependencies on their original origin.
(function(){
  var marker='__ntv_qjs_module=1',resolve=System.resolve,queue=[],ready=false,chain=Promise.resolve();
  System.resolve=function(id,parent){
    var url=resolve.call(this,id,parent);
    if(!/^https?:/.test(url))throw new Error('Unsupported legacy module URL');
    if(url.indexOf(marker)<0){var hash=url.indexOf('#'),tail=hash<0?'':url.slice(hash);if(hash>=0)url=url.slice(0,hash);url+=(url.indexOf('?')<0?'?':'&')+marker;url+=tail;}
    return url;
  };
  function load(url){chain=chain.then(function(){return System.import(url);}).catch(function(e){console.error('Legacy module: '+e);});return chain;}
  window.__ntvImportModule=function(url,async){if(async)return System.import(url);if(ready)return load(url);queue.push(url);};
  document.addEventListener('DOMContentLoaded',function(){ready=true;for(var i=0;i<queue.length;i++)load(queue[i]);queue=[];},false);
})();
