(function(root,factory){
  var api=factory();
  if(typeof module==='object'&&module.exports)module.exports=api;
  root.NtvMp4Finalizer=api;
})(typeof globalThis!=='undefined'?globalThis:this,function(){
  'use strict';
  var containers={moov:1,trak:1,mdia:1,minf:1,stbl:1,mvex:1,moof:1,traf:1,edts:1};

  function view(data){return new DataView(data.buffer,data.byteOffset,data.byteLength)}
  function u32(data,offset){return view(data).getUint32(offset,false)}
  function i32(data,offset){return view(data).getInt32(offset,false)}
  function u64(data,offset){return u32(data,offset)*4294967296+u32(data,offset+4)}
  function put32(data,offset,value){view(data).setUint32(offset,Math.max(0,Math.floor(value))>>>0,false)}
  function put64(data,offset,value){value=Math.max(0,Math.floor(value));put32(data,offset,Math.floor(value/4294967296));put32(data,offset+4,value%4294967296)}
  function text(data,offset,length){var value='';for(var i=0;i<length;i++)value+=String.fromCharCode(data[offset+i]);return value}
  function bytes(value){if(value instanceof Uint8Array)return value;return new Uint8Array(value)}
  function concat(parts){var size=0;for(var i=0;i<parts.length;i++)size+=parts[i].byteLength;var result=new Uint8Array(size),offset=0;for(var j=0;j<parts.length;j++){result.set(parts[j],offset);offset+=parts[j].byteLength}return result}
  function typeBytes(type){return new Uint8Array([type.charCodeAt(0),type.charCodeAt(1),type.charCodeAt(2),type.charCodeAt(3)])}
  function makeBox(type,parts){parts=parts||[];var payload=concat(parts),result=new Uint8Array(payload.byteLength+8);put32(result,0,result.byteLength);result.set(typeBytes(type),4);result.set(payload,8);return result}
  function fullBox(type,version,flags,parts){var header=new Uint8Array(4);header[0]=version;header[1]=flags>>>16&255;header[2]=flags>>>8&255;header[3]=flags&255;return makeBox(type,[header].concat(parts||[]))}
  function uints(values){var result=new Uint8Array(values.length*4);for(var i=0;i<values.length;i++)put32(result,i*4,values[i]);return result}

  function parseBoxes(data,start,end){
    var boxes=[],position=start||0;end=end==null?data.byteLength:end;
    while(position+8<=end){
      var size=u32(data,position),type=text(data,position+4,4),header=8;
      if(size===1){if(position+16>end)break;size=u64(data,position+8);header=16}else if(size===0)size=end-position;
      if(size<header||position+size>end)break;
      var box={type:type,start:position,size:size,header:header,end:position+size,children:[]};
      if(containers[type])box.children=parseBoxes(data,position+header,position+size);
      boxes.push(box);position+=size;
    }
    return boxes;
  }
  function child(box,type){for(var i=0;i<box.children.length;i++)if(box.children[i].type===type)return box.children[i];return null}
  function descendants(box,type,result){result=result||[];if(box.type===type)result.push(box);for(var i=0;i<box.children.length;i++)descendants(box.children[i],type,result);return result}
  function raw(data,box){return data.slice(box.start,box.end)}
  function payloadOffset(box){return box.start+box.header}

  function readTrackId(data,tkhd){var p=payloadOffset(tkhd),version=data[p];return u32(data,p+(version?20:12))}
  function readTimescale(data,box){var p=payloadOffset(box),version=data[p];return u32(data,p+(version?20:12))}
  function readHandler(data,hdlr){return text(data,payloadOffset(hdlr)+8,4)}
  function patchDuration(data,box,type,duration){
    var result=raw(data,box),p=box.header,version=result[p],offset;
    if(type==='tkhd')offset=p+(version?28:20);else offset=p+(version?24:16);
    if(version)put64(result,offset,duration);else put32(result,offset,duration);
    return result;
  }

  function parseTrex(data,box){var p=payloadOffset(box);return{trackId:u32(data,p+4),duration:u32(data,p+12),size:u32(data,p+16),flags:u32(data,p+20)}}
  function parseTfhd(data,box){
    var p=payloadOffset(box),flags=data[p+1]<<16|data[p+2]<<8|data[p+3],position=p+8,result={trackId:u32(data,p+4),duration:0,size:0,flags:0};
    if(flags&1)position+=8;if(flags&2)position+=4;if(flags&8){result.duration=u32(data,position);position+=4}if(flags&16){result.size=u32(data,position);position+=4}if(flags&32)result.flags=u32(data,position);
    return result;
  }
  function parseTrun(data,box,defaults){
    var p=payloadOffset(box),version=data[p],flags=data[p+1]<<16|data[p+2]<<8|data[p+3],count=u32(data,p+4),position=p+8,dataOffset=null,firstFlags=null,samples=[];
    if(flags&1){dataOffset=i32(data,position);position+=4}if(flags&4){firstFlags=u32(data,position);position+=4}
    for(var i=0;i<count;i++){
      var sample={duration:defaults.duration,size:defaults.size,flags:i===0&&firstFlags!=null?firstFlags:defaults.flags,cto:0};
      if(flags&256){sample.duration=u32(data,position);position+=4}if(flags&512){sample.size=u32(data,position);position+=4}if(flags&1024){sample.flags=u32(data,position);position+=4}if(flags&2048){sample.cto=version?i32(data,position):u32(data,position);position+=4}
      if(!sample.duration||!sample.size)throw new Error('MP4 分片缺少采样时长或大小');
      samples.push(sample);
    }
    return{dataOffset:dataOffset,samples:samples};
  }

  function rle(values){var entries=[];for(var i=0;i<values.length;i++){var last=entries[entries.length-1];if(last&&last.value===values[i])last.count++;else entries.push({count:1,value:values[i]})}return entries}
  function stts(track){var entries=rle(track.samples.map(function(sample){return sample.duration})),values=[entries.length];for(var i=0;i<entries.length;i++)values.push(entries[i].count,entries[i].value);return fullBox('stts',0,0,[uints(values)])}
  function ctts(track){var offsets=track.samples.map(function(sample){return sample.cto||0}),hasOffset=offsets.some(function(value){return value!==0});if(!hasOffset)return null;var entries=rle(offsets),negative=offsets.some(function(value){return value<0}),result=new Uint8Array(4+entries.length*8);put32(result,0,entries.length);for(var i=0;i<entries.length;i++){put32(result,4+i*8,entries[i].count);view(result).setInt32(8+i*8,entries[i].value,false)}return fullBox('ctts',negative?1:0,0,[result])}
  function stsc(track){var entries=[];for(var i=0;i<track.chunks.length;i++){var count=track.chunks[i].sampleCount,last=entries[entries.length-1];if(!last||last.samples!==count)entries.push({first:i+1,samples:count})}var values=[entries.length];for(var j=0;j<entries.length;j++)values.push(entries[j].first,entries[j].samples,1);return fullBox('stsc',0,0,[uints(values)])}
  function stsz(track){return fullBox('stsz',0,0,[uints([0,track.samples.length].concat(track.samples.map(function(sample){return sample.size})))])}
  function stco(track){return fullBox('stco',0,0,[uints([track.chunkOffsets.length].concat(track.chunkOffsets))])}
  function stss(track){if(track.handler!=='vide')return null;var sync=[];for(var i=0;i<track.samples.length;i++)if((track.samples[i].flags&65536)===0)sync.push(i+1);if(!sync.length)sync.push(1);return fullBox('stss',0,0,[uints([sync.length].concat(sync))])}

  function finalize(initSegment,mediaSegments){
    var init=bytes(initSegment),top=parseBoxes(init),ftyp=null,moov=null;
    for(var i=0;i<top.length;i++){if(top[i].type==='ftyp')ftyp=top[i];else if(top[i].type==='moov')moov=top[i]}
    if(!moov)throw new Error('MP4 初始化信息不完整');
    var mvhd=child(moov,'mvhd'),movieTimescale=mvhd?readTimescale(init,mvhd):1000,tracks={},trackList=[],trex={};
    var trexBoxes=descendants(moov,'trex');for(i=0;i<trexBoxes.length;i++){var defaults=parseTrex(init,trexBoxes[i]);trex[defaults.trackId]=defaults}
    for(i=0;i<moov.children.length;i++)if(moov.children[i].type==='trak'){
      var trak=moov.children[i],tkhd=child(trak,'tkhd'),mdia=child(trak,'mdia'),mdhd=mdia&&child(mdia,'mdhd'),hdlr=mdia&&child(mdia,'hdlr');
      if(!tkhd||!mdhd||!hdlr)continue;
      var id=readTrackId(init,tkhd),track={id:id,handler:readHandler(init,hdlr),timescale:readTimescale(init,mdhd),samples:[],chunks:[],chunkOffsets:[],duration:0};
      tracks[id]=track;trackList.push(track);
    }
    if(!trackList.length)throw new Error('没有找到音视频轨道');
    var chunkOrder=[];
    for(i=0;i<mediaSegments.length;i++){
      var media=bytes(mediaSegments[i]),boxes=parseBoxes(media);
      for(var b=0;b<boxes.length;b++)if(boxes[b].type==='moof'){
        var moof=boxes[b],trafs=moof.children.filter(function(box){return box.type==='traf'}),fallback=null;
        for(var next=b+1;next<boxes.length;next++)if(boxes[next].type==='mdat'){fallback=boxes[next].start+boxes[next].header;break}
        for(var t=0;t<trafs.length;t++){
          var tfhdBox=child(trafs[t],'tfhd');if(!tfhdBox)continue;
          var tfhd=parseTfhd(media,tfhdBox),track=tracks[tfhd.trackId];if(!track)continue;
          var base=trex[track.id]||{},defaults={duration:tfhd.duration||base.duration||0,size:tfhd.size||base.size||0,flags:tfhd.flags||base.flags||0},truns=trafs[t].children.filter(function(box){return box.type==='trun'}),cursor=fallback;
          for(var tr=0;tr<truns.length;tr++){
            var parsed=parseTrun(media,truns[tr],defaults);if(parsed.dataOffset!=null)cursor=moof.start+parsed.dataOffset;
            var length=0;for(var s=0;s<parsed.samples.length;s++)length+=parsed.samples[s].size;
            if(cursor==null||cursor<0||cursor+length>media.byteLength)throw new Error('MP4 分片采样数据越界');
            var chunk={track:track,sampleCount:parsed.samples.length,data:media.slice(cursor,cursor+length)};cursor+=length;
            track.chunks.push(chunk);chunkOrder.push(chunk);
            for(s=0;s<parsed.samples.length;s++){track.samples.push(parsed.samples[s]);track.duration+=parsed.samples[s].duration}
          }
        }
      }
    }
    for(i=0;i<trackList.length;i++)if(!trackList[i].samples.length)throw new Error((trackList[i].handler==='vide'?'视频':'音频')+'轨道没有有效采样');
    var movieDuration=0;for(i=0;i<trackList.length;i++)movieDuration=Math.max(movieDuration,Math.round(trackList[i].duration/trackList[i].timescale*movieTimescale));

    function rebuild(box,context){
      if(box.type==='mvex'||box.type==='edts')return null;
      if(box.type==='mvhd')return patchDuration(init,box,'mvhd',movieDuration);
      if(box.type==='tkhd'&&context)return patchDuration(init,box,'tkhd',Math.round(context.duration/context.timescale*movieTimescale));
      if(box.type==='mdhd'&&context)return patchDuration(init,box,'mdhd',context.duration);
      if(box.type==='stbl'&&context){
        var entries=[];for(var k=0;k<box.children.length;k++)if(box.children[k].type==='stsd')entries.push(raw(init,box.children[k]));
        entries.push(stts(context));var composition=ctts(context);if(composition)entries.push(composition);entries.push(stsc(context),stsz(context),stco(context));var sync=stss(context);if(sync)entries.push(sync);return makeBox('stbl',entries);
      }
      if(box.children.length){
        var childContext=context;if(box.type==='trak'){var trackTkhd=child(box,'tkhd');childContext=trackTkhd&&tracks[readTrackId(init,trackTkhd)]}
        var parts=[];for(var k=0;k<box.children.length;k++){var rebuilt=rebuild(box.children[k],childContext);if(rebuilt)parts.push(rebuilt)}return makeBox(box.type,parts);
      }
      return raw(init,box);
    }

    for(i=0;i<trackList.length;i++)trackList[i].chunkOffsets=new Array(trackList[i].chunks.length).fill(0);
    var ftypData=ftyp?raw(init,ftyp):makeBox('ftyp',[typeBytes('isom'),uints([512]),typeBytes('isom'),typeBytes('iso2'),typeBytes('avc1'),typeBytes('mp41')]);
    var moovData=rebuild(moov,null),payloadSize=0;for(i=0;i<chunkOrder.length;i++)payloadSize+=chunkOrder[i].data.byteLength;
    var offset=ftypData.byteLength+moovData.byteLength+8,trackChunkIndex={};
    for(i=0;i<chunkOrder.length;i++){var ordered=chunkOrder[i],index=trackChunkIndex[ordered.track.id]||0;ordered.track.chunkOffsets[index]=offset;trackChunkIndex[ordered.track.id]=index+1;offset+=ordered.data.byteLength}
    moovData=rebuild(moov,null);
    var mdatHeader=new Uint8Array(8);put32(mdatHeader,0,payloadSize+8);mdatHeader.set(typeBytes('mdat'),4);
    var blobParts=[ftypData,moovData,mdatHeader];for(i=0;i<chunkOrder.length;i++)blobParts.push(chunkOrder[i].data);
    return{blob:new Blob(blobParts,{type:'video/mp4'}),duration:movieDuration/movieTimescale,tracks:trackList.map(function(track){return{type:track.handler,duration:track.duration/track.timescale,samples:track.samples.length}})};
  }

  return{finalize:finalize};
});
