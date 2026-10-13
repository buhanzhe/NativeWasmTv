#!/usr/bin/env python3
"""Fingerprint + lifecycle regression; optional real capture remains local."""
import hashlib,os,pathlib,subprocess,sys,tempfile
ROOT=pathlib.Path(__file__).resolve().parents[2]
source=(ROOT/'app/src/main/java/xiao/bu/tv/LegacyScriptSourceAdapter.java').read_text()
fixture='''function removeText(node, targetStr) {
 if(node.nodeType===3){if(node.textContent.includes(targetStr))node.textContent=node.textContent.replace(targetStr,"");}
 else{for(var i=0;i<node.childNodes.length;i++)removeText(node.childNodes[i],targetStr);}
}
document.addEventListener('DOMContentLoaded', function () {
 removeText(document.body, 'target');
 var observer=new MutationObserver(function(){removeText(document.body,'target');});
 observer.observe(document.body,{childList:true,subtree:true,characterData:true});
});
'''
runner='''package xiao.bu.tv;
import java.nio.file.*;
public class XianAdapterTest {
 public static void main(String[] args)throws Exception {
  String source=new String(Files.readAllBytes(Paths.get(args[0])),"UTF-8"), page="https://v.xiancity.cn/folder8/folder39/folder29/",url="https://v.xiancity.cn/t/1/4/js/base.js";
  String output=LegacyScriptSourceAdapter.adapt(page,url,source);
  if(output.equals(source)||!output.contains("if (node === null && document.body === null) return;"))throw new AssertionError("guard missing");
  if(!output.replace("\\n    if (node === null && document.body === null) return;", "").replace("\\n    if (document.body === null) return;", "").equals(source))throw new AssertionError("unrelated edits");
  String[][] cases={{page,url,source+" "},{page,url+"?v=1",source},{"https://v.xiancity.cn/folder8/folder39/folder29/m/",url,source},{"https://example.org/folder8/folder39/folder29/",url,source},{page,url,output},{page,url,"<html>Denied</html>"},{"https://user@v.xiancity.cn/folder8/folder39/folder29/",url,source}};
  for(String[] c:cases)if(LegacyScriptSourceAdapter.adapt(c[0],c[1],c[2])!=c[2])throw new AssertionError("mismatch changed");
  Files.write(Paths.get(args[1]),output.getBytes("UTF-8"));
 }
}'''
semantics='''const fs=require('fs'),vm=require('vm'),assert=require('assert');
const original=fs.readFileSync(process.argv[1],'utf8'), adapted=fs.readFileSync(process.argv[2],'utf8');
function load(s){const c={events:{},observed:[],constructed:0,document:{body:null}};c.document.addEventListener=(n,f)=>c.events[n]=f;c.MutationObserver=function(fn){c.constructed++;c.callback=fn;this.observe=(node,options)=>{if(node===null)throw new Error('invalid observation target');c.observed.push({node,options});};};vm.createContext(c);vm.runInContext(s,c);return c;}
let before=load(original),after=load(adapted);
assert.throws(()=>before.events.DOMContentLoaded(),e=>e.name==='TypeError');
after.events.DOMContentLoaded();assert.strictEqual(after.constructed,0);assert.strictEqual(after.observed.length,0);
assert.throws(()=>before.removeText(null,'target'),e=>e.name==='TypeError');
assert.strictEqual(after.removeText(null,'target'),undefined);
assert.throws(()=>after.removeText(undefined,'target'),e=>e.name==='TypeError');
// A separate document with a normal single DOMContentLoaded, not late-body recovery.
after=load(adapted);after.document.body={nodeType:1,childNodes:[]};
assert.throws(()=>after.removeText(null,'target'),e=>e.name==='TypeError');
const text={nodeType:3,textContent:'before target after'},untouched={nodeType:3,textContent:'hello'},branch={nodeType:1,childNodes:[text,{nodeType:1,childNodes:[untouched]}]};
after.document.body=branch;after.removeText(branch,'target');assert.strictEqual(text.textContent,'before  after');assert.strictEqual(untouched.textContent,'hello');
const bad={get nodeType(){throw new Error('real failure')}};assert.throws(()=>after.removeText(bad,'target'),/real failure/);
after.events.DOMContentLoaded();assert.strictEqual(after.constructed,1);assert.strictEqual(after.observed[0].node,branch);assert.deepStrictEqual(JSON.parse(JSON.stringify(after.observed[0].options)),{childList:true,subtree:true,characterData:true});
after.document.body=null;after.callback();assert.throws(()=>after.removeText(bad,'target'),/real failure/);
console.log('PASS original absent-body failure; fixed absent-body only; live-DOM recursion; live-body null/undefined/real error propagation');
'''
java=pathlib.Path(os.environ['JAVA_HOME'])/'bin'
with tempfile.TemporaryDirectory() as tmp:
 d=pathlib.Path(tmp);(d/'XianAdapterTest.java').write_text(runner)
 for captured in [False]+([True] if len(sys.argv)>1 else []):
  body=pathlib.Path(sys.argv[1]).read_bytes() if captured else fixture.encode()
  code=source if captured else source.replace('aefac8d3b9f2a73f908f31703763d1066ff5281c1c0d1c26a7fca899c801a755',hashlib.sha256(body).hexdigest())
  (d/'LegacyScriptSourceAdapter.java').write_text(code);(d/'input.js').write_bytes(body)
  subprocess.run([str(java/'javac'),'-d',str(d),str(d/'LegacyScriptSourceAdapter.java'),str(d/'XianAdapterTest.java')],check=True)
  output=pathlib.Path(sys.argv[2]).resolve() if captured and len(sys.argv)>2 else d/'output.js'
  subprocess.run([str(java/'java'),'-cp',str(d),'xiao.bu.tv.XianAdapterTest',str(d/'input.js'),str(output)],check=True)
  if not captured:subprocess.run(['node','-e',semantics,str(d/'input.js'),str(output)],check=True)
  print('PASS Xi’an fingerprint guards:', 'production source with local capture' if captured else 'synthetic fixture with test-only fingerprint')
