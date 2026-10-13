const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert');
const deps=path.join(__dirname,'transpile/node_modules');
const Babel=require(path.join(deps,'@babel/standalone')),acorn=require(path.join(deps,'acorn'));
let calls=[];
const context={Babel:{transform(source,options){calls.push({full:!!options.presets,plugins:options.plugins.filter(p=>typeof p==='string')});return Babel.transform(source,options);}},acorn,Date,JSON};
vm.createContext(context);
for(const file of ['transpile-preflight.js','transpile-runtime.js'])vm.runInContext(fs.readFileSync(file==='transpile-runtime.js'&&process.env.NTV_TEST_RUNTIME?process.env.NTV_TEST_RUNTIME:path.join(__dirname,file),'utf8'),context);
function transform(source){calls=[];const result=JSON.parse(context.ntvTransform(source,'https://fixture.invalid/a.js',false));acorn.parse(result.code,{ecmaVersion:5});return result;}
function semantics(source){const result=transform(source),a={window:{}},b={window:{}};vm.runInNewContext(source,a);vm.runInNewContext(result.code,b);assert.strictEqual(JSON.stringify(b.window.result),JSON.stringify(a.window.result));return result;}
const logical='transform-logical-assignment-operators';
for(const op of ['&&=','||=','??=']){
 transform('x '+op+' 7;');
 assert(calls[0].plugins.includes(logical),op+' alone must select logical assignment');
 assert(calls.length===1&&!calls[0].full,op+' alone must compile selectively');
 for(const value of ['null','undefined','false','true','0','1','""','"ok"']){
  const source='let reads=0,writes=0,rhs=0,keys=0,bases=0;var v='+value+';var o={get x(){reads++;return v;},set x(n){writes++;v=n;}};function base(){bases++;return o;}function key(){keys++;return "x";}function right(){rhs++;return 7;}var answer=(base()[key()]'+op+'right());window.result=[answer,v,reads,writes,rhs,keys,bases];';
  semantics(source);
  assert(calls[0].plugins.includes(logical),op+' must select logical assignment');
  assert(calls.every(c=>!c.full),op+' should not need full fallback');
 }
}
for(const expression of [
 'x=2','x+=2','x-=2','x*=2','x/=2','x%=2','x|=2','x&=2','x^=2','x<<=2','x>>=2','x>>>=2',
 'x==2','x===2','x!=2','x!==2','x<=2','x>=2','x&&2','x||2','x??2',
 '"&&= ||= ??="','/&&=|\\|\\|=|\\?\\?=/.test("??=")','x/2/2',
 'x /* &&= ||= ??= */ + 2','x // &&= ||= ??=\n + 2'
]){
 semantics('let x=8;window.result='+expression+';');
 assert(calls.length===1,'Expected one selective transform: '+expression);
 assert(!calls[0].full&&!calls[0].plugins.includes(logical),'False logical assignment selection: '+expression);
}
assert.strictEqual(transform('var x=1;x=2;').changed,false);
assert.strictEqual(calls.length,0);
assert.throws(()=>transform('let x=;x ||= 1;'));
// Modern regex remains on complete fallback, with mandatory final ES5 validation.
semantics('let x=0;x ||= /a.b/s.test("a\\nb");window.result=x;');
assert(calls.some(c=>c.full));
const originalValidate=context.ntvLegacyEs5;let validations=0;
context.ntvLegacyEs5=function(){validations++;return false;};
assert.throws(()=>transform('let x=0;x ||= 1;'),/Chromium 30/);
assert.strictEqual(validations,3); // source, selective output, full fallback output
assert.strictEqual(calls.length,2);assert(calls[1].full);
context.ntvLegacyEs5=originalValidate;
console.log('PASS logical assignment selection and short-circuit/reference semantics; ordinary assignments, equality, strings, comments, regex and division negatives; ES5 pass-through, malformed input, fallback and final validation');
