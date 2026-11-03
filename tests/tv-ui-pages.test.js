const fs=require('fs'),vm=require('vm'),assert=require('assert');
const elements={};function element(id){if(elements[id])return elements[id];const e={value:'',textContent:'',disabled:false,options:[],files:[],appendChild(o){this.options.push(o);}};Object.defineProperty(e,'innerHTML',{set(){this.options=[];}});return elements[id]=e;}
const calls=[],reads=[];let selected='ui3',radioSelected='classic',fail=false,radioFail=false,hold=false,checking=false,lastXhr,tick;
const choices=[{id:'classic',name:'经典唱片'},{id:'optical',name:'柔光唱片'}];
function radioState(){return {selected:radioSelected,choices:choices.slice(),catalogRefreshing:checking};}
const ctx={window:{addEventListener(){}},clearTimeout(){tick=null;},setTimeout(fn){tick=fn;return 1;},document:{getElementById:element,createElement(){return {}; }},startPage(){},toast(){},api(path,data,done){calls.push({path,data});if(path==='/api/tv-ui'){if(data&&fail){done(new Error('load failed'));return;}if(data)selected=data.id;done(null,{selected});}else if(path==='/api/radio-ui'){if(data&&data.id){if(radioFail){done(new Error('download failed'));return;}radioSelected=data.id;}if(data&&data.refresh)checking=true;if(!data&&hold){reads.push(done);return;}done(null,radioState());}else done(null,{});},XMLHttpRequest:function(){lastXhr=this;this.open=(method,path)=>{this.path=path;};this.setRequestHeader=()=>{};this.send=file=>{this.file=file;};}};
vm.createContext(ctx);vm.runInContext(fs.readFileSync('app/src/main/assets/control/js/pages/personalization.js','utf8'),ctx);
assert.equal(element('tvUiStyle').value,'ui3');
element('tvUiStyle').value='ui2';ctx.saveTvUiStyle();assert.equal(selected,'ui2');assert.equal(element('tvUiStyle').disabled,false);
fail=true;element('tvUiStyle').value='ui4';ctx.saveTvUiStyle();assert.equal(element('tvUiStyle').value,'ui2');assert.equal(element('tvUiStyle').disabled,false);
console.log('PASS TV UI initial selection, save/failure restore');
assert.equal(element('radioUiStyle').options.length,2);
element('radioUiStyle').value='optical';ctx.saveRadioUiStyle();assert.equal(radioSelected,'optical');
radioFail=true;element('radioUiStyle').value='classic';ctx.saveRadioUiStyle();assert.equal(element('radioUiStyle').value,'optical');assert.equal(element('radioUiStyle').disabled,false);radioFail=false;
choices.push({id:'future-ui',name:'<b>新增界面</b>'});ctx.loadRadioUi();assert.equal(element('radioUiStyle').options[2].textContent,'<b>新增界面</b>');
hold=true;ctx.loadRadioUi();element('radioUiStyle').value='classic';ctx.saveRadioUiStyle();
reads[0]({message:'obsolete'},null);assert.equal(element('radioUiStyle').value,'classic');
reads[1](null,radioState());assert.equal(element('radioUiStyle').value,'classic');hold=false;
checking=true;ctx.loadRadioUi();assert.equal(typeof tick,'function');checking=false;tick();
const html=fs.readFileSync('app/src/main/assets/control/pages/personalization.html','utf8');assert.ok(!html.includes('radioUiUrl')&&!html.includes('radioUiJar')&&!html.includes('radioUiToggle'));
assert.ok(!html.includes('appearance-tools')&&!html.includes('tvUiJar')&&!html.includes('radioUiRefresh'));
console.log('PASS radio selection, failure rollback, stale reads and catalog polling without removed controls');
