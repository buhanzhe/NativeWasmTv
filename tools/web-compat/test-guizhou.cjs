// Offline contract tests. Synthetic catalog/media URLs; no live playback claim.
const fs=require('fs'),vm=require('vm'),assert=require('assert'),crypto=require('crypto');
const path=require('path'),root=path.resolve(__dirname,'../..');
const script=fs.readFileSync(path.join(__dirname,'sites.js'),'utf8');
const md5=value=>crypto.createHash('md5').update(value).digest('hex');
const page='https://www.gzstv.com/tv/ch01';
function catalog(){return {title:'fixture Guizhou',stream_url:'https://media.example.org/CH01.m3u8?txSecret=unchanged&txTime=6553ff10',_ntvServerTimeMs:1700000000000};}
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
  for(const id of ['ch01','ch02','ch03','ch04','ch05','ch06','ch13']){
    const r=realm({url:'https://www.gzstv.com/tv/'+id});await settle();
    assert.equal(r.w.__ntvTvCompatibility.adapter,'guizhou');assert.equal(r.video().src,catalog().stream_url);
    assert.equal(r.calls[0].url,'/__ntv_kitkat__/guizhou-play-info');assert.equal(r.calls.length,2);
    assert.equal(r.calls[1].url,catalog().stream_url);assert.equal(r.video().playCalls,1);
    vm.runInContext(script,r.w);await settle();assert.equal(r.nodes.length,1);assert.equal(r.calls.length,2);
  }
  const standalone=realm({shell:false,native:false});await settle();
  assert.equal(standalone.calls[0].url,'https://api.gzstv.com/v1/tv/ch01/?remote_address=&fields=description,title,stream_url,image,author');
  assert.equal(standalone.calls[0].init.headers,undefined,'Keep browser request simple, no new CORS preflight');assert.equal(standalone.calls.length,1);
  for(const url of ['https://www.gzstv.com/tv/ch07','https://www.gzstv.com/tv/ch01evil','https://www.gzstv.com.evil/tv/ch01','https://www.gzstv.com/tv/ch00']){
    const r=realm({url});await settle();assert.equal(r.calls.length,0);assert.equal(r.nodes.length,0);
  }
  for(const value of [{},{stream_url:null},{stream_url:''},{stream_url:'javascript:alert(1)'},{stream_url:'https://user:pass@media.example.org/a.m3u8'},
    {stream_url:'https://media.example.org/a.m3u8?txTime=bad-value'},
    {stream_url:'https://media.example.org/a.m3u8?txTime=123&txTime=456'},
    {stream_url:'https://media.example.org/a.m3u8?txTime=1234567890123'}]){
    const r=realm({value});await settle();assert.equal(r.video().src,undefined);assert(r.w.__ntvTvCompatibility.failure);assert.equal(r.calls.length,1);
  }
  for(const ms of [1700003600000,1700003600001]){
    const value=catalog();value._ntvServerTimeMs=ms;const r=realm({value});await settle();
    assert.equal(r.video().src,undefined);assert.equal(r.w.__ntvTvCompatibility.failure,'网站返回的播放地址已过期，请稍后重试');
    assert.equal(r.calls.length,1,'No CDN request or retry for stale metadata');
  }
  const future=catalog();future._ntvServerTimeMs=1699999999000;
  const fresh=realm({value:future});await settle();assert.equal(fresh.video().src,future.stream_url,'Preserve all token bytes');
  const noClock=catalog();delete noClock._ntvServerTimeMs;
  const absent=realm({value:noClock});await settle();assert.equal(absent.video().src,noClock.stream_url,'Do not infer expiry from bad device clock');
  const unsigned=catalog();unsigned.stream_url='https://media.example.org/a.m3u8?quality=sd#player';
  const simple=realm({value:unsigned});await settle();assert.equal(simple.video().src,unsigned.stream_url);
  for(const options of [{reject:true},{status:403},{status:500}]){
    const r=realm(options);await settle();assert.equal(r.video().src,undefined);assert(r.w.__ntvTvCompatibility.failure);assert.equal(r.calls.length,1);
  }
  const deferred=realm({deferred:true});await settle();assert.equal(deferred.nodes.length,0);
  deferred.document.body=deferred.body;deferred.events.DOMContentLoaded();await settle();assert.equal(deferred.video().src,catalog().stream_url);
  console.log('PASS Guizhou 7 channels, strict scope/schema, exact server-issued URL preservation, stale metadata guard, denial/no-retry, deferred shell and idempotence');
})().catch(error=>{console.error(error);process.exit(1);});
