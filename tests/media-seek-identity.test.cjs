const fs = require('fs'), vm = require('vm'), assert = require('assert');
const source = fs.readFileSync('app/src/main/assets/control/js/pages/media.js', 'utf8');
function method(name) {
  const start = source.indexOf('function ' + name + '(');
  let at = source.indexOf('{', start) + 1, depth = 1;
  while (depth) { depth += (source[at] === '{') - (source[at] === '}'); at++; }
  return source.slice(start, at);
}
let commands = [], rejected = 0, cancelled = 0;
const context = { mediaSeekPending: false, mediaSeekRevision: 1,
  mediaState: {sourceKey:'native-a',durationMs:60000},
  mediaCommand(action, body) { commands.push({action,body}); },
  mediaProgressCancel() { cancelled++; }, toast() { rejected++; },
  setTimeout() {}, Number, Math };
vm.createContext(context); vm.runInContext(method('mediaSeekCommit'), context);
const seek = (overrides={}) => context.mediaSeekCommit(Object.assign({disabled:false,value:'12000',ntvSourceKey:'native-a',ntvLive:false},overrides));
seek(); assert.equal(commands.length,1); assert.equal(commands[0].action,'seek'); assert.equal(commands[0].body.sourceKey,'native-a');
context.mediaState.sourceKey='native-b'; seek(); assert.equal(commands.length,1);
context.mediaState={sourceKey:'native-a',durationMs:0};seek();assert.equal(commands.length,1);
seek({ntvLive:true});assert.equal(commands.length,2);assert.equal(commands[1].action,'liveSeek');
context.mediaState={durationMs:60000,webPageKey:'page-a',webMediaToken:'video-a'};
seek({ntvSourceKey:'',ntvWebPageKey:'page-a',ntvWebMediaToken:'video-a'});assert.equal(commands.length,3);
seek({ntvSourceKey:'',ntvWebPageKey:'page-old',ntvWebMediaToken:'video-a'});assert.equal(commands.length,3);
seek({ntvSourceKey:'',ntvWebPageKey:'page-a',ntvWebMediaToken:'video-old'});assert.equal(commands.length,3);
context.mediaState=null;seek();assert.equal(commands.length,3);
assert.equal(rejected,5);assert.equal(cancelled,5);
console.log('PASS native/web VOD and live seek identities, changed channel/page/video/type, tagged requests');
