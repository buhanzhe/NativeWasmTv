#!/usr/bin/env python3
"""Known bare-base config only; no global URL or security behavior changes."""
import hashlib, os, pathlib, subprocess, sys, tempfile
ROOT=pathlib.Path(__file__).resolve().parents[2]
source=(ROOT/'app/src/main/java/xiao/bu/tv/LegacyScriptSourceAdapter.java').read_text()
FINGERPRINT='b03cd86fc5987d8bd1004fa28945a0545b2f1d6c54f14e2c2a58c1c612002913'
fixture='''var config={server: "hslive.hsnewsnet.com", ssl:false, dream:2, other:"https://elsewhere.example/a"};
function request(){return new URL('/program', config.server).href;}
'''
runner='''package xiao.bu.tv;
import java.nio.file.*;
public class HuangshanServerTest {
 public static void main(String[] args)throws Exception {
  String source=new String(Files.readAllBytes(Paths.get(args[0])),"UTF-8");
  String page="http://www.hsnewsnet.com/web/tvnews/",url="http://www.hsnewsnet.com/js/pc/liveList20211026.js";
  String output=LegacyScriptSourceAdapter.adapt(page,url,source);
  String old="server: \\"hslive.hsnewsnet.com\\"",fixed="server: \\"http://hslive.hsnewsnet.com\\"";
  if(output.equals(source)||!output.contains(fixed)||output.contains(old))throw new AssertionError("config repair missing");
  if(!output.replace(fixed,old).equals(source))throw new AssertionError("unrelated edits");
  if(output.indexOf(fixed)!=output.lastIndexOf(fixed))throw new AssertionError("multiple repairs");
  String[][] cases={{page,url,source+" "},{page,url+"?v=1",source},{"https://www.hsnewsnet.com/web/tvnews/",url,source},{"http://www.hsnewsnet.com/web/tvnews/?other=1",url,source},{"http://www.hsnewsnet.com/web/tvnews/other",url,source},{"http://example.org/web/tvnews/",url,source},{"http://www.hsnewsnet.com:80/web/tvnews/",url,source},{"http://user@www.hsnewsnet.com/web/tvnews/",url,source},{page,"https://www.hsnewsnet.com/js/pc/liveList20211026.js",source},{page,url,output},{page,url,"<html>Denied</html>"},{page,url,null},{null,url,source},{page,url,source.replace(old,"server: \\"https://hslive.hsnewsnet.com\\"")},{page,url,source.replace(old,"server: \\"another.example\\"")}};
  for(String[] c:cases)if(LegacyScriptSourceAdapter.adapt(c[0],c[1],c[2])!=c[2])throw new AssertionError("mismatch changed");
  Files.write(Paths.get(args[1]),output.getBytes("UTF-8"));
 }
}'''
semantics='''const fs=require('fs'),vm=require('vm'),assert=require('assert');
function load(p){const c={URL};vm.createContext(c);vm.runInContext(fs.readFileSync(p,'utf8'),c);return c;}
const before=load(process.argv[1]),after=load(process.argv[2]);assert.throws(()=>before.request(),e=>e.name==='TypeError');assert.strictEqual(after.request(),'http://hslive.hsnewsnet.com/program');
assert.strictEqual(after.URL,URL);assert.strictEqual(after.config.ssl,before.config.ssl);assert.strictEqual(after.config.dream,before.config.dream);assert.strictEqual(after.config.other,before.config.other);
assert.throws(()=>new after.URL('/x','another.example'),e=>e.name==='TypeError');assert.strictEqual(new after.URL('/x','https://secure.example').href,'https://secure.example/x');console.log('PASS bare-base original failure, exact HTTP config restored, unrelated options/HTTPS/native URL invalid-input errors preserved');
'''
java=pathlib.Path(os.environ['JAVA_HOME'])/'bin'
with tempfile.TemporaryDirectory() as tmp:
 d=pathlib.Path(tmp);(d/'HuangshanServerTest.java').write_text(runner)
 for captured in [False]+([True] if len(sys.argv)>1 else []):
  body=pathlib.Path(sys.argv[1]).read_bytes() if captured else fixture.encode()
  code=source if captured else source.replace(FINGERPRINT,hashlib.sha256(body).hexdigest())
  (d/'LegacyScriptSourceAdapter.java').write_text(code);(d/'input.js').write_bytes(body)
  subprocess.run([str(java/'javac'),'-source','1.7','-target','1.7','-d',str(d),str(d/'LegacyScriptSourceAdapter.java'),str(d/'HuangshanServerTest.java')],check=True)
  output=pathlib.Path(sys.argv[2]).resolve() if captured and len(sys.argv)>2 else d/'output.js'
  subprocess.run([str(java/'java'),'-cp',str(d),'xiao.bu.tv.HuangshanServerTest',str(d/'input.js'),str(output)],check=True)
  if not captured:subprocess.run(['node','-e',semantics,str(d/'input.js'),str(output)],check=True)
  print('PASS Huangshan server guards:', 'production source with local capture' if captured else 'synthetic fixture with test-only fingerprint')
