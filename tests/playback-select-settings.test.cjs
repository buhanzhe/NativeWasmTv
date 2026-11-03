const fs=require('fs'),vm=require('vm'),assert=require('assert');
const source=fs.readFileSync('app/src/main/assets/control/js/pages/playback.js','utf8');
function method(name){const start=source.indexOf('function '+name+'(');let i=source.indexOf('{',start)+1,depth=1;while(depth){depth+=(source[i]==='{')-(source[i]==='}');i++;}return source.slice(start,i);}
let target,requests=[],dateSaves=0;
const context={state:{settings:{}},document:{getElementById:()=>target},syncLegacySelectButtons(){},
 closeLegacySelectPicker(){context.legacySelectTarget=null;},toast(){},refresh(){},Number,
 api(path,body,done){requests.push({path,body});done(null);}};
vm.createContext(context);vm.runInContext(method('saveAutoSwitchSource'),context);
for(const seconds of [0,5,10,15,20,25,30]){
 target={value:String(seconds)};context.saveAutoSwitchSource();
 assert.equal(requests.at(-1).path,'/api/settings');assert.equal(requests.at(-1).body.autoSwitchSourceSeconds,seconds);
 assert.equal(context.state.settings.autoSwitchSourceSeconds,seconds);assert.equal(context.legacySelectTarget,null);
}
assert.equal(requests.length,7);
console.log('PASS timeout selection: all values save the correct API field and retain state');
