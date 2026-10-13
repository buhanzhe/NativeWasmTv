const fs=require('fs'),path=require('path'),crypto=require('crypto');
const root=path.resolve(__dirname,'../..'),deps=path.resolve(process.argv[2]||path.join(__dirname,'transpile'));
const resolve=name=>require.resolve(name,{paths:[deps]});
const vendors=[['@babel/standalone','7.28.5','babel.min.js'],['acorn','8.15.0','dist/acorn.js']];
const dest=path.join(root,'app/src/main/assets/legacy-compiler');fs.mkdirSync(dest,{recursive:true});
let bootstrap='globalThis.console={log:function(){},warn:function(){},error:function(){}};\n',metadata=[];
for(const [name,version,file] of vendors){
  const packageFile=resolve(name+'/package.json');if(require(packageFile).version!==version)throw Error('Compiler version mismatch');
  const base=path.dirname(packageFile),source=fs.readFileSync(path.join(base,file));
  const license=fs.readFileSync(path.join(base,'LICENSE'),'utf8');
  bootstrap+='\n/*! '+name+' '+version+'\n'+license.replace(/\*\//g,'* /')+'\n*/\n'+source.toString('utf8').replace(/\/\/# sourceMappingURL=.*$/gm,'')+'\n';
  metadata.push({name,version,file,sha256:crypto.createHash('sha256').update(source).digest('hex')});
}
const preflight=fs.readFileSync(path.join(__dirname,'transpile-preflight.js'),'utf8');
bootstrap+=preflight+'\n'+fs.readFileSync(path.join(__dirname,'transpile-runtime.js'),'utf8');
fs.writeFileSync(path.join(dest,'compiler.js'),bootstrap);
const acornBase=path.dirname(resolve('acorn/package.json'));
fs.writeFileSync(path.join(dest,'parser.js'),'/*! Acorn 8.15.0\n'+fs.readFileSync(path.join(acornBase,'LICENSE'),'utf8')+'\n*/\n'+fs.readFileSync(path.join(acornBase,'dist/acorn.js'),'utf8')+'\n'+
  preflight+'\n'+'globalThis.ntvTransform=function(s,u,m){var t=Date.now();try{if(!ntvLegacyEs5(s,m))throw 0;return JSON.stringify({changed:false,module:false,syntaxMs:Date.now()-t,transformMs:0,code:s});}catch(e){return JSON.stringify({needsTransform:true});}};');
const systemBase=path.dirname(resolve('systemjs/package.json'));
if(require(path.join(systemBase,'package.json')).version!=='6.15.1')throw Error('SystemJS version mismatch');
fs.writeFileSync(path.join(dest,'modules.js'),'/*! SystemJS 6.15.1\n'+fs.readFileSync(path.join(systemBase,'LICENSE'),'utf8').replace(/\r\n/g,'\n')+'\n*/\n'+fs.readFileSync(path.join(systemBase,'dist/s.min.js'),'utf8').replace(/\r\n/g,'\n')+'\n'+fs.readFileSync(path.join(__dirname,'module-runtime.js'),'utf8'));
metadata.push({name:'systemjs',version:'6.15.1',file:'dist/s.min.js',sha256:crypto.createHash('sha256').update(fs.readFileSync(path.join(systemBase,'dist/s.min.js'))).digest('hex')});
const digest=crypto.createHash('sha256');for(const file of ['compiler.js','parser.js','modules.js'])digest.update(fs.readFileSync(path.join(dest,file)));
fs.writeFileSync(path.join(dest,'version.txt'),digest.digest('hex')+'\n');
fs.writeFileSync(path.join(__dirname,'transpile-vendors.json'),JSON.stringify(metadata,null,2)+'\n');
console.log('Built QuickJS compiler bootstrap:',Buffer.byteLength(bootstrap),'bytes');
