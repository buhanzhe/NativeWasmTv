#!/usr/bin/env python3
"""Exact-source empty-carousel guard. Optional original capture stays local."""
import hashlib, os, pathlib, subprocess, sys, tempfile
ROOT=pathlib.Path(__file__).resolve().parents[2]
source=(ROOT/'app/src/main/java/xiao/bu/tv/LegacyScriptSourceAdapter.java').read_text()
FINGERPRINT='3f6e9ad7109eef107f66b993ee51c17e189a8db6e2b4f2ba2f504f2bad731438'
fixture='''function carousel(containers, o) {
 containers.forEach(function(container) {
        var tLi = container.children, tl = tLi.length;
        if(o.circular) {
            container.circular = true;
        }
        container.width = tLi[0].offsetWidth;
        container.initialized = true;
 });
 return containers;
}
'''
runner='''package xiao.bu.tv;
import java.nio.file.*;
public class HuangshanAdapterTest {
 public static void main(String[] args)throws Exception {
  String source=new String(Files.readAllBytes(Paths.get(args[0])),"UTF-8");
  String page="http://www.hsnewsnet.com/web/tvnews/",url="http://www.hsnewsnet.com/js/pc/jCarouselLite.js";
  String output=LegacyScriptSourceAdapter.adapt(page,url,source);
  String guard="        if (tl === 0) return;\\n\\n";
  if(output.equals(source)||!output.contains(guard))throw new AssertionError("guard missing");
  if(output.indexOf(guard)!=output.lastIndexOf(guard))throw new AssertionError("multiple guard insertions");
  if(!output.replace(guard, "").equals(source))throw new AssertionError("unrelated edits");
  String[][] cases={{page,url,source+" "},{page,url+"?v=1",source},{"https://www.hsnewsnet.com/web/tvnews/",url,source},{"http://www.hsnewsnet.com/web/tvnews/?other=1",url,source},{"http://www.hsnewsnet.com/web/tvnews/other",url,source},{"http://example.org/web/tvnews/",url,source},{"http://www.hsnewsnet.com:80/web/tvnews/",url,source},{"http://user@www.hsnewsnet.com/web/tvnews/",url,source},{page,"https://www.hsnewsnet.com/js/pc/jCarouselLite.js",source},{page,url,output},{page,url,"<html>Denied</html>"},{page,url,null},{null,url,source}};
  for(String[] c:cases)if(LegacyScriptSourceAdapter.adapt(c[0],c[1],c[2])!=c[2])throw new AssertionError("mismatch changed");
  Files.write(Paths.get(args[1]),output.getBytes("UTF-8"));
 }
}'''
semantics='''const fs=require('fs'),vm=require('vm'),assert=require('assert');
function load(p){const c={};vm.createContext(c);vm.runInContext(fs.readFileSync(p,'utf8'),c);return c.carousel;}
const original=load(process.argv[1]),adapted=load(process.argv[2]);
assert.throws(()=>original([{children:[]}],{circular:true}),e=>e.name==='TypeError');
for(const circular of [true,false]){
 const empty={children:[]},containers=[empty];assert.strictEqual(adapted(containers,{circular}),containers);assert.deepStrictEqual(empty,{children:[]});
 const make=()=>[{children:[{offsetWidth:37}]},{children:[{offsetWidth:0}]}];
 const before=make(),after=make();assert.strictEqual(original(before,{circular}),before);assert.strictEqual(adapted(after,{circular}),after);assert.deepStrictEqual(after,before);
 const mixed=[{children:[]},{children:[{offsetWidth:42}]},{children:[]}];assert.strictEqual(adapted(mixed,{circular}),mixed);assert.strictEqual(mixed[1].width,42);assert.strictEqual(mixed[1].initialized,true);assert.strictEqual(mixed[2].initialized,undefined);
}
for(const run of [original,adapted]){
 assert.throws(()=>run([{children:[null]}],{}),e=>e.name==='TypeError');
 assert.throws(()=>run([{children:[{get offsetWidth(){throw Error('real measurement failure')}}]}],{}),/real measurement failure/);
 assert.throws(()=>run([{get children(){throw Error('real collection failure')}}],{}),/real collection failure/);
}
console.log('PASS original empty-carousel failure; empty-only guard; nonempty circular/noncircular, zero-width, chain return, later containers, real exceptions unchanged');
'''
java=pathlib.Path(os.environ['JAVA_HOME'])/'bin'
with tempfile.TemporaryDirectory() as tmp:
 d=pathlib.Path(tmp);(d/'HuangshanAdapterTest.java').write_text(runner)
 for captured in [False]+([True] if len(sys.argv)>1 else []):
  body=pathlib.Path(sys.argv[1]).read_bytes() if captured else fixture.encode()
  code=source if captured else source.replace(FINGERPRINT,hashlib.sha256(body).hexdigest())
  (d/'LegacyScriptSourceAdapter.java').write_text(code);(d/'input.js').write_bytes(body)
  subprocess.run([str(java/'javac'),'-source','1.7','-target','1.7','-d',str(d),str(d/'LegacyScriptSourceAdapter.java'),str(d/'HuangshanAdapterTest.java')],check=True)
  output=pathlib.Path(sys.argv[2]).resolve() if captured and len(sys.argv)>2 else d/'output.js'
  subprocess.run([str(java/'java'),'-cp',str(d),'xiao.bu.tv.HuangshanAdapterTest',str(d/'input.js'),str(output)],check=True)
  if not captured:subprocess.run(['node','-e',semantics,str(d/'input.js'),str(output)],check=True)
  print('PASS Huangshan fingerprint guards:', 'production source with local capture' if captured else 'synthetic fixture with test-only fingerprint')
