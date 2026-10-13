// Offline contract tests. Synthetic catalog/media URLs; no live playback claim.
const fs=require('fs'),vm=require('vm'),assert=require('assert'),crypto=require('crypto');
const path=require('path'),root=path.resolve(__dirname,'../..');
const script=fs.readFileSync(path.join(__dirname,'sites.js'),'utf8');
const md5=value=>crypto.createHash('md5').update(value).digest('hex');
const page='https://www.hebtv.com/19/19js/st/xdszb/index.shtml';
function catalog(){return {returnCode:'0000',_ntvServerTimeMs:1700000000000,returnData:{news:Array.from({length:7},(_,i)=>({
  title:'fixture-channel-'+i,liveVideo:[{formats:[{url:'https://media.example.org/live/'+i+'.m3u8'}]}],
  appCustomParams:{movie:{liveUri:'/live/'+i+'.m3u8',liveKey:'public-fixture-key'}}
}))}};}
function realm(options={}){
  const calls=[],nodes=[],events={},body={appendChild:n=>nodes.push(n)};
  const location=new URL(options.url||page+'?index=0');
  function element(tag){
    if(tag==='a')return new URL(location.href);
    return {tag,style:{},children:[],events:{},appendChild(n){this.children.push(n);},
      setAttribute(){},addEventListener(n,f){this.events[n]=f;},play(){this.playCalls=(this.playCalls||0)+1;return Promise.resolve();}};
  }
  const document={body:options.deferred?null:body,createElement:element,addEventListener:(n,f)=>events[n]=f};
  const value=options.value===undefined?catalog():options.value;
  const w={location,document,Promise,URL,URLSearchParams,Date,console,setTimeout,clearTimeout,
    __ntvTvCompatibility:{},__ntvMd5:md5,__ntvLegacySiteShell:options.shell!==false,
    __ntvNativeTvCompatibility:options.native!==false,addEventListener:(n,f)=>events[n]=f,
    fetch:(url,init)=>{calls.push({url,init});return options.reject?Promise.reject(new Error('network')):
      Promise.resolve({ok:options.status===undefined||options.status===200,status:options.status||200,json:()=>Promise.resolve(value)});}};
  w.window=w;vm.createContext(w);vm.runInContext(script,w);
  return {w,calls,nodes,events,document,body,video:()=>nodes[0]&&nodes[0].children[0]};
}
async function settle(){for(let i=0;i<12;i++)await Promise.resolve();}
(async()=>{
  for(let i=0;i<7;i++){
    const r=realm({url:page+'?index='+i});await settle();
    const expected='https://media.example.org/live/'+i+'.m3u8?t=1700007200&k='+md5('/live/'+i+'.m3u8public-fixture-key1700007200');
    assert.equal(r.w.__ntvTvCompatibility.adapter,'hebei');assert.equal(r.video().src,expected);
    assert.equal(r.calls[0].url,'/__ntv_kitkat__/hebei-play-info');assert.equal(r.calls.length,2);
    assert.equal(r.calls[1].url,expected);assert.equal(r.video().playCalls,1);
    vm.runInContext(script,r.w);await settle();assert.equal(r.nodes.length,1);assert.equal(r.calls.length,2);
    r.video().events.playing();assert.equal(r.nodes[0].children[1].style.display,'none');
  }
  const standalone=realm({shell:false,native:false});await settle();
  assert.equal(standalone.calls[0].init.method,'POST');assert.equal(standalone.calls.length,1);
  assert.equal(standalone.calls[0].url,'https://api.cmc.hebrts.cn/cmsback/api/com/article/getArticleList?catalogId=32557&siteId=1');
  const noIndex=realm({url:page});await settle();assert(noIndex.video().src.includes('/0.m3u8?'));
  for(const query of ['index=-1','index=7','index=1.5','index=','index=undefined','index=1&index=2','index=999999999']){
    const r=realm({url:page+'?'+query});await settle();assert.equal(r.video().src,undefined,query);
    assert.equal(r.w.__ntvTvCompatibility.failure,'频道编号无效',query);assert.equal(r.calls.length,1);
  }
  for(const mutate of [
    v=>v.returnCode='denied',v=>delete v.returnData,v=>v.returnData.news=[],
    v=>delete v.returnData.news[0].liveVideo,v=>v.returnData.news[0].liveVideo[0].formats=[],
    v=>v.returnData.news[0].appCustomParams.movie.liveKey=null,
    v=>v.returnData.news[0].liveVideo[0].formats[0].url='javascript:alert(1)'
  ]){
    const value=catalog();mutate(value);const r=realm({value});await settle();
    assert.equal(r.video().src,undefined);assert(r.w.__ntvTvCompatibility.failure);assert.equal(r.calls.length,1);
  }
  for(const options of [{reject:true},{status:403},{status:500}]){
    const r=realm(options);await settle();assert.equal(r.video().src,undefined);assert(r.w.__ntvTvCompatibility.failure);
    assert.equal(r.calls.length,1,'No denial retry or alternate playback endpoint');
  }
  const queryValue=catalog();queryValue.returnData.news[0].liveVideo[0].formats[0].url+='?quality=sd&t=old&k=old#player';
  const query=realm({value:queryValue});await settle();const signed=new URL(query.video().src);
  assert.equal(signed.searchParams.get('quality'),'sd');assert.deepEqual(signed.searchParams.getAll('t'),['1700007200']);
  assert.equal(signed.searchParams.getAll('k').length,1);assert.equal(signed.hash,'#player');
  const deferred=realm({deferred:true});await settle();assert.equal(deferred.nodes.length,0);
  deferred.document.body=deferred.body;deferred.events.DOMContentLoaded();await settle();assert.equal(deferred.video().playCalls,1);
  const early=realm({shell:false});await settle();assert.equal(early.calls.length,0,'Wait for native shell');
  for(const url of ['https://www.hebtv.com/other?index=0','https://www.hebtv.com.evil.example/19/19js/st/xdszb/index.shtml?index=0']){
    const r=realm({url});await settle();assert.equal(r.calls.length,0);assert.equal(r.nodes.length,0);
  }
  // Existing adapters must retain their routing and public response handling.
  for(const [url,adapter,api] of [
    ['https://www.cztv.com/liveTV/101','cztv','/__ntv_kitkat__/cztv-play-info?id=101'],
    ['https://v.iqilu.com/live/sdws','iqilu','/__ntv_kitkat__/iqilu-play-info'],
    ['https://web.guangdianyun.tv/tv/2851?uin=1','guangdianyun','/__ntv_kitkat__/gdy-play-info']
  ]){
    const value={success:true,data:{url:'https://media.example.org/old.m3u8',multiBitrateStreamList:[{bitrateCode:'720P',urlList:['https://media.example.org/old.m3u8']}]}};
    const r=realm({url,value});await settle();assert.equal(r.w.__ntvTvCompatibility.adapter,adapter);
    assert.equal(r.calls[0].url,api);assert.equal(r.video().src,'https://media.example.org/old.m3u8');
  }
  console.log('PASS site adapters: seven Hebei indexes, public signing, query preservation, denial/schema/index failures, deferred shell, idempotence and three existing adapters');
})().catch(error=>{console.error(error);process.exitCode=1;});
