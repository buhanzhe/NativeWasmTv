(function(){
 function metadata(v){var out={};if(v&&typeof v==='object')Object.keys(v).forEach(function(k){var x=v[k];if(typeof x==='number'||typeof x==='boolean'||typeof x==='string'&&x.length<80&&!/url|src|source/i.test(k))out[k]=typeof x==='number'&&!isFinite(x)?String(x):x;});return out;}
 var send=Worker.prototype.postMessage;
 Worker.prototype.postMessage=function(v,transfer){if(!this.__audit){this.__audit=true;this.addEventListener('message',function(e){var x=e.data;console.log('MSE worker output '+JSON.stringify({top:metadata(x),data:metadata(x&&x.data)}));});}console.log('MSE worker input '+JSON.stringify(metadata(v)));return send.apply(this,arguments);};
 var original=SourceBuffer.prototype.appendBuffer,count=0;
 function number(u,p){return ((u[p]*16777216)+(u[p+1]<<16)+(u[p+2]<<8)+u[p+3]);}
 function boxes(u,a,b,out){
  for(var p=a;p+8<=b;){var n=number(u,p),t=String.fromCharCode(u[p+4],u[p+5],u[p+6],u[p+7]);if(n<8||p+n>b)return;
   if(t==='tfdt'){var at=p+12;out.push({type:t,stamp:u[p+8]===1?number(u,at)*4294967296+number(u,at+4):number(u,at)});}
   if(t==='tfhd')out.push({type:t,track:number(u,p+12)});
   if(t==='mdhd')out.push({type:t,scale:number(u,p+(u[p+8]===1?28:20))});
   if(['moof','traf','moov','trak','mdia'].indexOf(t)>=0)boxes(u,p+8,p+n,out);p+=n;
  }
 }
 SourceBuffer.prototype.appendBuffer=function(value){
  var u=ArrayBuffer.isView(value)?new Uint8Array(value.buffer,value.byteOffset,value.byteLength):new Uint8Array(value),info=[];
  boxes(u,0,u.length,info);
  if(info.length&&count++<60)console.log('MSE input metadata '+JSON.stringify({id:this._bufferId,offset:this.timestampOffset,bytes:u.length,boxes:info}));
  return original.call(this,value);
 };
})();
