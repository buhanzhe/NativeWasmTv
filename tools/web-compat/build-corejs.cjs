// Developer-only build. The APK contains the generated ES5 bundle, no compiler.
const fs=require('fs'),path=require('path'),crypto=require('crypto');
const root=path.resolve(__dirname,'../..');
const deps=path.resolve(process.argv[2]||path.join(__dirname,'corejs'));
const resolve=name=>require.resolve(name,{paths:[deps]});
const config=JSON.parse(fs.readFileSync(path.join(__dirname,'corejs/config.json'),'utf8'));
if(require(resolve('core-js/package.json')).version!==config.version ||
   require(resolve('core-js-builder/package.json')).version!==config.version)throw Error('Wrong core-js build version');
(async()=>{
  const compat=require(resolve('core-js-compat'));
  const modules=compat({targets:config.targets,modules:config.modules}).list;
  const bundle=await require(resolve('core-js-builder'))({targets:config.targets,modules:config.modules});
  const minimized=await require(resolve('terser')).minify(bundle,{ecma:5,compress:{passes:2},mangle:true,format:{ecma:5,ascii_only:true,comments:false}});
  if(!minimized.code)throw Error('Empty core-js build');
  require(resolve('acorn')).parse(minimized.code,{ecmaVersion:5});
  const dest=path.join(root,'third_party/web-compat/core-js');fs.mkdirSync(dest,{recursive:true});
  const bytes=minimized.code+'\n';fs.writeFileSync(path.join(dest,'legacy.min.js'),bytes);
  fs.copyFileSync(resolve('core-js/LICENSE'),path.join(dest,'LICENSE.txt'));
  fs.writeFileSync(path.join(dest,'modules.json'),JSON.stringify({version:config.version,targets:config.targets,modules},null,2)+'\n');
  const metadata=path.join(__dirname,'vendors.json'),vendors=JSON.parse(fs.readFileSync(metadata,'utf8'));
  const retained=vendors.filter(v=>!['promise-polyfill','url-search-params','core-js'].includes(v.name));
  retained.unshift({name:'core-js',version:config.version,file:'legacy.min.js',upstream:'https://github.com/zloirock/core-js',license:'MIT',sha256:crypto.createHash('sha256').update(bytes).digest('hex')});
  fs.writeFileSync(metadata,JSON.stringify(retained,null,2)+'\n');
  console.log('Built core-js',config.version,modules.length,'modules,',Buffer.byteLength(bytes),'bytes; ES5 parsed');
})().catch(error=>{console.error(error);process.exitCode=1;});
