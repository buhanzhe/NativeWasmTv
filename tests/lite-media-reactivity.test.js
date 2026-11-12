const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const vue=fs.readFileSync('browser-core/litebrowser/src/androidTest/assets/vue-2.6.14.min.js','utf8');
const source=fs.readFileSync('app/src/main/assets/userscripts/ntv-media-reactivity.user.js','utf8');
async function check(existing){
 const context={};vm.createContext(context);vm.runInContext('var globalThis=this;function Node(){};var owned=new WeakMap(),writes=0;',context);
 if(existing)vm.runInContext("Object.defineProperty(Node.prototype,'__vue__',{configurable:true,get:function(){return owned.get(this);},set:function(v){writes++;owned.set(this,v);}});",context);
 vm.runInContext(vue,context);vm.runInContext(source,context);
 vm.runInContext(`
 var component=new Vue({data:function(){return {hls:null,ordinary:{value:1}};}}),node=new Node();node.__vue__=component;
 var notifications=0;component.$watch('hls',function(){notifications++;});
 function Hls(){this.cells=Array.from({length:400},function(){return {character:'',pen:{foreground:'white',underline:false}};});}
 Hls.prototype.loadSource=function(){return 17;};Hls.prototype.attachMedia=function(){};Hls.prototype.on=function(){};Hls.prototype.destroy=function(){};
 var player=new Hls();component.hls=player;
 `,context);
 assert.equal(vm.runInContext('node.__vue__===component && component.hls===player',context),true);
 assert.equal(vm.runInContext('Object.isExtensible(player) && player.loadSource()===17',context),true);
 assert.equal(vm.runInContext('!!player.__ob__ || !!player.cells.__ob__ || !!player.cells[0].pen.__ob__',context),false);
 assert.equal(vm.runInContext('!!component.ordinary.__ob__',context),true);
 await vm.runInContext('Vue.nextTick()',context);assert.equal(vm.runInContext('notifications',context),1);
 vm.runInContext('component.hls={plain:{value:2}};',context);assert.equal(vm.runInContext('!!component.hls.plain.__ob__',context),true);
 if(existing)assert.equal(vm.runInContext('writes',context),1);
}
(async()=>{await check(false);await check(true);console.log('PASS real Vue2: mutable player identity and methods, decoder internals stay raw, component notifications/ordinary data and existing DOM hooks preserved');})().catch(e=>{console.error(e);process.exitCode=1;});
