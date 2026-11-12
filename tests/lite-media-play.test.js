// Playback contract only; real channel tests establish decoded output.
const vm=require('node:vm'),fs=require('node:fs'),assert=require('node:assert/strict'),path=require('node:path');
const context=vm.createContext({});
vm.runInContext(`
var jobs=[],commands=[],events=[];globalThis.__IntrinsicPromise=Promise;
function setTimeout(fn){jobs.push(fn);}function flush(){while(jobs.length)jobs.shift()();}
function Node(){this._nativePaused=true;this._ready=0;}function HTMLMediaElement(){Node.call(this);}
HTMLMediaElement.prototype=Object.create(Node.prototype);HTMLMediaElement.prototype.constructor=HTMLMediaElement;
function HTMLVideoElement(){HTMLMediaElement.call(this);}HTMLVideoElement.prototype=Object.create(HTMLMediaElement.prototype);
Node.prototype.play=function(){};Node.prototype.pause=function(){};
Node.prototype.dispatchEvent=function(e){events.push(e.type);};
Object.defineProperties(Node.prototype,{paused:{configurable:true,get:function(){return this._nativePaused;}},readyState:{configurable:true,get:function(){return this._ready;}}});
function Event(type){this.type=type;}function DOMException(message,name){this.message=message;this.name=name;}
function __host(raw){commands.push(JSON.parse(raw));}
function __directMediaSource(node){return node.src;}
`,context);
vm.runInContext(fs.readFileSync(path.join(__dirname,'../browser-core/litebrowser/src/main/assets/media-play.js'),'utf8'),context);
(async()=>{
 vm.runInContext(`var video=new HTMLVideoElement();video.src='first';__mediaSourceAssigned(video,video.src);var firstSettled=false,first=video.play().then(function(){firstSettled=true;});flush();`,context);
 await Promise.resolve();assert.equal(context.firstSettled,false,'a play request cannot invent native readiness');
 vm.runInContext(`video._ready=4;__nativeMediaPlayStarted(video);flush();`,context);await context.first;
 vm.runInContext(`video.pause();flush();var pausedCommands=commands.length;video.pause();flush();`,context);
 assert.equal(vm.runInContext('commands.length',context),context.pausedCommands,'an already paused element must not issue another decoder pause');
 vm.runInContext(`var resumed=false,resume=video.play().then(function(){resumed=true;});flush();`,context);await context.resume;
 assert.equal(context.resumed,true,'an already confirmed ready decoder can resume');
 vm.runInContext(`video.src='second';video._ready=0;__mediaSourceAssigned(video,video.src);var nextSettled=false,nextError='',next=video.play().then(function(){nextSettled=true;},function(error){nextError=error.name;});video.dispatchEvent(new Event('playing'));flush();`,context);
 await Promise.resolve();assert.equal(context.nextSettled,false,'a new source needs a fresh native callback');
 vm.runInContext(`video.pause();flush();`,context);await context.next;
 assert.equal(context.nextError,'AbortError','manual pause must still abort a pending play');
 vm.runInContext(`events.length=0;var mixed=new HTMLVideoElement();mixed.src='mixed';__mediaSourceAssigned(mixed,mixed.src);mixed.play().catch(function(){});mixed.pause();mixed.play().catch(function(){});flush();`,context);
 assert.equal(vm.runInContext('events.includes("pause")',context),false,'an obsolete queued pause cannot override the later play intent');
 vm.runInContext(`events.length=0;mixed.pause();flush();`,context);assert.equal(vm.runInContext('events.includes("pause")',context),true,'the latest manual pause remains observable');
 vm.runInContext(`events.length=0;mixed.play().catch(function(){});mixed.src='changed';__mediaSourceAssigned(mixed,mixed.src);flush();`,context);assert.equal(vm.runInContext('events.includes("play")',context),false,'a previous source cannot deliver its queued play event');
 console.log('PASS real media-play implementation: native-only initial readiness, ready resume, no-op pause, source isolation and explicit pause');
})().catch(error=>{console.error(error);process.exitCode=1;});
