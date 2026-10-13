const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert');
const deps=path.join(__dirname,'transpile/node_modules');
function realm(){const c={Babel:require(path.join(deps,'@babel/standalone')),acorn:require(path.join(deps,'acorn')),Date,JSON};vm.createContext(c);vm.runInContext(fs.readFileSync(path.join(__dirname,'transpile-preflight.js'),'utf8'),c);return c;}
const runtime=realm();vm.runInContext(fs.readFileSync(path.join(__dirname,'transpile-runtime.js'),'utf8'),runtime);
const parser={Date,JSON};vm.createContext(parser);vm.runInContext(fs.readFileSync(path.join(__dirname,'../../app/src/main/assets/legacy-compiler/parser.js'),'utf8'),parser);
const strict=[
 '"use strict";var re=/"/;if(true){function inside(){return 42;}window.result=inside();}',
 '"use strict";if(true){function inside(){return 42;}window.result=inside();}',
 'function outer(){"use strict";if(true){function inside(){return 42;}window.result=inside();}}outer();',
 '"a directive";"use strict";switch(1){case 1:function inside(){return 42;}window.result=inside();}',
 '"use strict";if(true){function/**/inside(){return 42;}window.result=inside();}',
 '"use strict";if(true){function 中文(){return 42;}window.result=中文();}',
 '"use strict";if(true){function \\u0066(){return 42;}window.result=\\u0066();}'
];
for(const source of strict){assert.strictEqual(runtime.ntvLegacyEs5(source,false),false);assert(JSON.parse(parser.ntvTransform(source,'fixture.js',false)).needsTransform);const result=JSON.parse(runtime.ntvTransform(source,'fixture.js',false));assert(result.changed);assert(runtime.ntvLegacyEs5(result.code,false));const c={window:{}};vm.runInNewContext(result.code,c);assert.strictEqual(c.window.result,42);}
const ordinary=[
 '"use strict";function inside(){return 42;}window.result=inside();',
 '"use strict";function outer(){function inside(){return 42;}return inside();}window.result=outer();',
 '"use\\x20strict";if(true){function inside(){return 42;}}',
 'var text="use strict";if(true){function inside(){return 42;}}',
 'if(true){function inside(){return 42;}}'
];
for(const source of ordinary){assert(runtime.ntvLegacyEs5(source,false));assert.strictEqual(JSON.parse(parser.ntvTransform(source,'fixture.js',false)).changed,false);assert.strictEqual(JSON.parse(runtime.ntvTransform(source,'fixture.js',false)).changed,false);}
fs.mkdirSync(path.join(__dirname,'results'),{recursive:true});
fs.writeFileSync(path.join(__dirname,'results/strict-preflight-inputs.json'),JSON.stringify(strict));
console.log('PASS runtime and generated parser: strict-only/inherited/switch/comment/unicode declarations, ordinary function bodies, escaped and non-directive strict strings');
