// ==UserScript==
// @name 播放器对象响应化兼容
// @version 1.0.0
// @match *://app.gztv.com/*
// @run-at document-start
// @grant none
// ==/UserScript==
(function(g){
'use strict';
if(typeof Symbol!=='function'||typeof WeakMap!=='function'||!g.Node)return;
var values=new WeakMap(),prepared=new WeakSet(),original=Object.getOwnPropertyDescriptor(Node.prototype,'__vue__');
if(original&&!original.configurable)return;
if(original&&Object.prototype.hasOwnProperty.call(original,'value'))return;
function media(value){
 if(!value||typeof value!=='object'||!Object.isExtensible(value))return;
 // Keep the live SDK instance mutable, while excluding its decoder internals
 // from Vue's recursive plain-object observation. Component state still notifies.
 if(typeof value.loadSource==='function'&&typeof value.attachMedia==='function'&&typeof value.on==='function'&&typeof value.destroy==='function'&&Object.prototype.toString.call(value)==='[object Object]')
  Object.defineProperty(value,Symbol.toStringTag,{value:'Hls',configurable:true});
}
function prepare(vm){
 if(!vm||!vm._isVue||!vm.$data||prepared.has(vm))return;
 prepared.add(vm);var data=vm.$data;
 Object.keys(data).slice(0,64).forEach(function(key){
  var descriptor=Object.getOwnPropertyDescriptor(data,key);
  if(!descriptor||!descriptor.configurable||typeof descriptor.set!=='function')return;
  var setter=descriptor.set;
  descriptor.set=function(value){try{media(value);}catch(ignore){}return setter.call(this,value);};
  Object.defineProperty(data,key,descriptor);
 });
}
Object.defineProperty(Node.prototype,'__vue__',{configurable:true,enumerable:original?original.enumerable:false,
 get:function(){return original&&original.get?original.get.call(this):values.get(this);},
 set:function(vm){try{prepare(vm);}catch(ignore){}if(original&&original.set)original.set.call(this,vm);else values.set(this,vm);}
});
})(globalThis);
