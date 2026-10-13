// Synthetic regression only. Captured official-page API19 tests are separate.
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const bundle=fs.readFileSync(__dirname+'/page-adapters.js','utf8');
let hainan=bundle;
// Original test fixture, not redistributed website source. Substitute only the
// expected source fingerprint in the test realm; production constant stays fixed.
function getOtherHtml(url,callback,update) {
  var xhr=new XMLHttpRequest();
  xhr.onreadystatechange=function(){
    if(xhr.readyState===4&&xhr.status===200)callback(true,xhr.responseText);
    else if(xhr.status!==0&&xhr.status!==200)callback(false,xhr.responseText);
  };
  xhr.open('GET',url+(update?'?v='+(new Date()).getTime():''),true);xhr.send(null);
}
const original=getOtherHtml.toString();let hash=5381;for(const c of original.replace(/\r\n/g,'\n'))hash=((hash*33)^c.charCodeAt(0))>>>0;
assert(hainan.includes('hash !== 3279651206'));hainan=hainan.replace('hash !== 3279651206','hash !== '+hash);
function realm(host='www.hnntv.cn',path='/live.html',source=original){
 const requests=[],events={},c={location:{hostname:host,pathname:path},__ntvTvCompatibility:{},document:{readyState:'loading',addEventListener:(name,fn)=>events[name]=fn}};
 function XHR(){this.readyState=0;this.code=200;requests.push(this);}
 Object.defineProperty(XHR.prototype,'status',{get(){if(this.error)throw this.error;if(this.readyState<2)throw Object.assign(new Error('early'),{name:'InvalidStateError'});return this.code;}});
 Object.defineProperty(XHR.prototype,'responseText',{get(){assert.strictEqual(this.readyState,4);return 'body';}});
 XHR.prototype.open=function(...args){this.args=args;this.readyState=1;this.onreadystatechange();};XHR.prototype.send=function(body){this.sent=body;};
 c.XMLHttpRequest=XHR;c.window=c;vm.createContext(c);vm.runInContext(hainan,c);vm.runInContext(source,c);const before=c.getOtherHtml;if(events.DOMContentLoaded)events.DOMContentLoaded();return {c,requests,events,before};
}
const r=realm();assert.notStrictEqual(r.c.getOtherHtml,r.before);
for(const code of [0,200,401,403,404,500]){const calls=[];r.c.getOtherHtml('/sample',(...args)=>calls.push(args),false);const x=r.requests.at(-1);assert.deepStrictEqual(x.args,['GET','/sample',true]);assert.strictEqual(x.sent,null);x.code=code;for(const state of [2,3]){x.readyState=state;x.onreadystatechange();assert.strictEqual(calls.length,0);}x.readyState=4;x.onreadystatechange();assert.deepStrictEqual(calls,code===0?[]:[[code===200,'body']]);}
r.c.getOtherHtml('/refresh',()=>{},true);const x=r.requests.at(-1);assert(/^\/refresh\?v=\d+$/.test(x.args[1]));x.readyState=4;x.error=Object.assign(new Error('denied'),{name:'SecurityError'});assert.throws(()=>x.onreadystatechange(),e=>e===x.error);
const nativeXHR=r.c.XMLHttpRequest;
for(const name of ['InvalidStateError','TypeError']){x.error=Object.assign(new Error('late'),{name});assert.throws(()=>x.onreadystatechange(),e=>e===x.error);}
const callbackError=new Error('callback');r.c.getOtherHtml('/callback',()=>{throw callbackError;});const callbackXhr=r.requests.at(-1);callbackXhr.readyState=4;assert.throws(()=>callbackXhr.onreadystatechange(),e=>e===callbackError);
const same=r.c.getOtherHtml;r.events.DOMContentLoaded();assert.strictEqual(r.c.getOtherHtml,same);assert.strictEqual(r.c.XMLHttpRequest,nativeXHR);
for(const [host,path,source] of [['example.org','/live.html',original],['www.hnntv.cn','/other.html',original],['www.hnntv.cn','/live.html',original.replace('xhr.send(null)','xhr.send("changed")')],['www.hnntv.cn','/live.html',original.replace("'?v='","'?v= '")]]){const t=realm(host,path,source);assert.strictEqual(t.c.getOtherHtml,t.before);}
console.log('PASS Hainan fingerprinted helper: exact scope, early readyState guard, HTTP errors visible, native XHR unchanged');
