// Rebuild the standalone userscript from pinned ES5 vendor files and site scope.
const fs=require('fs'),path=require('path'),crypto=require('crypto');
const root=path.resolve(__dirname,'../..');
for(const vendor of JSON.parse(fs.readFileSync(path.join(__dirname,'vendors.json'),'utf8'))){
  const bytes=fs.readFileSync(path.join(root,'third_party/web-compat',vendor.name,vendor.file));
  if(crypto.createHash('sha256').update(bytes).digest('hex')!==vendor.sha256)throw Error('Vendor checksum mismatch: '+vendor.name);
}
const hosts=fs.readFileSync(path.join(__dirname,'hosts.txt'),'utf8').trim().split(/\r?\n/).sort();
function license(name){return '\n/*! '+name+'\n'+fs.readFileSync(path.join(root,'third_party/web-compat',name,'LICENSE.txt'),'utf8').replace(/\*\//g,'* /')+'\n*/\n';}
let out='// ==UserScript==\n// @name nTv Android 4.4 television compatibility\n// @namespace https://github.com/buhanzhe/NativeWasmTv\n// @version 1.1.0\n// @description Local core-js 3 API shims for webview.txt TV sites; keeps native media capability checks.\n';
for(const host of hosts)out+='// @match *://'+host+'/*\n';
out+='// @run-at document-start\n// @grant none\n// ==/UserScript==\n';
out+='(function(){var sites='+JSON.stringify(hosts)+';if((!window.__ntvNativeTvCompatibility&&sites.indexOf(location.hostname.toLowerCase())<0)||window.__ntvTvCompatibility)return;';
out+='if("noModule" in document.createElement("script"))return;\n';
for(const file of ['core-js/legacy.min.js','whatwg-fetch/fetch.umd.js']) {
  out+=license(file.split('/')[0]);
  out+='\n(function(){var define,module,exports;\n'+fs.readFileSync(path.join(root,'third_party/web-compat',file),'utf8').replace(/\/\/# sourceMappingURL=.*$/gm,'')+'\n}).call(window);\n';
}
out+=fs.readFileSync(path.join(__dirname,'compat.js'),'utf8')+'\n})();\n';
out+=license('blueimp-md5');
out+='\n(function(){if(!window.__ntvTvCompatibility||window.__ntvMd5)return;var define,module,exports;\n'+
  fs.readFileSync(path.join(root,'third_party/web-compat/blueimp-md5/md5.min.js'),'utf8').replace(/\/\/# sourceMappingURL=.*$/gm,'')+
  '\n}).call({set md5(value){window.__ntvMd5=value;}});\n';
out+=fs.readFileSync(path.join(__dirname,'sites.js'),'utf8');
const dest=path.join(root,'app/src/main/assets/control/userscripts/kitkat-tv.user.js');
fs.mkdirSync(path.dirname(dest),{recursive:true});fs.writeFileSync(dest,out);
console.log('Built',hosts.length,'hosts,',Buffer.byteLength(out),'bytes');
