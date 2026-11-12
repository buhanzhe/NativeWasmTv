// Exercise the production site adapter with a real component-style callback.
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const source=fs.readFileSync('app/src/main/assets/userscripts/ntv-site-selection.user.js','utf8');
const adapter=source.slice(source.indexOf('var gdtvLiveWait='),source.indexOf('g.__auto=function(){',source.indexOf('var gdtvLiveWait=')));
function fixture(options={}){
 let time=0,calls=0,ready=false;
 const owner={props:{match:{params:{id:options.id||'43'}},isPrefetch:options.prefetch},state:{timeShift:options.shift},getTvChannelDetailWithTcdn(){assert.strictEqual(this,owner);assert.equal(arguments.length,0);calls++;return Promise.resolve();}};
 const root={stateNode:owner};root.sibling=root;
 const context={WeakMap,WeakSet,Set,String,profile:{name:options.profile||'gdtv'},config:{headless:options.headless!==false,legacyMediaWorkers:options.legacy!==false},location:{pathname:'/tvChannelDetail/43'},host:()=>({mediaReady:ready}),document:{getElementById:id=>id==='root'?{_reactRootContainer:{_internalRoot:{current:root}}}:null},performance:{now:()=>time},console:{log(){}}};
 vm.createContext(context);vm.runInContext(adapter,context);
 return {run(at){time=at;context.recoverGdtvLiveRequest();},count:()=>calls,ready(){ready=true;},owner};
}
let test=fixture();test.run(0);test.run(2999);assert.equal(test.count(),0);test.run(3000);assert.equal(test.count(),1);test.run(100000);assert.equal(test.count(),1);
for(const opts of [{id:'44'},{profile:'cztv'},{headless:false},{legacy:false},{shift:12345},{prefetch:true}]){
 test=fixture(opts);test.run(0);test.run(10000);assert.equal(test.count(),0,JSON.stringify(opts));
}
test=fixture();test.run(0);test.ready();test.run(10000);assert.equal(test.count(),0);
test=fixture();test.run(0);test.owner.props.match.params.id='44';test.run(10000);assert.equal(test.count(),0);
console.log('PASS delayed original-handler recovery once, exact route/component ownership, healthy-stream/modern/rendered/archive/prefetch bypass and cyclic tree bounds');
